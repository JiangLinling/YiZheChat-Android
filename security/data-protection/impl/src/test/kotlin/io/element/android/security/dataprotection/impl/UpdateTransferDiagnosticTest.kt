/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateTransferDiagnosticTest {
    @Test fun `actual CDN response facts retain cache range bytes and elapsed without raw headers`() {
        val facts = UpdateTransferDiagnostic.facts(
            "download.yizhesy.cn",
            false,
            206,
            "Hit From MemCache token=PRIVATE",
            "bytes 100-199/200",
            100,
            100,
            50
        )
        listOf(
            "authority=CDN",
            "http_status=206",
            "cache=hit",
            "range_start=100",
            "range_end=199",
            "range_total=200",
            "bytes=100",
            "duration_ms=50",
            "bytes_per_second=2000"
        ).forEach { assertThat(facts).contains(it) }
        assertThat(facts).doesNotContain("PRIVATE")
        assertThat(facts).doesNotContain("yizhesy.cn")
    }

    @Test fun `origin compatibility and fallback are distinct while unknown values never leak`() {
        assertThat(UpdateTransferDiagnostic.facts("security.yizhesy.cn", false, 200, null, null, 0, 20, 10)).contains("authority=ORIGIN_COMPAT")
        assertThat(UpdateTransferDiagnostic.facts("security.yizhesy.cn", true, 503, "miss", null, 0, 0, 10, "HTTP_RESULT_FAILED"))
            .contains("authority=ORIGIN_FALLBACK")
        val unknown = UpdateTransferDiagnostic.facts("private-user-host", false, 0, "SECRET", "private-value", 0, 0, 0, "PRIVATE_FAILURE")
        assertThat(unknown).contains("authority=UNKNOWN")
        assertThat(unknown).doesNotContain("PRIVATE")
        assertThat(unknown).doesNotContain("private")
    }
}
