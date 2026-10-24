/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.AcquireRoomEpochRequest
import io.element.android.security.contentprotection.api.BurnAfterReadEndReason
import io.element.android.security.contentprotection.api.BurnAfterReadEndResult
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.EndBurnAfterReadRequest
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLease
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.OfflineLicenseService
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthority
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDecision
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleaseRoomEpochRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.ReleasedRoomEpoch
import io.element.android.security.contentprotection.api.RemoteRevocationSnapshot
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomEpochReference
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import io.element.android.security.contentprotection.api.StageThreeGateRequest
import io.element.android.security.contentprotection.api.StageThreeReadiness
import io.element.android.security.contentprotection.api.StageThreeReleaseRetentionAuthority
import io.element.android.security.contentprotection.api.StageThreeSecurityGate
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineReleaseScopeOwnerToken
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.test.FakeVaultKeyService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class DefaultContentProtectionServiceTest {
    @Test
    fun `initial render serves two independent operations while reserving real foreground and retiring pending bootstrap`() = runBlocking {
        val kms = OperationGatedKmsShareClient()
        val service = serviceWithKms(kms)
        val contents = protectDistinctContent(service, 6, "initial-render")
        val scope = "initial-render-scope"
        val owner = service.openTimelineReleaseScope(scope)
        val reads = mutableListOf<kotlinx.coroutines.Deferred<ByteArray>>()
        fun launchRead(index: Int, operation: String, releaseClass: TimelineContentReleaseClass) = async {
            withContext(LifecycleOperationContext(operation, FullAppLifecycleId.LC_13)) {
                service.decryptBytes(releaseRequest(contents[index].header).copy(
                    timelineReleaseIntent = timelineIntent(index, releaseClass, scope, owner),
                ), contents[index].ciphertext)
            }
        }.also { reads += it }
        try {
            assertThat(service.updateTimelineReleaseViewport(scope, 0, emptyList(), owner, emptyList())).isTrue()
            launchRead(0, "old-history", TimelineContentReleaseClass.HISTORY_PREFETCH)
            val old = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(old.operationId).isEqualTo("old-history")
            (1..4).forEach { launchRead(it, "initial-$it", TimelineContentReleaseClass.HISTORY_PREFETCH) }
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                while (service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.HISTORY_PREFETCH) != 4) delay(1)
            }
            assertThat(service.updateTimelineReleaseViewport(scope, 1, listOf("request-$scope-5"), owner,
                (1..4).map { "request-$scope-$it" })).isTrue()
            val first = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            val second = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(first.operationId).startsWith("initial-")
            assertThat(second.operationId).startsWith("initial-")
            assertThat(first.operationId).isNotEqualTo(second.operationId)
            assertThat(first.requests).hasSize(1)
            assertThat(second.requests).hasSize(1)
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                while (service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP) != 2) delay(1)
            }
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
            val currentRead = launchRead(5, "actual-current", TimelineContentReleaseClass.FOREGROUND_VISIBLE)
            val current = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(current.operationId).isEqualTo("actual-current")
            current.permit.complete(Unit)
            assertThat(withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { currentRead.await() }.decodeToString()).isEqualTo("initial-render-5")
            assertThat(old.permit.isCompleted).isFalse()
            assertThat(first.permit.isCompleted).isFalse()
            assertThat(second.permit.isCompleted).isFalse()
            assertThat(service.updateTimelineReleaseViewport(scope, 2, listOf("request-$scope-5"), owner, emptyList())).isTrue()
            assertThat(service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP)).isEqualTo(0)
            assertThat(service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.HISTORY_PREFETCH)).isEqualTo(2)
            // Neither an older generation nor a later hint can replenish the retired entry set.
            assertThat(service.updateTimelineReleaseViewport(scope, 1, emptyList(), owner, listOf("request-$scope-1"))).isFalse()
            assertThat(service.updateTimelineReleaseViewport(scope, 3, emptyList(), owner, listOf("request-$scope-1"))).isTrue()
            assertThat(service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP)).isEqualTo(0)
            first.permit.complete(Unit)
            second.permit.complete(Unit)
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
        } finally {
            kms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { reads.forEach { it.await() } }
        }
    }

    @Test
    fun `preview and unhinted requests cannot claim bootstrap or use another scope owner`() = runBlocking {
        val kms = OperationGatedKmsShareClient()
        val service = serviceWithKms(kms)
        val contents = protectDistinctContent(service, 3, "initial-reject")
        val scope = "initial-reject-scope"
        val owner = service.openTimelineReleaseScope(scope)
        val reads = mutableListOf<kotlinx.coroutines.Deferred<ByteArray>>()
        fun launchRead(index: Int, purpose: io.element.android.security.contentprotection.api.TimelineContentReadPurpose) = async {
            withContext(LifecycleOperationContext("reject-$index", FullAppLifecycleId.LC_13)) {
                service.decryptBytes(releaseRequest(contents[index].header).copy(
                    timelineReleaseIntent = timelineIntent(index,
                        if (index == 0) TimelineContentReleaseClass.HISTORY_PREFETCH else TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP,
                        scope, owner).copy(readPurpose = purpose),
                ), contents[index].ciphertext)
            }
        }.also { reads += it }
        try {
            launchRead(0, io.element.android.security.contentprotection.api.TimelineContentReadPurpose.TIMELINE)
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(service.updateTimelineReleaseViewport(scope, 1, emptyList(), TimelineReleaseScopeOwnerToken(owner.value + 1),
                listOf("request-$scope-1"))).isFalse()
            assertThat(service.updateTimelineReleaseViewport(scope, 1, emptyList(), owner, listOf("request-$scope-1"))).isTrue()
            launchRead(1, io.element.android.security.contentprotection.api.TimelineContentReadPurpose.ROOM_PREVIEW)
            launchRead(2, io.element.android.security.contentprotection.api.TimelineContentReadPurpose.TIMELINE)
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                while (service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.HISTORY_PREFETCH) != 2) delay(1)
            }
            assertThat(service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP)).isEqualTo(0)
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
        } finally {
            kms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { reads.forEach { it.await() } }
        }
    }

    @Test fun `external child is issuer owned preserves deadline and survives only parent close`() = runTest {
        val policy = io.mockk.mockk<io.element.android.security.dataprotection.api.DataProtectionPolicyV1>()
        io.mockk.every { policy.policyId } returns "policy-1"
        io.mockk.every { policy.version } returns 1
        var exportAllowed = true
        io.mockk.every { policy.allowContentExport } answers { exportAllowed }
        val provider = object : io.element.android.security.dataprotection.api.DataProtectionPolicyProvider {
            override fun current() = io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy(
                policy,
                io.element.android.security.dataprotection.api.DataProtectionPolicySource.VERIFIED_REMOTE
            )
        }
        var unlocked = true
        val gate = io.mockk.mockk<io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate>()
        io.mockk.every { gate.canAttemptPreview() } answers { unlocked }
        val sut = DefaultContentProtectionService(
            DefaultContentEnvelopeValidator(),
            deviceKeys,
            kms,
            offlineLicenses,
            AllowStageThreeSecurityGate,
            runtimeBinding,
            SystemClock { NOW },
            authorityClock,
            vaultKeys,
            runtime,
            provider,
            gate
        )
        val output = ByteArrayOutputStream()
        val header = sut.protectStream(protectRequest(), byteArrayOf(1, 2, 3).inputStream(), output)
        val parent = sut.decryptMediaStreamWithAuthority(releaseRequest(header), output.toByteArray().inputStream(), ByteArrayOutputStream())
        val binding = io.element.android.security.securestorage.api.SecureMediaExternalReadBinding(
            io.element.android.security.securestorage.api.SecureTransientMediaOwner(
                "external-child-operation-0001",
                ACCOUNT_HASH,
                "b".repeat(64),
                parent.description.installationId,
                "c".repeat(64),
                "d".repeat(64)
            ),
            "original-source-operation-0001",
            "policy-1",
            1,
            "1".repeat(64),
            "2".repeat(64),
            io.element.android.security.securestorage.api.SecureMediaExternalRecipient(22001, "test.reader", "test.reader.Viewer")
        )
        val forged = object : ProtectedMediaReleaseAuthority {
            override val description = parent.description
            override fun close() = Unit
        }
        assertThat(sut.deriveProtectedMediaExternalReadAuthority(forged, binding)).isNull()
        assertThat(sut.deriveProtectedMediaExternalReadAuthority(parent, binding.copy(sourcePolicyVersion = 2))).isNull()
        val child = checkNotNull(sut.deriveProtectedMediaExternalReadAuthority(parent, binding))
        val releaseCountBeforeChildRefresh = kms.releaseCount
        assertThat(sut.renewProtectedMediaReadAuthority(child)).isNull()
        assertThat(kms.releaseCount).isEqualTo(releaseCountBeforeChildRefresh)
        assertThat(child.description).isEqualTo(parent.description)
        assertThat(child.localAuthorityId).isNotNull()
        val deadline = checkNotNull(sut.protectedMediaDeadlineElapsedRealtimeMillis(child))
        assertThat(sut.deriveProtectedMediaExternalReadAuthority(child, binding)).isNull()
        parent.close()
        authorityClock.wallEpochMillis = NOW - 1_000
        assertThat(sut.protectedMediaDeadlineElapsedRealtimeMillis(child)).isEqualTo(deadline)
        assertThat(sut.validateProtectedMediaReleaseAuthority(child, child.description, 0)).isEqualTo(ProtectedMediaReleaseAuthorityDecision.VALIDATED)
        unlocked = false
        assertThat(
            sut.validateProtectedMediaReleaseAuthority(child, child.description, 0)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE)
        unlocked = true
        exportAllowed = false
        assertThat(
            sut.validateProtectedMediaReleaseAuthority(child, child.description, 0)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE)
        child.close()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    private val deviceKeys = SoftwareDeviceShareKeyService()
    private val kms = InMemoryKmsShareClient()
    private val offlineLicenses = TombstoneOfflineLicenseService()
    private val runtime = TestMatrixContentProtectionRuntime()
    private val runtimeBinding = MutableRuntimeBindingProvider(BOOT_COUNT)
    private val authorityClock = MutableAuthorityClock(NOW, ELAPSED_REALTIME)
    private val vaultKeys = FakeVaultKeyService().also { keys ->
        runBlocking {
            keys.provision(VaultProvisioningRequest(VAULT_ID, ACCOUNT_HASH, false, false))
            check(keys.unlock(VAULT_ID))
        }
    }
    private val service = DefaultContentProtectionService(
        validator = DefaultContentEnvelopeValidator(),
        deviceShareKeyService = deviceKeys,
        kmsShareClient = kms,
        offlineLicenseService = offlineLicenses,
        stageThreeSecurityGate = AllowStageThreeSecurityGate,
        runtimeBindingProvider = runtimeBinding,
        clock = SystemClock { NOW },
        authorityClock = authorityClock,
        vaultKeyService = vaultKeys,
        matrixContentProtectionRuntime = runtime,
    )

    @Test
    fun `signed playback permission survives short release expiry but expires without another release`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3)
        val output = ByteArrayOutputStream()
        val original = protectRequest()
        val header = service.protectStream(original.copy(
            kind = ContentKind.VIDEO,
            policy = original.policy.copy(expiresAtEpochMillis = NOW + 180_000)
        ), plaintext.inputStream(), output)
        kms.releaseExpiresAtEpochMillis = NOW + 60_000
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 60_000
        kms.playbackDeadline = NOW + 180_000
        val reader = service.openChunkReadSession(releaseRequest(header).copy(progressiveVideoPlayback = true))
        authorityClock.elapsedMillis += 90_000
        authorityClock.wallEpochMillis = NOW - 1_000
        try {
            repeat(3) {
                val actual = reader.decryptChunk(0, output.toByteArray(), plaintext.size, true)
                assertThat(actual).isEqualTo(plaintext)
                actual.fill(0)
            }
            assertThat(kms.releaseCount).isEqualTo(1)
            authorityClock.elapsedMillis += 90_000
            assertThat(runCatching { reader.requireAuthorized() }.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.Expired::class.java)
            assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), plaintext.size, true) }.isFailure).isTrue()
            assertThat(kms.releaseCount).isEqualTo(1)
        } finally {
            reader.close()
        }
    }

    @Test
    fun `requested playback permission is mandatory and cannot extend full decrypt authority`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val request = releaseRequest(header).copy(progressiveVideoPlayback = true)
        assertThat(runCatching { service.openChunkReadSession(request) }.exceptionOrNull())
            .isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        assertThat(kms.releaseCount).isEqualTo(1)
        kms.playbackDeadline = EXPIRY
        assertThat(runCatching { service.decryptStream(request, output.toByteArray().inputStream(), ByteArrayOutputStream()) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `signed playback permission still ends on tombstone runtime clear and boot change`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        kms.playbackDeadline = EXPIRY
        val request = releaseRequest(header).copy(progressiveVideoPlayback = true)
        val first = service.openChunkReadSession(request)
        runtime.clearSensitiveCaches()
        assertThat(runCatching { first.requireAuthorized() }.isFailure).isTrue()
        val second = service.openChunkReadSession(request)
        runtimeBinding.bootCount++
        assertThat(runCatching { second.requireAuthorized() }.isFailure).isTrue()
        runtimeBinding.bootCount--
        val third = service.openChunkReadSession(request)
        service.revokeLocalContent(VAULT_ID, header.contentId)
        assertThat(runCatching { third.decryptChunk(0, output.toByteArray(), 1, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(3)
    }

    @Test
    fun `progressive random reads release one key and authenticate every out of order record`() = runTest {
        val plaintext = ByteArray(2 * 65_536 + 97) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), plaintext.inputStream(), output)
        val bytes = output.toByteArray()
        val reader = service.openChunkReadSession(releaseRequest(header))
        try {
            for (index in listOf(2, 0, 1, 0, 2)) {
                val start = index * 65_557
                val last = index == 2
                val length = if (last) 97 else 65_536
                val actual = reader.decryptChunk(index.toLong(), bytes.copyOfRange(start, start + length + 21), length, last)
                assertThat(actual).isEqualTo(plaintext.copyOfRange(index * 65_536, index * 65_536 + length))
                actual.fill(0)
            }
            repeat(3) { reader.requireAuthorized() }
            assertThat(kms.releaseCount).isEqualTo(1)
        } finally {
            reader.close()
        }
        assertThat(runCatching { reader.requireAuthorized() }.isFailure).isTrue()
    }

    @Test
    fun `video file random reader retains ATTACHMENT kind and releases only once`() = runTest {
        val output = ByteArrayOutputStream()
        val plaintext = byteArrayOf(1, 2, 3)
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.ATTACHMENT), plaintext.inputStream(), output)
        val reader = service.openChunkReadSession(releaseRequest(header))
        try {
            repeat(2) {
                val actual = reader.decryptChunk(0, output.toByteArray(), plaintext.size, true)
                assertThat(actual).isEqualTo(plaintext)
                actual.fill(0)
            }
            assertThat(header.kind).isEqualTo(ContentKind.ATTACHMENT)
            assertThat(kms.releaseCount).isEqualTo(1)
        } finally {
            reader.close()
        }
        val audio = header.copy(kind = ContentKind.VOICE_MESSAGE)
        assertThat(runCatching { service.openChunkReadSession(releaseRequest(audio)) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `cancelled seek admission before or after decryption keeps the same authorized reader`() = runTest {
        var cancelAt = 0
        var calls = 0
        val gated = serviceWithGate(object : StageThreeSecurityGate {
            override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness {
                if (++calls == cancelAt) throw CancellationException("Retired decoder read")
                return AllowStageThreeSecurityGate.requireAccess(request)
            }
        })
        val output = ByteArrayOutputStream()
        val plaintext = byteArrayOf(1, 2, 3)
        val header = gated.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), plaintext.inputStream(), output)
        val reader = gated.openChunkReadSession(releaseRequest(header))
        try {
            for (cancelledGate in listOf(1, 2)) {
                calls = 0
                cancelAt = cancelledGate
                assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), plaintext.size, true) }.exceptionOrNull())
                    .isInstanceOf(CancellationException::class.java)
                cancelAt = 0
                assertThat(reader.decryptChunk(0, output.toByteArray(), plaintext.size, true)).isEqualTo(plaintext)
            }
            assertThat(kms.releaseCount).isEqualTo(1)
            gated.revokeLocalContent(VAULT_ID, header.contentId)
            assertThat(runCatching { reader.requireAuthorized() }.isFailure).isTrue()
        } finally { reader.close() }
        assertThat(runCatching { reader.requireAuthorized() }.isFailure).isTrue()
    }

    @Test
    fun `retained local inspection is coalesced but rechecks at 250 milliseconds`() = runTest {
        var checks = 0
        var allowed = true
        val gate = object : StageThreeSecurityGate {
            override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness {
                if (!allowed) throw ContentProtectionException.AccessDenied()
                return AllowStageThreeSecurityGate.requireAccess(request)
            }
            override suspend fun openReadAuthority(request: StageThreeGateRequest) =
                object : io.element.android.security.contentprotection.api.StageThreeReadAuthority {
                    override suspend fun requireAccess() {
                        checks++
                        if (!allowed) throw ContentProtectionException.AccessDenied()
                    }
                }
        }
        val gated = serviceWithGate(gate)
        val output = ByteArrayOutputStream()
        val header = gated.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val reader = gated.openChunkReadSession(releaseRequest(header))
        repeat(100) { reader.decryptChunk(0, output.toByteArray(), 1, true).fill(0) }
        assertThat(checks).isEqualTo(0)
        authorityClock.elapsedMillis += 250
        reader.requireBufferedReadAuthorized()
        assertThat(checks).isEqualTo(1)
        allowed = false
        authorityClock.elapsedMillis += 250
        assertThat(runCatching { reader.requireBufferedReadAuthorized() }.isFailure).isTrue()
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 1, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `coalesced admission still denies expiry boot changes and runtime close immediately`() = runTest {
        val gate = object : StageThreeSecurityGate {
            override suspend fun requireAccess(request: StageThreeGateRequest) = AllowStageThreeSecurityGate.requireAccess(request)
            override suspend fun openReadAuthority(request: StageThreeGateRequest) =
                object : io.element.android.security.contentprotection.api.StageThreeReadAuthority {
                    override suspend fun requireAccess() = Unit
                }
        }
        val gated = serviceWithGate(gate)
        val output = ByteArrayOutputStream()
        val header = gated.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val first = gated.openChunkReadSession(releaseRequest(header))
        runtimeBinding.bootCount++
        assertThat(runCatching { first.requireBufferedReadAuthorized() }.isFailure).isTrue()
        runtimeBinding.bootCount--
        val second = gated.openChunkReadSession(releaseRequest(header))
        runtime.clearSensitiveCaches()
        assertThat(runCatching { second.requireBufferedReadAuthorized() }.isFailure).isTrue()
        val third = gated.openChunkReadSession(releaseRequest(header))
        authorityClock.elapsedMillis += 60_001
        assertThat(runCatching { third.requireBufferedReadAuthorized() }.isFailure).isTrue()
    }

    @Test
    fun `progressive corrupted index tag final flag and length close the permission`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), ByteArray(65_537) { 7 }.inputStream(), output)
        val record = output.toByteArray().copyOfRange(0, 65_557)
        for (attack in 0..4) {
            val reader = service.openChunkReadSession(releaseRequest(header))
            val forged = record.copyOf()
            if (attack == 0) forged[17] = (forged[17].toInt() xor 1).toByte()
            if (attack == 2) forged[0] = 1
            if (attack == 3) forged[4] = 0
            val actual = if (attack == 4) forged.copyOf(forged.size - 1) else forged
            assertThat(runCatching { reader.decryptChunk(if (attack == 1) 1 else 0, actual, 65_536, false) }.isFailure).isTrue()
            assertThat(runCatching { reader.requireAuthorized() }.isFailure).isTrue()
        }
    }

    @Test
    fun `progressive local content revocation invalidates an already released reader`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1, 2).inputStream(), output)
        val reader = service.openChunkReadSession(releaseRequest(header))
        service.revokeLocalContent(VAULT_ID, header.contentId)
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 2, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `progressive runtime clear zeros and closes all existing read sessions`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1, 2).inputStream(), output)
        val reader = service.openChunkReadSession(releaseRequest(header))
        runtime.clearSensitiveCaches()
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 2, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `progressive expiry uses monotonic trusted time despite a wall clock rollback`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val reader = service.openChunkReadSession(releaseRequest(header))
        authorityClock.wallEpochMillis = NOW - 10_000
        authorityClock.elapsedMillis += EXPIRY - NOW + 1
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 1, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `progressive boot changes deny buffered and future delivery without another release`() = runTest {
        val output = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val reader = service.openChunkReadSession(releaseRequest(header))
        runtimeBinding.bootCount++
        assertThat(runCatching { reader.requireAuthorized() }.isFailure).isTrue()
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 1, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `progressive local gate closure immediately rejects a previously admitted reader`() = runTest {
        var allowed = true
        val gated = serviceWithGate(object : StageThreeSecurityGate {
            override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness {
                if (!allowed) throw ContentProtectionException.AccessDenied()
                return AllowStageThreeSecurityGate.requireAccess(request)
            }
        })
        val output = ByteArrayOutputStream()
        val header = gated.protectStream(protectRequest().copy(kind = ContentKind.VIDEO), byteArrayOf(1).inputStream(), output)
        val reader = gated.openChunkReadSession(releaseRequest(header))
        allowed = false
        assertThat(runCatching { reader.decryptChunk(0, output.toByteArray(), 1, true) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `media producer journal persists before first remote key store`() = runTest {
        var boundaries = 0
        val output = ByteArrayOutputStream()
        service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), output) {
            assertThat(kms.storeAttemptExpiries).isEmpty()
            boundaries++
        }
        assertThat(boundaries).isEqualTo(1)
        assertThat(kms.storeAttemptExpiries).hasSize(1)
    }

    @Test
    fun `media journal persistence failure prevents remote key creation`() = runTest {
        val failure = IllegalStateException("Unable to persist original draft")
        val result = runCatching {
            service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ByteArrayOutputStream()) { throw failure }
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).isEqualTo(failure.message)
        assertThat(kms.storeAttemptExpiries).isEmpty()
    }

    @Test
    fun `tracked media encryption failure retains the original KMS resource for reconciliation`() = runTest {
        val failedInput = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("Synthetic interrupted source")
        }
        assertThat(runCatching { service.protectStream(protectRequest(), failedInput, ByteArrayOutputStream()) {} }.isFailure).isTrue()
        assertThat(kms.hasStoredShare(CONTENT_ID)).isTrue()
    }

    @Test
    fun `durable producer boundary commits before KMS dispatch under the original lifecycle`() = runTest {
        var boundaries = 0
        val protected = withContext(LifecycleOperationContext("relay-original-preparation-0001", FullAppLifecycleId.LC_15)) {
            service.protectBytes(protectRequest(), "synthetic preparation payload".encodeToByteArray()) {
                assertThat(kms.storeAttemptExpiries).isEmpty()
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo("relay-original-preparation-0001")
                boundaries++
            }
        }
        assertThat(boundaries).isEqualTo(1)
        assertThat(kms.storeAttemptExpiries).hasSize(1)
        assertThat(service.decryptBytes(releaseRequest(protected.header), protected.ciphertext))
            .isEqualTo("synthetic preparation payload".encodeToByteArray())
    }

    @Test
    fun `failed producer journal boundary cannot dispatch KMS or return a carrier`() = runTest {
        var returned = false
        try {
            service.protectBytes(protectRequest(), "synthetic preparation payload".encodeToByteArray()) {
                throw java.io.IOException("Journal commit failed")
            }
            returned = true
        } catch (failure: java.io.IOException) {
            assertThat(failure.message).isEqualTo("Journal commit failed")
        }
        assertThat(returned).isFalse()
        assertThat(kms.storeAttemptExpiries).isEmpty()
    }

    @Test
    fun `cancelled producer boundary cannot dispatch KMS`() = runTest {
        try {
            service.protectBytes(protectRequest(), "synthetic preparation payload".encodeToByteArray()) {
                throw CancellationException("Preparation owner retired")
            }
            org.junit.Assert.fail("Cancelled preparation returned a carrier")
        } catch (_: CancellationException) {
            }
        assertThat(kms.storeAttemptExpiries).isEmpty()
    }

    @Test
    fun `byte content round trips through split key envelope`() = runTest {
        val plaintext = "commercial secret".encodeToByteArray()
        val protected = service.protectBytes(protectRequest(), plaintext)

        val decrypted = service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)

        assertThat(decrypted).isEqualTo(plaintext)
        assertThat(kms.storedShareFor(CONTENT_ID)).isNotEqualTo(plaintext)
        assertThat(protected.ciphertext).isNotEqualTo(plaintext)
    }

    @Test
    fun `online release preserves lifecycle operation context across batch worker`() = runTest {
        val protected = service.protectBytes(protectRequest(), "operation-bound secret".encodeToByteArray())

        withContext(LifecycleOperationContext(RECEIVE_OPERATION_ID, FullAppLifecycleId.LC_13)) {
            service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }

        assertThat(kms.releaseOperationIds).containsExactly(RECEIVE_OPERATION_ID)
    }

    @Test
    fun `cancelled plain release retains transport context and clears late key`() = runBlocking {
        val controlledKms = GatedBatchKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 1, "plain-cancel").single()
        val original = async {
            controlledService.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }
        val batch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        original.cancel()
        assertThat(runCatching { original.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(batch.single().immutableContextSha256).isEqualTo(protected.header.immutableContextSha256)
        controlledKms.releaseNextBatch()
        val returned = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returnedBatches.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (returned.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(10)
        }
        assertThat(returned.single().kmsShare).isEqualTo(ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES))
    }

    @Test
    fun `timeline bridge bounds four foreground releases and prioritizes foreground over queued history`() = runBlocking {
        val controlledKms = GatedBatchKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 11, "priority")
        val ownerToken = controlledService.openTimelineReleaseScope("room-hash:1000")
        val firstWave = protected.take(4).mapIndexed { index, content ->
            async {
                controlledService.decryptBytes(
                    releaseRequest(content.header).copy(
                        timelineReleaseIntent = timelineIntent(
                            index,
                            TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                            "room-hash:1000",
                            ownerToken,
                        ),
                    ),
                    content.ciphertext,
                )
            }
        }
        val firstBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        assertThat(firstBatch).hasSize(4)

        val historyFlood = protected.subList(4, 10).mapIndexed { offset, content ->
            async(start = CoroutineStart.UNDISPATCHED) {
                controlledService.decryptBytes(
                    releaseRequest(content.header).copy(
                        timelineReleaseIntent = timelineIntent(
                            index = offset + 4,
                            releaseClass = TimelineContentReleaseClass.HISTORY_PREFETCH,
                            scopeId = "room-hash:1000",
                            ownerToken = ownerToken,
                        ),
                    ),
                    content.ciphertext,
                )
            }
        }
        val foregroundContent = protected.last()
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            controlledService.decryptBytes(
                releaseRequest(foregroundContent.header).copy(
                    timelineReleaseIntent = timelineIntent(
                        10,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1000",
                        ownerToken,
                    ),
                ),
                foregroundContent.ciphertext,
            )
        }

        // decryptBytes crosses withContext(Dispatchers.IO), so UNDISPATCHED alone cannot prove
        // scheduler admission. Observe the read-only scheduler counts before releasing the gate.
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (
                controlledService.pendingTimelineReleaseAdmissionsForTest(
                    TimelineContentReleaseClass.HISTORY_PREFETCH
                ) != historyFlood.size ||
                controlledService.pendingTimelineReleaseAdmissionsForTest(
                    TimelineContentReleaseClass.FOREGROUND_VISIBLE
                ) != 1
            ) {
                delay(1)
            }
        }

        controlledKms.releaseNextBatch()
        val secondBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        assertThat(secondBatch.map { it.contentId }).contains(foregroundContent.header.contentId)

        repeat(8) { controlledKms.releaseNextBatch() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            (firstWave + historyFlood + foreground).forEach { it.await() }
        }
    }

    @Test
    fun `owner viewport update reprioritizes only pending content scheduler work`() = runBlocking {
        val controlledKms = GatedBatchKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 10, "viewport")
        val scopeId = "room-hash:viewport"
        val ownerToken = controlledService.openTimelineReleaseScope(scopeId)
        val firstWave = protected.take(4).mapIndexed { index, content ->
            async {
                controlledService.decryptBytes(
                    releaseRequest(content.header).copy(
                        timelineReleaseIntent = timelineIntent(
                            index,
                            TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                            scopeId,
                            ownerToken,
                        ),
                    ),
                    content.ciphertext,
                )
            }
        }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }

        val targetIndex = 4
        val pending = protected.drop(4).mapIndexed { offset, content ->
            val index = offset + 4
            async(start = CoroutineStart.UNDISPATCHED) {
                controlledService.decryptBytes(
                    releaseRequest(content.header).copy(
                        timelineReleaseIntent = timelineIntent(
                            index = index,
                            releaseClass = if (index == targetIndex) {
                                TimelineContentReleaseClass.HISTORY_PREFETCH
                            } else {
                                TimelineContentReleaseClass.FOREGROUND_VISIBLE
                            },
                            scopeId = scopeId,
                            ownerToken = ownerToken,
                        ),
                    ),
                    content.ciphertext,
                )
            }
        }
        assertThat(
            controlledService.updateTimelineReleaseViewport(
                scopeId = scopeId,
                generation = 2,
                visibleRequestIds = listOf("request-$scopeId-$targetIndex"),
                ownerToken = TimelineReleaseScopeOwnerToken(ownerToken.value + 1),
            )
        ).isFalse()
        assertThat(
            controlledService.updateTimelineReleaseViewport(
                scopeId = scopeId,
                generation = 2,
                visibleRequestIds = listOf("request-$scopeId-$targetIndex"),
                ownerToken = ownerToken,
            )
        ).isTrue()
        assertThat(
            controlledService.updateTimelineReleaseViewport(
                scopeId = scopeId,
                generation = 2,
                visibleRequestIds = emptyList(),
                ownerToken = ownerToken,
            )
        ).isFalse()

        controlledKms.releaseNextBatch()
        val nextBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        assertThat(nextBatch.map { it.contentId }).contains(protected[targetIndex].header.contentId)
        repeat(12) { controlledKms.releaseNextBatch() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { (firstWave + pending).forEach { it.await() } }
    }

    @Test
    fun `timeline bridge preserves lifecycle context`() = runBlocking {
        val recordingKms = InMemoryKmsShareClient()
        val controlledService = serviceWithKms(recordingKms)
        val protected = protectDistinctContent(controlledService, 1, "context").single()
        val ownerToken = controlledService.openTimelineReleaseScope("room-hash:1001")

        withContext(LifecycleOperationContext(RECEIVE_OPERATION_ID, FullAppLifecycleId.LC_13)) {
            controlledService.decryptBytes(
                releaseRequest(protected.header).copy(
                    timelineReleaseIntent = timelineIntent(
                        0,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1001",
                        ownerToken,
                    ),
                ),
                protected.ciphertext,
            )
        }

        assertThat(recordingKms.releaseOperationIds).containsExactly(RECEIVE_OPERATION_ID)
        Unit
    }

    @Test
    fun `history flood reserves three physical timeline slots for foreground`() = runBlocking {
        val kms = OperationGatedKmsShareClient()
        val service = serviceWithKms(kms)
        val contents = protectDistinctContent(service, 21, "reserve")
        val owner = service.openTimelineReleaseScope("reserve-scope")
        val history = contents.take(20).mapIndexed { i, content ->
            async {
            withContext(LifecycleOperationContext("history-$i", FullAppLifecycleId.LC_13)) {
                service.decryptBytes(releaseRequest(content.header).copy(
                    timelineReleaseIntent = timelineIntent(i, TimelineContentReleaseClass.HISTORY_PREFETCH, "reserve-scope", owner),
                ), content.ciphertext)
            }
        }
        }
        try {
            val first = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(first.operationId).startsWith("history-")
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                while (service.pendingTimelineReleaseAdmissionsForTest(TimelineContentReleaseClass.HISTORY_PREFETCH) != 19) delay(1)
            }
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
            val foreground = async {
                withContext(LifecycleOperationContext("current-message", FullAppLifecycleId.LC_13)) {
                    service.decryptBytes(releaseRequest(contents.last().header).copy(
                        timelineReleaseIntent = timelineIntent(20, TimelineContentReleaseClass.FOREGROUND_VISIBLE, "reserve-scope", owner),
                    ), contents.last().ciphertext)
                }
            }
            val current = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
            assertThat(current.operationId).isEqualTo("current-message")
            current.permit.complete(Unit)
            assertThat(withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { foreground.await() }.decodeToString()).isEqualTo("reserve-20")
            assertThat(first.permit.isCompleted).isFalse()
        } finally {
            kms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { history.forEach { it.await() } }
        }
    }

    @Test
    fun `owner closed during same operation wait never starts obsolete HTTP`() = runBlocking {
        val kms = OperationGatedKmsShareClient()
        val service = serviceWithKms(kms)
        val contents = protectDistinctContent(service, 2, "queued-owner")
        val owner = service.openTimelineReleaseScope("queued-scope")
        val active = async {
            withContext(LifecycleOperationContext("same-owner-operation", FullAppLifecycleId.LC_13)) {
                service.decryptBytes(releaseRequest(contents[0].header), contents[0].ciphertext)
            }
        }
        val first = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { kms.started.receive() }
        val queued = async {
            withContext(LifecycleOperationContext("same-owner-operation", FullAppLifecycleId.LC_13)) {
                service.decryptBytes(releaseRequest(contents[1].header).copy(
                    timelineReleaseIntent = timelineIntent(1, TimelineContentReleaseClass.HISTORY_PREFETCH, "queued-scope", owner),
                ), contents[1].ciphertext)
            }
        }
        try {
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
            service.closeTimelineReleaseScope("queued-scope", owner)
            assertThat(runCatching { queued.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            first.permit.complete(Unit)
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { active.await() }
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { kms.started.receive() }).isNull()
        } finally {
            kms.releaseAll()
            active.cancel()
            queued.cancel()
        }
    }

    @Test
    fun `independent lifecycle release reaches transport while history operation is blocked`() = runBlocking {
        val controlledKms = OperationGatedKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 2, "operation-parallel")
        val owner = controlledService.openTimelineReleaseScope("parallel-scope")
        val history = async {
            withContext(LifecycleOperationContext("history-operation", FullAppLifecycleId.LC_13)) {
                controlledService.decryptBytes(
                    releaseRequest(protected[0].header).copy(
                        timelineReleaseIntent = timelineIntent(0, TimelineContentReleaseClass.HISTORY_PREFETCH, "parallel-scope", owner),
                    ),
                    protected[0].ciphertext,
                )
            }
        }
        val first = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
        val foreground = async {
            withContext(LifecycleOperationContext("foreground-operation", FullAppLifecycleId.LC_13)) {
                controlledService.decryptBytes(
                    releaseRequest(protected[1].header).copy(
                        timelineReleaseIntent = timelineIntent(1, TimelineContentReleaseClass.FOREGROUND_VISIBLE, "parallel-scope", owner),
                    ),
                    protected[1].ciphertext,
                )
            }
        }
        try {
            val second = withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }
            assertThat(second).isNotNull()
            assertThat(first.operationId).isEqualTo("history-operation")
            assertThat(requireNotNull(second).operationId).isEqualTo("foreground-operation")
            assertThat(second.requests.single().contentId).isEqualTo(protected[1].header.contentId)
            assertThat(first.requests.single().contentId).isEqualTo(protected[0].header.contentId)
            second.permit.complete(Unit)
            assertThat(withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { foreground.await() }.decodeToString()).isEqualTo("operation-parallel-1")
            assertThat(history.isActive).isTrue()
        } finally {
            controlledKms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                history.await()
                foreground.await()
            }
        }
    }

    @Test
    fun `operation transport is bounded at four and does not combine lifecycle identities`() = runBlocking {
        val controlledKms = OperationGatedKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 5, "operation-bound")
        val requests = protected.mapIndexed { index, content ->
            async {
                withContext(LifecycleOperationContext("bounded-operation-$index", FullAppLifecycleId.LC_13)) {
                    controlledService.decryptBytes(releaseRequest(content.header), content.ciphertext)
                }
            }
        }
        try {
            val firstFour = (0 until 4).map {
                withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }
            }
            assertThat(firstFour).doesNotContain(null)
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }).isNull()
            firstFour.filterNotNull().forEach { batch ->
                assertThat(batch.requests).hasSize(1)
                val index = protected.indexOfFirst { it.header.contentId == batch.requests.single().contentId }
                assertThat(batch.operationId).isEqualTo("bounded-operation-$index")
            }
            requireNotNull(firstFour.first()).permit.complete(Unit)
            val fifth = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
            assertThat(fifth.operationId).isNotNull()
        } finally {
            controlledKms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { requests.forEach { it.await() } }
        }
    }

    @Test
    fun `same lifecycle transport batches retain order while another operation progresses`() = runBlocking {
        val controlledKms = OperationGatedKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 3, "operation-order")
        fun release(index: Int, operationId: String) = async {
            withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13)) {
                controlledService.decryptBytes(releaseRequest(protected[index].header), protected[index].ciphertext)
            }
        }
        val first = release(0, "same-operation")
        val firstBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
        val same = release(1, "same-operation")
        val other = release(2, "other-operation")
        try {
            val independent = withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }
            assertThat(independent).isNotNull()
            assertThat(requireNotNull(independent).operationId).isEqualTo("other-operation")
            assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }).isNull()
            firstBatch.permit.complete(Unit)
            val successor = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
            assertThat(successor.operationId).isEqualTo("same-operation")
            assertThat(successor.requests.single().contentId).isEqualTo(protected[1].header.contentId)
        } finally {
            controlledKms.releaseAll()
            withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
                first.await()
                same.await()
                other.await()
            }
        }
    }

    @Test
    fun `cancelled operation transport retains request bytes and clears its late share`() = runBlocking {
        val controlledKms = OperationGatedKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 1, "parallel-cancel").single()
        val original = async {
            withContext(LifecycleOperationContext("cancel-operation", FullAppLifecycleId.LC_13)) {
                controlledService.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
            }
        }
        val batch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
        original.cancel()
        assertThat(runCatching { original.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(batch.requests.single().immutableContextSha256).isEqualTo(protected.header.immutableContextSha256)
        controlledKms.releaseAll()
        val returned = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returned.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (returned.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(1)
        }
    }

    @Test
    fun `cache invalidation rejects active and waiting operation transport results`() = runBlocking {
        val controlledKms = OperationGatedKmsShareClient()
        val controlledRuntime = TestMatrixContentProtectionRuntime()
        val controlledService = serviceWithKms(controlledKms, controlledRuntime)
        val protected = protectDistinctContent(controlledService, 2, "parallel-invalidation")
        fun release(index: Int) = async {
            runCatching {
                withContext(LifecycleOperationContext("invalidation-operation", FullAppLifecycleId.LC_13)) {
                    controlledService.decryptBytes(releaseRequest(protected[index].header), protected[index].ciphertext)
                }
            }
        }
        val first = release(0)
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.started.receive() }
        val waiting = release(1)
        assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.started.receive() }).isNull()
        controlledRuntime.clearSensitiveCaches()
        controlledKms.releaseAll()
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            assertThat(first.await().isFailure).isTrue()
            assertThat(waiting.await().isFailure).isTrue()
        }
        val returned = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returned.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (returned.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(1)
        }
    }

    @Test
    fun `cancelled timeline transport does not reject a fresh nonce replacement as duplicate`() = runBlocking {
        val controlledKms = GatedBatchKmsShareClient()
        val controlledService = serviceWithKms(controlledKms)
        val protected = protectDistinctContent(controlledService, 1, "replacement").single()
        val scopeId = "room-hash:replacement"
        val ownerToken = controlledService.openTimelineReleaseScope(scopeId)
        val intent = timelineIntent(
            index = 0,
            releaseClass = TimelineContentReleaseClass.FOREGROUND_VISIBLE,
            scopeId = scopeId,
            ownerToken = ownerToken,
        )
        val original = async {
            controlledService.decryptBytes(
                releaseRequest(protected.header).copy(
                    requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 1 },
                    timelineReleaseIntent = intent,
                ),
                protected.ciphertext,
            )
        }
        val originalBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        assertThat(originalBatch.single().requestNonce)
            .isEqualTo(ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 1 })

        controlledService.cancelTimelineReleaseRequests(scopeId, setOf(intent.requestId), ownerToken)
        assertThat(runCatching { original.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        // The transport may still serialize this request after the caller has cleared its temporary context.
        assertThat(originalBatch.single().immutableContextSha256).isEqualTo(protected.header.immutableContextSha256)

        val replacement = async {
            controlledService.decryptBytes(
                releaseRequest(protected.header).copy(
                    requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 2 },
                    timelineReleaseIntent = intent,
                ),
                protected.ciphertext,
            )
        }
        delay(NO_EXTRA_BATCH_MILLIS)
        assertThat(replacement.isActive).isTrue()

        controlledKms.releaseNextBatch()
        val staleReturned = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returnedBatches.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (staleReturned.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(10)
        }
        val replacementBatch = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        assertThat(replacementBatch.single().requestNonce)
            .isEqualTo(ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 2 })
        controlledKms.releaseNextBatch()
        assertThat(withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { replacement.await() }.decodeToString())
            .isEqualTo("replacement-0")
    }

    @Test
    fun `timeline bridge rejects a scope that its owner did not open`() = runBlocking {
        val recordingKms = InMemoryKmsShareClient()
        val controlledService = serviceWithKms(recordingKms)
        val protected = protectDistinctContent(controlledService, 1, "unopened").single()

        val result = runCatching {
            controlledService.decryptBytes(
                releaseRequest(protected.header).copy(
                    timelineReleaseIntent = timelineIntent(0, TimelineContentReleaseClass.FOREGROUND_VISIBLE, "room-hash:2000"),
                ),
                protected.ciphertext,
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(recordingKms.releaseCount).isEqualTo(0)
    }

    @Test
    fun `independent timeline owners may register out of numeric order`() = runBlocking {
        val recordingKms = InMemoryKmsShareClient()
        val controlledService = serviceWithKms(recordingKms)
        val protected = protectDistinctContent(controlledService, 1, "out-of-order").single()
        val ownerB = controlledService.openTimelineReleaseScope("room-b:3000")
        controlledService.closeTimelineReleaseScope("room-b:3000", ownerB)
        val ownerA = controlledService.openTimelineReleaseScope("room-a:2999")

        val plaintext = controlledService.decryptBytes(
            releaseRequest(protected.header).copy(
                timelineReleaseIntent = timelineIntent(
                    0,
                    TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                    "room-a:2999",
                    ownerA,
                ),
            ),
            protected.ciphertext,
        )

        assertThat(plaintext.decodeToString()).isEqualTo("out-of-order-0")
    }

    @Test
    fun `independent active timeline owners coexist`() = runBlocking {
        val recordingKms = InMemoryKmsShareClient()
        val controlledService = serviceWithKms(recordingKms)
        val protected = protectDistinctContent(controlledService, 2, "coexist")
        val ownerB = controlledService.openTimelineReleaseScope("room-b:4000")
        val ownerA = controlledService.openTimelineReleaseScope("room-a:3999")

        val plaintextB = controlledService.decryptBytes(
            releaseRequest(protected[0].header).copy(
                timelineReleaseIntent = timelineIntent(
                    0,
                    TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                    "room-b:4000",
                    ownerB,
                ),
            ),
            protected[0].ciphertext,
        )
        val plaintextA = controlledService.decryptBytes(
            releaseRequest(protected[1].header).copy(
                timelineReleaseIntent = timelineIntent(
                    1,
                    TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                    "room-a:3999",
                    ownerA,
                ),
            ),
            protected[1].ciphertext,
        )

        assertThat(plaintextB.decodeToString()).isEqualTo("coexist-0")
        assertThat(plaintextA.decodeToString()).isEqualTo("coexist-1")
    }

    @Test
    fun `closed and cleared timeline scopes reject late enqueue and clear late shares`() = runBlocking {
        val controlledKms = GatedBatchKmsShareClient()
        val controlledRuntime = TestMatrixContentProtectionRuntime()
        val controlledService = serviceWithKms(controlledKms, controlledRuntime)
        val protected = protectDistinctContent(controlledService, 4, "cancel")
        val closedScopeOwner = controlledService.openTimelineReleaseScope("room-hash:1002")
        val active = async {
            controlledService.decryptBytes(
                releaseRequest(protected[0].header).copy(
                    timelineReleaseIntent = timelineIntent(
                        0,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1002",
                        closedScopeOwner,
                    ),
                ),
                protected[0].ciphertext,
            )
        }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }

        controlledService.closeTimelineReleaseScope("room-hash:1002", closedScopeOwner)
        assertThat(runCatching { active.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        controlledKms.releaseNextBatch()
        val returned = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returnedBatches.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (returned.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(10)
        }

        val lateAfterClose = runCatching {
            withTimeout(NO_EXTRA_BATCH_MILLIS) {
                controlledService.decryptBytes(
                    releaseRequest(protected[1].header).copy(
                        timelineReleaseIntent = timelineIntent(
                            1,
                            TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                            "room-hash:1002",
                            closedScopeOwner,
                        ),
                    ),
                    protected[1].ciphertext,
                )
            }
        }
        assertThat(lateAfterClose.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.startedBatches.receive() }).isNull()

        val clearScopeOwner = controlledService.openTimelineReleaseScope("room-hash:1003")
        val activeBeforeClear = async {
            controlledService.decryptBytes(
                releaseRequest(protected[2].header).copy(
                    timelineReleaseIntent = timelineIntent(
                        2,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1003",
                        clearScopeOwner,
                    ),
                ),
                protected[2].ciphertext,
            )
        }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        controlledRuntime.clearSensitiveCaches()
        assertThat(runCatching { activeBeforeClear.await() }.exceptionOrNull()).isNotNull()
        controlledKms.releaseNextBatch()
        val returnedAfterClear = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.returnedBatches.receive() }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            while (returnedAfterClear.any { share -> share.kmsShare.any { it != 0.toByte() } }) delay(10)
        }

        val lateAfterClear = runCatching {
            withTimeout(NO_EXTRA_BATCH_MILLIS) {
                controlledService.decryptBytes(
                    releaseRequest(protected[3].header).copy(
                        timelineReleaseIntent = timelineIntent(
                            3,
                            TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                            "room-hash:1003",
                            clearScopeOwner,
                        ),
                    ),
                    protected[3].ciphertext,
                )
            }
        }
        assertThat(lateAfterClear.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(withTimeoutOrNull(NO_EXTRA_BATCH_MILLIS) { controlledKms.startedBatches.receive() }).isNull()

        val reopenedOwner = controlledService.openTimelineReleaseScope("room-hash:1003")
        controlledService.closeTimelineReleaseScope("room-hash:1003", clearScopeOwner)
        val reopened = async {
            controlledService.decryptBytes(
                releaseRequest(protected[3].header).copy(
                    timelineReleaseIntent = timelineIntent(
                        3,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1003",
                        reopenedOwner,
                    ),
                ),
                protected[3].ciphertext,
            )
        }
        withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { controlledKms.startedBatches.receive() }
        controlledKms.releaseNextBatch()
        assertThat(withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) { reopened.await() }.decodeToString()).isEqualTo("cancel-3")
    }

    @Test
    fun `transport cancellation does not terminate the four timeline workers`() = runBlocking {
        val cancellingKms = CancellingBatchKmsShareClient(expectedFailedRequestCount = 4)
        val controlledService = serviceWithKms(cancellingKms)
        val protected = protectDistinctContent(controlledService, 5, "worker")
        val ownerToken = controlledService.openTimelineReleaseScope("room-hash:1006")

        val failures = supervisorScope {
            protected.take(4).mapIndexed { index, content ->
                async {
                    runCatching {
                        controlledService.decryptBytes(
                            releaseRequest(content.header).copy(
                                timelineReleaseIntent = timelineIntent(
                                    index,
                                    TimelineContentReleaseClass.HISTORY_PREFETCH,
                                    "room-hash:1006",
                                    ownerToken,
                                ),
                            ),
                            content.ciphertext,
                        )
                    }
                }
            }.map { it.await() }
        }
        assertThat(failures.all { it.exceptionOrNull() is CancellationException }).isTrue()

        val successor = withTimeout(BRIDGE_TEST_TIMEOUT_MILLIS) {
            controlledService.decryptBytes(
                releaseRequest(protected.last().header).copy(
                    timelineReleaseIntent = timelineIntent(
                        4,
                        TimelineContentReleaseClass.FOREGROUND_VISIBLE,
                        "room-hash:1006",
                        ownerToken,
                    ),
                ),
                protected.last().ciphertext,
            )
        }
        assertThat(successor.decodeToString()).isEqualTo("worker-4")
    }

    @Test
    fun `v3 room epoch release delegates only retention upper bound to online KMS`() = runTest {
        val gate = RecordingStageThreeSecurityGate()
        val protectedService = serviceWithGate(gate)
        val protected = protectedService.protectBytes(protectRequest(), "v3 online".encodeToByteArray())

        val plaintext = protectedService.decryptBytes(releaseRequest(protected.header), protected.ciphertext)

        assertThat(plaintext.decodeToString()).isEqualTo("v3 online")
        val release = gate.requests.single { it.operation == io.element.android.security.contentprotection.api.StageThreeOperation.RELEASE }
        assertThat(release.releaseRetentionAuthority).isEqualTo(StageThreeReleaseRetentionAuthority.ONLINE_KMS)
        assertThat(release.offlineLeaseMode).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `legacy bounded offline release keeps local retention authority`() = runTest {
        val gate = RecordingStageThreeSecurityGate()
        val protectedService = serviceWithGate(gate)
        val protected = protectedService.protectBytes(
            protectRequest().copy(offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE),
            "legacy offline".encodeToByteArray(),
        )

        runCatchingExceptions {
            protectedService.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }

        val release = gate.requests.single { it.operation == io.element.android.security.contentprotection.api.StageThreeOperation.RELEASE }
        assertThat(release.releaseRetentionAuthority).isEqualTo(StageThreeReleaseRetentionAuthority.LOCAL_POLICY)
    }

    @Test
    fun `expired v3 contract refreshes once before rebuilding the complete envelope`() = runTest {
        val oldContract = roomLifecycle("contract_012345678901234567890120", EXPIRY)
        val freshExpiry = EXPIRY + 60_000
        kms.resolvedRoomLifecycle = roomLifecycle("contract_012345678901234567890121", freshExpiry)
        kms.lifecycleContractExpiryFailures = 1

        val protected = service.protectBytes(
            protectRequest().copy(roomLifecycle = oldContract),
            "fresh contract".encodeToByteArray(),
        )

        assertThat(kms.storeAttemptExpiries).containsExactly(EXPIRY, freshExpiry).inOrder()
        assertThat(kms.storeAttemptContractIds).containsExactly(
            "contract_012345678901234567890120",
            "contract_012345678901234567890121",
        ).inOrder()
        assertThat(kms.resolveRoomLifecycleCount).isEqualTo(1)
        assertThat(protected.header.policy.expiresAtEpochMillis).isEqualTo(freshExpiry)
        assertThat(service.decryptBytes(releaseRequest(protected.header), protected.ciphertext).decodeToString())
            .isEqualTo("fresh contract")
    }

    @Test
    fun `a second expired v3 contract fails closed without an unbounded retry`() = runTest {
        val oldContract = roomLifecycle("contract_012345678901234567890122", EXPIRY)
        val freshExpiry = EXPIRY + 60_000
        kms.resolvedRoomLifecycle = roomLifecycle("contract_012345678901234567890123", freshExpiry)
        kms.lifecycleContractExpiryFailures = 2

        val result = runCatchingExceptions {
            service.protectBytes(protectRequest().copy(roomLifecycle = oldContract), "never stored".encodeToByteArray())
        }

        assertThat(result.exceptionOrNull())
            .isInstanceOf(ContentProtectionException.LifecycleContractRefreshRequired::class.java)
        assertThat(kms.storeAttemptExpiries).containsExactly(EXPIRY, freshExpiry).inOrder()
        assertThat(kms.resolveRoomLifecycleCount).isEqualTo(1)
        assertThat(kms.hasStoredShare(CONTENT_ID)).isFalse()
    }

    @Test
    fun `repeated decrypt uses Vault protected session CEK cache`() = runTest {
        val plaintext = "cached commercial secret".encodeToByteArray()
        val protected = service.protectBytes(protectRequest(), plaintext)

        assertThat(service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)).isEqualTo(plaintext)
        assertThat(service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)).isEqualTo(plaintext)

        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `LC13 receive confirms KMS authority even when session CEK is cached`() = runTest {
        val plaintext = "operation-bound cached secret".encodeToByteArray()
        val protected = service.protectBytes(protectRequest(), plaintext)
        service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)

        val decrypted = withContext(LifecycleOperationContext(RECEIVE_OPERATION_ID, FullAppLifecycleId.LC_13)) {
            service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }

        assertThat(decrypted).isEqualTo(plaintext)
        assertThat(kms.releaseCount).isEqualTo(2)
        assertThat(kms.releaseOperationIds).containsExactly(null, RECEIVE_OPERATION_ID).inOrder()
    }

    @Test
    fun `LC15 relay retry confirms KMS authority even when session CEK is cached`() = runTest {
        val plaintext = "operation-bound relay edit".encodeToByteArray()
        val protected = service.protectBytes(protectRequest(), plaintext)
        service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        val operationId = "relay-edit-original-0001"
        val decrypted = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }
        assertThat(decrypted).isEqualTo(plaintext)
        assertThat(kms.releaseCount).isEqualTo(2)
        assertThat(kms.releaseOperationIds).containsExactly(null, operationId).inOrder()
    }

    @Test
    fun `runtime cache clear forces a new KMS release`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())
        service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)

        runtime.clearSensitiveCaches()
        service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)

        assertThat(kms.releaseCount).isEqualTo(2)
    }

    @Test
    fun `lost burn end response still clears session key and persists local revocation`() = runTest {
        val protected = service.protectBytes(protectRequest(), "one time secret".encodeToByteArray())
        service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        kms.failBurnEnd = true

        val end = runCatchingExceptions {
            service.endBurnAfterRead(
                VAULT_ID,
                EndBurnAfterReadRequest(
                    contentId = ContentId(CONTENT_ID),
                    accountIdHash = ACCOUNT_HASH,
                    requestingInstallationId = INSTALLATION_ID,
                    sessionId = "burn-session-00000000000001",
                    reason = BurnAfterReadEndReason.APP_LOCKED,
                ),
            )
        }
        val reopen = runCatchingExceptions {
            service.decryptBytes(releaseRequest(protected.header), protected.ciphertext)
        }

        assertThat(end.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        assertThat(reopen.exceptionOrNull()).isInstanceOf(ContentProtectionException.Revoked::class.java)
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test
    fun `room substitution fails before KMS release`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())

        val result = runCatchingExceptions {
            service.decryptBytes(releaseRequest(protected.header).copy(roomId = "!other:example.org"), protected.ciphertext)
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.InvalidEnvelope::class.java)
        assertThat(kms.releaseCount).isEqualTo(0)
    }

    @Test
    fun `tampered ciphertext is rejected`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())
        protected.ciphertext[0] = (protected.ciphertext[0].toInt() xor 1).toByte()

        val result = runCatchingExceptions { service.decryptBytes(releaseRequest(protected.header), protected.ciphertext) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
    }

    @Test
    fun `destroyed KMS share cannot be recovered from ciphertext`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())
        assertThat(service.destroyContentAccess(VAULT_ID, ContentId(CONTENT_ID), ContentAccessDestroyReason.RETENTION_EXPIRED)).isTrue()

        val result = runCatchingExceptions { service.decryptBytes(releaseRequest(protected.header), protected.ciphertext) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Revoked::class.java)
    }

    @Test
    fun `management cleanup persists local tombstone without repeating remote KMS deletion`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())

        assertThat(service.revokeLocalContent(VAULT_ID, ContentId(CONTENT_ID))).isTrue()
        assertThat(kms.storedShareFor(CONTENT_ID)).isNotEmpty()
        val result = runCatchingExceptions { service.decryptBytes(releaseRequest(protected.header), protected.ciphertext) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Revoked::class.java)
        assertThat(kms.releaseCount).isEqualTo(0)
    }

    @Test
    fun `restoring an old KMS backup cannot resurrect locally revoked content`() = runTest {
        val protected = service.protectBytes(protectRequest(), "secret".encodeToByteArray())
        val oldBackup = kms.storedShareFor(CONTENT_ID)
        service.destroyContentAccess(VAULT_ID, ContentId(CONTENT_ID), ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
        kms.restoreShare(CONTENT_ID, oldBackup)

        val result = runCatchingExceptions { service.decryptBytes(releaseRequest(protected.header), protected.ciphertext) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Revoked::class.java)
        assertThat(kms.releaseCount).isEqualTo(0)
    }

    @Test
    fun `chunked stream round trips and authenticates final chunk`() = runTest {
        val plaintext = ByteArray(150_000) { (it % 251).toByte() }
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream(plaintext), ciphertext)
        val decrypted = ByteArrayOutputStream()

        service.decryptStream(releaseRequest(header), ByteArrayInputStream(ciphertext.toByteArray()), decrypted)

        assertThat(decrypted.toByteArray()).isEqualTo(plaintext)
    }

    @Test
    fun `media stream authority preserves the validated online release`() = runTest {
        val plaintext = ByteArray(70_000) { (it % 251).toByte() }
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream(plaintext), ciphertext)
        kms.releaseGrantId = "online-grant-verified"
        kms.releaseExpiresAtEpochMillis = NOW + 2_000
        kms.releaseAuthorizationDeadlineEpochMillis = kms.releaseExpiresAtEpochMillis
        val decrypted = ByteArrayOutputStream()

        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            decrypted,
        )

        assertThat(decrypted.toByteArray()).isEqualTo(plaintext)
        assertThat(authority.description.authorityId).isEqualTo("online-grant-verified")
        assertThat(authority.description.releaseExpiresAtEpochMillis).isEqualTo(NOW + 2_000)
        assertThat(authority.description.authorizationDeadlineEpochMillis).isEqualTo(NOW + 2_000)
        assertThat(authority.description.bootCount).isEqualTo(BOOT_COUNT)
        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.VALIDATED)

        authority.close()
        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.CLOSED)
    }

    @Test
    fun `media decrypt crossing deadline fails without issuing authority`() = runTest {
        val plaintext = ByteArray(140_000) { (it % 251).toByte() }
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream(plaintext), ciphertext)
        kms.releaseExpiresAtEpochMillis = NOW + 50
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 50
        val decrypted = AdvancingOutputStream(authorityClock, advanceByMillis = 50)

        val result = runCatchingExceptions {
            service.decryptMediaStreamWithAuthority(
                releaseRequest(header),
                ByteArrayInputStream(ciphertext.toByteArray()),
                decrypted,
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        assertThat(decrypted.size()).isGreaterThan(0)
    }

    @Test
    fun `authority issuance point rejects deadline reached by final decrypt write`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(
            protectRequest(),
            ByteArrayInputStream("last authenticated chunk".encodeToByteArray()),
            ciphertext,
        )
        kms.releaseExpiresAtEpochMillis = NOW + 1
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 1
        val decrypted = AdvancingOutputStream(authorityClock, advanceByMillis = 1)

        val result = runCatchingExceptions {
            service.decryptMediaStreamWithAuthority(
                releaseRequest(header),
                ByteArrayInputStream(ciphertext.toByteArray()),
                decrypted,
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        assertThat(decrypted.toByteArray()).isEqualTo("last authenticated chunk".encodeToByteArray())
    }

    @Test
    fun `online sixty second grant expires despite idle preview and old supplied time`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val request = protectRequest()
        val header = service.protectStream(
            request.copy(
                kind = ContentKind.ATTACHMENT,
                offlineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
                policy = request.policy.copy(expiresAtEpochMillis = NOW + 120_000),
            ),
            ByteArrayInputStream("PDF fixture".encodeToByteArray()),
            ciphertext,
        )
        kms.releaseIssuedAtEpochMillis = NOW
        kms.releaseExpiresAtEpochMillis = NOW + 60_000
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 60_000
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header), ByteArrayInputStream(ciphertext.toByteArray()), ByteArrayOutputStream(),
        )
        assertThat(authority.description.mode).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
        authorityClock.elapsedMillis += 59_999
        assertThat(service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L))
            .isEqualTo(ProtectedMediaReleaseAuthorityDecision.VALIDATED)
        authorityClock.wallEpochMillis = NOW - 1_000
        authorityClock.elapsedMillis += 1
        repeat(3) {
            assertThat(service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L))
                .isEqualTo(ProtectedMediaReleaseAuthorityDecision.EXPIRED)
        }
        authority.close()
        assertThat(service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L))
            .isEqualTo(ProtectedMediaReleaseAuthorityDecision.CLOSED)
    }

    @Test
    fun `wall rollback cannot refresh authority while elapsed realtime advances`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        kms.releaseExpiresAtEpochMillis = NOW + 2_000
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 2_000
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        authorityClock.wallEpochMillis = NOW - 1_000
        authorityClock.elapsedMillis += 1_500
        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.VALIDATED)

        authorityClock.elapsedMillis += 500
        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.EXPIRED)
    }

    @Test
    fun `caller supplied old time cannot extend authority`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        kms.releaseExpiresAtEpochMillis = NOW + 500
        kms.releaseAuthorizationDeadlineEpochMillis = NOW + 500
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        authorityClock.elapsedMillis += 500

        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.EXPIRED)
    }

    @Test
    fun `boot mismatch invalidates issued authority`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        runtimeBinding.bootCount = BOOT_COUNT + 1

        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.BINDING_INVALID)
    }

    @Test
    fun `elapsed realtime rollback invalidates issued authority`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        authorityClock.elapsedMillis = ELAPSED_REALTIME - 1

        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.BINDING_INVALID)
    }

    @Test
    fun `unavailable internal wall clock fails authority validation closed`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        authorityClock.wallEpochMillis = 0L

        assertThat(
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE)
    }

    @Test
    fun `caller forged and copied media authority cannot authorize`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        val authority = service.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )
        val forged = object : ProtectedMediaReleaseAuthority {
            override val description = authority.description
            override fun close() = Unit
        }

        assertThat(
            service.validateProtectedMediaReleaseAuthority(forged, forged.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE)
        assertThat(
            service.validateProtectedMediaReleaseAuthority(
                authority,
                authority.description.copy(authorityId = "copied-description"),
                NOW,
            )
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.RELEASE_MISMATCH)
        assertThat(
            serviceWithKms(kms).validateProtectedMediaReleaseAuthority(authority, authority.description, NOW)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.OWNER_MISMATCH)
    }

    @Test
    fun `missing or expired release evidence fails before media authority issuance`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream("media".encodeToByteArray()), ciphertext)
        kms.releaseGrantId = ""
        val missing = runCatchingExceptions {
            service.decryptMediaStreamWithAuthority(
                releaseRequest(header),
                ByteArrayInputStream(ciphertext.toByteArray()),
                ByteArrayOutputStream(),
            )
        }
        kms.releaseGrantId = "online-grant-verified"
        kms.releaseAuthorizationDeadlineEpochMillis = NOW
        val expiredOutput = ByteArrayOutputStream()
        val expired = runCatchingExceptions {
            service.decryptMediaStreamWithAuthority(
                releaseRequest(header),
                ByteArrayInputStream(ciphertext.toByteArray()),
                expiredOutput,
            )
        }

        assertThat(missing.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        assertThat(expired.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        assertThat(expiredOutput.size()).isEqualTo(0)
    }

    @Test
    fun `bounded offline media authority keeps the lease id and earliest real deadline`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(
            protectRequest().copy(offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE),
            ByteArrayInputStream("offline media".encodeToByteArray()),
            ciphertext,
        )
        val offlineDeadline = NOW + 500
        val offlineService = serviceWithOffline(
            FixedOfflineLicenseService(kms::storedShareFor, authorizationDeadlineEpochMillis = offlineDeadline)
        )

        val authority = offlineService.decryptMediaStreamWithAuthority(
            releaseRequest(header),
            ByteArrayInputStream(ciphertext.toByteArray()),
            ByteArrayOutputStream(),
        )

        assertThat(authority.description.authorityId).isEqualTo("offline-lease-verified")
        assertThat(authority.description.mode).isEqualTo(OfflineLeaseMode.BOUNDED_OFFLINE)
        assertThat(authority.description.authorizationDeadlineEpochMillis).isEqualTo(offlineDeadline)
        authorityClock.elapsedMillis += 500
        assertThat(
            offlineService.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        ).isEqualTo(ProtectedMediaReleaseAuthorityDecision.EXPIRED)
    }

    @Test
    fun `failed authenticated stream returns no media authority`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream(ByteArray(70_000) { 7 }), ciphertext)
        val truncated = ciphertext.toByteArray().copyOf(ciphertext.size() - 1)

        val result = runCatchingExceptions {
            service.decryptMediaStreamWithAuthority(
                releaseRequest(header),
                ByteArrayInputStream(truncated),
                ByteArrayOutputStream(),
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
    }

    @Test
    fun `truncated chunked stream is rejected`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val header = service.protectStream(protectRequest(), ByteArrayInputStream(ByteArray(70_000) { 7 }), ciphertext)
        val truncated = ciphertext.toByteArray().copyOf(ciphertext.size() - 1)

        val result = runCatchingExceptions {
            service.decryptStream(releaseRequest(header), ByteArrayInputStream(truncated), ByteArrayOutputStream())
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
    }

    @Test
    fun `binary codec round trips the authenticated envelope`() = runTest {
        val codec = ContentEnvelopeBinaryCodec()
        val protected = service.protectBytes(
            protectRequest().copy(audienceAccountIdHashes = setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH)),
            "secret".encodeToByteArray(),
        )

        val decoded = codec.decode(codec.encode(protected))
        val decrypted = service.decryptBytes(releaseRequest(decoded.header), decoded.ciphertext)

        assertThat(decoded.header.formatVersion).isEqualTo(ContentProtectionFormat.CURRENT_VERSION)
        assertThat(decoded.header.audienceAccountIdHashes).isEmpty()
        assertThat(decoded.header.deviceShareEnvelopes).isEmpty()
        assertThat(decoded.header.roomEpoch).isNotNull()
        assertThat(decrypted.decodeToString()).isEqualTo("secret")
    }

    @Test
    fun `binary codec authenticates burn after read lifecycle metadata`() = runTest {
        val codec = ContentEnvelopeBinaryCodec()
        val protected = service.protectBytes(
            protectRequest().copy(
                audienceAccountIdHashes = setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH),
                lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
            ),
            "view once".encodeToByteArray(),
        )

        val decoded = codec.decode(codec.encode(protected))

        assertThat(decoded.header.lifecycle.mode).isEqualTo(MessageLifecycleMode.BURN_AFTER_READ)
        assertThat(decoded.header.lifecycle.conversationKind).isEqualTo(ConversationKind.DIRECT)
    }

    @Test
    fun `static location can be protected as burn after read content`() = runTest {
        val protected = service.protectBytes(
            protectRequest().copy(
                kind = ContentKind.LOCATION,
                audienceAccountIdHashes = setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH),
                lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
            ),
            "geo:26.10,111.03".encodeToByteArray(),
        )

        assertThat(protected.header.kind).isEqualTo(ContentKind.LOCATION)
        assertThat(protected.header.lifecycle.mode).isEqualTo(MessageLifecycleMode.BURN_AFTER_READ)
    }

    @Test
    fun `concurrent room epoch reads coalesce epoch release while authorizing every content share`() = runTest {
        var epochReleases = 0
        val controlledKms = object : InMemoryKmsShareClient() {
            override suspend fun releaseRoomEpoch(request: ReleaseRoomEpochRequest): ReleasedRoomEpoch {
                epochReleases++
                delay(100)
                return super.releaseRoomEpoch(request)
            }
        }
        val controlledRuntime = TestMatrixContentProtectionRuntime()
        val controlledService = serviceWithKms(controlledKms, controlledRuntime)
        val envelopes = List(4) { index ->
            controlledService.protectBytes(protectRequest().copy(contentId = ContentId("$CONTENT_ID-$index")), "message-$index".encodeToByteArray())
        }
        controlledRuntime.clearSensitiveCaches()
        val decrypted = envelopes.map { envelope ->
            async { controlledService.decryptBytes(releaseRequest(envelope.header), envelope.ciphertext).decodeToString() }
        }.awaitAll()
        assertThat(decrypted).containsExactly("message-0", "message-1", "message-2", "message-3").inOrder()
        assertThat(epochReleases).isEqualTo(1)
        assertThat(controlledKms.releaseCount).isEqualTo(4)
    }

    @Test
    fun `room epoch envelope size remains fixed through one thousand recipients`() = runTest {
        val codec = ContentEnvelopeBinaryCodec()
        val descriptor = deviceKeys.currentDescriptor()
        val encodedSizes = listOf(1, 100, 500, 1_000).mapIndexed { populationIndex, population ->
            val accounts: MutableSet<String> = (0 until population - 1).mapTo(mutableSetOf()) { index ->
                MessageDigest.getInstance("SHA-256")
                    .digest("account-$index".encodeToByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            accounts.add(ACCOUNT_HASH)
            val recipients = buildList {
                add(
                    DeviceShareRecipient(
                        installationId = descriptor.installationId,
                        keyId = descriptor.keyId,
                        algorithm = descriptor.algorithm,
                        publicKeyDer = descriptor.publicKeyDer,
                        allowsBoundedOffline = true,
                    )
                )
                repeat(population - 1) { index ->
                    add(
                        DeviceShareRecipient(
                            installationId = "synthetic-installation-${index.toString().padStart(4, '0')}",
                            keyId = "synthetic-key-${index.toString().padStart(4, '0')}",
                            algorithm = descriptor.algorithm,
                            publicKeyDer = descriptor.publicKeyDer,
                            allowsBoundedOffline = true,
                        )
                    )
                }
            }
            val envelope = service.protectBytes(
                protectRequest().copy(
                    contentId = ContentId("content-size-${populationIndex.toString().padStart(20, '0')}"),
                    audienceAccountIdHashes = accounts,
                    recipients = recipients,
                ),
                "same payload".encodeToByteArray(),
            )

            assertThat(envelope.header.audienceAccountIdHashes).isEmpty()
            assertThat(envelope.header.deviceShareEnvelopes).isEmpty()
            assertThat(envelope.header.roomEpoch).isNotNull()
            codec.encode(envelope).size
        }

        assertThat(encodedSizes.toSet()).hasSize(1)
    }

    @Test
    fun `room epoch protection rejects more than one thousand audience accounts`() = runTest {
        val accounts = (0 until ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS + 1).mapTo(mutableSetOf()) { index ->
            MessageDigest.getInstance("SHA-256")
                .digest("overflow-account-$index".encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        accounts.add(ACCOUNT_HASH)

        val result = runCatchingExceptions {
            service.protectBytes(
                protectRequest().copy(audienceAccountIdHashes = accounts),
                "same payload".encodeToByteArray(),
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a second audience account can release while an outsider is denied before KMS`() = runTest {
        val protected = service.protectBytes(
            protectRequest().copy(audienceAccountIdHashes = setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH)),
            "shared secret".encodeToByteArray(),
        )

        val audiencePlaintext = service.decryptBytes(
            releaseRequest(protected.header).copy(accountIdHash = SECOND_ACCOUNT_HASH),
            protected.ciphertext,
        )
        val releaseCountAfterAudience = kms.releaseCount
        val outsider = runCatchingExceptions {
            service.decryptBytes(
                releaseRequest(protected.header).copy(accountIdHash = OUTSIDER_ACCOUNT_HASH),
                protected.ciphertext,
            )
        }

        assertThat(audiencePlaintext.decodeToString()).isEqualTo("shared secret")
        assertThat(outsider.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(kms.releaseCount).isEqualTo(releaseCountAfterAudience)
    }

    private fun nextSixtySecondGrant(now: Long, id: String) {
        kms.releaseGrantId = id
        kms.releaseIssuedAtEpochMillis = now
        kms.releaseServerTimeEpochMillis = now
        kms.releaseExpiresAtEpochMillis = now + 60_000
        kms.releaseAuthorizationDeadlineEpochMillis = now + 60_000
    }

    private suspend fun renewablePdfAuthority(): ProtectedMediaReleaseAuthority {
        val ciphertext = ByteArrayOutputStream()
        val original = protectRequest()
        val header = service.protectStream(original.copy(kind = ContentKind.ATTACHMENT,
            offlineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
            policy = original.policy.copy(expiresAtEpochMillis = NOW + 3_600_000)),
            "PDF fixture".byteInputStream(), ciphertext)
        nextSixtySecondGrant(NOW, "pdf-grant-0")
        return service.decryptMediaStreamWithAuthority(releaseRequest(header), ciphertext.toByteArray().inputStream(), ByteArrayOutputStream())
    }

    @Test fun `fresh signed grants sustain PDF for more than five minutes without changing old grants`() = runTest {
        var authority = renewablePdfAuthority()
        assertThat(service.renewProtectedMediaReadAuthority(authority)).isNull()
        assertThat(kms.releaseCount).isEqualTo(1)
        repeat(9) { index ->
            val previous = authority
            val immutable = previous.description
            authorityClock.elapsedMillis += 40_000
            authorityClock.wallEpochMillis += 40_000
            nextSixtySecondGrant(NOW + (index + 1) * 40_000L, "pdf-grant-${index + 1}")
            authority = checkNotNull(service.renewProtectedMediaReadAuthority(previous))
            assertThat(previous.description).isEqualTo(immutable)
            assertThat(authority.description.authorizationDeadlineEpochMillis).isEqualTo(NOW + (index + 1) * 40_000L + 60_000)
            assertThat(service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0))
                .isEqualTo(ProtectedMediaReleaseAuthorityDecision.VALIDATED)
            assertThat(authority.description.requestNonceSha256Hex).isNotEqualTo(immutable.requestNonceSha256Hex)
            assertThat(checkNotNull(kms.lastReleasedShare).all { it == 0.toByte() }).isTrue()
            previous.close()
        }
        assertThat(kms.releasedNonces.map { it.toList() }.toSet()).hasSize(10)
        assertThat(kms.releaseOperationIds.toSet()).containsExactly(null)
        authority.close()
    }

    @Test fun `expired or closed source never dispatches fresh release`() = runTest {
        val authority = renewablePdfAuthority()
        authorityClock.elapsedMillis += 60_000
        authorityClock.wallEpochMillis = NOW - 1_000
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        authority.close()
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
    }

    @Test fun `late signed release cannot revive old grant and returned key share is wiped`() = runTest {
        val authority = renewablePdfAuthority()
        authorityClock.elapsedMillis += 40_000
        authorityClock.wallEpochMillis += 40_000
        nextSixtySecondGrant(NOW + 40_000, "late-grant")
        kms.onRelease = { authorityClock.elapsedMillis += 20_000 }
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        assertThat(service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0))
            .isEqualTo(ProtectedMediaReleaseAuthorityDecision.EXPIRED)
        assertThat(checkNotNull(kms.lastReleasedShare).all { it == 0.toByte() }).isTrue()
        authority.close()
    }

    @Test fun `close during KMS request rejects candidate`() = runTest {
        val authority = renewablePdfAuthority()
        authorityClock.elapsedMillis += 40_000
        nextSixtySecondGrant(NOW + 40_000, "closed-grant")
        kms.onRelease = { authority.close() }
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        assertThat(checkNotNull(kms.lastReleasedShare).all { it == 0.toByte() }).isTrue()
    }

    @Test fun `fresh admission rejects replayed grant and installation policy or context mismatch`() = runTest {
        val authority = renewablePdfAuthority()
        authorityClock.elapsedMillis += 40_000
        nextSixtySecondGrant(NOW + 40_000, "pdf-grant-0")
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        nextSixtySecondGrant(NOW + 40_000, "new-grant")
        val invalid: List<(ReleasedKmsShare) -> ReleasedKmsShare> = listOf(
            { it.copy(installationId = "different-installation") },
            { it.copy(policyVersion = it.policyVersion + 1) },
            { it.copy(immutableContextSha256 = ByteArray(32)) },
            { it.copy(requestNonce = ByteArray(24)) },
        )
        invalid.forEach { transform ->
            kms.transformRelease = transform
            assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
            assertThat(checkNotNull(kms.lastReleasedShare).all { it == 0.toByte() }).isTrue()
        }
        authority.close()
    }

    @Test fun `fresh admission boot change fails closed before KMS dispatch`() = runTest {
        val authority = renewablePdfAuthority()
        authorityClock.elapsedMillis += 40_000
        runtimeBinding.bootCount += 1
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.isFailure).isTrue()
        assertThat(kms.releaseCount).isEqualTo(1)
        authority.close()
    }

    @Test fun `fresh admission KMS denial propagates without modifying old grant`() = runTest {
        val authority = renewablePdfAuthority()
        val original = authority.description
        authorityClock.elapsedMillis += 40_000
        kms.onRelease = { throw ContentProtectionException.AccessDenied() }
        assertThat(runCatchingExceptions { service.renewProtectedMediaReadAuthority(authority) }.exceptionOrNull())
            .isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(authority.description).isEqualTo(original)
        authority.close()
    }

    @Test fun `bounded offline media cannot obtain online refresh`() = runTest {
        val ciphertext = ByteArrayOutputStream()
        val original = protectRequest()
        val header = service.protectStream(original.copy(kind = ContentKind.ATTACHMENT,
            offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE), "offline PDF".byteInputStream(), ciphertext)
        val offlineService = serviceWithOffline(
            FixedOfflineLicenseService(kms::storedShareFor, authorizationDeadlineEpochMillis = NOW + 500)
        )
        val authority = offlineService.decryptMediaStreamWithAuthority(releaseRequest(header), ciphertext.toByteArray().inputStream(), ByteArrayOutputStream())
        val count = kms.releaseCount
        assertThat(offlineService.renewProtectedMediaReadAuthority(authority)).isNull()
        assertThat(kms.releaseCount).isEqualTo(count)
        authority.close()
    }

    private fun protectRequest(): ProtectContentRequest {
        val descriptor = deviceKeys.currentDescriptor()
        return ProtectContentRequest(
            contentId = ContentId(CONTENT_ID),
            vaultId = VAULT_ID,
            kind = ContentKind.TEXT,
            policy = ContentPolicyReference(PolicyId("policy-1"), 1, EXPIRY),
            accountIdHash = ACCOUNT_HASH,
            roomId = ROOM_ID,
            senderInstallationId = INSTALLATION_ID,
            createdAtEpochMillis = CREATED_AT,
            recipients = listOf(
                DeviceShareRecipient(
                    installationId = descriptor.installationId,
                    keyId = descriptor.keyId,
                    algorithm = descriptor.algorithm,
                    publicKeyDer = descriptor.publicKeyDer,
                    allowsBoundedOffline = true,
                )
            ),
        )
    }

    private suspend fun protectDistinctContent(
        contentService: DefaultContentProtectionService,
        count: Int,
        prefix: String,
    ): List<ProtectedContentEnvelope> = (0 until count).map { index ->
        contentService.protectBytes(
            protectRequest().copy(contentId = ContentId("$prefix-content-$index")),
            "$prefix-$index".encodeToByteArray(),
        )
    }

    private fun timelineIntent(
        index: Int,
        releaseClass: TimelineContentReleaseClass,
        scopeId: String,
        ownerToken: TimelineReleaseScopeOwnerToken? = null,
    ) = TimelineContentReleaseIntent(
        scopeId = scopeId,
        requestId = "request-$scopeId-$index",
        eventId = "event-$scopeId-$index",
        generation = 1L,
        releaseClass = releaseClass,
        roomId = ROOM_ID,
        scopeOwnerToken = ownerToken,
    )

    private fun roomLifecycle(contractId: String, messageExpiresAtEpochMillis: Long) = ResolvedRoomLifecycle(
        roomIdSha256 = MessageDigest.getInstance("SHA-256").digest(ROOM_ID.encodeToByteArray()),
        profileId = "STANDARD_30D",
        profileKind = RoomLifecycleProfileKind.STANDARD,
        retentionMillis = 30L * 24 * 60 * 60_000,
        maximumOfflineMillis = 60L * 60_000,
        recoveryPolicy = RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY,
        messageExpiresAtEpochMillis = messageExpiresAtEpochMillis,
        issuedAtEpochMillis = CREATED_AT,
        expiresAtEpochMillis = CREATED_AT + 5 * 60_000,
        protocolVersion = 3,
        contractId = contractId,
        profileRevision = 1,
        contractSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 7 },
        signingKeyId = "0123456789abcdef",
    )

    private fun releaseRequest(header: io.element.android.security.contentprotection.api.ContentProtectionHeader) = ReleaseContentRequest(
        header = header,
        vaultId = VAULT_ID,
        accountIdHash = ACCOUNT_HASH,
        roomId = ROOM_ID,
        requestingInstallationId = INSTALLATION_ID,
        requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 9 },
    )

    private fun serviceWithGate(gate: StageThreeSecurityGate) = DefaultContentProtectionService(
        validator = DefaultContentEnvelopeValidator(),
        deviceShareKeyService = deviceKeys,
        kmsShareClient = kms,
        offlineLicenseService = offlineLicenses,
        stageThreeSecurityGate = gate,
        runtimeBindingProvider = runtimeBinding,
        clock = SystemClock { NOW },
        authorityClock = authorityClock,
        vaultKeyService = vaultKeys,
        matrixContentProtectionRuntime = runtime,
    )

    private fun serviceWithKms(
        kmsShareClient: KmsShareClient,
        contentProtectionRuntime: TestMatrixContentProtectionRuntime = TestMatrixContentProtectionRuntime(),
    ) = DefaultContentProtectionService(
        validator = DefaultContentEnvelopeValidator(),
        deviceShareKeyService = deviceKeys,
        kmsShareClient = kmsShareClient,
        offlineLicenseService = offlineLicenses,
        stageThreeSecurityGate = AllowStageThreeSecurityGate,
        runtimeBindingProvider = runtimeBinding,
        clock = SystemClock { NOW },
        authorityClock = authorityClock,
        vaultKeyService = vaultKeys,
        matrixContentProtectionRuntime = contentProtectionRuntime,
    )

    private fun serviceWithOffline(offlineLicenseService: OfflineLicenseService) = DefaultContentProtectionService(
        validator = DefaultContentEnvelopeValidator(),
        deviceShareKeyService = deviceKeys,
        kmsShareClient = kms,
        offlineLicenseService = offlineLicenseService,
        stageThreeSecurityGate = AllowStageThreeSecurityGate,
        runtimeBindingProvider = runtimeBinding,
        clock = SystemClock { NOW },
        authorityClock = authorityClock,
        vaultKeyService = vaultKeys,
        matrixContentProtectionRuntime = TestMatrixContentProtectionRuntime(),
    )

    private open class InMemoryKmsShareClient : KmsShareClient {
        private val shares = mutableMapOf<String, ByteArray>()
        private val epochAudiences = mutableMapOf<String, Set<String>>()
        private val epochSecret = ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES) { 41 }
        var releaseCount = 0
        var releaseGrantId = "grant-1"
        var playbackDeadline: Long? = null
        var releaseServerTimeEpochMillis = NOW
        var onRelease: suspend () -> Unit = {}
        var transformRelease: (ReleasedKmsShare) -> ReleasedKmsShare = { it }
        val releasedNonces = mutableListOf<ByteArray>()
        var lastReleasedShare: ByteArray? = null
        var releaseIssuedAtEpochMillis = NOW - 1
        var releaseExpiresAtEpochMillis = NOW + 1_000
        var releaseAuthorizationDeadlineEpochMillis = releaseExpiresAtEpochMillis
        var resolvedRoomLifecycle: ResolvedRoomLifecycle? = null
        var lifecycleContractExpiryFailures = 0
        var resolveRoomLifecycleCount = 0
        val storeAttemptExpiries = mutableListOf<Long>()
        val storeAttemptContractIds = mutableListOf<String?>()
        val releaseOperationIds = mutableListOf<String?>()
        var failBurnEnd = false

        override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle {
            require(roomId == ROOM_ID)
            resolveRoomLifecycleCount++
            return requireNotNull(resolvedRoomLifecycle)
        }

        override suspend fun acquireRoomEpoch(request: AcquireRoomEpochRequest): ReleasedRoomEpoch {
            val reference = RoomEpochReference(
                epochId = "test-room-epoch-0000000000000001",
                generation = 1,
                audienceDigestSha256 = roomAudienceDigest(
                    request.audienceAccountIdHashes,
                    request.authorizedInstallationIds,
                ),
            )
            epochAudiences[reference.epochId] = request.audienceAccountIdHashes
            return releasedEpoch(
                reference,
                request.roomIdSha256,
                request.accountIdHash,
                request.requestingInstallationId,
                request.requestingKeyId,
                request.requestNonce,
            )
        }

        override suspend fun releaseRoomEpoch(request: ReleaseRoomEpochRequest): ReleasedRoomEpoch {
            if (request.accountIdHash !in epochAudiences[request.reference.epochId].orEmpty()) {
                throw ContentProtectionException.AccessDenied()
            }
            return releasedEpoch(
                request.reference,
                request.roomIdSha256,
                request.accountIdHash,
                request.requestingInstallationId,
                request.requestingKeyId,
                request.requestNonce,
            )
        }

        override suspend fun storeShare(request: StoreKmsShareRequest): KmsShareReference {
            storeAttemptExpiries += request.policy.expiresAtEpochMillis
            storeAttemptContractIds += request.roomLifecycle?.contractId
            if (lifecycleContractExpiryFailures > 0) {
                lifecycleContractExpiryFailures--
                throw ContentProtectionException.LifecycleContractRefreshRequired()
            }
            shares[request.contentId.value] = request.kmsShare.copyOf()
            return KmsShareReference("kms:${request.contentId.value}")
        }

        override suspend fun releaseShare(request: ReleaseKmsShareRequest): ReleasedKmsShare {
            releaseCount++
            releasedNonces += request.requestNonce.copyOf()
            onRelease()
            releaseOperationIds += currentLifecycleOperationContext()?.operationId
            val share = shares[request.contentId.value] ?: throw ContentProtectionException.Expired()
            return ReleasedKmsShare(
                grantId = releaseGrantId,
                reference = request.reference,
                contentId = request.contentId,
                policyVersion = request.policy.policyVersion,
                installationId = request.requestingInstallationId,
                immutableContextSha256 = request.immutableContextSha256.copyOf(),
                requestNonce = request.requestNonce.copyOf(),
                mode = OfflineLeaseMode.ONLINE_REQUIRED,
                issuedAtEpochMillis = releaseIssuedAtEpochMillis,
                expiresAtEpochMillis = releaseExpiresAtEpochMillis,
                authorizationDeadlineEpochMillis = releaseAuthorizationDeadlineEpochMillis,
                serverTimeEpochMillis = releaseServerTimeEpochMillis,
                bootCount = request.bootCount,
                kmsShare = share.copyOf().also { lastReleasedShare = it },
                progressivePlaybackDeadlineEpochMillis = if (request.progressiveVideoPlayback) playbackDeadline else null,
            ).let(transformRelease)
        }

        override suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> =
            requests.map { releaseShare(it) }

        override suspend fun issueOfflineLease(request: ReleaseKmsShareRequest): OfflineLease = error("Not used")

        override suspend fun fetchRevocations(request: FetchRevocationsRequest): RemoteRevocationSnapshot = error("Not used")

        override suspend fun destroyShare(contentId: ContentId, reason: ContentAccessDestroyReason): Boolean =
            shares.remove(contentId.value)?.apply { fill(0) } != null

        override suspend fun endBurnAfterRead(request: EndBurnAfterReadRequest): BurnAfterReadEndResult {
            if (failBurnEnd) throw ContentProtectionException.KmsUnavailable()
            return BurnAfterReadEndResult(
                contentId = request.contentId,
                accountIdHash = request.accountIdHash,
                sessionId = request.sessionId,
                state = "REVOKED",
                endedAtEpochMillis = NOW,
                allAccessEnded = true,
                serverTimeEpochMillis = NOW,
            )
        }

        fun storedShareFor(contentId: String): ByteArray = requireNotNull(shares[contentId]).copyOf()

        fun hasStoredShare(contentId: String): Boolean = contentId in shares

        fun restoreShare(contentId: String, share: ByteArray) {
            shares[contentId] = share.copyOf()
        }

        private fun releasedEpoch(
            reference: RoomEpochReference,
            roomIdSha256: ByteArray,
            accountIdHash: String,
            installationId: String,
            recipientKeyId: String,
            requestNonce: ByteArray,
        ) = ReleasedRoomEpoch(
            reference = reference,
            roomIdSha256 = roomIdSha256.copyOf(),
            accountIdHash = accountIdHash,
            installationId = installationId,
            recipientKeyId = recipientKeyId,
            requestNonce = requestNonce.copyOf(),
            activeUntilEpochMillis = NOW + 5 * 60_000,
            releaseUntilEpochMillis = EXPIRY,
            serverTimeEpochMillis = NOW,
            epochSecret = epochSecret.copyOf(),
        )

        private fun roomAudienceDigest(accounts: Set<String>, installations: Set<String>): ByteArray {
            val encoded = ByteArrayOutputStream().use { bytes ->
                java.io.DataOutputStream(bytes).use { output ->
                    output.write("YZRA0001".encodeToByteArray())
                    listOf(accounts.sorted(), installations.sorted()).forEach { values ->
                        output.writeInt(values.size)
                        values.forEach { value ->
                            val item = value.encodeToByteArray()
                            output.writeInt(item.size)
                            output.write(item)
                        }
                    }
                }
                bytes.toByteArray()
            }
            return sha256(encoded)
        }
    }

    private class OperationGatedKmsShareClient : InMemoryKmsShareClient() {
        data class Started(
            val operationId: String?,
            val requests: List<ReleaseKmsShareRequest>,
            val permit: CompletableDeferred<Unit> = CompletableDeferred(),
        )

        val started = Channel<Started>(Channel.UNLIMITED)
        val returned = Channel<List<ReleasedKmsShare>>(Channel.UNLIMITED)
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val resultLock = Mutex()
        private var unblocked = false

        override suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> {
            val batch = Started(currentLifecycleOperationContext()?.operationId, requests)
            synchronized(gates) {
                gates += batch.permit
                if (unblocked) batch.permit.complete(Unit)
            }
            started.send(batch)
            batch.permit.await()
            return resultLock.withLock { super.releaseShares(requests) }.also { returned.send(it) }
        }

        fun releaseAll() = synchronized(gates) {
            unblocked = true
            gates.forEach { it.complete(Unit) }
        }
    }

    private class GatedBatchKmsShareClient : InMemoryKmsShareClient() {
        val startedBatches = Channel<List<ReleaseKmsShareRequest>>(Channel.UNLIMITED)
        val returnedBatches = Channel<List<ReleasedKmsShare>>(Channel.UNLIMITED)
        private val permits = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> {
            startedBatches.send(requests)
            permits.receive()
            return super.releaseShares(requests).also { returnedBatches.send(it) }
        }

        fun releaseNextBatch() {
            check(permits.trySend(Unit).isSuccess)
        }
    }

    private class CancellingBatchKmsShareClient(
        private val expectedFailedRequestCount: Int,
    ) : InMemoryKmsShareClient() {
        private val failedRequestIds = mutableSetOf<String>()

        override suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> {
            if (failedRequestIds.size < expectedFailedRequestCount) {
                failedRequestIds += requests.map { it.contentId.value }
                throw CancellationException("synthetic transport cancellation")
            }
            return super.releaseShares(requests)
        }
    }

    private class MutableRuntimeBindingProvider(
        var bootCount: Long,
    ) : DeviceRuntimeBindingProvider {
        override fun bootCount(): Long = bootCount
    }

    private class MutableAuthorityClock(
        var wallEpochMillis: Long,
        var elapsedMillis: Long,
    ) : OfflineLicenseClock {
        override fun wallClockEpochMillis(): Long = wallEpochMillis
        override fun elapsedRealtimeMillis(): Long = elapsedMillis
    }

    private class AdvancingOutputStream(
        private val clock: MutableAuthorityClock,
        private val advanceByMillis: Long,
    ) : ByteArrayOutputStream() {
        private var advanced = false

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            super.write(buffer, offset, length)
            if (!advanced && length > 0) {
                advanced = true
                clock.elapsedMillis += advanceByMillis
            }
        }
    }

    private class TestMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
        private val clearers = mutableListOf<() -> Unit>()

        override fun attach() = Unit
        override fun detach() = Unit
        override fun isConnected(): Boolean = true
        override fun registerSensitiveCacheClearer(clearer: () -> Unit) {
            clearers += clearer
        }

        override fun clearSensitiveCaches() {
            clearers.forEach { clearer -> clearer() }
        }
    }

    private class TombstoneOfflineLicenseService : OfflineLicenseService {
        private val revokedContent = mutableSetOf<ContentId>()

        override suspend fun requireAccessAllowed(vaultId: AccountVaultId, contentId: ContentId) {
            if (contentId in revokedContent) throw ContentProtectionException.Revoked()
        }

        override suspend fun releaseOrAcquire(vaultId: AccountVaultId, request: ReleaseKmsShareRequest): ReleasedKmsShare = error("Not used")

        override suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): Boolean = revokedContent.add(contentId)

        override suspend fun clearVault(vaultId: AccountVaultId): Boolean = true
    }

    private class FixedOfflineLicenseService(
        private val shareFor: (String) -> ByteArray,
        private val authorizationDeadlineEpochMillis: Long,
    ) : OfflineLicenseService {
        override suspend fun requireAccessAllowed(vaultId: AccountVaultId, contentId: ContentId) = Unit

        override suspend fun releaseOrAcquire(
            vaultId: AccountVaultId,
            request: ReleaseKmsShareRequest,
        ): ReleasedKmsShare = ReleasedKmsShare(
            grantId = "offline-lease-verified",
            reference = request.reference,
            contentId = request.contentId,
            policyVersion = request.policy.policyVersion,
            installationId = request.requestingInstallationId,
            immutableContextSha256 = request.immutableContextSha256.copyOf(),
            requestNonce = request.requestNonce.copyOf(),
            mode = OfflineLeaseMode.BOUNDED_OFFLINE,
            issuedAtEpochMillis = NOW - 1,
            expiresAtEpochMillis = NOW + 1_000,
            authorizationDeadlineEpochMillis = authorizationDeadlineEpochMillis,
            serverTimeEpochMillis = NOW,
            bootCount = request.bootCount,
            kmsShare = shareFor(request.contentId.value),
        )

        override suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): Boolean = true

        override suspend fun clearVault(vaultId: AccountVaultId): Boolean = true
    }

    private data object AllowStageThreeSecurityGate : StageThreeSecurityGate {
        override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness = StageThreeReadiness(
            policyId = request.policy.policyId.value,
            policyVersion = request.policy.policyVersion,
            installationId = request.installationId,
            deviceShareKeyId = "test-key",
        )
    }

    private class RecordingStageThreeSecurityGate : StageThreeSecurityGate {
        val requests = mutableListOf<StageThreeGateRequest>()

        override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness {
            requests += request
            return StageThreeReadiness(
                policyId = request.policy.policyId.value,
                policyVersion = request.policy.policyVersion,
                installationId = request.installationId,
                deviceShareKeyId = "test-key",
            )
        }
    }

    private class SoftwareDeviceShareKeyService : DeviceShareKeyService {
        private val keyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(3072)
            generateKeyPair()
        }
        private val descriptor = DeviceShareKeyDescriptor(
            installationId = INSTALLATION_ID,
            keyId = sha256(keyPair.public.encoded).toHex(),
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = keyPair.public.encoded,
            hardwareSecurityLevel = HardwareSecurityLevel.SOFTWARE,
        )

        override suspend fun descriptor(): DeviceShareKeyDescriptor = currentDescriptor()

        fun currentDescriptor(): DeviceShareKeyDescriptor = descriptor.copy(publicKeyDer = descriptor.publicKeyDer.copyOf())

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope {
            val payload = sha256(contentId.value.encodeToByteArray()) + immutableContextSha256 + share
            val publicKey = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(recipient.publicKeyDer))
            return DeviceShareEnvelope(
                recipientInstallationId = recipient.installationId,
                recipientKeyId = recipient.keyId,
                algorithm = recipient.algorithm,
                ciphertext = rsaCipher(Cipher.ENCRYPT_MODE, publicKey).doFinal(payload),
            )
        }

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray {
            val payload = rsaCipher(Cipher.DECRYPT_MODE, keyPair.private).doFinal(envelope.ciphertext)
            val contentDigest = payload.copyOfRange(0, ContentProtectionFormat.SHA256_SIZE_BYTES)
            val context = payload.copyOfRange(ContentProtectionFormat.SHA256_SIZE_BYTES, ContentProtectionFormat.SHA256_SIZE_BYTES * 2)
            require(MessageDigest.isEqual(contentDigest, sha256(contentId.value.encodeToByteArray())))
            require(MessageDigest.isEqual(context, immutableContextSha256))
            return payload.copyOfRange(ContentProtectionFormat.SHA256_SIZE_BYTES * 2, payload.size).also { payload.fill(0) }
        }

        override suspend fun destroy(): Boolean = true

        private fun rsaCipher(mode: Int, key: java.security.Key): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(
                mode,
                key,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
            )
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val CONTENT_ID = "content-1"
        const val RECEIVE_OPERATION_ID = "receive-operation-0001"
        const val INSTALLATION_ID = "installation-1"
        const val ROOM_ID = "!room:example.org"
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECOND_ACCOUNT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OUTSIDER_ACCOUNT_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val CREATED_AT = 1_000L
        const val NOW = 2_000L
        const val EXPIRY = 10_000L
        const val BOOT_COUNT = 7L
        const val ELAPSED_REALTIME = 50_000L
        const val BRIDGE_TEST_TIMEOUT_MILLIS = 5_000L
        const val NO_EXTRA_BATCH_MILLIS = 500L
        val VAULT_ID = AccountVaultId("vault-1")

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    }
}
