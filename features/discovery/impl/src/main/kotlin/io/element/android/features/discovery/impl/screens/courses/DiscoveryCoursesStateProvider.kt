/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

class DiscoveryCoursesStateProvider : PreviewParameterProvider<DiscoveryCoursesState> {
    override val values = sequenceOf(
        DiscoveryCoursesState(eventSink = {}),
        DiscoveryCoursesState(loading = false, failed = true, eventSink = {}),
        DiscoveryCoursesState(loading = false, catalog = DiscoveryCourseCatalog(emptyList(), emptyList()), eventSink = {}),
        DiscoveryCoursesState(loading = false, catalog = sampleCourseCatalog(), eventSink = {}),
        DiscoveryCoursesState(loading = false, catalog = sampleCourseCatalog(), filter = CourseFilter.ENDED, eventSink = {}),
    )
}

internal fun sampleCourseCatalog(): DiscoveryCourseCatalog {
    val first = DiscoveryCourse(1, "公益共读课程", "公益课")
    val second = DiscoveryCourse(2, "文学经典课程", "文学课")
    fun session(id: Long, course: DiscoveryCourse, title: String, status: CourseStatus) = DiscoveryCourseSession(
        id, course, title, status, "杭州", "书院", "2026-11-01T09:00:00", "2026-11-02T17:00:00", 30,
        accommodation = true, meal = true, pickup = false, amap = "120.15,30.28", baidu = "", address = "书院", feeFen = 12345)
    return DiscoveryCourseCatalog(listOf(first, second), listOf(
        session(10, first, "周末共读 · 第一期", CourseStatus.OPEN),
        session(20, second, "经典精读 · 秋日班", CourseStatus.FULL),
        session(30, first, "往期共读回顾", CourseStatus.ENDED),
    ))
}
