/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.androidutils.ui.hideKeyboard
import io.element.android.libraries.androidutils.ui.showKeyboard
import io.element.android.libraries.designsystem.components.media.WaveFormSamples
import io.element.android.libraries.designsystem.preview.DAY_MODE_NAME
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.NIGHT_MODE_NAME
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconColorButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetailsProvider
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.textcomposer.components.LiveWaveformView
import io.element.android.libraries.textcomposer.components.SendButtonIcon
import io.element.android.libraries.textcomposer.components.TextFormatting
import io.element.android.libraries.textcomposer.components.VoiceMessageDeleteButtonIcon
import io.element.android.libraries.textcomposer.components.VoiceMessagePreview
import io.element.android.libraries.textcomposer.components.VoiceMessageRecorderButtonIcon
import io.element.android.libraries.textcomposer.components.VoiceMessageRecording
import io.element.android.libraries.textcomposer.components.markdown.MarkdownTextInput
import io.element.android.libraries.textcomposer.components.textInputRoundedCornerShape
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.Suggestion
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.textcomposer.model.aTextEditorStateRich
import io.element.android.libraries.textcomposer.model.showCaptionCompatibilityWarning
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.time.formatShort
import io.element.android.wysiwyg.compose.RichTextEditor
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.wysiwyg_composer.MenuAction
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * https://www.figma.com/design/G1xy0HDZKJf5TCRFmKb5d5/Compound-Android-Components?node-id=2012-39036
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextComposer(
    state: TextEditorState,
    voiceMessageState: VoiceMessageState,
    composerMode: MessageComposerMode,
    onRequestFocus: () -> Unit,
    onSendMessage: () -> Unit,
    onResetComposerMode: () -> Unit,
    onAddAttachment: () -> Unit,
    onDismissTextFormatting: () -> Unit,
    onVoiceRecorderEvent: (VoiceMessageRecorderEvent) -> Unit,
    onVoicePlayerEvent: (VoiceMessagePlayerEvent) -> Unit,
    onSendVoiceMessage: () -> Unit,
    onDeleteVoiceMessage: () -> Unit,
    onError: (Throwable) -> Unit,
    onTyping: (Boolean) -> Unit,
    onReceiveSuggestion: (Suggestion?) -> Unit,
    onSelectRichContent: ((Uri) -> Unit)?,
    resolveMentionDisplay: (text: String, url: String) -> TextDisplay,
    resolveAtRoomMentionDisplay: () -> TextDisplay,
    modifier: Modifier = Modifier,
    showTextFormatting: Boolean = false,
    onDismissAttachmentPanel: () -> Unit = {},
    onSendFlashReadMessage: (() -> Unit)? = null,
    flashReadLabel: String = "",
    flashReadSlideHint: String = "",
    flashReadReleaseHint: String = "",
    emotionPanel: (@Composable (EmojiPanelInput) -> Unit)? = null,
    onPickSticker: (() -> Unit)? = null,
    stickerLabel: String = "",
    onOpenCommon: (() -> Unit)? = null,
    commonLabel: String = "",
    commonPanel: (@Composable (Modifier, () -> Unit, () -> Unit) -> Unit)? = null,
    commonEditorId: String? = null,
    commonPanelEditing: Boolean = false,
    attachmentSendEnabled: Boolean = true,
    attachmentSendLabel: String? = null,
    voiceSendFailed: Boolean = false,
) {
    val markdown = when (state) {
        is TextEditorState.Markdown -> state.state.text.value()
        is TextEditorState.Rich -> state.richTextEditorState.messageMarkdown
    }
    var showCommonPanel by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val panelPreferences = remember(context) { context.getSharedPreferences("text_composer_panel_ui_v1", android.content.Context.MODE_PRIVATE) }
    val panelHeightKey = "height_${configuration.orientation}_${configuration.screenWidthDp}_${density.density}"
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var inputPanelHeight by remember(panelHeightKey) { mutableStateOf(panelPreferences.getFloat(panelHeightKey, EMOJI_PANEL_HEIGHT.value).dp) }
    LaunchedEffect(imeTargetBottom, navBottom) {
        val measured = with(density) { (imeTargetBottom - navBottom).coerceAtLeast(0).toDp() }
        if (measured > 150.dp && measured != inputPanelHeight) {
            inputPanelHeight = measured
            panelPreferences.edit().putFloat(panelHeightKey, measured.value).apply()
        }
    }
    var pendingKeyboardSwitch by remember { mutableStateOf(false) }
    var voiceInputMode by rememberSaveable { mutableStateOf(false) }
    var sendVoiceWhenReady by remember { mutableStateOf(false) }
    var releasedVoiceHidden by remember { mutableStateOf(false) }
    var voiceSendingObserved by remember { mutableStateOf(false) }
    val voiceSending = (voiceMessageState as? VoiceMessageState.Preview)?.isSending == true
    val voiceInputBlocked = releasedVoiceHidden || voiceSending
    val composerVoiceState = if (voiceInputBlocked) VoiceMessageState.Idle else voiceMessageState
    LaunchedEffect(voiceSending, voiceSendFailed) {
        if (voiceSending) voiceSendingObserved = true
        if (voiceSendFailed || voiceSendingObserved && !voiceSending) {
            releasedVoiceHidden = false
            voiceSendingObserved = false
        }
    }
    val emojiScope = rememberCoroutineScope()
    val composerView = LocalView.current
    val keyboardRequestGate = remember { KeyboardRequestGate() }
    val keyboardRequestJob = remember { AtomicReference<Job?>(null) }

    fun invalidateKeyboardRequest() {
        // Invalidate both the suspended focus pass and the later View.post callback. Cancelling
        // the active job also prevents a cooperative requestFocus() from resuming after the user
        // has already selected another composer mode.
        keyboardRequestGate.invalidate()
        keyboardRequestJob.getAndSet(null)?.cancel()
    }

    fun cancelKeyboardRequest() {
        pendingKeyboardSwitch = false
        invalidateKeyboardRequest()
    }

    DisposableEffect(keyboardRequestGate) {
        onDispose(::invalidateKeyboardRequest)
    }

    fun dismissComposerKeyboard(force: Boolean = false) {
        cancelKeyboardRequest()
        val focusedEditor = composerView.findFocus()?.takeIf { it.onCheckIsTextEditor() }
        if (focusedEditor != null) {
            // The editor is hosted in an AndroidView. One request on the actual focused view is
            // sufficient; also asking both Compose keyboard APIs and the root view makes the same
            // IME transition run through multiple focus/insets passes.
            focusedEditor.hideKeyboard()
            focusedEditor.clearFocus()
        } else if (force) {
            composerView.hideKeyboard()
        }
    }

    TextComposerBackHandler(
        showCommonPanel = showCommonPanel,
        showEmojiPanel = showEmojiPanel,
        isKeyboardVisible = imeBottom > navBottom,
        onCloseCommonPanel = { showCommonPanel = false },
        onCloseEmojiPanel = {
            cancelKeyboardRequest()
            showEmojiPanel = false
        },
        onHideKeyboard = { dismissComposerKeyboard(force = true) },
    )

    fun requestComposerKeyboard(
        allowWhileEmojiPanelVisible: Boolean = false,
        onKeyboardRequestSubmitted: () -> Unit = {},
        onKeyboardRequestFailed: () -> Unit = {},
    ) {
        keyboardRequestJob.getAndSet(null)?.cancel()
        val requestGeneration = keyboardRequestGate.begin()
        val requestJob = emojiScope.launch(start = CoroutineStart.LAZY) {
            // The request may have been invalidated before this lazily-started coroutine gets a
            // dispatcher turn. Do not let a stale request acquire editor focus.
            if (!keyboardRequestGate.isCurrent(requestGeneration)) return@launch
            state.requestFocus()
            // Rich editor focus acquisition may suspend. Re-check before scheduling any IME work
            // so opening emoji/voice/attachments while it is suspended wins deterministically.
            if (!keyboardRequestGate.isCurrent(requestGeneration)) return@launch
            // Rich editor focus is applied by its AndroidView update pass. Give that pass a small,
            // bounded number of frames, then keep the current surface if no real editor owns focus.
            // Every callback re-checks the generation so a fast emoji -> keyboard -> attachment
            // sequence cannot revive the IME after the attachment panel has won.
            fun postKeyboardRequest(attemptsRemaining: Int) {
                composerView.post {
                    if (!keyboardRequestGate.isCurrent(requestGeneration)) return@post
                    val focusedEditor = composerView.findFocus()?.takeIf { it.onCheckIsTextEditor() }
                    val surfaceAllowsKeyboard = !voiceInputMode && (!showEmojiPanel || allowWhileEmojiPanelVisible)
                    if (focusedEditor != null && state.hasFocus() && surfaceAllowsKeyboard) {
                        focusedEditor.showKeyboard()
                        // Only remove the emoji surface after showKeyboard has been submitted on
                        // the actual focused editor, never while focus is still in flight.
                        keyboardRequestGate.completeIfCurrent(requestGeneration, onKeyboardRequestSubmitted)
                    } else if (surfaceAllowsKeyboard && attemptsRemaining > 0) {
                        postKeyboardRequest(attemptsRemaining - 1)
                    } else {
                        keyboardRequestGate.completeIfCurrent(requestGeneration, onKeyboardRequestFailed)
                    }
                }
            }
            postKeyboardRequest(KEYBOARD_FOCUS_RETRY_COUNT)
        }
        keyboardRequestJob.set(requestJob)
        if (keyboardRequestGate.isCurrent(requestGeneration)) {
            requestJob.start()
        } else {
            keyboardRequestJob.compareAndSet(requestJob, null)
            requestJob.cancel()
        }
    }

    fun openCommonPanel() {
        if (commonPanel == null) { onOpenCommon?.invoke(); return }
        showCommonPanel = true
        showEmojiPanel = false
        onDismissAttachmentPanel()
        voiceInputMode = false
        dismissComposerKeyboard(force = true)
    }

    LaunchedEffect(commonEditorId) { if (commonEditorId != null) openCommonPanel() }

    val onPlayVoiceMessageClick = {
        onVoicePlayerEvent(VoiceMessagePlayerEvent.Play)
    }

    val onPauseVoiceMessageClick = {
        onVoicePlayerEvent(VoiceMessagePlayerEvent.Pause)
    }

    val onSeekVoiceMessage = { position: Float ->
        onVoicePlayerEvent(VoiceMessagePlayerEvent.Seek(position))
    }

    val layoutModifier = modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min)

    val placeholder = if (composerMode.inThread) {
        stringResource(id = CommonStrings.action_reply_in_thread)
    } else if (composerMode is MessageComposerMode.Attachment || composerMode is MessageComposerMode.EditCaption) {
        stringResource(id = R.string.rich_text_editor_composer_caption_placeholder)
    } else {
        ""
    }
    val textInput: @Composable () -> Unit = when (state) {
        is TextEditorState.Rich -> {
            val coroutineScope = rememberCoroutineScope()
            val view = LocalView.current
            remember(state.richTextEditorState, composerMode, onResetComposerMode, onError) {
                @Composable {
                    TextInputBox(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                coroutineScope.launch {
                                    state.requestFocus()
                                    view.showKeyboard()
                                }
                            }
                            .semantics {
                                hideFromAccessibility()
                            },
                        composerMode = composerMode,
                        onResetComposerMode = onResetComposerMode,
                        isTextEmpty = state.richTextEditorState.messageHtml.isEmpty(),
                    ) {
                        RichTextEditor(
                            state = state.richTextEditorState,
                            placeholder = placeholder,
                            registerStateUpdates = true,
                            modifier = Modifier
                                .padding(0.dp)
                                .fillMaxWidth(),
                            style = ElementRichTextEditorStyle.composerStyle(hasFocus = state.richTextEditorState.hasFocus),
                            resolveMentionDisplay = resolveMentionDisplay,
                            resolveRoomMentionDisplay = resolveAtRoomMentionDisplay,
                            onError = onError,
                            onRichContentSelected = onSelectRichContent,
                            onTyping = onTyping,
                        )
                    }
                }
            }
        }
        is TextEditorState.Markdown -> {
            @Composable {
                val style = ElementRichTextEditorStyle.composerStyle(hasFocus = state.hasFocus())
                TextInputBox(
                    composerMode = composerMode,
                    onResetComposerMode = onResetComposerMode,
                    isTextEmpty = state.state.text.value().isEmpty(),
                ) {
                    MarkdownTextInput(
                        state = state.state,
                        placeholder = placeholder,
                        placeholderColor = ElementTheme.colors.textSecondary,
                        onTyping = onTyping,
                        onReceiveSuggestion = onReceiveSuggestion,
                        richTextEditorStyle = style,
                        onSelectRichContent = onSelectRichContent,
                    )
                }
            }
        }
    }

    val canSendTextMessage = markdown.isNotBlank() || composerMode is MessageComposerMode.Attachment

    val textFormattingOptions: @Composable (() -> Unit)? = (state as? TextEditorState.Rich)?.let {
        @Composable { TextFormatting(state = it.richTextEditorState) }
    }

    val hapticFeedback = LocalHapticFeedback.current

    fun performHapticFeedback() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    @Composable
    fun rememberEndButtonParams() = remember(
        composerMode.isEditing,
        voiceMessageState.endButtonKey(),
        canSendTextMessage,
        attachmentSendEnabled,
        attachmentSendLabel,
    ) {
        when {
            composerMode is MessageComposerMode.Attachment && !attachmentSendEnabled -> EndButtonParams(
                endButtonContentDescriptionResId = CommonStrings.action_send_message,
                endButtonClick = {},
                endButtonContent = { SendButtonIcon(false, false, label = attachmentSendLabel) },
                enabled = false,
            )
            !canSendTextMessage ->
                when (voiceMessageState) {
                    VoiceMessageState.Idle -> EndButtonParams(
                        endButtonContentDescriptionResId = CommonStrings.a11y_voice_message_record,
                        endButtonClick = {
                            performHapticFeedback()
                            onVoiceRecorderEvent.invoke(VoiceMessageRecorderEvent.Start)
                        },
                        endButtonContent = @Composable {
                            VoiceMessageRecorderButtonIcon(
                                isRecording = false,
                            )
                        }
                    )
                    is VoiceMessageState.Recording -> EndButtonParams(
                        endButtonContentDescriptionResId = CommonStrings.a11y_voice_message_stop_recording,
                        endButtonClick = {
                            performHapticFeedback()
                            onVoiceRecorderEvent.invoke(VoiceMessageRecorderEvent.Stop)
                        },
                        endButtonContent = @Composable {
                            VoiceMessageRecorderButtonIcon(
                                isRecording = true,
                            )
                        }
                    )
                    is VoiceMessageState.Preview -> if (voiceMessageState.isSending) {
                        EndButtonParams(
                            endButtonContentDescriptionResId = CommonStrings.common_sending,
                            endButtonClick = {},
                            endButtonContent = @Composable {
                                VoiceMessageRecorderButtonIcon(isRecording = false)
                            }
                        )
                    } else {
                        EndButtonParams(
                            endButtonContentDescriptionResId = CommonStrings.action_send_voice_message,
                            endButtonClick = {
                                onSendVoiceMessage()
                            },
                            endButtonContent = @Composable {
                                SendButtonIcon(
                                    canSendMessage = true,
                                    isEditing = composerMode.isEditing,
                                )
                            },
                        )
                    }
                }
            composerMode.isEditing -> EndButtonParams(
                endButtonContentDescriptionResId = CommonStrings.action_send_edited_message,
                endButtonClick = {
                    onSendMessage()
                },
                endButtonContent = @Composable {
                    SendButtonIcon(
                        canSendMessage = true,
                        isEditing = true,
                    )
                },
            )
            else -> EndButtonParams(
                endButtonContentDescriptionResId = CommonStrings.action_send_message,
                endButtonClick = {
                    onSendMessage()
                },
                endButtonContent = @Composable {
                    SendButtonIcon(
                        canSendMessage = true,
                        isEditing = false,
                        label = attachmentSendLabel.takeIf { composerMode is MessageComposerMode.Attachment },
                    )
                },
            )
        }
    }

    @Composable
    fun rememberEndButtonParamsFormatting() = remember(composerMode.isEditing, canSendTextMessage) {
        if (composerMode.isEditing) {
            EndButtonParams(
                endButtonContentDescriptionResId = CommonStrings.action_send_edited_message,
                endButtonClick = {
                    if (canSendTextMessage) {
                        onSendMessage()
                    }
                },
                endButtonContent = @Composable {
                    SendButtonIcon(
                        canSendMessage = canSendTextMessage,
                        isEditing = true,
                    )
                },
            )
        } else {
            EndButtonParams(
                endButtonContentDescriptionResId = CommonStrings.action_send_message,
                endButtonClick = {
                    if (canSendTextMessage) {
                        onSendMessage()
                    }
                },
                endButtonContent = @Composable {
                    SendButtonIcon(
                        canSendMessage = canSendTextMessage,
                        isEditing = false,
                    )
                },
            )
        }
    }

    val voiceRecording = @Composable {
        when (voiceMessageState) {
            is VoiceMessageState.Preview ->
                VoiceMessagePreview(
                    isInteractive = !voiceMessageState.isSending,
                    isPlaying = voiceMessageState.isPlaying,
                    showCursor = voiceMessageState.showCursor,
                    waveform = voiceMessageState.waveform,
                    playbackProgress = voiceMessageState.playbackProgress,
                    time = voiceMessageState.time,
                    onPlayClick = onPlayVoiceMessageClick,
                    onPauseClick = onPauseVoiceMessageClick,
                    onSeek = onSeekVoiceMessage,
                )
            is VoiceMessageState.Recording ->
                VoiceMessageRecording(
                    levels = voiceMessageState.levels,
                    duration = voiceMessageState.duration,
                )
            VoiceMessageState.Idle -> {}
        }
    }

    if (showTextFormatting && textFormattingOptions != null) {
        val endButtonParams = rememberEndButtonParamsFormatting()
        TextFormattingLayout(
            modifier = layoutModifier,
            isRoomEncrypted = state.isRoomEncrypted,
            textInput = textInput,
            dismissTextFormattingButton = {
                IconColorButton(
                    onClick = onDismissTextFormatting,
                    imageVector = CompoundIcons.Close(),
                    contentDescription = stringResource(R.string.rich_text_editor_close_formatting_options),
                )
            },
            textFormatting = textFormattingOptions,
            endButtonParams = endButtonParams,
            onSendFlashReadMessage = onSendFlashReadMessage.takeIf { endButtonParams.enabled && !composerMode.isEditing },
            flashReadLabel = flashReadLabel,
            flashReadSlideHint = flashReadSlideHint,
            flashReadReleaseHint = flashReadReleaseHint,
        )
    } else {
        val endButtonParams = rememberEndButtonParams()
        StandardLayout(
            composerMode = composerMode,
            voiceMessageState = composerVoiceState,
            voiceInputBlocked = voiceInputBlocked,
            isRoomEncrypted = state.isRoomEncrypted,
            modifier = layoutModifier,
            textInput = textInput,
            endButtonParams = endButtonParams,
            voiceRecording = voiceRecording,
            onAddAttachment = {
                val cancelInFlightKeyboardSwitch = pendingKeyboardSwitch
                showEmojiPanel = false
                voiceInputMode = false
                if (cancelInFlightKeyboardSwitch) {
                    // A pending focus request may already have reached the Android editor even
                    // though its posted IME request has not run. Clear that focus before the
                    // attachment state is published, otherwise the delayed focus effect could
                    // immediately dismiss the newly opened panel.
                    dismissComposerKeyboard(force = true)
                } else {
                    cancelKeyboardRequest()
                }
                onAddAttachment()
            },
            onDeleteVoiceMessage = onDeleteVoiceMessage,
            onVoiceRecorderEvent = onVoiceRecorderEvent,
            onResetComposerMode = onResetComposerMode,
            canSendTextMessage = canSendTextMessage,
            showEmojiPanel = showEmojiPanel || showCommonPanel,
            emojiPanelVisible = showEmojiPanel,
            inputPanelHeight = inputPanelHeight,
            commonPanelContent = if (showCommonPanel) commonPanel?.let { content ->
                @Composable { content(Modifier.fillMaxWidth().height(
                    if (commonPanelEditing && imeBottom > navBottom) 176.dp
                    else (inputPanelHeight - with(density) { (imeBottom - navBottom).coerceAtLeast(0).toDp() }).coerceAtLeast(0.dp)
                ), { showCommonPanel = false }, {
                    showCommonPanel = false
                    showEmojiPanel = false
                    requestComposerKeyboard()
                }) }
            } else null,
            voiceInputMode = voiceInputMode,
            onToggleVoiceInputMode = {
                val openingVoiceInput = !voiceInputMode
                voiceInputMode = openingVoiceInput
                showEmojiPanel = false
                if (openingVoiceInput) {
                    onDismissAttachmentPanel()
                    dismissComposerKeyboard(force = true)
                } else {
                    requestComposerKeyboard()
                }
            },
            onToggleEmojiPanel = {
                when {
                    showCommonPanel -> {
                        showCommonPanel = false
                        requestComposerKeyboard()
                    }
                    pendingKeyboardSwitch -> {
                        // A second tap cancels the in-flight switch and leaves the usable emoji
                        // surface in place. Also clear any focus acquired by the stale request.
                        dismissComposerKeyboard(force = true)
                    }
                    showEmojiPanel -> {
                        pendingKeyboardSwitch = true
                        onDismissAttachmentPanel()
                        voiceInputMode = false
                        requestComposerKeyboard(
                            allowWhileEmojiPanelVisible = true,
                            onKeyboardRequestSubmitted = {
                                pendingKeyboardSwitch = false
                                showEmojiPanel = false
                            },
                            onKeyboardRequestFailed = {
                                // IME refusal or a missing editor must not strand the composer in
                                // a blank transitional state. Keep the emoji panel fully usable.
                                pendingKeyboardSwitch = false
                            },
                        )
                    }
                    else -> {
                        showEmojiPanel = true
                        onDismissAttachmentPanel()
                        voiceInputMode = false
                        dismissComposerKeyboard(force = true)
                    }
                }
            },
            onEmojiSelected = { emoji ->
                emojiScope.launch {
                    state.appendText(emoji)
                    // setMarkdown may restore editor focus. Keep the emoji panel in control until
                    // the user explicitly taps the keyboard button or the text field.
                    dismissComposerKeyboard()
                }
            },
            onEmojiBackspace = {
                emojiScope.launch {
                    state.deleteBackward()
                    dismissComposerKeyboard()
                }
            },
            emotionPanel = emotionPanel,
            onPickSticker = onPickSticker,
            stickerLabel = stickerLabel,
            onOpenCommon = if (commonPanel != null || onOpenCommon != null) ::openCommonPanel else null,
            commonLabel = commonLabel,
            onSendFlashReadMessage = onSendFlashReadMessage,
            flashReadLabel = flashReadLabel,
            flashReadSlideHint = flashReadSlideHint,
            flashReadReleaseHint = flashReadReleaseHint,
            onVoicePressStarted = {
                releasedVoiceHidden = false
                voiceSendingObserved = false
                sendVoiceWhenReady = false
                performHapticFeedback()
                onVoiceRecorderEvent(VoiceMessageRecorderEvent.Start)
            },
            onVoicePressFinished = { released ->
                // A permission prompt can end the gesture before recording actually starts.
                // Cancel that idle attempt instead of submitting a nonexistent voice draft.
                if (released && voiceMessageState !is VoiceMessageState.Idle) {
                    releasedVoiceHidden = true
                    sendVoiceWhenReady = false
                    onVoiceRecorderEvent(VoiceMessageRecorderEvent.Stop)
                    onSendVoiceMessage()
                } else {
                    sendVoiceWhenReady = false
                    onVoiceRecorderEvent(VoiceMessageRecorderEvent.Cancel)
                }
            },
        )
    }

    SoftKeyboardEffect(composerMode, onRequestFocus) {
        it is MessageComposerMode.Special
    }

    SoftKeyboardEffect(showTextFormatting, onRequestFocus) { it }

    LaunchedEffect(state.hasFocus()) {
        if (state.hasFocus()) {
            onDismissAttachmentPanel()
            showCommonPanel = false
            if (!pendingKeyboardSwitch) {
                showEmojiPanel = false
            }
            voiceInputMode = false
        }
    }

    LaunchedEffect(voiceMessageState, sendVoiceWhenReady) {
        when (voiceMessageState) {
            is VoiceMessageState.Preview -> if (sendVoiceWhenReady && !voiceMessageState.isSending) {
                sendVoiceWhenReady = false
                onSendVoiceMessage()
            }
            VoiceMessageState.Idle -> sendVoiceWhenReady = false
            is VoiceMessageState.Recording -> Unit
        }
    }

    val latestOnReceiveSuggestion by rememberUpdatedState(onReceiveSuggestion)
    if (state is TextEditorState.Rich) {
        val menuAction = state.richTextEditorState.menuAction
        LaunchedEffect(menuAction) {
            if (menuAction is MenuAction.Suggestion) {
                val suggestion = Suggestion(menuAction.suggestionPattern)
                latestOnReceiveSuggestion(suggestion)
            } else {
                latestOnReceiveSuggestion(null)
            }
        }
    }
}

private data class EndButtonParams(
    val endButtonContentDescriptionResId: Int,
    val endButtonClick: () -> Unit,
    val endButtonContent: @Composable () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun StandardLayout(
    composerMode: MessageComposerMode,
    voiceMessageState: VoiceMessageState,
    voiceInputBlocked: Boolean,
    isRoomEncrypted: Boolean?,
    textInput: @Composable () -> Unit,
    voiceRecording: @Composable () -> Unit,
    endButtonParams: EndButtonParams,
    onAddAttachment: () -> Unit,
    onDeleteVoiceMessage: () -> Unit,
    onVoiceRecorderEvent: (VoiceMessageRecorderEvent) -> Unit,
    onResetComposerMode: () -> Unit,
    canSendTextMessage: Boolean,
    showEmojiPanel: Boolean,
    emojiPanelVisible: Boolean,
    inputPanelHeight: Dp,
    commonPanelContent: (@Composable () -> Unit)?,
    voiceInputMode: Boolean,
    onToggleVoiceInputMode: () -> Unit,
    onToggleEmojiPanel: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onEmojiBackspace: () -> Unit,
    emotionPanel: (@Composable (EmojiPanelInput) -> Unit)?,
    onPickSticker: (() -> Unit)?,
    stickerLabel: String,
    onOpenCommon: (() -> Unit)?,
    commonLabel: String,
    onSendFlashReadMessage: (() -> Unit)?,
    flashReadLabel: String,
    flashReadSlideHint: String,
    flashReadReleaseHint: String,
    onVoicePressStarted: () -> Unit,
    onVoicePressFinished: (released: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = modifier.background(ElementTheme.colors.bgSubtleSecondary),
    ) {
        if (isRoomEncrypted == false) {
            Spacer(Modifier.height(16.dp))
            NotEncryptedBadge()
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(ElementTheme.colors.bgSubtleSecondary)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                when (composerMode) {
                is MessageComposerMode.Attachment -> {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                is MessageComposerMode.EditCaption -> {
                    Spacer(modifier = Modifier.width(19.dp))
                }
                else -> if (voiceInputMode && voiceMessageState !is VoiceMessageState.Preview) {
                    ComposerActionButton(
                        modifier = Modifier.size(COMPOSER_TOUCH_TARGET_SIZE),
                        onClick = {
                            if (voiceMessageState is VoiceMessageState.Idle) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleVoiceInputMode()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = CompoundIcons.Keyboard(),
                            contentDescription = stringResource(R.string.a11y_text_composer_switch_to_keyboard),
                            modifier = Modifier.size(COMPOSER_ICON_SIZE),
                            tint = ElementTheme.colors.iconPrimary,
                        )
                    }
                } else if (voiceMessageState !is VoiceMessageState.Idle) {
                    // Keep this control in the layout so keyboard focus is not lost when the voice state changes.
                    ComposerActionButton(
                        modifier = Modifier.size(38.dp),
                        onClick = {
                            if (voiceMessageState is VoiceMessageState.Idle) {
                                onAddAttachment()
                            } else {
                                when (voiceMessageState) {
                                    is VoiceMessageState.Preview -> if (!voiceMessageState.isSending) {
                                        onDeleteVoiceMessage()
                                    }
                                    is VoiceMessageState.Recording ->
                                        onVoiceRecorderEvent(VoiceMessageRecorderEvent.Cancel)
                                }
                            }
                        },
                    ) {
                        when (voiceMessageState) {
                            is VoiceMessageState.Preview ->
                                VoiceMessageDeleteButtonIcon(enabled = !voiceMessageState.isSending)
                            is VoiceMessageState.Recording ->
                                VoiceMessageDeleteButtonIcon(enabled = true)
                            VoiceMessageState.Idle -> Unit
                        }
                    }
                } else {
                    ComposerActionButton(
                        modifier = Modifier.size(COMPOSER_TOUCH_TARGET_SIZE),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleVoiceInputMode()
                        },
                    ) {
                        if (voiceInputMode) {
                            Icon(
                                imageVector = CompoundIcons.Keyboard(),
                                contentDescription = stringResource(R.string.a11y_text_composer_switch_to_keyboard),
                                modifier = Modifier.size(COMPOSER_ICON_SIZE),
                                tint = ElementTheme.colors.iconPrimary,
                            )
                        } else {
                            VoiceMessageRecorderButtonIcon(isRecording = false)
                        }
                    }
                }
            }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElementTheme.colors.bgCanvasDefault)
                ) {
                val movableVoiceRecording = remember { movableContentOf { voiceRecording() } }
                if (voiceInputMode && voiceMessageState !is VoiceMessageState.Preview) {
                    PressToTalkButton(
                        recordingState = voiceMessageState as? VoiceMessageState.Recording,
                        enabled = !voiceInputBlocked,
                        onPressStarted = onVoicePressStarted,
                        onPressFinished = onVoicePressFinished,
                    )
                } else if (voiceMessageState is VoiceMessageState.Idle) {
                    textInput()
                } else if (composerMode is MessageComposerMode.Special) {
                    TextInputBox(
                        composerMode = composerMode,
                        onResetComposerMode = onResetComposerMode,
                        isTextEmpty = true,
                    ) {
                        movableVoiceRecording()
                    }
                } else {
                    movableVoiceRecording()
                }
            }
                if (voiceMessageState is VoiceMessageState.Idle || voiceInputMode && voiceMessageState is VoiceMessageState.Recording) {
                val emojiButtonDescription = stringResource(
                    if (showEmojiPanel) {
                        R.string.a11y_text_composer_switch_to_keyboard
                    } else {
                        R.string.a11y_text_composer_choose_emoji
                    }
                )
                ComposerActionButton(
                    modifier = Modifier.size(COMPOSER_TOUCH_TARGET_SIZE),
                    onDoubleClick = onOpenCommon,
                    onClick = {
                        if (voiceMessageState is VoiceMessageState.Idle) {
                            onToggleEmojiPanel()
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (showEmojiPanel) CompoundIcons.Keyboard() else CompoundIcons.Reaction(),
                        contentDescription = emojiButtonDescription,
                        modifier = Modifier.size(COMPOSER_ICON_SIZE),
                        tint = ElementTheme.colors.iconPrimary,
                    )
                }
                if (canSendTextMessage && !voiceInputMode) {
                    val endButtonContentDescription = stringResource(endButtonParams.endButtonContentDescriptionResId)
                    SendGestureButton(
                        modifier = Modifier.size(72.dp, COMPOSER_TOUCH_TARGET_SIZE).clearAndSetSemantics {
                            contentDescription = endButtonContentDescription
                            if (endButtonParams.enabled) onClick(null, null) else disabled()
                        },
                        onClick = endButtonParams.endButtonClick,
                        onSendFlashRead = onSendFlashReadMessage.takeIf { endButtonParams.enabled && !composerMode.isEditing },
                        flashReadLabel = flashReadLabel,
                        flashReadSlideHint = flashReadSlideHint,
                        flashReadReleaseHint = flashReadReleaseHint,
                        content = endButtonParams.endButtonContent,
                    )
                } else {
                    ComposerActionButton(
                        modifier = Modifier.size(COMPOSER_TOUCH_TARGET_SIZE),
                        onClick = {
                            if (voiceMessageState is VoiceMessageState.Idle) {
                                onAddAttachment()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = CompoundIcons.Plus(),
                            contentDescription = stringResource(R.string.rich_text_editor_a11y_add_attachment),
                            modifier = Modifier.size(COMPOSER_ICON_SIZE),
                            tint = ElementTheme.colors.iconPrimary,
                        )
                    }
                }
                } else {
                    val endButtonContentDescription = stringResource(endButtonParams.endButtonContentDescriptionResId)
                    SendGestureButton(
                        modifier = Modifier.size(72.dp, 38.dp).clearAndSetSemantics {
                            contentDescription = endButtonContentDescription
                            if (endButtonParams.enabled) onClick(null, null) else disabled()
                        },
                        onClick = endButtonParams.endButtonClick,
                        onSendFlashRead = onSendFlashReadMessage.takeIf { endButtonParams.enabled && !composerMode.isEditing },
                        flashReadLabel = flashReadLabel,
                        flashReadSlideHint = flashReadSlideHint,
                        flashReadReleaseHint = flashReadReleaseHint,
                        content = endButtonParams.endButtonContent,
                    )
                }
        }
        if (commonPanelContent == null) AnimatedVisibility(
            visible = emojiPanelVisible && voiceMessageState is VoiceMessageState.Idle,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 12 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 12 },
        ) {
            EmojiPanel(
                panelHeight = inputPanelHeight,
                onEmojiSelected = onEmojiSelected,
                onBackspace = onEmojiBackspace,
                emotionPanel = emotionPanel,
            onPickSticker = onPickSticker,
                stickerLabel = stickerLabel,
                onOpenCommon = onOpenCommon,
                commonLabel = commonLabel,
            )
        }
        if (voiceMessageState is VoiceMessageState.Idle) commonPanelContent?.invoke()
    }
}

@Composable
private fun SendGestureButton(
    onClick: () -> Unit,
    onSendFlashRead: (() -> Unit)?,
    flashReadLabel: String,
    flashReadSlideHint: String,
    flashReadReleaseHint: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnSendFlashRead by rememberUpdatedState(onSendFlashRead)
    val hapticFeedback = LocalHapticFeedback.current
    val targetThresholdPx = with(LocalDensity.current) { FLASH_READ_TARGET_DISTANCE.toPx() }
    val popupOffset = with(LocalDensity.current) { IntOffset(0, (-84).dp.roundToPx()) }
    var isLongPressActive by remember { mutableStateOf(false) }
    var isFlashReadTargeted by remember { mutableStateOf(false) }
    var dragDistanceY by remember { mutableStateOf(0f) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ComposerActionButton(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onSendFlashRead) {
                    if (onSendFlashRead != null) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragDistanceY = 0f
                                isFlashReadTargeted = false
                                isLongPressActive = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDistanceY += dragAmount.y
                                val targeted = dragDistanceY <= -targetThresholdPx
                                if (targeted != isFlashReadTargeted) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                isFlashReadTargeted = targeted
                            },
                            onDragEnd = {
                                val shouldSend = isFlashReadTargeted
                                isLongPressActive = false
                                isFlashReadTargeted = false
                                dragDistanceY = 0f
                                if (shouldSend) latestOnSendFlashRead?.invoke()
                            },
                            onDragCancel = {
                                isLongPressActive = false
                                isFlashReadTargeted = false
                                dragDistanceY = 0f
                            },
                        )
                    }
                },
            onClick = { latestOnClick() },
            content = content,
        )

        if (isLongPressActive) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = popupOffset,
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isFlashReadTargeted) ElementTheme.colors.bgAccentRest else ElementTheme.colors.bgCanvasDefault
                        )
                        .border(
                            width = 1.dp,
                            color = if (isFlashReadTargeted) ElementTheme.colors.borderInteractiveSecondary else ElementTheme.colors.borderDisabled,
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = flashReadLabel,
                        style = ElementTheme.typography.fontBodyLgMedium,
                        color = if (isFlashReadTargeted) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textPrimary,
                    )
                    Text(
                        text = if (isFlashReadTargeted) flashReadReleaseHint else flashReadSlideHint,
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = if (isFlashReadTargeted) {
                            ElementTheme.colors.textOnSolidPrimary.copy(alpha = 0.8f)
                        } else {
                            ElementTheme.colors.textSecondary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PressToTalkButton(
    recordingState: VoiceMessageState.Recording?,
    enabled: Boolean,
    onPressStarted: () -> Unit,
    onPressFinished: (released: Boolean) -> Unit,
) {
    val recordingView = LocalView.current
    val idleDescription = stringResource(R.string.text_composer_hold_to_record)
    val sendingDescription = stringResource(R.string.text_composer_release_to_send_slide_to_cancel)
    val cancelDescription = stringResource(R.string.text_composer_release_to_cancel)
    val latestOnPressStarted by rememberUpdatedState(onPressStarted)
    val latestOnPressFinished by rememberUpdatedState(onPressFinished)
    val cancelThresholdPx = with(LocalDensity.current) { VOICE_CANCEL_DISTANCE.toPx() }
    val hapticFeedback = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    var cancelOnRelease by remember { mutableStateOf(false) }
    val description = when {
        cancelOnRelease -> cancelDescription
        isPressed || recordingState != null -> sendingDescription
        else -> idleDescription
    }
    val shape = textInputRoundedCornerShape(MessageComposerMode.Normal)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .requiredHeightIn(min = COMPOSER_TOUCH_TARGET_SIZE)
            .clip(shape)
            .background(
                if (cancelOnRelease) ElementTheme.colors.bgCriticalSubtle else ElementTheme.colors.bgCanvasDefault
            )
            .pointerInput(enabled) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabled) return@awaitEachGesture
                        isPressed = true
                        cancelOnRelease = false
                        latestOnPressStarted()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                latestOnPressFinished(false)
                                break
                            }
                            val shouldCancel = down.position.y - change.position.y >= cancelThresholdPx
                            if (shouldCancel && !cancelOnRelease) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            cancelOnRelease = shouldCancel
                            change.consume()
                            if (!change.pressed) {
                                // Window cancellation also releases Compose pointers. Only an up
                                // while this window still owns focus can be the user's send action.
                                latestOnPressFinished(!cancelOnRelease && recordingView.hasWindowFocus())
                                break
                            }
                        }
                        isPressed = false
                        cancelOnRelease = false
                    }
                } finally {
                    if (isPressed) {
                        latestOnPressFinished(false)
                    }
                    isPressed = false
                    cancelOnRelease = false
                }
            }
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = description,
            style = ElementTheme.typography.fontBodyLgMedium,
            color = if (cancelOnRelease) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textPrimary,
        )
    }
    if (isPressed || recordingState != null) {
        VoiceRecordingGestureOverlayPopup(
            levels = recordingState?.levels ?: persistentListOf(),
            duration = recordingState?.duration ?: 0.seconds,
            cancelOnRelease = cancelOnRelease,
        )
    }
}

@Composable
private fun VoiceRecordingGestureOverlayPopup(
    levels: ImmutableList<Float>,
    duration: kotlin.time.Duration,
    cancelOnRelease: Boolean,
) {
    Popup(
        popupPositionProvider = FullScreenPopupPositionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        VoiceRecordingGestureOverlay(
            levels = levels,
            duration = duration,
            cancelOnRelease = cancelOnRelease,
        )
    }
}

private object FullScreenPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
private fun VoiceRecordingGestureOverlay(
    levels: ImmutableList<Float>,
    duration: kotlin.time.Duration,
    cancelOnRelease: Boolean,
) {
    val activeColor = if (cancelOnRelease) ElementTheme.colors.bgCriticalSubtle else ElementTheme.colors.bgActionPrimaryRest
    val activeContentColor = if (cancelOnRelease) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textOnSolidPrimary
    val waveform = if (levels.isEmpty()) WaveFormSamples.realisticWaveForm else levels
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = VOICE_OVERLAY_SCRIM_ALPHA)),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(VOICE_OVERLAY_BUBBLE_WIDTH)
                    .height(VOICE_OVERLAY_BUBBLE_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(activeColor)
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LiveWaveformView(
                            levels = waveform,
                            modifier = Modifier
                                .width(150.dp)
                                .height(34.dp),
                            brush = SolidColor(activeContentColor),
                            lineWidth = 2.dp,
                            linePadding = 2.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = duration.formatShort(),
                            style = ElementTheme.typography.fontBodyMdMedium,
                            color = activeContentColor,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 9.dp)
                        .size(22.dp)
                        .rotate(45f)
                        .background(activeColor),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(
                    if (cancelOnRelease) R.string.text_composer_release_to_cancel else R.string.text_composer_slide_up_to_cancel
                ),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = if (cancelOnRelease) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textOnSolidPrimary,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(VOICE_OVERLAY_SEND_ARC_HEIGHT),
            contentAlignment = Alignment.TopCenter,
        ) {
            val sendArcColor = ElementTheme.colors.bgCanvasDefault
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(0f, size.height * 0.30f)
                    quadraticTo(size.width / 2f, 0f, size.width, size.height * 0.30f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = path, color = sendArcColor)
            }
            Text(
                modifier = Modifier.padding(top = 68.dp),
                text = stringResource(R.string.text_composer_release_to_send),
                style = ElementTheme.typography.fontHeadingMdBold,
                color = if (cancelOnRelease) ElementTheme.colors.textSecondary else ElementTheme.colors.textPrimary,
            )
        }
    }
}

@Preview(name = "Voice recording gesture", widthDp = 360, heightDp = 720)
@Composable
internal fun VoiceRecordingGestureOverlayPreview() = ElementPreview {
    VoiceRecordingGestureOverlay(
        levels = WaveFormSamples.realisticWaveForm,
        duration = 3.seconds,
        cancelOnRelease = false,
    )
}

@Preview(name = "Voice recording cancel", widthDp = 360, heightDp = 720)
@Composable
internal fun VoiceRecordingCancelOverlayPreview() = ElementPreview {
    VoiceRecordingGestureOverlay(
        levels = WaveFormSamples.realisticWaveForm,
        duration = 3.seconds,
        cancelOnRelease = true,
    )
}

@Composable
private fun EmojiPanel(
    panelHeight: Dp = EMOJI_PANEL_HEIGHT,
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    emotionPanel: (@Composable (EmojiPanelInput) -> Unit)?,
    onPickSticker: (() -> Unit)?,
    stickerLabel: String,
    onOpenCommon: (() -> Unit)?,
    commonLabel: String,
) {
    Box(Modifier.fillMaxWidth().height(panelHeight).background(ElementTheme.colors.bgSubtleSecondary)) {
        if (emotionPanel != null) {
            emotionPanel(EmojiPanelInput(EMOJI_CATALOG, onEmojiSelected, onBackspace, onOpenCommon, commonLabel))
        } else StandardEmojiPanel(
            panelHeight = panelHeight,
            onEmojiSelected = onEmojiSelected, onBackspace = onBackspace, onPickSticker = onPickSticker,
            stickerLabel = stickerLabel, onOpenCommon = onOpenCommon, commonLabel = commonLabel,
        )
    }
}

data class EmojiPanelInput(
    val emojis: List<String>,
    val onSelect: (String) -> Unit,
    val onBackspace: () -> Unit,
    val onOpenCommon: (() -> Unit)?,
    val commonLabel: String,
)

@Composable
private fun StandardEmojiPanel(
    panelHeight: Dp,
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onPickSticker: (() -> Unit)?,
    stickerLabel: String,
    onOpenCommon: (() -> Unit)?,
    commonLabel: String,
) {
    val emojis = remember { EMOJI_CATALOG }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(ElementTheme.colors.bgSubtleSecondary)
    ) {
        HorizontalDivider()
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 58.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(emojis) { _, emoji ->
                ComposerActionButton(
                    modifier = Modifier.size(44.dp),
                    onClick = { onEmojiSelected(emoji) },
                ) {
                    Text(
                        text = emoji,
                        style = ElementTheme.typography.fontHeadingMdBold,
                    )
                }
            }
        }
        if (onOpenCommon != null) {
            ComposerActionButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 54.dp).size(56.dp, 44.dp).clip(CircleShape).background(ElementTheme.colors.bgCanvasDefault),
                onClick = onOpenCommon,
            ) {
                Text(commonLabel, style = ElementTheme.typography.fontBodyMdMedium, color = ElementTheme.colors.textPrimary)
            }
        }
        ComposerActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 8.dp)
                .size(56.dp, 42.dp)
                .clip(CircleShape)
                .background(ElementTheme.colors.bgCanvasDefault),
            onClick = onBackspace,
        ) {
            Icon(
                imageVector = CompoundIcons.Backspace(),
                contentDescription = stringResource(CommonStrings.action_remove),
                tint = ElementTheme.colors.iconPrimary,
            )
        }
        if (onPickSticker != null) {
            ComposerActionButton(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 8.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ElementTheme.colors.bgCanvasDefault)
                    .padding(horizontal = 14.dp),
                onClick = onPickSticker,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = CompoundIcons.Image(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = ElementTheme.colors.iconPrimary,
                    )
                    Text(
                        text = stickerLabel,
                        style = ElementTheme.typography.fontBodyMdMedium,
                        color = ElementTheme.colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotEncryptedBadge() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(16.dp),
            imageVector = CompoundIcons.LockOff(),
            contentDescription = null,
            tint = ElementTheme.colors.iconInfoPrimary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(CommonStrings.common_not_encrypted),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun TextFormattingLayout(
    isRoomEncrypted: Boolean?,
    textInput: @Composable () -> Unit,
    dismissTextFormattingButton: @Composable () -> Unit,
    textFormatting: @Composable () -> Unit,
    endButtonParams: EndButtonParams,
    onSendFlashReadMessage: (() -> Unit)?,
    flashReadLabel: String,
    flashReadSlideHint: String,
    flashReadReleaseHint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isRoomEncrypted == false) {
            NotEncryptedBadge()
            Spacer(Modifier.height(8.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(textInputRoundedCornerShape(MessageComposerMode.Normal))
                .border(1.dp, ElementTheme.colors.borderDisabled, textInputRoundedCornerShape(MessageComposerMode.Normal))
                .background(ElementTheme.colors.bgSubtleSecondary)
        ) {
            textInput()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.padding(start = 3.dp)
            ) {
                dismissTextFormattingButton()
            }
            Box(modifier = Modifier.weight(1f)) {
                textFormatting()
            }
            // Keep this control in the layout so keyboard focus is not lost when the voice state changes.
            val endButtonContentDescription = stringResource(endButtonParams.endButtonContentDescriptionResId)
            SendGestureButton(
                modifier = Modifier
                    .padding(
                        start = 14.dp,
                        end = 6.dp,
                    )
                    .size(72.dp, 38.dp)
                    .clearAndSetSemantics {
                        contentDescription = endButtonContentDescription
                        onClick(null, null)
                    },
                onClick = endButtonParams.endButtonClick,
                onSendFlashRead = onSendFlashReadMessage,
                flashReadLabel = flashReadLabel,
                flashReadSlideHint = flashReadSlideHint,
                flashReadReleaseHint = flashReadReleaseHint,
                content = endButtonParams.endButtonContent,
            )
        }
    }
}

/**
 * The visible glyph stays compact while the touch target remains accessible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerActionButton(
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        label = "composerActionButtonScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressedScale
                scaleY = pressedScale
            }
            .then(if (onDoubleClick == null) Modifier.clickable(interactionSource, null, onClick = onClick)
                else Modifier.combinedClickable(interactionSource, null, onClick = onClick, onDoubleClick = onDoubleClick)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun TextInputBox(
    composerMode: MessageComposerMode,
    onResetComposerMode: () -> Unit,
    isTextEmpty: Boolean,
    modifier: Modifier = Modifier,
    textInput: @Composable () -> Unit,
) {
    val roundedCorners = textInputRoundedCornerShape(composerMode = composerMode)

    Column(
        modifier = Modifier
            .clip(roundedCorners)
            .requiredHeightIn(min = COMPOSER_TOUCH_TARGET_SIZE)
            .fillMaxWidth()
            .then(modifier),
    ) {
        if (composerMode is MessageComposerMode.Special) {
            ComposerModeView(
                composerMode = composerMode,
                onResetComposerMode = onResetComposerMode,
            )
        } else {
            // Keep the single-line composer on the same 38dp baseline as its controls.
            Spacer(Modifier.height(0.dp))
        }

        Box(
            modifier = Modifier
                .then(
                    if (composerMode is MessageComposerMode.Special) {
                        Modifier
                    } else {
                        Modifier.requiredHeightIn(min = COMPOSER_TOUCH_TARGET_SIZE)
                    }
                )
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp)
                .then(Modifier.testTag(TestTags.textEditor)),
            contentAlignment = Alignment.CenterStart,
        ) {
            textInput()
            // The warning is about a caption that older clients may not render. Showing a
            // critical icon while no caption exists makes a successfully selected attachment
            // look as if it failed to upload.
            if (!isTextEmpty && composerMode.showCaptionCompatibilityWarning()) {
                var showBottomSheet by remember { mutableStateOf(false) }
                Icon(
                    modifier = Modifier
                        .clickable { showBottomSheet = true }
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        .align(Alignment.CenterEnd),
                    imageVector = CompoundIcons.InfoSolid(),
                    tint = ElementTheme.colors.iconCriticalPrimary,
                    contentDescription = stringResource(CommonStrings.a11y_info),
                )
                if (showBottomSheet) {
                    CaptionWarningBottomSheet(
                        onDismiss = { showBottomSheet = false },
                    )
                }
            }
        }
    }
}

private val COMPOSER_TOUCH_TARGET_SIZE = 48.dp
private val COMPOSER_ICON_SIZE = 30.dp
private val EMOJI_PANEL_HEIGHT = 292.dp
private val FLASH_READ_TARGET_DISTANCE = 44.dp
private val VOICE_CANCEL_DISTANCE = 64.dp
private val VOICE_OVERLAY_BUBBLE_WIDTH = 220.dp
private val VOICE_OVERLAY_BUBBLE_HEIGHT = 112.dp
private val VOICE_OVERLAY_SEND_ARC_HEIGHT = 190.dp
private const val VOICE_OVERLAY_SCRIM_ALPHA = 0.64f
private const val KEYBOARD_FOCUS_RETRY_COUNT = 2
private val EMOJI_CATALOG = """
    😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩
    😘 😗 😚 😋 😎 🤓 🧐 🤔 🤗 🤭 🤫 🤥 😐 😑 😶 🙄
    😏 😣 😥 😮 🤐 😯 😪 😫 🥱 😴 😌 🤤 😛 😜 😝 🤤
    😒 😓 😔 😕 🙃 🫤 🫠 😲 😖 😞 😟 😤 😢 😭 😦 😧
    😨 😩 🤯 😬 😰 😱 🥵 🥶 😳 🤪 😵 😡 😠 🤬 😷 🤒
    👍 👎 👏 🙌 👐 🤝 🙏 💪 👌 ✌️ 🤞 🤟 🤘 👈 👉 👆
    👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤏 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍
    💔 ❣️ 💕 💞 💓 💗 💖 💘 🎉 🎊 ✨ ⭐ 🌟 🔥 💯 ✅
    ❌ ⚡ 💡 🎁 🌈 ☀️ 🌙 ☁️ 🍎 🍊 🍋 🍉 🍇 🍓 🍒 🍑
    🍔 🍟 🍕 🌭 🍿 🍜 🍣 🍰 ⚽ 🏀 🏆 🚗 ✈️ 🚀 🎵 🎸
    🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼
""".trimIndent().split(Regex("\\s+"))

private fun VoiceMessageState.endButtonKey() = when (this) {
    is VoiceMessageState.Idle -> "Idle"
    is VoiceMessageState.Preview -> "Preview_$isSending"
    is VoiceMessageState.Recording -> "Recording"
}

private fun aTextEditorStateMarkdownList(isRoomEncrypted: Boolean? = null) = persistentListOf(
    aTextEditorStateMarkdown(initialText = "", initialFocus = true, isRoomEncrypted = isRoomEncrypted),
    aTextEditorStateMarkdown(initialText = "A message", initialFocus = true, isRoomEncrypted = isRoomEncrypted),
    aTextEditorStateMarkdown(
        initialText = "A message\nWith several lines\nTo preview larger textfields and long lines with overflow",
        initialFocus = true,
        isRoomEncrypted = isRoomEncrypted,
    ),
    aTextEditorStateMarkdown(initialText = "A message without focus", initialFocus = false, isRoomEncrypted = isRoomEncrypted),
)

private fun aTextEditorStateRichList(isRoomEncrypted: Boolean? = null) = persistentListOf(
    aTextEditorStateRich(initialFocus = true, isRoomEncrypted = isRoomEncrypted),
    aTextEditorStateRich(initialText = "A message", initialFocus = true, isRoomEncrypted = isRoomEncrypted),
    aTextEditorStateRich(
        initialText = "A message\nWith several lines\nTo preview larger textfields and long lines with overflow",
        initialFocus = true,
        isRoomEncrypted = isRoomEncrypted,
    ),
    aTextEditorStateRich(initialText = "A message without focus", initialFocus = false, isRoomEncrypted = isRoomEncrypted),
)

@PreviewsDayNight
@Composable
internal fun TextComposerSimplePreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateMarkdownList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerSimpleNotEncryptedPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateMarkdownList(isRoomEncrypted = false),
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerFormattingPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            showTextFormatting = true,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerFormattingNotEncryptedPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList(isRoomEncrypted = false)
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            showTextFormatting = true,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerEditPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeEdit(),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerEditNotEncryptedPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList(isRoomEncrypted = false)
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeEdit(),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerEditCaptionPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeEditCaption(
                // Set an existing caption so that the UI will be in edit caption mode
                content = "An existing caption",
            ),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerAddCaptionPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeEditCaption(
                // No caption so that the UI will be in add caption mode
                content = "",
            ),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun MarkdownTextComposerEditPreview() = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateMarkdownList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeEdit(),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerReplyPreview(@PreviewParameter(InReplyToDetailsProvider::class) inReplyToDetails: InReplyToDetails) = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList()
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeReply(
                replyToDetails = inReplyToDetails,
            ),
        )
    }
}

@Preview(
    name = DAY_MODE_NAME,
    heightDp = 800,
)
@Preview(
    name = NIGHT_MODE_NAME,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    heightDp = 800,
)
@Composable
internal fun TextComposerReplyNotEncryptedPreview(@PreviewParameter(InReplyToDetailsProvider::class) inReplyToDetails: InReplyToDetails) = ElementPreview {
    PreviewColumn(
        items = aTextEditorStateRichList(isRoomEncrypted = false)
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = aMessageComposerModeReply(
                replyToDetails = inReplyToDetails,
            ),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerCaptionPreview() = ElementPreview {
    val list = aTextEditorStateMarkdownList()
    PreviewColumn(
        items = list,
    ) { textEditorState ->
        ATextComposer(
            state = textEditorState,
            voiceMessageState = VoiceMessageState.Idle,
            composerMode = MessageComposerMode.Attachment,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerVoicePreview() = ElementPreview {
    PreviewColumn(
        items = persistentListOf(
            VoiceMessageState.Recording(
                duration = 61.seconds,
                levels = WaveFormSamples.realisticWaveForm,
            ),
            VoiceMessageState.Preview(
                isSending = false,
                isPlaying = false,
                showCursor = false,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 0.seconds,
                playbackProgress = 0.0f,
            ),
            VoiceMessageState.Preview(
                isSending = false,
                isPlaying = true,
                showCursor = true,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 3.seconds,
                playbackProgress = 0.2f,
            ),
            VoiceMessageState.Preview(
                isSending = true,
                isPlaying = false,
                showCursor = false,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 61.seconds,
                playbackProgress = 0.0f,
            ),
        )
    ) { voiceMessageState ->
        ATextComposer(
            state = aTextEditorStateRich(initialFocus = true),
            voiceMessageState = voiceMessageState,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun TextComposerVoiceNotEncryptedPreview() = ElementPreview {
    PreviewColumn(
        items = persistentListOf(
            VoiceMessageState.Recording(
                duration = 61.seconds,
                levels = WaveFormSamples.realisticWaveForm,
            ),
            VoiceMessageState.Preview(
                isSending = false,
                isPlaying = false,
                showCursor = false,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 0.seconds,
                playbackProgress = 0.0f
            ),
            VoiceMessageState.Preview(
                isSending = false,
                isPlaying = true,
                showCursor = true,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 3.seconds,
                playbackProgress = 0.2f
            ),
            VoiceMessageState.Preview(
                isSending = true,
                isPlaying = false,
                showCursor = false,
                waveform = WaveFormSamples.realisticWaveForm,
                time = 61.seconds,
                playbackProgress = 0.0f
            ),
        )
    ) { voiceMessageState ->
        ATextComposer(
            state = aTextEditorStateRich(initialFocus = true, isRoomEncrypted = false),
            voiceMessageState = voiceMessageState,
            composerMode = MessageComposerMode.Normal,
        )
    }
}

@Preview
@Composable
internal fun TextComposerScaledDensityWithReplyPreview() {
    ElementPreview {
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = 3f,
                fontScale = 1.25f,
            ),
        ) {
            val replyToDetails = InReplyToDetails.Ready(
                eventId = EventId("\$1234"),
                senderId = UserId("@alice:example.com"),
                senderProfile = aProfileDetailsReady(),
                eventContent = MessageContent(
                    body = "Message which are being replied, and which was long enough to be displayed on two lines (only!).",
                    inReplyTo = null,
                    isEdited = false,
                    threadInfo = null,
                    type = TextMessageType("Message which are being replied, and which was long enough to be displayed on two lines (only!).", null)
                ),
                textContent = "Message which are being replied, and which was long enough to be displayed on two lines (only!).",
            )
            Box(modifier = Modifier.width(480.dp).height(120.dp)) {
                ATextComposer(
                    state = aTextEditorStateMarkdown(initialText = "", initialFocus = true),
                    voiceMessageState = VoiceMessageState.Idle,
                    composerMode = MessageComposerMode.Reply(replyToDetails, hideImage = false),
                )
            }
        }
    }
}

@Composable
private fun <T> PreviewColumn(
    items: ImmutableList<T>,
    view: @Composable (T) -> Unit,
) {
    Column {
        items.forEach { item ->
            HorizontalDivider()
            Box(
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                view(item)
            }
        }
    }
}

@Composable
private fun ATextComposer(
    state: TextEditorState,
    voiceMessageState: VoiceMessageState,
    composerMode: MessageComposerMode,
    showTextFormatting: Boolean = false,
) {
    TextComposer(
        state = state,
        showTextFormatting = showTextFormatting,
        voiceMessageState = voiceMessageState,
        composerMode = composerMode,
        onRequestFocus = {},
        onSendMessage = {},
        onResetComposerMode = {},
        onAddAttachment = {},
        onDismissTextFormatting = {},
        onVoiceRecorderEvent = {},
        onVoicePlayerEvent = {},
        onSendVoiceMessage = {},
        onDeleteVoiceMessage = {},
        onError = {},
        onTyping = {},
        onReceiveSuggestion = {},
        resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
        resolveAtRoomMentionDisplay = { TextDisplay.Plain },
        onSelectRichContent = null,
    )
}

fun aMessageComposerModeEdit(
    eventOrTransactionId: EventOrTransactionId = EventId("$1234").toEventOrTransactionId(),
    content: String = "Some text",
) = MessageComposerMode.Edit(
    eventOrTransactionId = eventOrTransactionId,
    content = content
)

fun aMessageComposerModeEditCaption(
    eventOrTransactionId: EventOrTransactionId = EventId("$1234").toEventOrTransactionId(),
    content: String,
) = MessageComposerMode.EditCaption(
    eventOrTransactionId = eventOrTransactionId,
    content = content,
)

fun aMessageComposerModeReply(
    replyToDetails: InReplyToDetails,
    hideImage: Boolean = false,
) = MessageComposerMode.Reply(
    replyToDetails = replyToDetails,
    hideImage = hideImage,
)
