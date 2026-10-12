/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline.factories

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aTimelineItemsFactory
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemRedactedContent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.Receipt
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TimelineItemsFactoryTest {
    @Test
    fun `pending sender avatar updates when member profile arrives without a new timeline snapshot`() = runTest {
        val factory = aTimelineItemsFactory(TimelineItemsFactoryConfig(computeReadReceipts = false, computeReactions = false))
        val sender = UserId("@own:server")
        val pending = MatrixTimelineItem.Event(UniqueId("optimistic:avatar"), anEventTimelineItem(
            isOwn = true, isRemote = false, sender = sender, content = aMessageContent(body = "pending"),
        ).copy(senderProfile = io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails.Unavailable))
        val items = listOf(pending)
        factory.timelineItems.test {
            factory.replaceWith(items, emptyList())
            assertThat(awaitItem().events().single().senderAvatar.url).isNull()
            factory.replaceWith(items, listOf(aRoomMember(userId = sender, displayName = "Own", avatarUrl = "mxc://own/avatar")))
            val updated = awaitItem().events().single()
            assertThat(updated.senderAvatar.name).isEqualTo("Own")
            assertThat(updated.senderAvatar.url).isEqualTo("mxc://own/avatar")
            assertThat(updated.id).isEqualTo(pending.uniqueId)
        }
    }

    @Test
    fun `updated send states survive unrelated timeline refreshes`() = runTest {
        val factory = aTimelineItemsFactory(
            TimelineItemsFactoryConfig(computeReadReceipts = false, computeReactions = false)
        )
        val localId = UniqueId("optimistic:send-progress")
        var pending = MatrixTimelineItem.Event(
            uniqueId = localId,
            event = anEventTimelineItem(isOwn = true, isRemote = false, content = aMessageContent(body = "pending")).copy(
                eventId = null,
                localSendState = LocalEventSendState.Sending.Encrypting,
            ),
        )
        var unrelated = MatrixTimelineItem.Event(
            uniqueId = UniqueId("unrelated"),
            event = anEventTimelineItem(eventId = EventId("\$unrelated"), content = aMessageContent(body = "unrelated")),
        )

        factory.timelineItems.test {
            factory.replaceWith(listOf(unrelated, pending), emptyList())
            assertThat(awaitItem().events().single { it.displayId == localId }.localSendState)
                .isEqualTo(LocalEventSendState.Sending.Encrypting)

            val sentEventId = EventId("\$sent")
            val states = listOf(
                LocalEventSendState.Sending.Event,
                LocalEventSendState.Failed.Unknown("test failure"),
                LocalEventSendState.Sending.Encrypting,
                LocalEventSendState.Sending.Event,
                LocalEventSendState.Sent(sentEventId),
            )
            states.forEachIndexed { index, sendState ->
                pending = pending.copy(event = pending.event.copy(
                    localSendState = sendState,
                    eventId = if (sendState is LocalEventSendState.Sent) sentEventId else null,
                ))
                factory.replaceWith(listOf(unrelated, pending), emptyList())
                val updated = awaitItem().events().single { it.displayId == localId }
                assertThat(updated.localSendState).isEqualTo(sendState)

                unrelated = unrelated.copy(event = unrelated.event.copy(content = aMessageContent(body = "refresh-$index")))
                factory.replaceWith(listOf(unrelated, pending), emptyList())
                val retained = awaitItem().events().single { it.displayId == localId }
                assertThat(retained.localSendState).isEqualTo(sendState)
                assertThat(retained.eventId).isEqualTo(updated.eventId)
                assertThat(retained.id).isEqualTo(localId)
            }
        }
    }

    @Test
    fun `redacted projection wins before duplicate event identity reaches compose`() {
        val eventId = EventId("\$burn-after-read")
        val stalePlaintext = aTimelineItemEvent(eventId = eventId).copy(
            id = UniqueId("live57"),
            displayId = UniqueId("live57"),
        )
        val redacted = aTimelineItemEvent(
            eventId = eventId,
            content = aTimelineItemRedactedContent(),
        ).copy(
            id = UniqueId("live58"),
            displayId = UniqueId("live58"),
        )

        val result = coalesceDuplicateRemoteEventProjections(listOf(stalePlaintext, redacted))

        assertThat(result).containsExactly(redacted)
        assertThat(duplicateComposeKeyDiagnostics(result, generation = 1)).isEmpty()
    }

    @Test
    fun `duplicate compose key diagnostics identify every conflicting event without message content`() {
        val duplicateDisplayId = UniqueId("optimistic:duplicate")
        val first = aTimelineItemEvent(
            eventId = EventId("\$first"),
            transactionId = io.element.android.libraries.matrix.api.core.TransactionId(duplicateDisplayId.value),
        ).copy(id = UniqueId("first"), displayId = duplicateDisplayId)
        val second = aTimelineItemEvent(
            eventId = EventId("\$second"),
            transactionId = io.element.android.libraries.matrix.api.core.TransactionId("second-transaction"),
        ).copy(id = UniqueId("second"), displayId = duplicateDisplayId)

        val conflicts = duplicateComposeKeyDiagnostics(listOf(first, second), generation = 7)

        assertThat(conflicts).hasSize(2)
        assertThat(conflicts.map { it.composeKey }).containsExactly(duplicateDisplayId.value, duplicateDisplayId.value)
        assertThat(conflicts.map { it.eventId }).containsExactly("\$first", "\$second")
        assertThat(conflicts.map { it.timelineIndex }).containsExactly(0, 1)
        assertThat(conflicts.map { it.generation }).containsExactly(7L, 7L)
    }

    @Test
    fun `first empty timeline is emitted`() = runTest {
        val factory = aTimelineItemsFactory(
            TimelineItemsFactoryConfig(
                computeReadReceipts = false,
                computeReactions = false,
            )
        )

        factory.timelineItems.test {
            factory.replaceWith(emptyList(), emptyList())
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `confirmation updates the same presentation item and virtual items do not affect sender grouping`() = runTest {
        val factory = aTimelineItemsFactory(
            TimelineItemsFactoryConfig(
                computeReadReceipts = false,
                computeReactions = false,
            )
        )
        val previousId = UniqueId("previous")
        val localId = UniqueId("optimistic:local")
        val remoteId = UniqueId("remote")
        val typing = MatrixTimelineItem.Virtual(UniqueId("typing"), VirtualTimelineItem.TypingNotification)
        val previous = MatrixTimelineItem.Event(
            uniqueId = previousId,
            event = anEventTimelineItem(
                eventId = EventId("\$previous"),
                isOwn = true,
                isRemote = true,
                timestamp = 1L,
                content = aMessageContent(body = "previous"),
            ),
        )
        val local = MatrixTimelineItem.Event(
            uniqueId = localId,
            event = anEventTimelineItem(
                eventId = EventId("\$temporary"),
                isOwn = true,
                isRemote = false,
                timestamp = 2L,
                content = aMessageContent(body = "pending"),
            ).copy(eventId = null),
        )
        val remoteEventId = EventId("\$remote")
        val remote = MatrixTimelineItem.Event(
            uniqueId = remoteId,
            displayUniqueId = localId,
            event = anEventTimelineItem(
                eventId = remoteEventId,
                isOwn = true,
                isRemote = true,
                timestamp = 2L,
                content = aMessageContent(body = "pending"),
            ),
        )

        factory.timelineItems.test {
            factory.replaceWith(listOf(previous, typing, local), emptyList())
            val pendingItem = awaitItem().events().single { it.displayId == localId }
            assertThat(pendingItem.id).isEqualTo(localId)
            assertThat(pendingItem.eventId).isNull()
            assertThat(pendingItem.groupPosition).isEqualTo(TimelineItemGroupPosition.Last)

            factory.replaceWith(listOf(previous, typing, remote), emptyList())
            val confirmedItem = awaitItem().events().single { it.displayId == localId }
            assertThat(confirmedItem.id).isEqualTo(localId)
            assertThat(confirmedItem.eventId).isEqualTo(remoteEventId)
            assertThat(confirmedItem.groupPosition).isEqualTo(TimelineItemGroupPosition.Last)
        }
    }

    @Test
    fun `member profile changes only rebuild events whose read receipts use that member`() = runTest {
        val factory = aTimelineItemsFactory(
            TimelineItemsFactoryConfig(
                computeReadReceipts = true,
                computeReactions = false,
            )
        )
        val receiptUserId = UserId("@receipt:example.org")
        val unaffected = MatrixTimelineItem.Event(
            uniqueId = UniqueId("unaffected"),
            event = anEventTimelineItem(
                eventId = EventId("\$unaffected"),
                timestamp = 1L,
                content = aMessageContent(body = "unaffected"),
            ),
        )
        val receiptBearing = MatrixTimelineItem.Event(
            uniqueId = UniqueId("receipt-bearing"),
            event = anEventTimelineItem(
                eventId = EventId("\$receipt-bearing"),
                timestamp = 2L,
                receipts = persistentListOf(Receipt(receiptUserId, timestamp = 3L)),
                content = aMessageContent(body = "receipt-bearing"),
            ),
        )
        val timeline = listOf(unaffected, receiptBearing)

        factory.timelineItems.test {
            factory.replaceWith(
                timeline,
                listOf(aRoomMember(userId = receiptUserId, displayName = "Before", avatarUrl = "mxc://before")),
            )
            val before = awaitItem().events().associateBy { it.displayId }

            factory.replaceWith(
                timeline,
                listOf(aRoomMember(userId = receiptUserId, displayName = "After", avatarUrl = "mxc://after")),
            )
            val after = awaitItem().events().associateBy { it.displayId }

            assertThat(after.getValue(unaffected.displayUniqueId))
                .isSameInstanceAs(before.getValue(unaffected.displayUniqueId))
            assertThat(after.getValue(receiptBearing.displayUniqueId))
                .isNotSameInstanceAs(before.getValue(receiptBearing.displayUniqueId))
            val updatedReceipt = after.getValue(receiptBearing.displayUniqueId).readReceiptState.receipts.single()
            assertThat(updatedReceipt.avatarData.name).isEqualTo("After")
            assertThat(updatedReceipt.avatarData.url).isEqualTo("mxc://after")
        }
    }

    @Test
    fun `initial visible window keeps exact event instances when history is expanded`() = runTest {
        val factory = aTimelineItemsFactory(
            TimelineItemsFactoryConfig(
                computeReadReceipts = false,
                computeReactions = false,
            )
        )
        val timeline = (0 until 40).map { index ->
            MatrixTimelineItem.Event(
                uniqueId = UniqueId("event-$index"),
                event = anEventTimelineItem(
                    eventId = EventId("\$event-$index"),
                    timestamp = index.toLong(),
                    content = aMessageContent(body = "message-$index"),
                ),
            )
        }

        factory.timelineItems.test {
            factory.replaceWith(timeline, emptyList())
            val visible = awaitItem().events()
            val expanded = awaitItem().events()

            assertThat(visible).hasSize(30)
            assertThat(expanded).hasSize(40)
            val expandedByDisplayId = expanded.associateBy { it.displayId }
            visible.forEach { visibleEvent ->
                assertThat(expandedByDisplayId.getValue(visibleEvent.displayId)).isSameInstanceAs(visibleEvent)
            }
        }
    }
}

private fun List<TimelineItem>.events(): List<TimelineItem.Event> = filterIsInstance<TimelineItem.Event>()
