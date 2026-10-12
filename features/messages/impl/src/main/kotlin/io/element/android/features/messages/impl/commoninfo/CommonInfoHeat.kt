/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import kotlin.math.pow

internal const val COMMON_USAGE_DAY_MS = 24L * 60 * 60 * 1000
internal enum class CommonInfoHeatLevel { NONE, OCCASIONAL, REGULAR, FREQUENT }
internal data class CommonInfoHeat(val score: Double, val lastUsedAt: Long) {
    val level: CommonInfoHeatLevel get() = when {
        score >= 5.5 -> CommonInfoHeatLevel.FREQUENT
        score >= 1.5 -> CommonInfoHeatLevel.REGULAR
        score > 0 -> CommonInfoHeatLevel.OCCASIONAL
        else -> CommonInfoHeatLevel.NONE
    }
}

/** One value owns both ordering and icon colour. Ignore future events and events outside the rolling month. */
internal fun commonInfoHeat(item: CommonInfoItem, now: Long): CommonInfoHeat {
    val times = item.uses.values.filter { it in (now - 30 * COMMON_USAGE_DAY_MS)..now }
    return CommonInfoHeat(
        score = times.sumOf { 2.0.pow(-(now - it).toDouble() / (7 * COMMON_USAGE_DAY_MS)) },
        lastUsedAt = times.maxOrNull() ?: 0,
    )
}
