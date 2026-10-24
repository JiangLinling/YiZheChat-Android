/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.AcquireRoomEpochRequest
import io.element.android.security.contentprotection.api.BurnAfterReadEndResult
import io.element.android.security.contentprotection.api.BurnAfterReadSession
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentCipherSuite
import io.element.android.security.contentprotection.api.ContentEnvelopeValidator
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionHeader
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.EndBurnAfterReadRequest
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.KmsTransportPriority
import io.element.android.security.contentprotection.api.KmsTransportSchedulingContext
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.OfflineLicenseService
import io.element.android.security.contentprotection.api.OpenBurnAfterReadRequest
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ProtectedChunkReadSession
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthority
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDecision
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDescription
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleaseRoomEpochRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.ReleasedRoomEpoch
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomEpochReference
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.StageThreeGateRequest
import io.element.android.security.contentprotection.api.StageThreeOperation
import io.element.android.security.contentprotection.api.StageThreeReleaseRetentionAuthority
import io.element.android.security.contentprotection.api.StageThreeSecurityGate
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineReleaseScopeOwnerToken
import io.element.android.security.contentprotection.impl.scheduling.BoundedKmsTransportArbiter
import io.element.android.security.contentprotection.impl.scheduling.LegacyKmsTransportOperation
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseAdmission
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseClass
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseDeduplicationKey
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseDispatchTicket
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseRequest
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseSchedulerConfig
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineReleaseSchedulerPrototype
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineResultDisposition
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineSensitiveResult
import io.element.android.security.contentprotection.impl.scheduling.NextTimelineViewportIdentity
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultWrappedKey
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private sealed interface ProtectedMediaAuthorityTimeReading {
    data class Available(val epochMillis: Long, val elapsedRealtimeMillis: Long) : ProtectedMediaAuthorityTimeReading
    data object BindingInvalid : ProtectedMediaAuthorityTimeReading
    data object Unavailable : ProtectedMediaAuthorityTimeReading
}

/**
 * Per-release, within-boot trusted time. The anchor is immutable and elapsed time can only move the
 * effective instant forward. A wall-clock rollback therefore cannot refresh an authority deadline.
 */
private class TrustedProtectedMediaAuthorityTime(
    private val bootCount: Long,
    private val anchorEpochMillis: Long,
    private val anchorElapsedRealtimeMillis: Long,
) {
    private var lastEffectiveEpochMillis = anchorEpochMillis

    @Synchronized
    fun read(
        clock: OfflineLicenseClock,
        runtimeBindingProvider: DeviceRuntimeBindingProvider,
    ): ProtectedMediaAuthorityTimeReading {
        val currentBootCount = runCatching { runtimeBindingProvider.bootCount() }
            .getOrElse { return ProtectedMediaAuthorityTimeReading.Unavailable }
        val wallClockEpochMillis = runCatching { clock.wallClockEpochMillis() }
            .getOrElse { return ProtectedMediaAuthorityTimeReading.Unavailable }
        val elapsedRealtimeMillis = runCatching { clock.elapsedRealtimeMillis() }
            .getOrElse { return ProtectedMediaAuthorityTimeReading.Unavailable }
        if (currentBootCount != bootCount || elapsedRealtimeMillis < anchorElapsedRealtimeMillis) {
            return ProtectedMediaAuthorityTimeReading.BindingInvalid
        }
        if (wallClockEpochMillis <= 0L || elapsedRealtimeMillis < 0L) {
            return ProtectedMediaAuthorityTimeReading.Unavailable
        }
        val elapsedDelta = elapsedRealtimeMillis - anchorElapsedRealtimeMillis
        val elapsedProjectedEpochMillis = if (elapsedDelta > Long.MAX_VALUE - anchorEpochMillis) {
            Long.MAX_VALUE
        } else {
            anchorEpochMillis + elapsedDelta
        }
        val effectiveEpochMillis = maxOf(
            lastEffectiveEpochMillis,
            wallClockEpochMillis,
            elapsedProjectedEpochMillis,
        )
        lastEffectiveEpochMillis = effectiveEpochMillis
        return ProtectedMediaAuthorityTimeReading.Available(effectiveEpochMillis, elapsedRealtimeMillis)
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultContentProtectionService internal constructor(
    private val validator: ContentEnvelopeValidator,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val kmsShareClient: KmsShareClient,
    private val offlineLicenseService: OfflineLicenseService,
    private val stageThreeSecurityGate: StageThreeSecurityGate,
    private val runtimeBindingProvider: DeviceRuntimeBindingProvider,
    private val clock: SystemClock,
    private val authorityClock: OfflineLicenseClock,
    private val vaultKeyService: VaultKeyService,
    matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    private val externalPolicyProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyProvider? = null,
    private val externalPreviewGate: io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate? = null,
    @Suppress("UNUSED_PARAMETER") externalReadConstructorMarker: Unit = Unit,
) : ContentProtectionService {
    @dev.zacsweers.metro.Inject
    constructor(
        validator: ContentEnvelopeValidator,
        deviceShareKeyService: DeviceShareKeyService,
        kmsShareClient: KmsShareClient,
        offlineLicenseService: OfflineLicenseService,
        stageThreeSecurityGate: StageThreeSecurityGate,
        runtimeBindingProvider: DeviceRuntimeBindingProvider,
        clock: SystemClock,
        authorityClock: OfflineLicenseClock,
        vaultKeyService: VaultKeyService,
        matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
        externalPolicyProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyProvider,
        externalPreviewGate: io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate,
    ) : this(
        validator,
        deviceShareKeyService,
        kmsShareClient,
        offlineLicenseService,
        stageThreeSecurityGate,
        runtimeBindingProvider,
        clock,
        authorityClock,
        vaultKeyService,
        matrixContentProtectionRuntime,
        externalPolicyProvider as io.element.android.security.dataprotection.api.DataProtectionPolicyProvider?,
        externalPreviewGate
    )

    private val secureRandom = SecureRandom()
    private val contentKeyCache = VaultProtectedContentKeyCache(vaultKeyService, clock)
    private val onlineKmsReleaseBatcher = OnlineKmsReleaseBatcher(kmsShareClient)
    private val roomEpochCache = RoomEpochMemoryCache(clock)
    private val mediaAuthorityIssuer = Any()
    private val chunkReaders = mutableSetOf<ChunkReadSession>()
    private val chunkReaderGeneration = AtomicLong()

    init {
        matrixContentProtectionRuntime.registerSensitiveCacheClearer {
            val readers = synchronized(chunkReaders) {
                chunkReaderGeneration.incrementAndGet()
                chunkReaders.toList().also { chunkReaders.clear() }
            }
            readers.forEach { it.close() }
            contentKeyCache.clear()
            roomEpochCache.clear()
            onlineKmsReleaseBatcher.clearPending()
        }
    }

    override fun cancelTimelineReleaseRequests(
        scopeId: String,
        requestIds: Set<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
    ) {
        onlineKmsReleaseBatcher.cancelTimelineRequests(scopeId, requestIds, ownerToken)
    }

    override fun updateTimelineReleaseViewport(
        scopeId: String,
        generation: Long,
        visibleRequestIds: List<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
        bootstrapRequestIds: List<String>,
    ): Boolean = onlineKmsReleaseBatcher.updateTimelineViewport(scopeId, generation, visibleRequestIds, ownerToken, bootstrapRequestIds)

    override fun openTimelineReleaseScope(scopeId: String): TimelineReleaseScopeOwnerToken =
        onlineKmsReleaseBatcher.openTimelineScope(scopeId)

    override fun closeTimelineReleaseScope(scopeId: String, ownerToken: TimelineReleaseScopeOwnerToken) {
        onlineKmsReleaseBatcher.closeTimelineScope(scopeId, ownerToken)
    }

    /** Read-only admission observation used to make scheduler concurrency tests deterministic. */
    internal fun pendingTimelineReleaseAdmissionsForTest(releaseClass: TimelineContentReleaseClass): Int =
        onlineKmsReleaseBatcher.pendingTimelineAdmissionsForTest(releaseClass)

    override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle =
        kmsShareClient.resolveRoomLifecycle(roomId)

    override suspend fun assignCreatedRoomLifecycle(
        roomId: String,
        profileKind: RoomLifecycleProfileKind,
    ): Boolean = kmsShareClient.assignCreatedRoomLifecycle(roomId, profileKind)

    override suspend fun registerMessageEvent(
        contentId: ContentId,
        roomId: String,
        eventId: String,
        mediaUris: Set<String>,
    ): Boolean = kmsShareClient.registerMessageEvent(contentId, roomId, eventId, mediaUris)

    override suspend fun confirmMatrixRedaction(roomId: String, eventId: String): Boolean =
        kmsShareClient.confirmMatrixRedaction(roomId, eventId)

    override suspend fun registerCallRecord(roomId: String, eventId: String): Boolean =
        kmsShareClient.registerCallRecord(roomId, eventId)

    override suspend fun protectBytes(request: ProtectContentRequest, plaintext: ByteArray): ProtectedContentEnvelope =
        protectBytes(request, plaintext) { }

    override suspend fun protectBytes(
        request: ProtectContentRequest,
        plaintext: ByteArray,
        beforeKmsStore: suspend () -> Unit,
    ): ProtectedContentEnvelope =
        withContext(Dispatchers.IO) {
            require(plaintext.size <= MAX_IN_MEMORY_CONTENT_BYTES)
            val prepared = LifecycleStageTiming.measure("send_prepare_protection", request.contentId.value) {
                prepareProtection(request, ContentCipherSuite.AES_256_GCM_HKDF_SHA256, beforeKmsStore)
            }
            try {
                val ciphertext = LifecycleStageTiming.measure("send_payload_encrypt", request.contentId.value) {
                    encryptAead(
                    key = prepared.contentKey,
                    nonce = prepared.header.nonce,
                    associatedData = ContentEnvelopeBinaryFormat.encodeHeader(prepared.header),
                    plaintext = plaintext,
                )
                }
                ProtectedContentEnvelope(prepared.header, ciphertext)
            } catch (failure: Exception) {
                retireFailedProtection(request.contentId)
                throw failure
            } finally {
                prepared.contentKey.zero()
            }
        }

    override suspend fun decryptBytes(request: ReleaseContentRequest, ciphertext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        require(ciphertext.size <= MAX_IN_MEMORY_CIPHERTEXT_BYTES)
        require(request.header.cipherSuite == ContentCipherSuite.AES_256_GCM_HKDF_SHA256)
        val ciphertextHash = MessageDigest.getInstance(SHA256).digest(ciphertext)
        val releasedKey = try {
            LifecycleStageTiming.measure("receive_content_key", request.header.contentId.value) {
                releaseContentKey(request, ciphertextHash, retainMediaAuthority = false)
            }
        } finally {
            ciphertextHash.zero()
        }
        try {
            LifecycleStageTiming.measure("receive_payload_decrypt", request.header.contentId.value) {
                decryptAead(
                key = releasedKey.contentKey,
                nonce = request.header.nonce,
                associatedData = ContentEnvelopeBinaryFormat.encodeHeader(request.header),
                ciphertext = ciphertext,
            )
            }
        } finally {
            releasedKey.contentKey.zero()
        }
    }

    override suspend fun protectStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
    ): ContentProtectionHeader = protectMediaStream(request, plaintext, ciphertext, {}, preservePreparedOnFailure = false)

    override suspend fun protectStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
        beforeKmsStore: suspend () -> Unit,
    ): ContentProtectionHeader = protectMediaStream(request, plaintext, ciphertext, beforeKmsStore, preservePreparedOnFailure = true)

    private suspend fun protectMediaStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
        beforeKmsStore: suspend () -> Unit,
        preservePreparedOnFailure: Boolean,
    ): ContentProtectionHeader = withContext(Dispatchers.IO) {
        val prepared = prepareProtection(
            request,
            ContentCipherSuite.CHUNKED_AES_256_GCM_HKDF_SHA256,
            beforeKmsStore,
            preservePreparedOnFailure
        )
        try {
            encryptChunks(prepared.header, prepared.contentKey, plaintext, ciphertext)
            prepared.header
        } catch (failure: Exception) {
            if (!preservePreparedOnFailure) retireFailedProtection(request.contentId)
            throw failure
        } finally {
            prepared.contentKey.zero()
        }
    }

    override suspend fun openChunkReadSession(request: ReleaseContentRequest): ProtectedChunkReadSession {
        require(request.header.cipherSuite == ContentCipherSuite.CHUNKED_AES_256_GCM_HKDF_SHA256)
        // A video sent as m.file retains its signed ATTACHMENT kind; never rewrite the authenticated header.
        require(request.header.kind in setOf(
            io.element.android.security.contentprotection.api.ContentKind.VIDEO,
            io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT,
        ))
        val generation = chunkReaderGeneration.get()
        val released = releaseContentKey(request, ciphertextHash = null, retainMediaAuthority = true)
        var reader: ChunkReadSession? = null
        try {
            val created = ChunkReadSession(request, released)
            reader = created
            created.prepareReadAuthority()
            created.requireAuthorized()
            synchronized(chunkReaders) {
                if (generation != chunkReaderGeneration.get()) throw ContentProtectionException.AccessDenied()
                chunkReaders.add(created)
            }
            return created
        } catch (failure: Throwable) {
            reader?.close() ?: released.contentKey.zero()
            throw failure
        }
    }

    private inner class ChunkReadSession(
        private val request: ReleaseContentRequest,
        private val released: ReleasedContentKey,
    ) : ProtectedChunkReadSession {
        private val keyLock = Any()
        private val admissionMutex = Mutex()
        private val lastLocalCheckElapsed = AtomicLong(Long.MIN_VALUE)
        // Same 250 ms local inspection bound as the viewer probe. No online reacquisition is added.
        private val localInspectionMillis = 250L
        private var closed = false
        private val headerAad = ContentEnvelopeBinaryFormat.encodeHeader(request.header)
        private var retainedReadAuthority: io.element.android.security.contentprotection.api.StageThreeReadAuthority? = null

        private fun gateRequest() = StageThreeGateRequest(
            operation = StageThreeOperation.RELEASE,
            vaultId = request.vaultId,
            accountIdHash = request.accountIdHash,
            installationId = request.requestingInstallationId,
            policy = request.header.policy,
            contentCreatedAtEpochMillis = request.header.createdAtEpochMillis,
            offlineLeaseMode = request.header.offlineLeaseMode,
            audienceAccountIdHashes = setOf(request.accountIdHash),
            releaseRetentionAuthority = if (
                request.header.formatVersion >= ContentProtectionFormat.CURRENT_VERSION &&
                request.header.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED &&
                request.header.roomEpoch != null && request.header.deviceShareEnvelopes.isEmpty()
            ) StageThreeReleaseRetentionAuthority.ONLINE_KMS else StageThreeReleaseRetentionAuthority.LOCAL_POLICY,
        )

        suspend fun prepareReadAuthority() {
            retainedReadAuthority = stageThreeSecurityGate.openReadAuthority(gateRequest())
        }

        override suspend fun requireBufferedReadAuthorized() {
            val retained = retainedReadAuthority ?: return requireAuthorized()
            synchronized(keyLock) { if (closed) throw ContentProtectionException.AccessDenied() }
            try {
                requireCurrentTime()
                admissionMutex.withLock {
                    val now = authorityClock.elapsedRealtimeMillis()
                    val previous = lastLocalCheckElapsed.get()
                    if (previous == Long.MIN_VALUE || now < previous || now - previous >= localInspectionMillis) {
                        // Anchor at the start: slow inspection must not extend the permitted interval.
                        retained.requireAccess()
                        offlineLicenseService.requireAccessAllowed(request.vaultId, request.header.contentId)
                        requireCurrentTime()
                        lastLocalCheckElapsed.set(now)
                    }
                }
                synchronized(keyLock) { if (closed) throw ContentProtectionException.AccessDenied() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        private fun requireCurrentTime() {
            val description = requireNotNull(released.mediaAuthority)
            when (val time = requireNotNull(released.mediaAuthorityTime).read(authorityClock, runtimeBindingProvider)) {
                is ProtectedMediaAuthorityTimeReading.Available -> {
                    val deadline = if (request.progressiveVideoPlayback) {
                        requireNotNull(released.progressivePlaybackDeadlineEpochMillis)
                    } else description.authorizationDeadlineEpochMillis
                    if (time.epochMillis >= deadline) throw ContentProtectionException.Expired()
                }
                else -> throw ContentProtectionException.AuthenticationFailed()
            }
        }

        override suspend fun requireAuthorized() {
            synchronized(keyLock) { if (closed) throw ContentProtectionException.AccessDenied() }
            val inspectionStarted = authorityClock.elapsedRealtimeMillis()
            try {
                stageThreeSecurityGate.requireAccess(
                    StageThreeGateRequest(
                        operation = StageThreeOperation.RELEASE,
                        vaultId = request.vaultId,
                        accountIdHash = request.accountIdHash,
                        installationId = request.requestingInstallationId,
                        policy = request.header.policy,
                        contentCreatedAtEpochMillis = request.header.createdAtEpochMillis,
                        offlineLeaseMode = request.header.offlineLeaseMode,
                        audienceAccountIdHashes = setOf(request.accountIdHash),
                        releaseRetentionAuthority = if (
                            request.header.formatVersion >= ContentProtectionFormat.CURRENT_VERSION &&
                            request.header.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED &&
                            request.header.roomEpoch != null && request.header.deviceShareEnvelopes.isEmpty()
                        ) {
                            StageThreeReleaseRetentionAuthority.ONLINE_KMS
                        } else {
                            StageThreeReleaseRetentionAuthority.LOCAL_POLICY
                        },
                    )
                )
                offlineLicenseService.requireAccessAllowed(request.vaultId, request.header.contentId)
                if (deviceShareKeyService.descriptor().installationId != request.requestingInstallationId) {
                    throw ContentProtectionException.AccessDenied()
                }
                requireCurrentTime()
                synchronized(keyLock) { if (closed) throw ContentProtectionException.AccessDenied() }
                lastLocalCheckElapsed.set(inspectionStarted)
            } catch (cancelled: CancellationException) {
                // A decoder seek cancels one read, not the viewer-owned key. No bytes are delivered.
                throw cancelled
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        override suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray {
            requireBufferedReadAuthorized()
            var result: ByteArray? = null
            try {
                require(index >= 0 && expectedPlainLength in 0..STREAM_CHUNK_BYTES)
                require(finalChunk || expectedPlainLength == STREAM_CHUNK_BYTES)
                require(record.size == 5 + expectedPlainLength + GCM_TAG_SIZE_BYTES)
                val input = DataInputStream(record.inputStream())
                require(input.readUnsignedByte() == if (finalChunk) FINAL_CHUNK_FLAG else 0)
                require(input.readInt() == expectedPlainLength + GCM_TAG_SIZE_BYTES)
                val encrypted = input.readExact(expectedPlainLength + GCM_TAG_SIZE_BYTES)
                val nonce = chunkNonce(request.header.nonce, index)
                val aad = chunkAssociatedData(headerAad, index, finalChunk)
                try {
                    result = synchronized(keyLock) {
                        if (closed) throw ContentProtectionException.AccessDenied()
                        decryptAead(released.contentKey, nonce, aad, encrypted)
                    }
                } finally {
                    encrypted.zero()
                    nonce.zero()
                    aad.zero()
                }
                requireBufferedReadAuthorized()
                return requireNotNull(result).also { result = null }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ContentProtectionException) {
                close()
                throw failure
            } catch (failure: Exception) {
                close()
                throw ContentProtectionException.AuthenticationFailed(failure)
            } finally {
                result?.zero()
            }
        }

        override fun close() {
            synchronized(keyLock) {
                closed = true
                retainedReadAuthority = null
                released.contentKey.zero()
                headerAad.zero()
            }
            synchronized(chunkReaders) { chunkReaders.remove(this) }
        }
    }

    override suspend fun decryptStream(
        request: ReleaseContentRequest,
        ciphertext: InputStream,
        plaintext: OutputStream,
    ) = withContext(Dispatchers.IO) {
        require(request.header.cipherSuite == ContentCipherSuite.CHUNKED_AES_256_GCM_HKDF_SHA256)
        val releasedKey = releaseContentKey(request, ciphertextHash = null, retainMediaAuthority = false)
        try {
            decryptChunks(request.header, releasedKey.contentKey, ciphertext, plaintext)
        } finally {
            releasedKey.contentKey.zero()
        }
    }

    override suspend fun decryptMediaStreamWithAuthority(
        request: ReleaseContentRequest,
        ciphertext: InputStream,
        plaintext: OutputStream,
    ): ProtectedMediaReleaseAuthority = withContext(Dispatchers.IO) {
        require(request.header.cipherSuite == ContentCipherSuite.CHUNKED_AES_256_GCM_HKDF_SHA256)
        val snapshot = request.copy(
            header = ContentEnvelopeBinaryFormat.decodeHeader(ContentEnvelopeBinaryFormat.encodeHeader(request.header)),
            requestNonce = request.requestNonce.copyOf(),
        )
        val releasedKey = releaseContentKey(snapshot, ciphertextHash = null, retainMediaAuthority = true)
        try {
            decryptChunks(snapshot.header, releasedKey.contentKey, ciphertext, plaintext)
            val description = requireNotNull(releasedKey.mediaAuthority) {
                "Verified media release authority was not retained."
            }
            val trustedTime = requireNotNull(releasedKey.mediaAuthorityTime) {
                "Verified media release authority time was not retained."
            }
            when (val reading = trustedTime.read(authorityClock, runtimeBindingProvider)) {
                is ProtectedMediaAuthorityTimeReading.Available -> {
                    if (reading.epochMillis >= description.authorizationDeadlineEpochMillis) {
                        throw ContentProtectionException.Expired()
                    }
                }
                ProtectedMediaAuthorityTimeReading.BindingInvalid,
                ProtectedMediaAuthorityTimeReading.Unavailable,
                -> throw ContentProtectionException.AuthenticationFailed()
            }
            Timber.d(
                "Secure media authority issued mode=%s grantLifetimeMillis=%d authorizationWindowMillis=%d",
                description.mode,
                description.releaseExpiresAtEpochMillis - description.issuedAtEpochMillis,
                description.authorizationDeadlineEpochMillis - description.serverTimeEpochMillis,
            )
            IssuedProtectedMediaReleaseAuthority(
                issuer = mediaAuthorityIssuer,
                description = description,
                trustedTime = trustedTime,
                renewalRequest = snapshot,
            )
        } finally {
            releasedKey.contentKey.zero()
        }
    }

    override suspend fun renewProtectedMediaReadAuthority(authority: ProtectedMediaReleaseAuthority): ProtectedMediaReleaseAuthority? {
        val parent = authority as? IssuedProtectedMediaReleaseAuthority ?: return null
        check(validateProtectedMediaReleaseAuthority(parent, parent.description, 0) == ProtectedMediaReleaseAuthorityDecision.VALIDATED)
        val original = parent.renewalRequest ?: return null
        if (parent.externalReadBinding != null || parent.localAuthorityId != null || original.progressiveVideoPlayback ||
            original.burnSessionId != null || original.header.lifecycle.mode != MessageLifecycleMode.FOLLOW_ROOM ||
            original.header.kind != io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT ||
            original.header.offlineLeaseMode != OfflineLeaseMode.ONLINE_REQUIRED) return null
        val now = parent.trustedTime.read(authorityClock, runtimeBindingProvider) as? ProtectedMediaAuthorityTimeReading.Available
            ?: throw ContentProtectionException.AuthenticationFailed()
        if (parent.description.authorizationDeadlineEpochMillis - now.epochMillis > 20_000L) return null
        val freshRequest = original.copy(requestNonce = ByteArray(24).also(secureRandom::nextBytes), timelineReleaseIntent = null)
        val released = releaseContentKey(freshRequest, ciphertextHash = null, retainMediaAuthority = true)
        try {
            // A late response cannot reopen a source whose current permission has already ended.
            check(validateProtectedMediaReleaseAuthority(parent, parent.description, 0) == ProtectedMediaReleaseAuthorityDecision.VALIDATED)
            val description = requireNotNull(released.mediaAuthority)
            val previous = parent.description
            check(description.copy(
                authorityId = previous.authorityId,
                requestNonceSha256Hex = previous.requestNonceSha256Hex,
                issuedAtEpochMillis = previous.issuedAtEpochMillis,
                releaseExpiresAtEpochMillis = previous.releaseExpiresAtEpochMillis,
                authorizationDeadlineEpochMillis = previous.authorizationDeadlineEpochMillis,
                serverTimeEpochMillis = previous.serverTimeEpochMillis,
            ) == previous)
            check(description.authorityId != previous.authorityId && description.requestNonceSha256Hex != previous.requestNonceSha256Hex)
            check(description.authorizationDeadlineEpochMillis > previous.authorizationDeadlineEpochMillis)
            val candidate = IssuedProtectedMediaReleaseAuthority(
                mediaAuthorityIssuer, description, requireNotNull(released.mediaAuthorityTime), renewalRequest = original,
            )
            if (validateProtectedMediaReleaseAuthority(candidate, description, 0) != ProtectedMediaReleaseAuthorityDecision.VALIDATED) {
                candidate.close()
                throw ContentProtectionException.AuthenticationFailed()
            }
            Timber.d("Secure media fresh grant verified stage=foreground_read_admission authorizationWindowMillis=%d",
                description.authorizationDeadlineEpochMillis - description.serverTimeEpochMillis)
            return candidate
        } finally {
            released.contentKey.zero()
            freshRequest.requestNonce.zero()
        }
    }

    override fun protectedMediaDeadlineElapsedRealtimeMillis(authority: ProtectedMediaReleaseAuthority): Long? {
        val issued = authority as? IssuedProtectedMediaReleaseAuthority ?: return null
        if (validateProtectedMediaReleaseAuthority(issued, issued.description, 0) != ProtectedMediaReleaseAuthorityDecision.VALIDATED) return null
        issued.deadlineElapsedRealtimeMillis?.let { return it }
        val reading = issued.trustedTime.read(authorityClock, runtimeBindingProvider) as? ProtectedMediaAuthorityTimeReading.Available ?: return null
        val remaining = issued.description.authorizationDeadlineEpochMillis - reading.epochMillis
        if (remaining <= 0) return null
        return runCatching { Math.addExact(reading.elapsedRealtimeMillis, remaining) }.getOrNull()
    }

    override fun deriveProtectedMediaExternalReadAuthority(
        authority: ProtectedMediaReleaseAuthority,
        binding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding,
    ): ProtectedMediaReleaseAuthority? {
        val parent = authority as? IssuedProtectedMediaReleaseAuthority ?: return null
        if (!externalReadAllowed(binding) || parent.externalReadBinding != null || validateProtectedMediaReleaseAuthority(parent, parent.description, 0L) !=
            ProtectedMediaReleaseAuthorityDecision.VALIDATED) {
                return null
            }
        if (binding.owner.accountIdHash != parent.description.accountIdHash ||
            binding.owner.installationId != parent.description.installationId ||
            binding.sourcePolicyVersion != parent.description.policyVersion) {
                return null
            }
        val deadline = protectedMediaDeadlineElapsedRealtimeMillis(parent) ?: return null
        return IssuedProtectedMediaReleaseAuthority(
            mediaAuthorityIssuer,
            parent.description,
            parent.trustedTime,
            binding,
            java.util.UUID.randomUUID().toString(),
            deadline
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override fun validateProtectedMediaReleaseAuthority(
        authority: ProtectedMediaReleaseAuthority,
        expected: ProtectedMediaReleaseAuthorityDescription,
        effectiveNowEpochMillis: Long,
    ): ProtectedMediaReleaseAuthorityDecision {
        val issued = authority as? IssuedProtectedMediaReleaseAuthority
            ?: return ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE
        if (issued.issuer !== mediaAuthorityIssuer) return ProtectedMediaReleaseAuthorityDecision.OWNER_MISMATCH
        if (issued.closed.get()) return ProtectedMediaReleaseAuthorityDecision.CLOSED
        if (issued.externalReadBinding?.let { !externalReadAllowed(it) } == true) {
            return ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE
        }
        if (issued.description != expected) return ProtectedMediaReleaseAuthorityDecision.RELEASE_MISMATCH
        return when (val reading = issued.trustedTime.read(authorityClock, runtimeBindingProvider)) {
            is ProtectedMediaAuthorityTimeReading.Available -> {
                if (reading.epochMillis >= expected.authorizationDeadlineEpochMillis) {
                    ProtectedMediaReleaseAuthorityDecision.EXPIRED
                } else {
                    ProtectedMediaReleaseAuthorityDecision.VALIDATED
                }
            }
            ProtectedMediaAuthorityTimeReading.BindingInvalid -> ProtectedMediaReleaseAuthorityDecision.BINDING_INVALID
            ProtectedMediaAuthorityTimeReading.Unavailable -> ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE
        }
    }

    private fun externalReadAllowed(binding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding): Boolean =
        runCatching {
            if (externalPreviewGate?.canAttemptPreview() != true) return@runCatching false
            val provider = externalPolicyProvider ?: return@runCatching false
            val current = provider.current()
            val original = provider.resolve(binding.sourcePolicyId, binding.sourcePolicyVersion) ?: return@runCatching false
            current.source == io.element.android.security.dataprotection.api.DataProtectionPolicySource.VERIFIED_REMOTE &&
                original.source == io.element.android.security.dataprotection.api.DataProtectionPolicySource.VERIFIED_REMOTE &&
                current.policy.allowContentExport && original.policy.allowContentExport
        }.getOrDefault(false)

    override suspend fun destroyContentAccess(
        vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        contentId: ContentId,
        reason: ContentAccessDestroyReason,
    ): Boolean {
        contentKeyCache.remove(vaultId, contentId)
        val locallyRevoked = offlineLicenseService.revokeContent(vaultId, contentId)
        val remotelyDestroyed = kmsShareClient.destroyShare(contentId, reason)
        return locallyRevoked && remotelyDestroyed
    }

    override suspend fun revokeLocalContent(
        vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        contentId: ContentId,
    ): Boolean {
        contentKeyCache.remove(vaultId, contentId)
        return offlineLicenseService.revokeContent(vaultId, contentId)
    }

    override suspend fun beginBurnAfterRead(request: OpenBurnAfterReadRequest): BurnAfterReadSession =
        kmsShareClient.openBurnAfterRead(request)

    override suspend fun endBurnAfterRead(
        vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        request: EndBurnAfterReadRequest,
    ): BurnAfterReadEndResult {
        contentKeyCache.remove(vaultId, request.contentId)
        val ended = try {
            kmsShareClient.endBurnAfterRead(request)
        } finally {
            // Ending a view is a permanent account-level decision. It must remain fail-closed even if the final
            // network acknowledgement is lost; the server-side absolute window independently expires the grant.
            offlineLicenseService.revokeContent(vaultId, request.contentId)
        }
        return ended
    }

    private suspend fun prepareProtection(
        request: ProtectContentRequest,
        cipherSuite: ContentCipherSuite,
        beforeKmsStore: suspend () -> Unit = { },
        preservePreparedOnFailure: Boolean = false,
    ): PreparedProtection {
        var activeRequest = request
        var lifecycleContractRefreshed = false
        while (true) {
            try {
                return prepareProtectionAttempt(activeRequest, cipherSuite, beforeKmsStore, preservePreparedOnFailure)
            } catch (failure: ContentProtectionException.LifecycleContractRefreshRequired) {
                if (lifecycleContractRefreshed || !activeRequest.canRefreshLifecycleContract()) {
                    throw failure
                }
                lifecycleContractRefreshed = true
                activeRequest = activeRequest.withFreshLifecycleContract()
            }
        }
    }

    private suspend fun ProtectContentRequest.withFreshLifecycleContract(): ProtectContentRequest {
        val refreshed = kmsShareClient.resolveRoomLifecycle(roomId)
        val expectedRoomHash = sha256(roomId.encodeToByteArray())
        try {
            if (refreshed.protocolVersion != ROOM_LIFECYCLE_CONTRACT_PROTOCOL_VERSION ||
                !MessageDigest.isEqual(expectedRoomHash, refreshed.roomIdSha256) ||
                refreshed.messageExpiresAtEpochMillis <= createdAtEpochMillis
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return copy(
                policy = policy.copy(expiresAtEpochMillis = refreshed.messageExpiresAtEpochMillis),
                roomLifecycle = refreshed,
            )
        } finally {
            expectedRoomHash.zero()
        }
    }

    private fun ProtectContentRequest.canRefreshLifecycleContract(): Boolean =
        lifecycle.mode == MessageLifecycleMode.FOLLOW_ROOM &&
            roomLifecycle?.protocolVersion == ROOM_LIFECYCLE_CONTRACT_PROTOCOL_VERSION

    private suspend fun prepareProtectionAttempt(
        request: ProtectContentRequest,
        cipherSuite: ContentCipherSuite,
        beforeKmsStore: suspend () -> Unit,
        preservePreparedOnFailure: Boolean,
    ): PreparedProtection {
        validateProtectionRequest(request)
        LifecycleStageTiming.measure("send_security_gate", request.contentId.value) {
            stageThreeSecurityGate.requireAccess(
            StageThreeGateRequest(
                operation = StageThreeOperation.PROTECT,
                vaultId = request.vaultId,
                accountIdHash = request.accountIdHash,
                installationId = request.senderInstallationId,
                policy = request.policy,
                contentCreatedAtEpochMillis = request.createdAtEpochMillis,
                offlineLeaseMode = request.offlineLeaseMode,
                audienceAccountIdHashes = request.audienceAccountIdHashes,
                recipients = request.recipients,
                roomLifecycle = request.roomLifecycle,
            )
        )
        }
        val localKey = deviceShareKeyService.descriptor()
        require(localKey.installationId == request.senderInstallationId)
        require(request.recipients.any { it.installationId == localKey.installationId && it.keyId == localKey.keyId })
        val authorizedInstallationIds = request.recipients.mapTo(mutableSetOf()) { it.installationId }
        val useRoomEpoch = request.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED
        val formatVersion = if (useRoomEpoch) {
            ContentProtectionFormat.CURRENT_VERSION
        } else {
            ContentProtectionFormat.LEGACY_DEVICE_ENVELOPE_VERSION
        }
        val roomIdSha256 = if (useRoomEpoch) sha256(request.roomId.encodeToByteArray()) else null
        var roomEpoch: RoomEpochReference? = null
        var acquiredEpochSecret: ByteArray? = null
        if (useRoomEpoch) {
            val roomHash = requireNotNull(roomIdSha256)
            val audienceDigest = roomAudienceDigest(request.audienceAccountIdHashes, authorizedInstallationIds)
            val cached = roomEpochCache.forSending(roomHash, audienceDigest, request.accountIdHash)
            val released = cached ?: randomBytes(ROOM_EPOCH_REQUEST_NONCE_BYTES).let { nonce ->
                try {
                    kmsShareClient.acquireRoomEpoch(
                        AcquireRoomEpochRequest(
                            roomId = request.roomId,
                            roomIdSha256 = roomHash,
                            accountIdHash = request.accountIdHash,
                            audienceAccountIdHashes = request.audienceAccountIdHashes,
                            authorizedInstallationIds = authorizedInstallationIds,
                            requestingInstallationId = request.senderInstallationId,
                            requestingKeyId = localKey.keyId,
                            requestNonce = nonce,
                        )
                    ).also { value ->
                        validateRoomEpoch(
                            released = value,
                            roomIdSha256 = roomHash,
                            audienceDigestSha256 = audienceDigest,
                            accountIdHash = request.accountIdHash,
                            installationId = request.senderInstallationId,
                            recipientKeyId = localKey.keyId,
                            requestNonce = nonce,
                            requireActiveForSend = true,
                        )
                        roomEpochCache.put(value)
                    }
                } finally {
                    nonce.zero()
                }
            }
            roomEpoch = released.reference
            acquiredEpochSecret = released.epochSecret
        }
        val immutableContextSha256 = sha256(
            immutableContext(
                roomId = request.roomId,
                formatVersion = formatVersion,
                contentId = request.contentId,
                kind = request.kind,
                cipherSuite = cipherSuite,
                policy = request.policy,
                senderInstallationId = request.senderInstallationId,
                audienceAccountIdHashes = request.audienceAccountIdHashes,
                authorizedInstallationIds = authorizedInstallationIds,
                roomEpoch = roomEpoch,
                createdAtEpochMillis = request.createdAtEpochMillis,
                leaseMode = request.offlineLeaseMode,
                lifecycle = request.lifecycle,
            )
        )
        val deviceShare = if (useRoomEpoch) {
            deriveRoomEpochDeviceShare(requireNotNull(acquiredEpochSecret), immutableContextSha256)
        } else {
            randomBytes(ContentProtectionFormat.SHARE_SIZE_BYTES)
        }
        val kmsShare = randomBytes(ContentProtectionFormat.SHARE_SIZE_BYTES)
        val contentKey = LifecycleStageTiming.measure("send_key_derive", request.contentId.value) {
            deriveContentKey(deviceShare, kmsShare, immutableContextSha256)
        }
        var shareStored = false
        try {
            val deviceEnvelopes = if (useRoomEpoch) {
                emptyList()
            } else {
                request.recipients.sortedBy { it.installationId }.map { recipient ->
                    deviceShareKeyService.wrapShare(recipient, request.contentId, immutableContextSha256, deviceShare)
                }
            }
            val kmsReference = LifecycleStageTiming.measure("send_kms_store", request.contentId.value) {
                // A failed/cancelled journal commit must stop before any remote share creation.
                beforeKmsStore()
                kmsShareClient.storeShare(
                StoreKmsShareRequest(
                    contentId = request.contentId,
                    // The server hashes this identifier and freezes the room lifecycle profile into the content
                    // row. It is required even when small rooms use per-device envelopes rather than a room epoch.
                    roomId = request.roomId,
                    kind = request.kind,
                    policy = request.policy,
                    accountIdHash = request.accountIdHash,
                    senderInstallationId = request.senderInstallationId,
                    audienceAccountIdHashes = request.audienceAccountIdHashes,
                    authorizedInstallationIds = authorizedInstallationIds,
                    immutableContextSha256 = immutableContextSha256,
                    kmsShare = kmsShare,
                    leaseMode = request.offlineLeaseMode,
                    roomEpoch = roomEpoch,
                    lifecycle = request.lifecycle,
                    roomLifecycle = request.roomLifecycle,
                    clientCreatedAtEpochMillis = request.createdAtEpochMillis,
                    matrixTransactionIdSha256 = sha256(request.contentId.value.encodeToByteArray()),
                )
            )
            }
            shareStored = true
            val header = ContentProtectionHeader(
                formatVersion = formatVersion,
                contentId = request.contentId,
                kind = request.kind,
                cipherSuite = cipherSuite,
                policy = request.policy,
                senderInstallationId = request.senderInstallationId,
                audienceAccountIdHashes = if (useRoomEpoch) emptySet() else request.audienceAccountIdHashes,
                createdAtEpochMillis = request.createdAtEpochMillis,
                immutableContextSha256 = immutableContextSha256.copyOf(),
                nonce = randomBytes(ContentProtectionFormat.GCM_NONCE_SIZE_BYTES),
                deviceShareEnvelopes = deviceEnvelopes,
                roomEpoch = roomEpoch,
                kmsShareReference = kmsReference,
                offlineLeaseMode = request.offlineLeaseMode,
                lifecycle = request.lifecycle,
            )
            validator.validate(header).getOrElse { throw ContentProtectionException.InvalidEnvelope(it) }
            return PreparedProtection(header, contentKey)
        } catch (failure: Exception) {
            contentKey.zero()
            if (shareStored && !preservePreparedOnFailure) retireFailedProtection(request.contentId)
            throw failure
        } finally {
            deviceShare.zero()
            kmsShare.zero()
            immutableContextSha256.zero()
            roomIdSha256?.zero()
            acquiredEpochSecret?.zero()
        }
    }

    private suspend fun retireFailedProtection(contentId: ContentId) {
        try {
            kmsShareClient.destroyShare(contentId, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
        } catch (_: Exception) {
            // Preserve the original encryption/validation failure. The KMS must also expire orphaned shares server-side.
        }
    }

    private data class ReleasedContentKey(
        val contentKey: ByteArray,
        val mediaAuthority: ProtectedMediaReleaseAuthorityDescription?,
        val mediaAuthorityTime: TrustedProtectedMediaAuthorityTime? = null,
        val progressivePlaybackDeadlineEpochMillis: Long? = null,
    )

    private inner class IssuedProtectedMediaReleaseAuthority(
        val issuer: Any,
        override val description: ProtectedMediaReleaseAuthorityDescription,
        val trustedTime: TrustedProtectedMediaAuthorityTime,
        override val externalReadBinding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding? = null,
        override val localAuthorityId: String? = null,
        val deadlineElapsedRealtimeMillis: Long? = null,
        val renewalRequest: ReleaseContentRequest? = null,
    ) : ProtectedMediaReleaseAuthority {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }

    private suspend fun releaseContentKey(
        request: ReleaseContentRequest,
        ciphertextHash: ByteArray?,
        retainMediaAuthority: Boolean,
    ): ReleasedContentKey {
        if (request.progressiveVideoPlayback) {
            require(retainMediaAuthority && ciphertextHash == null)
            require(request.header.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED)
            require(request.header.cipherSuite == ContentCipherSuite.CHUNKED_AES_256_GCM_HKDF_SHA256)
            require(request.header.kind in setOf(
                io.element.android.security.contentprotection.api.ContentKind.VIDEO,
                io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT,
            ))
        }
        val releaseStartedAt = System.nanoTime()
        val epochAdmissionGeneration = roomEpochCache.generation()
        validator.validate(request.header).getOrElse { throw ContentProtectionException.InvalidEnvelope(it) }
        validateReleaseRequest(request)
        val roomEpochFormat = request.header.formatVersion >= ContentProtectionFormat.CURRENT_VERSION
        if (!roomEpochFormat && request.accountIdHash !in request.header.audienceAccountIdHashes) denyRelease("AUDIENCE")
        val gateAudience = if (roomEpochFormat) setOf(request.accountIdHash) else request.header.audienceAccountIdHashes
        val releaseRetentionAuthority = if (
            roomEpochFormat &&
            request.header.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED &&
            request.header.roomEpoch != null &&
            request.header.deviceShareEnvelopes.isEmpty()
        ) {
            StageThreeReleaseRetentionAuthority.ONLINE_KMS
        } else {
            StageThreeReleaseRetentionAuthority.LOCAL_POLICY
        }
        LifecycleStageTiming.measure("receive_security_gate", request.header.contentId.value) {
            stageThreeSecurityGate.requireAccess(
            StageThreeGateRequest(
                operation = StageThreeOperation.RELEASE,
                vaultId = request.vaultId,
                accountIdHash = request.accountIdHash,
                installationId = request.requestingInstallationId,
                policy = request.header.policy,
                contentCreatedAtEpochMillis = request.header.createdAtEpochMillis,
                offlineLeaseMode = request.header.offlineLeaseMode,
                audienceAccountIdHashes = gateAudience,
                releaseRetentionAuthority = releaseRetentionAuthority,
            )
        )
        }
        if (clock.epochMillis() >= request.header.policy.expiresAtEpochMillis) throw ContentProtectionException.Expired()
        val expectedContext = sha256(
            immutableContext(
                roomId = request.roomId,
                formatVersion = request.header.formatVersion,
                contentId = request.header.contentId,
                kind = request.header.kind,
                cipherSuite = request.header.cipherSuite,
                policy = request.header.policy,
                senderInstallationId = request.header.senderInstallationId,
                audienceAccountIdHashes = request.header.audienceAccountIdHashes,
                authorizedInstallationIds = request.header.deviceShareEnvelopes.mapTo(mutableSetOf()) { it.recipientInstallationId },
                roomEpoch = request.header.roomEpoch,
                createdAtEpochMillis = request.header.createdAtEpochMillis,
                leaseMode = request.header.offlineLeaseMode,
                lifecycle = request.header.lifecycle,
            )
        )
        try {
            if (!MessageDigest.isEqual(expectedContext, request.header.immutableContextSha256)) {
                throw ContentProtectionException.InvalidEnvelope()
            }
            // Local and remotely synchronized tombstones always precede KMS access, including ONLINE_REQUIRED.
            // This prevents an old KMS/database backup from resurrecting content after a verified deletion.
            try {
                LifecycleStageTiming.measure("receive_tombstone_check", request.header.contentId.value) {
                    offlineLicenseService.requireAccessAllowed(request.vaultId, request.header.contentId)
                }
            } catch (failure: ContentProtectionException.AccessDenied) {
                denyRelease("LOCAL_ACCESS_STATE", failure)
            }
            val localKey = deviceShareKeyService.descriptor()
            if (localKey.installationId != request.requestingInstallationId) denyRelease("INSTALLATION")
            val requiresLifecycleAuthorityConfirmation =
                currentLifecycleOperationContext()?.lifecycleId in setOf(FullAppLifecycleId.LC_13, FullAppLifecycleId.LC_15)
            if (ciphertextHash != null && !requiresLifecycleAuthorityConfirmation) {
                contentKeyCache.get(request, ciphertextHash, expectedContext, localKey.keyId)?.let { cachedKey ->
                    Timber.tag(PERFORMANCE_TAG).d(
                        "stage=cek_release cache=hit duration_ms=%d",
                        (System.nanoTime() - releaseStartedAt) / NANOS_PER_MILLISECOND,
                    )
                    return ReleasedContentKey(cachedKey, mediaAuthority = null)
                }
            }
            val bootCount = runtimeBindingProvider.bootCount()
            val deviceShare = LifecycleStageTiming.measure("receive_device_share", request.header.contentId.value) {
                if (roomEpochFormat) {
                val reference = request.header.roomEpoch ?: denyRelease("ROOM_EPOCH_REFERENCE")
                val roomIdSha256 = sha256(request.roomId.encodeToByteArray())
                val releasedEpoch = try {
                    roomEpochCache.releaseOrLoad(reference, request.accountIdHash, epochAdmissionGeneration) {
                        kmsShareClient.releaseRoomEpoch(
                            ReleaseRoomEpochRequest(
                                reference = reference,
                                roomIdSha256 = roomIdSha256,
                                accountIdHash = request.accountIdHash,
                                requestingInstallationId = request.requestingInstallationId,
                                requestingKeyId = localKey.keyId,
                                requestNonce = request.requestNonce,
                            )
                        ).also { value ->
                            try {
                                validateRoomEpoch(
                                    released = value,
                                    roomIdSha256 = roomIdSha256,
                                    audienceDigestSha256 = reference.audienceDigestSha256,
                                    accountIdHash = request.accountIdHash,
                                    installationId = request.requestingInstallationId,
                                    recipientKeyId = localKey.keyId,
                                    requestNonce = request.requestNonce,
                                )
                            } catch (failure: Throwable) {
                                value.epochSecret.zero()
                                throw failure
                            }
                        }
                    }
                } finally {
                    roomIdSha256.zero()
                }
                try {
                    deriveRoomEpochDeviceShare(releasedEpoch.epochSecret, expectedContext)
                } finally {
                    releasedEpoch.epochSecret.zero()
                }
            } else {
                val deviceEnvelope = request.header.deviceShareEnvelopes.singleOrNull {
                    it.recipientInstallationId == localKey.installationId && it.recipientKeyId == localKey.keyId
                } ?: denyRelease("DEVICE_ENVELOPE")
                deviceShareKeyService.unwrapShare(
                    envelope = deviceEnvelope,
                    contentId = request.header.contentId,
                    immutableContextSha256 = expectedContext,
                )
            }
            }
            val kmsRequest = ReleaseKmsShareRequest(
                    reference = request.header.kmsShareReference,
                    contentId = request.header.contentId,
                    policy = request.header.policy,
                    accountIdHash = request.accountIdHash,
                    requestingInstallationId = request.requestingInstallationId,
                    requestingKeyId = localKey.keyId,
                    immutableContextSha256 = expectedContext,
                    requestNonce = request.requestNonce,
                    bootCount = bootCount,
                    burnSessionId = request.burnSessionId,
                    progressiveVideoPlayback = request.progressiveVideoPlayback,
                )
            val released = try {
                LifecycleStageTiming.measure(
                    "receive_kms_wait_and_release",
                    request.header.contentId.value,
                    scope = request.timelineReleaseIntent?.scopeId.orEmpty()
                ) {
                        when (request.header.offlineLeaseMode) {
                    OfflineLeaseMode.ONLINE_REQUIRED -> onlineKmsReleaseBatcher.release(kmsRequest, request.timelineReleaseIntent)
                    OfflineLeaseMode.BOUNDED_OFFLINE -> offlineLicenseService.releaseOrAcquire(request.vaultId, kmsRequest)
                }
                    }
            } catch (failure: ContentProtectionException.AccessDenied) {
                val stage = when (request.header.offlineLeaseMode) {
                    OfflineLeaseMode.ONLINE_REQUIRED -> "KMS_ONLINE"
                    OfflineLeaseMode.BOUNDED_OFFLINE -> "OFFLINE_LICENSE"
                }
                denyRelease(stage, failure)
            }
            try {
                LifecycleStageTiming.measure("receive_grant_verify", request.header.contentId.value) {
                    validateReleasedShare(request, released, bootCount)
                }
                val mediaAuthorityTime = if (retainMediaAuthority) {
                    captureProtectedMediaAuthorityTime(released)
                } else {
                    null
                }
                val mediaAuthority = if (retainMediaAuthority) {
                    ProtectedMediaReleaseAuthorityDescription(
                        authorityId = released.grantId,
                        reference = released.reference,
                        contentId = released.contentId,
                        policyVersion = released.policyVersion,
                        accountIdHash = request.accountIdHash,
                        installationId = released.installationId,
                        immutableContextSha256Hex = released.immutableContextSha256.toMediaAuthorityHex(),
                        requestNonceSha256Hex = sha256(request.requestNonce).let { digest ->
                            try {
                                digest.toMediaAuthorityHex()
                            } finally {
                                digest.zero()
                            }
                        },
                        mode = released.mode,
                        issuedAtEpochMillis = released.issuedAtEpochMillis,
                        releaseExpiresAtEpochMillis = released.expiresAtEpochMillis,
                        authorizationDeadlineEpochMillis = released.authorizationDeadlineEpochMillis,
                        serverTimeEpochMillis = released.serverTimeEpochMillis,
                        bootCount = released.bootCount,
                        burnSessionId = released.burnSessionId,
                    )
                } else {
                    null
                }
                val contentKey = LifecycleStageTiming.measure("receive_key_derive", request.header.contentId.value) {
                    deriveContentKey(deviceShare, released.kmsShare, expectedContext)
                }
                if (ciphertextHash != null) {
                    runCatching {
                        contentKeyCache.put(
                            request = request,
                            ciphertextHash = ciphertextHash,
                            expectedContext = expectedContext,
                            deviceKeyId = localKey.keyId,
                            contentKey = contentKey,
                            expiresAtEpochMillis = released.expiresAtEpochMillis,
                        )
                    }.onFailure {
                        Timber.tag(PERFORMANCE_TAG).w("Unable to wrap an in-memory CEK cache entry: %s", it.javaClass.simpleName)
                    }
                }
                Timber.tag(PERFORMANCE_TAG).d(
                    "stage=cek_release cache=miss duration_ms=%d",
                    (System.nanoTime() - releaseStartedAt) / NANOS_PER_MILLISECOND,
                )
                return ReleasedContentKey(contentKey, mediaAuthority, mediaAuthorityTime, released.progressivePlaybackDeadlineEpochMillis)
            } finally {
                deviceShare.zero()
                released.kmsShare.zero()
            }
        } finally {
            expectedContext.zero()
        }
    }

    private fun captureProtectedMediaAuthorityTime(released: ReleasedKmsShare): TrustedProtectedMediaAuthorityTime {
        val currentBootCount = runCatching { runtimeBindingProvider.bootCount() }
            .getOrElse { throw ContentProtectionException.AuthenticationFailed() }
        val wallClockEpochMillis = runCatching { authorityClock.wallClockEpochMillis() }
            .getOrElse { throw ContentProtectionException.AuthenticationFailed() }
        val elapsedRealtimeMillis = runCatching { authorityClock.elapsedRealtimeMillis() }
            .getOrElse { throw ContentProtectionException.AuthenticationFailed() }
        if (currentBootCount != released.bootCount || wallClockEpochMillis <= 0L || elapsedRealtimeMillis < 0L) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        val trustedEpochMillis = maxOf(released.serverTimeEpochMillis, wallClockEpochMillis)
        if (trustedEpochMillis >= released.authorizationDeadlineEpochMillis) {
            throw ContentProtectionException.Expired()
        }
        return TrustedProtectedMediaAuthorityTime(
            bootCount = released.bootCount,
            anchorEpochMillis = trustedEpochMillis,
            anchorElapsedRealtimeMillis = elapsedRealtimeMillis,
        )
    }

    @Suppress("ComplexCondition")
    private fun validateReleasedShare(request: ReleaseContentRequest, released: ReleasedKmsShare, bootCount: Long) {
        if (released.grantId.isBlank() ||
            released.reference != request.header.kmsShareReference ||
            released.contentId != request.header.contentId ||
            released.policyVersion != request.header.policy.policyVersion ||
            released.installationId != request.requestingInstallationId ||
            released.mode != request.header.offlineLeaseMode ||
            released.burnSessionId != request.burnSessionId ||
            released.bootCount != bootCount ||
            !MessageDigest.isEqual(released.immutableContextSha256, request.header.immutableContextSha256) ||
            !MessageDigest.isEqual(released.requestNonce, request.requestNonce)
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        val playbackDeadline = released.progressivePlaybackDeadlineEpochMillis
        if (request.progressiveVideoPlayback) {
            val maximumDeadline = runCatching { Math.addExact(released.issuedAtEpochMillis, 3_600_000L) }
                .getOrElse { throw ContentProtectionException.AuthenticationFailed(it) }
            if (playbackDeadline == null || released.mode != OfflineLeaseMode.ONLINE_REQUIRED ||
                playbackDeadline < released.expiresAtEpochMillis || playbackDeadline > maximumDeadline ||
                playbackDeadline > request.header.policy.expiresAtEpochMillis
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
        } else if (playbackDeadline != null) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        if (released.kmsShare.size != ContentProtectionFormat.SHARE_SIZE_BYTES ||
            released.issuedAtEpochMillis <= 0L ||
            released.issuedAtEpochMillis > released.serverTimeEpochMillis ||
            released.serverTimeEpochMillis >= released.authorizationDeadlineEpochMillis ||
            released.authorizationDeadlineEpochMillis > released.expiresAtEpochMillis ||
            released.serverTimeEpochMillis >= released.expiresAtEpochMillis ||
            released.expiresAtEpochMillis > request.header.policy.expiresAtEpochMillis ||
            released.bootCount < 0L ||
            clock.epochMillis() >= released.authorizationDeadlineEpochMillis
        ) {
            throw ContentProtectionException.Expired()
        }
    }

    private fun ByteArray.toMediaAuthorityHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Suppress("ComplexCondition")
    private fun validateRoomEpoch(
        released: ReleasedRoomEpoch,
        roomIdSha256: ByteArray,
        audienceDigestSha256: ByteArray,
        accountIdHash: String,
        installationId: String,
        recipientKeyId: String,
        requestNonce: ByteArray,
        requireActiveForSend: Boolean = false,
    ) {
        if (!MessageDigest.isEqual(released.roomIdSha256, roomIdSha256) ||
            !MessageDigest.isEqual(released.reference.audienceDigestSha256, audienceDigestSha256) ||
            released.accountIdHash != accountIdHash ||
            released.installationId != installationId ||
            released.recipientKeyId != recipientKeyId ||
            !MessageDigest.isEqual(released.requestNonce, requestNonce) ||
            released.reference.epochId.isBlank() ||
            released.reference.generation <= 0
        ) {
            released.epochSecret.zero()
            throw ContentProtectionException.AuthenticationFailed()
        }
        if (released.epochSecret.size != ContentProtectionFormat.SHARE_SIZE_BYTES ||
            released.serverTimeEpochMillis >= released.releaseUntilEpochMillis ||
            clock.epochMillis() >= released.releaseUntilEpochMillis ||
            (requireActiveForSend &&
                released.serverTimeEpochMillis + ROOM_EPOCH_SEND_HEADROOM_MILLIS >= released.activeUntilEpochMillis)
        ) {
            released.epochSecret.zero()
            throw ContentProtectionException.Expired()
        }
    }

    private fun roomAudienceDigest(accountHashes: Set<String>, installationIds: Set<String>): ByteArray {
        val encoded = java.io.ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(ROOM_AUDIENCE_MAGIC)
                listOf(accountHashes.sorted(), installationIds.sorted()).forEach { values ->
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

    private fun deriveRoomEpochDeviceShare(epochSecret: ByteArray, contextSha256: ByteArray): ByteArray {
        require(epochSecret.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        require(contextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        val input = ROOM_EPOCH_MESSAGE_INFO + contextSha256
        return try {
            Mac.getInstance(HMAC_SHA256).run {
                init(SecretKeySpec(epochSecret, HMAC_SHA256))
                doFinal(input).copyOf(ContentProtectionFormat.SHARE_SIZE_BYTES)
            }
        } finally {
            input.zero()
        }
    }

    private fun validateProtectionRequest(request: ProtectContentRequest) {
        require(request.contentId.value.isNotBlank())
        require(request.accountIdHash.isNotBlank())
        require(request.roomId.isNotBlank())
        require(request.senderInstallationId.isNotBlank())
        require(request.accountIdHash in request.audienceAccountIdHashes)
        require(
            request.audienceAccountIdHashes.isNotEmpty() &&
                request.audienceAccountIdHashes.size <= ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS
        )
        require(request.audienceAccountIdHashes.all(ACCOUNT_HASH::matches))
        require(request.createdAtEpochMillis > 0)
        require(request.policy.policyVersion > 0)
        require(request.policy.expiresAtEpochMillis > request.createdAtEpochMillis)
        if (request.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
            require(request.lifecycle.conversationKind != null)
            require(request.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED)
            require(request.kind in BURN_AFTER_READ_KINDS)
        }
        val maximumRecipients = if (request.offlineLeaseMode == OfflineLeaseMode.ONLINE_REQUIRED) {
            ContentProtectionFormat.MAX_ROOM_AUTHORIZED_INSTALLATIONS
        } else {
            ContentProtectionFormat.MAX_LEGACY_DEVICE_SHARE_ENVELOPES
        }
        require(request.recipients.isNotEmpty() && request.recipients.size <= maximumRecipients)
        require(request.recipients.map { it.installationId }.distinct().size == request.recipients.size)
        request.recipients.forEach { recipient ->
            require(recipient.installationId.isNotBlank())
            require(recipient.keyId.isNotBlank())
            require(recipient.algorithm == ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
            require(recipient.publicKeyDer.isNotEmpty() && recipient.publicKeyDer.size <= MAX_PUBLIC_KEY_BYTES)
        }
    }

    private fun validateReleaseRequest(request: ReleaseContentRequest) {
        require(request.accountIdHash.isNotBlank())
        require(request.roomId.isNotBlank())
        require(request.requestingInstallationId.isNotBlank())
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
        if (request.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
            require(!request.burnSessionId.isNullOrBlank())
        } else {
            require(request.burnSessionId == null)
        }
    }

    private fun immutableContext(
        roomId: String,
        formatVersion: Int,
        contentId: ContentId,
        kind: io.element.android.security.contentprotection.api.ContentKind,
        cipherSuite: ContentCipherSuite,
        policy: ContentPolicyReference,
        senderInstallationId: String,
        audienceAccountIdHashes: Set<String>,
        authorizedInstallationIds: Set<String>,
        roomEpoch: RoomEpochReference?,
        createdAtEpochMillis: Long,
        leaseMode: OfflineLeaseMode,
        lifecycle: io.element.android.security.contentprotection.api.ContentLifecycle,
    ): ByteArray = ContentEnvelopeBinaryFormat.encodeImmutableContext(
        roomId = roomId,
        formatVersion = formatVersion,
        contentId = contentId,
        kind = kind,
        cipherSuite = cipherSuite,
        policy = policy,
        senderInstallationId = senderInstallationId,
        audienceAccountIdHashes = audienceAccountIdHashes,
        authorizedInstallationIds = authorizedInstallationIds,
        roomEpoch = roomEpoch,
        createdAtEpochMillis = createdAtEpochMillis,
        offlineLeaseMode = leaseMode,
        lifecycle = lifecycle,
    )

    private fun encryptChunks(
        header: ContentProtectionHeader,
        key: ByteArray,
        plaintext: InputStream,
        ciphertext: OutputStream,
    ) {
        val aad = ContentEnvelopeBinaryFormat.encodeHeader(header)
        val output = DataOutputStream(ciphertext)
        var current = ByteArray(STREAM_CHUNK_BYTES)
        var next = ByteArray(STREAM_CHUNK_BYTES)
        try {
            var currentLength = plaintext.readChunk(current)
            if (currentLength < 0) {
                writeEncryptedChunk(output, header.nonce, key, aad, 0, true, current, 0)
                return
            }
            var chunkIndex = 0L
            while (true) {
                val nextLength = plaintext.readChunk(next)
                val finalChunk = nextLength < 0
                writeEncryptedChunk(output, header.nonce, key, aad, chunkIndex, finalChunk, current, currentLength)
                if (finalChunk) break
                val swap = current
                current = next
                next = swap
                currentLength = nextLength
                chunkIndex = Math.addExact(chunkIndex, 1L)
            }
        } finally {
            current.zero()
            next.zero()
            aad.zero()
        }
    }

    private fun writeEncryptedChunk(
        output: DataOutputStream,
        baseNonce: ByteArray,
        key: ByteArray,
        headerAad: ByteArray,
        index: Long,
        finalChunk: Boolean,
        buffer: ByteArray,
        length: Int,
    ) {
        val nonce = chunkNonce(baseNonce, index)
        val aad = chunkAssociatedData(headerAad, index, finalChunk)
        val chunkPlaintext = buffer.copyOfRange(0, length)
        val encrypted = try {
            encryptAead(key, nonce, aad, chunkPlaintext)
        } finally {
            chunkPlaintext.zero()
        }
        try {
            output.writeByte(if (finalChunk) FINAL_CHUNK_FLAG else 0)
            output.writeInt(encrypted.size)
            output.write(encrypted)
        } finally {
            nonce.zero()
            aad.zero()
            encrypted.zero()
        }
    }

    private fun decryptChunks(
        header: ContentProtectionHeader,
        key: ByteArray,
        ciphertext: InputStream,
        plaintext: OutputStream,
    ) {
        val aad = ContentEnvelopeBinaryFormat.encodeHeader(header)
        val input = DataInputStream(ciphertext)
        var index = 0L
        try {
            while (true) {
                val flags = try {
                    input.readUnsignedByte()
                } catch (_: EOFException) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
                require(flags == 0 || flags == FINAL_CHUNK_FLAG)
                val finalChunk = flags == FINAL_CHUNK_FLAG
                val encryptedLength = input.readInt()
                require(encryptedLength in GCM_TAG_SIZE_BYTES..MAX_ENCRYPTED_CHUNK_BYTES)
                val encrypted = input.readExact(encryptedLength)
                val nonce = chunkNonce(header.nonce, index)
                val chunkAad = chunkAssociatedData(aad, index, finalChunk)
                val decrypted = try {
                    decryptAead(key, nonce, chunkAad, encrypted)
                } finally {
                    encrypted.zero()
                    nonce.zero()
                    chunkAad.zero()
                }
                try {
                    require(decrypted.size <= STREAM_CHUNK_BYTES)
                    plaintext.write(decrypted)
                } finally {
                    decrypted.zero()
                }
                if (finalChunk) {
                    if (input.read() != -1) throw ContentProtectionException.AuthenticationFailed()
                    break
                }
                index = Math.addExact(index, 1L)
            }
        } catch (failure: ContentProtectionException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        } finally {
            aad.zero()
        }
    }

    private fun deriveContentKey(deviceShare: ByteArray, kmsShare: ByteArray, contextSha256: ByteArray): ByteArray {
        require(deviceShare.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        require(kmsShare.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        require(contextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        val inputKeyMaterial = deviceShare + kmsShare
        val extract = Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(contextSha256, HMAC_SHA256))
            doFinal(inputKeyMaterial)
        }
        inputKeyMaterial.zero()
        return try {
            val info = HKDF_INFO + contextSha256
            val output = Mac.getInstance(HMAC_SHA256).run {
                init(SecretKeySpec(extract, HMAC_SHA256))
                doFinal(info + byteArrayOf(1))
            }
            info.zero()
            output.copyOf(ContentProtectionFormat.CONTENT_KEY_SIZE_BYTES).also { output.zero() }
        } finally {
            extract.zero()
        }
    }

    private fun encryptAead(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray =
        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce))
            updateAAD(associatedData)
            doFinal(plaintext)
        }

    private fun decryptAead(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray = try {
        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce))
            updateAAD(associatedData)
            doFinal(ciphertext)
        }
    } catch (failure: AEADBadTagException) {
        throw ContentProtectionException.AuthenticationFailed(failure)
    }

    private fun chunkNonce(baseNonce: ByteArray, index: Long): ByteArray = baseNonce.copyOf().also { nonce ->
        for (offset in 0 until Long.SIZE_BYTES) {
            val position = nonce.lastIndex - offset
            nonce[position] = (nonce[position].toInt() xor index.ushr(offset * Byte.SIZE_BITS).toInt()).toByte()
        }
    }

    private fun chunkAssociatedData(headerAad: ByteArray, index: Long, finalChunk: Boolean): ByteArray =
        headerAad + ByteArray(Long.SIZE_BYTES + 1).also { suffix ->
            for (offset in 0 until Long.SIZE_BYTES) {
                suffix[offset] = index.ushr((Long.SIZE_BYTES - offset - 1) * Byte.SIZE_BITS).toByte()
            }
            suffix[suffix.lastIndex] = if (finalChunk) 1 else 0
        }

    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read < 0) return if (count == 0) -1 else count
            if (read == 0) continue
            count += read
        }
        return count
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(SHA256).digest(bytes).also { bytes.zero() }

    private fun ByteArray.zero() = fill(0)

    private fun denyRelease(
        stage: String,
        cause: ContentProtectionException.AccessDenied? = null,
    ): Nothing {
        Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_RELEASE_DENIED stage=%s", stage)
        if (cause != null) throw cause
        throw ContentProtectionException.AccessDenied()
    }

    private data class PreparedProtection(
        val header: ContentProtectionHeader,
        val contentKey: ByteArray,
    )

    private companion object {
        val HKDF_INFO = "YiZheChat-Content-Key-v1".encodeToByteArray()
        val ROOM_AUDIENCE_MAGIC = "YZRA0001".encodeToByteArray()
        val ROOM_EPOCH_MESSAGE_INFO = "YiZheChat-Room-Epoch-Message-v1".encodeToByteArray()
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_ALGORITHM = "AES"
        const val HMAC_SHA256 = "HmacSHA256"
        const val SHA256 = "SHA-256"
        const val ROOM_LIFECYCLE_CONTRACT_PROTOCOL_VERSION = 3
        const val GCM_TAG_SIZE_BITS = 128
        const val GCM_TAG_SIZE_BYTES = GCM_TAG_SIZE_BITS / Byte.SIZE_BITS
        const val STREAM_CHUNK_BYTES = 64 * 1024
        const val MAX_ENCRYPTED_CHUNK_BYTES = STREAM_CHUNK_BYTES + GCM_TAG_SIZE_BYTES
        const val FINAL_CHUNK_FLAG = 1
        const val MAX_IN_MEMORY_CONTENT_BYTES = 16 * 1024 * 1024
        const val MAX_IN_MEMORY_CIPHERTEXT_BYTES = MAX_IN_MEMORY_CONTENT_BYTES + GCM_TAG_SIZE_BYTES
        const val MAX_PUBLIC_KEY_BYTES = 8192
        const val MAX_REQUEST_NONCE_BYTES = 64
        const val ROOM_EPOCH_REQUEST_NONCE_BYTES = 24
        const val ROOM_EPOCH_SEND_HEADROOM_MILLIS = 60_000L
        val ACCOUNT_HASH = Regex("[0-9a-f]{64}")
        val BURN_AFTER_READ_KINDS = setOf(
            io.element.android.security.contentprotection.api.ContentKind.TEXT,
            io.element.android.security.contentprotection.api.ContentKind.IMAGE,
            io.element.android.security.contentprotection.api.ContentKind.VOICE_MESSAGE,
            io.element.android.security.contentprotection.api.ContentKind.VIDEO,
            io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT,
            io.element.android.security.contentprotection.api.ContentKind.LOCATION,
        )
        const val PERFORMANCE_TAG = "YiZheTimelinePerf"
        const val PROTECTED_DIAGNOSTIC_TAG = "YiZheProtectedContent"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** Volatile epoch cache. It is wiped on app lock/logout/revocation and never survives process death. */
internal class RoomEpochMemoryCache(
    private val clock: SystemClock,
    private val maximumEntries: Int = 64,
) {
    private data class Entry(
        val reference: RoomEpochReference,
        val roomIdSha256: ByteArray,
        val accountIdHash: String,
        val installationId: String,
        val recipientKeyId: String,
        val activeUntilEpochMillis: Long,
        val releaseUntilEpochMillis: Long,
        val cacheUntilEpochMillis: Long,
        val secret: ByteArray,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val releaseLocks = Array(16) { Mutex() }
    private var invalidationGeneration = 0L

    @Synchronized
    fun generation(): Long = invalidationGeneration

    /** Reuses only the existing validated volatile epoch; each message still requires its own KMS share. */
    suspend fun releaseOrLoad(
        reference: RoomEpochReference,
        accountIdHash: String,
        admissionGeneration: Long = generation(),
        load: suspend () -> ReleasedRoomEpoch,
    ): ReleasedRoomEpoch = releaseLocks[(reference.epochId.hashCode() xor accountIdHash.hashCode()) and 15].withLock {
        val cached = synchronized(this) {
            if (admissionGeneration != invalidationGeneration) throw ContentProtectionException.AccessDenied()
            forRelease(reference, accountIdHash)
        }
        if (cached != null) return@withLock cached
        val released = load()
        try {
            synchronized(this) {
                if (admissionGeneration != invalidationGeneration) throw ContentProtectionException.AccessDenied()
                if (released.accountIdHash != accountIdHash || released.reference.epochId != reference.epochId ||
                    released.reference.generation != reference.generation ||
                    !MessageDigest.isEqual(released.reference.audienceDigestSha256, reference.audienceDigestSha256)
                ) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
                put(released)
            }
            released
        } catch (failure: Throwable) {
            released.epochSecret.fill(0)
            throw failure
        }
    }

    @Synchronized
    fun forSending(roomIdSha256: ByteArray, audienceDigestSha256: ByteArray, accountIdHash: String): ReleasedRoomEpoch? {
        prune()
        val now = clock.epochMillis()
        val entry = entries.values.firstOrNull { candidate ->
            now + ROOM_EPOCH_SEND_HEADROOM_MILLIS < candidate.activeUntilEpochMillis &&
                candidate.accountIdHash == accountIdHash &&
                MessageDigest.isEqual(candidate.roomIdSha256, roomIdSha256) &&
                MessageDigest.isEqual(candidate.reference.audienceDigestSha256, audienceDigestSha256)
        } ?: return null
        return entry.releasedCopy()
    }

    @Synchronized
    fun forRelease(reference: RoomEpochReference, accountIdHash: String): ReleasedRoomEpoch? {
        prune()
        val entry = entries[reference.epochId] ?: return null
        if (entry.accountIdHash != accountIdHash || entry.reference.generation != reference.generation ||
            !MessageDigest.isEqual(entry.reference.audienceDigestSha256, reference.audienceDigestSha256)
        ) {
            return null
        }
        return entry.releasedCopy()
    }

    @Synchronized
    fun put(released: ReleasedRoomEpoch) {
        require(released.epochSecret.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        val now = clock.epochMillis()
        if (released.releaseUntilEpochMillis <= now) return
        entries.remove(released.reference.epochId)?.zero()
        entries[released.reference.epochId] = Entry(
            reference = released.reference.copy(audienceDigestSha256 = released.reference.audienceDigestSha256.copyOf()),
            roomIdSha256 = released.roomIdSha256.copyOf(),
            accountIdHash = released.accountIdHash,
            installationId = released.installationId,
            recipientKeyId = released.recipientKeyId,
            activeUntilEpochMillis = released.activeUntilEpochMillis,
            releaseUntilEpochMillis = released.releaseUntilEpochMillis,
            cacheUntilEpochMillis = minOf(released.releaseUntilEpochMillis, now + MAX_CACHE_MILLIS),
            secret = released.epochSecret.copyOf(),
        )
        while (entries.size > maximumEntries) {
            val eldest = entries.entries.iterator().next()
            eldest.value.zero()
            entries.remove(eldest.key)
        }
    }

    @Synchronized
    fun clear() {
        invalidationGeneration++
        entries.values.forEach { entry -> entry.zero() }
        entries.clear()
    }

    private fun prune() {
        val now = clock.epochMillis()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now >= entry.cacheUntilEpochMillis) {
                entry.zero()
                iterator.remove()
            }
        }
    }

    private fun Entry.releasedCopy() = ReleasedRoomEpoch(
        reference = reference.copy(audienceDigestSha256 = reference.audienceDigestSha256.copyOf()),
        roomIdSha256 = roomIdSha256.copyOf(),
        accountIdHash = accountIdHash,
        installationId = installationId,
        recipientKeyId = recipientKeyId,
        requestNonce = byteArrayOf(),
        activeUntilEpochMillis = activeUntilEpochMillis,
        releaseUntilEpochMillis = releaseUntilEpochMillis,
        serverTimeEpochMillis = clock.epochMillis(),
        epochSecret = secret.copyOf(),
    )

    private fun Entry.zero() {
        reference.audienceDigestSha256.fill(0)
        roomIdSha256.fill(0)
        secret.fill(0)
    }

    private companion object {
        const val MAX_CACHE_MILLIS = 5 * 60_000L
        const val ROOM_EPOCH_SEND_HEADROOM_MILLIS = 60_000L
    }
}

/** Coalesces concurrent online releases without caching replay-protected authorization proofs. */
private class OnlineKmsReleaseBatcher(
    private val kmsShareClient: KmsShareClient,
) {
    private data class Pending(
        val request: ReleaseKmsShareRequest,
        val generation: Long,
        val operationContext: LifecycleOperationContext?,
        val result: CompletableDeferred<ReleasedKmsShare>,
        val enqueuedAtNanos: Long = System.nanoTime(),
        val timelineOwner: ScheduledPending? = null,
        val transportPriority: KmsTransportPriority = KmsTransportPriority.BACKGROUND,
    )

    private data class ScheduledPending(
        val request: ReleaseKmsShareRequest,
        val intent: TimelineContentReleaseIntent,
        val generation: Long,
        val operationContext: LifecycleOperationContext?,
        val result: CompletableDeferred<ReleasedKmsShare>,
        val enqueuedAtNanos: Long = System.nanoTime(),
    )

    private data class TimelineViewportState(
        val generation: Long,
        val visibleRequestIds: Set<String>,
        val bootstrapRequestIds: Set<String>,
        val initialBootstrapRequestIds: Set<String>,
        val bootstrapRetired: Boolean,
    )

    private val generation = AtomicLong(0)
    private val pending = BoundedKmsTransportArbiter<Pending>()
    private val timelineScheduler = NextTimelineReleaseSchedulerPrototype<ScheduledPending>(
        NextTimelineReleaseSchedulerConfig(
            maximumPending = MAX_PENDING_RELEASES,
            maximumBatchSize = MAX_BATCH_RELEASES,
            foregroundWeight = 2,
            historyWeight = 1,
            reliableWeight = 1,
            foregroundReservedPending = MAX_SCHEDULED_IN_FLIGHT,
        )
    )
    private val foregroundSignals = Channel<Unit>(capacity = MAX_SCHEDULED_IN_FLIGHT - 2)
    private val reservedForegroundSignals = Channel<Unit>(capacity = 1)
    private val historySignals = Channel<Unit>(capacity = 1)
    private val timelineStateLock = Any()
    private val timelineOwnerTokenSequence = AtomicLong(0)
    private val activeTimelineScopeOwners = mutableMapOf<String, TimelineReleaseScopeOwnerToken>()
    private val timelineViewportStates = mutableMapOf<String, TimelineViewportState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transportDiagnosticSequence = AtomicLong()
    private fun Pending.transportEntry() = BoundedKmsTransportArbiter.Entry(
        operationContext?.let { it.operationId to it.lifecycleId } ?: LegacyKmsTransportOperation.valueOf(transportPriority.name),
        transportPriority,
        this,
    )

    init {
        repeat(MAX_SCHEDULED_IN_FLIGHT) {
            scope.launch {
                while (true) {
                    val batch = pending.nextBatch(BATCH_WINDOW_MILLIS)
                    try {
                        dispatchOperationBatch(batch.values)
                    } finally {
                        pending.complete(batch)
                    }
                }
            }
        }
        repeat(MAX_SCHEDULED_IN_FLIGHT) { worker ->
            scope.launch {
                val isHistoryWorker = worker == MAX_SCHEDULED_IN_FLIGHT - 1
                val signals = when {
                    isHistoryWorker -> historySignals
                    worker == 0 -> reservedForegroundSignals
                    else -> foregroundSignals
                }
                for (ignored in signals) {
                    while (true) {
                        val history = if (isHistoryWorker) {
                            timelineScheduler.takeBatch(
                            limit = 1,
                                allowedClasses = setOf(NextTimelineReleaseClass.HISTORY_PREFETCH),
                        ).singleOrNull()
                        } else {
                            null
                        }
                        // One permanent foreground worker never lends its slot to bootstrap.
                        val foregroundClasses = if (worker == 0 || isHistoryWorker) {
                            setOf(NextTimelineReleaseClass.FOREGROUND_VISIBLE)
                        } else {
                            setOf(NextTimelineReleaseClass.FOREGROUND_VISIBLE, NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)
                        }
                        val ticket = history ?: timelineScheduler.takeBatch(
                            limit = 1,
                            allowedClasses = foregroundClasses,
                        ).singleOrNull() ?: break
                        dispatchScheduled(ticket)
                    }
                }
            }
        }
    }

    suspend fun release(
        request: ReleaseKmsShareRequest,
        timelineIntent: TimelineContentReleaseIntent? = null,
    ): ReleasedKmsShare {
        // The caller clears its temporary context when cancelled. The independent transport owns these copies.
        val transportRequest = request.copy(
            immutableContextSha256 = request.immutableContextSha256.copyOf(),
            requestNonce = request.requestNonce.copyOf(),
        )
        val result = CompletableDeferred<ReleasedKmsShare>()
        val operationContext = currentLifecycleOperationContext()
        if (timelineIntent == null) {
            var transferred = false
            return try {
                val priority = currentCoroutineContext()[KmsTransportSchedulingContext]?.priority
                    ?: if (operationContext?.lifecycleId == FullAppLifecycleId.LC_13) {
                        KmsTransportPriority.CURRENT_MESSAGE
                    } else {
                        KmsTransportPriority.BACKGROUND
                    }
                pending.enqueue(Pending(transportRequest, generation.get(), operationContext, result, transportPriority = priority).transportEntry())
                transferred = true
                result.await()
            } catch (failure: CancellationException) {
                cancelResult(result)
                pending.removeQueued { it.result === result }.forEach { it.request.clearTransportBytes() }
                throw failure
            } finally {
                if (!transferred) transportRequest.clearTransportBytes()
            }
        }

        var ownershipTransferred = false
        try {
            val scheduledGeneration = synchronized(timelineStateLock) { generation.get() }
            val scheduledPending = ScheduledPending(transportRequest, timelineIntent, scheduledGeneration, operationContext, result)
            val viewportIdentity = NextTimelineViewportIdentity(
                scopeId = timelineIntent.scopeId,
                roomId = requireNotNull(timelineIntent.roomId) { "Timeline release intent is missing its Matrix room identity." },
                eventId = timelineIntent.eventId,
                contentVersion = request.immutableContextSha256.toHex(),
                generation = timelineIntent.generation,
            )
            val schedulerRequest = NextTimelineReleaseRequest(
                requestId = timelineIntent.requestId,
                deduplicationKey = NextTimelineReleaseDeduplicationKey(
                    requestIdentity = timelineRequestIdentity(timelineIntent),
                    contentIdentity = "${request.contentId.value}:${request.immutableContextSha256.toHex()}",
                    authorizationIdentity = listOf(
                        request.accountIdHash,
                        request.requestingInstallationId,
                        request.requestingKeyId,
                        request.policy.policyVersion.toString(),
                        request.bootCount.toString(),
                        request.burnSessionId.orEmpty(),
                        MessageDigest.getInstance("SHA-256").digest(request.requestNonce).toHex(),
                    ).joinToString(separator = ":"),
                ),
                releaseClass = when (timelineIntent.releaseClass) {
                    TimelineContentReleaseClass.FOREGROUND_VISIBLE -> NextTimelineReleaseClass.FOREGROUND_VISIBLE
                    TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP -> NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP
                    TimelineContentReleaseClass.HISTORY_PREFETCH -> NextTimelineReleaseClass.HISTORY_PREFETCH
                },
                payload = scheduledPending,
                viewportIdentity = viewportIdentity,
                allowBootstrap = timelineIntent.readPurpose == TimelineContentReadPurpose.TIMELINE,
            )
            val admission = synchronized(timelineStateLock) {
                val registeredOwner = activeTimelineScopeOwners[timelineIntent.scopeId]
                if (timelineIntent.scopeOwnerToken == null ||
                    registeredOwner != timelineIntent.scopeOwnerToken ||
                    scheduledGeneration != generation.get()
                ) {
                    null
                } else {
                    val viewportState = timelineViewportStates[timelineIntent.scopeId]
                        ?.takeIf { state -> state.generation >= timelineIntent.generation }
                    val effectiveIntent = viewportState?.let { state ->
                        timelineIntent.copy(
                            generation = state.generation,
                            releaseClass = if (timelineIntent.requestId in state.visibleRequestIds) {
                                TimelineContentReleaseClass.FOREGROUND_VISIBLE
                            } else if (timelineIntent.readPurpose == TimelineContentReadPurpose.TIMELINE &&
                                timelineIntent.requestId in state.bootstrapRequestIds
                            ) {
                                TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP
                            } else {
                                TimelineContentReleaseClass.HISTORY_PREFETCH
                            },
                        )
                    } ?: timelineIntent.copy(
                        releaseClass = timelineIntent.releaseClass.takeUnless { it == TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP }
                            ?: TimelineContentReleaseClass.HISTORY_PREFETCH,
                    )
                    timelineScheduler.enqueue(
                        schedulerRequest.copy(
                            deduplicationKey = schedulerRequest.deduplicationKey.copy(
                                requestIdentity = timelineRequestIdentity(effectiveIntent),
                            ),
                            releaseClass = when (effectiveIntent.releaseClass) {
                                TimelineContentReleaseClass.FOREGROUND_VISIBLE -> NextTimelineReleaseClass.FOREGROUND_VISIBLE
                    TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP -> NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP
                                TimelineContentReleaseClass.HISTORY_PREFETCH -> NextTimelineReleaseClass.HISTORY_PREFETCH
                            },
                            payload = scheduledPending.copy(intent = effectiveIntent),
                            viewportIdentity = schedulerRequest.viewportIdentity?.copy(generation = effectiveIntent.generation),
                        )
                    )
                }
            }
            when (admission) {
                null -> {
                    transportRequest.clearTransportBytes()
                    result.completeExceptionally(CancellationException("Timeline release scope is closed."))
                }
                NextTimelineReleaseAdmission.Enqueued -> {
                    ownershipTransferred = true
                    signalScheduledWorkers()
                }
                is NextTimelineReleaseAdmission.SkippedBackpressure,
                is NextTimelineReleaseAdmission.SkippedDuplicate -> {
                        transportRequest.clearTransportBytes()
                        result.completeExceptionally(ContentProtectionException.KmsUnavailable())
                    }
                is NextTimelineReleaseAdmission.RetryRequired ->
                    error("Timeline release work must never enter the reliable-security lane.")
            }
            return try {
                result.await()
            } catch (failure: CancellationException) {
                cancelResult(result)
                timelineIntent.scopeOwnerToken?.let { ownerToken ->
                    cancelTimelineRequests(timelineIntent.scopeId, setOf(timelineIntent.requestId), ownerToken)
                }
                throw failure
            }
        } finally {
            if (!ownershipTransferred) transportRequest.clearTransportBytes()
        }
    }

    fun cancelTimelineRequests(
        scopeId: String,
        requestIds: Set<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
    ) {
        if (requestIds.isEmpty()) return
        val cancellation = synchronized(timelineStateLock) {
            if (activeTimelineScopeOwners[scopeId] == ownerToken) {
                timelineViewportStates[scopeId]?.let { state ->
                    timelineViewportStates[scopeId] = state.copy(bootstrapRequestIds = state.bootstrapRequestIds - requestIds,
                        initialBootstrapRequestIds = state.initialBootstrapRequestIds - requestIds,
                        bootstrapRetired = state.bootstrapRetired ||
                            state.initialBootstrapRequestIds.isNotEmpty() && (state.initialBootstrapRequestIds - requestIds).isEmpty())
                }
                timelineScheduler.cancelViewportRequests(scopeId, requestIds)
            } else {
                null
            }
        }
        cancellation?.let(::completeTimelineCancellation)
        if (cancellation != null) signalScheduledWorkers()
    }

    fun updateTimelineViewport(
        scopeId: String,
        generation: Long,
        visibleRequestIds: List<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
        bootstrapRequestIds: List<String>,
    ): Boolean {
        val reprioritization = synchronized(timelineStateLock) {
            if (activeTimelineScopeOwners[scopeId] != ownerToken) return false
            val previous = timelineViewportStates[scopeId]
            if (previous != null && generation <= previous.generation) return false
            val initial = previous?.initialBootstrapRequestIds?.takeIf { it.isNotEmpty() }
                ?: bootstrapRequestIds.distinct().take(30).toSet()
            val wasActivated = previous?.initialBootstrapRequestIds?.isNotEmpty() == true
            val retired = previous?.bootstrapRetired == true || wasActivated && bootstrapRequestIds.isEmpty()
            val bootstrap = if (retired) emptySet() else bootstrapRequestIds.filter { it in initial }.toSet()
            timelineViewportStates[scopeId] = TimelineViewportState(generation, visibleRequestIds.toSet(), bootstrap, initial, retired)
            timelineScheduler.reprioritizeViewport(scopeId, generation, visibleRequestIds, bootstrap.toList())
        }
        reprioritization.removedHistory.forEach { request ->
            request.payload.request.clearTransportBytes()
            request.payload.result.completeExceptionally(ContentProtectionException.KmsUnavailable())
        }
        signalScheduledWorkers()
        return true
    }

    fun openTimelineScope(scopeId: String): TimelineReleaseScopeOwnerToken {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        val tokenValue = timelineOwnerTokenSequence.incrementAndGet()
        check(tokenValue > 0) { "Timeline release scope owner token exhausted." }
        val token = TimelineReleaseScopeOwnerToken(tokenValue)
        val cancellation = synchronized(timelineStateLock) {
            val previousOwner = activeTimelineScopeOwners.put(scopeId, token)
            timelineViewportStates.remove(scopeId)
            if (previousOwner == null) null else timelineScheduler.cancelViewportScope(scopeId)
        }
        cancellation?.let(::completeTimelineCancellation)
        if (cancellation != null) signalScheduledWorkers()
        return token
    }

    fun closeTimelineScope(scopeId: String, ownerToken: TimelineReleaseScopeOwnerToken) {
        val cancellation = synchronized(timelineStateLock) {
            if (activeTimelineScopeOwners[scopeId] != ownerToken) {
                null
            } else {
                activeTimelineScopeOwners.remove(scopeId)
                timelineViewportStates.remove(scopeId)
                timelineScheduler.cancelViewportScope(scopeId)
            }
        }
        cancellation?.let(::completeTimelineCancellation)
        if (cancellation != null) signalScheduledWorkers()
    }

    fun pendingTimelineAdmissionsForTest(releaseClass: TimelineContentReleaseClass): Int =
        timelineScheduler.pendingCount(
            when (releaseClass) {
                TimelineContentReleaseClass.FOREGROUND_VISIBLE -> NextTimelineReleaseClass.FOREGROUND_VISIBLE
                    TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP -> NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP
                TimelineContentReleaseClass.HISTORY_PREFETCH -> NextTimelineReleaseClass.HISTORY_PREFETCH
            }
        )

    fun clearPending() {
        val cancellations = synchronized(timelineStateLock) {
            generation.incrementAndGet()
            pending.removeQueued { true }.forEach { item ->
                item.request.clearTransportBytes()
                item.result.completeExceptionally(ContentProtectionException.AccessDenied())
            }
            val scopes = activeTimelineScopeOwners.keys.toList()
            activeTimelineScopeOwners.clear()
            timelineViewportStates.clear()
            scopes.map(timelineScheduler::cancelViewportScope)
        }
        cancellations.forEach(::completeTimelineCancellation)
        signalScheduledWorkers()
    }

    private suspend fun dispatchScheduled(ticket: NextTimelineReleaseDispatchTicket<ScheduledPending>) {
        val scheduled = ticket.request.payload
        // Exact original-operation timeline queue boundary; diagnostic only, no scheduling or authority change.
        LifecycleStageTiming.interval(
            "kms_timeline_queue_wait",
            scheduled.enqueuedAtNanos,
            System.nanoTime(),
            scheduled.request.contentId.value,
            ticket.request.releaseClass.name,
            scheduled.operationContext?.operationId.orEmpty(),
        )
        val transportResult = CompletableDeferred<ReleasedKmsShare>()
        try {
            Timber.d(
                "YZKMS_SCHEDULE scope=%s request=%s class=%s queue_ms=%d",
                scheduled.intent.scopeId,
                scheduled.intent.requestId,
                ticket.request.releaseClass,
                (System.nanoTime() - scheduled.enqueuedAtNanos) / 1_000_000L
            )
            val enqueueFailure = synchronized(timelineStateLock) {
                when {
                    scheduled.generation != generation.get() -> ContentProtectionException.AccessDenied()
                    !isCurrentTimelineOwner(scheduled) -> CancellationException("Timeline release owner is closed.")
                    !pending.tryEnqueue(
                        Pending(
                            scheduled.request,
                            scheduled.generation,
                            scheduled.operationContext,
                            transportResult,
                            timelineOwner = scheduled,
                            transportPriority = when (ticket.request.releaseClass) {
                                NextTimelineReleaseClass.FOREGROUND_VISIBLE -> KmsTransportPriority.CURRENT_MESSAGE
                                NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP -> KmsTransportPriority.FOREGROUND_BOOTSTRAP
                                else -> KmsTransportPriority.BACKGROUND
                            },
                        ).transportEntry()
                    ) -> ContentProtectionException.KmsUnavailable()
                    else -> null
                }
            }
            if (enqueueFailure != null) {
                scheduled.request.clearTransportBytes()
                throw enqueueFailure
            }
            val released = transportResult.await()
            when (
                val disposition = ticket.complete(
                    NextTimelineSensitiveResult(released) { stale -> stale.kmsShare.fill(0) },
                    ticket.request.viewportIdentity,
                )
            ) {
                is NextTimelineResultDisposition.Delivered -> {
                    if (!scheduled.result.complete(disposition.value)) {
                        disposition.value.kmsShare.fill(0)
                    }
                }
                NextTimelineResultDisposition.ClearedDuplicate,
                NextTimelineResultDisposition.ClearedStale ->
                    scheduled.result.completeExceptionally(CancellationException("Timeline release became stale."))
            }
        } catch (failure: CancellationException) {
            ticket.fail()
            scheduled.result.completeExceptionally(failure)
            // Transport code may report a request-level cancellation. Preserve this permanent worker unless its own
            // coroutine was cancelled.
            currentCoroutineContext().ensureActive()
        } catch (failure: Exception) {
            ticket.fail()
            scheduled.result.completeExceptionally(failure)
        } finally {
            signalScheduledWorkers()
        }
    }

    private fun completeTimelineCancellation(
        cancellation: io.element.android.security.contentprotection.impl.scheduling.NextTimelineGenerationCancellation<ScheduledPending>,
    ) {
        val failure = CancellationException("Timeline release was cancelled before projection.")
        cancellation.removedPending.forEach { request ->
            request.payload.request.clearTransportBytes()
            request.payload.result.completeExceptionally(failure)
        }
        cancellation.cancelledInFlight.forEach { ticket -> ticket.request.payload.result.completeExceptionally(failure) }
        pending.removeQueued { it.timelineOwner?.result?.isActive == false }.forEach { item ->
            item.request.clearTransportBytes()
            item.result.completeExceptionally(failure)
        }
    }

    private fun signalScheduledWorkers() {
        reservedForegroundSignals.trySend(Unit)
        repeat(MAX_SCHEDULED_IN_FLIGHT - 2) { foregroundSignals.trySend(Unit) }
        historySignals.trySend(Unit)
    }

    private fun timelineRequestIdentity(intent: TimelineContentReleaseIntent): String = listOf(
        intent.scopeId,
        intent.scopeOwnerToken?.value?.toString().orEmpty(),
        intent.requestId,
        intent.generation.toString(),
        intent.readPurpose.name,
    ).joinToString(separator = ":")

    private suspend fun dispatchOperationBatch(batch: List<Pending>) {
        val context = batch.first().operationContext
        val diagnosticId = "batch-${transportDiagnosticSequence.incrementAndGet()}"
        val admittedNs = System.nanoTime()
        LifecycleStageTiming.interval(
            "kms_arbiter_queue_wait",
            batch.minOf(Pending::enqueuedAtNanos),
            admittedNs,
            diagnosticId,
            "release_batch",
            context?.operationId.orEmpty(),
        )
        LifecycleStageTiming.observed("kms_arbiter_class", diagnosticId, batch.first().transportPriority.name, context?.operationId.orEmpty())
        try {
            releaseBatch(batch)
        } finally {
            LifecycleStageTiming.interval(
                "kms_batch_transport",
                admittedNs,
                System.nanoTime(),
                diagnosticId,
                "release_batch",
                context?.operationId.orEmpty(),
            )
        }
    }

    private suspend fun releaseBatch(batch: List<Pending>) {
        // Recheck after channel, global-slot and same-operation waits. Only disposable scoped reads
        // are dropped; legacy/reliable transports retain their original ownership and ordering.
        val currentBatch = synchronized(timelineStateLock) {
            batch.filter { item ->
                val owner = item.timelineOwner
                if (owner == null || isCurrentTimelineOwner(owner)) {
                    true
                } else {
                    item.request.clearTransportBytes()
                    item.result.completeExceptionally(CancellationException("Timeline release owner is closed."))
                    false
                }
            }
        }
        if (currentBatch.isEmpty()) return
        releaseCurrentBatch(currentBatch)
    }

    /** Called under timelineStateLock. Active HTTP retains bytes until its own finally block. */
    private fun isCurrentTimelineOwner(owner: ScheduledPending): Boolean =
        owner.result.isActive && owner.generation == generation.get() &&
            activeTimelineScopeOwners[owner.intent.scopeId] == owner.intent.scopeOwnerToken

    private suspend fun releaseCurrentBatch(batch: List<Pending>) {
        val startedAtNanos = System.nanoTime()
        val context = batch.first().operationContext
        Timber.d(
            "YZKMS_TRANSPORT phase=start operation=%s items=%d queue_ms=%d",
            context?.operationId.orEmpty(),
            batch.size,
            (startedAtNanos - batch.minOf(Pending::enqueuedAtNanos)) / 1_000_000L,
        )
        try {
            val operationContext = batch.first().operationContext
            val released = if (operationContext == null) {
                kmsShareClient.releaseShares(batch.map(Pending::request))
            } else {
                withContext(operationContext) {
                    kmsShareClient.releaseShares(batch.map(Pending::request))
                }
            }
            if (released.size != batch.size) {
                released.forEach { share -> share.kmsShare.fill(0) }
                throw ContentProtectionException.AuthenticationFailed()
            }
            batch.zip(released).forEach { (item, share) ->
                if (item.generation != generation.get() || !item.result.complete(share)) {
                    share.kmsShare.fill(0)
                    item.result.completeExceptionally(ContentProtectionException.AccessDenied())
                }
            }
        } catch (failure: Exception) {
            batch.forEach { item -> item.result.completeExceptionally(failure) }
        } finally {
            batch.forEach { it.request.clearTransportBytes() }
            Timber.d(
                "YZKMS_TRANSPORT phase=finish operation=%s items=%d transport_ms=%d",
                context?.operationId.orEmpty(),
                batch.size,
                (System.nanoTime() - startedAtNanos) / 1_000_000L,
            )
        }
    }

    private fun ReleaseKmsShareRequest.clearTransportBytes() {
        immutableContextSha256.fill(0)
        requestNonce.fill(0)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun cancelResult(result: CompletableDeferred<ReleasedKmsShare>) {
        result.cancel()
        // A result may have won completion just before prompt await cancellation.
        runCatching { result.getCompleted() }.getOrNull()?.kmsShare?.fill(0)
    }

    private companion object {
        const val BATCH_WINDOW_MILLIS = 16L
        const val MAX_BATCH_RELEASES = 32
        const val MAX_PENDING_RELEASES = 64
        const val MAX_SCHEDULED_IN_FLIGHT = 4
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * Keeps only Vault-wrapped CEKs in process memory. Every hit still passes policy, trust, Vault, audience and
 * tombstone checks in [DefaultContentProtectionService.releaseContentKey] before an unwrap is attempted.
 */
private class VaultProtectedContentKeyCache(
    private val vaultKeyService: VaultKeyService,
    private val clock: SystemClock,
    private val maximumEntries: Int = 128,
) {
    private data class Key(
        val vaultId: String,
        val accountIdHash: String,
        val roomId: String,
        val contentId: String,
        val ciphertextHash: String,
        val policyVersion: Long,
        val envelopeVersion: Int,
        val deviceKeyId: String,
        val immutableContextHash: String,
        val burnSessionId: String?,
    )

    private data class Entry(
        val wrappedKey: VaultWrappedKey,
        val binding: ByteArray,
        val expiresAtEpochMillis: Long,
    )

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    suspend fun get(
        request: ReleaseContentRequest,
        ciphertextHash: ByteArray,
        expectedContext: ByteArray,
        deviceKeyId: String,
    ): ByteArray? {
        val key = key(request, ciphertextHash, expectedContext, deviceKeyId)
        val entry = synchronized(entries) {
            purgeExpiredLocked()
            entries[key]
        } ?: return null
        return runCatching {
            vaultKeyService.unwrapKey(request.vaultId, entry.wrappedKey, entry.binding).also { contentKey ->
                if (contentKey.size != ContentProtectionFormat.CONTENT_KEY_SIZE_BYTES) {
                    contentKey.fill(0)
                    throw ContentProtectionException.AuthenticationFailed()
                }
            }
        }.getOrElse {
            synchronized(entries) { entries.remove(key)?.clear() }
            null
        }
    }

    suspend fun put(
        request: ReleaseContentRequest,
        ciphertextHash: ByteArray,
        expectedContext: ByteArray,
        deviceKeyId: String,
        contentKey: ByteArray,
        expiresAtEpochMillis: Long,
    ) {
        val key = key(request, ciphertextHash, expectedContext, deviceKeyId)
        val binding = binding(key)
        val wrapped = try {
            vaultKeyService.wrapKey(request.vaultId, VaultKeyDomain.CACHE, contentKey, binding)
        } catch (failure: Exception) {
            binding.fill(0)
            throw failure
        }
        synchronized(entries) {
            entries.remove(key)?.clear()
            entries[key] = Entry(wrapped, binding, expiresAtEpochMillis)
            while (entries.size > maximumEntries) {
                val eldest = entries.entries.first()
                entries.remove(eldest.key)?.clear()
            }
        }
    }

    fun remove(vaultId: AccountVaultId, contentId: ContentId) = synchronized(entries) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.vaultId == vaultId.value && entry.key.contentId == contentId.value) {
                iterator.remove()
                entry.value.clear()
            }
        }
    }

    fun clear() = synchronized(entries) {
        entries.values.forEach { entry -> entry.clear() }
        entries.clear()
    }

    private fun purgeExpiredLocked() {
        val now = clock.epochMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value.expiresAtEpochMillis) {
                iterator.remove()
                entry.value.clear()
            }
        }
    }

    private fun key(
        request: ReleaseContentRequest,
        ciphertextHash: ByteArray,
        expectedContext: ByteArray,
        deviceKeyId: String,
    ): Key = Key(
        vaultId = request.vaultId.value,
        accountIdHash = request.accountIdHash,
        roomId = request.roomId,
        contentId = request.header.contentId.value,
        ciphertextHash = ciphertextHash.toHex(),
        policyVersion = request.header.policy.policyVersion,
        envelopeVersion = request.header.formatVersion,
        deviceKeyId = deviceKeyId,
        immutableContextHash = expectedContext.toHex(),
        burnSessionId = request.burnSessionId,
    )

    private fun binding(key: Key): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        listOf(
            "yzcp-cek-cache-v1",
            key.vaultId,
            key.accountIdHash,
            key.roomId,
            key.contentId,
            key.ciphertextHash,
            key.policyVersion.toString(),
            key.envelopeVersion.toString(),
            key.deviceKeyId,
            key.immutableContextHash,
            key.burnSessionId.orEmpty(),
        ).joinToString(separator = "\u0000").encodeToByteArray()
    )

    private fun Entry.clear() {
        binding.fill(0)
        wrappedKey.initializationVector.fill(0)
        wrappedKey.ciphertext.fill(0)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
