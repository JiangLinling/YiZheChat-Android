/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the browsing projection; selection and upload retain the original source URI. */
internal class AttachmentPreviewHandle private constructor(
    val media: LocalMedia,
    private val lease: LocalAttachmentPreviewLease?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) lease?.close()
    }

    companion object {
        suspend fun open(source: LocalMedia, service: LocalAttachmentPreviewLeaseService): AttachmentPreviewHandle {
            val lease = if (service.requiresLease(source)) service.openForBrowse(source).getOrThrow() else null
            return AttachmentPreviewHandle(lease?.previewMedia ?: source, lease)
        }
    }
}
