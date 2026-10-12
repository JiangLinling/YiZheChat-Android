/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.messages.impl.timeline
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import org.junit.Test
class RoomReadReceiptPolicyTest {
    @Test fun `group public prohibition still permits private unread accounting`() {
        assertThat(roomReadReceiptType(false, false, true)).isEqualTo(ReceiptType.READ_PRIVATE)
        assertThat(roomReadReceiptType(false, false, false)).isEqualTo(ReceiptType.READ_PRIVATE)
    }
    @Test fun `public receipts require both managed permission and user preference`() {
        assertThat(roomReadReceiptType(false, true, true)).isEqualTo(ReceiptType.READ)
        assertThat(roomReadReceiptType(false, true, false)).isEqualTo(ReceiptType.READ_PRIVATE)
        assertThat(roomReadReceiptType(true, false, true)).isEqualTo(ReceiptType.READ)
        assertThat(roomReadReceiptType(true, false, false)).isEqualTo(ReceiptType.READ_PRIVATE)
    }
}
