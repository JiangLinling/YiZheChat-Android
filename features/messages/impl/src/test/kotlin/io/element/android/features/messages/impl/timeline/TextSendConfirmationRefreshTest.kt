/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class TextSendConfirmationRefreshTest {
    private val event = EventId("\$original-event")
    private val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "send", "a".repeat(64), "b".repeat(64), "c".repeat(64),
        operationId = "original-send-operation-12345")
    private val binding = TextSendConfirmationBinding(request, TransactionId("original-sdk-transaction"), SEND_TEST_INSTALLATION)
    private val committed = committedTextSend(request, event)

    @Test fun `ready signed status needs only one read`() = runTest {
        var reads = 0
        assertThat(refreshTextSendConfirmation(binding, event, { true }) { reads++; committed }).isTrue()
        assertThat(reads).isEqualTo(1)
    }

    @Test fun `early pending snapshot is refreshed without a history update`() = runTest {
        var reads = 0
        assertThat(refreshTextSendConfirmation(binding, event, { true }) {
            if (++reads == 1) committed.copy(steps = emptyList()) else committed
        }).isTrue()
        assertThat(reads).isEqualTo(2)
        assertThat(testScheduler.currentTime).isEqualTo(100L)
    }

    @Test fun `unconfirmed or mismatched identity is bounded and never confirms`() = runTest {
        var reads = 0
        assertThat(refreshTextSendConfirmation(binding, event, { true }) {
            reads++; committed.copy(operationId = "different-original-operation")
        }).isFalse()
        assertThat(reads).isEqualTo(5)
        assertThat(testScheduler.currentTime).isEqualTo(1_500L)
    }

    @Test fun `failed authority stops refresh without a success`() = runTest {
        var reads = 0
        assertThat(refreshTextSendConfirmation(binding, event, { true }) {
            reads++; committed.copy(status = LifecycleOperationStatus.BLOCKED)
        }).isFalse()
        assertThat(reads).isEqualTo(1)
    }

    @Test fun `retired or already settled echo stops all later reads`() = runTest {
        var pending = true
        var reads = 0
        assertThat(refreshTextSendConfirmation(binding, event, { pending }) {
            reads++; pending = false; committed.copy(steps = emptyList())
        }).isFalse()
        assertThat(reads).isEqualTo(1)
    }

    @Test fun `deadline cancels a suspended original status read`() = runTest {
        var cancelled = false
        assertThat(refreshTextSendConfirmation(binding, event, { true }) {
            try { delay(10_000); committed } finally { cancelled = true }
        }).isFalse()
        assertThat(cancelled).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(2_500L)
    }

    @Test fun `transport failure does not loop or invent confirmation`() = runTest {
        var reads = 0
        val result = runCatching {
            refreshTextSendConfirmation(binding, event, { true }) { reads++; throw IOException("offline") }
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(reads).isEqualTo(1)
    }
}
