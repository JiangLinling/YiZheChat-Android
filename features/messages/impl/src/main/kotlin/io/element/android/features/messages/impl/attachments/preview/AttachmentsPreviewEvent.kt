/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.ImageAnnotation
import io.element.android.features.messages.impl.attachments.preview.imageeditor.NormalizedCropRect
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset

sealed interface AttachmentsPreviewEvent {
    data class UpdateGallerySelection(val items: List<Attachment.Media>, val originalPhotos: Boolean, val send: Boolean) : AttachmentsPreviewEvent
    data class FocusAttachment(val index: Int) : AttachmentsPreviewEvent
    data class ToggleAttachment(val index: Int) : AttachmentsPreviewEvent
    data class SetOriginalPhotos(val value: Boolean) : AttachmentsPreviewEvent
    data class SetVideoQuality(val preset: VideoCompressionPreset) : AttachmentsPreviewEvent
    data object OpenVideoEditor : AttachmentsPreviewEvent
    data object CloseVideoEditor : AttachmentsPreviewEvent
    data class ApplyVideoEdits(val startMs: Long, val endMs: Long?, val mute: Boolean) : AttachmentsPreviewEvent
    data class AddImageAnnotation(val annotation: ImageAnnotation) : AttachmentsPreviewEvent
    data class ReplaceImageAnnotations(val annotations: List<ImageAnnotation>) : AttachmentsPreviewEvent
    data object UndoImageEdit : AttachmentsPreviewEvent
    data object RedoImageEdit : AttachmentsPreviewEvent
    data object ConfirmSubmission : AttachmentsPreviewEvent
    data object ShowRecoveryDetails : AttachmentsPreviewEvent
    data object DismissRecoveryDetails : AttachmentsPreviewEvent
    data object RetryAttachment : AttachmentsPreviewEvent
    data object SendAttachment : AttachmentsPreviewEvent
    data object SendFlashReadAttachment : AttachmentsPreviewEvent
    data object DismissPreservingDraft : AttachmentsPreviewEvent
    data object CancelAndDismiss : AttachmentsPreviewEvent
    data object CancelAndClearSendState : AttachmentsPreviewEvent
    data object OpenImageEditor : AttachmentsPreviewEvent
    data object CloseImageEditor : AttachmentsPreviewEvent
    data object RotateImageToTheLeft : AttachmentsPreviewEvent
    data object FlipImageHorizontally : AttachmentsPreviewEvent
    data object FlipImageVertically : AttachmentsPreviewEvent
    data object ApplyImageEdits : AttachmentsPreviewEvent
    data object ResetImageEdits : AttachmentsPreviewEvent
    data class UpdateImageCropRect(val cropRect: NormalizedCropRect) : AttachmentsPreviewEvent
    data object ClearImageEditError : AttachmentsPreviewEvent
}
