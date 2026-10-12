/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineEntryClockTest {
    @Test
    fun `process recreation does not reset elapsed offline time`() {
        assertThat(boundedOfflineNow(1_000_000L, 100L, 8_100L, 7, 7, 1_008_000L)).isEqualTo(1_008_000L)
        assertThat(boundedOfflineNow(1_000_000L, 100L, 16_100L, 7, 7, 1_016_000L)).isEqualTo(1_016_000L)
    }

    @Test
    fun `boot change and missing boot anchor require online verification`() {
        assertThat(boundedOfflineNow(1_000_000L, 100L, 100L, 7, 8, 1_000_000L)).isNull()
        assertThat(boundedOfflineNow(1_000_000L, 100L, 100L, -1, -1, 1_000_000L)).isNull()
    }

    @Test
    fun `wall clock rollback never extends a grant`() {
        assertThat(boundedOfflineNow(1_000_000L, 100L, 500_100L, 7, 7, 1_000_000L)).isNull()
        assertThat(boundedOfflineNow(1_000_000L, 100L, 8_100L, 7, 7, 1_000_000L)).isEqualTo(1_008_000L)
    }

    @Test
    fun `elapsed rollback and arithmetic overflow reject`() {
        assertThat(boundedOfflineNow(1_000_000L, 100L, 99L, 7, 7, 1_000_000L)).isNull()
        assertThat(boundedOfflineNow(Long.MAX_VALUE, 0L, 1L, 7, 7, Long.MAX_VALUE)).isNull()
    }

    @Test
    fun `retry after honors seconds and HTTP dates`() {
        assertThat(parseRetryAfterMillis("120", 0L)).isEqualTo(120_000L)
        assertThat(parseRetryAfterMillis("Thu, 01 Jan 1970 00:02:00 GMT", 0L)).isEqualTo(120_000L)
        assertThat(parseRetryAfterMillis("invalid", 0L)).isNull()
    }
}
