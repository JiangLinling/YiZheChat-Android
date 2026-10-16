/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav.loggedin

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.DeviceRecipientRegistrySnapshot
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.MatrixContentProtectionBinding
import io.element.android.security.contentprotection.api.MatrixContentProtectionStatus
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountIdentityContinuity
import io.element.android.security.dataprotection.api.AccountReactivationAttempt
import io.element.android.security.dataprotection.api.AccountReactivationCheckpoint
import io.element.android.security.dataprotection.api.AccountReactivationCheckpointState
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.accountIdHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest

class AccountReactivationCoordinatorTest {
    @Test
    fun `retained checks and descriptor run off caller then return to caller in original order`() = runTest {
        val caller = Thread.currentThread()
        val calls = mutableListOf<String>()
        val workers = mutableListOf<Thread>()
        val management = mockk<ManagementControlService>()
        coEvery { management.localListEntryStatus() } coAnswers {
            calls += "permission"
            workers += Thread.currentThread()
            listPermission()
        }
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } coAnswers {
            calls += "checkpoint"
            workers += Thread.currentThread()
            AccountReactivationCheckpoint(attempt = attempt(), state = AccountReactivationCheckpointState.COMMITTED, receipt = receipt())
        }
        val fixture = fixture(management, scopeStore)
        coEvery { fixture.shareKeyService.descriptor() } coAnswers {
            calls += "descriptor"
            workers += Thread.currentThread()
            shareKey()
        }

        assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isTrue()
        assertThat(Thread.currentThread()).isSameInstanceAs(caller)
        assertThat(calls).containsExactly("permission", "checkpoint", "descriptor").inOrder()
        workers.forEach { assertThat(it).isNotSameInstanceAs(caller) }
        coVerify(exactly = 0) { management.synchronize() }
    }

    @Test
    fun `cancelling a retained checkpoint read cannot return permission or read the descriptor`() = runTest {
        val management = mockk<ManagementControlService>()
        coEvery { management.localListEntryStatus() } returns listPermission()
        val entered = CompletableDeferred<Unit>()
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } coAnswers {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val fixture = fixture(management, scopeStore)
        val read = async { fixture.coordinator.canRestoreLocalList(fixture.matrixClient) }
        entered.await()
        read.cancel()
        assertThat(runCatching { read.await() }.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        read.join()
        coVerify(exactly = 0) { fixture.shareKeyService.descriptor() }
        coVerify(exactly = 0) { management.synchronize() }
    }

    @Test
    fun `vault locked while checkpoint read is pending remains denied on the worker`() = runTest {
        val management = mockk<ManagementControlService>()
        coEvery { management.localListEntryStatus() } returns listPermission()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } coAnswers {
            entered.complete(Unit)
            resume.await()
            AccountReactivationCheckpoint(attempt = attempt(), state = AccountReactivationCheckpointState.COMMITTED, receipt = receipt())
        }
        val fixture = fixture(management, scopeStore)
        val read = async { fixture.coordinator.canRestoreLocalList(fixture.matrixClient) }
        entered.await()
        coEvery { fixture.vaultKeyService.isUnlocked(any()) } returns false
        resume.complete(Unit)
        assertThat(read.await()).isFalse()
        coVerify(exactly = 0) { fixture.shareKeyService.descriptor() }
        coVerify(exactly = 0) { management.synchronize() }
    }

    private fun listPermission() = io.element.android.security.dataprotection.api.LocalChatListPermission(
        POLICY_VERSION, 7, 5_000,
        listOf(io.element.android.security.dataprotection.api.OfflineEntryAccountBinding(ACCOUNT_HASH, MATRIX_DEVICE_HASH, 1, ACTIVATION_ID)),
    )

    @Test
    fun `retained list requires exact committed identity and cannot perform online activation`() = runTest {
        val management = mockk<ManagementControlService>()
        val entry = io.element.android.security.dataprotection.api.LocalChatListPermission(
            POLICY_VERSION, 7, 5_000,
            listOf(io.element.android.security.dataprotection.api.OfflineEntryAccountBinding(ACCOUNT_HASH, MATRIX_DEVICE_HASH, 1, ACTIVATION_ID)),
        )
        coEvery { management.localListEntryStatus() } returns entry
        val scopeStore = mockk<AccountReactivationScopeStore>()
        val checkpoint = AccountReactivationCheckpoint(attempt = attempt(), state = AccountReactivationCheckpointState.COMMITTED, receipt = receipt())
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint
        val fixture = fixture(management, scopeStore)
        assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isTrue()
        for (invalid in listOf(
            checkpoint.copy(pendingExplicitIdentityReset = true),
            checkpoint.copy(state = AccountReactivationCheckpointState.ALLOCATED),
            checkpoint.copy(receipt = receipt().copy(accountMobileGeneration = 2)),
            checkpoint.copy(receipt = receipt().copy(policyVersion = POLICY_VERSION + 1)),
            checkpoint.copy(receipt = receipt().copy(activationId = "different-activation")),
        )) {
            coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns invalid
            assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isFalse()
        }
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint
        coEvery { management.localListEntryStatus() } returns entry.copy(remainingMillis = 0)
        assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isFalse()
        coEvery { management.localListEntryStatus() } returns entry.copy(accountBindings = emptyList())
        assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isFalse()
        coEvery { management.localListEntryStatus() } returns null
        assertThat(fixture.coordinator.canRestoreLocalList(fixture.matrixClient)).isFalse()
        coVerify(exactly = 0) { management.synchronize() }
    }

    @Test
    fun `offline restoration requires matching committed CORE receipt and active entry grant`() = runTest {
        val management = mockk<ManagementControlService>()
        coEvery { management.offlineEntryStatus() } returns io.element.android.security.dataprotection.api.OfflineEntryStatus(
            POLICY_VERSION,
            10_000,
            5_000,
            listOf(io.element.android.security.dataprotection.api.OfflineEntryAccountBinding(ACCOUNT_HASH, MATRIX_DEVICE_HASH, 1, ACTIVATION_ID))
        )
        val scopeStore = mockk<AccountReactivationScopeStore>()
        val checkpoint = AccountReactivationCheckpoint(attempt = attempt(), state = AccountReactivationCheckpointState.COMMITTED, receipt = receipt())
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint
        val fixture = fixture(management, scopeStore)
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isTrue()
        for (mismatched in listOf(
            receipt().copy(accountIdHash = "f".repeat(64)),
            receipt().copy(matrixDeviceIdHash = "e".repeat(64)),
            receipt().copy(installationId = "different-installation-background"),
            receipt().copy(activationId = "different-activation-background"),
            receipt().copy(attestationVersion = 8),
            receipt().copy(recipientKeyId = "different-recipient-background"),
        )) {
            coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint.copy(receipt = mismatched)
            assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        }
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint
        coEvery { fixture.vaultKeyService.isUnlocked(any()) } returns false
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        coEvery { fixture.vaultKeyService.isUnlocked(any()) } returns true
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isTrue()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns
            checkpoint.copy(receipt = receipt().copy(policyVersion = POLICY_VERSION + 1))
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint.copy(pendingExplicitIdentityReset = true)
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns
            checkpoint.copy(receipt = receipt().copy(accountMobileGeneration = 2))
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        coEvery { management.offlineEntryStatus() } returns null
        assertThat(fixture.coordinator.canRestoreOffline(fixture.matrixClient)).isFalse()
        coVerify(exactly = 0) { management.synchronize() }
    }

    @Test
    fun `navigation enforcement classification is bound to account installation and Matrix device`() = runTest {
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery {
            scopeStore.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        } returns true
        val fixture = fixture(mockk(relaxed = true), scopeStore)

        assertThat(fixture.coordinator.requiresCoreReadyEnforcement(fixture.matrixClient).getOrThrow()).isTrue()

        coVerify(exactly = 1) {
            scopeStore.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        }
    }

    @Test
    fun `unauthenticated reactivation boundary classification is not converted to fresh login`() = runTest {
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.requiresCoreReadyEnforcement(any(), any(), any()) } throws
            IllegalStateException("checkpoint authentication failed")
        val fixture = fixture(mockk(relaxed = true), scopeStore)

        assertThat(fixture.coordinator.requiresCoreReadyEnforcement(fixture.matrixClient).isFailure).isTrue()
    }

    @Test
    fun `pending explicit reset is discoverable after process recreation`() = runTest {
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns
            AccountReactivationCheckpoint(
                attempt = attempt(),
                state = AccountReactivationCheckpointState.ALLOCATED,
                reactivationRequired = true,
                pendingExplicitIdentityReset = true,
            )
        val fixture = fixture(mockk(relaxed = true), scopeStore)

        assertThat(fixture.coordinator.hasPendingExplicitIdentityReset(fixture.matrixClient).getOrThrow()).isTrue()
    }

    @Test
    fun `checkpoint is durable before prepare and exact scope reaches commit`() = runTest {
        val calls = mutableListOf<String>()
        val attempt = attempt()
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns null
        coEvery { scopeStore.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } coAnswers {
            calls += "checkpoint"
            attempt
        }
        val preparedCheckpointSlot = slot<PreparedAccountReactivation>()
        coEvery { scopeStore.markPrepared(attempt, capture(preparedCheckpointSlot)) } coAnswers {
            calls += "prepared-checkpoint"
            AccountReactivationCheckpoint(
                attempt = attempt,
                state = AccountReactivationCheckpointState.PREPARED,
                prepared = preparedCheckpointSlot.captured,
            )
        }
        val committedReceiptSlot = slot<AccountActivationReceipt>()
        coEvery { scopeStore.markCommitted(attempt, capture(committedReceiptSlot)) } coAnswers {
            calls += "committed-checkpoint"
            AccountReactivationCheckpoint(
                attempt = attempt,
                state = AccountReactivationCheckpointState.COMMITTED,
                prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE),
                receipt = committedReceiptSlot.captured,
            )
        }
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        val requestSlot = slot<io.element.android.security.dataprotection.api.AccountReactivationRequest>()
        coEvery { management.prepareAccountReactivation(capture(requestSlot)) } coAnswers {
            calls += "prepare"
            val request = requestSlot.captured
            assertThat(request.clientScopeId).isEqualTo(attempt.clientScopeId)
            assertThat(request.clientGeneration).isEqualTo(attempt.clientGeneration)
            assertThat(request.idempotencyKey).isEqualTo(attempt.idempotencyKey)
            PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE)
        }
        coEvery { management.commitAccountReactivation(any(), any()) } coAnswers {
            calls += "commit"
            receipt()
        }
        val fixture = fixture(management, scopeStore)

        val result = fixture.coordinator.activate(fixture.matrixClient)

        assertThat(result.getOrThrow()).isEqualTo(receipt())
        assertThat(calls).containsExactly(
            "checkpoint",
            "prepare",
            "prepared-checkpoint",
            "commit",
            "committed-checkpoint",
        ).inOrder()
    }

    @Test
    fun `prepared transaction is resumed after process recreation without allocating another generation`() = runTest {
        val attempt = attempt()
        val prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE)
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns
            AccountReactivationCheckpoint(attempt, AccountReactivationCheckpointState.PREPARED, prepared)
        coEvery { scopeStore.markCommitted(attempt, any()) } returns
            AccountReactivationCheckpoint(attempt, AccountReactivationCheckpointState.COMMITTED, prepared, receipt())
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        coEvery { management.commitAccountReactivation(any(), prepared) } returns receipt()
        val fixture = fixture(management, scopeStore)

        assertThat(fixture.coordinator.activate(fixture.matrixClient).getOrThrow()).isEqualTo(receipt())

        coVerify(exactly = 0) { scopeStore.nextAttempt(any(), any(), any()) }
        coVerify(exactly = 0) { management.prepareAccountReactivation(any()) }
        coVerify(exactly = 1) { management.commitAccountReactivation(any(), prepared) }
    }

    @Test
    fun `server commit is durably finalized when navigation cancels the activation`() = runTest {
        val attempt = attempt()
        val prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE)
        val markEntered = CompletableDeferred<Unit>()
        val allowMarkToFinish = CompletableDeferred<Unit>()
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns
            AccountReactivationCheckpoint(attempt, AccountReactivationCheckpointState.PREPARED, prepared)
        coEvery { scopeStore.markCommitted(attempt, any()) } coAnswers {
            markEntered.complete(Unit)
            allowMarkToFinish.await()
            AccountReactivationCheckpoint(
                attempt = attempt,
                state = AccountReactivationCheckpointState.COMMITTED,
                prepared = prepared,
                receipt = receipt(),
            )
        }
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        coEvery { management.commitAccountReactivation(any(), prepared) } returns receipt()
        val fixture = fixture(management, scopeStore)

        val activation = async { fixture.coordinator.activate(fixture.matrixClient) }
        markEntered.await()
        activation.cancel()
        allowMarkToFinish.complete(Unit)
        activation.join()

        coVerify(exactly = 1) { scopeStore.markCommitted(attempt, receipt()) }
    }

    @Test
    fun `committed checkpoint is reused only after current prerequisites are rechecked`() = runTest {
        val checkpoint = AccountReactivationCheckpoint(
            attempt = attempt(),
            state = AccountReactivationCheckpointState.COMMITTED,
            prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE),
            receipt = receipt(),
        )
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns checkpoint
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        val fixture = fixture(management, scopeStore)

        assertThat(fixture.coordinator.activate(fixture.matrixClient).getOrThrow()).isEqualTo(receipt())

        coVerify(exactly = 0) { scopeStore.nextAttempt(any(), any(), any()) }
        coVerify(exactly = 0) { management.prepareAccountReactivation(any()) }
        coVerify(exactly = 0) { management.commitAccountReactivation(any(), any()) }
    }

    @Test
    fun `failed security prerequisite cannot allocate or publish a generation`() = runTest {
        val scopeStore = mockk<AccountReactivationScopeStore>(relaxed = true)
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        val fixture = fixture(management, scopeStore, kmsAvailable = false)

        val result = fixture.coordinator.activate(fixture.matrixClient)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { scopeStore.nextAttempt(any(), any(), any()) }
        coVerify(exactly = 0) { management.prepareAccountReactivation(any()) }
    }

    @Test
    fun `ordinary reactivation remains fail closed when the recipient registry is unavailable`() = runTest {
        val scopeStore = mockk<AccountReactivationScopeStore>(relaxed = true)
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        val fixture = fixture(
            management = management,
            scopeStore = scopeStore,
            recipientRegistryAvailable = false,
        )

        val result = fixture.coordinator.activate(fixture.matrixClient)

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        coVerify(exactly = 1) { fixture.recipientRegistry.approvedRecipients(ACCOUNT_HASH) }
        coVerify(exactly = 0) { scopeStore.nextAttempt(any(), any(), any()) }
        coVerify(exactly = 0) { management.prepareAccountReactivation(any()) }
        coVerify(exactly = 0) { management.commitAccountReactivation(any(), any()) }
    }

    @Test
    fun `explicit identity reset commits before the unavailable old KMS boundary is checked`() = runTest {
        val resetAttempt = attempt().copy(clientGeneration = 5)
        val resetCheckpoint = AccountReactivationCheckpoint(
            attempt = resetAttempt,
            state = AccountReactivationCheckpointState.ALLOCATED,
            reactivationRequired = true,
            pendingExplicitIdentityReset = true,
        )
        val resetReceipt = receipt(
            attempt = resetAttempt,
            identityContinuity = AccountIdentityContinuity.RESET_CONFIRMED,
        )
        val scopeStore = mockk<AccountReactivationScopeStore>()
        coEvery { scopeStore.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) } returns resetCheckpoint
        val preparedSlot = slot<PreparedAccountReactivation>()
        coEvery { scopeStore.markPrepared(resetAttempt, capture(preparedSlot)) } answers {
            resetCheckpoint.copy(
                state = AccountReactivationCheckpointState.PREPARED,
                prepared = preparedSlot.captured,
            )
        }
        coEvery { scopeStore.markCommitted(resetAttempt, resetReceipt) } returns resetCheckpoint.copy(
            state = AccountReactivationCheckpointState.COMMITTED,
            prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE),
            receipt = resetReceipt,
            reactivationRequired = false,
            pendingExplicitIdentityReset = false,
        )
        val management = mockk<ManagementControlService>()
        coEvery { management.synchronize() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = POLICY_VERSION,
        )
        val requestSlot = slot<io.element.android.security.dataprotection.api.AccountReactivationRequest>()
        coEvery { management.prepareAccountReactivation(capture(requestSlot)) } returns
            PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE)
        coEvery { management.commitAccountReactivation(any(), any()) } returns resetReceipt
        val fixture = fixture(
            management = management,
            scopeStore = scopeStore,
            kmsAvailable = false,
            recipientRegistryAvailable = false,
        )

        assertThat(fixture.coordinator.activate(fixture.matrixClient).getOrThrow()).isEqualTo(resetReceipt)
        assertThat(requestSlot.captured.explicitIdentityResetConfirmed).isTrue()
        coVerify(exactly = 0) { fixture.recipientRegistry.approvedRecipients(any<String>()) }
        coVerify(exactly = 1) { management.prepareAccountReactivation(any()) }
        coVerify(exactly = 1) { management.commitAccountReactivation(any(), any()) }
    }

    private fun fixture(
        management: ManagementControlService,
        scopeStore: AccountReactivationScopeStore,
        kmsAvailable: Boolean = true,
        recipientRegistryAvailable: Boolean = true,
    ): Fixture {
        val trust = mockk<DeviceTrustService>()
        coEvery { trust.current() } returns DeviceTrustSnapshot(
            installationId = INSTALLATION_ID,
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = 7,
            lastVerifiedAtEpochMillis = 1,
        )
        val vault = mockk<VaultKeyService>()
        coEvery { vault.isUnlocked(any()) } returns true
        val shareKeyService = mockk<DeviceShareKeyService>()
        coEvery { shareKeyService.descriptor() } returns shareKey()
        val kms = mockk<KmsAuthorizationProvider>()
        coEvery { kms.isAvailable() } returns kmsAvailable
        val recipientRegistry = mockk<DeviceRecipientRegistry>()
        if (recipientRegistryAvailable) {
            coEvery { recipientRegistry.approvedRecipients(ACCOUNT_HASH) } returns DeviceRecipientRegistrySnapshot(
                accountIdHash = ACCOUNT_HASH,
                issuedAtEpochMillis = 1,
                expiresAtEpochMillis = Long.MAX_VALUE,
                recipients = listOf(recipient()),
            )
        } else {
            coEvery { recipientRegistry.approvedRecipients(ACCOUNT_HASH) } throws ContentProtectionException.KmsUnavailable()
        }
        val binding = mockk<MatrixContentProtectionBinding>()
        every { binding.status() } returns MatrixContentProtectionStatus.READY
        coEvery { binding.recipientsApproved(any(), any()) } returns true
        val policyProvider = mockk<DataProtectionPolicyProvider>()
        every { policyProvider.current() } returns ActiveDataProtectionPolicy(policy(), DataProtectionPolicySource.VERIFIED_REMOTE)
        val matrixClient = FakeMatrixClient(A_SESSION_ID, A_DEVICE_ID)
        return Fixture(
            coordinator = AccountReactivationCoordinator(
                managementControlService = management,
                deviceTrustService = trust,
                vaultKeyService = vault,
                deviceShareKeyService = shareKeyService,
                kmsAuthorizationProvider = kms,
                deviceRecipientRegistry = recipientRegistry,
                matrixContentProtectionBinding = binding,
                dataProtectionPolicyProvider = policyProvider,
                accountReactivationScopeStore = scopeStore,
            ),
            matrixClient = matrixClient,
            recipientRegistry = recipientRegistry,
            shareKeyService = shareKeyService,
            vaultKeyService = vault,
        )
    }

    private fun policy() = DataProtectionPolicyV1(
        policyId = "policy-reactivation-test",
        version = POLICY_VERSION,
        issuedAtEpochMillis = 1,
        effectiveAtEpochMillis = 1,
        expiresAtEpochMillis = null,
        contentRetentionMillis = 30 * DAY_MILLIS,
        maximumOfflineMillis = DAY_MILLIS,
        offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
        notificationExposure = NotificationExposure.SENDER_ONLY,
        hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
        allowContentExport = false,
        allowClipboard = false,
        allowExternalShare = false,
        screenCapturePolicy = ScreenCapturePolicy.BLOCK,
    )

    private fun attempt() = AccountReactivationAttempt(
        accountIdHash = ACCOUNT_HASH,
        installationId = INSTALLATION_ID,
        matrixDeviceIdHash = MATRIX_DEVICE_HASH,
        clientScopeId = "scope-reactivation-test-000001",
        clientGeneration = 4,
        scopeStartedAtEpochMillis = 1234,
        idempotencyKey = "idempotency-reactivation-test-000001",
    )

    private fun shareKey() = DeviceShareKeyDescriptor(
        installationId = INSTALLATION_ID,
        keyId = SHARE_KEY_ID,
        algorithm = "RSA-OAEP-3072-SHA256-MGF1SHA1",
        publicKeyDer = byteArrayOf(1, 2, 3),
        hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
    )

    private fun recipient() = DeviceShareRecipient(
        installationId = INSTALLATION_ID,
        keyId = SHARE_KEY_ID,
        algorithm = "RSA-OAEP-3072-SHA256-MGF1SHA1",
        publicKeyDer = byteArrayOf(1, 2, 3),
        allowsBoundedOffline = true,
    )

    private fun receipt(
        attempt: AccountReactivationAttempt = attempt(),
        identityContinuity: AccountIdentityContinuity = AccountIdentityContinuity.FIRST_ACTIVATION,
    ) = AccountActivationReceipt(
        activationId = ACTIVATION_ID,
        accountIdHash = ACCOUNT_HASH,
        installationId = INSTALLATION_ID,
        matrixDeviceIdHash = MATRIX_DEVICE_HASH,
        clientScopeId = attempt.clientScopeId,
        clientGeneration = attempt.clientGeneration,
        attestationVersion = 7,
        policyVersion = POLICY_VERSION,
        matrixCrossSigningKeyHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        recipientKeyId = SHARE_KEY_ID,
        identityContinuity = identityContinuity,
        committedAtEpochMillis = 5678,
        accountMobileGeneration = 1,
    )

    private data class Fixture(
        val coordinator: AccountReactivationCoordinator,
        val matrixClient: FakeMatrixClient,
        val recipientRegistry: DeviceRecipientRegistry,
        val shareKeyService: DeviceShareKeyService,
        val vaultKeyService: VaultKeyService,
    )

    private companion object {
        const val INSTALLATION_ID = "installation-reactivation-test-0001"
        const val SHARE_KEY_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val ACTIVATION_ID = "activation-reactivation-test-0001"
        const val POLICY_VERSION = 9L
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        val ACCOUNT_HASH = accountIdHash(A_SESSION_ID.value)
        val MATRIX_DEVICE_HASH = MessageDigest.getInstance("SHA-256")
            .digest(A_DEVICE_ID.value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
