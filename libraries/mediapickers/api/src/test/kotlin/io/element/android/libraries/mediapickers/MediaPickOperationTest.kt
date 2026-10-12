/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers

import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPickOperationTest {
    @Test
    fun `operation rejects a non hashed room subject`() {
        assertThrows(IllegalArgumentException::class.java) {
            anOperation(roomIdHash = "room-id")
        }
    }

    @Test
    fun `operation rejects a deadline that cannot outlive creation`() {
        assertThrows(IllegalArgumentException::class.java) {
            anOperation(createdAtEpochMillis = 2, deadlineEpochMillis = 2)
        }
    }

    private fun anOperation(
        roomIdHash: String = "a".repeat(64),
        createdAtEpochMillis: Long = 1,
        deadlineEpochMillis: Long = 2,
    ) = MediaPickOperation(
        operationId = "operation-1234567890",
        source = MediaPickSource.GALLERY,
        roomIdHash = roomIdHash,
        createdAtEpochMillis = createdAtEpochMillis,
        deadlineEpochMillis = deadlineEpochMillis,
    )
}
