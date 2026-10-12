/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.test.room.aRemoteLatestEvent
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import org.junit.Test

fun aRoomListRoomSummaryFactory(
    dateFormatter: DateFormatter = FakeDateFormatter { _, _, _ -> "Today" },
    roomLatestEventFormatter: RoomLatestEventFormatter = FakeRoomLatestEventFormatter(),
    dataProtectionPolicyProvider: FakeDataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(
        requireContentEnvelope = true,
    ),
    protectedPreviewAuthorizationGate: FakeProtectedPreviewAuthorizationGate = FakeProtectedPreviewAuthorizationGate(),
) = RoomListRoomSummaryFactory(
    dateFormatter = dateFormatter,
    roomLatestEventFormatter = roomLatestEventFormatter,
    dataProtectionPolicyProvider = dataProtectionPolicyProvider,
    protectedPreviewAuthorizationGate = protectedPreviewAuthorizationGate,
)

class RoomListRoomSummaryFactoryTest {
    @Test
    fun `strict policy hides a latest message without the content authorization marker`() {
        val summary = aRoomListRoomSummaryFactory().create(
            aRoomSummary(
                numUnreadMessages = 1,
                latestEvent = aRemoteLatestEvent(content = aMessageContent(), timestamp = 1234L),
            )
        )

        assertThat(summary.latestEvent.content()).isNull()
        assertThat(summary.timestamp).isEqualTo("Today")
        assertThat(summary.numberOfUnreadMessages).isEqualTo(1)
    }

    @Test
    fun `strict policy allows a latest message only after content authorization`() {
        val summary = aRoomListRoomSummaryFactory().create(
            aRoomSummary(
                numUnreadMessages = 1,
                latestEvent = aRemoteLatestEvent(
                    content = aMessageContent().copy(isContentProtectionAuthorized = true),
                    timestamp = 1234L,
                ),
            )
        )

        assertThat(summary.timestamp).isEqualTo("Today")
        assertThat(summary.numberOfUnreadMessages).isEqualTo(1)
    }

    @Test
    fun `encrypted event without complete authorization keeps metadata but hides preview content`() {
        val summary = aRoomListRoomSummaryFactory().create(
            aRoomSummary(
                numUnreadMessages = 4,
                numUnreadNotifications = 3,
                numUnreadMentions = 2,
                latestEvent = aRemoteLatestEvent(
                    content = UnableToDecryptContent(UnableToDecryptContent.Data.Unknown, threadInfo = null),
                    timestamp = 1234L,
                ),
            )
        )

        assertThat(summary.latestEvent.content()).isNull()
        assertThat(summary.timestamp).isEqualTo("Today")
        assertThat(summary.numberOfUnreadMessages).isEqualTo(4)
        assertThat(summary.numberOfUnreadNotifications).isEqualTo(3)
        assertThat(summary.numberOfUnreadMentions).isEqualTo(2)
    }

    @Test
    fun `strict policy formats an authorized non-message event instead of calling it a protected message`() {
        val formatter = FakeRoomLatestEventFormatter().apply {
            givenFormatResult("Started an audio call")
        }
        val summary = aRoomListRoomSummaryFactory(roomLatestEventFormatter = formatter).create(
            aRoomSummary(
                latestEvent = aRemoteLatestEvent(
                    content = CallNotifyContent(CallIntent.AUDIO, declinedBy = emptyList()),
                    timestamp = 1234L,
                ),
            )
        )

        assertThat(summary.latestEvent.content()).isEqualTo("Started an audio call")
    }
}
