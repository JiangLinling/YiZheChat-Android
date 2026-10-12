/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.roomlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.home.impl.R
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListContextMenu(
    contextMenu: RoomListState.ContextMenu.Shown,
    canReportRoom: Boolean,
    eventSink: (RoomListEvent.ContextMenuEvent) -> Unit,
    onRoomSettingsClick: (roomId: RoomId) -> Unit,
    onReportRoomClick: (roomId: RoomId) -> Unit
) {
    var confirmDelete by remember(contextMenu.roomId) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { eventSink(RoomListEvent.HideContextMenu) },
        scrollable = false,
    ) {
        RoomListModalBottomSheetContent(
            contextMenu = contextMenu,
            canReportRoom = canReportRoom,
            onRoomMarkReadClick = {
                eventSink(RoomListEvent.HideContextMenu)
                eventSink(RoomListEvent.MarkAsRead(contextMenu.roomId))
            },
            onRoomMarkUnreadClick = {
                eventSink(RoomListEvent.HideContextMenu)
                eventSink(RoomListEvent.MarkAsUnread(contextMenu.roomId))
            },
            onFavoriteChange = { isFavorite ->
                eventSink(RoomListEvent.SetRoomIsFavorite(contextMenu.roomId, isFavorite))
            },
            onMutedChange = { isMuted ->
                eventSink(RoomListEvent.SetRoomMuted(contextMenu.roomId, isMuted))
            },
            onDeleteConversationClick = { confirmDelete = true },
        )
    }
    if (confirmDelete) {
        ConfirmationDialog(
            content = stringResource(R.string.screen_roomlist_delete_chat_confirmation),
            submitText = stringResource(R.string.screen_roomlist_delete_chat),
            destructiveSubmit = true,
            onSubmitClick = {
                confirmDelete = false
                eventSink(RoomListEvent.HideContextMenu)
                eventSink(RoomListEvent.DeleteConversation(contextMenu.roomId, contextMenu.latestEventTimestamp))
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RoomListModalBottomSheetContent(
    contextMenu: RoomListState.ContextMenu.Shown,
    @Suppress("UNUSED_PARAMETER") canReportRoom: Boolean,
    onFavoriteChange: (isFavorite: Boolean) -> Unit,
    onMutedChange: (isMuted: Boolean) -> Unit,
    onRoomMarkReadClick: () -> Unit,
    onRoomMarkUnreadClick: () -> Unit,
    onDeleteConversationClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = contextMenu.roomName ?: stringResource(id = CommonStrings.common_no_room_name),
                    style = ElementTheme.typography.fontBodyLgMedium,
                    fontStyle = FontStyle.Italic.takeIf { contextMenu.roomName == null }
                )
            }
        )
        if (contextMenu.hasNewContent) {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(id = R.string.screen_roomlist_mark_as_read),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                onClick = onRoomMarkReadClick,
                leadingContent = ListItemContent.Icon(
                    iconSource = IconSource.Vector(CompoundIcons.MarkAsRead())
                ),
            )
        } else {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(id = R.string.screen_roomlist_mark_as_unread),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                onClick = onRoomMarkUnreadClick,
                leadingContent = ListItemContent.Icon(
                    iconSource = IconSource.Vector(CompoundIcons.MarkAsUnread())
                ),
            )
        }
        val (textResId, icon) = if (contextMenu.isFavorite) {
            R.string.screen_roomlist_unpin to CompoundIcons.PinSolid()
        } else {
            R.string.screen_roomlist_pin to CompoundIcons.Pin()
        }
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(id = textResId),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            leadingContent = ListItemContent.Icon(
                iconSource = IconSource.Vector(
                    icon,
                )
            ),
            trailingContent = ListItemContent.Switch(
                checked = contextMenu.isFavorite,
            ),
            onClick = {
                onFavoriteChange(!contextMenu.isFavorite)
            },
        )
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(
                        if (contextMenu.isMuted) R.string.screen_roomlist_unmute else R.string.screen_roomlist_mute
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            leadingContent = ListItemContent.Icon(
                iconSource = IconSource.Vector(
                    if (contextMenu.isMuted) CompoundIcons.Notifications() else CompoundIcons.NotificationsOff()
                )
            ),
            trailingContent = ListItemContent.Switch(checked = contextMenu.isMuted),
            onClick = { onMutedChange(!contextMenu.isMuted) },
        )
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.screen_roomlist_delete_chat))
            },
            modifier = Modifier.clickable { onDeleteConversationClick() },
            leadingContent = ListItemContent.Icon(
                iconSource = IconSource.Vector(CompoundIcons.Delete())
            ),
            style = ListItemStyle.Destructive,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun RoomListContextMenuPreview(
    @PreviewParameter(RoomListStateContextMenuShownProvider::class) contextMenu: RoomListState.ContextMenu.Shown
) = ElementPreview {
    RoomListContextMenu(
        contextMenu = contextMenu,
        canReportRoom = true,
        onRoomSettingsClick = {},
        onReportRoomClick = {},
        eventSink = {},
    )
}
