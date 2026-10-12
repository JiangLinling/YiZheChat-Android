/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.share

import io.element.android.features.location.api.Location
import org.junit.Test
import org.junit.Assert.*

class MapCoordinatesTest {
    @Test fun `Beijing GPS converts to expected display coordinate and round trips`() {
        val gps = Location(39.908823, 116.39747)
        val display = MapCoordinates.toGcj02(gps)
        assertEquals(39.9102265, display.lat, 0.00001)
        assertEquals(116.4037136, display.lon, 0.00001)
        val restored = MapCoordinates.toWgs84(display)
        assertEquals(gps.lat, restored.lat, 0.000001)
        assertEquals(gps.lon, restored.lon, 0.000001)
    }
    @Test fun `foreign coordinates do not acquire a China offset`() {
        val paris = Location(48.8566, 2.3522)
        assertEquals(paris, MapCoordinates.toGcj02(paris))
        assertEquals(paris, MapCoordinates.toWgs84(paris))
    }
}
