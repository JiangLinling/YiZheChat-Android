/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.util

/** Wire limits count Unicode scalar values, as Python len does for valid Unicode. */
internal fun String.discoveryCodePointCount(): Int? {
    var index = 0
    var count = 0
    while (index < length) {
        val character = this[index++]
        if (Character.isHighSurrogate(character)) {
            if (index == length || !Character.isLowSurrogate(this[index++])) return null
        } else if (Character.isLowSurrogate(character)) return null
        count++
    }
    return count
}

internal fun String.discoveryTextFits(maximum: Int, minimum: Int = 0): Boolean =
    discoveryCodePointCount()?.let { it in minimum..maximum } == true
