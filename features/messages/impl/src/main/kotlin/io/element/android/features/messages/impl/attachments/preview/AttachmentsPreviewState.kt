/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Immutable
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.textcomposer.model.TextEditorState

data class AttachmentsPreviewState(
    val attachment: Attachment,
    val attachmentCount: Int = 1,
    val filePreviewStates: Map<Int, AttachmentFilePreviewState> = emptyMap(),
    val imageEditorState: AttachmentImageEditorState?,
    val canEditImage: Boolean,
    val isApplyingImageEdits: Boolean,
    val displayImageEditError: Boolean,
    val sendActionState: SendActionState,
    val textEditorState: TextEditorState,
    val mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    val displayFileTooLargeError: Boolean,
    val isBurnAfterRead: Boolean,
    val displaySendProgressInline: Boolean = false,
    val eventSink: (AttachmentsPreviewEvent) -> Unit,
    val attachments: List<Attachment> = listOf(attachment),
    val focusedIndex: Int = 0,
    val selectedIndices: List<Int> = listOf(0),
    val originalPhotos: Boolean = false,
    val videoQuality: io.element.android.libraries.preferences.api.store.VideoCompressionPreset = io.element.android.libraries.preferences.api.store.VideoCompressionPreset.STANDARD,
    val videoEditorOpen: Boolean = false,
    val draftReady: Boolean = true,
    val draftError: Boolean = false,
    val sourceAttachments: List<Attachment> = attachments,
    val uncertainIndices: List<Int> = emptyList(),
    val failedIndices: List<Int> = emptyList(),
    val confirmationStillPending: Boolean = false,
    val blockedIndices: List<Int> = emptyList(),
    val acceptedIndices: List<Int> = emptyList(),
    val cancellationRequestedIndices: List<Int> = emptyList(),
    val discardedIndices: List<Int> = emptyList(),
    val recoveryDetailsVisible: Boolean = false,
    val checkingRecovery: Boolean = false,
    val recoveryChecks: List<MediaRecoveryItemCheck> = emptyList(),
)

@Immutable
sealed interface SendActionState {
    data object Idle : SendActionState

    @Immutable
    sealed interface Sending : SendActionState {
        data class Processing(val displayProgress: Boolean) : Sending
        data class ReadyToUpload(val mediaInfo: MediaUploadInfo) : Sending
        data class Uploading(val mediaUploadInfo: MediaUploadInfo, val progress: Float? = null) : Sending
    }

    data class Failure(val error: Throwable, val mediaUploadInfo: MediaUploadInfo?) : SendActionState
    data object Done : SendActionState

    fun mediaUploadInfo(): MediaUploadInfo? = when (this) {
        is Sending.ReadyToUpload -> mediaInfo
        is Sending.Uploading -> mediaUploadInfo
        is Failure -> mediaUploadInfo
        else -> null
    }
}
