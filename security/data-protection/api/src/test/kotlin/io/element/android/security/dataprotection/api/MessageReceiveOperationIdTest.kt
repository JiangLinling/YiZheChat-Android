/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageReceiveOperationIdTest {
    @Test
    fun `existing Matrix receive identity remains byte for byte compatible`() {
        assertThat(messageReceiveOperationId("account-a", "device-a", "room-a", "event-a"))
            .isEqualTo("lc13-receive-211720be4462b163fda583869c0deb421649027fc17fb7b777469c3faf36ec11")
    }

    @Test
    fun `notification cannot borrow another account device room or event identity`() {
        val original = messageReceiveOperationId("account-a", "device-a", "room-a", "event-a")
        assertThat(listOf(
            messageReceiveOperationId("account-b", "device-a", "room-a", "event-a"),
            messageReceiveOperationId("account-a", "device-b", "room-a", "event-a"),
            messageReceiveOperationId("account-a", "device-a", "room-b", "event-a"),
            messageReceiveOperationId("account-a", "device-a", "room-a", "event-b"),
        )).doesNotContain(original)
    }
}
