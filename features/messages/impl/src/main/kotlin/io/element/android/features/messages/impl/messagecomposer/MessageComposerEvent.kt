/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.textcomposer.mentions.ResolvedSuggestion
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.Suggestion
import io.element.android.features.messages.impl.commoninfo.CommonInfoItem

sealed interface MessageComposerEvent {
    data object ToggleFullScreenState : MessageComposerEvent
    data object SendMessage : MessageComposerEvent
    data class SendEmotion(val id: String) : MessageComposerEvent
    data class SendCommonInfo(val item: CommonInfoItem, val onComplete: (Result<Unit>) -> Unit) : MessageComposerEvent
    data class InsertCommonInfo(val item: CommonInfoItem, val onComplete: (Result<Unit>) -> Unit) : MessageComposerEvent
    data object SendFlashReadMessage : MessageComposerEvent
    data object ToggleBurnAfterRead : MessageComposerEvent
    data class SendUri(val uri: Uri) : MessageComposerEvent
    data object CloseSpecialMode : MessageComposerEvent
    data class SetMode(val composerMode: MessageComposerMode) : MessageComposerEvent
    /** Volatile summary from the current authorized room projection; never part of persisted draft data. */
    data class RestoreReplyProjection(
        val expectedDetails: InReplyToDetails.Ready,
        val textContent: String,
        val hideImage: Boolean,
    ) : MessageComposerEvent

    /** Drops a volatile reply summary after its current authorized projection disappears. */
    data class InvalidateReplyProjection(val expectedDetails: InReplyToDetails.Ready) : MessageComposerEvent

    /** Restores a withdrawn message from volatile RAM only; it must never enter persistent draft storage. */
    data class RestoreWithdrawnText(val text: String) : MessageComposerEvent
    data object ClearWithdrawnText : MessageComposerEvent
    data object AddAttachment : MessageComposerEvent
    data class SelectRecentImage(val uri: Uri) : MessageComposerEvent
    data class LoadMoreRecentImages(val after: Uri) : MessageComposerEvent
    data object DismissRecentImages : MessageComposerEvent
    data class RecentImageUnavailable(val uri: Uri) : MessageComposerEvent
    data object PasteFromInAppClipboard : MessageComposerEvent
    data object StartRelay : MessageComposerEvent
    data object DismissRelay : MessageComposerEvent
    data class CreateRelay(val title: String, val initialEntry: String, val onComplete: (Result<Unit>) -> Unit = {}) : MessageComposerEvent
    data class AppendRelay(val relayEventId: EventId, val entry: String, val onComplete: (Result<Unit>) -> Unit = {}) : MessageComposerEvent
    data class EditRelayEntry(val relayEventId: EventId, val itemId: String, val expectedRevision: Long,
        val entry: String, val onComplete: (Result<Unit>) -> Unit = {}, val operationId: String = java.util.UUID.randomUUID().toString()) : MessageComposerEvent
    data class LoadRelayEditRecovery(val relayEventId: EventId,
        val onComplete: (Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?>) -> Unit) : MessageComposerEvent
    data class CloseRelay(val relayEventId: EventId, val onComplete: (Result<Unit>) -> Unit = {}) : MessageComposerEvent
    data object DismissCameraPermissionRationale : MessageComposerEvent
    data object OpenCameraPermissionSettings : MessageComposerEvent
    data object DismissAttachmentMenu : MessageComposerEvent
    sealed interface PickAttachmentSource : MessageComposerEvent {
        data object FromGallery : PickAttachmentSource
        data object FromGalleryAsSticker : PickAttachmentSource
        data object FromFiles : PickAttachmentSource
        data object PhotoFromCamera : PickAttachmentSource
        data object VideoFromCamera : PickAttachmentSource
        data object Location : PickAttachmentSource
        data object Poll : PickAttachmentSource
    }

    data class ToggleTextFormatting(val enabled: Boolean) : MessageComposerEvent
    data class Error(val error: Throwable) : MessageComposerEvent
    data class TypingNotice(val isTyping: Boolean) : MessageComposerEvent
    data class SuggestionReceived(val suggestion: Suggestion?) : MessageComposerEvent
    data class InsertSuggestion(val resolvedSuggestion: ResolvedSuggestion) : MessageComposerEvent
    data object SaveDraft : MessageComposerEvent
    data object ClearSlashError : MessageComposerEvent
}
