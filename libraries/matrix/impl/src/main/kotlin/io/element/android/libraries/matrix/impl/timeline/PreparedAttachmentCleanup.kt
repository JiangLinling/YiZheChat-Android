/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retains only this preparation's unfinished revocations; this is not a queue-absence receipt. */
internal class PreparedAttachmentCleanup(
    media: List<ProtectedMatrixMedia>,
    caption: PreparedProtectedContent?,
    private val adapter: MatrixProtectedContentAdapter,
) {
    private val mutation = Mutex()
    private val pendingMedia = media.distinctBy { it.contentId }.toMutableList()
    private var pendingCaption = caption

    suspend fun release(): Unit = mutation.withLock {
        var firstFailure: Exception? = null
        fun retainFailure(failure: Exception) {
            if (firstFailure == null) firstFailure = failure
            else if (firstFailure !== failure) firstFailure?.addSuppressed(failure)
        }
        // An unavailable media share must not prevent revoking another share or its title.
        pendingMedia.toList().forEach { media ->
            try {
                check(adapter.destroyMedia(media, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                    "Prepared media revocation remains pending."
                }
                pendingMedia.remove(media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retainFailure(failure)
            }
        }
        pendingCaption?.let { caption ->
            try {
                check(adapter.destroyPrepared(caption, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                    "Prepared caption revocation remains pending."
                }
                pendingCaption = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retainFailure(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}
