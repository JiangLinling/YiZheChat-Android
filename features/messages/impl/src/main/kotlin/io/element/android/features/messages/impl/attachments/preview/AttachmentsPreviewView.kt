/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.error.sendAttachmentError
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorView
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorEvent
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoUploadEstimation
import io.element.android.libraries.androidutils.media.runAndRelease
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.ProgressDialogType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.dialogs.RetryDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.TextComposer
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.formatter.rememberFileSizeFormatter
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Ref: https://www.figma.com/design/zftpgS6LjiczobJZ1GUNpt/Updates-to-Media---File-Upload?node-id=51-3514
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsPreviewView(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    officePreviewAccountId: String? = null,
    officePreviewDeviceId: String? = null,
    modifier: Modifier = Modifier,
    onReturnToGallery: (() -> Unit)? = null,
) {
    val snackbarMessage by LocalSnackbarDispatcher.current.collectSnackbarMessageAsState()
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage)
    var showCaption by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val canShowEditAction = when (state.sendActionState) {
        is SendActionState.Sending.Uploading -> false
        is SendActionState.Sending.Processing -> !state.sendActionState.displayProgress
        SendActionState.Done -> false
        else -> true
    }

    fun postSendAttachment() {
        if (state.selectedIndices.isEmpty() && (state.attachment as Attachment.Media).fromGallery && state.focusedIndex !in state.blockedIndices) {
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(state.focusedIndex))
        }
        state.eventSink(AttachmentsPreviewEvent.SendAttachment)
    }

    fun postCancel() {
        if (onReturnToGallery != null && (state.attachment as Attachment.Media).fromGallery && !state.attachment.fromRecentImages) {
            onReturnToGallery()
        } else {
            state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
        }
    }

    fun postClearSendState() {
        state.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
    }

    fun postOpenImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
    }

    fun postCloseImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.CloseImageEditor)
    }

    fun postResetImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.ResetImageEdits)
    }

    fun postApplyImageEdits() {
        state.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)
    }

    BackHandler(enabled = state.sendActionState !is SendActionState.Sending.Uploading && state.sendActionState !is SendActionState.Done) {
        if (state.videoEditorOpen) {
            state.eventSink(AttachmentsPreviewEvent.CloseVideoEditor)
        } else if (state.imageEditorState != null) {
            postCloseImageEditor()
        } else {
            postCancel()
        }
    }

    if (state.videoEditorOpen) {
        VideoEditorView(
            state.attachment as Attachment.Media,
            onCancel = { state.eventSink(AttachmentsPreviewEvent.CloseVideoEditor) },
            onDone = { start, end, mute -> state.eventSink(AttachmentsPreviewEvent.ApplyVideoEdits(start, end, mute)) },
            modifier = modifier
        )
    } else if (state.imageEditorState != null) {
        AttachmentImageEditorView(
            state = state.imageEditorState,
            onCropRectChange = { cropRect ->
                state.eventSink(AttachmentsPreviewEvent.UpdateImageCropRect(cropRect))
            },
            onRotateClick = { state.eventSink(AttachmentsPreviewEvent.RotateImageToTheLeft) },
            onFlipHorizontallyClick = { state.eventSink(AttachmentsPreviewEvent.FlipImageHorizontally) },
            onFlipVerticallyClick = { state.eventSink(AttachmentsPreviewEvent.FlipImageVertically) },
            onCancelClick = ::postCloseImageEditor,
            onResetClick = ::postResetImageEditor,
            onDoneClick = ::postApplyImageEdits,
            modifier = modifier,
            onAnnotation = { state.eventSink(AttachmentsPreviewEvent.AddImageAnnotation(it)) },
            onReplaceAnnotations = { state.eventSink(AttachmentsPreviewEvent.ReplaceImageAnnotations(it)) },
            onUndo = { state.eventSink(AttachmentsPreviewEvent.UndoImageEdit) },
            onRedo = { state.eventSink(AttachmentsPreviewEvent.RedoImageEdit) },
        )
    } else {
        Scaffold(
            modifier = modifier,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                androidx.compose.material3.CenterAlignedTopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color(0xff303030), titleContentColor = Color.White),
                    navigationIcon = {
                        BackButton(
                            onClick = ::postCancel,
                        )
                    },
                    title = {
                        Text(
                            modifier = Modifier.semantics {
                                heading()
                            },
                            text = if ((state.attachment as Attachment.Media).fromGallery) {
                                "${state.focusedIndex + 1}/${state.attachments.size}"
                            } else {
                                stringResource(
                                    R.string.screen_media_upload_preview_title
                                )
                            },
                            style = ElementTheme.typography.fontBodyLgRegular.copy(fontSize = 18.sp),
                        )
                    },
                    actions = {
                        val selected = state.focusedIndex in state.selectedIndices
                        Row(
                            Modifier.toggleable(
                                value = selected,
                                role = Role.Checkbox,
                                onValueChange = { state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(state.focusedIndex)) }
                            )
                            .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaChoiceCircle(selected)
                            Text(stringResource(R.string.media_select), Modifier.padding(start = 6.dp), color = Color.White)
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(CompoundIcons.OverflowVertical(), stringResource(R.string.media_more)) }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.media_confirm_submission)) }, onClick = {
                                    moreMenu = false
                                    state.eventSink(AttachmentsPreviewEvent.ShowRecoveryDetails)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.media_add_caption)) }, onClick = {
                                    showCaption = !showCaption
                                    moreMenu = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.media_exit_selection)) }, onClick = {
                                    moreMenu = false
                                    state.eventSink(AttachmentsPreviewEvent.DismissPreservingDraft)
                                })
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            AttachmentPreviewContent(
                modifier = Modifier.padding(paddingValues),
                state = state,
                localMediaRenderer = localMediaRenderer,
                officePreviewAccountId = officePreviewAccountId,
                officePreviewDeviceId = officePreviewDeviceId,
                onSendClick = ::postSendAttachment,
                onEditClick = if (state.canEditImage) {
                    ::postOpenImageEditor
                } else {
                    { state.eventSink(AttachmentsPreviewEvent.OpenVideoEditor) }
                },
                canEdit = state.uncertainIndices.isEmpty() && state.focusedIndex !in state.blockedIndices && canShowEditAction && (state.canEditImage || (state.attachment as Attachment.Media).localMedia.info.mimeType.isMimeTypeVideo()),
                showCaption = showCaption || state.isBurnAfterRead || !(state.attachment as Attachment.Media).fromGallery,
            )
        }
    }
    if (state.recoveryDetailsVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.eventSink(AttachmentsPreviewEvent.DismissRecoveryDetails) },
            title = { Text(stringResource(R.string.media_confirm_submission)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when {
                        !state.draftReady -> R.string.media_recovery_loading
                        state.draftError -> R.string.media_draft_error
                        state.focusedIndex in state.discardedIndices -> R.string.media_recovery_discarded
                        state.focusedIndex in state.cancellationRequestedIndices -> R.string.media_recovery_cancellation_pending
                        state.focusedIndex in state.acceptedIndices -> R.string.media_recovery_accepted
                        state.focusedIndex in state.failedIndices -> R.string.media_send_recovered_failure
                        else -> R.string.media_confirmation_pending
                    }))
                    if (state.checkingRecovery) Text(stringResource(R.string.media_recovery_checking))
                    state.recoveryChecks.firstOrNull { it.index == state.focusedIndex }?.let { checked ->
                        Text(stringResource(when (checked.submission) {
                            MediaSubmissionCheck.MISSING_IDENTITY -> R.string.media_recovery_missing_identity
                            MediaSubmissionCheck.ACCEPTED -> R.string.media_recovery_accepted
                            MediaSubmissionCheck.CANCEL_PENDING -> R.string.media_recovery_cancellation_pending
                            MediaSubmissionCheck.DISCARDED -> R.string.media_recovery_discarded
                            else -> R.string.media_confirmation_pending
                        }))
                        val operation = checked.operation
                        Text(stringResource(when {
                            operation == null -> R.string.media_recovery_authority_unavailable
                            operation.terminal -> R.string.media_recovery_task_terminal
                            operation.deadlinePassed -> R.string.media_recovery_task_expired
                            else -> R.string.media_recovery_task_pending
                        }))
                        if (operation != null && !operation.terminal) {
                            Text(stringResource(R.string.media_recovery_required_pending, operation.pendingRequiredSteps))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(stringResource(R.string.media_confirm_submission), enabled = state.draftReady && !state.draftError && !state.checkingRecovery,
                    onClick = { state.eventSink(AttachmentsPreviewEvent.ConfirmSubmission) })
            },
            dismissButton = { TextButton(stringResource(CommonStrings.action_close), onClick = {
                state.eventSink(AttachmentsPreviewEvent.DismissRecoveryDetails)
            }) },
        )
    }
    if (state.draftError) {
        AlertDialog(
            title = stringResource(CommonStrings.common_error),
            content = stringResource(R.string.media_draft_error),
        onDismiss = { state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss) }
        )
    }
    AttachmentSendStateView(
        sendActionState = state.sendActionState,
        displaySendProgressInline = state.displaySendProgressInline,
        isApplyingImageEdits = state.isApplyingImageEdits,
        displayImageEditError = state.displayImageEditError,
        onDismissImageEditError = { state.eventSink(AttachmentsPreviewEvent.ClearImageEditError) },
        onDismissClick = ::postClearSendState,
        onRetryClick = { state.eventSink(AttachmentsPreviewEvent.RetryAttachment) }
    )
}

@Composable
private fun MediaChoiceCircle(selected: Boolean) {
    Box(
        Modifier.size(22.dp).border(1.5.dp, if (selected) Color(0xff07c160) else Color.White, CircleShape)
        .background(if (selected) Color(0xff07c160) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = Color.White)
    }
}

@Composable
private fun AttachmentSendStateView(
    sendActionState: SendActionState,
    displaySendProgressInline: Boolean,
    isApplyingImageEdits: Boolean,
    displayImageEditError: Boolean,
    onDismissImageEditError: () -> Unit,
    onDismissClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    when {
        isApplyingImageEdits -> {
            ProgressDialog(
                type = ProgressDialogType.Indeterminate,
                text = stringResource(CommonStrings.common_preparing),
                showCancelButton = false,
                onDismissRequest = {},
            )
        }
        displayImageEditError -> {
            AlertDialog(
                title = stringResource(CommonStrings.common_error),
                content = stringResource(CommonStrings.common_something_went_wrong_message),
                onDismiss = onDismissImageEditError,
            )
        }
        else -> when (sendActionState) {
            is SendActionState.Sending.Processing -> {
                if (sendActionState.displayProgress && !displaySendProgressInline) {
                    ProgressDialog(
                        type = ProgressDialogType.Indeterminate,
                        text = stringResource(CommonStrings.common_preparing),
                        showCancelButton = true,
                        onDismissRequest = onDismissClick,
                    )
                }
            }
            is SendActionState.Sending.Uploading -> {
                if (!displaySendProgressInline) {
                    val progress = sendActionState.progress
                    ProgressDialog(
                        type = progress?.let(ProgressDialogType::Determinate) ?: ProgressDialogType.Indeterminate,
                        text = buildString {
                            append(stringResource(id = CommonStrings.common_sending))
                            if (progress != null) append(" ${(progress * 100).toInt()}%")
                        },
                        showCancelButton = true,
                        onDismissRequest = onDismissClick,
                    )
                }
            }
            is SendActionState.Failure -> {
                RetryDialog(
                    content = stringResource(sendAttachmentError(sendActionState.error)),
                    onDismiss = onDismissClick,
                    onRetry = onRetryClick
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun AttachmentPreviewContent(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    officePreviewAccountId: String?,
    officePreviewDeviceId: String?,
    onSendClick: () -> Unit,
    onEditClick: () -> Unit,
    canEdit: Boolean,
    showCaption: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val pager = rememberPagerState(initialPage = state.focusedIndex, pageCount = { state.attachments.size })
            LaunchedEffect(state.focusedIndex) { if (pager.currentPage != state.focusedIndex) pager.scrollToPage(state.focusedIndex) }
            LaunchedEffect(pager.settledPage) { state.eventSink(AttachmentsPreviewEvent.FocusAttachment(pager.settledPage)) }
            HorizontalPager(state = pager, key = { (state.attachments[it] as Attachment.Media).localMedia.lifecycleOperationId }) { index ->
                val attachment = state.attachments[index] as Attachment.Media
                val previewState = state.filePreviewStates[index]
                if (previewState != null && previewState != AttachmentFilePreviewState.Ready) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (previewState == AttachmentFilePreviewState.Preparing) CircularProgressIndicator()
                        Text(stringResource(when (previewState) {
                            AttachmentFilePreviewState.Preparing -> R.string.screen_attachment_preview_preparing
                            AttachmentFilePreviewState.Failed -> R.string.screen_attachment_preview_failed
                            AttachmentFilePreviewState.Revoked -> R.string.screen_attachment_preview_revoked
                            else -> R.string.screen_attachment_preview_closed
                        }), modifier = Modifier.padding(16.dp))
                    }
                } else if (attachment.localMedia.info.mimeType.isMimeTypeVideo()) {
                    EditableVideoPlayer(
                        attachment.localMedia.uri,
                    attachment.videoStartMs,
                        attachment.videoEndMs,
                        attachment.muteAudio,
                        Modifier.fillMaxSize()
                    )
                } else {
                    localMediaRenderer.Render(
                        attachment.localMedia,
                        accountId = officePreviewAccountId,
                        deviceId = officePreviewDeviceId,
                    renderMode = LocalMediaRenderMode.EMBEDDED
                    )
                }
            }
        }
        if ((state.uncertainIndices.isNotEmpty() || state.blockedIndices.isNotEmpty()) &&
            state.sendActionState !is SendActionState.Sending) {
                Text(stringResource(when {
                    state.focusedIndex in state.discardedIndices -> R.string.media_recovery_discarded
                    state.focusedIndex in state.cancellationRequestedIndices -> R.string.media_recovery_cancellation_pending
                    state.focusedIndex in state.acceptedIndices -> R.string.media_recovery_accepted
                    else -> R.string.media_confirmation_pending
                }),
                    Modifier.padding(12.dp))
                TextButton(stringResource(R.string.media_confirm_submission), onClick = {
                    state.eventSink(AttachmentsPreviewEvent.ConfirmSubmission)
                })
            }
        if (state.failedIndices.isNotEmpty() && state.sendActionState !is SendActionState.Sending) {
            Text(stringResource(R.string.media_send_recovered_failure), Modifier.padding(12.dp))
            TextButton(stringResource(CommonStrings.action_retry), onClick = { state.eventSink(AttachmentsPreviewEvent.RetryAttachment) })
        }
        LazyRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(
                state.attachments,
                key = { index, _ -> "$index:${(state.sourceAttachments[index] as Attachment.Media).localMedia.lifecycleOperationId}" }
            ) { index, item ->
                Box(
                    Modifier.size(64.dp).border(if (index ==
                    state.focusedIndex) {
                        3.dp
                    } else {
                        1.dp
                    }, if (index == state.focusedIndex) Color(0xff07c160) else Color.Gray)
                    .clickable { state.eventSink(AttachmentsPreviewEvent.FocusAttachment(index)) }
                ) {
                    AttachmentThumbnail((item as Attachment.Media).localMedia, Modifier.fillMaxSize())
                    Text(
                        if (index in state.selectedIndices) "${state.selectedIndices.indexOf(index) + 1}" else "○",
                        Modifier.align(
                            Alignment.TopEnd
                        ).background(Color.Black.copy(alpha = .6f)).padding(6.dp)
                    )
                }
            }
        }
        val mediaInfo = (state.attachment as? Attachment.Media)?.localMedia?.info
        val formatter = rememberFileSizeFormatter()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mediaInfo?.isImageAttachment() == true) {
                // Original-photo choice sits beside Edit and Send in the bottom toolbar.
            } else if (mediaInfo?.mimeType?.isMimeTypeVideo() == true) {
                listOf(
                    VideoCompressionPreset.STANDARD to R.string.media_standard,
                    VideoCompressionPreset.HIGH to R.string.media_high
                ).forEach { (preset, label) ->
                    TextButton(stringResource(label) + if (state.videoQuality ==
                        preset) {
                            " ✓"
                        } else {
                            ""
                        }, onClick = { state.eventSink(AttachmentsPreviewEvent.SetVideoQuality(preset)) })
                }
                val item = state.attachment as Attachment.Media
                val context = androidx.compose.ui.platform.LocalContext.current
                val duration by androidx.compose.runtime.produceState<Long?>(null, item.localMedia.uri) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            android.media.MediaMetadataRetriever().runAndRelease {
                            setDataSource(context, item.localMedia.uri)
                            extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                        }
                        }.getOrNull()
                    }
                }
                state.mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()?.firstOrNull { it.preset == state.videoQuality }?.let {
                    Text(stringResource(R.string.media_estimated_size, formatter.format(if (duration != null && duration!! > 0) {
                        val end = minOf(item.videoEndMs ?: duration!!, duration!!)
                        (it.sizeInBytes * ((end - item.videoStartMs).coerceAtLeast(0).toDouble() / duration!!)).toLong()
                    } else {
                        it.sizeInBytes
                    }, true)), Modifier.weight(1f))
                }
            }
        }
        val sizeFormatter = rememberFileSizeFormatter()
        if (state.displayFileTooLargeError) {
            val maxFileUploadSize = state.mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
            if (maxFileUploadSize != null) {
                val content = stringResource(CommonStrings.dialog_file_too_large_to_upload_subtitle, sizeFormatter.format(maxFileUploadSize, true))
                AlertDialog(
                    title = stringResource(CommonStrings.dialog_file_too_large_to_upload_title),
                    content = content,
                    onDismiss = { state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss) },
                )
            }
        }

                if ((state.attachment as Attachment.Media).fromGallery &&
                    !state.isBurnAfterRead) {
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xff303030)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(stringResource(CommonStrings.action_edit), enabled = canEdit, onClick = onEditClick)
            if (mediaInfo?.isImageAttachment() == true) {
                Row(
                    Modifier.toggleable(
                        value = state.originalPhotos,
                        role = Role.Checkbox,
                        onValueChange = { state.eventSink(AttachmentsPreviewEvent.SetOriginalPhotos(it)) }
                    )
                    .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaChoiceCircle(state.originalPhotos)
                    Text(
                        stringResource(R.string.media_original_photos),
                        Modifier.padding(start = 6.dp),
                        color = Color.White,
                        style = ElementTheme.typography.fontBodyLgRegular.copy(fontSize = 18.sp)
                    )
                }
            }
            Button(
                onClick = onSendClick,
                enabled =
                    state.draftReady && !state.draftError && (state.selectedIndices.any { it !in state.blockedIndices } ||
                        ((state.attachment as Attachment.Media).fromGallery && state.focusedIndex !in state.blockedIndices)) &&
                        !state.isApplyingImageEdits &&
                    state.sendActionState !is SendActionState.Sending.Uploading,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xff07c160), contentColor = Color.White)
            ) {
                Text(
                    if (state.selectedIndices.isEmpty()) {
                        stringResource(
                            CommonStrings.action_send
                        )
                    } else {
                        stringResource(R.string.media_send_count, state.selectedIndices.size)
                    },
                    color = Color.White,
                    style = ElementTheme.typography.fontBodyLgRegular.copy(fontSize = 18.sp)
                )
            }
        }
                    }
        if (showCaption) {
            AttachmentsPreviewBottomActions(
            state = state,
            onSendClick = onSendClick,
            modifier = Modifier
                .fillMaxWidth()
                .background(ElementTheme.colors.bgCanvasDefault)
                .height(IntrinsicSize.Min)
                .imePadding(),
        )
        }
    }
}

@Composable
private fun ImageOptimizationSelector(state: MediaOptimizationSelectorState) {
    if (state.displayMediaSelectorViews == true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .niceClickable {
                    state.isImageOptimizationEnabled?.let { value ->
                        state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(!value))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                text = stringResource(R.string.screen_media_upload_preview_optimize_image_quality_title),
                style = ElementTheme.typography.fontBodyLgRegular,
            )
            Switch(
                modifier = Modifier.height(32.dp),
                checked = state.isImageOptimizationEnabled.orFalse(),
                onCheckedChange = { value -> state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(value)) },
            )
        }
    }
}

@Composable
private fun VideoPresetSelector(
    state: MediaOptimizationSelectorState,
) {
    val videoPresets = state.videoSizeEstimations.dataOrNull()
    var selectedPreset by remember(state.selectedVideoPreset) { mutableStateOf(state.selectedVideoPreset) }

    val displayDialog = state.displayVideoPresetSelectorDialog

    val sizeFormatter = rememberFileSizeFormatter()

    if (state.displayMediaSelectorViews == true && videoPresets != null && state.selectedVideoPreset != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .niceClickable { state.eventSink(MediaOptimizationSelectorEvent.OpenVideoPresetSelectorDialog) }
        ) {
            val estimation = videoPresets.find { it.preset == selectedPreset }
            val estimationMb = estimation?.sizeInBytes?.let { sizeFormatter.format(it, true) }
            val title = buildString {
                append(state.selectedVideoPreset.title())
                if (estimationMb != null) {
                    append(" ($estimationMb)")
                }
            }
            Text(text = title, style = ElementTheme.typography.fontBodyLgMedium)
            Text(
                text = stringResource(R.string.screen_media_upload_preview_change_video_quality_prompt),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }

    if (displayDialog) {
        VideoQualitySelectorDialog(
            selectedPreset = selectedPreset ?: VideoCompressionPreset.STANDARD,
            videoSizeEstimations = videoPresets ?: persistentListOf(),
            maxFileUploadSize = state.maxUploadSize.dataOrNull(),
            onSubmit = { preset ->
                selectedPreset = preset
                state.eventSink(MediaOptimizationSelectorEvent.SelectVideoPreset(preset))
            },
            onDismiss = { state.eventSink(MediaOptimizationSelectorEvent.DismissVideoPresetSelectorDialog) }
        )
    }
}

@Composable
private fun VideoQualitySelectorDialog(
    selectedPreset: VideoCompressionPreset,
    videoSizeEstimations: ImmutableList<VideoUploadEstimation>,
    maxFileUploadSize: Long?,
    onSubmit: (VideoCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeFormatter = rememberFileSizeFormatter()

    var localSelectedPreset by remember(selectedPreset) { mutableStateOf(selectedPreset) }
    val subtitlePartNoFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_no_file_size)
    val subtitlePartWithFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_file_size)
    val subtitle = remember(maxFileUploadSize) {
        buildString {
            append(subtitlePartNoFileSize)
            if (maxFileUploadSize != null) {
                append(String.format(subtitlePartWithFileSize, sizeFormatter.format(maxFileUploadSize, true)))
            }
        }
    }
    ListDialog(
        title = stringResource(CommonStrings.dialog_video_quality_selector_title),
        subtitle = subtitle,
        onSubmit = { onSubmit(localSelectedPreset) },
        onDismissRequest = onDismiss,
        applyPaddingToContents = false,
    ) {
        for (videoEstimation in videoSizeEstimations) {
            val preset = videoEstimation.preset
            val isSelected = preset == localSelectedPreset
            item(
                key = preset,
                contentType = preset,
            ) {
                val estimationMb = sizeFormatter.format(videoEstimation.sizeInBytes, true)
                val title = "${preset.title()} ($estimationMb)"
                ListItem(
                    headlineContent = {
                        Text(
                            text = title,
                            style = ElementTheme.typography.fontBodyLgMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = preset.subtitle(),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    },
                    leadingContent = ListItemContent.RadioButton(
                        selected = isSelected,
                    ),
                    onClick = {
                        localSelectedPreset = preset
                    },
                    enabled = videoEstimation.canUpload,
                )
            }
        }
    }
}

@Composable
private fun AttachmentsPreviewBottomActions(
    state: AttachmentsPreviewState,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextComposer(
        modifier = modifier,
        state = state.textEditorState,
        voiceMessageState = VoiceMessageState.Idle,
        composerMode = MessageComposerMode.Attachment,
        attachmentSendLabel = stringResource(R.string.media_send_count, state.selectedIndices.size),
        attachmentSendEnabled =
            state.draftReady && !state.draftError && (state.selectedIndices.any { it !in state.blockedIndices } ||
                        ((state.attachment as Attachment.Media).fromGallery && state.focusedIndex !in state.blockedIndices)) &&
                !state.isApplyingImageEdits &&
            state.sendActionState !is SendActionState.Sending.Uploading,
        onRequestFocus = {},
        onSendMessage = onSendClick,
        showTextFormatting = false,
        onResetComposerMode = {},
        onAddAttachment = {},
        onDismissTextFormatting = {},
        onVoiceRecorderEvent = {},
        onVoicePlayerEvent = {},
        onSendVoiceMessage = {},
        onDeleteVoiceMessage = {},
        onReceiveSuggestion = {},
        resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
        resolveAtRoomMentionDisplay = { TextDisplay.Plain },
        onError = {},
        onTyping = {},
        onSelectRichContent = {},
        onSendFlashReadMessage = if (state.isBurnAfterRead) {
            null
        } else {
            { state.eventSink(AttachmentsPreviewEvent.SendFlashReadAttachment) }
        },
        flashReadLabel = stringResource(R.string.screen_room_burn_after_read_mode),
        flashReadSlideHint = stringResource(R.string.screen_room_flash_read_slide_hint),
        flashReadReleaseHint = stringResource(R.string.screen_room_flash_read_release_hint),
    )
}

// Only preview in dark, dark theme is forced on the Node.
@Preview
@Composable
internal fun AttachmentsPreviewViewPreview(@PreviewParameter(AttachmentsPreviewStateProvider::class) state: AttachmentsPreviewState) = ElementPreviewDark {
    AttachmentsPreviewView(
        state = state,
        localMediaRenderer = object : LocalMediaRenderer {
            @Composable
            override fun Render(
                localMedia: LocalMedia,
                accountId: String?,
                deviceId: String?,
                renderMode: LocalMediaRenderMode,
            ) {
                Image(
                    painter = painterResource(id = CommonDrawables.sample_background),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                )
            }
        }
    )
}

@PreviewsDayNight
@Composable
internal fun VideoQualitySelectorDialogPreview() {
    ElementPreview {
        VideoQualitySelectorDialog(
            selectedPreset = VideoCompressionPreset.STANDARD,
            videoSizeEstimations = persistentListOf(
                VideoUploadEstimation(VideoCompressionPreset.HIGH, 2_000_000, canUpload = false),
                VideoUploadEstimation(VideoCompressionPreset.STANDARD, 1_000_000, canUpload = true),
                VideoUploadEstimation(VideoCompressionPreset.LOW, 500_000, canUpload = true)
            ),
            maxFileUploadSize = 1_500_000,
            onSubmit = {},
            onDismiss = {},
        )
    }
}

@Composable
fun VideoCompressionPreset.title(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low
        }
    )
}

@Composable
fun VideoCompressionPreset.subtitle(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard_description
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high_description
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low_description
        }
    )
}
