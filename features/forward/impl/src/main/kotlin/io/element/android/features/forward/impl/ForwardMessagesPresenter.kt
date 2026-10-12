/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.timeline.TimelineProvider
import io.element.android.libraries.matrix.api.timeline.ForwardEventPairResult
import io.element.android.libraries.matrix.api.timeline.getActiveTimeline
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

@AssistedInject
class ForwardMessagesPresenter(
    @Assisted eventIds: List<String>,
    @Assisted actionIntentId: String,
    @Assisted private val timelineProvider: TimelineProvider,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val forwardOperationStore: ForwardOperationStore,
) : Presenter<ForwardMessagesState> {
    private val eventIds: List<EventId> = eventIds.map(::EventId)
    private val scopeKey = (eventIds.sorted().joinToString("\u0000") + "\u0000" + actionIntentId).sha256()
    private var operationNonce = UUID.randomUUID().toString()
    private var generation = UUID.randomUUID().toString()
    private var expiresAtEpochMs = 0L
    private val pairStates = mutableStateOf<Map<ForwardPair, ForwardPairState>>(emptyMap())
    private var selectedRoomIds: List<RoomId> = emptyList()
    private var forwarding = false

    @AssistedFactory
    fun interface Factory {
        fun create(eventIds: List<String>, actionIntentId: String, timelineProvider: TimelineProvider): ForwardMessagesPresenter
    }

    private val forwardingActionState: MutableState<AsyncAction<List<RoomId>>> = mutableStateOf(AsyncAction.Uninitialized)

    fun onRoomSelected(roomIds: List<RoomId>) {
        if (forwarding) return
        selectedRoomIds = roomIds.distinct()
        forwarding = true
        sessionCoroutineScope.forwardEvents(eventIds, selectedRoomIds)
    }

    @Composable
    override fun present(): ForwardMessagesState {
        fun handleEvent(event: ForwardMessagesEvents) {
            when (event) {
                ForwardMessagesEvents.ClearError -> forwardingActionState.value = AsyncAction.Uninitialized
                ForwardMessagesEvents.RetryFailed -> if (!forwarding && pairStates.value.values.any { it is ForwardPairState.Failed }) {
                    forwarding = true
                    sessionCoroutineScope.forwardEvents(eventIds, selectedRoomIds)
                }
            }
        }

        return ForwardMessagesState(
            forwardAction = forwardingActionState.value,
            pairStates = pairStates.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.forwardEvents(
        eventIds: List<EventId>,
        roomIds: List<RoomId>,
    ) = launch {
        try {
            suspend {
            val now = System.currentTimeMillis()
            val requestedPairs = eventIds.flatMap { eventId -> roomIds.map { ForwardPair(eventId, it) } }
            val restored = forwardOperationStore.load(scopeKey, now)
            if (restored != null && restored.pairStates.keys == requestedPairs.toSet()) {
                generation = restored.generation
                operationNonce = restored.operationNonce
                expiresAtEpochMs = restored.expiresAtEpochMs
                pairStates.value = restored.pairStates
            } else {
                if (restored != null) {
                    error("A durable forward outcome cannot be replaced with different targets.")
                }
                generation = UUID.randomUUID().toString()
                operationNonce = UUID.randomUUID().toString()
                expiresAtEpochMs = now + OPERATION_RECOVERY_TTL_MILLIS
                pairStates.value = requestedPairs.associateWith { ForwardPairState.Pending }
                pairStates.value = forwardOperationStore.create(
                    PersistedForwardOperation(scopeKey, generation, operationNonce, expiresAtEpochMs, pairStates.value)
                ).pairStates
            }
            appOperationalStateCoordinator.perform(
                lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_15,
                    trigger = "forward",
                    eventIdHash = eventIds.joinToString(separator = "\u0000") { it.value }.sha256(),
                )
            ) {
                val timeline = timelineProvider.getActiveTimeline()
                val retryable = eventIds.flatMap { eventId -> roomIds.map { ForwardPair(eventId, it) } }
                    .filter { pairStates.value[it] is ForwardPairState.Pending || pairStates.value[it] is ForwardPairState.Failed }
                retryable.forEach { pair ->
                    val claimed = forwardOperationStore.claimPair(scopeKey, generation, pair) ?: return@forEach
                    pairStates.value = claimed.operation.pairStates
                    val operationId = "$operationNonce\u0000${pair.eventId.value}\u0000${pair.roomId.value}".sha256()
                    val result = try {
                        timeline.forwardEventPair(pair.eventId, pair.roomId, operationId)
                    } catch (failure: Throwable) {
                        val ambiguous = withContext(NonCancellable) {
                            forwardOperationStore.finishPair(
                                scopeKey,
                                generation,
                                pair,
                                claimed.token,
                                ForwardPairState.Ambiguous(failure.message),
                            )
                        }
                        pairStates.value = ambiguous.pairStates
                        if (failure is CancellationException) throw failure
                        throw failure
                    }
                    val finished = forwardOperationStore.finishPair(scopeKey, generation, pair, claimed.token, when (result) {
                        ForwardEventPairResult.Success -> ForwardPairState.Success
                        is ForwardEventPairResult.Failed -> ForwardPairState.Failed(result.reason)
                        is ForwardEventPairResult.Ambiguous -> ForwardPairState.Ambiguous(result.reason)
                    })
                    pairStates.value = finished.pairStates
                }
                check(pairStates.value.values.all { it is ForwardPairState.Success }) { "Forwarding only partially completed" }
            }
            roomIds
            }.runCatchingUpdatingState(forwardingActionState)
        } finally {
            forwarding = false
        }
    }

}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

private const val OPERATION_RECOVERY_TTL_MILLIS = 5 * 60 * 1000L
