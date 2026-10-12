/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.libraries.matrix.api.core.SendHandle
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.SendHandleProvider
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.security.contentprotection.api.OfflineOutboxService
import io.element.android.security.contentprotection.api.OfflineTextOutboxMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FailedMessageRetryTest {
    private val failed = LocalEventSendState.Failed.Unknown("previous failure")

    @Test
    fun `previous SDK failure retries its original handle without creating a new send`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        var retries = 0
        val event = aMessageEvent(sendState = failed, sendHandleProvider = SendHandleProvider {
            SendHandle { retries++; Result.success(Unit) }
        }).copy(id = UniqueId("sdk-old-failure"), displayId = UniqueId("optimistic:old-display"))
        assertThat(sut.retryFailedEvent(event).isSuccess).isTrue()
        assertThat(retries).isEqualTo(1)
        sut.close()
    }

    @Test
    fun `owned protected failure uses stable display identity and never falls back on security failure`() = runTest {
        val rejection = IllegalStateException("protected admission failed")
        var sends = 0
        val live = FakeTimeline().apply { sendMessageLambda = { _, _, _, _, _ -> sends++; Result.failure(rejection) } }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticLocalEcho("pending", null)
        sut.resolveOptimisticLocalEcho(id, rejection)
        var sdkRetries = 0
        val event = aMessageEvent(sendState = failed, sendHandleProvider = SendHandleProvider {
            SendHandle { sdkRetries++; Result.success(Unit) }
        }).copy(id = UniqueId("sdk-projection"), displayId = id)
        assertThat(sut.retryFailedEvent(event).exceptionOrNull()).isSameInstanceAs(rejection)
        assertThat(sends).isEqualTo(1)
        assertThat(sdkRetries).isEqualTo(0)
        val retained = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(retained.displayUniqueId).isEqualTo(id)
        assertThat(retained.event.localSendState).isInstanceOf(LocalEventSendState.Failed::class.java)
        sut.close()
    }

    @Test
    fun `restored old outbox failure retains the original transaction on retry`() = runTest {
        val entries = mutableListOf<OfflineTextOutboxMessage>()
        val outbox = object : OfflineOutboxService {
            override suspend fun stage(message: OfflineTextOutboxMessage): Boolean {
                entries.removeAll { it.transactionId == message.transactionId }; entries += message; return true
            }
            override suspend fun messages(accountId: String, roomId: String) = entries.toList()
            override suspend fun remove(accountId: String, roomId: String, transactionId: String): Boolean {
                entries.removeAll { it.transactionId == transactionId }; return true
            }
        }
        val old = FakeTimeline().apply { sendMessageLambda = { _, _, _, _, _ -> Result.failure(IllegalStateException("offline")) } }
        val first = TimelineController(FakeJoinedRoom(liveTimeline = old), old, offlineOutboxService = outbox)
        val id = first.addOptimisticLocalEcho("saved message", null)
        val original = (first.timelineItems().first().single() as MatrixTimelineItem.Event).transactionId
        first.resolveOptimisticLocalEcho(id, first.sendOptimisticLocalEcho(id).exceptionOrNull())
        first.close()
        var retriedTransaction: TransactionId? = null
        val fake = FakeTimeline().apply {
            backwardPaginationStatus.value = Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = false)
        }
        val live = object : Timeline by fake {
            override suspend fun sendMessageTransaction(
                transactionId: TransactionId, body: String, htmlBody: String?, intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?, msgType: MsgType, asPlainText: Boolean,
            ): Result<Unit> { retriedTransaction = transactionId; return Result.success(Unit) }
        }
        val restarted = TimelineController(FakeJoinedRoom(liveTimeline = live), live, offlineOutboxService = outbox)
        val restored = restarted.timelineItems().first { it.isNotEmpty() }.single() as MatrixTimelineItem.Event
        assertThat(restored.event.localSendState).isInstanceOf(LocalEventSendState.Failed::class.java)
        val event = aMessageEvent(sendState = failed, sendHandleProvider = SendHandleProvider { null })
            .copy(id = restored.uniqueId, displayId = restored.displayUniqueId)
        assertThat(restarted.retryFailedEvent(event).isSuccess).isTrue()
        assertThat(retriedTransaction).isEqualTo(original)
        assertThat(entries).isEmpty()
        restarted.close()
    }

    @Test
    fun `missing original send handle returns failure instead of silent success`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        assertThat(sut.retryFailedEvent(aMessageEvent(sendState = failed, sendHandleProvider = SendHandleProvider { null })).isFailure).isTrue()
        sut.close()
    }

    @Test
    fun `repeated taps share the same pending retry and do not duplicate sends`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var sends = 0
        val event = aMessageEvent(sendState = failed, sendHandleProvider = SendHandleProvider {
            SendHandle { sends++; started.complete(Unit); complete.await(); Result.success(Unit) }
        })
        val first = async { sut.retryFailedEvent(event) }
        started.await()
        assertThat(sut.retryFailedEvent(event).isSuccess).isTrue()
        assertThat(sends).isEqualTo(1)
        complete.complete(Unit)
        assertThat(first.await().isSuccess).isTrue()
        sut.close()
    }

    @Test
    fun `sent or incoming events cannot be retried`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        assertThat(sut.retryFailedEvent(aMessageEvent()).isFailure).isTrue()
        assertThat(sut.retryFailedEvent(aMessageEvent(isMine = false, sendState = failed)).isFailure).isTrue()
        sut.close()
    }
}
