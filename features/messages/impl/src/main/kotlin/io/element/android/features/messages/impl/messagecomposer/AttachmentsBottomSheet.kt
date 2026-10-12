/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.androidutils.ui.hideKeyboard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun AttachmentsPanel(
    state: MessageComposerState,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    enableTextFormatting: Boolean,
    modifier: Modifier = Modifier,
) {
    val localView = LocalView.current
    BackHandler(enabled = state.showAttachmentSourcePicker) {
        state.eventSink(MessageComposerEvent.DismissAttachmentMenu)
    }

    LaunchedEffect(state.showAttachmentSourcePicker) {
        if (state.showAttachmentSourcePicker) {
            localView.findFocus()?.clearFocus()
            localView.hideKeyboard()
        }
    }

    if (state.showAttachmentSourcePicker) {
        AttachmentSourcePickerMenu(
            state = state,
            enableTextFormatting = enableTextFormatting,
            onSendLocationClick = onSendLocationClick,
            onCreatePollClick = onCreatePollClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun AttachmentSourcePickerMenu(
    state: MessageComposerState,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    enableTextFormatting: Boolean,
    modifier: Modifier = Modifier,
) {
    val actions = buildList {
        add(AttachmentAction(CompoundIcons.Image(), R.string.screen_room_attachment_source_gallery) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
        })
        add(AttachmentAction(CompoundIcons.TakePhoto(), R.string.screen_room_attachment_source_camera_photo) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
        })
        add(AttachmentAction(CompoundIcons.VideoCall(), R.string.screen_room_attachment_source_camera_video) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.VideoFromCamera)
        })
        add(AttachmentAction(CompoundIcons.Attachment(), R.string.screen_room_attachment_source_files) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles)
        })
        if (state.canShareLocation) {
            add(AttachmentAction(CompoundIcons.LocationPin(), R.string.screen_room_attachment_source_location_short) {
                state.eventSink(MessageComposerEvent.PickAttachmentSource.Location)
                onSendLocationClick()
            })
        }
        add(AttachmentAction(CompoundIcons.Polls(), R.string.screen_room_attachment_source_poll) {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)
            onCreatePollClick()
        })
        add(AttachmentAction(CompoundIcons.Restart(), R.string.screen_room_attachment_source_relay) {
            state.eventSink(MessageComposerEvent.StartRelay)
        })
        if (enableTextFormatting) {
            add(AttachmentAction(CompoundIcons.TextFormatting(), R.string.screen_room_attachment_text_formatting) {
                state.eventSink(MessageComposerEvent.ToggleTextFormatting(enabled = true))
            })
        }
    }

    Column(modifier = modifier.fillMaxWidth().heightIn(max = 248.dp).verticalScroll(rememberScrollState())) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            actions.chunked(ATTACHMENT_COLUMN_COUNT).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    rowActions.forEach { action ->
                        AttachmentGridItem(
                            action = action,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(ATTACHMENT_COLUMN_COUNT - rowActions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class AttachmentAction(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentGridItem(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = stringResource(action.labelRes),
                    tint = ElementTheme.colors.iconPrimary,
                )
            }
        }
        Text(
            text = stringResource(action.labelRes),
            color = ElementTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private const val ATTACHMENT_COLUMN_COUNT = 4

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
