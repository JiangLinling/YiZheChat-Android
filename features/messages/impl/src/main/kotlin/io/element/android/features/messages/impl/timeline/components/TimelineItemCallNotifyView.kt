/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.components.receipt.ReadReceiptViewState
import io.element.android.features.messages.impl.timeline.components.receipt.TimelineItemDeliveryIndicator
import io.element.android.features.messages.impl.timeline.components.receipt.aReadReceiptData
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.RtcNotificationState
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun TimelineItemCallNotifyView(
    timelineRoomInfo: TimelineRoomInfo,
    event: TimelineItem.Event,
    content: TimelineItemRtcNotificationContent,
    renderReadReceipts: Boolean,
    isLastOutgoingMessage: Boolean,
    onClick: (TimelineItem.Event) -> Unit,
    onLongClick: (TimelineItem.Event) -> Unit,
    onReadReceiptsClick: (TimelineItem.Event) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            contentAlignment = if (event.isMine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val bubbleShape = RoundedCornerShape(12.dp)
            val readReceiptViewState = ReadReceiptViewState(
                sendState = event.localSendState,
                isMine = event.isMine,
                isLastOutgoingMessage = isLastOutgoingMessage,
                receipts = event.readReceiptState.receipts,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(CALL_AVATAR_BUBBLE_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!event.isMine) CallSenderAvatar(event)
                if (event.isMine) {
                    TimelineItemDeliveryIndicator(
                        state = readReceiptViewState,
                        renderReadReceipts = renderReadReceipts,
                        onFailureClick = { onLongClick(event) },
                        onReadReceiptsClick = { onReadReceiptsClick(event) },
                    )
                }
                Row(
                    modifier = Modifier
                        .testTag(TestTags.messageBubble)
                        .widthIn(max = CALL_BUBBLE_MAX_WIDTH)
                        .clip(bubbleShape)
                        .background(MessageEventBubbleDefaults.backgroundBubbleColor(event.isMine))
                        .combinedClickable(
                            enabled = true,
                            onClick = { onClick(event) },
                            onLongClick = { onLongClick(event) },
                            onLongClickLabel = stringResource(CommonStrings.action_open_context_menu),
                        )
                        .onKeyboardContextMenuAction { onLongClick(event) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(20.sp.toDp()),
                        imageVector = getIcon(timelineRoomInfo, content),
                        contentDescription = null,
                        tint = ElementTheme.colors.iconSecondary,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = getCallTitle(content),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        getCallDetail(timelineRoomInfo, content)?.let { detail ->
                            Text(
                                text = detail,
                                style = ElementTheme.typography.fontBodyXsRegular,
                                color = ElementTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (event.isMine) CallSenderAvatar(event)
            }
        }
    }
}

@Composable
private fun CallSenderAvatar(event: TimelineItem.Event) {
    Avatar(
        avatarData = event.senderAvatar,
        avatarType = AvatarType.User,
        modifier = Modifier
            .size(AvatarSize.TimelineSender.dp)
            .clip(CircleShape),
    )
}

@Composable
private fun getCallTitle(content: TimelineItemRtcNotificationContent): String = stringResource(
    if (content.callIntent == CallIntent.AUDIO) R.string.screen_room_call_type_audio else R.string.screen_room_call_type_video
)

@Composable
private fun getCallDetail(
    timelineRoomInfo: TimelineRoomInfo,
    content: TimelineItemRtcNotificationContent,
): String? {
    val state = if (!timelineRoomInfo.isDm && content.state !is RtcNotificationState.Started) {
        RtcNotificationState.Started
    } else {
        content.state
    }
    return when (state) {
        is RtcNotificationState.Declined -> stringResource(
            requireNotNull(state.terminalDetailResource(timelineRoomInfo.isDm))
        )
        is RtcNotificationState.Cancelled -> stringResource(
            requireNotNull(state.terminalDetailResource(timelineRoomInfo.isDm))
        )
        is RtcNotificationState.Missed -> stringResource(
            requireNotNull(state.terminalDetailResource(timelineRoomInfo.isDm))
        )
        is RtcNotificationState.Ended -> state.durationMillis?.let {
            stringResource(R.string.screen_room_call_detail_duration, formatCallDuration(it))
        } ?: stringResource(R.string.screen_room_call_detail_ended)
        RtcNotificationState.Connected -> stringResource(R.string.screen_room_call_detail_connected)
        RtcNotificationState.Started -> null
    }
}

@Composable
private fun getIcon(
    timelineRoomInfo: TimelineRoomInfo,
    content: TimelineItemRtcNotificationContent
): ImageVector {
    val showAsDeclined = timelineRoomInfo.isDm && when (content.state) {
        is RtcNotificationState.Declined,
        is RtcNotificationState.Cancelled,
        is RtcNotificationState.Missed -> true
        RtcNotificationState.Connected,
        is RtcNotificationState.Ended,
        RtcNotificationState.Started -> false
    }
    val icon = if (showAsDeclined) {
        if (content.callIntent == CallIntent.AUDIO) CompoundIcons.VoiceCallDeclinedSolid() else CompoundIcons.VideoCallDeclinedSolid()
    } else {
        if (content.callIntent == CallIntent.AUDIO) CompoundIcons.VoiceCallSolid() else CompoundIcons.VideoCallSolid()
    }
    return icon
}

@PreviewsDayNight
@Composable
internal fun TimelineItemCallNotifyViewPreview() = ElementPreview {
    val readReceiptState = aTimelineItemReadReceipts(
        receipts = List(3) { aReadReceiptData(it) },
    )
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        listOf(false, true).forEach { isMine ->
            listOf(false, true).forEach { isDm ->
                listOf(CallIntent.AUDIO, CallIntent.VIDEO).forEach { callIntent ->
                    listOf(
                        RtcNotificationState.Started,
                        RtcNotificationState.Declined(byMe = false),
                        RtcNotificationState.Declined(byMe = true),
                        RtcNotificationState.Cancelled(byMe = true),
                        RtcNotificationState.Missed(byMe = true),
                        RtcNotificationState.Connected,
                        RtcNotificationState.Ended(durationMillis = 65_000L),
                    ).forEach { state ->
                        val content = TimelineItemRtcNotificationContent(callIntent, state)
                        TimelineItemCallNotifyView(
                            timelineRoomInfo = aTimelineRoomInfo(isDm = isDm),
                            event = aTimelineItemEvent(
                                isMine = isMine,
                                senderDisplayName = "Alice",
                                content = content,
                                readReceiptState = readReceiptState,
                            ),
                            content = content,
                            renderReadReceipts = false,
                            isLastOutgoingMessage = false,
                            onClick = {},
                            onLongClick = {},
                            onReadReceiptsClick = {},
                        )
                    }
                }
            }
        }
    }
}

private val CALL_BUBBLE_MAX_WIDTH = 300.dp
private val CALL_AVATAR_BUBBLE_GAP = 8.dp

private fun formatCallDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
