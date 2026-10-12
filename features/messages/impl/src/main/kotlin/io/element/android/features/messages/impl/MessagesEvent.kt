/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.user.MatrixUser

sealed interface MessagesEvent {
    data class HandleAction(val action: TimelineItemAction, val event: TimelineItem.Event) : MessagesEvent
    data class ToggleReaction(val emoji: String, val eventOrTransactionId: EventOrTransactionId) : MessagesEvent
    data class ForwardEvents(val eventIds: List<EventId>) : MessagesEvent
    data class FavouriteEvents(val eventIds: List<EventId>) : MessagesEvent
    data class DismissFavouriteSnackbar(val messageId: Long) : MessagesEvent
    data class DeleteLocalEvents(val eventIds: List<EventId>) : MessagesEvent
    data class UnpinEvent(val eventId: EventId) : MessagesEvent
    data class ReEditWithdrawn(val eventId: EventId) : MessagesEvent
    data class ResolveUnsignedDeviceAndStartCall(
        val failedEvent: TimelineItem.Event,
        val isAudioCall: Boolean,
    ) : MessagesEvent
    data object ClearWithdrawnDrafts : MessagesEvent
    data class InviteDialogDismissed(val action: InviteDialogAction) : MessagesEvent
    data class OnUserClicked(val user: MatrixUser) : MessagesEvent
    data object StopLiveLocationShare : MessagesEvent
    data object ShowLiveLocationShare : MessagesEvent
    data class OpenHistoryRecovery(val status: HistoryRecoveryStatus) : MessagesEvent
    data object MarkAsFullyReadAndExit : MessagesEvent
}

enum class InviteDialogAction {
    Cancel,
    Invite,
}
