/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.lease

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewResourceLimits
import io.element.android.libraries.localofficeviewer.api.FilePreviewVerificationAuthority
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCancelReason
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCleanup
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLimitException
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewOpenCleanup
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewOpenException
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.libraries.mediaviewer.impl.local.requiresFilePreviewLease
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAcquireResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAuthority
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCapability
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerMutationResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultLocalAttachmentPreviewLeaseService(
    @ApplicationContext private val context: Context,
    private val matrixClient: MatrixClient,
    private val coordinator: AppOperationalStateCoordinator,
    private val ownerAuthority: LifecycleOperationOwnerAuthority,
    private val vaultKeyService: VaultKeyService,
    private val sensitiveFileStore: SensitiveFileStore,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) : LocalAttachmentPreviewLeaseService {
    override fun requiresLease(source: LocalMedia): Boolean = source.info.requiresFilePreviewLease()

    override suspend fun open(source: LocalMedia): Result<LocalAttachmentPreviewLease> = openInternal(source, browsing = false)

    override suspend fun openForBrowse(source: LocalMedia): Result<LocalAttachmentPreviewLease> = openInternal(source, browsing = true)

    private suspend fun openInternal(source: LocalMedia, browsing: Boolean): Result<LocalAttachmentPreviewLease> {
        if (!requiresLease(source) || source.filePreviewLeaseToken != null) {
            return Result.failure(IllegalArgumentException("A fresh Office or PDF source is required."))
        }
        var owner: LifecycleOperationOwnerCapability? = null
        var reference: SecureObjectReference? = null
        var secureLease: SecureMediaLease? = null
        var admittedBrowseOperationId: String? = null
        var registeredToken: String? = null
        var writeStarted = false
        var issuanceStarted = false
        var sourceClosed = true
        return try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { FilePreviewLeaseRegistry.retryPendingReleases() }
            validateInputSize(source)
            val accountId = matrixClient.sessionId.value
            val deviceId = matrixClient.deviceId.value
            val accountHash = sha256Hex(accountId)
            val deviceHash = sha256Hex(deviceId)
            val vaultId = deriveAccountVaultId(accountId, deviceId)
            check(vaultKeyService.isUnlocked(vaultId)) { "The account vault is locked." }
            val operation = coordinator.start(
                lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_16,
                    // Send confirmation keeps its upload intent. A list preview owns only local resources.
                    trigger = if (browsing) "local_preview" else "upload",
                    operationId = source.lifecycleOperationId,
                    accountIdHash = accountHash,
                    matrixDeviceIdHash = deviceHash,
                    roomIdHash = source.lifecycleRoomIdHash,
                    deadlineEpochMillis = System.currentTimeMillis() +
                        (if (browsing) BROWSE_PREVIEW_TTL else SEND_PREVIEW_OPERATION_TTL).inWholeMilliseconds,
                )
            )
            check(operation.lifecycleId == FullAppLifecycleId.LC_16)
            check(operation.operationId == source.lifecycleOperationId)
            check(operation.status in NON_TERMINAL_RESUMABLE_STATUSES)
            check(operation.deadlineEpochMillis > System.currentTimeMillis())
            check(operation.subjects.accountIdHash == accountHash)
            check(operation.subjects.matrixDeviceIdHash == deviceHash)
            check(operation.subjects.roomIdHash == source.lifecycleRoomIdHash)
            if (browsing) {
                check(
                    operation.steps.filter { it.required }.map { it.component }.toSet() ==
                    setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
                ) {
                    "The local-preview authority has an incompatible component set."
                }
                admittedBrowseOperationId = operation.operationId
            }
            owner = when (val acquired = ownerAuthority.acquireOwner(
                operationId = operation.operationId,
                role = LifecycleOperationOwnerRole.PREVIEW,
                commandId = "file-preview-acquire-${UUID.randomUUID()}",
            )) {
                is LifecycleOperationOwnerAcquireResult.Granted -> acquired.capability
                is LifecycleOperationOwnerAcquireResult.Rejected ->
                    error("Preview ownership rejected: ${acquired.reason.name}")
            }
            val staged = stageSource(
                source,
                vaultId.value,
                operation.operationId,
                onWriteStarted = { writeStarted = true },
                onReference = { reference = it },
                onSourceOpened = { sourceClosed = false },
                onSourceClosed = { sourceClosed = it },
            )
            val ttl = localPreviewTtl(operation.deadlineEpochMillis, browsing)
            issuanceStarted = true
            val issuedLease = secureMediaLeaseManager.issue(
                SecureMediaLeaseRequest(
                    reference = staged.reference,
                    mimeType = source.info.mimeType,
                    displayName = source.info.filename,
                    timeToLive = ttl,
                    revocationScope = "file-preview:${operation.operationId}:${staged.attachmentFingerprint}",
                    requireSeekableDescriptor = source.info.mimeType == MimeTypes.Pdf,
                    deleteObjectOnRevoke = false,
                )
            )
            secureLease = issuedLease
            val previewMediaWithoutToken = source.copy(uri = issuedLease.uri)
            val closure = if (browsing) {
                LocalPreviewClosureTracker {
                CoroutineScope(sessionCoroutineScope.coroutineContext.minusKey(Job)).launch {
                    enqueueLocalCleanupFacts(operation.operationId)
                }
            }
            } else {
                null
            }
            val token = FilePreviewLeaseRegistry.register(
                binding = previewMediaWithoutToken.filePreviewLeaseBinding(),
                mediaFile = SecureLeaseMediaFile(issuedLease),
                verificationAuthority = FilePreviewVerificationAuthority.LOCAL_SECURE_FILE_STORE,
                runtimeFactory = closure?.let { it::beginRuntime },
                onRelease = closure?.let { it::hostReleased },
            )
            registeredToken = token
            DefaultLocalAttachmentPreviewLease(
                previewMedia = previewMediaWithoutToken.copy(filePreviewLeaseToken = token),
                token = token,
                reference = staged.reference,
                initialOwner = requireNotNull(owner),
                ownerAuthority = ownerAuthority,
                sensitiveFileStore = sensitiveFileStore,
                sessionCoroutineScope = sessionCoroutineScope,
                ttl = ttl,
                closure = closure,
            ).also {
                owner = null
                reference = null
                secureLease = null
                registeredToken = null
            }.let(Result.Companion::success)
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                val cleanup = cleanupFailedOpen(owner, reference, secureLease, registeredToken, writeStarted, issuanceStarted, sourceClosed)
                val queued = admittedBrowseOperationId?.let { cleanup.isComplete && enqueueLocalCleanupFacts(it) } == true
                Timber.w("LC-16 cancelled preview open cleanupPending=%s factsQueued=%s", !cleanup.isComplete || !queued, queued)
            }
            throw failure
        } catch (failure: Exception) {
            val projection = withContext(NonCancellable) {
                val cleanup = cleanupFailedOpen(owner, reference, secureLease, registeredToken, writeStarted, issuanceStarted, sourceClosed)
                admittedBrowseOperationId?.let { operationId ->
                    val queued = cleanup.isComplete && enqueueLocalCleanupFacts(operationId)
                    LocalAttachmentPreviewOpenException(operationId, cleanup, queued, failure)
                }
            }
            if (projection != null) {
                Timber.w("LC-16 failed preview open cleanupPending=%s factsQueued=%s", projection.cleanupPending, projection.cleanupFactsQueued)
            }
            Result.failure(projection ?: failure)
        }
    }

    private suspend fun stageSource(
        source: LocalMedia,
        vaultId: String,
        operationId: String,
        onWriteStarted: () -> Unit,
        onReference: (SecureObjectReference) -> Unit,
        onSourceOpened: () -> Unit,
        onSourceClosed: (Boolean) -> Unit,
    ): StagedSource {
        val expectedLength = requireNotNull(source.info.fileSize) { "A known source length is required." }
        val maxBytes = if (source.info.mimeType == MimeTypes.Pdf) MAX_PDF_BYTES else MAX_OFFICE_BYTES
        require(expectedLength > 0) { "The selected file is empty." }
        if (expectedLength > maxBytes) throw LocalAttachmentPreviewLimitException(maxBytes)
        val descriptor = context.contentResolver.openAssetFileDescriptor(source.uri, "r")
            ?: error("The selected file is unavailable.")
        onSourceOpened()
        var streamClosed = true
        return try {
            val asset = descriptor
            require(asset.length == expectedLength) { "The selected file length changed." }
            val attachmentFingerprint = attachmentFingerprint(source, expectedLength)
            val request = SecureObjectWriteRequest(
                objectId = SecureObjectId("file-preview-$operationId-${UUID.randomUUID()}"),
                vaultId = io.element.android.security.securestorage.api.AccountVaultId(vaultId),
                purpose = SecureObjectPurpose.DOCUMENT_PREVIEW,
                associatedData = associatedData(source, operationId, attachmentFingerprint, expectedLength),
                expectedPlaintextLength = expectedLength,
            )
            onWriteStarted()
            val reference = sensitiveFileStore.write(request) { output ->
                val input = asset.createInputStream()
                streamClosed = false
                try {
                    copyExactly(input, output, expectedLength)
                } finally {
                    val closed = runCatching { input.close() }
                    streamClosed = closed.isSuccess
                    closed.getOrThrow()
                }
            }
            check(reference.objectId == request.objectId && reference.vaultId == request.vaultId && reference.purpose == request.purpose) {
                "The staged reference did not match this preview's owned write."
            }
            onReference(reference)
            check(reference.plaintextLength == expectedLength) { "Encrypted preview staging length did not match the verified source." }
            StagedSource(reference, attachmentFingerprint)
        } finally {
            val closed = runCatching { descriptor.close() }
            onSourceClosed(streamClosed && closed.isSuccess)
            closed.getOrThrow()
        }
    }

    private fun validateInputSize(source: LocalMedia) {
        val expectedLength = requireNotNull(source.info.fileSize) { "A known source length is required." }
        require(expectedLength > 0) { "The selected file is empty." }
        val maxBytes = if (source.info.mimeType == MimeTypes.Pdf) MAX_PDF_BYTES else MAX_OFFICE_BYTES
        if (expectedLength > maxBytes) throw LocalAttachmentPreviewLimitException(maxBytes)
    }

    private suspend fun cleanupFailedOpen(
        owner: LifecycleOperationOwnerCapability?,
        reference: SecureObjectReference?,
        secureLease: SecureMediaLease?,
        registeredToken: String?,
        writeStarted: Boolean,
        issuanceStarted: Boolean,
        sourceClosed: Boolean,
    ): LocalAttachmentPreviewOpenCleanup {
        val leaseRevoked = when {
            registeredToken != null -> runCatching {
                val released = FilePreviewLeaseRegistry.release(registeredToken, FilePreviewLeaseReleaseReason.HOST_DISPOSED)
                released.leaseTokenMatched && released.activeStreamsClosed && released.hostHandleCloseCompleted
            }.getOrDefault(false)
            secureLease != null -> runCatching { secureLease.close() }.isSuccess
            issuanceStarted -> null
            else -> true
        }
        val readAccessRevoked = if (!sourceClosed) false else leaseRevoked
        val objectDeleted = when {
            reference != null -> runCatching { sensitiveFileStore.delete(reference) }.getOrDefault(false)
            writeStarted -> null
            else -> true
        }
        val ownerReleased = when {
            owner == null -> null
            readAccessRevoked != true || objectDeleted != true -> false
            else -> runCatching {
                ownerAuthority.cancelOwner(
                    capability = owner,
                    commandId = "file-preview-open-failed-${UUID.randomUUID()}",
                    reason = LifecycleOperationOwnerCancelReason.PREVIEW_FAILED,
                ) is LifecycleOperationOwnerMutationResult.Released
            }.getOrDefault(false)
        }
        return LocalAttachmentPreviewOpenCleanup(readAccessRevoked, objectDeleted, ownerReleased)
    }

    private suspend fun enqueueLocalCleanupFacts(operationId: String): Boolean = try {
        coordinator.enqueueClientReceipts(
            listOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE).map { component ->
                LifecycleClientReceipt(
                    operationId = operationId,
                    component = component,
                    phase = LifecyclePhase.CLIENT_APPLIED,
                    resultCode = "LOCAL_PREVIEW_RESOURCES_RELEASED",
                    receiptReference = "local-preview-cleanup:$operationId:${component.name}",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                )
            }
        )
        // Durable submission is not a verified terminal operation.
        Timber.d("LC-16 local preview cleanup facts queued")
        true
    } catch (failure: Exception) {
        Timber.w("LC-16 local preview cleanup facts could not be queued type=%s", failure.javaClass.simpleName)
        false
    }

    private data class StagedSource(
        val reference: SecureObjectReference,
        val attachmentFingerprint: String,
    )

    private companion object {
        const val MAX_OFFICE_BYTES = FilePreviewResourceLimits.OFFICE_INPUT_BYTES
        const val MAX_PDF_BYTES = FilePreviewResourceLimits.PDF_INPUT_BYTES
        val BROWSE_PREVIEW_TTL = 30.minutes
        val SEND_PREVIEW_OPERATION_TTL = 10.minutes
    }
}

internal fun localPreviewTtl(deadlineEpochMillis: Long, browsing: Boolean, nowEpochMillis: Long = System.currentTimeMillis()): Duration {
    val remaining = (deadlineEpochMillis - nowEpochMillis).milliseconds
    require(remaining.isPositive())
    // A longer office reading session must still end at the authority's actual signed deadline.
    return minOf(if (browsing) 30.minutes else 5.minutes, remaining)
}

internal class DefaultLocalAttachmentPreviewLease(
    override val previewMedia: LocalMedia,
    private val token: String,
    private val reference: SecureObjectReference,
    initialOwner: LifecycleOperationOwnerCapability,
    private val ownerAuthority: LifecycleOperationOwnerAuthority,
    private val sensitiveFileStore: SensitiveFileStore,
    private val sessionCoroutineScope: CoroutineScope,
    ttl: Duration,
    private val closure: LocalPreviewClosureTracker? = null,
) : LocalAttachmentPreviewLease {
    private val mutex = Mutex()
    private val accessRevoked = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)
    private val cleanup = CompletableDeferred<Result<LocalAttachmentPreviewCleanup>>()
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(sessionCoroutineScope.coroutineContext.minusKey(Job) + cleanupJob)
    private var owner: LifecycleOperationOwnerCapability? = initialOwner
    private var objectDeleted = false

    init {
        cleanup.invokeOnCompletion { cleanupJob.complete() }
        sessionCoroutineScope.launch {
            delay(ttl)
            cancelPreviewOwnerIfPresent(LocalAttachmentPreviewCancelReason.SESSION_EXPIRED)
        }
    }

    override suspend fun transferToUpload(): Result<Unit> {
        if (closure != null) return Result.failure(IllegalStateException("A browsing preview has no upload authority."))
        val result = runCatching {
            mutex.withLock {
                revokeReadAccess()
                deleteObject()
                val current = requireNotNull(owner) { "Preview ownership is unavailable." }
                check(current.descriptor.role == LifecycleOperationOwnerRole.PREVIEW)
                owner = when (val transferred = ownerAuthority.transferOwner(
                    capability = current,
                    toRole = LifecycleOperationOwnerRole.UPLOAD,
                    commandId = "file-preview-transfer-${UUID.randomUUID()}",
                )) {
                    is LifecycleOperationOwnerMutationResult.Transferred -> transferred.capability.also {
                        check(it.descriptor.role == LifecycleOperationOwnerRole.UPLOAD)
                    }
                    is LifecycleOperationOwnerMutationResult.Released -> error("Transfer released ownership.")
                    is LifecycleOperationOwnerMutationResult.Rejected ->
                        error("Preview transfer rejected: ${transferred.reason.name}")
                }
            }
        }
        if (result.isFailure) {
            cancelPreviewOwnerIfPresent(LocalAttachmentPreviewCancelReason.UPLOAD_FAILED)
        }
        return result
    }

    override suspend fun completeUpload(): Result<Unit> = runCatching {
        mutex.withLock {
            val current = requireNotNull(owner) { "Upload ownership is unavailable." }
            check(current.descriptor.role == LifecycleOperationOwnerRole.UPLOAD)
            when (val completed = ownerAuthority.completeOwner(
                capability = current,
                commandId = "file-preview-upload-complete-${UUID.randomUUID()}",
            )) {
                is LifecycleOperationOwnerMutationResult.Released -> owner = null
                is LifecycleOperationOwnerMutationResult.Transferred -> error("Completion transferred ownership.")
                is LifecycleOperationOwnerMutationResult.Rejected ->
                    error("Upload completion rejected: ${completed.reason.name}")
            }
            completeCleanup(ownerReleased = true)
        }
    }

    override suspend fun cancel(
        reason: LocalAttachmentPreviewCancelReason,
    ): Result<LocalAttachmentPreviewCleanup> = withContext(NonCancellable) {
        runCatching {
            mutex.withLock { cancelLocked(reason) }
        }
    }.also { result ->
        if (!cleanup.isCompleted) cleanup.complete(result)
    }

    override suspend fun closeAndAwait(): Result<LocalAttachmentPreviewCleanup> {
        close()
        return cleanup.await()
    }

    override fun close() {
        closure?.seal()
        revokeReadAccess()
        if (!cleanup.isCompleted && closeStarted.compareAndSet(false, true)) {
            cleanupScope.launch {
                val result = runCatching {
                    mutex.withLock {
                        if (owner?.descriptor?.role == LifecycleOperationOwnerRole.UPLOAD) {
                            null
                        } else {
                            cancelLocked(LocalAttachmentPreviewCancelReason.USER_CANCELLED)
                        }
                    }
                }
                completeCleanupResult(result)
            }
        }
    }

    private suspend fun cancelPreviewOwnerIfPresent(reason: LocalAttachmentPreviewCancelReason) {
        val result = withContext(NonCancellable) {
            runCatching {
                mutex.withLock {
                    if (owner?.descriptor?.role == LifecycleOperationOwnerRole.PREVIEW) {
                        cancelLocked(reason)
                    } else {
                        null
                    }
                }
            }
        }
        completeCleanupResult(result)
    }

    private fun completeCleanupResult(result: Result<LocalAttachmentPreviewCleanup?>) {
        result.fold(
            onSuccess = { completed ->
                if (completed != null && !cleanup.isCompleted) cleanup.complete(Result.success(completed))
            },
            onFailure = { failure ->
                if (!cleanup.isCompleted) cleanup.complete(Result.failure(failure))
            },
        )
    }

    private suspend fun cancelLocked(
        reason: LocalAttachmentPreviewCancelReason,
    ): LocalAttachmentPreviewCleanup {
        closure?.seal()
        revokeReadAccess(
            if (reason == LocalAttachmentPreviewCancelReason.SESSION_EXPIRED) {
                FilePreviewLeaseReleaseReason.SESSION_EXPIRED
            } else {
                FilePreviewLeaseReleaseReason.HOST_DISPOSED
            }
        )
        deleteObject()
        val current = owner
        if (current != null) {
            when (val cancelled = ownerAuthority.cancelOwner(
                capability = current,
                commandId = "file-preview-cancel-${UUID.randomUUID()}",
                reason = reason.toOwnerCancelReason(),
            )) {
                is LifecycleOperationOwnerMutationResult.Released -> owner = null
                is LifecycleOperationOwnerMutationResult.Transferred -> error("Cancellation transferred ownership.")
                is LifecycleOperationOwnerMutationResult.Rejected ->
                    error("Preview cancellation rejected: ${cancelled.reason.name}")
            }
        }
        return cleanupSnapshot(ownerReleased = owner == null).also { closure?.sourceReleased(it) }
    }

    private fun revokeReadAccess(reason: FilePreviewLeaseReleaseReason = FilePreviewLeaseReleaseReason.HOST_DISPOSED) {
        if (accessRevoked.compareAndSet(false, true)) {
            FilePreviewLeaseRegistry.release(token, reason)
        }
    }

    private suspend fun deleteObject() {
        if (!objectDeleted) {
            check(sensitiveFileStore.delete(reference)) { "Encrypted preview staging was not deleted." }
            objectDeleted = true
        }
    }

    private fun completeCleanup(ownerReleased: Boolean): LocalAttachmentPreviewCleanup = cleanupSnapshot(ownerReleased)
        .also { result ->
            if (!cleanup.isCompleted) cleanup.complete(Result.success(result))
        }

    private fun cleanupSnapshot(ownerReleased: Boolean): LocalAttachmentPreviewCleanup = LocalAttachmentPreviewCleanup(
        readAccessRevoked = accessRevoked.get(),
        encryptedObjectDeleted = objectDeleted,
        ownerReleased = ownerReleased,
    )
}

private class SecureLeaseMediaFile(private val lease: SecureMediaLease) : MediaFile {
    override fun path(): String = error("Secure preview media has no plaintext path.")
    override fun uri(): Uri = lease.uri
    override fun uriString(): String = lease.uri.toString()
    override suspend fun maintainReadAuthorization(): Boolean = lease.maintainReadAuthorization()

    override fun openInputStream(): InputStream = lease.openInputStream()
    override fun persist(path: String): Boolean = false
    override fun close() = lease.close()
}

private suspend fun copyExactly(input: InputStream, output: java.io.OutputStream, expectedLength: Long) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    try {
        while (copied < expectedLength) {
            val requested = minOf(buffer.size.toLong(), expectedLength - copied).toInt()
            val count = input.read(buffer, 0, requested)
            check(count > 0) { "The selected file ended before its declared length." }
            output.write(buffer, 0, count)
            copied += count
        }
        check(input.read() == -1) { "The selected file exceeded its declared length." }
    } finally {
        buffer.fill(0)
    }
}

private fun attachmentFingerprint(source: LocalMedia, expectedLength: Long): String = sha256Hex(
    buildString {
        append("local-attachment-preview-v1\u0000")
        append(source.uri)
        append('\u0000')
        append(source.info.mimeType)
        append('\u0000')
        append(source.info.filename)
        append('\u0000')
        append(expectedLength)
    }
)

private fun associatedData(
    source: LocalMedia,
    operationId: String,
    attachmentFingerprint: String,
    expectedLength: Long,
): ByteArray = buildString {
    append("local-file-preview-staging-v1\u0000LC-16\u0000")
    append(operationId)
    append('\u0000')
    append(source.lifecycleRoomIdHash.orEmpty())
    append('\u0000')
    append(attachmentFingerprint)
    append('\u0000')
    append(source.info.mimeType)
    append('\u0000')
    append(source.info.filename)
    append('\u0000')
    append(expectedLength)
}.encodeToByteArray()

private fun LocalAttachmentPreviewCancelReason.toOwnerCancelReason(): LifecycleOperationOwnerCancelReason = when (this) {
    LocalAttachmentPreviewCancelReason.USER_CANCELLED -> LifecycleOperationOwnerCancelReason.USER_CANCELLED
    LocalAttachmentPreviewCancelReason.ATTACHMENT_REMOVED -> LifecycleOperationOwnerCancelReason.ATTACHMENT_REMOVED
    LocalAttachmentPreviewCancelReason.ATTACHMENT_REPLACED -> LifecycleOperationOwnerCancelReason.ATTACHMENT_REPLACED
    LocalAttachmentPreviewCancelReason.SOURCE_UNAVAILABLE -> LifecycleOperationOwnerCancelReason.SOURCE_UNAVAILABLE
    LocalAttachmentPreviewCancelReason.PREVIEW_FAILED -> LifecycleOperationOwnerCancelReason.PREVIEW_FAILED
    LocalAttachmentPreviewCancelReason.UPLOAD_FAILED -> LifecycleOperationOwnerCancelReason.UPLOAD_FAILED
    LocalAttachmentPreviewCancelReason.SESSION_EXPIRED -> LifecycleOperationOwnerCancelReason.PREVIEW_FAILED
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
