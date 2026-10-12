/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemDebugInfo
import io.element.android.features.messages.impl.timeline.aTimelineItemReactions
import io.element.android.features.messages.impl.timeline.model.GroupedEventsKind
import io.element.android.features.messages.impl.timeline.model.ReadReceiptData
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateEventContent
import io.element.android.features.messages.impl.timeline.model.virtual.aTimelineItemDaySeparatorModel
import io.element.android.libraries.designsystem.components.avatar.anAvatarData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.core.FakeSendHandle
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class TimelineItemGrouperTest {
    private val sut = TimelineItemGrouper()

    private val aGroupableItem = TimelineItem.Event(
        id = UniqueId("0"),
        senderId = A_USER_ID,
        senderAvatar = anAvatarData(),
        senderProfile = aProfileDetailsReady(displayName = ""),
        content = TimelineItemStateEventContent(body = "a state event"),
        reactionsState = aTimelineItemReactions(count = 0),
        readReceiptState = TimelineItemReadReceipts(emptyList<ReadReceiptData>().toImmutableList()),
        localSendState = LocalEventSendState.Sent(AN_EVENT_ID),
        isEditable = false,
        canBeRepliedTo = false,
        inReplyTo = null,
        threadInfo = null,
        origin = null,
        timelineItemDebugInfoProvider = { aTimelineItemDebugInfo() },
        messageShieldProvider = { null },
        sendHandleProvider = { FakeSendHandle() },
        forwarder = null,
        forwarderProfile = null,
    )
    private val aNonGroupableItem = aMessageEvent()
    private val aNonGroupableItemNoEvent = TimelineItem.Virtual(UniqueId("virtual"), aTimelineItemDaySeparatorModel("Today"))

    @Test
    fun `test empty`() {
        val result = sut.group(emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `test non groupables`() {
        val result = sut.group(
            listOf(
                aNonGroupableItem,
                aNonGroupableItem,
            ),
        )
        assertThat(result).isEqualTo(
            listOf(
                aNonGroupableItem,
                aNonGroupableItem,
            )
        )
    }

    @Test
    fun `test groupables and ensure reordering`() {
        val result = sut.group(
            listOf(
                aGroupableItem.copy(id = UniqueId("1")),
                aGroupableItem.copy(id = UniqueId("0")),
            ),
        )
        assertThat(result).isEqualTo(
            listOf(
                TimelineItem.GroupedEvents(
                    id = computeGroupIdWith(aGroupableItem),
                    events = listOf(
                        aGroupableItem.copy(id = UniqueId("0")),
                        aGroupableItem.copy(id = UniqueId("1")),
                    ).toImmutableList(),
                    aggregatedReadReceipts = emptyList<ReadReceiptData>().toImmutableList(),
                ),
            )
        )
    }

    @Test
    fun `test 1 groupable, not group must be created`() {
        val listsToTest = listOf(
            listOf(aGroupableItem),
            listOf(aGroupableItem, aNonGroupableItem),
            listOf(aGroupableItem, aNonGroupableItemNoEvent),
            listOf(aNonGroupableItem, aGroupableItem),
            listOf(aNonGroupableItemNoEvent, aGroupableItem),
            listOf(aNonGroupableItem, aGroupableItem, aNonGroupableItem),
            listOf(aNonGroupableItemNoEvent, aGroupableItem, aNonGroupableItemNoEvent),
            listOf(aGroupableItem, aNonGroupableItem, aGroupableItem),
            listOf(aGroupableItem, aNonGroupableItemNoEvent, aGroupableItem),
            listOf(aNonGroupableItem),
            listOf(aNonGroupableItemNoEvent),
        )
        listsToTest.forEach { listToTest ->
            val result = sut.group(listToTest)
            assertThat(result).isEqualTo(listToTest)
        }
    }

    @Test
    fun `test 3 blocks`() {
        val result = sut.group(
            listOf(
                aGroupableItem,
                aGroupableItem,
                aNonGroupableItem,
                aGroupableItem,
                aGroupableItem,
                aGroupableItem,
            ),
        )
        assertThat(result).isEqualTo(
            listOf(
                TimelineItem.GroupedEvents(
                    id = computeGroupIdWith(aGroupableItem),
                    events = listOf(
                        aGroupableItem,
                        aGroupableItem,
                    ).toImmutableList(),
                    aggregatedReadReceipts = emptyList<ReadReceiptData>().toImmutableList(),
                ),
                aNonGroupableItem,
                TimelineItem.GroupedEvents(
                    id = computeGroupIdWith(aGroupableItem),
                    events = listOf(
                        aGroupableItem,
                        aGroupableItem,
                        aGroupableItem,
                    ).toImmutableList(),
                    aggregatedReadReceipts = emptyList<ReadReceiptData>().toImmutableList(),
                )
            )
        )
    }

    @Test
    fun `when calling multiple time the method group over a growing list of groupable items, then groupId is stable`() {
        // When
        val groupableItems = mutableListOf(
            aGroupableItem.copy(id = UniqueId("1")),
            aGroupableItem.copy(id = UniqueId("2"))
        )
        val expectedGroupId = sut.group(groupableItems).first().identifier()
        groupableItems.add(0, aGroupableItem.copy(UniqueId("3")))
        groupableItems.add(2, aGroupableItem.copy(UniqueId("4")))
        groupableItems.add(aGroupableItem.copy(UniqueId("5")))
        val actualGroupId = sut.group(groupableItems).first().identifier()
        // Then
        assertThat(actualGroupId).isEqualTo(expectedGroupId)
    }

    @Test
    fun `protected images from one batch are grouped in album order`() {
        val albumId = "album1234567890"
        val input = listOf(
            imageEvent("\$3", albumId, index = 2, count = 3),
            imageEvent("\$2", albumId, index = 1, count = 3),
            imageEvent("\$1", albumId, index = 0, count = 3, caption = "album caption"),
        )

        val result = sut.group(input)

        assertThat(result).hasSize(1)
        val group = result.single() as TimelineItem.GroupedEvents
        assertThat(group.kind).isEqualTo(GroupedEventsKind.MEDIA_ALBUM)
        assertThat(group.id).isEqualTo(UniqueId("media-album:$albumId"))
        assertThat(group.events.map { (it.content as TimelineItemImageContent).mediaAlbum?.index })
            .containsExactly(0, 1, 2)
            .inOrder()
    }

    @Test
    fun `images from different batches are not grouped together`() {
        val first = imageEvent("\$1", "album1234567890", index = 0, count = 2)
        val second = imageEvent("\$2", "album0987654321", index = 1, count = 2)

        assertThat(sut.group(listOf(first, second))).containsExactly(first, second).inOrder()
    }

    private fun imageEvent(
        eventId: String,
        albumId: String,
        index: Int,
        count: Int,
        caption: String? = null,
    ) = aMessageEvent(
        eventId = EventId(eventId),
        content = aTimelineItemImageContent(caption = caption).copy(
            mediaAlbum = ProtectedMediaAlbum(
                id = albumId,
                index = index,
                count = count,
            )
        ),
    )
}
