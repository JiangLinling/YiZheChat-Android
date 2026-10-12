/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemImageCaptionView
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemImageView
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemVideoCaptionView
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemVideoView
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionEvent
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.wysiwyg.link.Link

@Composable
internal fun TimelineItemMediaAlbumRow(
    timelineItem: TimelineItem.GroupedEvents,
    timelineMode: Timeline.Mode,
    timelineRoomInfo: TimelineRoomInfo,
    timelineProtectionState: TimelineProtectionState,
    renderReadReceipts: Boolean,
    isLastOutgoingMessage: Boolean,
    displayThreadSummaries: Boolean,
    onClick: (TimelineItem.Event) -> Unit,
    onLongClick: (TimelineItem.Event) -> Unit,
    inReplyToClick: (EventId) -> Unit,
    onUserDataClick: (MatrixUser) -> Unit,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onReactionClick: (key: String, TimelineItem.Event) -> Unit,
    onReactionLongClick: (key: String, TimelineItem.Event) -> Unit,
    onMoreReactionsClick: (TimelineItem.Event) -> Unit,
    onReadReceiptClick: (TimelineItem.Event) -> Unit,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val orderedEvents = timelineItem.events.sortedBy { event ->
        event.mediaAlbumIndex()
    }
    val captionEvent = orderedEvents.firstOrNull { it.hasMediaCaption() }
        ?: orderedEvents.first()
    val representative = captionEvent.copy(
        readReceiptState = TimelineItemReadReceipts(timelineItem.aggregatedReadReceipts)
    )

    TimelineItemEventRow(
        event = representative,
        timelineMode = timelineMode,
        timelineRoomInfo = timelineRoomInfo,
        timelineProtectionState = timelineProtectionState,
        renderReadReceipts = renderReadReceipts,
        isLastOutgoingMessage = isLastOutgoingMessage,
        displayThreadSummaries = displayThreadSummaries,
        onEventClick = { onClick(representative) },
        onLongClick = { onLongClick(representative) },
        onLinkClick = onLinkClick,
        onLinkLongClick = onLinkLongClick,
        onUserDataClick = onUserDataClick,
        inReplyToClick = inReplyToClick,
        onReactionClick = onReactionClick,
        onReactionLongClick = onReactionLongClick,
        onMoreReactionsClick = onMoreReactionsClick,
        onReadReceiptClick = onReadReceiptClick,
        onSwipeToReply = { },
        eventSink = eventSink,
        modifier = modifier,
        eventContentView = { contentModifier, onContentLayoutChange ->
            BoxWithConstraints(modifier = contentModifier) {
                val albumWidth = minOf(maxWidth * ALBUM_WIDTH_FRACTION, ALBUM_MAX_WIDTH)
                Column(
                    modifier = Modifier.width(albumWidth),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    orderedEvents.chunked(2).forEach { rowEvents ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowEvents.forEach { event ->
                                val itemModifier = Modifier.weight(1f)
                                when (val content = event.content) {
                                    is TimelineItemImageContent -> TimelineItemImageView(
                                        content = content.copy(
                                            caption = null,
                                            formattedCaption = null,
                                            width = 1,
                                            height = 1,
                                            aspectRatio = 1f,
                                        ),
                                        hideMediaContent = timelineProtectionState.hideMediaContent(event.eventId, event.isMine),
                                        onContentClick = { onClick(event) },
                                        onLongClick = { onLongClick(event) },
                                        onLinkClick = onLinkClick,
                                        onLinkLongClick = onLinkLongClick,
                                        onShowContentClick = {
                                            timelineProtectionState.eventSink(TimelineProtectionEvent.ShowContent(event.eventId))
                                        },
                                        onContentLayoutChange = { },
                                        modifier = itemModifier,
                                        contentScale = ContentScale.Fit,
                                        useFitBackdrop = true,
                                        portraitFrame = false,
                                    )
                                    is TimelineItemVideoContent -> TimelineItemVideoView(
                                        content = content.copy(
                                            caption = null,
                                            formattedCaption = null,
                                            width = 1,
                                            height = 1,
                                            aspectRatio = 1f,
                                        ),
                                        hideMediaContent = timelineProtectionState.hideMediaContent(event.eventId, event.isMine),
                                        onContentClick = { onClick(event) },
                                        onLongClick = { onLongClick(event) },
                                        onLinkClick = onLinkClick,
                                        onLinkLongClick = onLinkLongClick,
                                        onShowContentClick = {
                                            timelineProtectionState.eventSink(TimelineProtectionEvent.ShowContent(event.eventId))
                                        },
                                        onContentLayoutChange = { },
                                        modifier = itemModifier,
                                        contentScale = ContentScale.Fit,
                                        useFitBackdrop = true,
                                        portraitFrame = false,
                                    )
                                    else -> Spacer(modifier = itemModifier)
                                }
                            }
                            if (rowEvents.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    when (val captionContent = captionEvent.content) {
                        is TimelineItemImageContent -> TimelineItemImageCaptionView(
                            content = captionContent,
                            onLinkClick = onLinkClick,
                            onLinkLongClick = onLinkLongClick,
                            onContentLayoutChange = onContentLayoutChange,
                        )
                        is TimelineItemVideoContent -> TimelineItemVideoCaptionView(
                            content = captionContent,
                            onLinkClick = onLinkClick,
                            onLinkLongClick = onLinkLongClick,
                            onContentLayoutChange = onContentLayoutChange,
                        )
                        else -> Unit
                    }
                }
            }
        },
    )
}

private const val ALBUM_WIDTH_FRACTION = 0.72f
private val ALBUM_MAX_WIDTH = 296.dp

private fun TimelineItem.Event.mediaAlbumIndex(): Int? = when (val mediaContent = content) {
    is TimelineItemImageContent -> mediaContent.mediaAlbum?.index
    is TimelineItemVideoContent -> mediaContent.mediaAlbum?.index
    else -> null
}

private fun TimelineItem.Event.hasMediaCaption(): Boolean = when (val mediaContent = content) {
    is TimelineItemImageContent -> mediaContent.showCaption
    is TimelineItemVideoContent -> mediaContent.showCaption
    else -> false
}
