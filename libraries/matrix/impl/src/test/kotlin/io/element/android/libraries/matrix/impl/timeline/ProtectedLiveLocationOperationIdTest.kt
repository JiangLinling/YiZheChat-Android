/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtectedLiveLocationOperationIdTest {
    @Test
    fun `coordinate updates in one lifecycle use distinct operation ids`() {
        val first = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
            timestampMillis = 1_000L,
        )
        val second = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
            timestampMillis = 4_000L,
        )

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `retrying the same coordinate update keeps its operation id`() {
        val first = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
            timestampMillis = 1_000L,
        )
        val retry = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
            timestampMillis = 1_000L,
        )

        assertThat(retry).isEqualTo(first)
    }

    @Test
    fun `ending an old share cannot consume the first update of a new share`() {
        val oldShareEnd = protectedLiveLocationEndOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-old",
        )
        val newShareFirstUpdate = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-new",
            timestampMillis = 1_000L,
        )

        assertThat(oldShareEnd).isNotEqualTo(newShareFirstUpdate)
    }

    @Test
    fun `ending and updating one share use different operation ids`() {
        val update = protectedLiveLocationUpdateOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
            timestampMillis = 1_000L,
        )
        val end = protectedLiveLocationEndOperationId(
            lifecycleOperationId = "lc17-operation",
            aggregateId = "share-a",
        )

        assertThat(end).isNotEqualTo(update)
    }
}
