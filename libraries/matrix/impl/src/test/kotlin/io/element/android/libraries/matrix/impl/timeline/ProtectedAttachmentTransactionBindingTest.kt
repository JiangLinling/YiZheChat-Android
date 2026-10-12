/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff

class ProtectedAttachmentTransactionBindingTest {
    private val name = ".yizhe-content.yzm"
    private val carrier = "yizhe-caption-v1:encrypted-caption"
    private val local = MatrixTimelineItem.Event(UniqueId("local"), anEventTimelineItem(
        isOwn = true,
        transactionId = TransactionId("sdk-generated-transaction"),
        content = aMessageContent(messageType = FileMessageType(name, carrier, null, MediaSource("file://encrypted"), null)),
    ).copy(eventId = null, origin = TimelineItemEventOrigin.LOCAL))

    @Test fun `attachment without a caption still binds only its exact original LOCAL transaction`() = runTest {
        val calls = mutableListOf<String>()
        val binding = ProtectedAttachmentTransactionBinding(name, null) { calls += it }
        val type = (local.event.content as MessageContent).type as FileMessageType
        val noCaption = local.copy(event = local.event.copy(content = aMessageContent(messageType = type.copy(caption = null))))
        assertFalse(binding.bind(local))
        assertFalse(binding.bind(noCaption.copy(event = noCaption.event.copy(isRemote = true))))
        assertTrue(binding.bind(noCaption))
        assertEquals(listOf("sdk-generated-transaction"), calls)
    }

    @Test fun `actual SDK transaction restores only the original caption send context`() = runTest {
        val input = ProtectedEventOwnershipResolver.Input("a", "b", "install", "a", "install", "room",
            null, local.transactionId!!.value, "caption-content", ContentKind.MEDIA_CAPTION, "carrier-hash")
        var origin = ProtectedEventOwnershipResolver.Origin("original-caption-operation", FullAppLifecycleId.LC_15,
            "a", "b", "install", "room", null, null, "caption-content", ContentKind.MEDIA_CAPTION, "carrier-hash")
        assertEquals(ProtectedEventOwnershipResolver.Reason.ORIGIN_CONFLICT,
            ProtectedEventOwnershipResolver.resolve(input, origin).reason)
        val calls = mutableListOf<String>()
        val binding = ProtectedAttachmentTransactionBinding(name, carrier) {
            calls += it
            origin = origin.copy(transactionId = it)
        }
        assertTrue(binding.bind(local))
        assertTrue(binding.bind(local))
        assertEquals(listOf(local.transactionId!!.value), calls)
        assertEquals("original-caption-operation", ProtectedEventOwnershipResolver.resolve(input, origin).sendContext?.operationId)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "\$unregistered-event"), origin).sendContext)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(matrixDeviceIdHash = "other-device"), origin).sendContext)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(senderInstallationId = "other-install"), origin).sendContext)
        assertFalse(binding.bind(local.copy(event = local.event.copy(transactionId = TransactionId("second-transaction")))))
    }

    @Test fun `copied carrier cannot bind from remote cache canonical event or different payload`() = runTest {
        var calls = 0
        val binding = ProtectedAttachmentTransactionBinding(name, carrier) { calls++ }
        val type = (local.event.content as MessageContent).type as FileMessageType
        val variants = listOf(
            local.event.copy(isOwn = false),
            local.event.copy(isRemote = true),
            local.event.copy(origin = TimelineItemEventOrigin.SYNC),
            local.event.copy(origin = TimelineItemEventOrigin.CACHE),
            local.event.copy(origin = null),
            local.event.copy(eventId = EventId("\$copied-event")),
            local.event.copy(transactionId = null),
            local.event.copy(transactionId = TransactionId("bad transaction")),
            local.event.copy(content = aMessageContent(messageType = type.copy(filename = "other.yzm"))),
            local.event.copy(content = aMessageContent(messageType = type.copy(caption = "$carrier-tampered"))),
        )
        variants.forEach { assertFalse(binding.bind(local.copy(event = it))) }
        assertEquals(0, calls)
        assertTrue(binding.bind(local))
        assertEquals(1, calls)
    }

    @Test fun `journal failure keeps original handoff retryable and never reports bound`() = runTest {
        var attempts = 0
        val binding = ProtectedAttachmentTransactionBinding(name, carrier) {
            attempts++
            if (attempts == 1) throw IllegalStateException("journal unavailable")
        }
        try { binding.bind(local); fail("Journal failure must propagate") } catch (_: IllegalStateException) { }
        assertTrue(binding.bind(local))
        assertEquals(2, attempts)
    }

    @Test fun `transaction is journaled before any shell or decrypt projection sees local event`() = runTest {
        val phases = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> {
                assertEquals("bind", phases.firstOrNull())
                phases += "shell"
                return items
            }
        }
        val mapper = mockk<MatrixTimelineItemMapper>()
        every { mapper.map(any()) } returns local
        val processor = MatrixTimelineDiffProcessor(MutableStateFlow(emptyList()), MutableStateFlow(Unit),
            MutableStateFlow(Unit), mapper, adapter)
        processor.registerPreparedAttachment(name, carrier) { phases += "bind" }
        processor.postDiffs(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem())))
        assertEquals(listOf("bind", "shell"), phases)
    }
}
