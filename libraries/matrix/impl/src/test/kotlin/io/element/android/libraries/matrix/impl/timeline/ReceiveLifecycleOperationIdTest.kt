/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReceiveLifecycleOperationIdTest {
    @Test
    fun `same receive projection reuses operation id`() {
        val first = operationId(eventIdHash = "event-a")
        val recomposed = operationId(eventIdHash = "event-a")

        assertThat(recomposed).isEqualTo(first)
        assertThat(first).matches("lc13-receive-[0-9a-f]{64}")
    }

    @Test
    fun `different event or device uses a different operation id`() {
        val baseline = operationId(eventIdHash = "event-a")

        assertThat(operationId(eventIdHash = "event-b")).isNotEqualTo(baseline)
        assertThat(operationId(eventIdHash = "event-a", matrixDeviceIdHash = "device-b")).isNotEqualTo(baseline)
    }

    private fun operationId(
        eventIdHash: String,
        matrixDeviceIdHash: String = "device-a",
    ): String = receiveLifecycleOperationId(
        accountIdHash = "account-a",
        matrixDeviceIdHash = matrixDeviceIdHash,
        roomIdHash = "room-a",
        eventIdHash = eventIdHash,
    )
}
