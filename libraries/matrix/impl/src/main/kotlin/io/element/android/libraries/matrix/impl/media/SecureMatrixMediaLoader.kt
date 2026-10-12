/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.core.coroutines.SessionOwnedResourceWork
import io.element.android.security.dataprotection.api.withOperation

import android.net.Uri
import android.util.Log
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.KmsTransportPriority
import io.element.android.security.contentprotection.api.KmsTransportSchedulingContext
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import timber.log.Timber
import kotlin.time.Duration.Companion.hours
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.ContinuationInterceptor

class SecureMatrixMediaLoader(
    private val delegate: MatrixMediaLoader,
    private val vaultId: AccountVaultId,
    private val sensitiveFileStore: SensitiveFileStore,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val sessionCoroutineScope: CoroutineScope,
    @Suppress("unused") private val clock: SystemClock,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val protectedMediaService: MatrixProtectedMediaService = DisabledMatrixProtectedMediaService,
    private val accountIdHash: String,
    private val matrixDeviceIdHash: String,
    private val requireInnerProtection: () -> Boolean = { false },
    private val downloadDispatcher: CoroutineDispatcher? = null,
    private val cleanupRetryScope: CoroutineScope? = null,
    private val progressiveVideoEnabled: suspend () -> Boolean = { false },
    private val progressiveVideoAcquirer: (suspend (MediaSource, Long) -> MediaFile)? = null,
) : MatrixMediaLoader {
    private val downloadOwners = SessionOwnedResourceWork(sessionCoroutineScope)
    private val cleanupReporter = TransientMediaCleanupReporter(sensitiveFileStore, appOperationalStateCoordinator)
    private val cleanupWakeup = Channel<Unit>(Channel.CONFLATED)
    init {
        (cleanupRetryScope ?: sessionCoroutineScope).launch {
            do {
                runCatchingExceptions { cleanupReporter.recoverAndReport() }
                    .onFailure { Timber.w("Transient media object recovery remains pending.") }
                if (cleanupRetryScope == null) break
                withTimeoutOrNull(30_000) { cleanupWakeup.receive() }
            } while (isActive)
        }
    }
    override suspend fun openProgressiveVideo(source: MediaSource, wireLength: Long?): Result<MediaFile?> {
        if (!progressiveVideoEnabled()) return Result.success(null)
        if (source.json == null || source.eventOrigin?.eventId == null || wireLength == null) return Result.success(null)
        return withAuthorizedDownload(source, "download") {
            requireNotNull(progressiveVideoAcquirer) { "Progressive video acquisition is unavailable." }(source, wireLength)
        }.onFailure { failure ->
            Timber.tag("YiZheProgressiveVideo").w("open_failure type=%s", failure.javaClass.simpleName)
        }
    }

    override suspend fun loadMediaContent(source: MediaSource, cachePolicy: MediaCachePolicy): Result<ByteArray> =
        withAuthorizedDownload(source, "download") {
            decryptResult(delegate.loadMediaContent(source, cachePolicy)).getOrThrow()
        }

    override suspend fun loadMediaThumbnail(
        source: MediaSource,
        width: Long,
        height: Long,
        cachePolicy: MediaCachePolicy,
    ): Result<ByteArray> = withAuthorizedDownload(source, "download") {
        decryptResult(delegate.loadMediaThumbnail(source, width, height, cachePolicy)).getOrThrow()
    }

    override suspend fun loadPublicProfileThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> =
        delegate.loadMediaThumbnail(source, width, height, MediaCachePolicy.REUSABLE)

    override suspend fun downloadMediaFile(
        source: MediaSource,
        mimeType: String?,
        filename: String?,
        useCache: Boolean,
    ): Result<MediaFile> = withAuthorizedDownload(source, "download") {
        downloadMediaFileAuthorized(source, mimeType, filename, useCache).getOrThrow()
    }

    private suspend fun downloadMediaFileAuthorized(
        source: MediaSource,
        mimeType: String?,
        filename: String?,
        useCache: Boolean,
    ): Result<MediaFile> = delegate.loadMediaContent(
        source,
        if (useCache) MediaCachePolicy.REUSABLE else MediaCachePolicy.EPHEMERAL,
    ).fold(
        onSuccess = { downloaded ->
            runCatchingExceptions {
                try {
                    val purpose = purposeFor(mimeType)
                    val protected = protectedMediaService.isProtected(downloaded)
                    Log.i(DIAGNOSTIC_TAG, "stage=outer_download result=success protected_frame=$protected bytes=${downloaded.size}")
                    if (requireInnerProtection() && !protected) {
                        downloaded.fill(0)
                        throw SecurityException("Matrix media did not contain a YiZhe protected-media frame.")
                    }
                    if (protected) {
                        downloadAuthorizedProtectedFile(source, downloaded, purpose, mimeType, filename)
                    } else {
                        downloadLegacyEncryptedFile(source, downloaded, purpose, mimeType, filename)
                    }
                } finally {
                    // Includes adapter/inspection failures before staging acquires resource ownership.
                    downloaded.fill(0)
                }
            }
        },
        onFailure = {
            Log.w(DIAGNOSTIC_TAG, "stage=outer_download result=failure code=${it.stableDiagnosticCode()}")
            Result.failure(it)
        },
    )

    private suspend fun downloadAuthorizedProtectedFile(
        source: MediaSource,
        downloaded: ByteArray,
        purpose: SecureObjectPurpose,
        mimeType: String?,
        filename: String?,
    ): MediaFile {
        val authorityService = protectedMediaService as? MatrixProtectedMediaAuthorityService
            ?: throw SecurityException("Protected media authority adapter is unavailable.")
        val stagingStore = secureMediaLeaseManager as? SecureObjectStagingStore
            ?: throw SecurityException("Secure encrypted staging is unavailable.")
        val authorizedLeaseManager = secureMediaLeaseManager as? AuthorizedSecureMediaLeaseManager
            ?: throw SecurityException("Authorized secure media leases are unavailable.")
        val inspection = authorityService.inspect(downloaded)
        val locatorDigest = source.fingerprint()
        val objectId = SecureObjectId("matrix-$locatorDigest-${UUID.randomUUID()}")
        val descriptor = SecureMediaLogicalResourceDescriptor(
            assetId = locatorDigest,
            mediaVersion = inspection.policyVersion,
            roomIdSha256Hex = inspection.roomIdSha256Hex,
            contentContractId = inspection.policyId,
            resourceId = locatorDigest,
            contentId = inspection.contentId,
            kind = inspection.kind,
            index = 0,
            expectedPlaintextLength = inspection.expectedPlaintextLength,
            immutableContextSha256Hex = inspection.immutableContextSha256Hex,
            matrixLocatorSha256Hex = locatorDigest,
            outerFrameSha256Hex = sha256Hex(downloaded),
            outerFrameLength = downloaded.size.toLong(),
            vaultId = vaultId,
            objectId = objectId,
            purpose = purpose,
        )
        var authority: SecureMediaReleaseAuthority? = null
        var staging: SecureObjectStagingTransaction? = null
        val acquisitionStartedAt = System.nanoTime()
        try {
            staging = stagingStore.stage(
                SecureObjectWriteRequest(
                    objectId = objectId,
                    vaultId = vaultId,
                    purpose = purpose,
                    associatedData = descriptor.canonicalBytes(),
                    expectedPlaintextLength = descriptor.expectedPlaintextLength,
                    transientMediaOwner = originalMediaOwner(),
                )
            ) { encryptedStagingOutput ->
                authority = authorityService.decryptToEncryptedStaging(
                    downloaded,
                    vaultId,
                    accountIdHash,
                    encryptedStagingOutput,
                )
            }
            val lease = authorizedLeaseManager.issueAuthorized(
                SecureMediaAuthorizedLeaseRequest(
                    staging = staging,
                    logicalResource = descriptor,
                    releaseAuthority = requireNotNull(authority),
                    mimeType = mimeType,
                    displayName = filename,
                    timeToLive = MEDIA_LEASE_TTL,
                    requireSeekableDescriptor = requiresSeekable(mimeType, filename),
                )
            )
            Timber.d("Protected media lease ready: contentId=%s elapsedMs=%s", inspection.contentId, (System.nanoTime() - acquisitionStartedAt) / 1_000_000)
            authority = null
            staging = null
            return SecureMediaFile(lease) {}
        } catch (failure: Throwable) {
            try { authority?.close() } catch (closeFailure: Throwable) {
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            val transaction = staging
            if (transaction != null) {
                val cleanup = withContext(NonCancellable) { transaction.abort() }
                if (!cleanup.complete) throw SecureMediaCleanupException(cleanup, failure)
            }
            Log.w(DIAGNOSTIC_TAG, "stage=authorized_binding result=failure code=${failure.stableDiagnosticCode()}")
            throw failure
        } finally {
            downloaded.fill(0)
        }
    }

    private suspend fun downloadLegacyEncryptedFile(
        source: MediaSource,
        downloaded: ByteArray,
        purpose: SecureObjectPurpose,
        mimeType: String?,
        filename: String?,
    ): MediaFile {
        val reference = try {
            sensitiveFileStore.write(
                SecureObjectWriteRequest(
                    SecureObjectId("matrix-${source.fingerprint()}-${UUID.randomUUID()}"),
                    vaultId,
                    purpose,
                    associatedData(mimeType, filename),
                    downloaded.size.toLong(),
                    transientMediaOwner = originalMediaOwner(),
                )
            ) { it.write(downloaded) }
        } finally {
            downloaded.fill(0)
        }
        val lease = try {
            secureMediaLeaseManager.issue(
                SecureMediaLeaseRequest(
                    reference,
                    mimeType,
                    filename,
                    MEDIA_LEASE_TTL,
                    requireSeekableDescriptor = requiresSeekable(mimeType, filename),
                )
            )
        } catch (failure: Throwable) {
            sensitiveFileStore.delete(reference)
            throw failure
        }
        return SecureMediaFile(lease) { sessionCoroutineScope.launch { sensitiveFileStore.delete(reference) } }
    }

    private suspend fun <T : Any> withAuthorizedDownload(
        source: MediaSource,
        trigger: String,
        block: suspend () -> T,
    ): Result<T> = runCatchingExceptions {
        if (sensitiveFileStore.recoverTransientMediaObjects().isNotEmpty()) cleanupWakeup.trySend(Unit)
        val priority = if (currentCoroutineContext()[KmsTransportSchedulingContext]?.priority == KmsTransportPriority.USER_MEDIA) {
            KmsTransportPriority.USER_MEDIA
        } else {
            KmsTransportPriority.BACKGROUND
        }
        val parentOperation = currentLifecycleOperationContext()
        val dispatcher = downloadDispatcher ?: currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO
        val request = lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_16,
                trigger = trigger,
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                roomIdHash = source.eventOrigin?.roomId?.let { sha256Hex(it.encodeToByteArray()) },
                eventIdHash = source.eventOrigin?.eventId?.let { sha256Hex(it.encodeToByteArray()) },
            )
        val startedAt = System.nanoTime()
        var actualOperationId: String? = null
        Timber.d("Protected media download requested: requestedId=%s", request.operationId)
        downloadOwners.execute<T>(
            userInitiated = priority == KmsTransportPriority.USER_MEDIA,
            context = (parentOperation ?: EmptyCoroutineContext) + KmsTransportSchedulingContext(priority) + dispatcher,
            discard = ::discardDownload,
            isDiscardVerified = { value ->
                when (value) {
                    is ByteArray -> value.all { it == 0.toByte() }
                    is io.element.android.libraries.matrix.api.media.ProgressiveMediaFile -> value.isReadQuiescent
                    else -> false
                }
            },
            onCleanupFailure = {
                Timber.tag("YiZheSecureMedia").w("YZMEDIA_OWNER cleanup=PENDING role=%s", priority.name)
            },
        ) { retainUntilReceipt ->
                val acquire: suspend (String) -> T = { operationId ->
                    actualOperationId = operationId
                    Timber.d("Protected media download admitted: operationId=%s elapsedMs=%s", operationId, (System.nanoTime() - startedAt) / 1_000_000)
                    try {
                        block().also {
                            retainUntilReceipt(it)
                            Timber.d("Protected media download acquired: operationId=%s elapsedMs=%s", operationId, (System.nanoTime() - startedAt) / 1_000_000)
                        }
                    } catch (failure: ContentProtectionException.RoomEpochMaterialUnavailable) {
                        // decrypt's finally has erased the downloaded buffer and staging abort has completed.
                        // A failed abort throws SecureMediaCleanupException instead and cannot reach this fact.
                        withContext(NonCancellable) {
                            appOperationalStateCoordinator.enqueueClientReceipts(listOf(
                                io.element.android.security.dataprotection.api.LifecycleClientReceipt(
                                    operationId, io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE,
                                    io.element.android.security.dataprotection.api.LifecyclePhase.CLIENT_APPLIED,
                                    "MEDIA_DOWNLOAD_KEY_UNAVAILABLE_AND_CLEANED",
                                    "android-media-download-failure:v1:$operationId", System.currentTimeMillis(),
                                )
                            ))
                        }
                        throw failure
                    }
                }
                if (parentOperation?.lifecycleId == FullAppLifecycleId.LC_16) {
                    // The outer action still owns factory/preview completion and its client receipts.
                    appOperationalStateCoordinator.withOperation(request, acquire)
                } else {
                    appOperationalStateCoordinator.perform(request, acquire)
                }
        }.also {
            if (parentOperation?.lifecycleId != FullAppLifecycleId.LC_16) {
                Timber.d("Protected media download client_receipts_submitted: operationId=%s elapsedMs=%s", actualOperationId, (System.nanoTime() - startedAt) / 1_000_000)
            }
        }
    }.onFailure {
        Log.w(DIAGNOSTIC_TAG, "stage=download_authorization result=failure code=${it.stableDiagnosticCode()}")
    }

    private suspend fun originalMediaOwner(): SecureTransientMediaOwner {
        val context = requireNotNull(currentLifecycleOperationContext())
        require(context.lifecycleId == FullAppLifecycleId.LC_16)
        val operation = appOperationalStateCoordinator.operations.value[context.operationId]
            ?: appOperationalStateCoordinator.operation(context.operationId)
        require(operation.operationId == context.operationId && operation.lifecycleId == FullAppLifecycleId.LC_16 &&
            operation.subjects.accountIdHash == accountIdHash && operation.subjects.matrixDeviceIdHash == matrixDeviceIdHash)
        return SecureTransientMediaOwner(operation.operationId, accountIdHash, matrixDeviceIdHash,
            operation.subjects.installationId, operation.subjects.roomIdHash, operation.subjects.eventIdHash)
    }

    private fun discardDownload(value: Any) {
        when (value) {
            is ByteArray -> value.fill(0)
            is MediaFile -> value.close()
            else -> error("Unsupported protected media result.")
        }
    }

    private suspend fun decryptToMemoryIfProtected(downloaded: ByteArray): ByteArray {
        if (!protectedMediaService.isProtected(downloaded)) {
            if (requireInnerProtection()) {
                downloaded.fill(0)
                throw SecurityException("Matrix media did not contain a YiZhe protected-media frame.")
            }
            return downloaded
        }
        return try {
            ByteArrayOutputStream().use { plaintext ->
                protectedMediaService.decrypt(downloaded, vaultId, accountIdHash, plaintext)
                plaintext.toByteArray()
            }
        } finally {
            downloaded.fill(0)
        }
    }

    private suspend fun decryptResult(result: Result<ByteArray>): Result<ByteArray> = result.fold(
        onSuccess = { runCatchingExceptions { decryptToMemoryIfProtected(it) } },
        onFailure = { Result.failure(it) },
    )

    private fun purposeFor(mimeType: String?): SecureObjectPurpose = when {
        mimeType?.startsWith("image/") == true -> SecureObjectPurpose.IMAGE
        mimeType?.startsWith("video/") == true -> SecureObjectPurpose.VIDEO
        mimeType?.startsWith("audio/") == true -> SecureObjectPurpose.VOICE_MESSAGE
        else -> SecureObjectPurpose.ATTACHMENT
    }

    private fun requiresSeekable(mimeType: String?, filename: String?): Boolean =
        mimeType?.startsWith("audio/", true) == true || mimeType?.startsWith("video/", true) == true ||
            mimeType == MimeTypes.Pdf || filename?.substringAfterLast('.', "")?.equals("pdf", true) == true

    private fun associatedData(mimeType: String?, filename: String?): ByteArray =
        "matrix-media-v1\u0000${mimeType ?: MimeTypes.OctetStream}\u0000${filename.orEmpty()}".encodeToByteArray()

    private fun MediaSource.fingerprint(): String = sha256Hex(
        (safeUrl + "\u0000" + json.orEmpty() + "\u0000" + eventOrigin?.roomId.orEmpty() + "\u0000" + eventOrigin?.eventId.orEmpty())
            .encodeToByteArray()
    )
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIAGNOSTIC_TAG = "YiZheSecureMedia"
        val MEDIA_LEASE_TTL = 1.hours
    }
}

private fun Throwable.stableDiagnosticCode(): String = when (this) {
    is io.element.android.security.contentprotection.api.StageThreeGateDenied -> "STAGE_THREE_${reason.name}"
    is ContentProtectionException.InvalidEnvelope -> "INVALID_ENVELOPE"
    is ContentProtectionException.DeviceKeyUnavailable -> "DEVICE_KEY_UNAVAILABLE"
    is ContentProtectionException.KmsUnavailable -> "KMS_UNAVAILABLE"
    is ContentProtectionException.LifecycleContractRefreshRequired -> "LIFECYCLE_CONTRACT_REFRESH_REQUIRED"
    is ContentProtectionException.AccessDenied -> "ACCESS_DENIED"
    is ContentProtectionException.RecoveryApprovalRequired -> "RECOVERY_APPROVAL_REQUIRED"
    is ContentProtectionException.RoomEpochMaterialUnavailable -> "ROOM_EPOCH_MATERIAL_UNAVAILABLE"
    is ContentProtectionException.Expired -> "EXPIRED"
    is ContentProtectionException.Revoked -> "REVOKED"
    is ContentProtectionException.OfflineLicenseUnavailable -> "OFFLINE_LICENSE_UNAVAILABLE"
    is ContentProtectionException.ClockRollbackDetected -> "CLOCK_ROLLBACK_DETECTED"
    is ContentProtectionException.ReplayRejected -> "REPLAY_REJECTED"
    is ContentProtectionException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
    is SecurityException -> "SECURITY_EXCEPTION"
    is java.io.IOException -> "IO_EXCEPTION"
    is IllegalArgumentException -> "INVALID_ARGUMENT"
    is IllegalStateException -> "INVALID_STATE"
    else -> "UNEXPECTED_${javaClass.simpleName.take(48)}"
}

private class SecureMediaFile(
    private val lease: SecureMediaLease,
    private val onClose: () -> Unit,
) : MediaFile {
    override fun path(): String = error("Secure media has no plaintext filesystem path.")
    override fun uri(): Uri = lease.uri
    override fun uriString(): String = lease.uri.toString()
    override suspend fun maintainReadAuthorization(): Boolean = lease.maintainReadAuthorization()

    override fun openInputStream(): InputStream = lease.openInputStream()
    override fun persist(path: String): Boolean = false
    override fun close() {
        lease.close()
        onClose()
    }
}
