/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.api.local

/** Opens a process-bound, encrypted preview capability for a locally selected Office or PDF file. */
interface LocalAttachmentPreviewLeaseService {
    fun requiresLease(source: LocalMedia): Boolean

    suspend fun open(source: LocalMedia): Result<LocalAttachmentPreviewLease>

    /** Browsing never creates an upload intent or transfers its owner to upload. */
    suspend fun openForBrowse(source: LocalMedia): Result<LocalAttachmentPreviewLease> =
        Result.failure(UnsupportedOperationException("Local browsing preview is unavailable."))
}

interface LocalAttachmentPreviewLease : AutoCloseable {
    /** Preview-only projection. Upload callers must retain and revalidate the original picker URI. */
    val previewMedia: LocalMedia

    /** Revokes preview access, deletes owned staging, then atomically transfers the same LC-16 owner. */
    suspend fun transferToUpload(): Result<Unit>

    /** Completes only local owner ordering after the existing upload lifecycle receipts succeeded. */
    suspend fun completeUpload(): Result<Unit>

    suspend fun cancel(reason: LocalAttachmentPreviewCancelReason): Result<LocalAttachmentPreviewCleanup>

    /** Waitable counterpart to [close]; it never reports scheduled deletion as completed deletion. */
    suspend fun closeAndAwait(): Result<LocalAttachmentPreviewCleanup>

    /** Immediately revokes preview readability and schedules the remaining owned cleanup. */
    override fun close()
}

enum class LocalAttachmentPreviewCancelReason {
    USER_CANCELLED,
    ATTACHMENT_REMOVED,
    ATTACHMENT_REPLACED,
    SOURCE_UNAVAILABLE,
    PREVIEW_FAILED,
    UPLOAD_FAILED,
    SESSION_EXPIRED,
}

data class LocalAttachmentPreviewCleanup(
    val readAccessRevoked: Boolean,
    val encryptedObjectDeleted: Boolean,
    val ownerReleased: Boolean,
) {
    val isComplete: Boolean
        get() = readAccessRevoked && encryptedObjectDeleted && ownerReleased
}

/** Unknown facts remain null, including a write or issuance that failed without returning ownership. */
data class LocalAttachmentPreviewOpenCleanup(
    val readAccessRevoked: Boolean?,
    val encryptedObjectDeleted: Boolean?,
    val ownerReleased: Boolean?,
) {
    val isComplete: Boolean get() = readAccessRevoked == true && encryptedObjectDeleted == true && ownerReleased == true
}

/** A failed open's local cleanup projection; queued facts do not assert a signed global terminal state. */
class LocalAttachmentPreviewOpenException(
    val operationId: String,
    val cleanup: LocalAttachmentPreviewOpenCleanup,
    val cleanupFactsQueued: Boolean,
    cause: Throwable,
) : IllegalStateException("Local preview failed; its cleanup status is retained for recovery.", cause) {
    val cleanupPending: Boolean get() = !cleanup.isComplete || !cleanupFactsQueued
}
