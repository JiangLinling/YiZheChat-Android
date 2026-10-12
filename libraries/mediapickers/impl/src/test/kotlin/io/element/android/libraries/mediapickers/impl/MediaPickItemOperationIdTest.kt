/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import org.junit.Test

class MediaPickItemOperationIdTest {
    @Test
    fun `picked item identity satisfies lifecycle wire contract even for maximum parent length`() {
        listOf("217246de-551c-499d-bc80-15ebe0e1a841", "a".repeat(256)).forEach { parent ->
            val id = operation(parent).itemOperationId(Int.MAX_VALUE)
            assertThat(id.length).isAtLeast(16)
            assertThat(id.length).isAtMost(256)
            assertThat(id).matches("^[A-Za-z0-9_-]+$")
        }
    }

    @Test
    fun `retry and process recreation preserve identity but different parents and items do not alias`() {
        val parent = "217246de-551c-499d-bc80-15ebe0e1a841"
        val first = operation(parent).itemOperationId(0)
        assertThat(operation(parent).itemOperationId(0)).isEqualTo(first)
        assertThat(operation(parent).itemOperationId(1)).isNotEqualTo(first)
        assertThat(operation("2d21cbc4-ac88-4652-b2ab-3f3c65a9962b").itemOperationId(0)).isNotEqualTo(first)
    }

    private fun operation(id: String) = MediaPickOperation(
        operationId = id,
        source = MediaPickSource.GALLERY,
        roomIdHash = "a".repeat(64),
        createdAtEpochMillis = 1_000,
        deadlineEpochMillis = 2_000,
    )
}
