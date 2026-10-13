/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.ReadReceiptData
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemReadReceipts
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.getDisplayName
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class PrivateProfileProjectionTest {
    private var shieldReads = 0
    private fun source() = aTimelineItemEvent().copy(messageShieldProvider = { shieldReads++; null })

    @Test fun `unrelated timeline updates do not reread unchanged native shields`() {
        val source = source()
        repeat(100) { assertThat(source.withPrivateProfiles(emptyMap())).isSameInstanceAs(source) }
        assertThat(shieldReads).isEqualTo(1)
    }

    @Test fun `unrelated private profile changes preserve this source row`() {
        val source = source()
        val other = UserId("@other:example.org")
        assertThat(source.withPrivateProfiles(mapOf(other to FriendPrivateProfile(other, remarkName = "Other"))))
            .isSameInstanceAs(source)
        assertThat(shieldReads).isEqualTo(1)
    }

    @Test fun `changed private remark still updates sender and avatar and reads current shield`() {
        val source = source()
        val result = source.withPrivateProfiles(mapOf(source.senderId to FriendPrivateProfile(source.senderId, remarkName = "Changed")))
            as TimelineItem.Event
        assertThat(result.senderProfile.getDisplayName()).isEqualTo("Changed")
        assertThat(result.senderAvatar.name).isEqualTo("Changed")
        assertThat(shieldReads).isEqualTo(2)
        assertThat(result.withPrivateProfiles(mapOf(source.senderId to FriendPrivateProfile(source.senderId, remarkName = "Changed"))))
            .isSameInstanceAs(result)
        assertThat(shieldReads).isEqualTo(2)
    }

    @Test fun `removing a remark uses the current unmodified source instead of an old private projection`() {
        val source = source()
        source.withPrivateProfiles(mapOf(source.senderId to FriendPrivateProfile(source.senderId, remarkName = "Removed")))
        assertThat(source.withPrivateProfiles(emptyMap())).isSameInstanceAs(source)
    }

    @Test fun `new failed source wins over previous projection and observes its shield`() {
        val source = source()
        source.withPrivateProfiles(emptyMap())
        val failed = source.copy(localSendState = LocalEventSendState.Failed.Unknown("changed"))
        assertThat(failed.withPrivateProfiles(emptyMap())).isSameInstanceAs(failed)
        assertThat(shieldReads).isEqualTo(2)
    }

    @Test fun `unchanged grouped events and receipts preserve the group`() {
        val source = source()
        val receipt = ReadReceiptData(source.senderAvatar, "now")
        val group = TimelineItem.GroupedEvents(UniqueId("group"), persistentListOf(source), persistentListOf(receipt))
        assertThat(group.withPrivateProfiles(emptyMap())).isSameInstanceAs(group)
        assertThat(shieldReads).isEqualTo(1)
    }

    @Test fun `receipt-only private name updates are not lost`() {
        val source = source()
        val reader = UserId("@reader:example.org")
        val receipt = ReadReceiptData(source.senderAvatar.copy(id = reader.value, name = "Reader"), "now")
        val row = source.copy(readReceiptState = TimelineItemReadReceipts(persistentListOf(receipt)))
        val result = row.withPrivateProfiles(mapOf(reader to FriendPrivateProfile(reader, remarkName = "Private reader"))) as TimelineItem.Event
        assertThat(result.readReceiptState.receipts.single().avatarData.name).isEqualTo("Private reader")
        assertThat(result.senderProfile).isEqualTo(row.senderProfile)
    }
}
