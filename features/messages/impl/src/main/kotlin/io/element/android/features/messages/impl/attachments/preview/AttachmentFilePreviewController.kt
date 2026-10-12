/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

enum class AttachmentFilePreviewState { Preparing, Ready, Failed, Revoked, Closed }

/** One in-memory preparation generation. Never persisted or restored as an authorization. */
internal class AttachmentFilePreviewController(
    private val source: LocalMedia,
    private val service: LocalAttachmentPreviewLeaseService,
) {
    var state by mutableStateOf(AttachmentFilePreviewState.Preparing)
        private set
    var lease by mutableStateOf<LocalAttachmentPreviewLease?>(null)
        private set
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null || state != AttachmentFilePreviewState.Preparing) return
        job = scope.launch {
            var acquired: LocalAttachmentPreviewLease? = null
            try {
                acquired = service.open(source).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (state != AttachmentFilePreviewState.Preparing) return@launch
                val preview = acquired.previewMedia
                check(preview.lifecycleOperationId == source.lifecycleOperationId &&
                    preview.lifecycleRoomIdHash == source.lifecycleRoomIdHash && preview.info == source.info &&
                    !preview.filePreviewLeaseToken.isNullOrBlank()) { "The preview projection does not match its source." }
                // The renderer still validates token, carrier, subject binding and current lease authority.
                lease = acquired
                acquired = null
                state = AttachmentFilePreviewState.Ready
            } catch (cancelled: CancellationException) {
                if (state == AttachmentFilePreviewState.Preparing) state = AttachmentFilePreviewState.Closed
                throw cancelled
            } catch (failure: Exception) {
                if (state == AttachmentFilePreviewState.Preparing) {
                    state = if (failure is SecurityException) AttachmentFilePreviewState.Revoked else AttachmentFilePreviewState.Failed
                    Timber.w("LC-16 local attachment preview preparation failed; content remains blocked.")
                }
            } finally {
                // A non-cooperative or delayed issuer cannot revive a disposed/cancelled generation.
                acquired?.close()
            }
        }
    }

    fun cancelPreparation() {
        if (state == AttachmentFilePreviewState.Preparing) close()
    }

    fun close() {
        if (state == AttachmentFilePreviewState.Closed) return
        state = AttachmentFilePreviewState.Closed
        job?.cancel()
        lease?.close()
        lease = null
    }
}
