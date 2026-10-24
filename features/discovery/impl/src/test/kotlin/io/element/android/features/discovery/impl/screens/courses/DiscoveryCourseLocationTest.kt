/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class DiscoveryCourseLocationTest {
    private val base = sampleCourseCatalog().sessions.first()

    @Test
    fun `valid longitude latitude pair keeps map coordinate order`() {
        assertEquals(CourseCoordinate(latitude = 30.28, longitude = 120.15), courseCoordinate(base))
    }

    @Test
    fun `out of bounds or non coordinate links cannot launch geo intent`() {
        assertNull(courseCoordinate(base.copy(amap = "999.1,30.2", baidu = "")))
        assertNull(courseCoordinate(base.copy(amap = "https://example.test/location", baidu = "")))
    }
}
