/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.test.FakeActiveLiveLocationShareManager
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.crypto.sendfailure.resolve.aResolveVerifiedUserSendFailureState
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.fixtures.aTimelineItemsFactoryCreator
import io.element.android.features.messages.impl.local.FakeMessageReferenceStore
import io.element.android.features.messages.impl.timeline.components.MessageShieldData
import io.element.android.features.messages.impl.timeline.components.aCriticalShield
import io.element.android.features.messages.impl.timeline.model.NewEventState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.typing.aTypingNotificationState
import io.element.android.features.messages.impl.voicemessages.timeline.FakeRedactedVoiceMessageManager
import io.element.android.features.messages.impl.voicemessages.timeline.RedactedVoiceMessageManager
import io.element.android.features.messages.impl.voicemessages.timeline.aRedactedMatrixTimeline
import io.element.android.features.poll.api.actions.EndPollAction
import io.element.android.features.poll.api.actions.SendPollResponseAction
import io.element.android.features.poll.test.actions.FakeEndPollAction
import io.element.android.features.poll.test.actions.FakeSendPollResponseAction
import io.element.android.features.roomcall.api.aStandByCallState
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.room.MessageEventType
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.room.tombstone.PredecessorRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventReaction
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ReactionSender
import io.element.android.libraries.matrix.api.timeline.item.event.Receipt
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_THREAD_ID
import io.element.android.libraries.matrix.test.A_THREAD_ID_2
import io.element.android.libraries.matrix.test.A_UNIQUE_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID_2
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.room.powerlevels.FakeRoomPermissions
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.libraries.matrix.ui.components.aMatrixUserList
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.contentprotection.api.RedactionReasonResolver
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.awaitLastSequentialItem
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test as testRecompositions
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TimelinePresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createTimelinePresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.timelineItems).isEmpty()
            assertThat(initialState.isLive).isTrue()
            assertThat(initialState.newEventState).isEqualTo(NewEventState.None)
            assertThat(initialState.focusedEventId).isNull()
            assertThat(initialState.focusRequestState).isEqualTo(FocusRequestState.None)
        }
    }

    @Test
    fun `navigation bootstrap leaves empty visible state unchanged`() = runTest {
        createTimelinePresenter().testRecompositions {
            val initial = awaitItem()
            assertThat(awaitItem()).isEqualTo(initial)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `present - load more`() = runTest {
        val paginateLambda = lambdaRecorder { _: Timeline.PaginationDirection ->
            Result.success(false)
        }
        val timeline = FakeTimeline().apply {
            this.paginateLambda = paginateLambda
        }
        val presenter = createTimelinePresenter(timeline = timeline)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
            initialState.eventSink.invoke(TimelineEvent.LoadMore(Timeline.PaginationDirection.FORWARDS))
            assert(paginateLambda)
                .isCalledExactly(2)
                .withSequence(
                    listOf(value(Timeline.PaginationDirection.BACKWARDS)),
                    listOf(value(Timeline.PaginationDirection.FORWARDS))
                )
        }
    }

    @Test
    fun `present - on scroll finished mark a room as read if the first visible index is 0 - read private`() {
        `present - on scroll finished mark a room as read if the first visible index is 0`(
            isSendPublicReadReceiptsEnabled = false,
            expectedReceiptType = ReceiptType.READ_PRIVATE,
        )
    }

    @Test
    fun `present - on scroll finished mark a room as read if the first visible index is 0 - read`() {
        `present - on scroll finished mark a room as read if the first visible index is 0`(
            isSendPublicReadReceiptsEnabled = true,
            expectedReceiptType = ReceiptType.READ,
        )
    }

    @Test
    fun `history recovery status is scoped to the active room and disappears with its candidates`() = runTest {
        val roomCounts = MutableStateFlow<Map<RoomId, Int>>(emptyMap())
        val recoveryStatusFlow = MutableStateFlow(HistoryRecoveryStatus.IDLE)
        val inventory = object : HiddenMessageRecoveryInventory {
            override val candidateCount = MutableStateFlow(0)
            override val candidateCountByRoom = roomCounts
            override val recoveryStatus = recoveryStatusFlow
            override fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>) = Unit
            override fun clearRoom(roomId: RoomId) = Unit
            override fun updateRecoveryStatus(status: HistoryRecoveryStatus) {
                recoveryStatusFlow.value = status
            }
        }
        val presenter = createTimelinePresenter(hiddenMessageRecoveryInventory = inventory)

        presenter.test {
            assertThat(awaitFirstItem().historyRecoveryStatus).isEqualTo(HistoryRecoveryStatus.IDLE)

            roomCounts.value = mapOf(A_ROOM_ID to 4)
            recoveryStatusFlow.value = HistoryRecoveryStatus.WAITING_FOR_MATRIX_RECOVERY
            val waiting = consumeItemsUntilPredicate {
                it.historyRecoveryStatus == HistoryRecoveryStatus.WAITING_FOR_MATRIX_RECOVERY
            }.last()
            assertThat(waiting.hiddenHistoryCandidateCount).isEqualTo(4)

            roomCounts.value = emptyMap()
            val cleared = consumeItemsUntilPredicate { it.hiddenHistoryCandidateCount == 0 }.last()
            assertThat(cleared.historyRecoveryStatus).isEqualTo(HistoryRecoveryStatus.IDLE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing burn-after-read persists a local deletion reference`() = runTest {
        val timeline = FakeTimeline().apply {
            openBurnAfterReadLambda = { Result.success(Unit) }
            endBurnAfterReadLambda = { _, _ -> Result.success(Unit) }
        }
        val messageReferenceStore = FakeMessageReferenceStore()
        val presenter = createTimelinePresenter(
            timeline = timeline,
            messageReferenceStore = messageReferenceStore,
        )

        presenter.test {
            awaitFirstItem().eventSink(TimelineEvent.OpenBurnAfterRead(AN_EVENT_ID))
            val openedState = consumeItemsUntilPredicate { it.activeBurnAfterReadEventId == AN_EVENT_ID }.last()
            openedState.eventSink(TimelineEvent.CloseBurnAfterRead)
            advanceUntilIdle()

            assertThat(messageReferenceStore.state.value.isLocallyDeleted(A_ROOM_ID, AN_EVENT_ID)).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `burn-after-read exposes opening then viewing state`() = runTest {
        val openResult = CompletableDeferred<Result<Unit>>()
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        content = aMessageContent(body = "one view").copy(isBurnAfterRead = true),
                    ),
                )
            )
        )
        val timeline = FakeTimeline(timelineItems = sourceItems).apply {
            openBurnAfterReadLambda = { openResult.await() }
        }
        val presenter = createTimelinePresenter(timeline = timeline)

        presenter.test {
            val ready = consumeItemsUntilPredicate { state ->
                state.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            ready.eventSink(TimelineEvent.OpenBurnAfterRead(AN_EVENT_ID))
            val opening = consumeItemsUntilPredicate { it.openingBurnAfterReadEventId == AN_EVENT_ID }.last()
            openResult.complete(Result.success(Unit))
            val viewing = consumeItemsUntilPredicate { it.activeBurnAfterReadEventId == AN_EVENT_ID }.last()

            assertThat(opening.activeBurnAfterReadEventId).isNull()
            assertThat(viewing.burnAfterReadOpenRejectedEventId).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `burn-after-read open failure is an explicit fail-closed state`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        content = aMessageContent(body = "one view").copy(isBurnAfterRead = true),
                    ),
                )
            )
        )
        val timeline = FakeTimeline(timelineItems = sourceItems).apply {
            openBurnAfterReadLambda = { Result.failure(IllegalStateException("authority unavailable")) }
        }
        val presenter = createTimelinePresenter(timeline = timeline)

        presenter.test {
            val ready = consumeItemsUntilPredicate { state ->
                state.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            ready.eventSink(TimelineEvent.OpenBurnAfterRead(AN_EVENT_ID))
            val rejected = consumeItemsUntilPredicate { it.burnAfterReadOpenRejectedEventId == AN_EVENT_ID }.last()

            assertThat(rejected.activeBurnAfterReadEventId).isNull()
            assertThat(rejected.openingBurnAfterReadEventId).isNull()
            rejected.eventSink(TimelineEvent.DismissBurnAfterReadOpenFailure)
            assertThat(consumeItemsUntilPredicate { it.burnAfterReadOpenRejectedEventId == null }.last().activeBurnAfterReadEventId).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sender burn-after-read shell cannot dispatch an open`() = runTest {
        var openCalls = 0
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        isOwn = true,
                        content = aMessageContent(body = "sent one view").copy(isBurnAfterRead = true),
                    ),
                )
            )
        )
        val timeline = FakeTimeline(timelineItems = sourceItems).apply {
            openBurnAfterReadLambda = {
                openCalls += 1
                Result.success(Unit)
            }
        }
        val presenter = createTimelinePresenter(timeline = timeline)

        presenter.test {
            val ready = consumeItemsUntilPredicate { state ->
                state.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            ready.eventSink(TimelineEvent.OpenBurnAfterRead(AN_EVENT_ID))
            advanceUntilIdle()

            assertThat(openCalls).isEqualTo(0)
            assertThat(ready.activeBurnAfterReadEventId).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing burn-after-read reaches terminal state before its row animates away`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        content = aMessageContent(body = "one view").copy(isBurnAfterRead = true),
                    ),
                )
            )
        )
        val timeline = FakeTimeline(timelineItems = sourceItems).apply {
            openBurnAfterReadLambda = { Result.success(Unit) }
            endBurnAfterReadLambda = { _, _ -> Result.success(Unit) }
        }
        val messageReferenceStore = FakeMessageReferenceStore()
        val presenter = createTimelinePresenter(timeline = timeline, messageReferenceStore = messageReferenceStore)

        presenter.test {
            val initial = consumeItemsUntilPredicate { state ->
                state.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            initial.eventSink(TimelineEvent.OpenBurnAfterRead(AN_EVENT_ID))
            val opened = consumeItemsUntilPredicate { it.activeBurnAfterReadEventId == AN_EVENT_ID }.last()
            opened.eventSink(TimelineEvent.CloseBurnAfterRead)
            runCurrent()

            val destroying = consumeItemsUntilPredicate { AN_EVENT_ID in it.destroyingBurnAfterReadEventIds }.last()
            assertThat(messageReferenceStore.state.value.isLocallyDeleted(A_ROOM_ID, AN_EVENT_ID)).isTrue()
            assertThat(AN_EVENT_ID).isIn(destroying.retainedBurnAfterReadEventIds)
            assertThat(destroying.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }).isTrue()

            advanceTimeBy(459)
            runCurrent()
            expectNoEvents()

            advanceTimeBy(1)
            runCurrent()
            val removed = consumeItemsUntilPredicate { state ->
                state.timelineItems.none { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            assertThat(AN_EVENT_ID).isNotIn(removed.destroyingBurnAfterReadEventIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remote burn-after-read redaction persists terminal state before sender row animates away`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        isOwn = true,
                        content = aMessageContent(body = "one view").copy(isBurnAfterRead = true),
                    ),
                )
            )
        )
        val timeline = FakeTimeline(timelineItems = sourceItems)
        val messageReferenceStore = FakeMessageReferenceStore()
        val presenter = createTimelinePresenter(timeline = timeline, messageReferenceStore = messageReferenceStore)

        presenter.test {
            consumeItemsUntilPredicate { state ->
                state.timelineItems.any { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }
            sourceItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(eventId = AN_EVENT_ID, isOwn = true, content = RedactedContent),
                )
            )
            runCurrent()

            val destroying = consumeItemsUntilPredicate { AN_EVENT_ID in it.destroyingBurnAfterReadEventIds }.last()
            assertThat(messageReferenceStore.state.value.isLocallyDeleted(A_ROOM_ID, AN_EVENT_ID)).isTrue()
            val retained = destroying.timelineItems.single { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID } as TimelineItem.Event
            assertThat(retained.content.isBurnAfterRead).isTrue()

            advanceTimeBy(460)
            runCurrent()
            val removed = consumeItemsUntilPredicate { state ->
                state.timelineItems.none { (it as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            assertThat(removed.retainedBurnAfterReadEventIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remote message redaction retains only target row during destruction transition`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        content = aMessageContent(body = "stable history"),
                    ),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID_2,
                        isOwn = true,
                        content = aMessageContent(body = "withdraw target"),
                    ),
                ),
            )
        )
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            val initial = consumeItemsUntilPredicate { state ->
                state.timelineItems.count { it is TimelineItem.Event } == 2
            }.last()
            val originalIds = initial.timelineItems.map(TimelineItem::identifier)

            sourceItems.value = listOf(
                sourceItems.value[0],
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(eventId = AN_EVENT_ID_2, isOwn = true, content = RedactedContent),
                ),
            )
            runCurrent()

            val destroying = consumeItemsUntilPredicate {
                AN_EVENT_ID_2 in it.destroyingMessageRemovalEventIds
            }.last()
            assertThat(destroying.timelineItems.map(TimelineItem::identifier)).containsExactlyElementsIn(originalIds).inOrder()
            val retainedTarget = destroying.timelineItems
                .filterIsInstance<TimelineItem.Event>()
                .first { it.eventId == AN_EVENT_ID_2 }
            assertThat(retainedTarget.content).isNotInstanceOf(TimelineItemRedactedContent::class.java)
            assertThat(AN_EVENT_ID_2).isIn(destroying.retainedMessageRemovalEventIds)

            advanceTimeBy(460)
            runCurrent()
            val completed = consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>()
                    .firstOrNull { it.eventId == AN_EVENT_ID_2 }
                    ?.content is TimelineItemRedactedContent &&
                    AN_EVENT_ID_2 !in state.destroyingMessageRemovalEventIds
            }.last()
            assertThat(completed.timelineItems.map(TimelineItem::identifier)).containsExactlyElementsIn(originalIds).inOrder()
            assertThat(AN_EVENT_ID_2).isNotIn(completed.retainedMessageRemovalEventIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial retention redactions stay hidden while their signed reasons are pending`() = runTest {
        val resolutionStarted = CompletableDeferred<Unit>()
        val releaseResolution = CompletableDeferred<Unit>()
        val redactedEventId = AN_EVENT_ID_2
        val redactedEventHash = MessageDigest.getInstance("SHA-256")
            .digest(redactedEventId.value.encodeToByteArray())
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(eventId = AN_EVENT_ID, content = aMessageContent(body = "stable history")),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(eventId = redactedEventId, content = RedactedContent),
                ),
            )
        )
        val resolver = RedactionReasonResolver { hashes ->
            assertThat(hashes).containsExactly(redactedEventHash)
            resolutionStarted.complete(Unit)
            releaseResolution.await()
            mapOf(redactedEventHash to TrustedRedactionReason.RETENTION_EXPIRED)
        }
        val presenter = createTimelinePresenter(
            timeline = FakeTimeline(timelineItems = sourceItems),
            redactionReasonResolver = resolver,
        )

        presenter.test {
            val pending = consumeItemsUntilPredicate { state ->
                resolutionStarted.isCompleted &&
                    state.timelineItems.filterIsInstance<TimelineItem.Event>().any { it.eventId == AN_EVENT_ID }
            }.last()
            assertThat(pending.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId })
                .containsExactly(AN_EVENT_ID)

            releaseResolution.complete(Unit)
            advanceUntilIdle()
            val appendedEventId = EventId("\$after-retention-resolution")
            sourceItems.value = sourceItems.value + MatrixTimelineItem.Event(
                UniqueId("after-retention-resolution"),
                anEventTimelineItem(eventId = appendedEventId, content = aMessageContent(body = "new message")),
            )
            runCurrent()
            val resolved = consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>().any { it.eventId == appendedEventId }
            }.last()
            assertThat(resolved.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId })
                .containsExactly(AN_EVENT_ID, appendedEventId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unclassified redaction becomes a neutral row after reason resolution completes`() = runTest {
        val releaseResolution = CompletableDeferred<Unit>()
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(eventId = AN_EVENT_ID, content = aMessageContent(body = "stable history")),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(eventId = AN_EVENT_ID_2, content = RedactedContent),
                ),
            )
        )
        val resolver = RedactionReasonResolver {
            releaseResolution.await()
            emptyMap()
        }
        val presenter = createTimelinePresenter(
            timeline = FakeTimeline(timelineItems = sourceItems),
            redactionReasonResolver = resolver,
        )

        presenter.test {
            val pending = awaitFirstItem()
            assertThat(pending.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId })
                .doesNotContain(AN_EVENT_ID_2)

            releaseResolution.complete(Unit)
            advanceUntilIdle()
            val completed = consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>()
                    .any { it.eventId == AN_EVENT_ID_2 && it.content is TimelineItemRedactedContent }
            }.last()
            val neutral = completed.timelineItems.filterIsInstance<TimelineItem.Event>()
                .first { it.eventId == AN_EVENT_ID_2 }
            assertThat(neutral.trustedRedactionReason).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `new message keeps its canonical position while an older row is being removed`() = runTest {
        val newestEventId = EventId("\$newest-during-removal")
        val newestUniqueId = UniqueId("newest-during-removal")
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(eventId = AN_EVENT_ID, content = aMessageContent(body = "older")),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID_2,
                        isOwn = true,
                        content = aMessageContent(body = "remove me"),
                    ),
                ),
            )
        )
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            consumeItemsUntilPredicate { it.timelineItems.filterIsInstance<TimelineItem.Event>().size == 2 }
            sourceItems.value = listOf(
                sourceItems.value[0],
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(eventId = AN_EVENT_ID_2, isOwn = true, content = RedactedContent),
                ),
                MatrixTimelineItem.Event(
                    newestUniqueId,
                    anEventTimelineItem(eventId = newestEventId, isOwn = true, content = aMessageContent(body = "newest")),
                ),
            )
            runCurrent()

            val destroying = consumeItemsUntilPredicate {
                AN_EVENT_ID_2 in it.destroyingMessageRemovalEventIds &&
                    it.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == newestEventId }
            }.last()
            val destroyingIds = destroying.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId }
            assertThat(destroyingIds.indexOf(newestEventId)).isLessThan(destroyingIds.indexOf(AN_EVENT_ID_2))
            assertThat(destroyingIds.indexOf(AN_EVENT_ID_2)).isLessThan(destroyingIds.indexOf(AN_EVENT_ID))

            advanceTimeBy(460)
            runCurrent()
            val completed = consumeItemsUntilPredicate {
                AN_EVENT_ID_2 !in it.destroyingMessageRemovalEventIds &&
                    it.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == newestEventId }
            }.last()
            val completedIds = completed.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId }
            assertThat(completedIds.indexOf(newestEventId)).isLessThan(completedIds.indexOf(AN_EVENT_ID_2))
            assertThat(completedIds.indexOf(AN_EVENT_ID_2)).isLessThan(completedIds.indexOf(AN_EVENT_ID))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remote redaction stays terminal when a later append replays the old projection`() = runTest {
        val newestEventId = EventId("\$newest-after-redaction")
        val newestUniqueId = UniqueId("newest-after-redaction")
        val stableItem = MatrixTimelineItem.Event(
            A_UNIQUE_ID,
            anEventTimelineItem(eventId = AN_EVENT_ID, content = aMessageContent(body = "stable history")),
        )
        val originalTarget = MatrixTimelineItem.Event(
            A_UNIQUE_ID_2,
            anEventTimelineItem(eventId = AN_EVENT_ID_2, content = aMessageContent(body = "must not return")),
        )
        val redactedTarget = MatrixTimelineItem.Event(
            A_UNIQUE_ID_2,
            anEventTimelineItem(eventId = AN_EVENT_ID_2, content = RedactedContent),
        )
        val newestItem = MatrixTimelineItem.Event(
            newestUniqueId,
            anEventTimelineItem(eventId = newestEventId, content = aMessageContent(body = "new append")),
        )
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(listOf(stableItem, originalTarget))
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>().any { it.eventId == AN_EVENT_ID_2 }
            }

            sourceItems.value = listOf(stableItem, redactedTarget)
            runCurrent()
            consumeItemsUntilPredicate { AN_EVENT_ID_2 in it.destroyingMessageRemovalEventIds }
            advanceTimeBy(MESSAGE_REMOVAL_DESTRUCTION_MILLIS)
            runCurrent()
            consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>()
                    .firstOrNull { it.eventId == AN_EVENT_ID_2 }
                    ?.content is TimelineItemRedactedContent
            }

            // A late SDK projection may be delivered together with a genuinely new append. Once the
            // redaction was observed, the stale pre-redaction content must never become visible again.
            sourceItems.value = listOf(stableItem, originalTarget, newestItem)
            runCurrent()
            val afterAppend = consumeItemsUntilPredicate { state ->
                state.timelineItems.filterIsInstance<TimelineItem.Event>().any { it.eventId == newestEventId }
            }.last()

            val terminalTarget = afterAppend.timelineItems.filterIsInstance<TimelineItem.Event>()
                .first { it.eventId == AN_EVENT_ID_2 }
            assertThat(terminalTarget.content).isInstanceOf(TimelineItemRedactedContent::class.java)
            assertThat(afterAppend.timelineItems.filterIsInstance<TimelineItem.Event>().first { it.eventId == AN_EVENT_ID }.content)
                .isNotInstanceOf(TimelineItemRedactedContent::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fail-closed security shell interrupts a retained plaintext removal animation`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        isOwn = true,
                        content = aMessageContent(body = "must not survive revocation"),
                    ),
                )
            )
        )
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            val initial = consumeItemsUntilPredicate {
                it.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == AN_EVENT_ID }
            }.last()
            initial.eventSink(TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID))
            initial.eventSink(TimelineEvent.CommitMessageRemoval(AN_EVENT_ID))
            consumeItemsUntilPredicate { AN_EVENT_ID in it.destroyingMessageRemovalEventIds }

            sourceItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        isOwn = true,
                        content = aMessageContent(body = "").copy(
                            protectedMessageStatus = ProtectedMessageStatus.ACCESS_DENIED,
                        ),
                    ),
                )
            )
            runCurrent()

            val secured = consumeItemsUntilPredicate { state ->
                val event = state.timelineItems.filterIsInstance<TimelineItem.Event>().singleOrNull()
                event?.content?.isProtectedContentShell == true && state.retainedMessageRemovalEventIds.isEmpty()
            }.last()
            assertThat(secured.pendingMessageRemovalEventIds).isEmpty()
            assertThat(secured.destroyingMessageRemovalEventIds).isEmpty()
            val securedEvent = secured.timelineItems.filterIsInstance<TimelineItem.Event>().single()
            assertThat((securedEvent.content as io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent).body)
                .doesNotContain("must not survive revocation")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel arriving after authoritative commit cannot stop target destruction`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem(eventId = AN_EVENT_ID)))
        )
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            val initial = consumeItemsUntilPredicate { it.timelineItems.isNotEmpty() }.last()
            initial.eventSink(TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID))
            initial.eventSink(TimelineEvent.CommitMessageRemoval(AN_EVENT_ID))
            val destroying = consumeItemsUntilPredicate { AN_EVENT_ID in it.destroyingMessageRemovalEventIds }.last()

            destroying.eventSink(TimelineEvent.CancelMessageRemoval(AN_EVENT_ID))
            runCurrent()
            expectNoEvents()

            advanceTimeBy(460)
            runCurrent()
            val finished = consumeItemsUntilPredicate { AN_EVENT_ID !in it.destroyingMessageRemovalEventIds }.last()
            assertThat(finished.retainedMessageRemovalEventIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disposing room presentation clears pending animation state and rejects late timer`() = runTest {
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem(eventId = AN_EVENT_ID)))
        )
        val presenter = createTimelinePresenter(timeline = FakeTimeline(timelineItems = sourceItems))

        presenter.test {
            val initial = consumeItemsUntilPredicate { it.timelineItems.isNotEmpty() }.last()
            initial.eventSink(TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID))
            initial.eventSink(TimelineEvent.CommitMessageRemoval(AN_EVENT_ID))
            consumeItemsUntilPredicate { AN_EVENT_ID in it.destroyingMessageRemovalEventIds }
            cancelAndIgnoreRemainingEvents()
        }

        presenter.test {
            val reopened = consumeItemsUntilPredicate { it.timelineItems.isNotEmpty() }.last()
            assertThat(reopened.retainedMessageRemovalEventIds).isEmpty()
            assertThat(reopened.pendingMessageRemovalEventIds).isEmpty()
            assertThat(reopened.destroyingMessageRemovalEventIds).isEmpty()
            advanceTimeBy(460)
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flash destruction ordinary recall and new message settle independently in one update`() = runTest {
        val newestEventId = EventId("\$newest-terminal-race")
        val newestUniqueId = UniqueId("newest-terminal-race")
        val sourceItems = MutableStateFlow<List<MatrixTimelineItem>>(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID,
                        content = aMessageContent(body = "one view").copy(isBurnAfterRead = true),
                    ),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(
                        eventId = AN_EVENT_ID_2,
                        isOwn = true,
                        content = aMessageContent(body = "recall me"),
                    ),
                ),
            )
        )
        val messageReferenceStore = FakeMessageReferenceStore()
        val presenter = createTimelinePresenter(
            timeline = FakeTimeline(timelineItems = sourceItems),
            messageReferenceStore = messageReferenceStore,
        )

        presenter.test {
            val initial = consumeItemsUntilPredicate {
                it.timelineItems.filterIsInstance<TimelineItem.Event>().size == 2
            }.last()
            initial.eventSink(TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID_2))

            sourceItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(eventId = AN_EVENT_ID, content = RedactedContent),
                ),
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID_2,
                    anEventTimelineItem(eventId = AN_EVENT_ID_2, isOwn = true, content = RedactedContent),
                ),
                MatrixTimelineItem.Event(
                    newestUniqueId,
                    anEventTimelineItem(eventId = newestEventId, content = aMessageContent(body = "new message")),
                ),
            )
            runCurrent()

            val terminalBatch = consumeItemsUntilPredicate { state ->
                AN_EVENT_ID in state.destroyingBurnAfterReadEventIds &&
                    AN_EVENT_ID_2 in state.pendingMessageRemovalEventIds &&
                    state.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == newestEventId }
            }.last()
            terminalBatch.eventSink(TimelineEvent.CommitMessageRemoval(AN_EVENT_ID_2))
            val destroying = consumeItemsUntilPredicate { state ->
                AN_EVENT_ID in state.destroyingBurnAfterReadEventIds &&
                    AN_EVENT_ID_2 in state.destroyingMessageRemovalEventIds &&
                    state.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == newestEventId }
            }.last()
            assertThat(messageReferenceStore.state.value.isLocallyDeleted(A_ROOM_ID, AN_EVENT_ID)).isTrue()
            val destroyingIds = destroying.timelineItems.filterIsInstance<TimelineItem.Event>().mapNotNull { it.eventId }
            assertThat(destroyingIds).containsNoDuplicates()
            assertThat(destroyingIds).containsAtLeast(AN_EVENT_ID, AN_EVENT_ID_2, newestEventId)

            advanceTimeBy(460)
            runCurrent()
            val settled = consumeItemsUntilPredicate { state ->
                state.timelineItems.none { item -> (item as? TimelineItem.Event)?.eventId == AN_EVENT_ID } &&
                    state.timelineItems.filterIsInstance<TimelineItem.Event>()
                        .firstOrNull { it.eventId == AN_EVENT_ID_2 }
                        ?.content is TimelineItemRedactedContent &&
                    state.timelineItems.any { item -> (item as? TimelineItem.Event)?.eventId == newestEventId }
            }.last()
            assertThat(settled.destroyingBurnAfterReadEventIds).isEmpty()
            assertThat(settled.destroyingMessageRemovalEventIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread regression - background direct timeline loading never marks it read`() = runTest {
        val markAsReadResult = lambdaRecorder<ReceiptType, Result<Unit>> { Result.success(Unit) }
        val timeline = FakeTimeline(
            timelineItems = flowOf(listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()))),
            markAsReadResult = markAsReadResult,
        )
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(initialRoomInfo = aRoomInfo(isDirect = true, isDm = true)),
            liveTimeline = timeline,
        )
        val presenter = createTimelinePresenter(timeline = timeline, room = room)

        presenter.test {
            val state = consumeItemsUntilPredicate { it.timelineItems.any { row -> row is TimelineItem.Event } }.last()
            advanceUntilIdle()
            assertThat(state.renderReadReceipts).isTrue()
            assert(markAsReadResult).isNeverCalled()
            state.eventSink(TimelineEvent.OnScrollFinished(0, isUserVisible = false))
            advanceUntilIdle()
            assert(markAsReadResult).isNeverCalled()
            state.eventSink(TimelineEvent.OnScrollFinished(0, isUserVisible = true))
            advanceUntilIdle()
            assert(markAsReadResult).isCalledOnce().with(value(ReceiptType.READ))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread regression - actually viewed direct room sends a private receipt when public receipts are disabled`() = runTest {
        val markAsReadResult = lambdaRecorder<ReceiptType, Result<Unit>> { Result.success(Unit) }
        val timeline = FakeTimeline(
            timelineItems = flowOf(listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()))),
            markAsReadResult = markAsReadResult,
        )
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(initialRoomInfo = aRoomInfo(isDirect = true, isDm = true)),
            liveTimeline = timeline,
        )
        val presenter = createTimelinePresenter(
            timeline = timeline,
            room = room,
            sessionPreferencesStore = InMemorySessionPreferencesStore(isSendPublicReadReceiptsEnabled = false),
        )

        presenter.test {
            val state = consumeItemsUntilPredicate { it.timelineItems.any { row -> row is TimelineItem.Event } }.last()
            state.eventSink(TimelineEvent.OnScrollFinished(0, isUserVisible = true))
            advanceUntilIdle()
            assert(markAsReadResult)
                .isCalledOnce()
                .with(value(ReceiptType.READ_PRIVATE))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread regression - viewed group clears private unread while public receipts stay disabled`() = runTest {
        val markAsReadResult = lambdaRecorder<ReceiptType, Result<Unit>> { Result.success(Unit) }
        val timeline = FakeTimeline(
            timelineItems = flowOf(listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()))),
            markAsReadResult = markAsReadResult,
        )
        val presenter = createTimelinePresenter(
            timeline = timeline,
            dataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(allowGroupReadReceipts = false),
        )

        presenter.test {
            val state = consumeItemsUntilPredicate { it.timelineItems.any { row -> row is TimelineItem.Event } }.last()
            assertThat(state.renderReadReceipts).isFalse()
            state.eventSink(TimelineEvent.OnScrollFinished(0))
            advanceUntilIdle()
            assert(markAsReadResult).isCalledOnce().with(value(ReceiptType.READ_PRIVATE))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread regression - measured group viewport clears unread without scrolling or sending`() = runTest {
        val markAsReadResult = lambdaRecorder<ReceiptType, Result<Unit>> { Result.success(Unit) }
        val timeline = FakeTimeline(
            timelineItems = flowOf(listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()))),
            markAsReadResult = markAsReadResult,
        )
        val presenter = createTimelinePresenter(
            timeline = timeline,
            dataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(allowGroupReadReceipts = false),
        )
        presenter.test {
            val state = consumeItemsUntilPredicate { it.timelineItems.any { row -> row is TimelineItem.Event } }.last()
            state.eventSink(TimelineEvent.ViewportChanged(listOf(A_UNIQUE_ID), isUserVisible = false))
            advanceUntilIdle()
            assert(markAsReadResult).isNeverCalled()
            state.eventSink(TimelineEvent.ViewportChanged(listOf(A_UNIQUE_ID), isUserVisible = true))
            advanceUntilIdle()
            assert(markAsReadResult).isCalledOnce().with(value(ReceiptType.READ_PRIVATE))
            state.eventSink(TimelineEvent.ViewportChanged(listOf(A_UNIQUE_ID), isUserVisible = true))
            advanceUntilIdle()
            assert(markAsReadResult).isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun `present - on scroll finished mark a room as read if the first visible index is 0`(
        isSendPublicReadReceiptsEnabled: Boolean,
        expectedReceiptType: ReceiptType,
    ) = runTest(StandardTestDispatcher()) {
        val markAsReadResult = lambdaRecorder<ReceiptType, Result<Unit>> { Result.success(Unit) }
        val sendReadReceiptLambda = lambdaRecorder<EventId, ReceiptType, Result<Unit>> { _, _ -> Result.success(Unit) }
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem())
                )
            ),
            markAsReadResult = markAsReadResult,
            sendReadReceiptLambda = sendReadReceiptLambda,
        )
        val room = FakeJoinedRoom(
            liveTimeline = timeline,
        )
        val sessionPreferencesStore = InMemorySessionPreferencesStore(isSendPublicReadReceiptsEnabled = isSendPublicReadReceiptsEnabled)
        val presenter = createTimelinePresenter(
            timeline = timeline,
            room = room,
            sessionPreferencesStore = sessionPreferencesStore,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.OnScrollFinished(0))
            runCurrent()
            assert(markAsReadResult)
                .isCalledOnce()
                .with(value(expectedReceiptType))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - on scroll finished send read receipt if an event is before the index`() = runTest {
        val sendReadReceiptsLambda = lambdaRecorder { _: EventId, _: ReceiptType ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()),
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID_2,
                        event = anEventTimelineItem(
                            eventId = AN_EVENT_ID_2,
                            content = aMessageContent("Test message")
                        )
                    )
                )
            )
        ).apply {
            this.sendReadReceiptLambda = sendReadReceiptsLambda
        }
        val presenter = createTimelinePresenter(timeline)
        presenter.test {
            skipItems(1)
            awaitItem().run {
                eventSink.invoke(TimelineEvent.OnScrollFinished(1))
            }
            advanceUntilIdle()
            assert(sendReadReceiptsLambda)
                .isCalledOnce()
                .with(any(), value(ReceiptType.READ))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - on scroll finished send a private read receipt if an event is at an index other than 0 and public read receipts are disabled`() = runTest {
        val sendReadReceiptsLambda = lambdaRecorder { _: EventId, _: ReceiptType ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()),
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID_2,
                        event = anEventTimelineItem(
                            eventId = AN_EVENT_ID_2,
                            content = aMessageContent("Test message")
                        )
                    )
                )
            ),
            markAsReadResult = { Result.success(Unit) },
            sendReadReceiptLambda = sendReadReceiptsLambda,
        )
        val sessionPreferencesStore = InMemorySessionPreferencesStore(isSendPublicReadReceiptsEnabled = false)
        val presenter = createTimelinePresenter(
            timeline = timeline,
            sessionPreferencesStore = sessionPreferencesStore,
        )
        presenter.test {
            skipItems(1)
            awaitItem().run {
                eventSink.invoke(TimelineEvent.OnScrollFinished(0))
                eventSink.invoke(TimelineEvent.OnScrollFinished(1))
            }
            advanceUntilIdle()
            assert(sendReadReceiptsLambda)
                .isCalledOnce()
                .with(any(), value(ReceiptType.READ_PRIVATE))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - on scroll finished will not send read receipt the first visible event is the same as before`() = runTest {
        val sendReadReceiptsLambda = lambdaRecorder { _: EventId, _: ReceiptType ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem()),
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID_2,
                        event = anEventTimelineItem(
                            eventId = AN_EVENT_ID_2,
                            content = aMessageContent("Test message")
                        )
                    )
                )
            )
        ).apply {
            this.sendReadReceiptLambda = sendReadReceiptsLambda
        }
        val presenter = createTimelinePresenter(timeline)
        presenter.test {
            skipItems(1)
            awaitItem().run {
                eventSink.invoke(TimelineEvent.OnScrollFinished(1))
                eventSink.invoke(TimelineEvent.OnScrollFinished(1))
            }
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
            assert(sendReadReceiptsLambda).isCalledOnce()
        }
    }

    @Test
    fun `present - on scroll finished will not send read receipt only virtual events exist before the index`() = runTest {
        val sendReadReceiptsLambda = lambdaRecorder { _: EventId, _: ReceiptType ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Virtual(A_UNIQUE_ID, VirtualTimelineItem.ReadMarker),
                    MatrixTimelineItem.Virtual(A_UNIQUE_ID, VirtualTimelineItem.ReadMarker)
                )
            )
        ).apply {
            this.sendReadReceiptLambda = sendReadReceiptsLambda
        }
        val presenter = createTimelinePresenter(timeline)
        presenter.test {
            skipItems(1)
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.OnScrollFinished(1))
            cancelAndIgnoreRemainingEvents()
            assert(sendReadReceiptsLambda).isNeverCalled()
        }
    }

    @Test
    fun `present - newest event replacement with unchanged size signals arrival`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val presenter = createTimelinePresenter(FakeTimeline(timelineItems = timelineItems))
        presenter.test {
            awaitFirstItem()
            timelineItems.value = listOf(MatrixTimelineItem.Event(UniqueId("old-latest"), anEventTimelineItem(eventId = EventId("\$event-old"), content = aMessageContent())))
            consumeItemsUntilPredicate { it.timelineItems.size == 1 }
            runCurrent()
            timelineItems.value = listOf(MatrixTimelineItem.Event(UniqueId("new-latest"), anEventTimelineItem(eventId = EventId("\$event-new"), content = aMessageContent())))
            val received = consumeItemsUntilPredicate { it.newEventState == NewEventState.FromOther }.last()
            assertThat(received.timelineItems.size).isEqualTo(1)
            assertThat(received.timelineItems.first().identifier()).isEqualTo(UniqueId("new-latest"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - covers newEventState scenarios`() = runTest {
        val timelineItems = MutableStateFlow(emptyList<MatrixTimelineItem>())
        val timeline = FakeTimeline(
            timelineItems = timelineItems,
            markAsReadResult = { Result.success(Unit) },
        )
        val presenter = createTimelinePresenter(timeline)
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.newEventState).isEqualTo(NewEventState.None)
            assertThat(initialState.timelineItems.size).isEqualTo(0)
            timelineItems.emit(
                listOf(MatrixTimelineItem.Event(UniqueId("0"), anEventTimelineItem(eventId = EventId("\$event-0"), content = aMessageContent())))
            )
            consumeItemsUntilPredicate { it.timelineItems.size == 1 }
            runCurrent()
            // Mimics sending a message, and assert newEventState is FromMe
            timelineItems.getAndUpdate { items ->
                val event = anEventTimelineItem(eventId = EventId("\$event-1"), content = aMessageContent(), isOwn = true)
                items + listOf(MatrixTimelineItem.Event(UniqueId("1"), event))
            }
            consumeItemsUntilPredicate { it.timelineItems.size == 2 && it.newEventState == NewEventState.FromMe }.last().also { state ->
                assertThat(state.newEventState).isEqualTo(NewEventState.FromMe)
            }
            // Mimics receiving a message without clearing the previous FromMe
            timelineItems.getAndUpdate { items ->
                val event = anEventTimelineItem(eventId = EventId("\$event-2"), content = aMessageContent())
                items + listOf(MatrixTimelineItem.Event(UniqueId("2"), event))
            }
            consumeItemsUntilPredicate { it.timelineItems.size == 3 }

            // Scroll to bottom to clear previous FromMe
            initialState.eventSink.invoke(TimelineEvent.OnScrollFinished(0))
            awaitLastSequentialItem().also { state ->
                assertThat(state.newEventState).isEqualTo(NewEventState.None)
            }
            // Mimics receiving a message and assert newEventState is FromOther
            timelineItems.getAndUpdate { items ->
                val event = anEventTimelineItem(eventId = EventId("\$event-3"), content = aMessageContent())
                items + listOf(MatrixTimelineItem.Event(UniqueId("3"), event))
            }
            consumeItemsUntilPredicate { it.timelineItems.size == 4 && it.newEventState == NewEventState.FromOther }.last().also { state ->
                assertThat(state.newEventState).isEqualTo(NewEventState.FromOther)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - reaction ordering`() = runTest {
        val timelineItems = MutableStateFlow(emptyList<MatrixTimelineItem>())
        val timeline = FakeTimeline(
            timelineItems = timelineItems,
        )
        val presenter = createTimelinePresenter(timeline)
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.newEventState).isEqualTo(NewEventState.None)
            assertThat(initialState.timelineItems.size).isEqualTo(0)
            val now = Date().time
            val minuteInMillis = 60 * 1000
            // Use index as a convenient value for timestamp
            val (alice, bob, charlie) = aMatrixUserList().take(3).mapIndexed { i, user ->
                ReactionSender(senderId = user.userId, timestamp = now + i * minuteInMillis)
            }
            val oneReaction = persistentListOf(
                EventReaction(
                    key = "❤️",
                    senders = persistentListOf(alice, charlie)
                ),
                EventReaction(
                    key = "👍",
                    senders = persistentListOf(alice, bob)
                ),
                EventReaction(
                    key = "🐶",
                    senders = persistentListOf(charlie)
                ),
            )
            timelineItems.emit(
                listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem(reactions = oneReaction)))
            )
            val item = awaitItem().timelineItems.first()
            assertThat(item).isInstanceOf(TimelineItem.Event::class.java)
            val event = item as TimelineItem.Event
            val reactions = event.reactionsState.reactions
            assertThat(reactions.size).isEqualTo(3)

            // Aggregated reactions are sorted by count first and then timestamp ascending(new ones tagged on the end)
            assertThat(reactions[0].count).isEqualTo(2)
            assertThat(reactions[0].key).isEqualTo("👍")
            assertThat(reactions[0].senders[0].senderId).isEqualTo(bob.senderId)

            assertThat(reactions[1].count).isEqualTo(2)
            assertThat(reactions[1].key).isEqualTo("❤️")
            assertThat(reactions[1].senders[0].senderId).isEqualTo(charlie.senderId)

            assertThat(reactions[2].count).isEqualTo(1)
            assertThat(reactions[2].key).isEqualTo("🐶")
            assertThat(reactions[2].senders[0].senderId).isEqualTo(charlie.senderId)
        }
    }

    @Test
    fun `present - PollAnswerSelected event`() = runTest {
        val sendPollResponseAction = FakeSendPollResponseAction()
        val presenter = createTimelinePresenter(
            sendPollResponseAction = sendPollResponseAction,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.SelectPollAnswer(AN_EVENT_ID, "anAnswerId"))
        }
        delay(1)
        sendPollResponseAction.verifyExecutionCount(1)
    }

    @Test
    fun `present - PollEndClicked event`() = runTest {
        val endPollAction = FakeEndPollAction()
        val presenter = createTimelinePresenter(
            endPollAction = endPollAction,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.EndPoll(AN_EVENT_ID))
        }
        delay(1)
        endPollAction.verifyExecutionCount(1)
    }

    @Test
    fun `present - PollEditClicked event navigates`() = runTest {
        val onEditPollClickLambda = lambdaRecorder { _: EventId -> }
        val navigator = FakeMessagesNavigator(
            onEditPollClickLambda = onEditPollClickLambda
        )
        val presenter = createTimelinePresenter(
            messagesNavigator = navigator,
        )
        presenter.test {
            awaitFirstItem().eventSink(TimelineEvent.EditPoll(AN_EVENT_ID))
            onEditPollClickLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID))
        }
    }

    @Test
    fun `present - side effect on redacted items is invoked`() = runTest {
        val redactedVoiceMessageManager = FakeRedactedVoiceMessageManager()
        val presenter = createTimelinePresenter(
            timeline = FakeTimeline(
                timelineItems = flowOf(
                    aRedactedMatrixTimeline(AN_EVENT_ID),
                )
            ),
            redactedVoiceMessageManager = redactedVoiceMessageManager,
        )
        presenter.test {
            assertThat(redactedVoiceMessageManager.invocations.size).isEqualTo(0)
            skipItems(2)
            assertThat(redactedVoiceMessageManager.invocations.size).isEqualTo(1)
        }
    }

    @Test
    fun `present - focus on event then jump to live clears the historical focus`() = runTest {
        val detachedTimeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID,
                        event = anEventTimelineItem(),
                    )
                )
            )
        )
        val liveTimeline = FakeTimeline(
            timelineItems = flowOf(emptyList())
        )
        val room = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) },
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                threadRootIdForEventResult = { _ -> Result.success(null) },
            ),
        )
        val presenter = createTimelinePresenter(
            room = room,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))
            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }
            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))
            }
            consumeItemsUntilPredicate { it.focusRequestState is FocusRequestState.Success && it.timelineItems.isNotEmpty() }.last().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.timelineItems).isNotEmpty()
            }
            initialState.eventSink.invoke(TimelineEvent.JumpToLive)
            consumeItemsUntilPredicate { it.isLive && it.timelineItems.isEmpty() && it.focusRequestState == FocusRequestState.None }.last().also { state ->
                assertThat(state.focusedEventId).isNull()
                assertThat(state.navigatingToFirstNewMessage).isFalse()
                assertThat(state.timelineItems).isEmpty()
            }
        }
    }

    @Test
    fun `present - focus on known event retrieves the event from cache`() = runTest {
        val timelineItemIndexer = TimelineItemIndexer()
        val presenter = createTimelinePresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline(
                    timelineItems = flowOf(
                        listOf(
                            MatrixTimelineItem.Event(
                                uniqueId = A_UNIQUE_ID,
                                event = anEventTimelineItem(eventId = AN_EVENT_ID),
                            )
                        )
                    )
                ),
                baseRoom = FakeBaseRoom(
                    roomPermissions = roomPermissions(),
                    threadRootIdForEventResult = { Result.success(null) },
                ),
            ),
            timelineItemIndexer = timelineItemIndexer,
        )
        presenter.test {
            val initialState = awaitFirstItem()

            advanceUntilIdle()

            // Pre-populate the indexer after the first items have been retrieved
            timelineItemIndexer.process(listOf(aMessageEvent(eventId = AN_EVENT_ID)))

            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))

            advanceUntilIdle()

            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }
            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Success(AN_EVENT_ID, 0))
            }
        }
    }

    @Test
    fun `present - focus on event error case`() = runTest {
        val presenter = createTimelinePresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline(
                    timelineItems = flowOf(emptyList()),
                ),
                createTimelineResult = { Result.failure(RuntimeException("An error")) },
                baseRoom = FakeBaseRoom(
                    roomPermissions = roomPermissions(),
                    threadRootIdForEventResult = { _ -> Result.success(null) },
                ),
            )
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(TimelineEvent.FocusOnEvent(AN_EVENT_ID))
            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }
            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))
            }
            awaitItem().also { state ->
                assertThat(state.focusRequestState).isInstanceOf(FocusRequestState.Failure::class.java)
                state.eventSink(TimelineEvent.ClearFocusRequestState)
            }
            awaitItem().also { state ->
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.None)
            }
        }
    }

    @Test
    fun `present - focus on event in a thread opens the thread`() = runTest {
        val threadId = A_THREAD_ID
        val detachedTimeline = FakeTimeline(
            mode = Timeline.Mode.FocusedOnEvent(AN_EVENT_ID_2),
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID,
                        event = anEventTimelineItem(),
                    )
                )
            )
        )
        val liveTimeline = FakeTimeline(
            timelineItems = flowOf(emptyList())
        )
        val room = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) },
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                threadRootIdForEventResult = { _ -> Result.success(threadId) },
            ),
        )
        val openThreadLambda = lambdaRecorder { _: ThreadId, _: EventId? -> }
        val navigator = FakeMessagesNavigator(onOpenThreadLambda = openThreadLambda)
        val presenter = createTimelinePresenter(
            room = room,
            timeline = liveTimeline,
            messagesNavigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))

            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }

            advanceUntilIdle()

            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))

            // The live timeline focuses in the thread root
            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Success(A_THREAD_ID.asEventId()))

            // The thread is opened
            openThreadLambda.assertions()
                .isCalledOnce()
                .with(
                    value(threadId),
                    value(AN_EVENT_ID),
                )
        }
    }

    @Test
    fun `present - focus on event in a thread when in the same thread just moves the focus`() = runTest {
        val threadId = A_THREAD_ID
        val detachedTimeline = FakeTimeline(
            mode = Timeline.Mode.FocusedOnEvent(AN_EVENT_ID_2),
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID,
                        event = anEventTimelineItem(),
                    )
                )
            )
        )
        val liveTimeline = FakeTimeline(
            mode = Timeline.Mode.Thread(threadId),
            timelineItems = flowOf(emptyList())
        )
        val room = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) },
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                threadRootIdForEventResult = { _ -> Result.success(threadId) },
            ),
        )
        val openThreadLambda = lambdaRecorder { _: ThreadId, _: EventId? -> }
        val navigator = FakeMessagesNavigator(onOpenThreadLambda = openThreadLambda)
        val presenter = createTimelinePresenter(
            room = room,
            timeline = liveTimeline,
            messagesNavigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))

            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }

            advanceUntilIdle()

            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))

            // The live timeline focuses in the event directly since we are already in the thread
            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Success(AN_EVENT_ID))

            // The thread is not opened again
            openThreadLambda.assertions().isNeverCalled()
        }
    }

    @Test
    fun `present - focus on event in a thread when in a different thread opens the new thread`() = runTest {
        val currentThreadId = A_THREAD_ID
        val detachedTimeline = FakeTimeline(
            mode = Timeline.Mode.FocusedOnEvent(AN_EVENT_ID_2),
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID,
                        event = anEventTimelineItem(),
                    )
                )
            )
        )
        val liveTimeline = FakeTimeline(
            mode = Timeline.Mode.Thread(currentThreadId),
            timelineItems = flowOf(emptyList())
        )
        val room = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) },
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                // Use a different thread id
                threadRootIdForEventResult = { _ -> Result.success(A_THREAD_ID_2) },
            ),
        )
        val openThreadLambda = lambdaRecorder { _: ThreadId, _: EventId? -> }
        val navigator = FakeMessagesNavigator(onOpenThreadLambda = openThreadLambda)
        val presenter = createTimelinePresenter(
            room = room,
            timeline = liveTimeline,
            messagesNavigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))

            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }

            advanceUntilIdle()

            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))

            // The live timeline focuses in the event directly since we are already in the thread
            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Success(A_THREAD_ID_2.asEventId()))

            // The other thread is opened
            openThreadLambda.assertions()
                .isCalledOnce()
                .with(
                    value(A_THREAD_ID_2),
                    value(AN_EVENT_ID),
                )
        }
    }

    @Test
    fun `present - focus on event in a the room while in a thread of that room opens the room`() = runTest {
        val detachedTimeline = FakeTimeline(
            mode = Timeline.Mode.FocusedOnEvent(AN_EVENT_ID_2),
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        uniqueId = A_UNIQUE_ID,
                        event = anEventTimelineItem(),
                    )
                )
            )
        )
        val liveTimeline = FakeTimeline(
            mode = Timeline.Mode.Thread(A_THREAD_ID),
            timelineItems = flowOf(emptyList())
        )
        val room = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) },
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                // The event is in the main timeline, not in a thread
                threadRootIdForEventResult = { _ -> Result.success(null) },
            ),
        )
        val openRoomLambda = lambdaRecorder { _: RoomId, _: EventId?, _: List<String> -> }
        val navigator = FakeMessagesNavigator(onNavigateToRoomLambda = openRoomLambda)
        val presenter = createTimelinePresenter(
            room = room,
            timeline = liveTimeline,
            messagesNavigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(TimelineEvent.FocusOnEvent(AN_EVENT_ID))

            awaitItem().also { state ->
                assertThat(state.focusedEventId).isEqualTo(AN_EVENT_ID)
                assertThat(state.focusRequestState).isEqualTo(FocusRequestState.Requested(AN_EVENT_ID, Duration.ZERO))
            }

            advanceUntilIdle()

            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.Loading(AN_EVENT_ID))

            // The focus state will reset
            assertThat(awaitItem().focusRequestState).isEqualTo(FocusRequestState.None)

            // The room is opened again
            openRoomLambda.assertions()
                .isCalledOnce()
                .with(
                    value(room.roomId),
                    value(AN_EVENT_ID),
                    value(emptyList<String>())
                )
        }
    }

    @Test
    fun `present - show shield hide shield`() = runTest {
        val presenter = createTimelinePresenter()
        val shield = aCriticalShield()
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.messageShieldDialogData).isNull()
            val shieldData = MessageShieldData(shield, null, null)
            initialState.eventSink(TimelineEvent.ShowShieldDialog(shieldData))
            awaitItem().also { state ->
                assertThat(state.messageShieldDialogData).isEqualTo(shieldData)
                state.eventSink(TimelineEvent.HideShieldDialog)
            }
            awaitItem().also { state ->
                assertThat(state.messageShieldDialogData).isNull()
            }
        }
    }

    @Test
    fun `present - when room member info is loaded, read receipts info should be updated`() = runTest {
        val timeline = FakeTimeline(
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        A_UNIQUE_ID,
                        anEventTimelineItem(
                            sender = A_USER_ID,
                            receipts = persistentListOf(
                                Receipt(
                                    userId = A_USER_ID,
                                    timestamp = 0L,
                                )
                            )
                        )
                    )
                )
            )
        )
        val room = FakeJoinedRoom(
            liveTimeline = timeline,
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
        ).apply {
            givenRoomMembersState(RoomMembersState.Unknown)
        }

        val avatarUrl = "https://domain.com/avatar.jpg"

        val presenter = createTimelinePresenter(timeline, room)
        presenter.test {
            val initialState = consumeItemsUntilPredicate(30.seconds) { it.timelineItems.isNotEmpty() }.last()
            val event = initialState.timelineItems.first() as TimelineItem.Event
            assertThat(event.senderAvatar.url).isNull()
            assertThat(event.readReceiptState.receipts.first().avatarData.url).isNull()

            room.givenRoomMembersState(
                RoomMembersState.Ready(
                    persistentListOf(aRoomMember(userId = A_USER_ID, avatarUrl = avatarUrl))
                )
            )

            // Entry navigation may publish its frozen boundary while membership is resolving.
            // Wait for the actual avatar update rather than assuming the next emission is it.
            val updatedEvent = consumeItemsUntilPredicate { state ->
                (state.timelineItems.firstOrNull() as? TimelineItem.Event)?.readReceiptState
                    ?.receipts?.firstOrNull()?.avatarData?.url == avatarUrl
            }.last().timelineItems.first() as TimelineItem.Event
            assertThat(updatedEvent.readReceiptState.receipts.first().avatarData.url).isEqualTo(avatarUrl)
        }
    }

    @Test
    fun `present - timeline room info includes predecessor room when room has predecessor`() = runTest {
        val predecessorRoomId = RoomId("!predecessor:server.org")
        val predecessorRoom = PredecessorRoom(roomId = predecessorRoomId)

        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                predecessorRoomResult = { predecessorRoom }
            ),
        )

        val presenter = createTimelinePresenter(room = room)
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.timelineRoomInfo.predecessorRoom).isNotNull()
            assertThat(initialState.timelineRoomInfo.predecessorRoom?.roomId).isEqualTo(predecessorRoomId)
        }
    }

    @Test
    fun `present - timeline room info no predecessor`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                predecessorRoomResult = { null }
            ),
        )
        val presenter = createTimelinePresenter(room = room)
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.timelineRoomInfo.predecessorRoom).isNull()
        }
    }

    @Test
    fun `present - timeline event navigate to room`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
        )
        val onNavigateToRoomLambda = lambdaRecorder<RoomId, EventId?, List<String>, Unit> { _, _, _ -> }
        val navigator = FakeMessagesNavigator(
            onNavigateToRoomLambda = onNavigateToRoomLambda
        )
        val presenter = createTimelinePresenter(room = room, messagesNavigator = navigator)
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(TimelineEvent.NavigateToPredecessorOrSuccessorRoom(A_ROOM_ID))
            assert(onNavigateToRoomLambda)
                .isCalledOnce()
                .with(
                    value(A_ROOM_ID),
                    // No event id when navigating to a successor/predecessor room
                    value(null),
                    value(emptyList<String>())
                )
        }
    }

    @Test
    fun `new message navigation preserves frozen target after eight seconds and consumes it only after render`() = runTest {
        val old = EventId("\$old")
        val first = EventId("\$first-new")
        val newest = EventId("\$newest-new")
        val references = FakeMessageReferenceStore(io.element.android.features.messages.api.MessageReferenceState(
            readingNavigation = mapOf(A_ROOM_ID.value to io.element.android.features.messages.api.RoomReadingNavigation(latestSeenEventId = old.value)),
        ))
        val timeline = FakeTimeline(timelineItems = flowOf(listOf(
            MatrixTimelineItem.Event(UniqueId("old"), anEventTimelineItem(eventId = old, content = aMessageContent("navigation fixture"))),
            MatrixTimelineItem.Event(UniqueId("first-new"), anEventTimelineItem(eventId = first, content = aMessageContent("navigation fixture"))),
            MatrixTimelineItem.Event(UniqueId("newest-new"), anEventTimelineItem(eventId = newest, content = aMessageContent("navigation fixture"))),
        )))
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val controller = TimelineController(room, timeline, messageReferenceStore = references)
        val presenter = createTimelinePresenter(timeline = timeline, room = room, messageReferenceStore = references, controller = controller)
        try {
            presenter.test {
                val captured = consumeItemsUntilPredicate { it.newMessageNavigation.firstNewEventId == first.value }.last()
                assertThat(captured.newMessageNavigation.batchEndEventId).isEqualTo(newest.value)
                captured.eventSink(TimelineEvent.NewMessageHintGeometry(aboveViewport = true, targetVisible = false, atLiveEdge = true))
                val activated = consumeItemsUntilPredicate { it.newMessageNavigation.hintActivated }.last()
                activated.eventSink(TimelineEvent.NewMessageHintElapsed(8_000L))
                val expired = consumeItemsUntilPredicate { it.newMessageNavigation.hintRemainingMillis == 0L }.last()
                assertThat(expired.newMessageNavigation.firstNewEventId).isEqualTo(first.value)
                expired.eventSink(TimelineEvent.ViewFirstNewMessage)
                val focused = consumeItemsUntilPredicate { it.focusRequestState is FocusRequestState.Success }.last()
                assertThat(focused.newMessageNavigation.firstNewEventId).isEqualTo(first.value)
                assertThat(focused.navigatingToFirstNewMessage).isTrue()
                focused.eventSink(TimelineEvent.OnFocusEventRender)
                val rendered = consumeItemsUntilPredicate { it.newMessageNavigation.firstNewEventId == null }.last()
                assertThat(rendered.navigatingToFirstNewMessage).isFalse()
                assertThat(rendered.newMessageDividerEventId).isEqualTo(first)
                cancelAndIgnoreRemainingEvents()
            }
        } finally { controller.close() }
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitFirstItem(): T {
        return awaitItem()
    }

    private suspend fun TimelinePresenter.test(validate: suspend TurbineTestContext<TimelineState>.() -> Unit) {
        // Nodes expose Molecule state through StateFlow, which suppresses equal states.
        // Preserve every distinct state here, including asynchronous navigation and removal transitions.
        moleculeFlow(RecompositionMode.Immediate) { present() }
            .distinctUntilChanged()
            .test { validate() }
    }

    private fun roomPermissions(
        canRedactOther: Boolean = false,
        canRedactOwn: Boolean = true,
        canSendMessage: Boolean = true,
        canSendReaction: Boolean = true,
        canPinUnpin: Boolean = false,
    ) = FakeRoomPermissions(
        canSendMessage = { type ->
            when (type) {
                MessageEventType.RoomMessage -> canSendMessage
                MessageEventType.Reaction -> canSendReaction
                else -> lambdaError()
            }
        },
        canRedactOther = canRedactOther,
        canRedactOwn = canRedactOwn,
        canPinUnpin = canPinUnpin,
    )

    @Test
    fun `previous SDK failure retries through the failure indicator entry point`() = runTest {
        val called = CompletableDeferred<Unit>()
        val presenter = createTimelinePresenter()
        presenter.test {
            val initial = awaitFirstItem()
            val event = aMessageEvent(
                sendState = io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState.Failed.Unknown("previous failure"),
                sendHandleProvider = io.element.android.libraries.matrix.api.timeline.item.event.SendHandleProvider {
                    io.element.android.libraries.matrix.api.core.SendHandle { called.complete(Unit); Result.success(Unit) }
                },
            )
            initial.eventSink(TimelineEvent.RetrySend(event))
            called.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.createTimelinePresenter(
        timeline: Timeline = FakeTimeline(),
        room: FakeJoinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
        ),
        redactedVoiceMessageManager: RedactedVoiceMessageManager = FakeRedactedVoiceMessageManager(),
        messagesNavigator: FakeMessagesNavigator = FakeMessagesNavigator(),
        endPollAction: EndPollAction = FakeEndPollAction(),
        sendPollResponseAction: SendPollResponseAction = FakeSendPollResponseAction(),
        sessionPreferencesStore: InMemorySessionPreferencesStore = InMemorySessionPreferencesStore(),
        timelineItemIndexer: TimelineItemIndexer = TimelineItemIndexer(),
        featureFlagService: FakeFeatureFlagService = FakeFeatureFlagService(),
        liveLocationShareManager: FakeActiveLiveLocationShareManager = FakeActiveLiveLocationShareManager(),
        dataProtectionPolicyProvider: FakeDataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(allowGroupReadReceipts = true),
        messageReferenceStore: FakeMessageReferenceStore = FakeMessageReferenceStore(),
        redactionReasonResolver: RedactionReasonResolver = RedactionReasonResolver.Noop,
        hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory = HiddenMessageRecoveryInventory.Noop,
        controller: TimelineController = TimelineController(room, timeline),
    ): TimelinePresenter {
        return TimelinePresenter(
            timelineItemsFactoryCreator = aTimelineItemsFactoryCreator(),
            room = room,
            dispatchers = testCoroutineDispatchers(),
            sessionCoroutineScope = this,
            navigator = messagesNavigator,
            redactedVoiceMessageManager = redactedVoiceMessageManager,
            endPollAction = endPollAction,
            sendPollResponseAction = sendPollResponseAction,
            sessionPreferencesStore = sessionPreferencesStore,
            timelineItemIndexer = timelineItemIndexer,
            timelineController = controller,
            resolveVerifiedUserSendFailurePresenter = { aResolveVerifiedUserSendFailureState() },
            typingNotificationPresenter = { aTypingNotificationState() },
            roomCallStatePresenter = { aStandByCallState() },
            featureFlagService = featureFlagService,
            analyticsService = FakeAnalyticsService(),
            liveLocationShareManager = liveLocationShareManager,
            dataProtectionPolicyProvider = dataProtectionPolicyProvider,
            dataProtectionPolicyRevisionProvider = object : DataProtectionPolicyRevisionProvider {
                override val policyRevision = MutableStateFlow(0L)
            },
            messageReferenceStore = messageReferenceStore,
            redactionReasonResolver = redactionReasonResolver,
            hiddenMessageRecoveryInventory = hiddenMessageRecoveryInventory,
            snackbarDispatcher = io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher(),
        )
    }
}
