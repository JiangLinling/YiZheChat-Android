/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import java.io.File

class MediaUploadHandlerImpl(
    private val filesToUpload: List<File>,
    private val sendAttachmentJoinHandle: SendAttachmentJoinHandle,
    private val onFailure: suspend () -> Unit = {},
    private val verifyAcceptance: (suspend () -> Unit)? = null,
    private val onCancel: () -> Unit = {},
    override val protectedMediaTransportName: String? = null,
) : MediaUploadHandler {
    override suspend fun await(): Result<Unit> {
        val result = runCatchingExceptions {
            sendAttachmentJoinHandle.join()
            // join() also returns successfully for cancellation. Only Matrix event evidence
            // permits the protected draft to be checkpointed as accepted.
            verifyAcceptance?.invoke()
            Unit
        }
        if (result.isFailure) onFailure()
        cleanUpFiles()
        return result
    }

    override fun cancel() {
        sendAttachmentJoinHandle.cancel()
        onCancel()
        cleanUpFiles()
    }

    private fun cleanUpFiles() {
        filesToUpload.forEach { file -> file.safeDelete() }
    }
}
