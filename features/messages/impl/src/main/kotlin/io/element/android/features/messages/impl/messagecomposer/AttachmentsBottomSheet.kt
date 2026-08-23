/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.androidutils.ui.hideKeyboard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.compound.theme.ElementTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentsBottomSheet(
    state: MessageComposerState,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    enableTextFormatting: Boolean,
    modifier: Modifier = Modifier,
) {
    val localView = LocalView.current
    var isVisible by rememberSaveable { mutableStateOf(state.showAttachmentSourcePicker) }

    BackHandler(enabled = isVisible) {
        isVisible = false
    }

    LaunchedEffect(state.showAttachmentSourcePicker) {
        isVisible = if (state.showAttachmentSourcePicker) {
            // We need to use this instead of `LocalFocusManager.clearFocus()` to hide the keyboard when focus is on an Android View
            localView.hideKeyboard()
            true
        } else {
            false
        }
    }
    // Send 'DismissAttachmentMenu' event when the bottomsheet was just hidden
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            state.eventSink(MessageComposerEvent.DismissAttachmentMenu)
        }
    }

    if (isVisible) {
        ModalBottomSheet(
            modifier = modifier,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            onDismissRequest = { isVisible = false },
            scrollable = false,
        ) {
            AttachmentSourcePickerMenu(
                state = state,
                enableTextFormatting = enableTextFormatting,
                onSendLocationClick = onSendLocationClick,
                onCreatePollClick = onCreatePollClick,
            )
        }
    }
}

@Composable
private fun AttachmentSourcePickerMenu(
    state: MessageComposerState,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    enableTextFormatting: Boolean,
) {
    val actions = buildList {
        add(AttachmentAction(CompoundIcons.Image(), R.string.screen_room_attachment_source_gallery) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
        })
        add(AttachmentAction(CompoundIcons.TakePhoto(), R.string.screen_room_attachment_source_camera_photo) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
        })
        add(AttachmentAction(CompoundIcons.LocationPin(), R.string.screen_room_attachment_source_location, state.canShareLocation) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.Location)
            onSendLocationClick()
        })
        add(AttachmentAction(CompoundIcons.Attachment(), R.string.screen_room_attachment_source_files) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles)
        })
        add(AttachmentAction(CompoundIcons.VideoCall(), R.string.screen_room_attachment_source_camera_video) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.VideoFromCamera)
        })
        add(AttachmentAction(CompoundIcons.Polls(), R.string.screen_room_attachment_source_poll) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)
            onCreatePollClick()
        })
        if (enableTextFormatting) {
            add(AttachmentAction(CompoundIcons.TextFormatting(), R.string.screen_room_attachment_text_formatting) {
                state.eventSink(MessageComposerEvent.ToggleTextFormatting(enabled = true))
            })
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        userScrollEnabled = false,
    ) {
        items(actions, key = { it.labelRes }) { action ->
            AttachmentGridItem(action = action)
        }
    }
}

private data class AttachmentAction(
    val icon: ImageVector,
    val labelRes: Int,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentGridItem(action: AttachmentAction) {
    Column(
        modifier = Modifier.aspectRatio(0.82f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(60.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = ElementTheme.colors.bgCanvasDefault,
        ) {
            androidx.compose.material3.IconButton(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = stringResource(action.labelRes),
                    tint = if (action.enabled) ElementTheme.colors.iconPrimary else ElementTheme.colors.iconDisabled,
                )
            }
        }
        Text(
            text = stringResource(action.labelRes),
            color = if (action.enabled) ElementTheme.colors.textPrimary else ElementTheme.colors.textDisabled,
            textAlign = TextAlign.Center,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun AttachmentSourcePickerMenuPreview() = ElementPreview {
    AttachmentSourcePickerMenu(
        state = aMessageComposerState(
            canShareLocation = true,
        ),
        onSendLocationClick = {},
        onCreatePollClick = {},
        enableTextFormatting = true,
    )
}
