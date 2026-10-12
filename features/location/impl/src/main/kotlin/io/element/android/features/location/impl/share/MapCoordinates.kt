/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.share

import io.element.android.features.location.api.Location
import kotlin.math.*

/** Matrix geo: values remain WGS84; AMap's display coordinates are GCJ02. */
internal object MapCoordinates {
    fun toGcj02(point: Location): Location {
        val lat = point.lat
        val lon = point.lon
        if (lon !in 72.004..137.8347 || lat !in 0.8293..55.8271) return point
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLat += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        dLat += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        var dLon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLon += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        dLon += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        val radians = lat / 180 * PI
        val magic = 1 - 0.006693421622965943 * sin(radians).pow(2)
        dLat = dLat * 180 / ((6378245.0 * (1 - 0.006693421622965943)) / (magic * sqrt(magic)) * PI)
        dLon = dLon * 180 / (6378245.0 / sqrt(magic) * cos(radians) * PI)
        return Location(lat + dLat, lon + dLon)
    }

    fun toWgs84(point: Location): Location {
        var result = point
        repeat(5) {
            val shifted = toGcj02(result)
            result = Location(result.lat + point.lat - shifted.lat, result.lon + point.lon - shifted.lon)
        }
        return result
    }
}
