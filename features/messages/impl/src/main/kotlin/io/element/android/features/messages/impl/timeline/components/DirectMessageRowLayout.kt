/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemReactions
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState

/**
 * The fixed DM row geometry does not need a constraint solver. Each existing content slot is
 * measured once, with the same available width as before; no message state or gestures live here.
 * Slots stay in the same composition when receipts, pins or reactions change.
 */
@Composable
internal fun DirectMessageRowLayout(
    isMine: Boolean,
    avatar: @Composable () -> Unit,
    bubble: @Composable () -> Unit,
    deliveryIndicator: @Composable () -> Unit,
    pinIcon: @Composable () -> Unit,
    reactions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { avatar() }
            Box { bubble() }
            Box { deliveryIndicator() }
            Box { pinIcon() }
            Box(Modifier.zIndex(1f)) { reactions() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val avatarPlaceable = measurables[0].measure(loose)
        val bubblePlaceable = measurables[1].measure(loose)
        val deliveryPlaceable = measurables[2].measure(loose)
        val pinPlaceable = measurables[3].measure(loose)
        val reactionsPlaceable = measurables[4].measure(loose)
        val outerMargin = 12.dp.roundToPx()
        val avatarGap = 8.dp.roundToPx()
        val deliveryGap = 2.dp.roundToPx()
        val pinGap = 8.dp.roundToPx()
        val reactionsOverlap = 4.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(
            2 * outerMargin + avatarPlaceable.width + avatarGap + bubblePlaceable.width +
                maxOf(deliveryGap + deliveryPlaceable.width, pinGap + pinPlaceable.width)
        )
        val avatarStart = if (isMine) width - outerMargin - avatarPlaceable.width else outerMargin
        val bubbleStart = if (isMine) {
            avatarStart - avatarGap - bubblePlaceable.width
        } else {
            avatarStart + avatarPlaceable.width + avatarGap
        }
        val reactionsTop = bubblePlaceable.height - reactionsOverlap
        val height = constraints.constrainHeight(
            maxOf(avatarPlaceable.height, bubblePlaceable.height, pinPlaceable.height, reactionsTop + reactionsPlaceable.height)
        )
        layout(width, height) {
            avatarPlaceable.placeRelative(avatarStart, 0)
            bubblePlaceable.placeRelative(bubbleStart, 0)
            deliveryPlaceable.placeRelative(
                bubbleStart - deliveryGap - deliveryPlaceable.width,
                bubblePlaceable.height - deliveryPlaceable.height,
            )
            pinPlaceable.placeRelative(
                if (isMine) bubbleStart - pinGap - pinPlaceable.width else bubbleStart + bubblePlaceable.width + pinGap,
                0,
            )
            reactionsPlaceable.placeRelative(
                if (isMine) bubbleStart + bubblePlaceable.width - reactionsPlaceable.width else 0,
                reactionsTop,
            )
        }
    }
}

internal data class DirectMessageRowPreviewState(
    val isMine: Boolean,
    val sendState: LocalEventSendState? = null,
    val decorated: Boolean = false,
)

internal class DirectMessageRowPreviewProvider : PreviewParameterProvider<DirectMessageRowPreviewState> {
    override val values = sequenceOf(
        DirectMessageRowPreviewState(isMine = false),
        DirectMessageRowPreviewState(isMine = true),
        DirectMessageRowPreviewState(isMine = true, sendState = LocalEventSendState.Sending.Event),
        DirectMessageRowPreviewState(isMine = true, sendState = LocalEventSendState.Failed.Unknown("QA"), decorated = true),
    )
}

@PreviewsDayNight
@Composable
internal fun DirectMessageRowLayoutPreview(@PreviewParameter(DirectMessageRowPreviewProvider::class) state: DirectMessageRowPreviewState) = ElementPreview {
    val event = aTimelineItemEvent(
        isMine = state.isMine,
        sendState = state.sendState,
        timelineItemReactions = aTimelineItemReactions(count = if (state.decorated) 2 else 0),
    )
    ATimelineItemEventRow(
        event = event,
        timelineRoomInfo = aTimelineRoomInfo(isDm = true, pinnedEventIds = if (state.decorated) listOfNotNull(event.eventId) else emptyList()),
        renderReadReceipts = true,
        isLastOutgoingMessage = true,
    )
}
