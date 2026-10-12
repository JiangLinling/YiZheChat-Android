/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runUpdatingState
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AssistedInject
class ReportMessagePresenter(
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    @Assisted private val inputs: Inputs,
) : Presenter<ReportMessageState> {
    data class Inputs(
        val eventId: EventId,
        val senderId: UserId,
    )

    @AssistedFactory
    interface Factory {
        fun create(inputs: Inputs): ReportMessagePresenter
    }

    @Composable
    override fun present(): ReportMessageState {
        val coroutineScope = rememberCoroutineScope()
        var reason by rememberSaveable(
            room.roomId.value,
            matrixClient.sessionId.value,
            inputs.eventId.value,
            inputs.senderId.value,
        ) { mutableStateOf("") }
        var blockUser by rememberSaveable(
            room.roomId.value,
            matrixClient.sessionId.value,
            inputs.eventId.value,
            inputs.senderId.value,
        ) { mutableStateOf(false) }
        var reportSubmitted by rememberSaveable(
            room.roomId.value,
            matrixClient.sessionId.value,
            inputs.eventId.value,
            inputs.senderId.value,
            reason,
        ) { mutableStateOf(false) }
        var result: MutableState<AsyncAction<Unit>> = remember(
            room.roomId.value,
            matrixClient.sessionId.value,
            inputs.eventId.value,
            inputs.senderId.value,
        ) { mutableStateOf(AsyncAction.Uninitialized) }

        fun handleEvent(event: ReportMessageEvent) {
            when (event) {
                is ReportMessageEvent.UpdateReason -> if (!reportSubmitted && result.value !is AsyncAction.Loading) {
                    reason = event.reason
                }
                ReportMessageEvent.ToggleBlockUser -> if (!reportSubmitted && result.value !is AsyncAction.Loading) {
                    blockUser = !blockUser
                }
                ReportMessageEvent.Report -> if (result.value !is AsyncAction.Loading) {
                    // Set this synchronously so repeated taps cannot launch a second Matrix report
                    // before Compose observes the loading state.
                    result.value = AsyncAction.Loading
                    coroutineScope.report(
                        eventId = inputs.eventId,
                        userId = inputs.senderId,
                        reason = reason,
                        blockUser = blockUser,
                        reportSubmitted = reportSubmitted,
                        onReportSubmitted = { reportSubmitted = true },
                        result = result,
                    )
                }
                ReportMessageEvent.ClearError -> if (result.value is AsyncAction.Failure) {
                    result.value = AsyncAction.Uninitialized
                }
            }
        }

        return ReportMessageState(
            reason = reason,
            blockUser = blockUser,
            reportSubmitted = reportSubmitted,
            result = result.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.report(
        eventId: EventId,
        userId: UserId,
        reason: String,
        blockUser: Boolean,
        reportSubmitted: Boolean,
        onReportSubmitted: () -> Unit,
        result: MutableState<AsyncAction<Unit>>,
    ) = launch {
        result.runUpdatingState {
            if (!reportSubmitted) {
                val reportResult = room.reportContent(eventId, reason, blockUserId = null)
                if (reportResult.isFailure) return@runUpdatingState reportResult
                onReportSubmitted()
            }
            if (blockUser) matrixClient.ignoreUser(userId) else Result.success(Unit)
        }
    }
}
