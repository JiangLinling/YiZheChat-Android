/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.timeline.ForwardEventPairResult
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.LiveTimelineProvider
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ForwardMessagesPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createForwardMessagesPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.forwardAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - forward successful`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: RoomId, _: String ->
            ForwardEventPairResult.Success
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventPairLambda = forwardEventLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createForwardMessagesPresenter(fakeRoom = room, appOperationalStateCoordinator = coordinator)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val summary = aRoomSummary()
            presenter.onRoomSelected(listOf(summary.roomId))
            val forwardingState = awaitItem()
            assertThat(forwardingState.forwardAction.isLoading()).isTrue()
            var successfulForwardState = awaitItem()
            while (
                !successfulForwardState.forwardAction.isSuccess() &&
                !successfulForwardState.forwardAction.isFailure()
            ) {
                successfulForwardState = awaitItem()
            }
            assertThat(successfulForwardState.forwardAction).isEqualTo(AsyncAction.Success(listOf(summary.roomId)))
            forwardEventLambda.assertions().isCalledOnce()
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_15)
            assertThat(coordinator.startedRequests.single().trigger).isEqualTo("forward")
            assertThat(coordinator.startedRequests.single().eventIdHash).isNotEmpty()
        }
    }

    @Test
    fun `present - select a room and forward failed, then clear`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: RoomId, _: String ->
            ForwardEventPairResult.Failed("error")
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventPairLambda = forwardEventLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val presenter = createForwardMessagesPresenter(fakeRoom = room)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val summary = aRoomSummary()
            presenter.onRoomSelected(listOf(summary.roomId))
            var failedForwardState = awaitItem()
            while (
                !failedForwardState.forwardAction.isSuccess() &&
                !failedForwardState.forwardAction.isFailure()
            ) {
                failedForwardState = awaitItem()
            }
            assertThat(failedForwardState.forwardAction.isFailure()).isTrue()
            // Then clear error
            failedForwardState.eventSink(ForwardMessagesEvents.ClearError)
            assertThat(awaitItem().forwardAction.isUninitialized()).isTrue()
            forwardEventLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `partial retry sends only failed source target pair`() = runTest {
        val first = EventId("\$first:test")
        val second = EventId("\$second:test")
        val roomA = RoomId("!a:test")
        val roomB = RoomId("!b:test")
        val calls = mutableListOf<Pair<EventId, RoomId>>()
        var failSecondB = true
        val timeline = FakeTimeline().apply {
            forwardEventPairLambda = { eventId, roomId, _ ->
                calls += eventId to roomId
                if (eventId == second && roomId == roomB && failSecondB) ForwardEventPairResult.Failed("denied") else ForwardEventPairResult.Success
            }
        }
        val presenter = createForwardMessagesPresenter(eventIds = listOf(first, second), fakeRoom = FakeJoinedRoom(liveTimeline = timeline))
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(roomA, roomB))
            var state = awaitItem()
            while (!state.forwardAction.isFailure()) state = awaitItem()
            assertThat(calls).containsExactly(first to roomA, first to roomB, second to roomA, second to roomB)
            failSecondB = false
            state.eventSink(ForwardMessagesEvents.RetryFailed)
            while (!awaitItem().forwardAction.isSuccess()) Unit
            assertThat(calls.count { it == second to roomB }).isEqualTo(2)
            assertThat(calls.count { it != second to roomB }).isEqualTo(3)
        }
    }

    @Test
    fun `ambiguous pair is retained and never blindly retried`() = runTest {
        val calls = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            forwardEventPairLambda = { _, _, operationId -> calls += operationId; ForwardEventPairResult.Ambiguous("timeout") }
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline))
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(RoomId("!a:test")))
            var state = awaitItem()
            while (!state.forwardAction.isFailure()) state = awaitItem()
            state.eventSink(ForwardMessagesEvents.RetryFailed)
            expectNoEvents()
            assertThat(calls).hasSize(1)
        }
    }

    @Test
    fun `cancellation during SDK send durably finishes the pair as ambiguous`() = runTest {
        val started = CompletableDeferred<Unit>()
        var sends = 0
        val timeline = FakeTimeline().apply {
            forwardEventPairSuspendingLambda = { _, _, _ ->
                sends++
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val store = InMemoryForwardOperationStore()
        val sessionJob = SupervisorJob()
        val presenter = ForwardMessagesPresenter(
            eventIds = listOf(AN_EVENT_ID.value),
            actionIntentId = "cancelled-action",
            timelineProvider = LiveTimelineProvider(FakeJoinedRoom(liveTimeline = timeline)),
            sessionCoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + sessionJob),
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            forwardOperationStore = store,
        )
        val roomId = RoomId("!cancelled:test")

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(roomId))
            started.await()
            sessionJob.cancel()
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        val recreated = ForwardMessagesPresenter(
            eventIds = listOf(AN_EVENT_ID.value),
            actionIntentId = "cancelled-action",
            timelineProvider = LiveTimelineProvider(FakeJoinedRoom(liveTimeline = timeline)),
            sessionCoroutineScope = backgroundScope,
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            forwardOperationStore = store,
        )
        moleculeFlow(RecompositionMode.Immediate) { recreated.present() }.test {
            skipItems(1)
            recreated.onRoomSelected(listOf(roomId))
            var state = awaitItem()
            while (!state.forwardAction.isFailure()) state = awaitItem()
            assertThat(state.pairStates.values.single()).isInstanceOf(ForwardPairState.Ambiguous::class.java)
            assertThat(sends).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `persistence failure before send fails closed`() = runTest {
        var sends = 0
        val timeline = FakeTimeline().apply {
            forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success }
        }
        val failingStore = object : ForwardOperationStore {
            override suspend fun load(scopeKey: String, nowEpochMs: Long) = null
            override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long) = error("vault unavailable")
            override suspend fun clear(scopeKey: String, generation: String) = Unit
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = failingStore)
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(RoomId("!a:test")))
            while (!awaitItem().forwardAction.isFailure()) Unit
            assertThat(sends).isEqualTo(0)
        }
    }

    @Test
    fun `expired mixed success and ambiguous recovery performs no SDK send`() = runTest {
        var sends = 0
        val roomA = RoomId("!a:test")
        val roomB = RoomId("!b:test")
        val timeline = FakeTimeline().apply {
            forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success }
        }
        val store = object : ForwardOperationStore {
            private var operation: PersistedForwardOperation? = null
            override suspend fun load(scopeKey: String, nowEpochMs: Long): PersistedForwardOperation {
                return operation ?: PersistedForwardOperation(
                    scopeKey, "generation", "nonce", nowEpochMs - 1,
                    mapOf(
                        ForwardPair(AN_EVENT_ID, roomA) to ForwardPairState.Success,
                        ForwardPair(AN_EVENT_ID, roomB) to ForwardPairState.Ambiguous("Forward recovery window expired"),
                    ),
                )
            }
            override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long): PersistedForwardOperation = operation.also { this.operation = it }
            override suspend fun clear(scopeKey: String, generation: String) = Unit
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = store)
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(roomA, roomB))
            while (!awaitItem().forwardAction.isFailure()) Unit
            assertThat(sends).isEqualTo(0)
        }
    }

    @Test
    fun `different targets cannot replace an ambiguous operation`() = runTest {
        var sends = 0
        val storedRoom = RoomId("!stored:test")
        val requestedRoom = RoomId("!requested:test")
        val timeline = FakeTimeline().apply { forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success } }
        val store = object : ForwardOperationStore {
            override suspend fun load(scopeKey: String, nowEpochMs: Long) = PersistedForwardOperation(
                scopeKey, "generation", "nonce", nowEpochMs + 1_000,
                mapOf(ForwardPair(AN_EVENT_ID, storedRoom) to ForwardPairState.Ambiguous("timeout")),
            )
            override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long) = operation
            override suspend fun clear(scopeKey: String, generation: String) = error("must not clear")
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = store)
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(requestedRoom))
            while (!awaitItem().forwardAction.isFailure()) Unit
            assertThat(sends).isEqualTo(0)
        }
    }

    @Test
    fun `durable all-success outcome survives presenter recreation without resend`() = runTest {
        var sends = 0
        val room = RoomId("!a:test")
        val timeline = FakeTimeline().apply { forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success } }
        val store = InMemoryForwardOperationStore()
        suspend fun run(presenter: ForwardMessagesPresenter) {
            moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
                skipItems(1)
                presenter.onRoomSelected(listOf(room))
                while (!awaitItem().forwardAction.isSuccess()) Unit
            }
        }

        run(createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = store))
        run(createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = store))

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `new navigation action can forward the same messages and targets again`() = runTest {
        var sends = 0
        val room = RoomId("!a:test")
        val timeline = FakeTimeline().apply { forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success } }
        val store = InMemoryForwardOperationStore()
        suspend fun run(actionIntentId: String) {
            val presenter = createForwardMessagesPresenter(
                fakeRoom = FakeJoinedRoom(liveTimeline = timeline),
                forwardOperationStore = store,
                actionIntentId = actionIntentId,
            )
            moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
                skipItems(1)
                presenter.onRoomSelected(listOf(room))
                while (!awaitItem().forwardAction.isSuccess()) Unit
            }
        }

        run("first-action")
        run("second-action")

        assertThat(sends).isEqualTo(2)
    }

    @Test
    fun `different targets cannot discard a prior success and failed pair`() = runTest {
        var sends = 0
        val roomA = RoomId("!a:test")
        val roomB = RoomId("!b:test")
        val roomC = RoomId("!c:test")
        val store = object : ForwardOperationStore {
            override suspend fun load(scopeKey: String, nowEpochMs: Long) = PersistedForwardOperation(
                scopeKey, "generation", "nonce", nowEpochMs + 1_000,
                mapOf(
                    ForwardPair(AN_EVENT_ID, roomA) to ForwardPairState.Success,
                    ForwardPair(AN_EVENT_ID, roomB) to ForwardPairState.Failed("denied"),
                ),
            )
            override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long) = error("must not replace")
            override suspend fun clear(scopeKey: String, generation: String) = error("must not clear")
        }
        val timeline = FakeTimeline().apply { forwardEventPairLambda = { _, _, _ -> sends++; ForwardEventPairResult.Success } }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), forwardOperationStore = store)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            skipItems(1)
            presenter.onRoomSelected(listOf(roomA, roomC))
            while (!awaitItem().forwardAction.isFailure()) Unit
        }
        assertThat(sends).isEqualTo(0)
    }
}

fun TestScope.createForwardMessagesPresenter(
    eventId: EventId = AN_EVENT_ID,
    eventIds: List<EventId> = listOf(eventId),
    fakeRoom: FakeJoinedRoom = FakeJoinedRoom(),
    appOperationalStateCoordinator: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    forwardOperationStore: ForwardOperationStore = InMemoryForwardOperationStore(),
    actionIntentId: String = "action-intent",
) = ForwardMessagesPresenter(
    eventIds = eventIds.map(EventId::value),
    actionIntentId = actionIntentId,
    timelineProvider = LiveTimelineProvider(fakeRoom),
    sessionCoroutineScope = this,
    appOperationalStateCoordinator = appOperationalStateCoordinator,
    forwardOperationStore = forwardOperationStore,
)

private class InMemoryForwardOperationStore : ForwardOperationStore {
    private val values = mutableMapOf<String, PersistedForwardOperation>()
    override suspend fun load(scopeKey: String, nowEpochMs: Long): PersistedForwardOperation? = values[scopeKey]?.copy(
        pairStates = values.getValue(scopeKey).pairStates.mapValues { (_, state) ->
            if (state is ForwardPairState.Sending) ForwardPairState.Ambiguous("interrupted") else state
        }
    )
    override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long): PersistedForwardOperation {
        val current = values[operation.scopeKey]
        check(current != null && current.generation == operation.generation && current.revision == expectedRevision)
        return operation.copy(revision = expectedRevision + 1).also { values[operation.scopeKey] = it }
    }
    override suspend fun clear(scopeKey: String, generation: String) {
        if (values[scopeKey]?.generation == generation) values.remove(scopeKey)
    }
    override suspend fun create(operation: PersistedForwardOperation): PersistedForwardOperation {
        check(values[operation.scopeKey] == null)
        return operation.copy(revision = 1).also { values[operation.scopeKey] = it }
    }
    override suspend fun claimPair(scopeKey: String, generation: String, pair: ForwardPair): ForwardPairClaim? {
        val current = values[scopeKey] ?: error("missing")
        if (current.generation != generation || current.pairStates[pair] !is ForwardPairState.Pending && current.pairStates[pair] !is ForwardPairState.Failed) return null
        val token = java.util.UUID.randomUUID().toString()
        val next = current.copy(pairStates = current.pairStates + (pair to ForwardPairState.Sending), pairClaimTokens = current.pairClaimTokens + (pair to token), revision = current.revision + 1)
        values[scopeKey] = next
        return ForwardPairClaim(next, token)
    }
    override suspend fun finishPair(scopeKey: String, generation: String, pair: ForwardPair, claimToken: String, result: ForwardPairState): PersistedForwardOperation {
        val current = values.getValue(scopeKey)
        check(current.generation == generation && current.pairStates[pair] is ForwardPairState.Sending && current.pairClaimTokens[pair] == claimToken)
        return current.copy(pairStates = current.pairStates + (pair to result), pairClaimTokens = current.pairClaimTokens - pair, revision = current.revision + 1).also { values[scopeKey] = it }
    }
}
