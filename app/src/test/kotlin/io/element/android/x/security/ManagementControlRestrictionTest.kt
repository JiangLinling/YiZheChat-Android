/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionRecoveryReport
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.ManagementCommandExecutionReport
import io.element.android.security.dataprotection.api.ManagementCommandState
import io.element.android.security.dataprotection.api.ManagementCommandType
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementHeartbeatReport
import io.element.android.security.dataprotection.api.ManagementHeartbeatStatus
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ManagementControlRestrictionTest {
    @Test
    fun `signed partial registration reaches startup exit without a false local access restriction`() = runTest {
        val fixture = Fixture()
        fixture.report = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 13,
            deviceSecurityState = io.element.android.security.dataprotection.api.ManagedDeviceSecurityState.READY,
            errorCode = "REGISTRATION_PENDING")
        val coordinator = fixture.coordinator(backgroundScope, testCoroutineDispatchers())
        val readiness = coordinator.awaitStartupReadiness(System.currentTimeMillis() + 10_000)
        assertWithMessage("startup reason=%s", readiness.reasonCode).that(readiness.state)
            .isEqualTo(StartupSecurityReadinessState.READY_FOR_LC01)
        assertThat(readiness.reasonCode).isEqualTo("REGISTRATION_PENDING")
        assertThat(coordinator.localEntryRestriction.value).isNull()
        assertThat(fixture.sessions.getAllSessions()).hasSize(1)
    }
    @Test
    fun `global replacement waits for original same account cleanup across multiple command replacements`() = runTest {
        for (oldId in listOf("original-command", "two-replacements-before-current")) {
            val fixture = Fixture()
            fixture.sessions.removeSession(fixture.sessions.getAllSessions().single().userId)
            val account = "a".repeat(64)
            val command = fixture.clearCommand(account)
            fixture.commands = listOf(command)
            val pending = DeletionReport(DeletionOperationId("managed-$oldId-${account.take(12)}"),
                setOf(DeletionStep.REQUESTED), true, accountIdHash = account)
            coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(listOf(pending), 0)
            val coordinator = fixture.coordinator(backgroundScope)
            coordinator.refreshSecurityAccess()
            coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.WAITING_RESTART }) }
            coVerify(exactly = 0) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }
            coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(listOf(
                pending.copy(completedSteps = setOf(DeletionStep.COMPLETE), retryRequired = false),
            ), 0)
            coordinator.refreshSecurityAccess()
            coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }
            assertThat(fixture.operational.startedRequests.none { it.lifecycleId == FullAppLifecycleId.LC_22 }).isTrue()
            assertThat(fixture.operational.clientReceipts).isEmpty()
            coVerify(exactly = 0) { fixture.vaultKeys.destroy(any(), any()) }
        }
    }

    @Test
    fun `global cleanup does not infer full account ownership from a shared twelve character prefix`() = runTest {
        for (knownAccount in listOf("a".repeat(12) + "b".repeat(52), null)) {
            val fixture = Fixture()
            fixture.sessions.removeSession(fixture.sessions.getAllSessions().single().userId)
            val account = "a".repeat(64)
            fixture.commands = listOf(fixture.clearCommand(account))
            val pending = DeletionReport(DeletionOperationId("managed-earlier-command-${account.take(12)}"),
                setOf(DeletionStep.REQUESTED), true, accountIdHash = knownAccount)
            coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(listOf(pending), 0)
            fixture.coordinator(backgroundScope).refreshSecurityAccess()
            val expected = if (knownAccount == null) ManagementCommandState.WAITING_RESTART else ManagementCommandState.COMPLETE
            coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == expected }) }
            val forbidden = if (knownAccount == null) ManagementCommandState.COMPLETE else ManagementCommandState.WAITING_RESTART
            coVerify(exactly = 0) { fixture.service.reportCommand(match { it.state == forbidden }) }
        }
    }

    @Test
    fun `ordinary scoped clear still ignores another login cleanup on the same account`() = runTest {
        val fixture = Fixture()
        val replacement = fixture.sessions.getAllSessions().single()
        val account = MessageDigest.getInstance("SHA-256").digest(replacement.userId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fixture.commands = listOf(fixture.clearCommand(account, global = false))
        coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(listOf(
            DeletionReport(DeletionOperationId("another-login-original-journal"), setOf(DeletionStep.REQUESTED), true,
                accountIdHash = account),
        ), 0)
        fixture.coordinator(backgroundScope).refreshSecurityAccess()
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }
        assertThat(fixture.sessions.getAllSessions()).containsExactly(replacement)
        coVerify(exactly = 0) { fixture.vaultKeys.destroy(any(), any()) }
    }

    @Test
    fun `global unreadable journal never becomes completed command report`() = runTest {
        val fixture = Fixture()
        fixture.sessions.removeSession(fixture.sessions.getAllSessions().single().userId)
        fixture.commands = listOf(fixture.clearCommand("a".repeat(64)))
        coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(emptyList(), 1)
        fixture.coordinator(backgroundScope).refreshSecurityAccess()
        coVerify(exactly = 0) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.FAILED }) }
    }

    @Test
    fun `local clear hides only its exact account immediately while original files remain pending`() = runTest {
        val fixture = Fixture()
        val target = fixture.sessions.getAllSessions().single()
        val other = target.copy(userId = "@other:test", deviceId = "OTHER")
        fixture.sessions.addSession(other)
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val command = VerifiedManagementCommand(
            commandId = "local-admin-clear-original-command", installationId = fixture.trust.current().installationId,
            type = ManagementCommandType.CLEAR_ACCOUNT, accountIdHash = hash(target.userId), contentId = null,
            version = 3, issuedAtEpochMillis = 1, notBeforeEpochMillis = 1, expiresAtEpochMillis = Long.MAX_VALUE,
            reasonCode = "ADMIN_LOCAL_ACCOUNT_CLEAR", targetMatrixDeviceIdHash = hash(target.deviceId), mobileGeneration = 1,
        )
        fixture.commands = listOf(command)
        coEvery { fixture.deletions.prepare(any()) } answers {
            DeletionReport(firstArg<io.element.android.security.dataprotection.api.DeletionRequest>().operationId,
                setOf(DeletionStep.REQUESTED), true)
        }
        coEvery { fixture.vaultKeys.destroy(any(), any()) } returns true
        fixture.coordinator(backgroundScope).refreshSecurityAccess()
        assertThat(fixture.sessions.getAllSessions().first { it.userId == target.userId }.isTokenValid).isFalse()
        assertThat(fixture.sessions.getAllSessions().first { it.userId == other.userId }).isEqualTo(other)
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.WAITING_RESTART }) }
        coVerify(exactly = 1) { fixture.deletions.prepare(match { it.operationId.value.startsWith("managed-${command.commandId}-") }) }
        assertThat(fixture.operational.clientReceipts).isEmpty()
        assertThat(fixture.operational.startedRequests.single { it.lifecycleId == FullAppLifecycleId.LC_22 }.trigger)
            .isEqualTo("remove_local_account")
    }

    @Test
    fun `signed global child clears local login without inventing another global operation`() = runTest {
        val fixture = Fixture()
        val session = fixture.sessions.getAllSessions().single()
        val account = MessageDigest.getInstance("SHA-256").digest(session.userId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fixture.commands = listOf(VerifiedManagementCommand(
            commandId = "global-original-client-clear", installationId = fixture.trust.current().installationId,
            type = ManagementCommandType.CLEAR_ACCOUNT, accountIdHash = account, contentId = null,
            version = 3, issuedAtEpochMillis = 1, notBeforeEpochMillis = 1, expiresAtEpochMillis = Long.MAX_VALUE,
            reasonCode = "GLOBAL_ACCOUNT_DESTROY", globalDestroyOperationId = "original-global-root-00001",
        ))
        coEvery { fixture.deletions.prepare(any()) } answers {
            DeletionReport(firstArg<io.element.android.security.dataprotection.api.DeletionRequest>().operationId,
                setOf(DeletionStep.REQUESTED), true)
        }
        coEvery { fixture.vaultKeys.destroy(any(), any()) } returns true
        fixture.coordinator(backgroundScope).refreshSecurityAccess()
        assertThat(fixture.sessions.getAllSessions().single().isTokenValid).isFalse()
        assertThat(fixture.operational.startedRequests.none { it.lifecycleId == FullAppLifecycleId.LC_22 }).isTrue()
        assertThat(fixture.operational.clientReceipts).isEmpty()
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.WAITING_RESTART }) }
    }

    @Test
    fun `scoped handover keeps its original cleanup intent and waits for actual local completion`() = runTest {
        val fixture = Fixture()
        val replacement = fixture.sessions.getAllSessions().single()
        val account = MessageDigest.getInstance("SHA-256").digest(replacement.userId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val command = VerifiedManagementCommand(
            commandId = "scoped-original-handover-command",
            installationId = fixture.trust.current().installationId,
            type = ManagementCommandType.CLEAR_ACCOUNT,
            accountIdHash = account,
            contentId = null,
            version = 2,
            issuedAtEpochMillis = 1,
            notBeforeEpochMillis = 1,
            expiresAtEpochMillis = Long.MAX_VALUE,
            reasonCode = "ACCOUNT_MOBILE_SUPERSEDED",
            targetMatrixDeviceIdHash = "f".repeat(64),
            mobileGeneration = 1,
        )
        fixture.commands = listOf(command)
        coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(
            operationReports = listOf(DeletionReport(
                operationId = DeletionOperationId("managed-${command.commandId}-${account.take(12)}"),
                completedSteps = setOf(DeletionStep.REQUESTED),
                retryRequired = true,
            )),
            unreadableJournalEntries = 0,
        )
        val coordinator = fixture.coordinator(backgroundScope)
        coordinator.refreshSecurityAccess()
        assertThat(fixture.operational.clientReceipts).isEmpty()
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.WAITING_RESTART }) }
        assertThat(fixture.sessions.getAllSessions()).containsExactly(replacement)
        assertThat(coordinator.localEntryRestriction.value).isNull()

        coEvery { fixture.deletions.resumePending(any()) } returns DeletionRecoveryReport(emptyList(), 0)
        coordinator.refreshSecurityAccess()
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }
        val requests = fixture.operational.startedRequests
        assertThat(requests.map { it.operationId }.distinct()).hasSize(1)
        assertThat(requests.all { it.trigger == "remove_local_account" && it.accountIdHash == account &&
            it.matrixDeviceIdHash == command.targetMatrixDeviceIdHash && it.eventIdHash == null &&
            it.operationId.startsWith("managed-session-exit-") }).isTrue()
        assertThat(fixture.operational.clientReceipts.map { it.component }).containsExactly(
            io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_FRONTEND,
            io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE,
        )
        assertThat(fixture.sessions.getAllSessions()).containsExactly(replacement)
        coVerify(exactly = 0) { fixture.vaultKeys.destroy(any(), any()) }
    }

    @Test
    fun `cold administrative lock keeps one foreground control owner until verified unlock`() = runTest {
        val fixture = Fixture()
        fixture.trust.applyRemoteRestriction(DeviceTrustState.STALE, 2)
        fixture.report = fixture.lockedReport()
        val coordinator = fixture.coordinator(backgroundScope, testCoroutineDispatchers())
        assertThat(coordinator.awaitStartupReadiness(System.currentTimeMillis() + 10_000).state)
            .isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        coordinator.onStart(mockk())
        coordinator.onStart(mockk())
        runCurrent()
        assertThat(coordinator.startupReadiness.value.reasonCode).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
        assertThat(coordinator.startupReadiness.value.installationId).isEqualTo(fixture.trust.current().installationId)
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")

        advanceTimeBy(67_000)
        runCurrent()
        coVerify(exactly = 1) { fixture.service.heartbeat() }
        assertThat(fixture.operational.startedRequests.count { it.lifecycleId == FullAppLifecycleId.LC_07 }).isEqualTo(1)
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
        assertThat(coordinator.verifiedOnlineReadiness()).isNull()
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()

        fixture.trust.applyRemoteTrust(attestationVersion = 3, verifiedAtEpochMillis = 3)
        fixture.report = ManagementSyncReport(
            ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = 13,
            deviceSecurityState = io.element.android.security.dataprotection.api.ManagedDeviceSecurityState.READY,
        )
        advanceTimeBy(67_000)
        runCurrent()
        coVerify(exactly = 2) { fixture.service.heartbeat() }
        assertThat(coordinator.localEntryRestriction.value).isNull()
        assertThat(fixture.sessions.getAllSessions()).hasSize(1)
        assertThat(coordinator.awaitStartupReadiness(System.currentTimeMillis() + 10_000).state)
            .isEqualTo(StartupSecurityReadinessState.READY_FOR_LC01)

        coordinator.onStop(mockk())
        advanceTimeBy(67_000)
        runCurrent()
        coVerify(exactly = 2) { fixture.service.heartbeat() }
    }

    @Test
    fun `other cold security rejections do not start a management heartbeat`() = runTest {
        val fixture = Fixture()
        fixture.trust.applyRemoteRestriction(DeviceTrustState.STALE, 2)
        fixture.report = fixture.lockedReport().copy(errorCode = "SECURITY_POLICY_REJECTED")
        val coordinator = fixture.coordinator(backgroundScope, testCoroutineDispatchers())
        assertThat(coordinator.awaitStartupReadiness(System.currentTimeMillis() + 10_000).state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        coordinator.onStart(mockk())
        runCurrent()
        advanceTimeBy(67_000)
        runCurrent()
        coVerify(exactly = 0) { fixture.service.heartbeat() }
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("SECURITY_POLICY_REJECTED")
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()
        coordinator.onStop(mockk())
    }

    @Test
    fun `administrative control loop stops after a different security rejection`() = runTest {
        val fixture = Fixture()
        fixture.trust.applyRemoteRestriction(DeviceTrustState.STALE, 2)
        fixture.report = fixture.lockedReport()
        val coordinator = fixture.coordinator(backgroundScope, testCoroutineDispatchers())
        assertThat(coordinator.awaitStartupReadiness(System.currentTimeMillis() + 10_000).state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        coordinator.onStart(mockk())
        runCurrent()
        fixture.report = fixture.lockedReport().copy(errorCode = "SECURITY_POLICY_REJECTED")
        advanceTimeBy(67_000)
        runCurrent()
        advanceTimeBy(67_000)
        runCurrent()
        coVerify(exactly = 1) { fixture.service.heartbeat() }
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("SECURITY_POLICY_REJECTED")
        coordinator.onStop(mockk())
    }

    @Test
    fun `fresh process retains signed administrative cause without a pending LOCK command`() = runTest {
        val fixture = Fixture()
        fixture.trust.applyRemoteRestriction(DeviceTrustState.STALE, 2)
        fixture.report = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            acceptedPolicyVersion = 13,
            errorCode = "DEVICE_ADMINISTRATIVELY_LOCKED",
            deviceSecurityState = io.element.android.security.dataprotection.api.ManagedDeviceSecurityState.ACTION_REQUIRED,
        )
        val coordinator = fixture.coordinator(backgroundScope)
        repeat(2) {
            assertThat(coordinator.refreshSecurityAccess().errorCode).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
            assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
            assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()
        }
        coVerify(exactly = 0) { fixture.service.reportCommand(any()) }
        assertThat(fixture.sessions.getAllSessions()).hasSize(1)
    }

    @Test
    fun `administrative lock survives later online sync and network retry until verified unlock`() = runTest {
        val fixture = Fixture()
        val coordinator = fixture.coordinator(backgroundScope)
        fixture.commands = listOf(
            VerifiedManagementCommand(
                commandId = "test-administrative-lock-command",
                installationId = fixture.trust.current().installationId,
                type = ManagementCommandType.LOCK,
                accountIdHash = null,
                contentId = null,
                version = 1,
                issuedAtEpochMillis = 1,
                notBeforeEpochMillis = 1,
                expiresAtEpochMillis = Long.MAX_VALUE,
            )
        )

        assertThat(coordinator.refreshSecurityAccess().status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()
        coVerify(exactly = 1) { fixture.service.reportCommand(match { it.state == ManagementCommandState.COMPLETE }) }

        // A successful transport exchange does not authorize a still-locked Vault.
        repeat(2) {
            assertThat(coordinator.refreshSecurityAccess().errorCode).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
            assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
        }
        fixture.report = ManagementSyncReport(ManagementSyncStatus.RETRY_REQUIRED)
        // A retry result alone is not evidence of a network transition.
        coordinator.onNetworkUnavailable()
        coordinator.refreshSecurityAccess()
        assertThat(coordinator.entryIsOffline.value).isTrue()
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()

        // Only a new accepted authority decision restores trust, followed by a successful refresh.
        fixture.trust.applyRemoteTrust(attestationVersion = 2, verifiedAtEpochMillis = 2)
        fixture.report = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 13)
        assertThat(coordinator.refreshSecurityAccess().status).isEqualTo(ManagementSyncStatus.SYNCHRONIZED)
        assertThat(coordinator.localEntryRestriction.value).isNull()
        assertThat(coordinator.entryIsOffline.value).isFalse()
        assertThat(fixture.sessions.getAllSessions()).hasSize(1)
    }

    @Test
    fun `ordinary offline expiry still hides entry and fresh security rejection takes precedence`() = runTest {
        val fixture = Fixture()
        val coordinator = fixture.coordinator(backgroundScope)
        fixture.report = ManagementSyncReport(ManagementSyncStatus.RETRY_REQUIRED)
        coordinator.refreshSecurityAccess()
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("OFFLINE_ENTRY_EXPIRED")
        assertThat(fixture.sessions.getAllSessions()).hasSize(1)

        fixture.report = ManagementSyncReport(ManagementSyncStatus.SECURITY_REJECTED, errorCode = "SECURITY_POLICY_REJECTED")
        coordinator.refreshSecurityAccess()
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("SECURITY_POLICY_REJECTED")
        assertThat(fixture.trust.current().allowsLocalVaultAccess).isFalse()
    }

    @Test
    fun `unexpired transport failure keeps its precise reason without claiming an expired grant`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.service.isOfflineDeadlineExceeded() } returns false
        fixture.report = ManagementSyncReport(ManagementSyncStatus.RETRY_REQUIRED, errorCode = "CONTROL_DNS_UNAVAILABLE")
        val coordinator = fixture.coordinator(backgroundScope)
        coordinator.refreshSecurityAccess()
        assertThat(coordinator.localEntryRestriction.value).isEqualTo("CONTROL_DNS_UNAVAILABLE")
    }

    private class Fixture {
        val operational = FakeAppOperationalStateCoordinator()
        val deletions = mockk<DataDeletionCoordinator>(relaxed = true)
        val vaultKeys = mockk<VaultKeyService>(relaxed = true)
        fun lockedReport() = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            acceptedPolicyVersion = 13,
            errorCode = "DEVICE_ADMINISTRATIVELY_LOCKED",
        )
        var report = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 13)
        var commands = emptyList<VerifiedManagementCommand>()
        val sessions = InMemorySessionStore(listOf(aSessionData(isTokenValid = true)))
        fun clearCommand(account: String, global: Boolean = true) = VerifiedManagementCommand(
            commandId = "latest-replacement-command", supersedesCommandId = "previous-replacement-command",
            installationId = "test-restriction-installation", type = ManagementCommandType.CLEAR_ACCOUNT,
            accountIdHash = account, contentId = null, version = 4, issuedAtEpochMillis = 1,
            notBeforeEpochMillis = 1, expiresAtEpochMillis = Long.MAX_VALUE,
            reasonCode = if (global) "GLOBAL_ACCOUNT_DESTROY" else "ADMIN_LOCAL_ACCOUNT_CLEAR",
            globalDestroyOperationId = if (global) "original-global-root-00001" else null,
            targetMatrixDeviceIdHash = if (global) null else "f".repeat(64), mobileGeneration = if (global) null else 1,
        )
        val trust = FakeDeviceTrustService(
            DeviceTrustSnapshot(
                installationId = "test-restriction-installation",
                hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                trustState = DeviceTrustState.TRUSTED,
                attestationVersion = 1,
                lastVerifiedAtEpochMillis = 1,
            )
        )
        val service = mockk<ManagementControlService>(relaxed = true) {
            coEvery { synchronize() } answers { report }
            coEvery { heartbeat() } returns ManagementHeartbeatReport(ManagementHeartbeatStatus.REAUTH_REQUIRED)
            coEvery { pendingCommands() } answers { commands }
            coEvery { offlineEntryStatus() } returns null
            coEvery { isOfflineDeadlineExceeded() } returns true
            coEvery { policyApplication() } returns null
            coEvery { reportCommand(any()) } answers {
                if (firstArg<ManagementCommandExecutionReport>().state == ManagementCommandState.COMPLETE) commands = emptyList()
                true
            }
        }
        val policy = ActiveDataProtectionPolicy(
            policy = DataProtectionPolicyV1(
                policyId = "test-online-policy",
                version = 13,
                issuedAtEpochMillis = 1,
                effectiveAtEpochMillis = 1,
                expiresAtEpochMillis = null,
                contentRetentionMillis = 86_400_000,
                maximumOfflineMillis = 0,
                offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
                notificationExposure = NotificationExposure.HIDDEN,
                hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
                allowContentExport = false,
                allowClipboard = false,
                allowExternalShare = false,
                screenCapturePolicy = ScreenCapturePolicy.BLOCK,
            ),
            source = DataProtectionPolicySource.VERIFIED_REMOTE,
        )

        fun coordinator(
            scope: kotlinx.coroutines.CoroutineScope,
            dispatchers: CoroutineDispatchers = CoroutineDispatchers.Default,
        ) = ManagementControlCoordinator(
            appCoroutineScope = scope,
            managementControlService = service,
            sessionStore = sessions,
            policyProvider = mockk<DataProtectionPolicyProvider> { every { current() } returns policy },
            dataDeletionCoordinator = deletions,
            deviceTrustService = trust,
            vaultKeyService = vaultKeys,
            deviceShareKeyService = mockk(relaxed = true),
            contentProtectionService = mockk(relaxed = true),
            secureMediaLeaseManager = mockk(relaxed = true),
            matrixContentProtectionRuntime = mockk(relaxed = true),
            notificationCleaner = mockk(relaxed = true),
            appForegroundStateService = mockk(relaxed = true) {
                every { hasRingingCall } returns MutableStateFlow(false)
                every { isInCall } returns MutableStateFlow(false)
            },
            appOperationalStateCoordinator = operational,
            coroutineDispatchers = dispatchers,
        )
    }
}
