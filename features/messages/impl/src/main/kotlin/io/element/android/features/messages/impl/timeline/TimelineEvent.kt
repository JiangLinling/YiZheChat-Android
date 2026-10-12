/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import io.element.android.features.messages.impl.timeline.components.MessageShieldData
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlin.time.Duration

sealed interface TimelineEvent {
    data class OnScrollFinished(val firstIndex: Int, val isUserVisible: Boolean = true) : TimelineEvent
    data class ViewportChanged(val visibleItemIds: List<UniqueId>, val isUserVisible: Boolean = true) : TimelineEvent
    data class FocusOnEvent(val eventId: EventId, val debounce: Duration = Duration.ZERO) : TimelineEvent
    data object ClearFocusRequestState : TimelineEvent
    data object OnFocusEventRender : TimelineEvent
    data object JumpToLive : TimelineEvent
    data object ViewFirstNewMessage : TimelineEvent
    data class NewMessageHintGeometry(val aboveViewport: Boolean, val targetVisible: Boolean, val atLiveEdge: Boolean) : TimelineEvent
    data class NewMessageHintElapsed(val elapsedMillis: Long) : TimelineEvent
    data class AcknowledgePendingMediaPresentation(val displayId: UniqueId) : TimelineEvent

    data object HideShieldDialog : TimelineEvent

    /**
     * Events coming from a timeline item.
     */
    sealed interface TimelineItemEvent : TimelineEvent

    data class ComputeVerifiedUserSendFailure(val event: TimelineItem.Event) : TimelineItemEvent
    data class RetrySend(val event: TimelineItem.Event) : TimelineItemEvent
    data class ShowShieldDialog(val messageShieldData: MessageShieldData) : TimelineItemEvent
    data class LoadMore(val direction: Timeline.PaginationDirection) : TimelineItemEvent
    data class OpenThread(val threadRootEventId: ThreadId, val focusedEvent: EventId?) : TimelineItemEvent
    data class OpenBurnAfterRead(val eventId: EventId) : TimelineItemEvent
    data object DismissBurnAfterReadOpenFailure : TimelineItemEvent
    data object CloseBurnAfterRead : TimelineItemEvent
    data class EndBurnAfterRead(val reason: Timeline.BurnAfterReadEndReason) : TimelineItemEvent
    data class PrepareMessageRemoval(val eventId: EventId) : TimelineItemEvent
    data class CommitMessageRemoval(val eventId: EventId) : TimelineItemEvent
    data class CancelMessageRemoval(val eventId: EventId) : TimelineItemEvent

    /**
     * Navigate to the predecessor or successor room of the current room.
     */
    data class NavigateToPredecessorOrSuccessorRoom(val roomId: RoomId) : TimelineItemEvent

    /**
     * Events coming from a poll item.
     */
    sealed interface TimelineItemPollEvent : TimelineItemEvent

    data class SelectPollAnswer(
        val pollStartId: EventId,
        val answerId: String,
        val onComplete: (Result<Unit>) -> Unit = {},
    ) : TimelineItemPollEvent

    data class EndPoll(
        val pollStartId: EventId,
        val onComplete: (Result<Unit>) -> Unit = {},
    ) : TimelineItemPollEvent

    data class EditPoll(
        val pollStartId: EventId,
    ) : TimelineItemPollEvent

    data class StopLiveLocationShare(val protectedAggregateId: String?) : TimelineItemEvent
}
