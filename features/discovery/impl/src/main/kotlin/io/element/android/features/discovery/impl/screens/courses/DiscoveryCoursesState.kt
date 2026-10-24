/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

data class DiscoveryCourse(val id: Long, val title: String, val shortName: String) {
    val label: String get() = shortName.ifBlank {
        when {
            title.contains("公益") -> "公益课"
            title.contains("文学") -> "文学课"
            title.contains("弟子") -> "弟子课"
            else -> title.removeSuffix("课程").ifBlank { "课程" }
        }
    }
}

enum class CourseStatus { OPEN, FULL, PENDING, ENDED, CLOSED, CANCELLED }
enum class CourseFilter { ALL, OPEN, PENDING, ENDED }

data class DiscoveryCourseSession(
    val id: Long,
    val course: DiscoveryCourse,
    val title: String,
    val status: CourseStatus,
    val city: String,
    val venue: String,
    val startDate: String,
    val endDate: String,
    val quota: Int,
    val accommodation: Boolean,
    val meal: Boolean,
    val pickup: Boolean,
    val amap: String,
    val baidu: String,
    val address: String,
    val feeFen: Long,
    val enrollmentOpen: Boolean = false,
)

data class DiscoveryCourseCatalog(val courses: List<DiscoveryCourse>, val sessions: List<DiscoveryCourseSession>) {
    val current: DiscoveryCourseSession? get() = sessions.firstOrNull { it.status == CourseStatus.OPEN }
        ?: sessions.firstOrNull { it.status == CourseStatus.PENDING }
}

sealed interface DiscoveryCoursesEvent {
    data object Reload : DiscoveryCoursesEvent
    data class Filter(val value: CourseFilter) : DiscoveryCoursesEvent
    data class Course(val id: Long?) : DiscoveryCoursesEvent
    data class Search(val query: String) : DiscoveryCoursesEvent
}

data class DiscoveryCoursesState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val catalog: DiscoveryCourseCatalog? = null,
    val filter: CourseFilter = CourseFilter.ALL,
    val courseId: Long? = null,
    val query: String = "",
    val eventSink: (DiscoveryCoursesEvent) -> Unit,
) {
    val items: List<DiscoveryCourseSession> get() = catalog?.sessions.orEmpty().filter { session ->
        (courseId == null || courseId == session.course.id) &&
            (query.isBlank() || listOf(session.title, session.course.title, session.course.shortName, session.city, session.venue)
                .any { it.contains(query.trim(), ignoreCase = true) }) && when (filter) {
            CourseFilter.ALL -> true
            CourseFilter.OPEN -> session.status in setOf(CourseStatus.OPEN, CourseStatus.FULL)
            CourseFilter.PENDING -> session.status == CourseStatus.PENDING
            CourseFilter.ENDED -> session.status == CourseStatus.ENDED
        }
    }
}
