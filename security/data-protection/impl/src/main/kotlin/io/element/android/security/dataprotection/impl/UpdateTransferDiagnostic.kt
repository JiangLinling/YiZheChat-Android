/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

/** Structured download facts only: never output URLs, headers verbatim, IPs, bodies or tokens. */
internal object UpdateTransferDiagnostic {
    fun facts(host: String, fallback: Boolean, status: Int, cacheHeader: String?, range: String?, offset: Long,
              bytes: Long, elapsedMillis: Long, failure: String? = null): String {
        val authority = when (host.lowercase()) {
            "download.yizhesy.cn" -> "CDN"
            "security.yizhesy.cn" -> if (fallback) "ORIGIN_FALLBACK" else "ORIGIN_COMPAT"
            else -> "UNKNOWN"
        }
        val cache = when {
            cacheHeader?.contains("miss", ignoreCase = true) == true -> "miss"
            cacheHeader?.contains("hit", ignoreCase = true) == true -> "hit"
            else -> "unavailable"
        }
        val parsed = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})").matchEntire(range.orEmpty())
        val rangeFields = parsed?.groupValues?.drop(1)?.mapNotNull(String::toLongOrNull)
            ?.takeIf { it.size == 3 }?.let { "range_start=${it[0]} range_end=${it[1]} range_total=${it[2]}" }.orEmpty()
        val duration = elapsedMillis.coerceAtLeast(0)
        val transferred = bytes.coerceAtLeast(0)
        val rate = if (duration > 0) transferred.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 / duration else 0
        val safeFailure = failure?.takeIf {
            it in setOf(
                "NETWORK_TIMEOUT",
                "NETWORK_CONNECTION_FAILED",
                "TLS_VALIDATION_FAILED",
            "HTTP_RESULT_FAILED",
                "DOWNLOAD_INTEGRITY_FAILED",
                "DOWNLOAD_CANCELLED",
                "DOWNLOAD_FAILED"
            )
        }.orEmpty()
        return "SDK_UPDATE_TRANSFER authority=$authority http_status=$status cache=$cache resume_offset=$offset bytes=$transferred " +
            "duration_ms=$duration bytes_per_second=$rate $rangeFields $safeFailure"
    }
}
