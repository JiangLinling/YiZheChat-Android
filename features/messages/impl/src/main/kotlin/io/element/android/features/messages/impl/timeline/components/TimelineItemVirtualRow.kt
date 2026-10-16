/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineItemReadMarkerView
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineItemRoomBeginningView
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineLoadingMoreIndicator
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemDaySeparatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLastForwardIndicatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemRoomBeginningModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemTypingNotificationModel
import io.element.android.features.messages.impl.typing.TypingNotificationView
import timber.log.Timber

@Composable
fun TimelineItemVirtualRow(
    virtual: TimelineItem.Virtual,
    timelineRoomInfo: TimelineRoomInfo,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (virtual.model) {
            // YiZheChat renders WeChat-style date/time markers immediately above the first event in
            // each time segment. Keep the virtual item for indexing and the floating date badge only.
            is TimelineItemDaySeparatorModel -> Spacer(modifier = Modifier)
            TimelineItemReadMarkerModel -> TimelineItemReadMarkerView()
            TimelineItemRoomBeginningModel -> {
                TimelineItemRoomBeginningView(
                    predecessorRoom = timelineRoomInfo.predecessorRoom,
                    roomName = timelineRoomInfo.name,
                    isDm = timelineRoomInfo.isDm,
                    onPredecessorRoomClick = { roomId ->
                        eventSink(TimelineEvent.NavigateToPredecessorOrSuccessorRoom(roomId))
                    },
                )
            }
            is TimelineItemLoadingIndicatorModel -> {
                val latestEventSink by rememberUpdatedState(eventSink)
                if (virtual.model.lastRequestFailed) {
                    // A failed cache/network read keeps existing messages and the composer alive.
                    // Only an explicit retry starts another request; timestamp updates must not
                    // turn a storage failure into an unbounded native/Compose retry loop.
                    TextButton(
                        text = stringResource(CommonStrings.action_retry),
                        onClick = { latestEventSink(TimelineEvent.LoadMore(virtual.model.direction)) },
                    )
                } else {
                    TimelineLoadingMoreIndicator(virtual.model.direction)
                    LaunchedEffect(virtual.model.timestamp) {
                        Timber.d("Pagination triggered by load more indicator")
                        latestEventSink(TimelineEvent.LoadMore(virtual.model.direction))
                    }
                }
            }
            // Empty model trick to avoid timeline jumping during forward pagination.
            is TimelineItemLastForwardIndicatorModel -> {
                Spacer(modifier = Modifier)
            }
            is TimelineItemTypingNotificationModel -> {
                TypingNotificationView(
                    state = timelineRoomInfo.typingNotificationState,
                )
            }
        }
    }
}
