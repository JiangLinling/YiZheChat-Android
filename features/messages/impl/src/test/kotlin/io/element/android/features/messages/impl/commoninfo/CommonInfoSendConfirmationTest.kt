/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.messagecomposer.commonInfoSendConfirmation
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class CommonInfoSendConfirmationTest {
    private val shell = MessageContent("", null, false, null, TextMessageType("", null),
        protectedMessageStatus = ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
        confirmedProtectedContentId = "common-confirmed-content")

    @Test fun `confirmed own unopened burn event counts without authorizing or revealing the body`() {
        val confirmation = commonInfoSendConfirmation(event(own = true, confirmed = true))
        assertThat(confirmation?.contentId).isEqualTo("common-confirmed-content")
        assertThat(shell.isContentProtectionAuthorized).isFalse()
        assertThat(shell.body).isEmpty()
    }

    @Test fun `an optimistic row cannot count as a successful send`() {
        assertThat(commonInfoSendConfirmation(event(own = true, confirmed = false))).isNull()
    }

    @Test fun `another sender cannot consume a pending common transaction`() {
        assertThat(commonInfoSendConfirmation(event(own = false, confirmed = true))).isNull()
    }

    private fun event(own: Boolean, confirmed: Boolean): MatrixTimelineItem.Event = mockk {
        every { event } returns mockk {
            every { isOwn } returns own
            every { content } returns shell
            every { localSendState } returns null
            every { eventId } returns if (confirmed) EventId("\$common-confirmed-event") else null
            every { timestamp } returns 100L
        }
    }
}
