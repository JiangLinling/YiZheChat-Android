/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.room.SendQueueUpdate
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.protectedTextTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.security.dataprotection.api.*
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class TextSendConfirmationIntegrationTest {
    private class Fixture {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val timeline = FakeTimeline(timelineItems = items).apply {
            sendMessageLambda = lambdaRecorder { _, _, _, _, _ -> Result.success(Unit) }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val operations = MutableStateFlow<Map<String, LifecycleOperation>>(emptyMap())
        val requests = mutableMapOf<UniqueId, LifecycleOperationRequest>()
        var reads = 0
        var freshReply: LifecycleOperation? = null
        val coordinator = object : AppOperationalStateCoordinator by FakeAppOperationalStateCoordinator() {
            override val operations = this@Fixture.operations
            override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation =
                committedTextSend(request, EventId("\$not-yet-sent")).copy(steps = emptyList()).also {
                    operations.value = operations.value + (request.operationId to it)
                }
            override suspend fun operation(operationId: String): LifecycleOperation {
                reads++
                return (freshReply?.takeIf { it.operationId == operationId } ?: operations.value.getValue(operationId)).also {
                    operations.value = operations.value + (operationId to it)
                }
            }
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) = Unit
        }
        val client = FakeMatrixClient(sessionId = room.sessionId)
        val controller = TimelineController(room, timeline, requireContentProtection = true,
            matrixClient = client, appOperationalStateCoordinator = coordinator)

        suspend fun send(): UniqueId {
            val id = controller.addOptimisticLocalEcho("same body", null)
            val request = io.element.android.features.messages.impl.messagecomposer.ordinaryMessageLifecycleRequest(room, client, "send", id.value)
            requests[id] = request
            // This test owns an already persisted local intent and uses the real send-context binding.
            coordinator.start(request)
            withContext(LifecycleOperationContext(request.operationId, request.lifecycleId)) {
                assertThat(controller.sendOptimisticLocalEcho(id).isSuccess).isTrue()
            }
            controller.resolveOptimisticLocalEcho(id, null)
            return id
        }

        suspend fun queueAck(id: UniqueId, eventId: EventId) = room.givenSendQueueUpdate(
            SendQueueUpdate.SentEvent(protectedTextTransactionId(room.roomId, TransactionId(id.value)), eventId))

        fun commit(id: UniqueId, eventId: EventId) {
            val operation = committedTextSend(requests.getValue(id), eventId)
            operations.value = operations.value + (operation.operationId to operation)
        }

        suspend fun sent(id: UniqueId): MatrixTimelineItem.Event = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                controller.timelineItems().first { list -> list.filterIsInstance<MatrixTimelineItem.Event>().any {
                    it.displayUniqueId == id && it.event.localSendState is LocalEventSendState.Sent
                } }.filterIsInstance<MatrixTimelineItem.Event>().single { it.displayUniqueId == id }
            }
        }
    }

    @Test fun `native ACK and signed direct status settle the original bubble without any history update`() = runTest {
        val f = Fixture()
        try {
            val id = f.send(); val event = EventId("\$direct-confirmation")
            f.freshReply = committedTextSend(f.requests.getValue(id), event)
            f.queueAck(id, event)
            val row = f.sent(id)
            assertThat(f.items.value).isEmpty()
            assertThat(row.eventId).isEqualTo(event)
            assertThat(row.event.isRemote).isFalse()
            assertThat(f.reads).isEqualTo(1)
            f.controller.resolveOptimisticLocalEcho(id, null)
            assertThat((f.controller.timelineItems().first().single() as MatrixTimelineItem.Event).event.localSendState)
                .isEqualTo(LocalEventSendState.Sent(event))
        } finally { f.controller.close() }
    }

    @Test fun `same body sends and reordered signed statuses keep distinct event identities`() = runTest {
        val f = Fixture()
        try {
            val first = f.send(); val second = f.send()
            val firstEvent = EventId("\$first"); val secondEvent = EventId("\$second")
            f.commit(second, secondEvent)
            f.queueAck(first, firstEvent)
            f.queueAck(second, secondEvent)
            assertThat(f.sent(second).eventId).isEqualTo(secondEvent)
            val pending = f.controller.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single { it.displayUniqueId == first }
            assertThat(pending.event.localSendState).isInstanceOf(LocalEventSendState.Sending::class.java)
            f.commit(first, firstEvent)
            assertThat(f.sent(first).eventId).isEqualTo(firstEvent)
            assertThat(f.controller.timelineItems().first()).hasSize(2)
        } finally { f.controller.close() }
    }

    @Test fun `confirmed bubble survives decrypting sync gap and terminal redaction still wins`() = runTest {
        val f = Fixture()
        try {
            val id = f.send(); val event = EventId("\$redacted-after-send")
            f.commit(id, event); f.queueAck(id, event)
            val local = f.sent(id)
            f.items.value = listOf(local.copy(uniqueId = UniqueId("sdk-row"), event = local.event.copy(
                eventId = null, transactionId = protectedTextTransactionId(f.room.roomId, TransactionId(id.value)),
            )))
            assertThat(f.sent(id).event.localSendState).isEqualTo(LocalEventSendState.Sent(event))
            f.items.value = emptyList()
            assertThat(f.sent(id).eventId).isEqualTo(event)
            val remote = local.copy(uniqueId = UniqueId("sdk-row"), event = local.event.copy(isRemote = true, transactionId = null))
            f.items.value = listOf(remote.copy(event = remote.event.copy(content = (remote.event.content as MessageContent).copy(
                body = "ciphertext", protectedMessageStatus = ProtectedMessageStatus.DECRYPTING))))
            assertThat(f.sent(id).event.localSendState).isEqualTo(LocalEventSendState.Sent(event))
            f.items.value = listOf(remote.copy(event = remote.event.copy(content = RedactedContent)))
            withContext(Dispatchers.Default) { withTimeout(5_000) {
                val rows = f.controller.timelineItems().first { list -> list.filterIsInstance<MatrixTimelineItem.Event>().any { it.event.content is RedactedContent } }
                assertThat(rows).hasSize(1)
            } }
            f.queueAck(id, event)
            assertThat((f.controller.timelineItems().first().single() as MatrixTimelineItem.Event).event.content).isInstanceOf(RedactedContent::class.java)
        } finally { f.controller.close() }
    }

    @Test fun `unrelated queue event and late commit cannot erase a visible failure`() = runTest {
        val f = Fixture()
        try {
            val id = f.send(); val event = EventId("\$late-ack")
            f.commit(id, event)
            f.room.givenSendQueueUpdate(SendQueueUpdate.SentEvent(TransactionId("unrelated"), event))
            f.controller.resolveOptimisticLocalEcho(id, IllegalStateException("original failure"))
            f.queueAck(id, event)
            val row = f.controller.timelineItems().first().single() as MatrixTimelineItem.Event
            assertThat(row.event.localSendState).isInstanceOf(LocalEventSendState.Failed::class.java)
        } finally { f.controller.close() }
    }
}
