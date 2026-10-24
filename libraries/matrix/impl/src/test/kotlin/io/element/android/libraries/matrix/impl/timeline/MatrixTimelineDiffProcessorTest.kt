/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.poll.PollAnswer
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.libraries.matrix.impl.timeline.item.event.EventTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.matrix.impl.timeline.item.virtual.VirtualTimelineItemMapper
import io.element.android.libraries.matrix.test.A_UNIQUE_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID_2
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentInvalidation
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineReleaseScopeOwnerToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId as RustEventOrTransactionId
import org.matrix.rustcomponents.sdk.VirtualTimelineItem as RustVirtualTimelineItem

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixTimelineDiffProcessorTest {
    private val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())

    private val anEvent = MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem())
    private val anEvent2 = MatrixTimelineItem.Event(A_UNIQUE_ID_2, anEventTimelineItem())

    @Test
    fun `owned initial render borrows two workers while actual visible work still progresses`() = runTest {
        verifyInitialRenderBootstrap()
    }

    @Test
    fun `initial render recognizes the measured rendered tail when all later text is still hidden`() = runTest {
        verifyInitialRenderBootstrap(anchorBeforeHidden = true)
    }

    @Test
    fun `initial render is opt in and unknown metadata or burn text never acquires bootstrap`() = runTest {
        verifyInitialRenderBootstrap(allowInitialRender = false)
        verifyInitialRenderBootstrap(metadataEligible = false)
        verifyInitialRenderBootstrap(burn = true)
    }

    @Test
    fun `leaving initial render retires pending eligibility and later raw arrivals cannot replenish it`() = runTest {
        verifyInitialRenderBootstrap(retire = true)
    }

    @Test
    fun `initial inactive viewport may arm once but cannot rearm after leaving active initial render`() = runTest {
        verifyInitialRenderBootstrap(initialInactive = true, retire = true)
    }

    @Test
    fun `unmeasured owned startup reads leave bootstrap workers free before the first viewport`() = runTest {
        verifyInitialRenderBootstrap(startBeforeViewport = true)
    }

    @Test
    fun `fully hidden initial room can reveal its first anchor through the original history lane`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val intents = mutableListOf<TimelineContentReleaseIntent>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) =
                items.map { it.withText("").withProtectedStatus(ProtectedMessageStatus.DECRYPTING) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true

            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                intents += timelineReleaseIntent
                if (item.uniqueIdValue() != "all-hidden-3") gate.await()
                val event = item.withText("first readable anchor") as MatrixTimelineItem.Event
                return event.copy(event = event.event.copy(content = (event.event.content as MessageContent).copy(isContentProtectionAuthorized = true)))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(174L)
        processor.activateCommitScope(scope)
        val raw = (0 until 4).map { index -> FakeFfiTimelineItem(uniqueId = "all-hidden-$index", asEventResult = aRustEventTimelineItem(
            eventOrTransactionId = RustEventOrTransactionId.EventId("\$all-hidden-$index"),
            content = aRustTimelineItemContentMsgLike("encrypted:$index"),
        )) }
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(raw))))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertThat(intents).isNotEmpty()
        assertThat(intents.all { it.releaseClass == TimelineContentReleaseClass.HISTORY_PREFETCH }).isTrue()
        assertThat(timelineItems.value.any { it.uniqueIdValue() == "all-hidden-3" && it.textBody() == "first readable anchor" }).isTrue()
        gate.complete(Unit)
        runCurrent()
        processor.invalidateCommitScope(scope)
    }

    private suspend fun TestScope.verifyInitialRenderBootstrap(
        allowInitialRender: Boolean = true,
        anchorBeforeHidden: Boolean = false,
        metadataEligible: Boolean = true,
        burn: Boolean = false,
        retire: Boolean = false,
        initialInactive: Boolean = false,
        startBeforeViewport: Boolean = false,
    ) {
        val gate = CompletableDeferred<Unit>()
        val observed = mutableListOf<Pair<String, TimelineContentReleaseIntent>>()
        fun authorized(item: MatrixTimelineItem): MatrixTimelineItem {
            val event = item.withText("authorized:${item.uniqueIdValue()}") as MatrixTimelineItem.Event
            return event.copy(event = event.event.copy(content = (event.event.content as MessageContent).copy(isContentProtectionAuthorized = true)))
        }
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun isInitialRenderText(item: MatrixTimelineItem): Boolean =
                metadataEligible && item.uniqueIdValue().startsWith("initial-text-")

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? =
                item.takeIf { it.uniqueIdValue() == "initial-anchor" }?.let(::authorized)

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = item.uniqueIdValue() != "initial-anchor"

            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> = items.map { item ->
                if (item.uniqueIdValue() == "initial-anchor") item.withText("").withProtectedStatus(ProtectedMessageStatus.DECRYPTING) else {
                    val text = item.uniqueIdValue().startsWith("initial-text-")
                    val event = item.withText("").withProtectedStatus(
                        if (text) ProtectedMessageStatus.DECRYPTING else ProtectedMessageStatus.ATTACHMENT_DECRYPTING
                    ) as MatrixTimelineItem.Event
                    event.copy(event = event.event.copy(content = (event.event.content as MessageContent).copy(isBurnAfterRead = burn && text)))
                }
            }

            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                observed += item.uniqueIdValue() to timelineReleaseIntent
                if (item.uniqueIdValue() != "initial-visible") gate.await()
                return authorized(item)
            }
        }
        fun raw(id: String) = FakeFfiTimelineItem(uniqueId = id, asEventResult = aRustEventTimelineItem(
            eventOrTransactionId = RustEventOrTransactionId.EventId("\$$id"), content = aRustTimelineItemContentMsgLike("encrypted:$id"),
        ))
        val old = (0 until 20).map { raw("initial-old-$it") }
        val hidden = (0 until 4).map { raw("initial-text-$it") }
        val initial = old + if (anchorBeforeHidden) listOf(raw("initial-anchor")) + hidden else hidden + raw("initial-anchor")
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(173L)
        processor.activateCommitScope(scope)
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(initial))))
        if (startBeforeViewport) {
            runCurrent()
            // Before measurement only the original history worker may start. Otherwise
            // two foreground workers wait on HISTORY tickets that cannot be upgraded.
            assertThat(observed).hasSize(1)
            assertThat(observed.all { it.second.releaseClass == TimelineContentReleaseClass.HISTORY_PREFETCH }).isTrue()
        }
        var viewportGeneration = 0L
        if (initialInactive) processor.updateViewport(scope, ++viewportGeneration, emptyList(), allowInitialRender = false)
        processor.updateViewport(scope, ++viewportGeneration, listOf(UniqueId("initial-anchor")), allowInitialRender)
        runCurrent()
        val eligible = allowInitialRender && metadataEligible && !burn
        val boot = observed.filter { it.second.releaseClass == TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP }
        assertThat(boot.size).isEqualTo(if (eligible) 2 else 0)
        assertThat(boot.map { it.first }).containsNoDuplicates()
        if (eligible) {
            assertThat(boot.map { it.first }).containsExactlyElementsIn(
                if (startBeforeViewport) listOf("initial-text-2", "initial-text-1") else listOf("initial-text-3", "initial-text-2")
            )
            assertThat(processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L,
                listOf(TimelineDiff.Append(listOf(raw("initial-visible"))))))).isTrue()
            processor.updateViewport(scope, ++viewportGeneration, listOf(UniqueId("initial-visible"), UniqueId("initial-anchor")), !retire)
            runCurrent()
            assertThat(observed.map { it.first }).contains("initial-visible")
            assertThat(observed.single { it.first == "initial-visible" }.second.releaseClass)
                .isEqualTo(TimelineContentReleaseClass.FOREGROUND_VISIBLE)
            if (retire) {
                processor.commitDiffs(TimelineDiffTransaction(scope, 3L, 1L, listOf(TimelineDiff.Append(listOf(raw("initial-text-late"))))))
                processor.updateViewport(scope, ++viewportGeneration, listOf(UniqueId("initial-visible")), allowInitialRender = true)
            }
        }
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        if (retire) assertThat(observed.filter { it.first == "initial-text-late" }
            .none { it.second.releaseClass == TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP }).isTrue()
        processor.invalidateCommitScope(scope)
    }

    @Test
    fun `initial hidden recent text keeps history authorization ahead of older shell backlog`() = runTest {
        verifyInitialHiddenHistoryScheduling(tailOnScreen = true)
    }

    @Test
    fun `initial old viewport does not expedite hidden recent text`() = runTest {
        verifyInitialHiddenHistoryScheduling(tailOnScreen = false)
    }

    @Test
    fun `initial tail hint retires before dispatch when reader moves to old messages`() = runTest {
        verifyInitialHiddenHistoryScheduling(tailOnScreen = true, leaveTailBeforeDispatch = true)
    }

    @Test
    fun `initial hidden history hint ignores a trailing virtual item`() = runTest {
        verifyInitialHiddenHistoryScheduling(tailOnScreen = true, trailingVirtualItem = true)
    }

    @Test
    fun `initial history hint retires without moving FIFO or erasing incremental recent arrivals`() {
        fun item(id: String) = MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem())
        val queue = PendingDecryptQueue(maximumSize = 8, historyFairnessVisibleBurst = 30)
        queue.offer(
            visible = emptyList(),
            history = listOf(item("older-backlog"), item("hidden-text")),
            recentHistory = listOf(item("arrival-a"), item("arrival-b")),
            rawStateGeneration = 7L,
            timelineScopeId = "startup-owner",
            releaseGeneration = 1L,
        )
        queue.reprioritize(
            visibleKeys = emptyList(), shellCandidates = emptyList(), rawStateGeneration = 7L,
            timelineScopeId = "startup-owner", releaseGeneration = 1L,
            preferredHistoryKeys = listOf("event:hidden-text"),
        )
        val firstArrival = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(firstArrival.key).isEqualTo("event:arrival-a")
        assertThat(firstArrival.recentHistory).isTrue()
        queue.complete(firstArrival)

        // The next real viewport is away from the tail. This retires only bootstrap preference.
        queue.reprioritize(
            visibleKeys = emptyList(), shellCandidates = emptyList(), rawStateGeneration = 7L,
            timelineScopeId = "startup-owner", releaseGeneration = 2L,
        )
        val secondArrival = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(secondArrival.key).isEqualTo("event:arrival-b")
        assertThat(secondArrival.recentHistory).isTrue()
        assertThat(secondArrival.releaseGeneration).isEqualTo(2L)
        queue.complete(secondArrival)
        val backlog = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(backlog.key).isEqualTo("event:older-backlog")
        queue.complete(backlog)
        val hidden = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(hidden.key).isEqualTo("event:hidden-text")
        assertThat(hidden.priority).isEqualTo(DecryptPriority.HISTORY)
        assertThat(hidden.recentHistory).isFalse()
        assertThat(hidden.timelineScopeId).isEqualTo("startup-owner")
        assertThat(hidden.releaseGeneration).isEqualTo(2L)
    }

    @Test
    fun `initial preferred history dispatches consume the existing backlog fairness budget`() {
        fun item(id: String) = MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem())
        val queue = PendingDecryptQueue(maximumSize = 8, historyFairnessVisibleBurst = 2)
        queue.offer(
            visible = emptyList(),
            history = listOf(item("older-backlog")) + (0 until 4).map { item("hidden-$it") },
            rawStateGeneration = 3L, timelineScopeId = "startup-owner", releaseGeneration = 1L,
        )
        queue.reprioritize(
            visibleKeys = emptyList(), shellCandidates = emptyList(), rawStateGeneration = 3L,
            timelineScopeId = "startup-owner", releaseGeneration = 1L,
            preferredHistoryKeys = (3 downTo 0).map { "event:hidden-$it" },
        )
        val keys = (0 until 3).map {
            val work = checkNotNull(queue.pollVisibleOrHistory())
            assertThat(work.priority).isEqualTo(DecryptPriority.HISTORY)
            assertThat(work.recentHistory).isFalse()
            queue.complete(work)
            work.key
        }
        assertThat(keys).containsExactly("event:hidden-3", "event:hidden-2", "event:older-backlog").inOrder()
    }

    private suspend fun TestScope.verifyInitialHiddenHistoryScheduling(
        tailOnScreen: Boolean,
        leaveTailBeforeDispatch: Boolean = false,
        trailingVirtualItem: Boolean = false,
    ) {
        val olderAttachmentGate = CompletableDeferred<Unit>()
        val observedIntents = mutableListOf<Pair<String, TimelineContentReleaseIntent>>()
        fun authorized(item: MatrixTimelineItem, body: String): MatrixTimelineItem {
            val projected = item.withText(body) as MatrixTimelineItem.Event
            val content = projected.event.content as MessageContent
            return projected.copy(event = projected.event.copy(content = content.copy(isContentProtectionAuthorized = true)))
        }
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> = items.map { item ->
                when {
                    item.uniqueIdValue() == "startup-qa" -> item.withText("latest QA already readable")
                    item.uniqueIdValue().startsWith("startup-attachment-") ->
                        item.withText("").withProtectedStatus(ProtectedMessageStatus.ATTACHMENT_DECRYPTING)
                    else -> item.withText("").withProtectedStatus(ProtectedMessageStatus.DECRYPTING)
                }
            }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean =
                item is MatrixTimelineItem.Event && item.uniqueIdValue() != "startup-qa"

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? =
                item.takeIf { it.uniqueIdValue() == "startup-qa" }?.let { authorized(it, "latest QA already readable") }

            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                if (item !is MatrixTimelineItem.Event) return item
                val identity = item.uniqueIdValue()
                observedIntents += identity to timelineReleaseIntent
                if (identity.startsWith("startup-attachment-")) olderAttachmentGate.await()
                return authorized(item, "authorized:$identity")
            }
        }
        fun raw(identity: String) = FakeFfiTimelineItem(
            uniqueId = identity,
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$$identity"),
                content = aRustTimelineItemContentMsgLike("encrypted:$identity"),
            ),
        )
        val expectedRawOrder = (0 until 40).map { "startup-attachment-$it" } +
            (0 until 4).map { "startup-text-$it" } + "startup-qa" +
            if (trailingVirtualItem) listOf("startup-read-marker") else emptyList()
        val rawOrder = expectedRawOrder.map { identity ->
            if (identity == "startup-read-marker") {
                FakeFfiTimelineItem(uniqueId = identity, asVirtualResult = RustVirtualTimelineItem.ReadMarker)
            } else {
                raw(identity)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(172L)
        processor.activateCommitScope(scope)
        assertThat(processor.commitDiffs(TimelineDiffTransaction(
            scope = scope,
            sequence = 1L,
            structuralEpoch = 1L,
            diffs = listOf(TimelineDiff.Reset(rawOrder)),
        ))).isTrue()

        // Hidden ordinary text has no layout node. The real viewport can therefore contain
        // older attachment shells and the already authorized latest QA item, but not that text.
        val measuredIds = (36 until 39).map { UniqueId("startup-attachment-$it") } +
            if (tailOnScreen) listOf(UniqueId("startup-qa")) else emptyList()
        assertThat(processor.updateViewport(scope, 1L, measuredIds)).isTrue()
        if (leaveTailBeforeDispatch) {
            assertThat(processor.updateViewport(scope, 2L, measuredIds.filterNot { it.value == "startup-qa" })).isTrue()
        }
        runCurrent()

        val textRequests = observedIntents.filter { it.first.startsWith("startup-text-") }
        if (!tailOnScreen || leaveTailBeforeDispatch) {
            assertThat(textRequests).isEmpty()
            assertThat(observedIntents.any { (identity, intent) ->
                identity.startsWith("startup-attachment-") && intent.releaseClass == TimelineContentReleaseClass.HISTORY_PREFETCH
            }).isTrue()
            return
        }
        assertThat(textRequests.map { it.first }).contains("startup-text-3")
        assertThat(textRequests.map { it.second.releaseClass }.toSet()).containsExactly(TimelineContentReleaseClass.HISTORY_PREFETCH)
        assertThat(textRequests.all { (identity, intent) ->
            intent.scopeId == "172" && intent.generation == 1L && intent.requestId == "event:$identity" && intent.eventId == "\$$identity"
        }).isTrue()
        assertThat(textRequests.none { UniqueId(it.first) in measuredIds }).isTrue()
        assertThat(observedIntents.any { (identity, intent) ->
            identity.startsWith("startup-attachment-") && intent.releaseClass == TimelineContentReleaseClass.HISTORY_PREFETCH
        }).isTrue()

        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        val projected = timelineItems.value
        assertThat(projected.map { it.uniqueIdValue() }).containsExactlyElementsIn(expectedRawOrder).inOrder()
        assertThat(projected.filter { it.uniqueIdValue().startsWith("startup-attachment-") }
            .all { it.protectedStatus() == ProtectedMessageStatus.ATTACHMENT_DECRYPTING }).isTrue()
        assertThat(projected.first { it.uniqueIdValue() == "startup-text-3" }.textBody()).isEqualTo("authorized:startup-text-3")
    }

    @Test
    fun `one arrival with media origins enabled preserves all unchanged historical text references`() = runTest {
        val history = (1..500).map { index ->
            MatrixTimelineItem.Event(UniqueId("history-$index"), anEventTimelineItem(eventId = EventId("\$history-$index")))
        }
        timelineItems.value = history
        val processor = createMatrixTimelineDiffProcessor(timelineItems, mediaRoomId = "!room:server")
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem())))
        assertThat(timelineItems.value).hasSize(501)
        history.indices.forEach { index -> assertThat(timelineItems.value[index]).isSameInstanceAs(history[index]) }
    }

    @Test
    fun `room summary carrier snapshot belongs only to accepted live Matrix commits`() = runTest {
        val snapshots = mutableListOf<Pair<String, MatrixTimelineItem.Event?>>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun updateRoomSummaryCarrier(scopeId: String, latestEvent: MatrixTimelineItem.Event?) {
                snapshots += scopeId to latestEvent
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems = timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(98L)
        processor.activateCommitScope(scope)
        val event = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "summary-source")
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(event)))))
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots.single().second?.eventId).isEqualTo(AN_EVENT_ID)
        processor.invalidateCommitScope(scope)
        processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L, listOf(TimelineDiff.Clear)))
        assertThat(snapshots).hasSize(1)
    }

    @Test
    fun `Append adds new entries at the end of the list`() = runTest {
        timelineItems.value = listOf(anEvent)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Append(listOf(FakeFfiTimelineItem()))))
        assertThat(timelineItems.value.count()).isEqualTo(2)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
            MatrixTimelineItem.Other,
        )
    }

    @Test
    fun `PushBack adds a new entry at the end of the list`() = runTest {
        timelineItems.value = listOf(anEvent)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem())))
        assertThat(timelineItems.value.count()).isEqualTo(2)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
            MatrixTimelineItem.Other,
        )
    }

    @Test
    fun `PushFront inserts a new entry at the start of the list`() = runTest {
        timelineItems.value = listOf(anEvent)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.PushFront(FakeFfiTimelineItem())))
        assertThat(timelineItems.value.count()).isEqualTo(2)
        assertThat(timelineItems.value).containsExactly(
            MatrixTimelineItem.Other,
            anEvent,
        )
    }

    @Test
    fun `Set replaces an entry at some index`() = runTest {
        timelineItems.value = listOf(anEvent, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Set(1u, FakeFfiTimelineItem())))
        assertThat(timelineItems.value.count()).isEqualTo(2)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
            MatrixTimelineItem.Other
        )
    }

    @Test
    fun `Insert inserts a new entry at the provided index`() = runTest {
        timelineItems.value = listOf(anEvent, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Insert(1u, FakeFfiTimelineItem())))
        assertThat(timelineItems.value.count()).isEqualTo(3)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
            MatrixTimelineItem.Other,
            anEvent2,
        )
    }

    @Test
    fun `Remove removes an entry at some index`() = runTest {
        timelineItems.value = listOf(anEvent, MatrixTimelineItem.Other, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Remove(1u)))
        assertThat(timelineItems.value.count()).isEqualTo(2)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
            anEvent2,
        )
    }

    @Test
    fun `PopBack removes an entry at the end of the list`() = runTest {
        timelineItems.value = listOf(anEvent, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.PopBack))
        assertThat(timelineItems.value.count()).isEqualTo(1)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
        )
    }

    @Test
    fun `PopFront removes an entry at the start of the list`() = runTest {
        timelineItems.value = listOf(anEvent, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.PopFront))
        assertThat(timelineItems.value.count()).isEqualTo(1)
        assertThat(timelineItems.value).containsExactly(
            anEvent2,
        )
    }

    @Test
    fun `Clear removes all the entries`() = runTest {
        timelineItems.value = listOf(anEvent, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Clear))
        assertThat(timelineItems.value).isEmpty()
    }

    @Test
    fun `Truncate removes all entries after the provided length`() = runTest {
        timelineItems.value = listOf(anEvent, MatrixTimelineItem.Other, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Truncate(1u)))
        assertThat(timelineItems.value.count()).isEqualTo(1)
        assertThat(timelineItems.value).containsExactly(
            anEvent,
        )
    }

    @Test
    fun `Reset removes all entries and add the provided ones`() = runTest {
        timelineItems.value = listOf(anEvent, MatrixTimelineItem.Other, anEvent2)
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem()))))
        assertThat(timelineItems.value.count()).isEqualTo(1)
        assertThat(timelineItems.value).containsExactly(
            MatrixTimelineItem.Other,
        )
    }

    @Test
    fun `a transaction from an older subscription scope cannot commit`() = runTest {
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        val firstScope = TimelineCommitScope(1L)
        val secondScope = TimelineCommitScope(2L)
        val first = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "first-scope")
        val stale = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "stale-scope")
        val current = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "current-scope")

        processor.activateCommitScope(firstScope)
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(firstScope, sequence = 1L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(first))))
            )
        ).isTrue()

        processor.activateCommitScope(secondScope)
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(
                    firstScope,
                    sequence = 2L,
                    structuralEpoch = 1L,
                    diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(stale))),
                )
            )
        ).isFalse()
        assertThat(timelineItems.value.single().uniqueIdValue()).isEqualTo("first-scope")

        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(secondScope, sequence = 1L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(current))))
            )
        ).isTrue()
        assertThat(timelineItems.value.single().uniqueIdValue()).isEqualTo("current-scope")
    }

    @Test
    fun `an older structural epoch cannot overwrite a newer atomic commit`() = runTest {
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        val scope = TimelineCommitScope(1L)
        val current = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "epoch-two")
        val stale = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "epoch-one")

        processor.activateCommitScope(scope)
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(
                    scope,
                    sequence = 1L,
                    structuralEpoch = 2L,
                    diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(current))),
                )
            )
        ).isTrue()
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(
                    scope,
                    sequence = 2L,
                    structuralEpoch = 1L,
                    diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(stale))),
                )
            )
        ).isFalse()
        assertThat(timelineItems.value.single().uniqueIdValue()).isEqualTo("epoch-two")
    }

    @Test
    fun `an out of order sequence in the active scope is rejected`() = runTest {
        val processor = createMatrixTimelineDiffProcessor(timelineItems)
        val scope = TimelineCommitScope(1L)
        val skipped = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "sequence-two")
        val current = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "sequence-one")

        processor.activateCommitScope(scope)
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(scope, sequence = 2L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(skipped))))
            )
        ).isFalse()
        assertThat(timelineItems.value).isEmpty()
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(scope, sequence = 1L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(current))))
            )
        ).isTrue()
        assertThat(timelineItems.value.single().uniqueIdValue()).isEqualTo("sequence-one")
    }

    @Test
    fun `authorization change during scoped atomic commit publishes only the safe shell`() = runTest {
        val shellLabel = ""
        var authorizationRevisionCalls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override fun authorizationRevision(): Long =
                if (++authorizationRevisionCalls == 1) 0L else 1L
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(1L)
        val item = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "protected-item")

        processor.activateCommitScope(scope)
        assertThat(
            processor.commitDiffs(
                TimelineDiffTransaction(scope, sequence = 1L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(item))))
            )
        ).isTrue()

        assertThat(authorizationRevisionCalls).isAtLeast(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)
    }

    @Test
    fun `cancellation cannot leave an accepted scoped transaction half committed`() = runTest {
        val shellGate = CompletableDeferred<Unit>()
        val shellStarted = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> {
                shellStarted.complete(Unit)
                shellGate.await()
                return items.map { it.withText("") }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(1L)
        val item = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "atomic-cancel")

        processor.activateCommitScope(scope)
        val commitJob = launch {
            processor.commitDiffs(
                TimelineDiffTransaction(scope, sequence = 1L, structuralEpoch = 1L, diffs = listOf(TimelineDiff.Reset(listOf(item))))
            )
        }
        runCurrent()
        assertThat(shellStarted.isCompleted).isTrue()

        commitJob.cancel()
        shellGate.complete(Unit)
        runCurrent()
        commitJob.join()

        assertThat(timelineItems.value.single().uniqueIdValue()).isEqualTo("atomic-cancel")
        assertThat(timelineItems.value.single().textBody()).isEmpty()
    }

    @Test
    fun `protected decrypt runs only for added or replaced events`() = runTest {
        timelineItems.value = emptyList()
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return item
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        processor.postDiffs(listOf(TimelineDiff.Set(0u, FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        assertThat(decryptCount).isEqualTo(2)
    }

    @Test
    fun `scoped decrypt forwards local release intent and closes the same scope`() = runTest {
        val observedIntents = mutableListOf<TimelineContentReleaseIntent>()
        val cancelledRequests = mutableListOf<Pair<String, Set<String>>>()
        val openedScopes = mutableListOf<String>()
        val closedScopes = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(
                item: MatrixTimelineItem,
                timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MatrixTimelineItem {
                observedIntents += timelineReleaseIntent
                return item
            }

            override fun cancelTimelineReleaseRequests(scopeId: String, requestIds: Set<String>) {
                cancelledRequests += scopeId to requestIds
            }

            override fun openTimelineReleaseScope(scopeId: String): String {
                openedScopes += scopeId
                return "owner-handle:$scopeId"
            }

            override fun closeTimelineReleaseScope(scopeId: String) {
                closedScopes += scopeId
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val scope = TimelineCommitScope(7L)
        val item = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "release-intent")

        processor.activateCommitScope(scope)
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Reset(listOf(item))),
            )
        )
        runCurrent()

        val intent = observedIntents.single()
        assertThat(intent.scopeId).isEqualTo("owner-handle:7")
        assertThat(intent.generation).isEqualTo(0L)
        // Generation zero has no measured visibility. Startup reads retain the original TIMELINE purpose.
        assertThat(intent.releaseClass).isEqualTo(TimelineContentReleaseClass.HISTORY_PREFETCH)
        assertThat(intent.readPurpose).isEqualTo(io.element.android.security.contentprotection.api.TimelineContentReadPurpose.TIMELINE)
        assertThat(intent.roomId).isNull()
        assertThat(intent.eventId).isNotEmpty()
        assertThat(openedScopes).containsExactly("7")
        assertThat(cancelledRequests.single().first).isEqualTo("owner-handle:7")
        assertThat(cancelledRequests.single().second).containsExactly(intent.requestId)

        processor.invalidateCommitScope(scope)

        assertThat(closedScopes).containsExactly("owner-handle:7")
    }

    @Test
    fun `worker parent closure retires owner even without flow unsubscribe`() = runTest {
        val closed = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun closeTimelineReleaseScope(scopeId: String) { closed += scopeId }
        }
        val parent = Job(backgroundScope.coroutineContext[Job])
        val workers = CoroutineScope(backgroundScope.coroutineContext + parent)
        val processor = createMatrixTimelineDiffProcessor(protectedContentAdapter = adapter, workerScope = workers)
        processor.activateCommitScope(TimelineCommitScope(70L))
        runCurrent()
        parent.cancel()
        runCurrent()
        assertThat(closed).containsExactly("70")
        assertThat(runCatching { processor.activateCommitScope(TimelineCommitScope(71L)) }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `scope exit cancels blocked consumers and replacement uses surviving workers`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var cancelled = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) =
                items.map { it.withText("").withProtectedStatus(ProtectedMessageStatus.ATTACHMENT_DECRYPTING) }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                if (timelineReleaseIntent.scopeId == "71") {
                    started++
                    try { gate.await() } catch (failure: CancellationException) { cancelled++; throw failure }
                }
                return item.withText("ready")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val old = TimelineCommitScope(71L)
        processor.activateCommitScope(old)
        val oldItems = List(MatrixTimelineDiffProcessor.DECRYPT_WORKER_COUNT) { i ->
            FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "old-$i")
        }
        processor.commitDiffs(TimelineDiffTransaction(old, 1L, 1L, listOf(TimelineDiff.Reset(oldItems))))
        // Measure the actual pending rows so this owner-exit case still exercises all four workers.
        processor.updateViewport(old, 1L, oldItems.indices.map { UniqueId("old-$it") })
        runCurrent()
        assertThat(started).isEqualTo(oldItems.size)
        processor.invalidateCommitScope(old)
        val replacement = TimelineCommitScope(72L)
        processor.activateCommitScope(replacement)
        val fresh = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "fresh-owner")
        processor.commitDiffs(TimelineDiffTransaction(replacement, 1L, 1L, listOf(TimelineDiff.Reset(listOf(fresh)))))
        processor.updateViewport(replacement, 1L, listOf(UniqueId("fresh-owner")))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1L)
        runCurrent()
        assertThat(cancelled).isEqualTo(oldItems.size)
        assertThat(gate.isCompleted).isFalse()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("ready")
    }

    @Test
    fun `late scope invalidation cannot close the replacement release owner`() = runTest {
        val openedScopes = mutableListOf<Pair<String, String>>()
        val closedOwnerHandles = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun openTimelineReleaseScope(scopeId: String): String {
                val ownerHandle = "owner-handle:$scopeId:${openedScopes.size + 1}"
                openedScopes += scopeId to ownerHandle
                return ownerHandle
            }

            override fun closeTimelineReleaseScope(scopeId: String) {
                closedOwnerHandles += scopeId
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val firstScope = TimelineCommitScope(41L)
        val replacementScope = TimelineCommitScope(42L)

        processor.activateCommitScope(firstScope)
        processor.activateCommitScope(replacementScope)

        assertThat(openedScopes).containsExactly(
            "41" to "owner-handle:41:1",
            "42" to "owner-handle:42:2",
        ).inOrder()
        assertThat(closedOwnerHandles).containsExactly("owner-handle:41:1")

        processor.invalidateCommitScope(firstScope)
        assertThat(closedOwnerHandles).containsExactly("owner-handle:41:1")

        processor.invalidateCommitScope(replacementScope)
        assertThat(closedOwnerHandles).containsExactly(
            "owner-handle:41:1",
            "owner-handle:42:2",
        ).inOrder()
    }

    @Test
    fun `measured viewport reprioritizes exact raw id with its release generation`() = runTest {
        val observedIntents = mutableListOf<TimelineContentReleaseIntent>()
        val viewportUpdates = mutableListOf<Triple<String, Long, List<String>>>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(
                item: MatrixTimelineItem,
                timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MatrixTimelineItem {
                observedIntents += timelineReleaseIntent
                return item
            }

            override fun updateTimelineReleaseViewport(
                scopeId: String, generation: Long, visibleRequestIds: List<String>,
                bootstrapRequestIds: List<String>,
            ) {
                viewportUpdates += Triple(scopeId, generation, visibleRequestIds)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val scope = TimelineCommitScope(19L)
        val targetId = UniqueId("viewport-target")
        val items = (0..MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(),
                uniqueId = if (index == 0) targetId.value else "viewport-filler-$index",
            )
        }

        processor.activateCommitScope(scope)
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Reset(items)),
            )
        )
        assertThat(processor.updateViewport(scope, 17L, listOf(targetId))).isTrue()
        assertThat(processor.updateViewport(scope, 17L, emptyList())).isFalse()
        assertThat(processor.updateViewport(TimelineCommitScope(20L), 18L, emptyList())).isFalse()
        runCurrent()

        val targetIntent = observedIntents.single { it.requestId == "event:${targetId.value}" }
        assertThat(targetIntent.generation).isEqualTo(17L)
        assertThat(targetIntent.releaseClass).isEqualTo(TimelineContentReleaseClass.FOREGROUND_VISIBLE)
        assertThat(viewportUpdates).containsExactly(
            Triple("19", 17L, listOf("event:${targetId.value}"))
        )
    }

    @Test
    fun `release scope handle cannot borrow a replacement owner token`() {
        val registry = TimelineReleaseScopeOwnerRegistry()
        val firstToken = TimelineReleaseScopeOwnerToken(11L)
        val replacementToken = TimelineReleaseScopeOwnerToken(12L)

        val staleHandle = registry.register("7", "room-hash:7", firstToken)
        val replacementHandle = registry.register("7", "room-hash:7", replacementToken)

        assertThat(staleHandle).isNotEqualTo(replacementHandle)
        assertThat(registry.resolve(staleHandle)).isNull()
        assertThat(registry.resolve(replacementHandle)?.ownerToken).isEqualTo(replacementToken)

        // A late close for the old subscription must not remove the replacement registration.
        assertThat(registry.remove(staleHandle)).isNull()
        assertThat(registry.resolve(replacementHandle)?.ownerToken).isEqualTo(replacementToken)
    }

    @Test
    fun `updated protected event waits for in flight decrypt of the same event`() = runTest {
        var decryptCount = 0
        var activeDecrypts = 0
        var maximumActiveDecrypts = 0
        val firstDecryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                activeDecrypts++
                maximumActiveDecrypts = maxOf(maximumActiveDecrypts, activeDecrypts)
                return try {
                    if (decryptCount == 1) firstDecryptGate.await()
                    item
                } finally {
                    activeDecrypts--
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(
            listOf(
                TimelineDiff.PushBack(
                    FakeFfiTimelineItem(
                        asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("first"))
                    )
                )
            )
        )
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        processor.postDiffs(
            listOf(
                TimelineDiff.Set(
                    0u,
                    FakeFfiTimelineItem(
                        asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("updated"))
                    )
                )
            )
        )
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        firstDecryptGate.complete(Unit)
        runCurrent()
        assertThat(decryptCount).isEqualTo(2)
        assertThat(maximumActiveDecrypts).isEqualTo(1)
    }

    @Test
    fun `relay confirmation retains only authorized original transaction until canonical projection is ready`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var localSource: MatrixTimelineItem? = null
        var valid = true
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.withText("") }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun pendingOriginalSendProjection(source: MatrixTimelineItem, replacement: MatrixTimelineItem): MatrixTimelineItem? =
                source.takeIf { valid && it == localSource && (replacement as? MatrixTimelineItem.Event)?.eventId != null }
                    ?.withText("authorized original relay")
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if ((item as MatrixTimelineItem.Event).eventId == null) {
                    localSource = item
                    return item.withText("authorized original relay")
                }
                gate.await()
                return item.withText("authorized canonical relay")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val local = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(
            eventOrTransactionId = RustEventOrTransactionId.TransactionId("relay-txn"),
            content = aRustTimelineItemContentMsgLike("encrypted relay")), uniqueId = "relay-sdk")
        val canonical = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(
            eventOrTransactionId = RustEventOrTransactionId.EventId("\$relay-confirmed"),
            content = aRustTimelineItemContentMsgLike("encrypted relay")), uniqueId = "relay-sdk")
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(local))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        processor.postDiffs(listOf(TimelineDiff.Set(0u, canonical)))
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized original relay")
        assertThat((timelineItems.value.single() as MatrixTimelineItem.Event).eventId).isNull()
        // Repeated canonical updates must revalidate the encrypted original source, not borrow
        // the canonical event's not-yet-authorized identity or previously emitted plaintext.
        processor.postDiffs(listOf(TimelineDiff.Set(0u, canonical)))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized original relay")
        valid = false
        processor.postDiffs(listOf(TimelineDiff.Set(0u, canonical)))
        assertThat(timelineItems.value.any { it.textBody() == "authorized original relay" }).isFalse()
        valid = true
        processor.postDiffs(listOf(TimelineDiff.Set(0u, canonical)))
        assertThat(timelineItems.value.any { it.textBody() == "authorized original relay" }).isFalse()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized canonical relay")
        assertThat((timelineItems.value.single() as MatrixTimelineItem.Event).eventId?.value).isEqualTo("\$relay-confirmed")
    }

    @Test
    fun `confirmed event update keeps the old projection and compose identity until replacement decrypts`() = runTest {
        val eventId = "\$stable-event"
        val replacementDecryptGate = CompletableDeferred<Unit>()
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return if (decryptCount == 1) {
                    item.withText("old cleartext")
                } else {
                    replacementDecryptGate.await()
                    item.withText("new cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val oldItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("old raw"),
            ),
            uniqueId = "old-sdk-identity",
        )
        val replacement = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("new raw"),
            ),
            uniqueId = "new-sdk-identity",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(oldItem))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        val originalDisplayId = (timelineItems.value.single() as MatrixTimelineItem.Event).displayUniqueId
        assertThat(timelineItems.value.single().textBody()).isEqualTo("old cleartext")

        processor.postDiffs(listOf(TimelineDiff.Set(0u, replacement)))
        assertThat(timelineItems.value.single().textBody()).isEqualTo("old cleartext")
        assertThat((timelineItems.value.single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(originalDisplayId)

        replacementDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEqualTo("new cleartext")
        assertThat((timelineItems.value.single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(originalDisplayId)
    }

    @Test
    fun `initial room entry publishes current cached visible projection before async decrypt batch`() = runTest {
        val decryptGate = CompletableDeferred<Unit>()
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem =
                item.withText("cached cleartext")

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                decryptGate.await()
                return item.withText("async cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val item = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = "cached-entry-item",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(item))))
        assertThat(timelineItems.value.single().textBody()).isEqualTo("cached cleartext")

        runCurrent()
        assertThat(decryptCount).isEqualTo(0)
        decryptGate.complete(Unit)
    }

    @Test
    fun `room reentry reset restores current cached projection instead of publishing shells`() = runTest {
        val eventId = EventId("\$reentry-cached-projection")
        val uniqueId = UniqueId("reentry-cached-projection")
        val replayedProjection = MatrixTimelineItem.Event(
            uniqueId = uniqueId,
            event = anEventTimelineItem(eventId = eventId),
        ).withText("replayed cleartext")
        timelineItems.value = listOf(replayedProjection)
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem =
                item.withText("cached current cleartext")

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("async cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val rawItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId.value),
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = uniqueId.value,
        )
        val scope = TimelineCommitScope(91L)
        processor.activateCommitScope(scope)

        val committed = processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(rawItem))),
            )
        )

        assertThat(committed).isTrue()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("cached current cleartext")
        decryptGate.complete(Unit)
    }

    @Test
    fun `room reentry cache cannot publish across an authorization revision change`() = runTest {
        val eventId = EventId("\$reentry-revoked-cache")
        val uniqueId = UniqueId("reentry-revoked-cache")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                uniqueId = uniqueId,
                event = anEventTimelineItem(eventId = eventId),
            ).withText("previous authorized cleartext")
        )
        var revision = 0L
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem {
                revision++
                return item.withText("stale cached cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val rawItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId.value),
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = uniqueId.value,
        )
        val scope = TimelineCommitScope(92L)
        processor.activateCommitScope(scope)

        val committed = processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(rawItem))),
            )
        )

        assertThat(committed).isTrue()
        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("stale cached cleartext")
    }

    @Test
    fun `initial room entry keeps safe shell when current cache misses`() = runTest {
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("async cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val item = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = "cache-miss-entry-item",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(item))))
        assertThat(timelineItems.value.single().textBody()).isEmpty()

        decryptGate.complete(Unit)
    }

    @Test
    fun `initial scoped structural reset publishes authorized first content while a slower item remains protected`() = runTest {
        val firstDecryptGate = CompletableDeferred<Unit>()
        val secondDecryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                when (item.textBody()) {
                    "first encrypted" -> firstDecryptGate.await()
                    "second encrypted" -> secondDecryptGate.await()
                }
                val clear = item.withText(item.textBody()?.replace(" encrypted", " cleartext").orEmpty()) as MatrixTimelineItem.Event
                val content = clear.event.content as MessageContent
                return clear.copy(event = clear.event.copy(content = content.copy(isContentProtectionAuthorized = true)))
            }

            override suspend fun decryptItem(
                item: MatrixTimelineItem,
                timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MatrixTimelineItem = decryptItem(item)
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val first = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("first encrypted")),
            uniqueId = "initial-barrier-first",
        )
        val second = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("second encrypted")),
            uniqueId = "initial-barrier-second",
        )
        val scope = TimelineCommitScope(101L)
        processor.activateCommitScope(scope)

        val committed = processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(first, second))),
            )
        )

        assertThat(committed).isTrue()
        assertThat(timelineItems.value).isEmpty()
        runCurrent()

        // The original history lane starts from the newest raw event before any
        // viewport exists. Its first authorized result must release the barrier.
        secondDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("", "second cleartext").inOrder()

        firstDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("first cleartext", "second cleartext").inOrder()
    }

    @Test
    fun `hard invalidation interrupts initial scoped projection and stale cleartext cannot return`() = runTest {
        val staleDecryptGate = CompletableDeferred<Unit>()
        val currentDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                return if (revision == 0L) {
                    staleDecryptGate.await()
                    item.withText("stale cleartext")
                } else {
                    currentDecryptGate.await()
                    item.withText("current cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val item = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
            uniqueId = "initial-barrier-hard-invalidation",
        )
        val scope = TimelineCommitScope(102L)
        processor.activateCommitScope(scope)
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(item))),
            )
        )
        runCurrent()
        assertThat(timelineItems.value).isEmpty()

        revision = 1L
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEmpty()

        staleDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("stale cleartext")

        currentDecryptGate.complete(Unit)
    }

    @Test
    fun `unexpected decrypt failure completes initial scoped projection with a fail closed shell`() = runTest {
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = error("simulated KMS failure")
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val item = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
            uniqueId = "initial-barrier-failure",
        )
        val scope = TimelineCommitScope(103L)
        processor.activateCommitScope(scope)

        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(item))),
            )
        )
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value).hasSize(1)
        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("encrypted carrier")
    }

    @Test
    fun `temporary security failure retries only the failed projection after backoff`() = runTest {
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return if (decryptCount == 1) {
                    item.withProtectedStatus(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
                } else {
                    item.withText("recovered cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(
            listOf(
                TimelineDiff.PushBack(
                    FakeFfiTimelineItem(
                        asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
                        uniqueId = "temporary-security-retry",
                    )
                )
            )
        )
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(1)
        assertThat(timelineItems.value.single().protectedStatus())
            .isEqualTo(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)

        advanceTimeBy(MatrixTimelineDiffProcessor.TRANSIENT_RETRY_DELAYS_MILLIS.first() + 1)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo("recovered cleartext")
    }

    @Test
    fun `pending send confirmation reauthorizes and restores the original projection`() = runTest {
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return if (decryptCount == 1) {
                    item.withProtectedStatus(ProtectedMessageStatus.SEND_BINDING_PENDING)
                } else {
                    item.withText("recovered cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(
            listOf(
                TimelineDiff.PushBack(
                    FakeFfiTimelineItem(
                        asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
                        uniqueId = "original-send-binding-retry",
                    )
                )
            )
        )
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(1)
        assertThat(timelineItems.value.single().protectedStatus())
            .isEqualTo(ProtectedMessageStatus.SEND_BINDING_PENDING)

        advanceTimeBy(MatrixTimelineDiffProcessor.TRANSIENT_RETRY_DELAYS_MILLIS.first() + 1)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo("recovered cleartext")
    }

    @Test
    fun `viewport promotion immediately reauthorizes a backpressured history shell`() = runTest {
        var decryptCount = 0
        val observedIntents = mutableListOf<TimelineContentReleaseIntent>()
        val viewportUpdates = mutableListOf<Triple<String, Long, List<String>>>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(
                item: MatrixTimelineItem,
                timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MatrixTimelineItem {
                observedIntents += timelineReleaseIntent
                decryptCount++
                return if (decryptCount == 1) {
                    item.withProtectedStatus(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
                } else {
                    item.withText("recovered after viewport promotion")
                }
            }

            override fun updateTimelineReleaseViewport(
                scopeId: String, generation: Long, visibleRequestIds: List<String>,
                bootstrapRequestIds: List<String>,
            ) {
                viewportUpdates += Triple(scopeId, generation, visibleRequestIds)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(104L)
        val targetId = UniqueId("backpressured-history")
        val targetKey = "event:${targetId.value}"
        val item = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
            uniqueId = targetId.value,
        )
        processor.activateCommitScope(scope)
        assertThat(processor.updateViewport(scope, 1L, emptyList())).isTrue()
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Reset(listOf(item))),
            )
        )
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(1)
        assertThat(timelineItems.value.single().protectedStatus())
            .isEqualTo(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)

        assertThat(processor.updateViewport(scope, 2L, listOf(targetId))).isTrue()
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo("recovered after viewport promotion")
        assertThat(observedIntents.map { it.generation to it.releaseClass }).containsExactly(
            1L to TimelineContentReleaseClass.HISTORY_PREFETCH,
            2L to TimelineContentReleaseClass.FOREGROUND_VISIBLE,
        ).inOrder()
        assertThat(viewportUpdates).containsExactly(
            Triple("104", 1L, emptyList<String>()),
            Triple("104", 2L, listOf(targetKey)),
        ).inOrder()
    }

    @Test
    fun `cancelling the collector during its batching delay releases the unpublished source`() = runTest {
        val queue = PendingDecryptQueue(8, 3)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "scope")
        val work = requireNotNull(queue.pollVisible())
        queue.awaitPublication(work)
        queue.complete(work)
        val input = Channel<DecryptedItem>(4)
        input.send(DecryptedItem(anEvent, anEvent.withText("authorized"), 0L, DecryptPriority.VISIBLE,
            rawStateGeneration = 1L, timelineScopeId = "scope", pendingWork = work))
        var released = 0
        val collector = backgroundScope.launch {
            collectDecryptedItemBatches(input, releaseBatch = { batch ->
                batch.forEach { item -> item.pendingWork?.let(queue::completePublication) }
                released += batch.size
            }) { error("Cancelled batch must not publish") }
        }
        runCurrent()
        assertThat(released).isEqualTo(0)
        collector.cancel()
        runCurrent()
        assertThat(released).isEqualTo(1)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "scope")
        assertThat(queue.pollVisible()).isNotNull()
        input.cancel()
    }

    @Test
    fun `retiring scope and invalidating content retire only the matching buffered source`() {
        val queue = PendingDecryptQueue(8, 3)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "old")
        val old = requireNotNull(queue.pollVisible())
        queue.awaitPublication(old)
        queue.complete(old)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 2L, timelineScopeId = "new")
        val current = requireNotNull(queue.pollVisible())
        queue.awaitPublication(current)
        queue.complete(current)
        queue.cancelScope("old")
        queue.invalidate(setOf(current.key), 1L)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 2L, timelineScopeId = "new")
        assertThat(queue.pollVisible()).isNull()
        queue.invalidate(setOf(current.key), 3L)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 3L, timelineScopeId = "new")
        assertThat(queue.pollVisible()).isNotNull()
    }

    @Test
    fun `buffered publication deduplication does not reserve a lane for an obsolete source`() {
        val queue = PendingDecryptQueue(8, 3)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "scope")
        val old = requireNotNull(queue.pollVisible())
        queue.awaitPublication(old)
        queue.complete(old)
        val replacement = anEvent.withText("new encrypted source")
        queue.offer(listOf(replacement), emptyList(), rawStateGeneration = 2L, timelineScopeId = "scope")
        val current = requireNotNull(queue.pollVisible())
        assertThat(current.item).isEqualTo(replacement)
        queue.awaitPublication(current)
        queue.complete(current)
        queue.completePublication(old)
        queue.offer(listOf(replacement), emptyList(), rawStateGeneration = 2L, timelineScopeId = "scope")
        assertThat(queue.pollVisible()).isNull()
        queue.completePublication(current)
        queue.offer(listOf(replacement), emptyList(), rawStateGeneration = 2L, timelineScopeId = "scope")
        assertThat(queue.pollVisible()?.item).isEqualTo(replacement)
    }

    @Test
    fun `buffered result cannot suppress forced authorization retry or a new owner`() {
        val queue = PendingDecryptQueue(8, 3)
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "old-owner")
        val old = requireNotNull(queue.pollVisible())
        queue.awaitPublication(old)
        queue.complete(old)
        queue.offer(listOf(anEvent), emptyList(), force = true, rawStateGeneration = 1L, timelineScopeId = "old-owner")
        val retry = requireNotNull(queue.pollVisible())
        queue.complete(retry)
        queue.cancelScope("old-owner")
        queue.offer(listOf(anEvent), emptyList(), rawStateGeneration = 1L, timelineScopeId = "new-owner")
        assertThat(queue.pollVisible()?.timelineScopeId).isEqualTo("new-owner")
    }

    @Test
    fun `viewport update does not decrypt the same source again while its result awaits publication`() = runTest {
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.withText("") }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                decryptCount++
                return item.withText("authorized result")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(106L)
        val targetId = UniqueId("buffered-result")
        processor.activateCommitScope(scope)
        processor.updateViewport(scope, 1L, listOf(targetId))
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted carrier")),
                uniqueId = targetId.value,
            )
        )))))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)
        assertThat(timelineItems.value.single().textBody()).isEmpty()
        processor.updateViewport(scope, 2L, listOf(targetId))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized result")
        processor.updateViewport(scope, 3L, listOf(targetId))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)
    }

    @Test
    fun `same visible viewport does not repeatedly reauthorize a temporary shell`() = runTest {
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(
                item: MatrixTimelineItem,
                timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MatrixTimelineItem {
                decryptCount++
                return item.withProtectedStatus(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(105L)
        val targetId = UniqueId("same-visible-temporary")
        processor.activateCommitScope(scope)
        assertThat(processor.updateViewport(scope, 1L, listOf(targetId))).isTrue()
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(
                    TimelineDiff.Reset(
                        listOf(
                            FakeFfiTimelineItem(
                                asEventResult = aRustEventTimelineItem(
                                    content = aRustTimelineItemContentMsgLike("encrypted carrier")
                                ),
                                uniqueId = targetId.value,
                            )
                        )
                    )
                ),
            )
        )
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        assertThat(processor.updateViewport(scope, 2L, listOf(targetId))).isTrue()
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(1)
    }

    @Test
    fun `redaction terminal interrupts initial scoped projection and delayed decrypt cannot revive it`() = runTest {
        val eventId = "\$initial-barrier-redaction"
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> if (item.textBody() != null) item.withText("") else item }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = item.textBody() != null

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (item.textBody() == null) return item
                decryptGate.await()
                return item.withText("delayed stale cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val encrypted = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = "initial-barrier-redaction",
        )
        val redacted = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentRedacted(),
            ),
            uniqueId = "initial-barrier-redaction",
        )
        val scope = TimelineCommitScope(104L)
        processor.activateCommitScope(scope)
        processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 1L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(encrypted))),
            )
        )
        runCurrent()
        assertThat(timelineItems.value).isEmpty()

        val terminalCommitted = processor.commitDiffs(
            TimelineDiffTransaction(
                scope = scope,
                sequence = 2L,
                structuralEpoch = 1L,
                diffs = listOf(TimelineDiff.Set(0u, redacted)),
            )
        )
        assertThat(terminalCommitted).isTrue()
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)

        decryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)
        assertThat(timelineItems.value.single().textBody()).isNull()
    }

    @Test
    fun `sync reset appends a new shell without replacing an existing authorized projection`() = runTest {
        val pendingDecryptGate = CompletableDeferred<Unit>()
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                return if (++decryptCount == 1) {
                    item.withText("existing cleartext")
                } else {
                    pendingDecryptGate.await()
                    item.withText("later cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val existing = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$existing-reset-event"),
                content = aRustTimelineItemContentMsgLike("existing raw"),
            ),
            uniqueId = "existing-reset-item",
        )
        val incoming = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$incoming-reset-event"),
                content = aRustTimelineItemContentMsgLike("incoming raw"),
            ),
            uniqueId = "incoming-reset-item",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(existing))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("existing cleartext")

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(existing, incoming))))

        assertThat(timelineItems.value.map { it.textBody() }).containsExactly(
            "existing cleartext",
            "",
        ).inOrder()
    }

    @Test
    fun `Clear Reset retains only unchanged authorized sources and preserves display identity`() = runTest {
        val rebuildDecryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (item.textBody()?.startsWith("rebuild-") == true) rebuildDecryptGate.await()
                return item.withText("clear:${item.textBody()}")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val unchanged = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$unchanged-clear-reset"),
                content = aRustTimelineItemContentMsgLike("unchanged-raw"),
            ),
            uniqueId = "unchanged-old-identity",
        )
        val oldChanged = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$changed-clear-reset"),
                content = aRustTimelineItemContentMsgLike("old-raw"),
            ),
            uniqueId = "changed-old-identity",
        )
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(unchanged, oldChanged))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        val previousChangedDisplayId = (timelineItems.value[1] as MatrixTimelineItem.Event).displayUniqueId
        assertThat(timelineItems.value.map { it.textBody() }).containsExactly(
            "clear:unchanged-raw",
            "clear:old-raw",
        ).inOrder()

        val replacement = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$changed-clear-reset"),
                content = aRustTimelineItemContentMsgLike("rebuild-new-raw"),
            ),
            uniqueId = "changed-new-identity",
        )
        processor.postDiffs(listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(unchanged, replacement))))

        assertThat(timelineItems.value.map { it.textBody() }).containsExactly(
            "clear:unchanged-raw",
            "",
        ).inOrder()
        assertThat((timelineItems.value[1] as MatrixTimelineItem.Event).displayUniqueId)
            .isEqualTo(previousChangedDisplayId)

        rebuildDecryptGate.complete(Unit)
    }

    @Test
    fun `structural Reset recovers the complete current-authority projection without reviving a terminal event`() = runTest {
        val cachedProjections = mutableMapOf<String, MatrixTimelineItem>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item ->
                    if (item.eventContent() is RedactedContent) item else item.withText("")
                }

            // This reproduces the production path that exposed the true-device flash: a Reset can
            // rebuild normal protected rows without classifying every row as a transient shell.
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = false

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? {
                if (item.eventContent() is RedactedContent) return null
                return MatrixTimelineDiffProcessor.itemKey(item)?.let(cachedProjections::get)
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (item.eventContent() is RedactedContent) return item
                val projected = item.withText("clear:${item.textBody()}")
                MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> cachedProjections[key] = projected }
                return projected
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val itemCount = MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT + 12
        val rawItems = (0 until itemCount).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(
                    eventOrTransactionId = RustEventOrTransactionId.EventId("\$structural-cache-$index"),
                    content = aRustTimelineItemContentMsgLike("raw-$index"),
                ),
                uniqueId = "structural-cache-$index",
            )
        }

        processor.postDiffs(listOf(TimelineDiff.Reset(rawItems)))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactlyElementsIn((0 until itemCount).map { index -> "clear:raw-$index" })
            .inOrder()

        val terminalFirstItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$structural-cache-0"),
                content = aRustTimelineItemContentRedacted(),
            ),
            uniqueId = "structural-cache-0-terminal",
        )
        processor.postDiffs(
            listOf(
                TimelineDiff.Clear,
                TimelineDiff.Reset(listOf(terminalFirstItem) + rawItems.drop(1)),
            )
        )

        assertThat(timelineItems.value).hasSize(itemCount)
        assertThat(timelineItems.value.first().eventContent()).isInstanceOf(RedactedContent::class.java)
        assertThat(timelineItems.value.drop(1).map { it.textBody() })
            .containsExactlyElementsIn((1 until itemCount).map { index -> "clear:raw-$index" })
            .inOrder()
    }

    @Test
    fun `initial protected decrypt prioritizes newest events with bounded concurrency`() = runTest {
        val decryptGate = CompletableDeferred<Unit>()
        val startedKeys = mutableListOf<String>()
        var activeDecrypts = 0
        var maximumActiveDecrypts = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                startedKeys += checkNotNull(MatrixTimelineDiffProcessor.itemKey(item))
                activeDecrypts++
                maximumActiveDecrypts = maxOf(maximumActiveDecrypts, activeDecrypts)
                return try {
                    decryptGate.await()
                    item
                } finally {
                    activeDecrypts--
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val itemCount = MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT + 8
        val items = (0 until itemCount).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(),
                uniqueId = "priority-$index",
            )
        }

        processor.postDiffs(listOf(TimelineDiff.Reset(items)))
        runCurrent()

        assertThat(maximumActiveDecrypts).isEqualTo(MatrixTimelineDiffProcessor.DECRYPT_WORKER_COUNT)
        assertThat(startedKeys).containsExactlyElementsIn(
            (itemCount - MatrixTimelineDiffProcessor.DECRYPT_WORKER_COUNT until itemCount)
                .map { index -> "event:priority-$index" }
        )

        decryptGate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `off-screen history decrypt uses a single background lane`() = runTest {
        val historyGate = CompletableDeferred<Unit>()
        val historyItemCount = 8
        val itemCount = MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT + historyItemCount
        val historyKeys = (0 until historyItemCount).mapTo(mutableSetOf()) { index -> "event:history-$index" }
        val startedHistoryKeys = mutableListOf<String>()
        var activeHistoryDecrypts = 0
        var maximumActiveHistoryDecrypts = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val key = checkNotNull(MatrixTimelineDiffProcessor.itemKey(item))
                if (key !in historyKeys) return item
                startedHistoryKeys += key
                activeHistoryDecrypts++
                maximumActiveHistoryDecrypts = maxOf(maximumActiveHistoryDecrypts, activeHistoryDecrypts)
                return try {
                    historyGate.await()
                    item
                } finally {
                    activeHistoryDecrypts--
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val items = (0 until itemCount).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(),
                uniqueId = "history-$index",
            )
        }

        processor.postDiffs(listOf(TimelineDiff.Reset(items)))
        runCurrent()

        assertThat(startedHistoryKeys).hasSize(1)
        assertThat(maximumActiveHistoryDecrypts).isEqualTo(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_WORKER_COUNT)

        historyGate.complete(Unit)
        runCurrent()
        assertThat(startedHistoryKeys).containsExactlyElementsIn(historyKeys)
        assertThat(maximumActiveHistoryDecrypts).isEqualTo(1)
    }

    @Test
    fun `visible decrypt result flushes a pending history batch immediately`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val historyResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.HISTORY)
        val visibleResult = DecryptedItem(anEvent2, anEvent2, 0L, DecryptPriority.VISIBLE)

        input.send(historyResult)
        runCurrent()
        advanceTimeBy(100L)
        assertThat(batches).isEmpty()

        input.send(visibleResult)
        runCurrent()

        assertThat(batches).containsExactly(listOf(historyResult, visibleResult))
    }

    @Test
    fun `visible decrypt result uses only the short visible deadline`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val visibleResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.VISIBLE)

        input.send(visibleResult)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS - 1L)
        runCurrent()
        assertThat(batches).isEmpty()

        advanceTimeBy(2L)
        runCurrent()

        assertThat(batches).containsExactly(listOf(visibleResult))
    }

    @Test
    fun `history decrypt results are emitted together at the bounded history deadline`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val firstResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.HISTORY)
        val secondResult = DecryptedItem(anEvent2, anEvent2, 0L, DecryptPriority.HISTORY)

        input.send(firstResult)
        runCurrent()
        advanceTimeBy(100L)
        input.send(secondResult)
        runCurrent()
        assertThat(batches).isEmpty()

        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS)
        runCurrent()

        assertThat(batches).containsExactly(listOf(firstResult, secondResult))
    }

    @Test
    fun `recent history result publishes without waiting for a coalescing window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val recentResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.HISTORY, recentHistory = true)

        input.send(recentResult)
        runCurrent()

        assertThat(batches).containsExactly(listOf(recentResult))
        assertThat(batches.single().single().priority).isEqualTo(DecryptPriority.HISTORY)
    }

    @Test
    fun `recent history result interrupts a pending history coalescing window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val historyResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.HISTORY)
        val recentResult = DecryptedItem(anEvent2, anEvent2, 0L, DecryptPriority.HISTORY, recentHistory = true)

        input.send(historyResult)
        runCurrent()
        advanceTimeBy(100L)
        assertThat(batches).isEmpty()
        input.send(recentResult)
        runCurrent()

        assertThat(batches).containsExactly(listOf(historyResult, recentResult))
    }

    @Test
    fun `recent history result interrupts a pending visible coalescing window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch {
            collectDecryptedItemBatches(input) { batch -> batches += batch }
        }
        val visibleResult = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.VISIBLE)
        val recentResult = DecryptedItem(anEvent2, anEvent2, 0L, DecryptPriority.HISTORY, recentHistory = true)

        input.send(visibleResult)
        runCurrent()
        advanceTimeBy(50L)
        assertThat(batches).isEmpty()
        input.send(recentResult)
        runCurrent()

        assertThat(batches).containsExactly(listOf(visibleResult, recentResult))
    }

    @Test
    fun `request cancellation does not retire decrypt workers or block later timeline items`() = runTest {
        val cancelledDecrypts = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> item.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val body = requireNotNull(item.textBody())
                if (body.startsWith("cancel-")) {
                    cancelledDecrypts += body
                    throw CancellationException("Superseded timeline release")
                }
                return item.withText("decrypted-$body")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val cancelledItems = (0 until MatrixTimelineDiffProcessor.DECRYPT_WORKER_COUNT).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(
                    content = aRustTimelineItemContentMsgLike("cancel-$index"),
                ),
                uniqueId = "cancelled-worker-$index",
            )
        }

        processor.postDiffs(listOf(TimelineDiff.Reset(cancelledItems)))
        runCurrent()

        assertThat(cancelledDecrypts).hasSize(MatrixTimelineDiffProcessor.DECRYPT_WORKER_COUNT)

        val laterItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                content = aRustTimelineItemContentMsgLike("later-item"),
            ),
            uniqueId = "later-item-after-request-cancellation",
        )
        processor.postDiffs(listOf(TimelineDiff.PushBack(laterItem)))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1L)
        runCurrent()

        assertThat(timelineItems.value.last().textBody()).isEqualTo("decrypted-later-item")
    }

    @Test
    fun `recent visible projection skips its visible coalescing window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch { collectDecryptedItemBatches(input) { batches += it } }
        val ready = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.VISIBLE, recentProjection = true)
        input.send(ready)
        runCurrent()
        assertThat(batches).containsExactly(listOf(ready))
        assertThat(batches.single().single().priority).isEqualTo(DecryptPriority.VISIBLE)
    }

    @Test
    fun `recent visible projection interrupts another visible publication window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch { collectDecryptedItemBatches(input) { batches += it } }
        val ordinary = DecryptedItem(anEvent, anEvent, 0L, DecryptPriority.VISIBLE)
        val ready = DecryptedItem(anEvent2, anEvent2, 0L, DecryptPriority.VISIBLE, recentProjection = true)
        input.send(ordinary)
        runCurrent()
        advanceTimeBy(50L)
        assertThat(batches).isEmpty()
        input.send(ready)
        runCurrent()
        assertThat(batches).containsExactly(listOf(ordinary, ready))
    }

    @Test
    fun `new history replacement supersedes queued visible item with the same key`() = runTest {
        val sharedUniqueId = "shared-priority-key"
        val sharedKey = "event:$sharedUniqueId"
        val decryptedSharedBodies = mutableListOf<String?>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (MatrixTimelineDiffProcessor.itemKey(item) == sharedKey) {
                    decryptedSharedBodies += item.textBody()
                    return item.withText("new cleartext")
                }
                return item
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val oldItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("old raw")),
            uniqueId = sharedUniqueId,
        )
        val newItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("new raw")),
            uniqueId = sharedUniqueId,
        )
        val newerVisibleItems = (0 until MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT).map { index ->
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("filler")),
                uniqueId = "newer-$index",
            )
        }

        // Do not yield between diffs: the old value remains queued as visible while its replacement
        // moves outside the visible-first window in the later reset.
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(oldItem))))
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(newItem) + newerVisibleItems)))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptedSharedBodies).containsExactly("new raw")
        val projectedSharedItem = timelineItems.value.first { MatrixTimelineDiffProcessor.itemKey(it) == sharedKey }
        assertThat(projectedSharedItem.textBody()).isEqualTo("new cleartext")
    }

    @Test
    fun `forced authorization retry remains queued while identical item is in flight`() {
        val queue = PendingDecryptQueue(
            maximumSize = 4,
            historyFairnessVisibleBurst = MatrixTimelineDiffProcessor.HISTORY_FAIRNESS_VISIBLE_BURST,
        )
        queue.offer(visible = listOf(anEvent), history = emptyList())
        val inFlight = checkNotNull(queue.pollVisible())

        // Models an authorization refresh after the worker's revision check but before complete().
        queue.offer(visible = listOf(anEvent), history = emptyList(), force = true)
        queue.complete(inFlight)

        assertThat(checkNotNull(queue.pollVisible()).item).isEqualTo(anEvent)
    }

    @Test
    fun `viewport reprioritization changes only waiting work and preserves eventual history`() {
        val oldVisible = MatrixTimelineItem.Event(UniqueId("old-visible"), anEventTimelineItem())
        val newlyVisible = MatrixTimelineItem.Event(UniqueId("new-visible"), anEventTimelineItem())
        val queue = PendingDecryptQueue(
            maximumSize = 4,
            historyFairnessVisibleBurst = MatrixTimelineDiffProcessor.HISTORY_FAIRNESS_VISIBLE_BURST,
        )
        queue.offer(
            visible = listOf(oldVisible),
            history = listOf(newlyVisible),
            rawStateGeneration = 3L,
            timelineScopeId = "scope",
        )

        queue.reprioritize(
            visibleKeys = listOf("event:new-visible"),
            shellCandidates = listOf(newlyVisible),
            rawStateGeneration = 3L,
            timelineScopeId = "scope",
            releaseGeneration = 9L,
        )

        val promoted = checkNotNull(queue.pollVisible())
        assertThat(promoted.item).isEqualTo(newlyVisible)
        assertThat(promoted.releaseGeneration).isEqualTo(9L)
        assertThat(promoted.priority).isEqualTo(DecryptPriority.VISIBLE)
        queue.complete(promoted)
        val history = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(history.item).isEqualTo(oldVisible)
        assertThat(history.releaseGeneration).isEqualTo(9L)
        assertThat(history.priority).isEqualTo(DecryptPriority.HISTORY)
    }

    @Test
    fun `older forced snapshot and completing retry cannot overwrite newer raw generation`() {
        val oldRaw = anEvent.withText("old raw")
        val newRaw = anEvent.withText("new raw")
        val queue = PendingDecryptQueue(
            maximumSize = 4,
            historyFairnessVisibleBurst = MatrixTimelineDiffProcessor.HISTORY_FAIRNESS_VISIBLE_BURST,
        )
        queue.offer(
            visible = listOf(oldRaw),
            history = emptyList(),
            rawStateGeneration = 1L,
        )
        val oldInFlight = checkNotNull(queue.pollVisible())

        // Authorization refresh first queues the identical old raw while it is in flight.
        queue.offer(
            visible = listOf(oldRaw),
            history = emptyList(),
            force = true,
            rawStateGeneration = 1L,
        )
        // A later Reset replaces that key. It moved outside the viewport, but the queue preserves
        // the already acquired visible priority while replacing the immutable raw value.
        queue.offer(
            visible = emptyList(),
            history = listOf(newRaw),
            rawStateGeneration = 2L,
        )
        // Models the old refresh snapshot reaching the queue after the newer Reset offer.
        queue.offer(
            visible = listOf(oldRaw),
            history = emptyList(),
            force = true,
            rawStateGeneration = 1L,
        )
        queue.complete(oldInFlight, retry = true)

        val replacement = checkNotNull(queue.pollVisible())
        assertThat(replacement.item).isEqualTo(newRaw)
        assertThat(replacement.rawStateGeneration).isEqualTo(2L)
        assertThat(replacement.priority).isEqualTo(DecryptPriority.VISIBLE)
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }

    @Test
    fun `closing a timeline scope prevents its in flight worker from requeueing`() {
        val queue = PendingDecryptQueue(
            maximumSize = 4,
            historyFairnessVisibleBurst = MatrixTimelineDiffProcessor.HISTORY_FAIRNESS_VISIBLE_BURST,
        )
        queue.offer(
            visible = listOf(anEvent),
            history = emptyList(),
            rawStateGeneration = 1L,
            timelineScopeId = "closed-scope",
        )
        val inFlight = checkNotNull(queue.pollVisible())

        queue.cancelScope("closed-scope")
        queue.complete(inFlight, retry = true)

        assertThat(queue.isCurrent(inFlight.key, 1L, "closed-scope")).isFalse()
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }

    @Test
    fun `history lane serves one history item after bounded visible burst`() {
        val visibleBurst = 3
        val queue = PendingDecryptQueue(
            maximumSize = 8,
            historyFairnessVisibleBurst = visibleBurst,
        )
        val visibleItems = (0..visibleBurst).map { index ->
            MatrixTimelineItem.Event(UniqueId("fair-visible-$index"), anEventTimelineItem())
        }
        val historyItem = MatrixTimelineItem.Event(UniqueId("fair-history"), anEventTimelineItem())
        queue.offer(visible = visibleItems, history = listOf(historyItem))

        val priorities = mutableListOf<DecryptPriority>()
        val polled = buildList {
            repeat(visibleBurst + 1) {
                val work = checkNotNull(queue.pollVisibleOrHistory())
                priorities += work.priority
                add(work.item)
                queue.complete(work)
            }
        }

        assertThat(polled.take(visibleBurst)).containsExactlyElementsIn(visibleItems.take(visibleBurst)).inOrder()
        assertThat(polled.last()).isEqualTo(historyItem)
        assertThat(priorities).containsExactly(
            DecryptPriority.VISIBLE,
            DecryptPriority.VISIBLE,
            DecryptPriority.VISIBLE,
            DecryptPriority.HISTORY,
        ).inOrder()
    }

    @Test
    fun `overflow evicts oldest history and keeps nearest history after visible window`() {
        val itemCount = 5_000
        val items = (0 until itemCount).map { index ->
            MatrixTimelineItem.Event(UniqueId("overflow-$index"), anEventTimelineItem())
        }
        val priorityOrder = items.asReversed()
        val queue = PendingDecryptQueue(
            maximumSize = MatrixTimelineDiffProcessor.MAX_PENDING_DECRYPTS,
            historyFairnessVisibleBurst = MatrixTimelineDiffProcessor.HISTORY_FAIRNESS_VISIBLE_BURST,
        )

        queue.offer(
            visible = priorityOrder.take(MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT),
            history = priorityOrder.drop(MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT),
        )

        assertThat(queue.pendingSize()).isEqualTo(MatrixTimelineDiffProcessor.MAX_PENDING_DECRYPTS)
        assertThat(queue.inFlightSize()).isEqualTo(0)
        val first = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(queue.pendingSize()).isEqualTo(MatrixTimelineDiffProcessor.MAX_PENDING_DECRYPTS - 1)
        assertThat(queue.inFlightSize()).isEqualTo(1)
        queue.complete(first)
        val polled = buildList {
            add(first.item)
            repeat(MatrixTimelineDiffProcessor.MAX_PENDING_DECRYPTS - 1) {
                val work = checkNotNull(queue.pollVisibleOrHistory())
                add(work.item)
                queue.complete(work)
            }
        }

        // The 30 visible items and the nearest 4,066 history items survive. The oldest 904 do not.
        assertThat(polled).containsExactlyElementsIn(priorityOrder.take(MatrixTimelineDiffProcessor.MAX_PENDING_DECRYPTS)).inOrder()
        assertThat(polled[MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT]).isEqualTo(items[4_969])
        assertThat(polled.last()).isEqualTo(items[904])
        assertThat(polled).doesNotContain(items[903])
        assertThat(queue.pendingSize()).isEqualTo(0)
        assertThat(queue.inFlightSize()).isEqualTo(0)
    }

    @Test
    fun `replay seed and same batch replacement queue only the latest item`() = runTest {
        val sharedKey = "event:${A_UNIQUE_ID.value}"
        timelineItems.value = listOf(anEvent.withText("seed raw"))
        val decryptedSharedBodies = mutableListOf<String?>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (MatrixTimelineDiffProcessor.itemKey(item) == sharedKey) {
                    decryptedSharedBodies += item.textBody()
                    return item.withText("replacement cleartext")
                }
                return item
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val replacement = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("replacement raw")),
            uniqueId = A_UNIQUE_ID.value,
        )

        processor.postDiffs(listOf(TimelineDiff.Set(0u, replacement)))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptedSharedBodies).containsExactly("replacement raw")
        assertThat(timelineItems.value.single().textBody()).isEqualTo("replacement cleartext")
    }

    @Test
    fun `new raw generation rejects stale decrypt even when replacement is value equal`() = runTest {
        val oldDecryptGate = CompletableDeferred<Unit>()
        val newDecryptGate = CompletableDeferred<Unit>()
        var decryptCalls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                return when (++decryptCalls) {
                    1 -> {
                        oldDecryptGate.await()
                        item.withText("stale cleartext")
                    }
                    else -> {
                        newDecryptGate.await()
                        item.withText("fresh cleartext")
                    }
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val raw = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("same raw")),
            uniqueId = "same-generation-key",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(raw))))
        runCurrent()
        assertThat(decryptCalls).isEqualTo(1)

        processor.postDiffs(listOf(TimelineDiff.Set(0u, raw)))
        runCurrent()
        oldDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(decryptCalls).isEqualTo(2)

        newDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEqualTo("fresh cleartext")
    }

    @Test
    fun `redaction tombstone rejects pending decrypt and reconnect replay idempotently`() = runTest {
        val eventId = "\$redaction-generation"
        val decryptGate = CompletableDeferred<Unit>()
        var decryptCalls = 0
        var plaintextClearCalls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> if (item.textBody() != null) item.withText("") else item }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = item.textBody() != null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (item.textBody() == null) return item
                decryptCalls++
                decryptGate.await()
                return item.withText("stale plaintext")
            }

            override fun clearDerivedPlaintextForRedaction(eventIdentities: Set<String>) {
                plaintextClearCalls++
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val encrypted = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("encrypted carrier"),
            ),
            uniqueId = "redaction-generation",
        )
        val redacted = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentRedacted(),
            ),
            uniqueId = "redaction-generation",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(encrypted))))
        runCurrent()
        assertThat(decryptCalls).isEqualTo(1)

        processor.postDiffs(listOf(TimelineDiff.Set(0u, redacted)))
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)
        assertThat(plaintextClearCalls).isEqualTo(1)

        decryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)

        processor.postDiffs(listOf(TimelineDiff.Set(0u, encrypted)))
        runCurrent()
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)
        assertThat(decryptCalls).isEqualTo(1)

        processor.postDiffs(listOf(TimelineDiff.Set(0u, redacted)))
        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)
        assertThat(plaintextClearCalls).isEqualTo(3)
    }

    @Test
    fun `persisted redaction seed blocks database restore of old encrypted event`() = runTest {
        val eventId = EventId("\$persisted-redaction")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("persisted-redaction"),
                anEventTimelineItem(eventId = eventId, content = RedactedContent),
            )
        )
        var decryptCalls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> = items

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = item.textBody() != null

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (item.textBody() == null) return item
                decryptCalls++
                return item.withText("restored stale plaintext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val restoredOldEvent = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId.value),
                content = aRustTimelineItemContentMsgLike("old database carrier"),
            ),
            uniqueId = "persisted-redaction",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(restoredOldEvent))))
        runCurrent()

        assertThat(timelineItems.value.single().eventContent()).isEqualTo(RedactedContent)
        assertThat(decryptCalls).isEqualTo(0)
    }

    @Test
    fun `protected decrypt publishes a safe shell before cleartext`() = runTest {
        val shellLabel = ""
        val cleartext = "authenticated content"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = item.withText(cleartext)
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(cleartext)
    }

    @Test
    fun `slow protected decrypt keeps a stable safe shell until cleartext is ready`() = runTest {
        val shellLabel = ""
        val cleartext = "authenticated content"
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText(cleartext)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        decryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(cleartext)
    }

    @Test
    fun `protected carrier restored from replay cache is never published`() = runTest {
        val rawCarrier = "yizhe-secure-v1:transport-carrier"
        val shellLabel = ""
        val cleartext = "authenticated content"
        val decryptGate = CompletableDeferred<Unit>()
        timelineItems.value = listOf(anEvent.withText(rawCarrier))
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> if (item.textBody() == rawCarrier) item.withText(shellLabel) else item }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = item.textBody() == rawCarrier

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText(cleartext)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(emptyList())
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)
        assertThat(timelineItems.value.single().textBody()).doesNotContain(rawCarrier)

        decryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(cleartext)
    }

    @Test
    fun `explicit authorized projection publishes the exact current raw item`() = runTest {
        val eventId = "\$burn-direct-current"
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("background cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                uniqueId = "burn-direct-current",
            )
        ))))
        val raw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(eventId))))

        val published = processor.publishAuthorizedProjection(raw, raw.withText("active burn cleartext"))

        assertThat(published).isTrue()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("active burn cleartext")
    }

    @Test
    fun `clearing one authorized projection preserves unrelated cleartext and rejects stale republish`() = runTest {
        val targetEventId = "\$burn-clear-target"
        val historyEventId = "\$burn-clear-history"
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("background cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(
                    eventOrTransactionId = RustEventOrTransactionId.EventId(historyEventId)
                ),
                uniqueId = "burn-clear-history",
            ),
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(
                    eventOrTransactionId = RustEventOrTransactionId.EventId(targetEventId)
                ),
                uniqueId = "burn-clear-target",
            ),
        ))))
        val targetRaw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(targetEventId))))
        val historyRaw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(historyEventId))))
        assertThat(processor.publishAuthorizedProjection(historyRaw, historyRaw.withText("history cleartext"))).isTrue()
        assertThat(processor.publishAuthorizedProjection(targetRaw, targetRaw.withText("target cleartext"))).isTrue()

        assertThat(processor.clearAuthorizedProjection(targetRaw)).isTrue()

        val projectedById = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
            .associateBy { checkNotNull(it.eventId).value }
        assertThat(projectedById.getValue(targetEventId).event.content).isEqualTo(RedactedContent)
        assertThat(projectedById.getValue(historyEventId).textBody()).isEqualTo("history cleartext")
        assertThat(processor.publishAuthorizedProjection(targetRaw, targetRaw.withText("stale cleartext"))).isFalse()
        assertThat(
            timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
                .first { it.eventId?.value == targetEventId }
                .event.content
        ).isEqualTo(RedactedContent)
    }

    @Test
    fun `remote content invalidation terminates only its target and survives structural reset`() = runTest {
        val targetEventId = "\$remote-content-target"
        val targetContentId = ContentId("remote-content-id")
        val decryptGate = CompletableDeferred<Unit>()
        var invalidationListener: ((MatrixContentInvalidation) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> if (item.eventContent() is RedactedContent) item else item.withText("") }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("late cleartext")
            }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean =
                item.eventContent() !is RedactedContent

            override fun containsContent(item: MatrixTimelineItem, contentId: ContentId): Boolean =
                contentId == targetContentId &&
                    (item as? MatrixTimelineItem.Event)?.eventId?.value == targetEventId

            override fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable {
                invalidationListener = listener
                return AutoCloseable { invalidationListener = null }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val ffiItems = (0..10).map { index ->
            val eventId = if (index == 0) targetEventId else "\$remote-history-$index"
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(
                    eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                    content = aRustTimelineItemContentMsgLike("encrypted-$index"),
                ),
                uniqueId = "remote-content-$index",
            )
        }
        processor.postDiffs(listOf(TimelineDiff.Reset(ffiItems)))
        val rawItems = (0..10).map { index ->
            val eventId = if (index == 0) targetEventId else "\$remote-history-$index"
            checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(eventId))))
        }
        rawItems.forEachIndexed { index, raw ->
            assertThat(processor.publishAuthorizedProjection(raw, raw.withText("cleartext-$index"))).isTrue()
        }

        checkNotNull(invalidationListener).invoke(MatrixContentInvalidation(sequence = 1L, contentId = targetContentId))
        runCurrent()

        val afterInvalidation = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(afterInvalidation.first().event.content).isEqualTo(RedactedContent)
        assertThat(afterInvalidation.drop(1).map { it.textBody() })
            .containsExactlyElementsIn((1..10).map { "cleartext-$it" }).inOrder()
        assertThat(processor.publishAuthorizedProjection(rawItems.first(), rawItems.first().withText("stale"))).isFalse()

        processor.postDiffs(listOf(TimelineDiff.Clear, TimelineDiff.Reset(ffiItems)))

        val afterReset = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(afterReset.first().event.content).isEqualTo(RedactedContent)
        assertThat(afterReset.drop(1).map { it.textBody() })
            .containsExactlyElementsIn((1..10).map { "cleartext-$it" }).inOrder()
    }

    @Test
    fun `content invalidation before carrier arrival prevents its first projection`() = runTest {
        val targetEventId = "\$late-content-target"
        val unaffectedEventId = "\$late-content-unaffected"
        val targetContentId = ContentId("late-content-id")
        var invalidationListener: ((MatrixContentInvalidation) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { item -> if (item.eventContent() is RedactedContent) item else item.withText("") }

            override fun containsContent(item: MatrixTimelineItem, contentId: ContentId): Boolean =
                contentId == targetContentId &&
                    (item as? MatrixTimelineItem.Event)?.eventId?.value == targetEventId

            override fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable {
                invalidationListener = listener
                return AutoCloseable { invalidationListener = null }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)

        checkNotNull(invalidationListener).invoke(MatrixContentInvalidation(sequence = 1L, contentId = targetContentId))
        runCurrent()
        processor.postDiffs(
            listOf(
                TimelineDiff.Reset(
                    listOf(
                        FakeFfiTimelineItem(
                            asEventResult = aRustEventTimelineItem(
                                eventOrTransactionId = RustEventOrTransactionId.EventId(targetEventId),
                                content = aRustTimelineItemContentMsgLike("encrypted-target"),
                            ),
                            uniqueId = "late-content-target",
                        ),
                        FakeFfiTimelineItem(
                            asEventResult = aRustEventTimelineItem(
                                eventOrTransactionId = RustEventOrTransactionId.EventId(unaffectedEventId),
                                content = aRustTimelineItemContentMsgLike("encrypted-unaffected"),
                            ),
                            uniqueId = "late-content-unaffected",
                        ),
                    )
                )
            )
        )

        val targetRaw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(targetEventId))))
        val unaffectedRaw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(unaffectedEventId))))
        assertThat(targetRaw.eventContent()).isEqualTo(RedactedContent)
        assertThat(processor.publishAuthorizedProjection(targetRaw, targetRaw.withText("must-not-revive"))).isFalse()
        assertThat(processor.publishAuthorizedProjection(unaffectedRaw, unaffectedRaw.withText("unaffected-cleartext"))).isTrue()
        val displayed = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(displayed.first().event.content).isEqualTo(RedactedContent)
        assertThat(displayed.last().textBody()).isEqualTo("unaffected-cleartext")
    }

    @Test
    fun `explicit authorized projection rejects a replaced raw item`() = runTest {
        val eventId = "\$burn-direct-replaced"
        val decryptGate = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptGate.await()
                return item.withText("background cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val oldItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("old raw"),
            ),
            uniqueId = "burn-direct-replaced",
        )
        val replacement = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                content = aRustTimelineItemContentMsgLike("replacement raw"),
            ),
            uniqueId = "burn-direct-replaced",
        )
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(oldItem))))
        val staleRaw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(eventId))))
        processor.postDiffs(listOf(TimelineDiff.Set(0u, replacement)))

        val published = processor.publishAuthorizedProjection(staleRaw, staleRaw.withText("stale burn cleartext"))

        assertThat(published).isFalse()
        assertThat(timelineItems.value.single().textBody()).isEmpty()
    }

    @Test
    fun `authorization revision change prevents an explicit projection`() = runTest {
        val eventId = "\$burn-direct-revoked"
        var revisionCalls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override fun authorizationRevision(): Long {
                revisionCalls++
                return if (revisionCalls <= 2) 0L else 1L
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(
            FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                uniqueId = "burn-direct-revoked",
            )
        ))))
        val raw = checkNotNull(processor.rawItem(EventOrTransactionId.Event(EventId(eventId))))

        val published = processor.publishAuthorizedProjection(raw, raw.withText("revoked burn cleartext"))

        assertThat(published).isFalse()
        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("revoked burn cleartext")
    }

    @Test
    fun `selective cache expiry keeps unaffected projections stable and retries only cache misses`() = verifyCacheRefresh(
        MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
    )

    @Test
    fun `unlock immediately retries missing projections and preserves current authorized cache`() = verifyCacheRefresh(
        MatrixAuthorizationRefreshMode.PREVIEW_ACCESS_REOPENED,
    )

    private fun verifyCacheRefresh(mode: MatrixAuthorizationRefreshMode) = runTest {
        val expiredDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val refreshedDecryptBodies = mutableListOf<String?>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? =
                item.takeIf { it.textBody() == "stable raw" }?.withText("stable cleartext")

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                return if (revision == 0L) {
                    item.withText(item.textBody()?.replace(" raw", " cleartext").orEmpty())
                } else {
                    refreshedDecryptBodies += item.textBody()
                    expiredDecryptGate.await()
                    item.withText("expired reauthorized")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val expired = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("expired raw")),
            uniqueId = "expired-cache-entry",
        )
        val stable = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("stable raw")),
            uniqueId = "stable-cache-entry",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(expired, stable))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("expired cleartext", "stable cleartext").inOrder()

        revision = 1L
        checkNotNull(refreshListener).invoke(selectiveCacheExpiryRefresh(revision).copy(mode = mode))
        runCurrent()

        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("", "stable cleartext").inOrder()
        assertThat(refreshedDecryptBodies).containsExactly("expired raw")

        expiredDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("expired reauthorized", "stable cleartext").inOrder()
    }

    @Test
    fun `history recovery retries only its target room and retains current cache projections`() = runTest {
        val missingDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val retriedBodies = mutableListOf<String?>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun accountScopeId(): String = "account-scope"
            override fun roomScopeId(): String = "!target:example.org"
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true
            override fun authorizationRevision(): Long = revision
            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? =
                item.takeIf { it.textBody() == "stable raw" }?.withText("stable cleartext")

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = if (revision == 0L) {
                item.withText(item.textBody()?.replace(" raw", " cleartext").orEmpty())
            } else {
                retriedBodies += item.textBody()
                missingDecryptGate.await()
                item.withText("recovered cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val missing = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("missing raw")),
            uniqueId = "history-missing",
        )
        val stable = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("stable raw")),
            uniqueId = "history-stable",
        )
        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(missing, stable))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        revision = 1L
        checkNotNull(refreshListener).invoke(
            historyRecoveryRefresh(
                revision = revision,
                roomScopeIds = setOf("!target:example.org"),
            )
        )
        runCurrent()

        assertThat(timelineItems.value.map { it.textBody() })
            .containsExactly("", "stable cleartext").inOrder()
        assertThat(retriedBodies).containsExactly("missing raw")
        missingDecryptGate.complete(Unit)
    }

    @Test
    fun `history recovery from another room leaves the current timeline untouched`() = runTest {
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        var refreshedDecryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun accountScopeId(): String = "account-scope"
            override fun roomScopeId(): String = "!current:example.org"
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override fun authorizationRevision(): Long = revision
            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if (revision > 0L) refreshedDecryptCount++
                return item.withText("visible cleartext")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        revision = 1L
        checkNotNull(refreshListener).invoke(
            historyRecoveryRefresh(
                revision = revision,
                roomScopeIds = setOf("!other:example.org"),
            )
        )
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEqualTo("visible cleartext")
        assertThat(refreshedDecryptCount).isEqualTo(0)
    }

    @Test
    fun `hard authorization invalidation ignores cached cleartext and immediately publishes shells`() = runTest {
        val hardDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var cacheLookupCount = 0
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? {
                if (revision == 0L) return null
                cacheLookupCount++
                return item.withText("stale cached cleartext")
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = if (revision == 0L) {
                item.withText("initial cleartext")
            } else {
                hardDecryptGate.await()
                item.withText("hard reauthorized")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("initial cleartext")

        revision = 1L
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(cacheLookupCount).isEqualTo(0)
        hardDecryptGate.complete(Unit)
    }

    @Test
    fun `unseen hard generation dominates a later selective refresh delivered first`() = runTest {
        val decryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var cacheLookupCount = 0
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText("") }

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? {
                if (revision == 0L) return null
                cacheLookupCount++
                return item.withText("must not survive")
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = if (revision == 0L) {
                item.withText("initial cleartext")
            } else {
                decryptGate.await()
                item.withText("reauthorized")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        revision = 2L
        checkNotNull(refreshListener).invoke(
            MatrixAuthorizationRefresh(
                revision = revision,
                mode = MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
                latestHardInvalidationRevision = 1L,
            )
        )
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEmpty()
        assertThat(cacheLookupCount).isEqualTo(0)
        decryptGate.complete(Unit)
    }

    @Test
    fun `authorization refresh during decrypt discards stale result and rechecks the event`() = runTest {
        val shellLabel = ""
        val firstDecryptGate = CompletableDeferred<Unit>()
        val secondDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var decryptCount = 0
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return when (decryptCount) {
                    1 -> {
                        firstDecryptGate.await()
                        item.withText("stale cleartext")
                    }
                    else -> {
                        secondDecryptGate.await()
                        item.withText("reauthorized cleartext")
                    }
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        revision++
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        firstDecryptGate.complete(Unit)
        runCurrent()
        assertThat(decryptCount).isEqualTo(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        secondDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("reauthorized cleartext")
    }

    @Test
    fun `new raw survives forced refresh and old worker revision retry interleaving`() = runTest {
        val sharedUniqueId = "refresh-set-race"
        val shellLabel = ""
        val oldDecryptGate = CompletableDeferred<Unit>()
        val newDecryptGate = CompletableDeferred<Unit>()
        val decryptedBodies = mutableListOf<String?>()
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val body = item.textBody()
                decryptedBodies += body
                return when (body) {
                    "old raw" -> {
                        oldDecryptGate.await()
                        item.withText("old cleartext")
                    }
                    "new raw" -> {
                        newDecryptGate.await()
                        item.withText("new cleartext")
                    }
                    else -> item
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )
        val oldItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("old raw")),
            uniqueId = sharedUniqueId,
        )
        val newItem = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("new raw")),
            uniqueId = sharedUniqueId,
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(oldItem))))
        runCurrent()
        assertThat(decryptedBodies).containsExactly("old raw")

        revision++
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        processor.postDiffs(listOf(TimelineDiff.Set(0u, newItem)))
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        oldDecryptGate.complete(Unit)
        runCurrent()
        assertThat(decryptedBodies).containsExactly("old raw", "new raw").inOrder()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)

        newDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEqualTo("new cleartext")
    }

    @Test
    fun `revision change after item validation rejects batch and retries current raw`() = runTest {
        val shellLabel = ""
        val retryDecryptGate = CompletableDeferred<Unit>()
        var authorizationRevisionCalls = 0
        var decryptCount = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long {
                authorizationRevisionCalls++
                // Calls 1-2 surround decryption. Call 3 is batch validation. The transition on
                // call 4 is deterministic and occurs at the final batch commit boundary.
                return if (authorizationRevisionCalls <= 3) 0L else 1L
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return if (decryptCount == 1) {
                    item.withText("stale cleartext")
                } else {
                    retryDecryptGate.await()
                    item.withText("current cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(decryptCount).isEqualTo(2)
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("stale cleartext")

        retryDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        assertThat(timelineItems.value.single().textBody()).isEqualTo("current cleartext")
    }

    @Test
    fun `authorization refresh before projection prevents queued stale cleartext from returning`() = runTest {
        val shellLabel = ""
        val refreshedDecryptGate = CompletableDeferred<Unit>()
        var revision = 0L
        var decryptCount = 0
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
                items.map { it.withText(shellLabel) }

            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = true

            override fun authorizationRevision(): Long = revision

            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }

            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                decryptCount++
                return if (decryptCount == 1) {
                    item.withText("queued stale cleartext")
                } else {
                    refreshedDecryptGate.await()
                    item.withText("current cleartext")
                }
            }
        }
        val processor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
            protectedContentAdapter = adapter,
        )

        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem()))))
        runCurrent()
        assertThat(decryptCount).isEqualTo(1)

        revision++
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo(shellLabel)
        assertThat(timelineItems.value.single().textBody()).isNotEqualTo("queued stale cleartext")

        refreshedDecryptGate.complete(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("current cleartext")
    }

    @Test
    fun `relay edit carriers update exact author item in place and rebuild identically after reset`() = runTest {
        val root = "$" + "relay-root"
        val body = "接龙：Team\n1. Alice original"
        fun operation(id: String, kind: ProtectedOperationContent.Operation, text: String? = null, revision: Long? = null) =
            ProtectedOperationContent(protocolVersion=1,operationId=id,operation=kind,targetEventId=root,
                aggregateId=root,relayEntry=text,relayEntryId="bob-item",relayExpectedRevision=revision)
        val contentById = linkedMapOf(
            root to MessageContent(body,null,false,null,TextMessageType(body,null),isContentProtectionAuthorized=true,
                protectedRelayInitialEntryId="alice-item"),
            "$"+"append" to operation("append-op",ProtectedOperationContent.Operation.RELAY_APPEND,"Bob original"),
            "$"+"wrong-author" to operation("wrong-author-op",ProtectedOperationContent.Operation.RELAY_EDIT,"Attack",0),
            "pending-edit" to operation("edit-op",ProtectedOperationContent.Operation.RELAY_EDIT,"Bob revised",0),
            "$"+"edit" to operation("edit-op",ProtectedOperationContent.Operation.RELAY_EDIT,"Bob revised",0),
            "$"+"stale" to operation("stale-op",ProtectedOperationContent.Operation.RELAY_EDIT,"Stale",0),
            "$"+"close" to operation("close-op",ProtectedOperationContent.Operation.RELAY_CLOSE),
            "$"+"late" to operation("late-op",ProtectedOperationContent.Operation.RELAY_EDIT,"Late",1),
        )
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val event = item as? MatrixTimelineItem.Event ?: return item
                val content = contentById[event.eventId?.value ?: event.transactionId?.value] ?: return item
                return event.copy(event=event.event.copy(content=content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems,protectedContentAdapter=adapter)
        val events = contentById.keys.mapIndexed { index,id -> FakeFfiTimelineItem(
            asEventResult=aRustEventTimelineItem(eventOrTransactionId=if (id == "pending-edit") RustEventOrTransactionId.TransactionId(id)
                else RustEventOrTransactionId.EventId(id),
                sender=if (id == root || id == "$"+"wrong-author" || id == "$"+"close") A_USER_ID.value else A_USER_ID_2.value),
            uniqueId="relay-$index",
        ) }
        repeat(2) {
            processor.postDiffs(listOf(TimelineDiff.Reset(events)))
            runCurrent()
            repeat(5) { advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS+1); runCurrent() }
            assertThat(timelineItems.value).hasSize(1)
            val projected = (timelineItems.value.single() as MatrixTimelineItem.Event).event.content as MessageContent
            assertThat(projected.protectedRelayEntries?.map { it.text }).containsExactly("Alice original","Bob revised").inOrder()
            assertThat(projected.protectedRelayEntries?.map { it.id }).containsExactly("alice-item","bob-item").inOrder()
            assertThat(projected.protectedRelayEntries?.last()?.revision).isEqualTo(1L)
            assertThat(projected.protectedRelayEntries?.last()?.author).isEqualTo(A_USER_ID_2)
            assertThat(projected.protectedRelayEntries?.last()?.isConfirmed).isTrue()
            assertThat(projected.body).contains("（已结束）")
        }
    }

    @Test
    fun `protected reaction operations are reduced deterministically and replay ids are ignored`() = runTest {
        val targetId = "\$protected-target"
        val firstOperationId = "\$protected-reaction-1"
        val replayOperationId = "\$protected-reaction-2"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    targetId -> MessageContent(
                        body = "protected target",
                        inReplyTo = null,
                        isEdited = false,
                        threadInfo = null,
                        type = TextMessageType("protected target", null),
                    )
                    firstOperationId -> reactionOperation(targetId, enabled = true)
                    replayOperationId -> reactionOperation(targetId, enabled = false)
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val target = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(targetId),
                sender = A_USER_ID.value,
            ),
            uniqueId = "protected-target",
        )
        val operation = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(firstOperationId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "protected-reaction-1",
        )
        val replay = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(replayOperationId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "protected-reaction-2",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(target, operation, replay))))
        runCurrent()
        repeat(3) {
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
        }

        assertThat(timelineItems.value).hasSize(1)
        val projected = timelineItems.value.single() as MatrixTimelineItem.Event
        assertThat(projected.event.reactions).hasSize(1)
        assertThat(projected.event.reactions.single().key).isEqualTo("👍")
        assertThat(projected.event.reactions.single().senders.single().senderId).isEqualTo(A_USER_ID_2)
    }

    @Test
    fun `protected operation replay ids are scoped by actor and root after target validation`() = runTest {
        val firstRootId = "$" + "protected-scope-root-1"
        val secondRootId = "$" + "protected-scope-root-2"
        val missingRootId = "$" + "protected-scope-missing-root"
        val actorOneEventId = "$" + "protected-scope-actor-1"
        val actorTwoEventId = "$" + "protected-scope-actor-2"
        val missingRootEventId = "$" + "protected-scope-missing-event"
        val validRootEventId = "$" + "protected-scope-valid-event"
        val sharedActorOperationId = "shared-operation-id-across-actors"
        val sharedRootOperationId = "shared-operation-id-across-roots"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    firstRootId,
                    secondRootId -> MessageContent(
                        body = "protected target",
                        inReplyTo = null,
                        isEdited = false,
                        threadInfo = null,
                        type = TextMessageType("protected target", null),
                    )
                    actorOneEventId -> reactionOperation(firstRootId, enabled = true)
                        .copy(operationId = sharedActorOperationId)
                    actorTwoEventId -> reactionOperation(firstRootId, enabled = true)
                        .copy(operationId = sharedActorOperationId)
                    missingRootEventId -> reactionOperation(missingRootId, enabled = true)
                        .copy(operationId = sharedRootOperationId)
                    validRootEventId -> reactionOperation(secondRootId, enabled = true)
                        .copy(operationId = sharedRootOperationId)
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val firstRoot = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(firstRootId),
                sender = A_USER_ID.value,
            ),
            uniqueId = "protected-scope-root-1",
        )
        val actorOneOperation = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(actorOneEventId),
                sender = A_USER_ID.value,
            ),
            uniqueId = "protected-scope-actor-1",
        )
        val actorTwoOperation = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(actorTwoEventId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "protected-scope-actor-2",
        )
        val missingRootOperation = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(missingRootEventId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "protected-scope-missing-event",
        )
        val secondRoot = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(secondRootId),
                sender = A_USER_ID.value,
            ),
            uniqueId = "protected-scope-root-2",
        )
        val validRootOperation = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(validRootEventId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "protected-scope-valid-event",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(
            firstRoot,
            actorOneOperation,
            actorTwoOperation,
            missingRootOperation,
            secondRoot,
            validRootOperation,
        ))))
        runCurrent()
        repeat(6) {
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
        }

        val projectedById = timelineItems.value
            .filterIsInstance<MatrixTimelineItem.Event>()
            .associateBy { it.eventId?.value }
        val firstRootReactions = requireNotNull(projectedById[firstRootId]).event.reactions
        assertThat(firstRootReactions).hasSize(1)
        assertThat(firstRootReactions.single().senders.map { it.senderId })
            .containsExactly(A_USER_ID, A_USER_ID_2)
            .inOrder()
        val secondRootReactions = requireNotNull(projectedById[secondRootId]).event.reactions
        assertThat(secondRootReactions).hasSize(1)
        assertThat(secondRootReactions.single().senders.single().senderId).isEqualTo(A_USER_ID_2)
    }

    @Test
    fun `invalid protected operation does not consume the replay id before a valid operation is accepted`() = runTest {
        val rootId = "$" + "protected-validation-root"
        val invalidEventId = "$" + "protected-validation-invalid"
        val validEventId = "$" + "protected-validation-valid"
        val replayEventId = "$" + "protected-validation-replay"
        val operationId = "shared-operation-id-after-invalid-payload"
        fun pollResponse(answerId: String) = ProtectedOperationContent(
            protocolVersion = MatrixTimelineDiffProcessor.PROTECTED_OPERATION_PROTOCOL_VERSION,
            operationId = operationId,
            operation = ProtectedOperationContent.Operation.POLL_RESPONSE,
            targetEventId = rootId,
            aggregateId = rootId,
            selectedAnswerIds = listOf(answerId),
        )
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    rootId -> PollContent(
                        question = "Choose one",
                        kind = PollKind.Disclosed,
                        maxSelections = 1uL,
                        answers = persistentListOf(
                            PollAnswer("answer-a", "A"),
                            PollAnswer("answer-b", "B"),
                        ),
                        votes = persistentMapOf(),
                        endTime = null,
                        isEdited = false,
                        threadInfo = null,
                    )
                    invalidEventId -> pollResponse("unknown-answer")
                    validEventId -> pollResponse("answer-a")
                    replayEventId -> pollResponse("answer-b")
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        fun timelineItem(eventId: String, uniqueId: String) = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(eventId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = uniqueId,
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(
            timelineItem(rootId, "protected-validation-root"),
            timelineItem(invalidEventId, "protected-validation-invalid"),
            timelineItem(validEventId, "protected-validation-valid"),
            timelineItem(replayEventId, "protected-validation-replay"),
        ))))
        runCurrent()
        repeat(4) {
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
        }

        val projected = timelineItems.value.single() as MatrixTimelineItem.Event
        val poll = projected.event.content as PollContent
        assertThat(poll.votes["answer-a"]).containsExactly(A_USER_ID_2)
        assertThat(poll.votes["answer-b"]).isNull()
    }

    @Test
    fun `aggregate destruction excludes protected operations owned by another sender`() = runTest {
        val targetId = "$" + "destruction-target"
        val ownOperationId = "$" + "destruction-own-reaction"
        val foreignOperationId = "$" + "destruction-foreign-reaction"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    targetId -> MessageContent(
                        body = "protected target",
                        inReplyTo = null,
                        isEdited = false,
                        threadInfo = null,
                        type = TextMessageType("protected target", null),
                    )
                    ownOperationId,
                    foreignOperationId -> reactionOperation(targetId, enabled = true)
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        fun item(id: String, sender: String) = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(id),
                sender = sender,
            ),
            uniqueId = id,
        )

        processor.postDiffs(
            listOf(
                TimelineDiff.Reset(
                    listOf(
                        item(targetId, A_USER_ID.value),
                        item(ownOperationId, A_USER_ID.value),
                        item(foreignOperationId, A_USER_ID_2.value),
                    )
                )
            )
        )
        runCurrent()
        repeat(3) {
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
        }

        val destructionTargets = processor.rawItemsForDestruction(EventOrTransactionId.Event(EventId(targetId)))
            .filterIsInstance<MatrixTimelineItem.Event>()
            .mapNotNull { it.eventId?.value }

        assertThat(destructionTargets).containsExactly(targetId, ownOperationId)
    }

    @Test
    fun `protected DM call lifecycle reaches ended once and ignores late non terminal state`() = runTest {
        val targetId = "\$call-target"
        val connectedId = "\$call-connected"
        val endedId = "\$call-ended"
        val lateConnectedId = "\$call-connected-late"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    targetId -> CallNotifyContent(CallIntent.AUDIO, declinedBy = emptyList())
                    connectedId -> callOperation("connected", targetId, CallLifecycleState.CONNECTED)
                    endedId -> callOperation("ended", targetId, CallLifecycleState.ENDED, durationMillis = 65_000L)
                    lateConnectedId -> callOperation("late-connected", targetId, CallLifecycleState.CONNECTED)
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        fun item(id: String, sender: String) = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(id),
                sender = sender,
            ),
            uniqueId = id,
        )

        processor.postDiffs(
            listOf(
                TimelineDiff.Reset(
                    listOf(
                        item(targetId, A_USER_ID.value),
                        item(connectedId, A_USER_ID_2.value),
                        item(endedId, A_USER_ID.value),
                        item(lateConnectedId, A_USER_ID_2.value),
                    )
                )
            )
        )
        runCurrent()
        repeat(3) {
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
        }

        assertThat(timelineItems.value).hasSize(1)
        val call = (timelineItems.value.single() as MatrixTimelineItem.Event).event.content as CallNotifyContent
        assertThat(call.lifecycleState).isEqualTo(CallLifecycleState.ENDED)
        assertThat(call.durationMillis).isEqualTo(65_000L)
        assertThat(call.lifecycleActor).isEqualTo(A_USER_ID)
    }

    @Test
    fun `pending original call operation stays hidden and exact binding recovery updates original card`() = runTest {
        val targetId = "\$original-call-target"
        val operationEventId = "\$original-call-state"
        val operationId = "original-lc20-operation"
        var bound = false
        var permitted = false
        val projectedOperationEvents = mutableListOf<MatrixTimelineItem.Event>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val event = item as? MatrixTimelineItem.Event ?: return item
                val content = when (event.eventId?.value) {
                    targetId -> CallNotifyContent(CallIntent.AUDIO, declinedBy = emptyList(), lifecycleState = CallLifecycleState.CONNECTED)
                    operationEventId -> {
                        if (!bound || !permitted) {
                            return (projectProtectedMessageStatus(event, null,
                                if (!bound) ProtectedMessageDisplayState.SEND_BINDING_PENDING else ProtectedMessageDisplayState.ACCESS_DENIED,
                                io.element.android.security.contentprotection.api.ContentKind.CALL_OPERATION,
                            ) as MatrixTimelineItem.Event).also(projectedOperationEvents::add)
                        }
                        callOperation(operationId, targetId, CallLifecycleState.ENDED, durationMillis = 39_000L)
                    }
                    else -> return item
                }
                return event.copy(event = event.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val raw = listOf(targetId, operationEventId).map { id -> FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(id), sender = A_USER_ID.value),
            uniqueId = id,
        ) }
        suspend fun rebuild(): CallNotifyContent {
            processor.postDiffs(listOf(TimelineDiff.Reset(raw)))
            runCurrent()
            repeat(3) {
                advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
                runCurrent()
            }
            assertThat(timelineItems.value).hasSize(1)
            val card = timelineItems.value.single() as MatrixTimelineItem.Event
            assertThat(card.eventId?.value).isEqualTo(targetId)
            assertThat(processor.rawItemsForDestruction(EventOrTransactionId.Event(EventId(operationEventId)))
                .filterIsInstance<MatrixTimelineItem.Event>().map { it.eventId?.value }).contains(operationEventId)
            return card.event.content as CallNotifyContent
        }
        assertThat(rebuild().lifecycleState).isEqualTo(CallLifecycleState.CONNECTED)
        bound = true
        assertThat(rebuild().lifecycleState).isEqualTo(CallLifecycleState.CONNECTED)
        assertThat(projectedOperationEvents.map { it.eventId?.value }.toSet()).containsExactly(operationEventId)
        permitted = true
        assertThat(rebuild().durationMillis).isEqualTo(39_000L)
        assertThat(rebuild().lifecycleState).isEqualTo(CallLifecycleState.ENDED)
    }

    @Test
    fun `hidden call binding retries original raw event without sync and authorization denial does not retry`() = runTest {
        listOf(ProtectedMessageDisplayState.SEND_BINDING_PENDING, ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE,
            ProtectedMessageDisplayState.ACCESS_DENIED).forEach { initialState ->
            val projections = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
            val targetId = "\$retry-call-target"
            val operationEventId = "\$retry-call-state"
            var admitted = false
            val attempted = mutableListOf<MatrixTimelineItem.Event>()
            val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
                override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> = items.map { item ->
                    val event = item as? MatrixTimelineItem.Event
                    if (event?.eventId?.value == operationEventId) hiddenProtectedOperationItem(event) else item
                }

                override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                    val event = item as? MatrixTimelineItem.Event ?: return item
                    val content = when (event.eventId?.value) {
                        targetId -> CallNotifyContent(CallIntent.AUDIO, declinedBy = emptyList(), lifecycleState = CallLifecycleState.CONNECTED)
                        operationEventId -> {
                            attempted += event
                            if (!admitted) return projectProtectedMessageStatus(event, null, initialState,
                                io.element.android.security.contentprotection.api.ContentKind.CALL_OPERATION)
                            callOperation("original-lc20-retry-operation", targetId, CallLifecycleState.ENDED, durationMillis = 39_000L)
                        }
                        else -> return item
                    }
                    return event.copy(event = event.event.copy(content = content))
                }
            }
            val processor = createMatrixTimelineDiffProcessor(projections, protectedContentAdapter = adapter)
            val raw = listOf(targetId, operationEventId).map { id -> FakeFfiTimelineItem(
                asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(id), sender = A_USER_ID.value),
                uniqueId = id,
            ) }
            processor.postDiffs(listOf(TimelineDiff.Reset(raw)))
            runCurrent()
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
            assertThat(projections.value).hasSize(1)
            assertThat(attempted).hasSize(1)
            admitted = true
            advanceTimeBy(1_001L + MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS)
            runCurrent()
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
            assertThat(projections.value).hasSize(1)
            val card = projections.value.single() as MatrixTimelineItem.Event
            assertThat(card.eventId?.value).isEqualTo(targetId)
            val call = card.event.content as CallNotifyContent
            if (initialState == ProtectedMessageDisplayState.ACCESS_DENIED) {
                assertThat(attempted).hasSize(1)
                assertThat(call.lifecycleState).isEqualTo(CallLifecycleState.CONNECTED)
            } else {
                assertThat(attempted).hasSize(2)
                assertThat(attempted[1]).isEqualTo(attempted[0])
                assertThat(call.lifecycleState).isEqualTo(CallLifecycleState.ENDED)
                assertThat(call.durationMillis).isEqualTo(39_000L)
            }
        }
    }

    @Test
    fun `protected reply keeps independent body and replaces derived quote when target is redacted`() = runTest {
        val targetId = "\$lineage-target"
        val replyId = "\$lineage-reply"
        val originalMarker = "LINEAGE-ORIGINAL-MARKER"
        val independentReply = "independent reply body"
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val eventItem = item as? MatrixTimelineItem.Event ?: return item
                val content = when (eventItem.eventId?.value) {
                    targetId -> MessageContent(
                        body = originalMarker,
                        inReplyTo = null,
                        isEdited = false,
                        threadInfo = null,
                        type = TextMessageType(originalMarker, null),
                        isContentProtectionAuthorized = true,
                    )
                    replyId -> MessageContent(
                        body = independentReply,
                        inReplyTo = InReplyTo.NotLoaded(EventId(targetId)),
                        isEdited = false,
                        threadInfo = null,
                        type = TextMessageType(independentReply, null),
                        isContentProtectionAuthorized = true,
                    )
                    else -> return item
                }
                return eventItem.copy(event = eventItem.event.copy(content = content))
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val target = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(targetId),
                sender = A_USER_ID.value,
            ),
            uniqueId = "lineage-target",
        )
        val reply = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(replyId),
                sender = A_USER_ID_2.value,
            ),
            uniqueId = "lineage-reply",
        )

        processor.postDiffs(listOf(TimelineDiff.Reset(listOf(target, reply))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()

        val projectedReplyBefore = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
            .single { it.eventId?.value == replyId }
        val replyBefore = projectedReplyBefore.event.content as MessageContent
        assertThat(replyBefore.body).isEqualTo(independentReply)
        assertThat(((replyBefore.inReplyTo as InReplyTo.Ready).content as MessageContent).body).isEqualTo(originalMarker)

        val redactedTarget = FakeFfiTimelineItem(
            asEventResult = aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId(targetId),
                sender = A_USER_ID.value,
                content = aRustTimelineItemContentRedacted(),
            ),
            uniqueId = "lineage-target",
        )
        processor.postDiffs(listOf(TimelineDiff.Set(0u, redactedTarget)))

        val projectedReplyAfter = timelineItems.value.filterIsInstance<MatrixTimelineItem.Event>()
            .single { it.eventId?.value == replyId }
        val replyAfter = projectedReplyAfter.event.content as MessageContent
        assertThat(replyAfter.body).isEqualTo(independentReply)
        val quoteAfter = replyAfter.inReplyTo as InReplyTo.Ready
        assertThat(quoteAfter.eventId.value).isEqualTo(targetId)
        assertThat(quoteAfter.content).isEqualTo(RedactedContent)
        assertThat(replyAfter.toString()).doesNotContain(originalMarker)
    }

    @Test
    fun `retained room renews retired owner on lock and unlock without resubscription`() =
        assertRetainedRoomRenewsRetiredOwner(initiallyEmpty = false)

    @Test
    fun `closed room clears on lock and does not decrypt on unlock until resubscribed`() = runTest {
        var revision = 0L
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        var reads = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun authorizationRevision() = revision
            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.withText("") }
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                reads++
                return item.withText("authorized revision $revision")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(80L)
        val event = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "closed-room-event")
        processor.activateCommitScope(scope)
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(event)))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized revision 0")
        val initialReads = reads
        assertThat(initialReads).isGreaterThan(0)
        processor.invalidateCommitScope(scope)
        for (nextRevision in 1L..2L) {
            revision = nextRevision
            checkNotNull(refreshListener).invoke(hardRefresh(revision))
            runCurrent()
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
            assertThat(timelineItems.value.single().textBody()).isEmpty()
            assertThat(reads).isEqualTo(initialReads)
        }
        val reopenedScope = TimelineCommitScope(81L)
        processor.activateCommitScope(reopenedScope)
        processor.commitDiffs(TimelineDiffTransaction(reopenedScope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(event)))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized revision 2")
        assertThat(reads).isGreaterThan(initialReads)
    }

    @Test
    fun `empty retained room renews retired owner before first event arrives`() =
        assertRetainedRoomRenewsRetiredOwner(initiallyEmpty = true)

    private fun assertRetainedRoomRenewsRetiredOwner(initiallyEmpty: Boolean) = runTest {
        var revision = 0L
        var locked = false
        var currentOwner: String? = null
        var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val viewportOwners = mutableListOf<String>()
        val intents = mutableListOf<TimelineContentReleaseIntent>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun authorizationRevision() = revision
            override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
                refreshListener = listener
                return AutoCloseable { refreshListener = null }
            }
            override fun openTimelineReleaseScope(scopeId: String): String = "owner:$scopeId:${opened.size + 1}".also {
                opened += it
                currentOwner = it
            }
            override fun closeTimelineReleaseScope(scopeId: String) {
                closed += scopeId
                if (currentOwner == scopeId) currentOwner = null
            }
            override fun updateTimelineReleaseViewport(
                scopeId: String, generation: Long, visibleRequestIds: List<String>,
                bootstrapRequestIds: List<String>,
            ) {
                assertThat(generation).isEqualTo(1L)
                assertThat(visibleRequestIds).containsExactly("event:retained-owner-event")
                viewportOwners += scopeId
            }
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.withText("") }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                intents += timelineReleaseIntent
                return item.withText(if (!locked && timelineReleaseIntent.scopeId == currentOwner) "authorized fresh content" else "")
            }
        }
        val processor = createMatrixTimelineDiffProcessor(timelineItems, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(79L)
        val event = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "retained-owner-event")
        processor.activateCommitScope(scope)
        if (!initiallyEmpty) {
            processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(event)))))
            runCurrent()
            advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
            runCurrent()
            assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized fresh content")
        }
        processor.updateViewport(scope, 1L, listOf(UniqueId("retained-owner-event")))
        runCurrent()
        // Models runtime.clearSensitiveCaches retiring every KMS owner at the lock boundary.
        locked = true
        currentOwner = null
        revision = 1L
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        assertThat(opened).hasSize(2)
        if (!initiallyEmpty) assertThat(timelineItems.value.single().textBody()).isEmpty()
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        assertThat(opened).hasSize(2)
        locked = false
        currentOwner = null
        revision = 2L
        checkNotNull(refreshListener).invoke(hardRefresh(revision))
        runCurrent()
        if (initiallyEmpty) {
            processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(event)))))
        }
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        assertThat(timelineItems.value.single().textBody()).isEqualTo("authorized fresh content")
        assertThat(intents.last().scopeId).isEqualTo("owner:79:3")
        assertThat(intents.last().generation).isEqualTo(1L)
        assertThat(viewportOwners).containsExactly("owner:79:1", "owner:79:2", "owner:79:3").inOrder()
        revision = 3L
        checkNotNull(refreshListener).invoke(selectiveCacheExpiryRefresh(revision).copy(latestHardInvalidationRevision = 2L))
        runCurrent()
        assertThat(opened).hasSize(3)
        // Renewing the release owner must not reset Matrix's serialized transaction sequence.
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Clear)))
        assertThat(timelineItems.value).hasSize(1)
        processor.invalidateCommitScope(scope)
        assertThat(closed).containsExactly("owner:79:1", "owner:79:2", "owner:79:3").inOrder()
    }

    private fun reactionOperation(targetId: String, enabled: Boolean) = ProtectedOperationContent(
        protocolVersion = MatrixTimelineDiffProcessor.PROTECTED_OPERATION_PROTOCOL_VERSION,
        operationId = "stable-operation-id",
        operation = ProtectedOperationContent.Operation.REACTION_SET,
        targetEventId = targetId,
        aggregateId = targetId,
        emoji = "👍",
        enabled = enabled,
    )

    private fun callOperation(
        operationId: String,
        targetId: String,
        state: CallLifecycleState,
        durationMillis: Long? = null,
    ) = ProtectedOperationContent(
        protocolVersion = MatrixTimelineDiffProcessor.PROTECTED_OPERATION_PROTOCOL_VERSION,
        operationId = operationId,
        operation = ProtectedOperationContent.Operation.CALL_STATE,
        targetEventId = targetId,
        aggregateId = targetId,
        callLifecycleState = state,
        durationMillis = durationMillis,
    )
}

private fun MatrixTimelineItem.withText(body: String): MatrixTimelineItem {
    val eventItem = this as? MatrixTimelineItem.Event ?: return this
    return eventItem.copy(
        event = eventItem.event.copy(
            content = MessageContent(
                body = body,
                inReplyTo = null,
                isEdited = false,
                threadInfo = null,
                type = TextMessageType(body = body, formatted = null),
            )
        )
    )
}

private fun MatrixTimelineItem.withProtectedStatus(status: ProtectedMessageStatus): MatrixTimelineItem {
    val eventItem = this as? MatrixTimelineItem.Event ?: return this
    val content = eventItem.event.content as? MessageContent ?: return this
    return eventItem.copy(event = eventItem.event.copy(content = content.copy(protectedMessageStatus = status)))
}

private fun MatrixTimelineItem.textBody(): String? =
    ((this as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)?.body

private fun MatrixTimelineItem.protectedStatus(): ProtectedMessageStatus? =
    ((this as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)?.protectedMessageStatus

private fun MatrixTimelineItem.uniqueIdValue(): String = when (this) {
    is MatrixTimelineItem.Event -> uniqueId.value
    is MatrixTimelineItem.Virtual -> uniqueId.value
    MatrixTimelineItem.Other -> "other"
}

private fun MatrixTimelineItem.eventContent() =
    (this as? MatrixTimelineItem.Event)?.event?.content

private fun hardRefresh(revision: Long) = MatrixAuthorizationRefresh(
    revision = revision,
    mode = MatrixAuthorizationRefreshMode.HARD_INVALIDATION,
    latestHardInvalidationRevision = revision,
)

private fun selectiveCacheExpiryRefresh(revision: Long) = MatrixAuthorizationRefresh(
    revision = revision,
    mode = MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
    latestHardInvalidationRevision = 0L,
)

private fun historyRecoveryRefresh(
    revision: Long,
    roomScopeIds: Set<String>,
    latestHardInvalidationRevision: Long = 0L,
    recoveryGeneration: Long = 1L,
) = MatrixAuthorizationRefresh(
    revision = revision,
    mode = MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA,
    latestHardInvalidationRevision = latestHardInvalidationRevision,
    accountScopeId = "account-scope",
    roomScopeIds = roomScopeIds,
    recoveryGeneration = recoveryGeneration,
)

private fun aRustTimelineItemContentRedacted() = TimelineItemContent.MsgLike(
    content = MsgLikeContent(
        kind = MsgLikeKind.Redacted,
        reactions = emptyList(),
        threadRoot = null,
        inReplyTo = null,
        threadSummary = null,
    )
)

internal fun TestScope.createMatrixTimelineDiffProcessor(
    timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> = MutableSharedFlow(),
    membershipChangeEventReceivedFlow: MutableSharedFlow<Unit> = MutableSharedFlow(),
    syncedEventReceivedFlow: MutableSharedFlow<Unit> = MutableSharedFlow(),
    protectedContentAdapter: MatrixProtectedContentAdapter? = null,
    workerScope: CoroutineScope = backgroundScope,
    mediaRoomId: String? = null,
    ): MatrixTimelineDiffProcessor {
    val timelineEventContentMapper = TimelineEventContentMapper()
    val timelineItemFactory = MatrixTimelineItemMapper(
        fetchDetailsForEvent = { _ -> Result.success(Unit) },
        coroutineScope = this,
        virtualTimelineItemMapper = VirtualTimelineItemMapper(),
        eventTimelineItemMapper = EventTimelineItemMapper(
            contentMapper = timelineEventContentMapper
        )
    )
    return MatrixTimelineDiffProcessor(
        timelineItems = timelineItems,
        membershipChangeEventReceivedFlow = membershipChangeEventReceivedFlow,
        syncedEventReceivedFlow = syncedEventReceivedFlow,
        timelineItemMapper = timelineItemFactory,
        protectedContentAdapter = protectedContentAdapter,
        coroutineScope = workerScope,
        decryptDispatcher = StandardTestDispatcher(testScheduler),
        mediaRoomId = mediaRoomId,
    )
}
