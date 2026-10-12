/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.receipt

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.components.receipt.DeliveryIndicator.Failed
import io.element.android.features.messages.impl.timeline.components.receipt.DeliveryIndicator.Hidden
import io.element.android.features.messages.impl.timeline.components.receipt.DeliveryIndicator.Read
import io.element.android.features.messages.impl.timeline.components.receipt.DeliveryIndicator.Sending
import io.element.android.features.messages.impl.timeline.components.receipt.DeliveryIndicator.Unread
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class TimelineItemDeliveryIndicatorTest {
    @Test
    fun `secure preparation is distinct from Matrix sending`() {
        assertThat(resolveDeliveryIndicator(
            aReadReceiptViewState(sendState = LocalEventSendState.Sending.Encrypting),
            renderReadReceipts = true,
        )).isEqualTo(DeliveryIndicator.Encrypting)
        assertThat(resolveDeliveryIndicator(
            aReadReceiptViewState(isMine = false, sendState = LocalEventSendState.Sending.Encrypting),
            renderReadReceipts = true,
        )).isEqualTo(Hidden)
    }
    @Test
    fun `incoming messages never show a delivery indicator`() {
        val state = aReadReceiptViewState(
            isMine = false,
            sendState = LocalEventSendState.Failed.Unknown("failure"),
        )

        assertThat(resolveDeliveryIndicator(state, renderReadReceipts = true)).isEqualTo(Hidden)
    }

    @Test
    fun `sending and failed messages show actionable state`() {
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(sendState = LocalEventSendState.Sending.Event),
                renderReadReceipts = true,
            )
        ).isEqualTo(Sending)
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(sendState = LocalEventSendState.Failed.Unknown("failure")),
                renderReadReceipts = true,
            )
        ).isEqualTo(Failed)
    }

    @Test
    fun `latest outgoing message shows read or unread state`() {
        val receipts = persistentListOf(aReadReceiptData(1))
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(isLastOutgoingMessage = true, receipts = receipts),
                renderReadReceipts = true,
            )
        ).isEqualTo(Read)
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(isLastOutgoingMessage = true),
                renderReadReceipts = true,
            )
        ).isEqualTo(Unread)
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(isLastOutgoingMessage = false),
                renderReadReceipts = true,
            )
        ).isEqualTo(Hidden)
        assertThat(
            resolveDeliveryIndicator(
                aReadReceiptViewState(isLastOutgoingMessage = true),
                renderReadReceipts = false,
            )
        ).isEqualTo(Hidden)
    }
}
