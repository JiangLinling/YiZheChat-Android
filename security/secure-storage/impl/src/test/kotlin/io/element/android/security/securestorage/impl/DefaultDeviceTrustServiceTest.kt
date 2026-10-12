/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.DeviceRootKeyDescriptor
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticOutcome
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticSink
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticStage
import io.element.android.security.securestorage.api.DeviceRiskSignal
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultDeviceTrustServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `initialize creates no server trust and no attestation key`() = runTest {
        val fixture = fixture()

        val snapshot = fixture.service.initialize()

        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.NOT_ENROLLED)
        assertThat(snapshot.hardwareSecurityLevel).isEqualTo(HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT)
        assertThat(snapshot.installationId).hasLength(64)
        assertThat(fixture.attestationBackend.keyCount).isEqualTo(0)
    }

    @Test
    fun `enrollment emits challenge-bound evidence but remains pending server verification`() = runTest {
        val fixture = fixture()
        val challenge = ByteArray(32) { it.toByte() }

        val evidence = fixture.service.enroll(challenge)
        val snapshot = fixture.service.current()

        assertThat(evidence.challengeSha256).isEqualTo(sha256(challenge))
        assertThat(evidence.statement.copyOfRange(12, 44)).isEqualTo(sha256(evidence.installationId.encodeToByteArray()))
        assertThat(evidence.certificateChainDer).hasSize(2)
        assertThat(evidence.signedStatement).isEqualTo(sha256(evidence.statement))
        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.PENDING)
        assertThat(snapshot.remotelyTrusted).isFalse()
        assertThat(snapshot.attestationVersion).isEqualTo(1)
        assertThat(temporaryFolder.root.walkTopDown().filter(File::isFile).map(File::getName).toList())
            .containsAtLeast("installation-id.yzid", "device-root.yzdrk", "device-trust.yzdt")
    }

    @Test
    fun `durable trust record and DAK survive a process restart`() = runTest {
        val roots = roots()
        val rootBackend = TestDeviceRootKeyBackend()
        val attestationBackend = TestDeviceAttestationBackend()
        val platform = TestDevicePlatformSignalSource()
        val first = fixture(roots, rootBackend, attestationBackend, platform)
        first.service.enroll(ByteArray(32) { 1 })

        val restarted = fixture(roots, rootBackend, attestationBackend, platform)
        val snapshot = restarted.service.initialize()
        val refreshed = restarted.service.refresh(ByteArray(32) { 2 })

        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.PENDING)
        assertThat(refreshed.attestationVersion).isEqualTo(2)
        assertThat(attestationBackend.keyCount).isEqualTo(1)
    }

    @Test
    fun `blocking platform signal fails local Vault access closed`() = runTest {
        val platform = TestDevicePlatformSignalSource(
            risks = setOf(DeviceRiskSignal.ROOT_ARTIFACT_DETECTED),
        )
        val fixture = fixture(platformSignalSource = platform)

        assertThat(fixture.service.initialize().trustState).isEqualTo(DeviceTrustState.INTEGRITY_WARNING)
        fixture.service.enroll(ByteArray(32) { 3 })
        val snapshot = fixture.service.current()

        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.INTEGRITY_WARNING)
        assertThat(snapshot.allowsLocalVaultAccess).isFalse()
    }

    @Test
    fun `changed application signing identity is an integrity warning`() = runTest {
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(platformSignalSource = platform)
        fixture.service.enroll(ByteArray(32) { 4 })

        platform.signingCertificateSha256 = listOf(ByteArray(32) { 9 })
        val snapshot = fixture.service.current()

        assertThat(snapshot.riskSignals).contains(DeviceRiskSignal.APP_SIGNING_IDENTITY_CHANGED)
        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.INTEGRITY_WARNING)
    }

    @Test
    fun `copied trust records without the old DAK become permanently lost`() = runTest {
        val roots = roots()
        val rootBackend = TestDeviceRootKeyBackend()
        val firstBackend = TestDeviceAttestationBackend()
        val platform = TestDevicePlatformSignalSource()
        val first = fixture(roots, rootBackend, firstBackend, platform)
        first.service.enroll(ByteArray(32) { 5 })

        val restored = fixture(roots, rootBackend, TestDeviceAttestationBackend(), platform)
        val snapshot = restored.service.initialize()

        assertThat(snapshot.trustState).isEqualTo(DeviceTrustState.LOST)
        assertThat(captureFailure { restored.service.enroll(ByteArray(32) { 6 }) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `destroy writes LOST before deleting the DAK`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 7 })

        assertThat(fixture.service.destroy()).isTrue()
        assertThat(fixture.attestationBackend.keyCount).isEqualTo(0)
        assertThat(fixture.service.current().trustState).isEqualTo(DeviceTrustState.LOST)
        assertThat(captureFailure { fixture.service.refresh(ByteArray(32) { 8 }) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `remote restrictions persist and a revoked device cannot be promoted locally`() = runTest {
        val roots = roots()
        val rootBackend = TestDeviceRootKeyBackend()
        val attestationBackend = TestDeviceAttestationBackend()
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(roots, rootBackend, attestationBackend, platform)
        fixture.service.enroll(ByteArray(32) { 8 })

        val stale = fixture.service.applyRemoteRestriction(DeviceTrustState.STALE, verifiedAtEpochMillis = 1_000)
        val revoked = fixture.service.applyRemoteRestriction(DeviceTrustState.REVOKED, verifiedAtEpochMillis = 2_000)
        val attemptedDowngrade = fixture.service.applyRemoteRestriction(DeviceTrustState.STALE, verifiedAtEpochMillis = 3_000)
        val restarted = fixture(roots, rootBackend, attestationBackend, platform).service.initialize()

        assertThat(stale.trustState).isEqualTo(DeviceTrustState.STALE)
        assertThat(stale.allowsLocalVaultAccess).isFalse()
        assertThat(revoked.trustState).isEqualTo(DeviceTrustState.REVOKED)
        assertThat(attemptedDowngrade.trustState).isEqualTo(DeviceTrustState.REVOKED)
        assertThat(restarted.trustState).isEqualTo(DeviceTrustState.REVOKED)
        assertThat(restarted.lastVerifiedAtEpochMillis).isEqualTo(3_000)
    }

    @Test
    fun `server approval promotes only the current hardware attestation version`() = runTest {
        val fixture = fixture()
        val evidence = fixture.service.enroll(ByteArray(32) { 10 })

        val trusted = fixture.service.applyRemoteTrust(evidence.attestationVersion, verifiedAtEpochMillis = 5_000)
        val wrongVersionFailure = captureFailure {
            fixture.service.applyRemoteTrust(evidence.attestationVersion + 1, verifiedAtEpochMillis = 6_000)
        }

        assertThat(trusted.trustState).isEqualTo(DeviceTrustState.TRUSTED)
        assertThat(trusted.lastVerifiedAtEpochMillis).isEqualTo(5_000)
        assertThat(wrongVersionFailure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `server approval cannot promote a device without a hardware attestation chain`() = runTest {
        val attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 1)
        val fixture = fixture(attestationBackend = attestationBackend)
        val evidence = fixture.service.enroll(ByteArray(32) { 12 })

        val failure = captureFailure {
            fixture.service.applyRemoteTrust(evidence.attestationVersion, verifiedAtEpochMillis = 5_000)
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `signed managed review decision persists without granting protected access`() = runTest {
        val roots = roots()
        val rootBackend = TestDeviceRootKeyBackend(HardwareSecurityLevel.SOFTWARE)
        val attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 1)
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(roots, rootBackend, attestationBackend, platform)
        val evidence = fixture.service.enroll(ByteArray(32) { 15 })

        val pending = fixture.service.applyRemoteManagedPending(evidence.attestationVersion, verifiedAtEpochMillis = 5_000)
        val restarted = fixture(roots, rootBackend, attestationBackend, platform).service.initialize()

        assertThat(pending.managedApprovalPending).isTrue()
        assertThat(pending.allowsLocalVaultAccess).isFalse()
        assertThat(restarted.managedApprovalPending).isTrue()
        assertThat(restarted.trustState).isEqualTo(DeviceTrustState.MANAGED)
    }

    @Test
    fun `server required managed review accepts a locally reported TEE without granting protected access`() = runTest {
        val fixture = fixture(attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 2))
        val evidence = fixture.service.enroll(ByteArray(32) { 16 })

        val pending = fixture.service.applyRemoteManagedPending(evidence.attestationVersion, verifiedAtEpochMillis = 5_000)

        assertThat(pending.hardwareSecurityLevel).isEqualTo(HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT)
        assertThat(pending.riskSignals).isEmpty()
        assertThat(pending.managedApprovalPending).isTrue()
        assertThat(pending.allowsLocalVaultAccess).isFalse()
    }

    @Test
    fun `managed approval accepts missing hardware attestation without claiming hardware trust`() = runTest {
        val roots = roots()
        val rootBackend = TestDeviceRootKeyBackend(HardwareSecurityLevel.SOFTWARE)
        val attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 1)
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(roots, rootBackend, attestationBackend, platform)
        val evidence = fixture.service.enroll(ByteArray(32) { 13 })
        val verifiedAt = System.currentTimeMillis()
        val license = ManagedDeviceLicense(
            licenseId = "managed-license-1",
            installationId = evidence.installationId,
            devicePublicKeySha256 = evidence.attestationPublicKeySha256.toHex(),
            contentShareKeyId = "device-share-key-1",
            appPackageName = evidence.appPackageName,
            appVersionCode = evidence.appVersionCode,
            revision = 1,
            allowedScopes = setOf("PROTECTED_MESSAGES"),
            offlineAllowed = false,
            issuedAtEpochMillis = verifiedAt,
            expiresAtEpochMillis = verifiedAt + 30L * 24 * 60 * 60 * 1_000,
        )

        val managed = fixture.service.applyRemoteManagedTrust(evidence.attestationVersion, license, verifiedAt)
        val restarted = fixture(roots, rootBackend, attestationBackend, platform).service.initialize()

        assertThat(managed.trustState).isEqualTo(DeviceTrustState.MANAGED)
        assertThat(managed.remotelyTrusted).isFalse()
        assertThat(managed.allowsLocalVaultAccess).isTrue()
        assertThat(restarted.managedLicense).isEqualTo(license)
    }

    @Test
    fun `managed approval survives an app upgrade but rejects a future minimum version`() = runTest {
        val platform = TestDevicePlatformSignalSource(appVersionCode = 2)
        val fixture = fixture(
            attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 1),
            platformSignalSource = platform,
        )
        val evidence = fixture.service.enroll(ByteArray(32) { 17 })
        val verifiedAt = System.currentTimeMillis()
        val license = ManagedDeviceLicense(
            licenseId = "managed-license-upgrade",
            installationId = evidence.installationId,
            devicePublicKeySha256 = evidence.attestationPublicKeySha256.toHex(),
            contentShareKeyId = "device-share-key-1",
            appPackageName = evidence.appPackageName,
            appVersionCode = 1,
            revision = 1,
            allowedScopes = setOf("PROTECTED_MESSAGES"),
            offlineAllowed = false,
            issuedAtEpochMillis = verifiedAt,
            expiresAtEpochMillis = verifiedAt + 30L * 24 * 60 * 60 * 1_000,
        )

        val accepted = fixture.service.applyRemoteManagedTrust(evidence.attestationVersion, license, verifiedAt)
        val futureVersionFailure = captureFailure {
            fixture.service.applyRemoteManagedTrust(
                evidence.attestationVersion,
                license.copy(licenseId = "managed-license-future", appVersionCode = 3, revision = 2),
                verifiedAt,
            )
        }

        assertThat(accepted.managedLicense).isEqualTo(license)
        assertThat(futureVersionFailure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `managed approval rejects an offline or differently bound license`() = runTest {
        val fixture = fixture(attestationBackend = TestDeviceAttestationBackend(certificateChainSize = 1))
        val evidence = fixture.service.enroll(ByteArray(32) { 14 })
        val verifiedAt = System.currentTimeMillis()
        val invalid = ManagedDeviceLicense(
            licenseId = "managed-license-invalid",
            installationId = evidence.installationId,
            devicePublicKeySha256 = "00".repeat(32),
            contentShareKeyId = "device-share-key-1",
            appPackageName = evidence.appPackageName,
            appVersionCode = evidence.appVersionCode,
            revision = 1,
            allowedScopes = setOf("PROTECTED_MESSAGES"),
            offlineAllowed = true,
            issuedAtEpochMillis = verifiedAt,
            expiresAtEpochMillis = verifiedAt + 1_000,
        )

        val failure = captureFailure {
            fixture.service.applyRemoteManagedTrust(evidence.attestationVersion, invalid, verifiedAt)
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
        assertThat(fixture.service.current().trustState).isEqualTo(DeviceTrustState.PENDING)
    }

    @Test
    fun `revoked device can sign a final control report but lost device cannot`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 11 })
        fixture.service.applyRemoteRestriction(DeviceTrustState.REVOKED, verifiedAtEpochMillis = 7_000)

        val reportSignature = fixture.service.signControlStatement("command-report".encodeToByteArray())
        fixture.service.destroy()
        val lostFailure = captureFailure { fixture.service.signControlStatement("retry".encodeToByteArray()) }

        assertThat(reportSignature.signature).isEqualTo(sha256("command-report".encodeToByteArray()))
        assertThat(lostFailure).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `tampered durable trust state is rejected`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 9 })
        val record = temporaryFolder.root.walkTopDown().first { it.name == "device-trust.yzdt" }
        record.flipLastByteForDeviceTrustTest()

        assertThat(captureFailure { fixture.service.current() })
            .isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `short challenges are rejected before generating a key`() = runTest {
        val fixture = fixture()

        assertThat(captureFailure { fixture.service.enroll(ByteArray(8)) }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fixture.attestationBackend.keyCount).isEqualTo(0)
    }

    @Test
    fun `diagnostics retain actual current and sign results and backend order`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 21 })
        val events = mutableListOf<Pair<DeviceTrustDiagnosticStage, DeviceTrustDiagnosticOutcome>>()
        val boundaries = mutableListOf<Pair<Long, Long>>()
        val sink = DeviceTrustDiagnosticSink { stage, start, end, outcome ->
            boundaries += start to end
            events += stage to outcome
        }
        val plain = fixture.service.current()
        fixture.attestationBackend.actions.clear()
        assertThat(fixture.service.current(sink)).isEqualTo(plain)
        assertThat(fixture.attestationBackend.actions).containsExactly("load").inOrder()
        assertThat(events.map { it.first }).containsExactly(
            DeviceTrustDiagnosticStage.MUTEX_WAIT, DeviceTrustDiagnosticStage.ROOT_READY,
            DeviceTrustDiagnosticStage.INSTALLATION_LOAD, DeviceTrustDiagnosticStage.PLATFORM_ASSESS,
            DeviceTrustDiagnosticStage.RECORD_READ, DeviceTrustDiagnosticStage.RECORD_VALIDATE,
            DeviceTrustDiagnosticStage.DAK_LOAD, DeviceTrustDiagnosticStage.RECORD_WRITE_UNCHANGED,
            DeviceTrustDiagnosticStage.MUTEX_HOLD,
        ).inOrder()
        val statement = "real-test-statement".encodeToByteArray()
        val signature = fixture.service.signControlStatement(statement)
        fixture.attestationBackend.actions.clear()
        val observed = fixture.service.signControlStatement(statement, sink)
        assertThat(observed.installationId).isEqualTo(signature.installationId)
        assertThat(observed.signature).isEqualTo(signature.signature)
        assertThat(fixture.attestationBackend.actions).containsExactly("load", "load", "sign").inOrder()
        assertThat(events.all { it.second == DeviceTrustDiagnosticOutcome.OK }).isTrue()
        assertThat(boundaries.all { (start, end) -> end >= start }).isTrue()
    }

    @Test
    fun `observing writes retains changed risks and durable restrictive state`() = runTest {
        val roots = roots()
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(roots = roots, platformSignalSource = platform)
        fixture.service.enroll(ByteArray(32) { 22 })
        val stages = mutableListOf<DeviceTrustDiagnosticStage>()
        val sink = DeviceTrustDiagnosticSink { stage, _, _, _ -> stages += stage }
        platform.riskSignals = setOf(DeviceRiskSignal.ROOT_ARTIFACT_DETECTED)
        assertThat(fixture.service.current(sink).allowsLocalVaultAccess).isFalse()
        assertThat(stages.takeLast(2)).containsExactly(
            DeviceTrustDiagnosticStage.RECORD_WRITE_CHANGED, DeviceTrustDiagnosticStage.MUTEX_HOLD,
        ).inOrder()
        assertThat(fixture.service.current(sink).trustState).isEqualTo(DeviceTrustState.INTEGRITY_WARNING)
        assertThat(stages.takeLast(2)).containsExactly(
            DeviceTrustDiagnosticStage.RECORD_WRITE_UNCHANGED, DeviceTrustDiagnosticStage.MUTEX_HOLD,
        ).inOrder()
        assertThat(DeviceTrustRecordStore(roots).read()?.trustState).isEqualTo(DeviceTrustState.INTEGRITY_WARNING)
    }

    @Test
    fun `observer failures cannot replace signatures or lost-key rejection`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 23 })
        val throwing = DeviceTrustDiagnosticSink { _, _, _, _ -> throw CancellationException("observer-only") }
        val statement = "observer-failure-test".encodeToByteArray()
        assertThat(fixture.service.current(throwing)).isEqualTo(fixture.service.current())
        assertThat(fixture.service.signControlStatement(statement, throwing).signature).isEqualTo(sha256(statement))
        fixture.service.destroy()
        assertThat(captureFailure { fixture.service.signControlStatement(statement, throwing) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `actual platform failure retains original cause and baseline recovery shape`() = runTest {
        val platform = TestDevicePlatformSignalSource()
        val fixture = fixture(platformSignalSource = platform)
        val failure = IllegalStateException("private-test-exception")
        platform.failure = failure
        val events = mutableListOf<Pair<DeviceTrustDiagnosticStage, DeviceTrustDiagnosticOutcome>>()
        val sink = DeviceTrustDiagnosticSink { stage, _, _, outcome ->
            events += stage to outcome
            throw IllegalStateException("observer-only")
        }
        // The caller outside IO sees the same recovery shape for legacy and observed calls.
        val plainRecovered = checkNotNull(captureFailure { fixture.service.current() })
        val observedRecovered = checkNotNull(captureFailure { fixture.service.current(sink) })
        assertThat(observedRecovered.javaClass).isEqualTo(plainRecovered.javaClass)
        assertThat(observedRecovered.message).isEqualTo(plainRecovered.message)
        assertThat(generateSequence(plainRecovered) { it.cause }.last()).isSameInstanceAs(failure)
        assertThat(generateSequence(observedRecovered) { it.cause }.last()).isSameInstanceAs(failure)
        assertThat(events).contains(DeviceTrustDiagnosticStage.PLATFORM_ASSESS to DeviceTrustDiagnosticOutcome.ERROR)
        assertThat(events.last()).isEqualTo(DeviceTrustDiagnosticStage.MUTEX_HOLD to DeviceTrustDiagnosticOutcome.ERROR)
        assertThat(events.none { it.first == DeviceTrustDiagnosticStage.RECORD_READ }).isTrue()
    }

    @Test
    fun `real coroutine cancellation while root readiness suspends releases trust mutex`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var blockRoot = false
        val fixture = fixture(rootDecorator = { root ->
            object : DeviceRootKeyService by root {
                override suspend fun ensureReady(): DeviceRootKeyDescriptor {
                    if (blockRoot) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    return root.ensureReady()
                }
            }
        })
        fixture.service.enroll(ByteArray(32) { 24 })
        val events = mutableListOf<Pair<DeviceTrustDiagnosticStage, DeviceTrustDiagnosticOutcome>>()
        val sink = DeviceTrustDiagnosticSink { stage, _, _, outcome -> events += stage to outcome }
        blockRoot = true
        val job = launch { fixture.service.current(sink) }
        entered.await()
        assertThat(events).isEmpty()
        job.cancelAndJoin()
        blockRoot = false
        assertThat(events).contains(DeviceTrustDiagnosticStage.ROOT_READY to DeviceTrustDiagnosticOutcome.CANCELLED)
        assertThat(events.last()).isEqualTo(DeviceTrustDiagnosticStage.MUTEX_HOLD to DeviceTrustDiagnosticOutcome.CANCELLED)
        assertThat(events.count { it.first == DeviceTrustDiagnosticStage.MUTEX_HOLD }).isEqualTo(1)
        assertThat(fixture.service.current().trustState).isEqualTo(DeviceTrustState.PENDING)
    }

    @Test
    fun `cancelling actual mutex waiter cannot run root work or unlock another caller`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockRoot = false
        var rootCalls = 0
        val fixture = fixture(rootDecorator = { root ->
            object : DeviceRootKeyService by root {
                override suspend fun ensureReady(): DeviceRootKeyDescriptor {
                    rootCalls++
                    if (blockRoot) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return root.ensureReady()
                }
            }
        })
        fixture.service.enroll(ByteArray(32) { 25 })
        blockRoot = true
        val holder = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.service.current() }
        entered.await()
        val callsWhileHeld = rootCalls
        val events = mutableListOf<Pair<DeviceTrustDiagnosticStage, DeviceTrustDiagnosticOutcome>>()
        val sink = DeviceTrustDiagnosticSink { stage, _, _, outcome -> events += stage to outcome }
        // Already in IO context, current's withContext(IO) runs until the actual locked mutex suspends.
        val waiter = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.service.current(sink) }
        waiter.cancelAndJoin()
        assertThat(events).containsExactly(DeviceTrustDiagnosticStage.MUTEX_WAIT to DeviceTrustDiagnosticOutcome.CANCELLED)
        assertThat(rootCalls).isEqualTo(callsWhileHeld)
        assertThat(holder.isCompleted).isFalse()
        // A fresh caller must still wait for the original holder, not enter through a mistakenly unlocked mutex.
        val third = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.service.current() }
        assertThat(rootCalls).isEqualTo(callsWhileHeld)
        assertThat(third.isCompleted).isFalse()
        blockRoot = false
        release.complete(Unit)
        holder.join()
        third.join()
        assertThat(rootCalls).isEqualTo(callsWhileHeld + 1)
        assertThat(fixture.service.current().trustState).isEqualTo(DeviceTrustState.PENDING)
    }

    @Test
    fun `every current and sign observer can synchronously reenter current on IO after actual unlock`() = runTest {
        val fixture = fixture()
        fixture.service.enroll(ByteArray(32) { 26 })
        val plain = fixture.service.current()
        val statement = "reentrant-observer-test".encodeToByteArray()
        var delivered = 0
        var reentered = 0
        val sink = DeviceTrustDiagnosticSink { _, _, _, _ ->
            delivered++
            // A real nested current waits on the actual mutex. If callbacks are inside the lock this times out.
            val nested = runBlocking(Dispatchers.IO) { withTimeout(5_000) { fixture.service.current() } }
            assertThat(nested).isEqualTo(plain)
            reentered++
        }
        assertThat(fixture.service.current(sink)).isEqualTo(plain)
        assertThat(delivered).isGreaterThan(0)
        assertThat(reentered).isEqualTo(delivered)
        val beforeSign = delivered
        assertThat(fixture.service.signControlStatement(statement, sink).signature).isEqualTo(sha256(statement))
        assertThat(delivered).isGreaterThan(beforeSign)
        assertThat(reentered).isEqualTo(delivered)
    }

    @Test
    fun `hold uniquely bounds the actual acquired body and excludes a real mutex wait and observer flush`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockRoot = false
        val fixture = fixture(rootDecorator = { root ->
            object : DeviceRootKeyService by root {
                override suspend fun ensureReady(): DeviceRootKeyDescriptor {
                    if (blockRoot) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return root.ensureReady()
                }
            }
        })
        fixture.service.enroll(ByteArray(32) { 27 })
        blockRoot = true
        val holder = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.service.current() }
        entered.await()
        val events = mutableListOf<TrustDiagnosticBoundary>()
        val sink = DeviceTrustDiagnosticSink { stage, start, end, outcome ->
            events += TrustDiagnosticBoundary(stage, start, end, outcome, System.nanoTime())
        }
        val waiter = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.service.current(sink) }
        assertThat(waiter.isCompleted).isFalse()
        assertThat(events).isEmpty()
        val releaseBoundary = System.nanoTime()
        blockRoot = false
        release.complete(Unit)
        holder.join()
        assertThat(waiter.await().trustState).isEqualTo(DeviceTrustState.PENDING)
        val wait = events.single { it.stage == DeviceTrustDiagnosticStage.MUTEX_WAIT }
        val hold = events.single { it.stage == DeviceTrustDiagnosticStage.MUTEX_HOLD }
        assertThat(wait.start).isLessThan(releaseBoundary)
        assertThat(hold.start).isAtLeast(releaseBoundary)
        assertThat(hold.start).isEqualTo(wait.end)
        assertThat(hold.end).isAtLeast(hold.start)
        assertThat(hold.outcome).isEqualTo(DeviceTrustDiagnosticOutcome.OK)
        assertThat(events.first().stage).isEqualTo(DeviceTrustDiagnosticStage.MUTEX_WAIT)
        assertThat(events.last().stage).isEqualTo(DeviceTrustDiagnosticStage.MUTEX_HOLD)
        events.filter { it.stage != DeviceTrustDiagnosticStage.MUTEX_WAIT && it.stage != DeviceTrustDiagnosticStage.MUTEX_HOLD }.forEach {
            assertThat(it.start).isAtLeast(hold.start)
            assertThat(it.end).isAtMost(hold.end)
        }
        assertThat(events.all { it.delivered >= hold.end }).isTrue()
    }

    private data class TrustDiagnosticBoundary(
        val stage: DeviceTrustDiagnosticStage,
        val start: Long,
        val end: Long,
        val outcome: DeviceTrustDiagnosticOutcome,
        val delivered: Long,
    )

    private fun fixture(
        roots: SecureStorageRoots = roots(),
        rootBackend: TestDeviceRootKeyBackend = TestDeviceRootKeyBackend(),
        attestationBackend: TestDeviceAttestationBackend = TestDeviceAttestationBackend(),
        platformSignalSource: TestDevicePlatformSignalSource = TestDevicePlatformSignalSource(),
        rootDecorator: (DeviceRootKeyService) -> DeviceRootKeyService = { it },
    ): Fixture {
        val identityStore = InstallationIdentityStore(roots)
        val rootService = DefaultDeviceRootKeyService(identityStore, DeviceRootKeyRecordStore(roots), rootBackend)
        return Fixture(
            service = DefaultDeviceTrustService(
                deviceRootKeyService = rootDecorator(rootService),
                installationIdentityStore = identityStore,
                recordStore = DeviceTrustRecordStore(roots),
                attestationBackend = attestationBackend,
                platformSignalSource = platformSignalSource,
            ),
            attestationBackend = attestationBackend,
        )
    }

    private fun roots(): SecureStorageRoots = object : SecureStorageRoots {
        override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
        override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private data class Fixture(
        val service: DefaultDeviceTrustService,
        val attestationBackend: TestDeviceAttestationBackend,
    )
}

private class TestDeviceAttestationBackend(
    private val certificateChainSize: Int = 2,
) : DeviceAttestationBackend {
    private val keys = mutableMapOf<String, DeviceAttestationKeyDescriptor>()
    val keyCount: Int get() = keys.size
    val actions = mutableListOf<String>()

    override fun load(alias: String): DeviceAttestationKeyDescriptor? {
        actions += "load"
        return keys[alias]
    }

    override fun create(alias: String, attestationChallenge: ByteArray): DeviceAttestationKeyDescriptor =
        keys.getOrPut(alias) {
            DeviceAttestationKeyDescriptor(
                publicKeySha256 = sha256(alias.encodeToByteArray()),
                certificateChainDer = List(certificateChainSize) { byteArrayOf((it + 1).toByte()) },
                hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                strongBoxRequested = false,
            )
        }

    override fun sign(alias: String, statement: ByteArray): ByteArray {
        actions += "sign"
        check(keys.containsKey(alias))
        return sha256(statement)
    }

    override fun destroy(alias: String): Boolean {
        keys.remove(alias)
        return true
    }
}

private class TestDevicePlatformSignalSource(
    risks: Set<DeviceRiskSignal> = emptySet(),
    private val appVersionCode: Long = 1,
) : DevicePlatformSignalSource {
    var signingCertificateSha256: List<ByteArray> = listOf(ByteArray(32) { 1 })
    var riskSignals: Set<DeviceRiskSignal> = risks

    var failure: Exception? = null

    override fun assess(): DevicePlatformAssessment {
        failure?.let { throw it }
        return DevicePlatformAssessment(
            packageName = "io.element.android.x",
            appVersionCode = appVersionCode,
            firstInstallTimeEpochMillis = 100,
            signingCertificateSha256 = signingCertificateSha256,
            riskSignals = riskSignals,
        )
    }
}

private fun File.flipLastByteForDeviceTrustTest() {
    val bytes = readBytes()
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    writeBytes(bytes)
}
