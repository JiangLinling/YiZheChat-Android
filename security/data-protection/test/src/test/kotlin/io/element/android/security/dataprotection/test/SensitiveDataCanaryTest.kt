/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveDataCanaryTest {
    @Test
    fun `factory creates a distinct searchable marker for every carrier kind`() {
        val canary = SensitiveDataCanaryFactory.create("run_20260824")

        assertThat(canary.markers.keys).containsExactlyElementsIn(SensitiveCanaryKind.entries)
        assertThat(canary.markers.values.distinct()).hasSize(SensitiveCanaryKind.entries.size)
        SensitiveCanaryKind.entries.forEach { kind ->
            assertThat(canary.marker(kind)).contains("run_20260824")
            assertThat(canary.fileName(kind)).contains(kind.name.lowercase())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `factory rejects human content as a run id`() {
        SensitiveDataCanaryFactory.create("secret message content")
    }
}
