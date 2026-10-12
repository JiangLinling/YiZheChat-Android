/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.reporter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BugDescriptionTest {
    @Test
    fun `keeps the user description and bounds an oversized crash stack`() {
        val userDescription = "Xiaomi 13 feedback failure"

        val result = buildBugDescription(
            problemDescription = userDescription,
            crashCallStack = "x".repeat(600_000),
            includeCrashLogs = true,
        )

        assertThat(result).startsWith(userDescription)
        assertThat(result).hasLength(512_000)
    }

    @Test
    fun `does not append crash data when logs are disabled`() {
        val result = buildBugDescription(
            problemDescription = "Xiaomi 13 feedback failure",
            crashCallStack = "crash data",
            includeCrashLogs = false,
        )

        assertThat(result).isEqualTo("Xiaomi 13 feedback failure")
    }
}
