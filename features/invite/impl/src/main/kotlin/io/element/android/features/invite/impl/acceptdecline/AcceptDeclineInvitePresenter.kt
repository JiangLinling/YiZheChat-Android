/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.invite.impl.acceptdecline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.zacsweers.metro.Inject
import io.element.android.features.invite.api.InviteData
import io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteEvents
import io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteState
import io.element.android.features.invite.api.acceptdecline.ConfirmingDeclineInvite
import io.element.android.features.invite.impl.AcceptInvite
import io.element.android.features.invite.impl.DeclineInvite
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runUpdatingState
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Inject
class AcceptDeclineInvitePresenter(
    private val acceptInvite: AcceptInvite,
    private val declineInvite: DeclineInvite,
) : Presenter<AcceptDeclineInviteState> {
    @Composable
    override fun present(): AcceptDeclineInviteState {
        val localCoroutineScope = rememberCoroutineScope()
        val acceptedAction: MutableState<AsyncAction<RoomId>> =
            remember { mutableStateOf(AsyncAction.Uninitialized) }
        val declinedAction: MutableState<AsyncAction<RoomId>> =
            remember { mutableStateOf(AsyncAction.Uninitialized) }

        var actionRoomId by remember { mutableStateOf<RoomId?>(null) }

        fun handleEvent(event: AcceptDeclineInviteEvents) = Snapshot.withMutableSnapshot {
            if ((acceptedAction.value is AsyncAction.Loading || declinedAction.value is AsyncAction.Loading) &&
                (event is AcceptDeclineInviteEvents.AcceptInvite || event is AcceptDeclineInviteEvents.DeclineInvite ||
                    event is AcceptDeclineInviteEvents.ResetActions)) return@withMutableSnapshot
            when (event) {
                AcceptDeclineInviteEvents.ResetActions -> {
                    acceptedAction.value = AsyncAction.Uninitialized
                    declinedAction.value = AsyncAction.Uninitialized
                }
                is AcceptDeclineInviteEvents.AcceptInvite -> {
                    actionRoomId = event.invite.roomId
                    declinedAction.value = AsyncAction.Uninitialized
                    acceptedAction.value = AsyncAction.Loading
                    localCoroutineScope.acceptInvite(event.invite.roomId, acceptedAction)
                }

                is AcceptDeclineInviteEvents.DeclineInvite -> {
                    actionRoomId = event.invite.roomId
                    acceptedAction.value = AsyncAction.Uninitialized
                    val inviteData = event.invite
                    if (event.shouldConfirm) {
                        declinedAction.value = ConfirmingDeclineInvite(inviteData, event.blockUser)
                    } else {
                        declinedAction.value = AsyncAction.Loading
                        localCoroutineScope.declineInvite(
                            inviteData = inviteData,
                            blockUser = event.blockUser,
                            declinedAction = declinedAction,
                        )
                    }
                }
                is InternalAcceptDeclineInviteEvents.ClearAcceptActionState -> {
                    acceptedAction.value = AsyncAction.Uninitialized
                }

                is InternalAcceptDeclineInviteEvents.ClearDeclineActionState -> {
                    declinedAction.value = AsyncAction.Uninitialized
                }
            }
        }

        return AcceptDeclineInviteState(
            actionRoomId = actionRoomId,
            acceptAction = acceptedAction.value,
            declineAction = declinedAction.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.acceptInvite(
        roomId: RoomId,
        acceptedAction: MutableState<AsyncAction<RoomId>>,
    ) = launch {
        acceptedAction.runUpdatingState {
            acceptInvite(roomId)
        }
    }

    private fun CoroutineScope.declineInvite(
        inviteData: InviteData,
        blockUser: Boolean,
        declinedAction: MutableState<AsyncAction<RoomId>>,
    ) = launch {
        declinedAction.runUpdatingState {
            declineInvite(
                roomId = inviteData.roomId,
                blockUser = blockUser,
                reportRoom = false,
                reportReason = null
            )
        }
    }
}
