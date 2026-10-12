/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.preferences.api.store

import java.security.MessageDigest

/** Bounded UI suppression metadata only: never stores URIs, filenames or image bytes. */
object RecentImageAcknowledgements {
    fun token(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun retain(records: Set<String>, now: Long): Set<String> = records.filterTo(mutableSetOf()) {
        val time = it.substringBefore(':').toLongOrNull()
        time != null && now - time in 0..120_000L
    }

    fun claimable(ids: List<String>, records: Set<String>): Set<String> {
        val seen = records.mapTo(mutableSetOf()) { it.substringAfter(':') }
        // Never fall back to older pictures when the newest was already handled.
        if (ids.isEmpty() || token(ids.first()) in seen) return emptySet()
        return ids.take(10).filterTo(linkedSetOf()) { token(it) !in seen }
    }

    fun acknowledge(records: Set<String>, ids: Collection<String>, now: Long): Set<String> =
        (retain(records, now) + ids.map { "$now:${token(it)}" }).sortedByDescending { it.substringBefore(':').toLong() }.take(64).toSet()
}
