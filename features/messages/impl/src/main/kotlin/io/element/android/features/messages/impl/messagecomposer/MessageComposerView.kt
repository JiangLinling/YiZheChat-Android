/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerStateProvider
import io.element.android.features.messages.api.timeline.voicemessages.composer.aVoiceMessageComposerState
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.components.receipt.MessageSendProgressIndicator
import io.element.android.features.messages.impl.commoninfo.CommonInfoPanel
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.textcomposer.TextComposer
import io.element.android.libraries.textcomposer.model.Suggestion
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.contentprotection.api.ContentProtectionException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun MessageComposerView(
    state: MessageComposerState,
    voiceMessageState: VoiceMessageComposerState,
    modifier: Modifier = Modifier,
    commonEditor: (@Composable (Modifier, () -> Unit) -> Unit)? = null,
    commonEditorId: String? = null,
) {
    if (state.showCameraPermissionRationaleDialog) {
        ConfirmationDialog(
            content = stringResource(R.string.screen_room_camera_permission_rationale),
            submitText = stringResource(R.string.screen_room_camera_permission_settings),
            cancelText = stringResource(CommonStrings.action_cancel),
            onSubmitClick = { state.eventSink(MessageComposerEvent.OpenCameraPermissionSettings) },
            onDismiss = { state.eventSink(MessageComposerEvent.DismissCameraPermissionRationale) },
        )
    }
    var commonPanelEditing by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) state.eventSink(MessageComposerEvent.DismissAttachmentMenu)
    }
    LaunchedEffect(state.commonInfoRepository, lifecycleState) {
        val repository = state.commonInfoRepository ?: return@LaunchedEffect
        if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            repository.startSync()
            try { withContext(Dispatchers.IO) { repository.loadForDisplay() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Opening the panel offers its own bounded retry. */ }
        } else repository.clearViewCache()
    }
    fun sendMessage() {
        state.eventSink(MessageComposerEvent.SendMessage)
    }

    fun sendFlashReadMessage() {
        if (voiceMessageState.voiceMessageState is VoiceMessageState.Preview) {
            voiceMessageState.eventSink(VoiceMessageComposerEvent.SendBurnAfterReadVoiceMessage)
            state.eventSink(MessageComposerEvent.CloseSpecialMode)
        } else {
            state.eventSink(MessageComposerEvent.SendFlashReadMessage)
        }
    }

    fun sendUri(uri: Uri) {
        if (!state.isBurnAfterReadEnabled) {
            state.eventSink(MessageComposerEvent.SendUri(uri))
        }
    }

    fun onAddAttachment() {
        state.eventSink(MessageComposerEvent.AddAttachment)
    }

    fun onCloseSpecialMode() {
        state.eventSink(MessageComposerEvent.CloseSpecialMode)
    }

    fun onDismissTextFormatting() {
        view.clearFocus()
        state.eventSink(MessageComposerEvent.ToggleTextFormatting(enabled = false))
    }

    fun onSuggestionReceived(suggestion: Suggestion?) {
        state.eventSink(MessageComposerEvent.SuggestionReceived(suggestion))
    }

    fun onError(error: Throwable) {
        state.eventSink(MessageComposerEvent.Error(error))
    }

    fun onTyping(typing: Boolean) {
        state.eventSink(MessageComposerEvent.TypingNotice(typing))
    }

    val coroutineScope = rememberCoroutineScope()
    fun onRequestFocus() {
        coroutineScope.launch {
            state.textEditorState.requestFocus()
        }
    }

    val onVoiceRecorderEvent = { press: VoiceMessageRecorderEvent ->
        voiceMessageState.eventSink(VoiceMessageComposerEvent.RecorderEvent(press))
    }

    val onSendVoiceMessage = {
        voiceMessageState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
        state.eventSink(MessageComposerEvent.CloseSpecialMode)
    }

    val onDeleteVoiceMessage = {
        voiceMessageState.eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)
    }

    val onVoicePlayerEvent = { event: VoiceMessagePlayerEvent ->
        voiceMessageState.eventSink(VoiceMessageComposerEvent.PlayerEvent(event))
    }

    Column(modifier = modifier) {
        if (state.showAttachmentSourcePicker) RecentImagesRow(state.recentImages, state.eventSink)
        if (state.isFlashReadPreparing) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageSendProgressIndicator(encrypting = true)
                Text(
                    text = stringResource(R.string.screen_room_flash_read_preparing),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        }
        TextComposer(
            state = state.textEditorState,
            voiceMessageState = voiceMessageState.voiceMessageState,
            voiceSendFailed = voiceMessageState.showSendFailureDialog,
            onRequestFocus = ::onRequestFocus,
            onSendMessage = ::sendMessage,
            composerMode = state.mode,
            showTextFormatting = state.showTextFormatting,
            onResetComposerMode = ::onCloseSpecialMode,
            onAddAttachment = ::onAddAttachment,
            onDismissTextFormatting = ::onDismissTextFormatting,
            onVoiceRecorderEvent = onVoiceRecorderEvent,
            onVoicePlayerEvent = onVoicePlayerEvent,
            onSendVoiceMessage = onSendVoiceMessage,
            onDeleteVoiceMessage = onDeleteVoiceMessage,
            onReceiveSuggestion = ::onSuggestionReceived,
            resolveMentionDisplay = state.resolveMentionDisplay,
            resolveAtRoomMentionDisplay = state.resolveAtRoomMentionDisplay,
            onError = ::onError,
            onTyping = ::onTyping,
            onSelectRichContent = ::sendUri,
            onSendFlashReadMessage = ::sendFlashReadMessage,
            flashReadLabel = stringResource(R.string.screen_room_burn_after_read_mode),
            flashReadSlideHint = stringResource(R.string.screen_room_flash_read_slide_hint),
            flashReadReleaseHint = stringResource(R.string.screen_room_flash_read_release_hint),
            // Keep received stickers renderable, but hide the low-value sticker sending entry.
            emotionPanel = { panel ->
                io.element.android.features.messages.impl.emotions.EmotionPicker(
                    canSend = state.mode is io.element.android.libraries.textcomposer.model.MessageComposerMode.Normal && !state.isBurnAfterReadEnabled,
                    onSend = { state.eventSink(MessageComposerEvent.SendEmotion(it.id)) },
                    emojis = panel.emojis,
                    onEmojiSelected = panel.onSelect,
                    onBackspace = panel.onBackspace,
                    onOpenCommon = panel.onOpenCommon,
                    commonLabel = panel.commonLabel,
                )
            },
            onPickSticker = null,
            stickerLabel = stringResource(R.string.screen_room_attachment_source_sticker),
            commonEditorId = commonEditorId,
            commonPanelEditing = commonEditorId != null || commonPanelEditing,
            commonPanel = commonEditor?.let { editor -> { panelModifier, close, _ -> editor(panelModifier, close) } }
                ?: state.commonInfoRepository?.let { repository ->
                { panelModifier, close, openKeyboard ->
                    CommonInfoPanel(repository, onDismiss = close, embedded = true, modifier = panelModifier,
                        onEditorChanged = { commonPanelEditing = it },
                        onOpenComposerKeyboard = openKeyboard,
                        onInsert = { item, complete -> state.eventSink(MessageComposerEvent.InsertCommonInfo(item, complete)) },
                        onSend = { item, complete -> state.eventSink(MessageComposerEvent.SendCommonInfo(item, complete)) })
                }
            },
            commonLabel = stringResource(R.string.screen_common_title),
            onDismissAttachmentPanel = {
                state.eventSink(MessageComposerEvent.DismissAttachmentMenu)
            },
        )
    }

    if (state.showRelayDialog) {
        RelayEditorDialog(
            isCreating = true,
            onSubmit = { title, entry, complete -> state.eventSink(MessageComposerEvent.CreateRelay(title, entry, complete)) },
            onDismiss = { state.eventSink(MessageComposerEvent.DismissRelay) },
        )
    }

    AsyncActionView(
        async = state.slashCommandAction,
        onSuccess = {},
        onErrorDismiss = { state.eventSink(MessageComposerEvent.ClearSlashError) },
        errorMessage = { error ->
            if (error is io.element.android.security.dataprotection.api.RelayEditException) {
                stringResource(relayEditErrorResource(error))
            } else if (error is ContentProtectionException.RoomEpochConflict) {
                stringResource(R.string.error_room_epoch_conflict)
            } else {
                stringResource(io.element.android.features.messages.impl.attachments.preview.error.protectedSendErrorResource(error))
            }
        },
    )
}

@PreviewsDayNight
@Composable
internal fun MessageComposerViewPreview(
    @PreviewParameter(MessageComposerStateProvider::class) state: MessageComposerState,
) = ElementPreview {
    Column {
        MessageComposerView(
            modifier = Modifier.height(IntrinsicSize.Min),
            state = state,
            voiceMessageState = aVoiceMessageComposerState(),
        )
        MessageComposerView(
            modifier = Modifier.height(200.dp),
            state = state,
            voiceMessageState = aVoiceMessageComposerState(),
        )
        DisabledComposerView()
    }
}

@PreviewsDayNight
@Composable
internal fun MessageComposerViewVoicePreview(
    @PreviewParameter(VoiceMessageComposerStateProvider::class) state: VoiceMessageComposerState,
) = ElementPreview {
    Column {
        MessageComposerView(
            modifier = Modifier.height(IntrinsicSize.Min),
            state = aMessageComposerState(),
            voiceMessageState = state,
        )
    }
}
