/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.network.interceptors

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.meta.BuildType
import io.element.android.libraries.matrix.api.tracing.LogLevel
import okhttp3.logging.HttpLoggingInterceptor.Level
import org.junit.Test

class DynamicHttpLoggingInterceptorTest {
    @Test
    fun `release builds never enable HTTP body logging`() {
        LogLevel.entries.forEach { logLevel ->
            assertThat(productionSafeHttpLoggingLevel(BuildType.RELEASE, logLevel)).isEqualTo(Level.NONE)
        }
    }

    @Test
    fun `debug builds only enable HTTP body logging for debug or trace levels`() {
        assertThat(productionSafeHttpLoggingLevel(BuildType.DEBUG, LogLevel.INFO)).isEqualTo(Level.NONE)
        assertThat(productionSafeHttpLoggingLevel(BuildType.DEBUG, LogLevel.DEBUG)).isEqualTo(Level.BODY)
        assertThat(productionSafeHttpLoggingLevel(BuildType.DEBUG, LogLevel.TRACE)).isEqualTo(Level.BODY)
    }
}
