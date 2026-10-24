/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
fun DiscoveryCoursesView(
    state: DiscoveryCoursesState,
    onSession: (courseId: Long, sessionId: Long) -> Unit,
    onLocation: (DiscoveryCourseSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Heading(R.string.screen_discovery_courses_start)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(R.string.screen_discovery_courses_path_first, R.string.screen_discovery_courses_path_second,
                    R.string.screen_discovery_courses_path_third).forEach { text ->
                    Text(stringResource(text), Modifier.weight(1f).background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(12.dp)).padding(12.dp),
                        style = ElementTheme.typography.fontBodyMdRegular)
                }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item {
            Text(stringResource(R.string.screen_discovery_unavailable))
            TextButton(onClick = { state.eventSink(DiscoveryCoursesEvent.Reload) }) { Text(stringResource(R.string.screen_discovery_retry)) }
        }
        state.catalog?.let { catalog ->
            item { Heading(R.string.screen_discovery_courses_current) }
            val current = catalog.current
            if (current == null) item { Text(stringResource(R.string.screen_discovery_courses_no_current), color = ElementTheme.colors.textSecondary) }
            else item { SessionCard(current, true, onSession, onLocation) }
            item {
                Heading(R.string.screen_discovery_courses_browse)
                OutlinedTextField(value = state.query, onValueChange = { state.eventSink(DiscoveryCoursesEvent.Search(it)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.screen_discovery_course_search_hint)) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) TextButton(onClick = { state.eventSink(DiscoveryCoursesEvent.Search("")) }) {
                            Text(stringResource(R.string.screen_discovery_clear))
                        }
                    })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseFilter.entries.forEach { filter ->
                        FilterChip(selected = state.filter == filter,
                            onClick = { state.eventSink(DiscoveryCoursesEvent.Filter(filter)) }, label = { Text(stringResource(filter.label)) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.courseId == null, onClick = { state.eventSink(DiscoveryCoursesEvent.Course(null)) },
                        label = { Text(stringResource(R.string.screen_discovery_courses_all_types)) })
                    catalog.courses.forEach { course ->
                        FilterChip(selected = state.courseId == course.id, onClick = { state.eventSink(DiscoveryCoursesEvent.Course(course.id)) },
                            label = { Text(course.label) })
                    }
                }
            }
            if (state.items.isEmpty()) item { Text(stringResource(R.string.screen_discovery_courses_empty), color = ElementTheme.colors.textSecondary) }
            items(state.items, key = { it.id }) { session -> SessionCard(session, false, onSession, onLocation) }
            item { TextButton(onClick = { state.eventSink(DiscoveryCoursesEvent.Reload) }) { Text(stringResource(R.string.screen_discovery_refresh)) } }
        }
    }
}

@Composable
private fun Heading(@StringRes label: Int) { Text(stringResource(label), style = ElementTheme.typography.fontHeadingMdBold) }

@Composable
private fun SessionCard(session: DiscoveryCourseSession, featured: Boolean, onSession: (Long, Long) -> Unit,
    onLocation: (DiscoveryCourseSession) -> Unit) {
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(session.status.label), color = ElementTheme.colors.textSecondary)
        Text(session.title, style = ElementTheme.typography.fontHeadingMdBold.copy(fontFamily = FontFamily.Serif))
        Text(session.course.label, color = ElementTheme.colors.textSecondary)
        val location = listOf(session.city, session.venue).filter(String::isNotBlank).joinToString(" · ")
            .ifBlank { stringResource(R.string.screen_discovery_courses_location_pending) }
        TextButton(onClick = { onLocation(session) }, enabled = session.amap.isNotBlank() || session.baidu.isNotBlank()) { Text(location) }
        Text(listOf(session.startDate, session.endDate).filter(String::isNotBlank).joinToString(" – ") { it.substringBefore('T').replace('-', '.') }
            .ifBlank { stringResource(R.string.screen_discovery_courses_date_pending) })
        if (featured) {
            Text(if (session.quota == 0) stringResource(R.string.screen_discovery_courses_quota_pending)
                else stringResource(R.string.screen_discovery_courses_quota, session.quota))
            val services = listOfNotNull(
                R.string.screen_discovery_courses_accommodation.takeIf { session.accommodation },
                R.string.screen_discovery_courses_meal.takeIf { session.meal },
                R.string.screen_discovery_courses_pickup.takeIf { session.pickup },
            ).map { stringResource(it) }.joinToString("、")
            Text(services.ifBlank { stringResource(R.string.screen_discovery_courses_service_pending) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (featured) TextButton(onClick = { onSession(session.course.id, session.id) }) {
                Text(stringResource(R.string.screen_discovery_courses_detail))
            }
            TextButton(onClick = { onSession(session.course.id, session.id) }) {
                Text(stringResource(if (featured) R.string.screen_discovery_courses_learn else session.status.action))
            }
        }
    }
}

private val CourseFilter.label: Int get() = when (this) {
    CourseFilter.ALL -> R.string.screen_discovery_courses_all
    CourseFilter.OPEN -> R.string.screen_discovery_courses_open
    CourseFilter.PENDING -> R.string.screen_discovery_courses_pending
    CourseFilter.ENDED -> R.string.screen_discovery_courses_ended
}
private val CourseStatus.label: Int get() = when (this) {
    CourseStatus.OPEN -> R.string.screen_discovery_courses_open
    CourseStatus.FULL -> R.string.screen_discovery_courses_full
    CourseStatus.PENDING -> R.string.screen_discovery_courses_pending
    CourseStatus.ENDED -> R.string.screen_discovery_courses_ended
    CourseStatus.CLOSED -> R.string.screen_discovery_courses_closed
    CourseStatus.CANCELLED -> R.string.screen_discovery_courses_cancelled
}
private val CourseStatus.action: Int get() = when (this) {
    CourseStatus.OPEN -> R.string.screen_discovery_courses_enroll
    CourseStatus.FULL -> R.string.screen_discovery_courses_waitlist
    CourseStatus.ENDED -> R.string.screen_discovery_courses_review
    else -> R.string.screen_discovery_courses_detail
}

@PreviewsDayNight
@Composable
private fun CoursesPreview(@PreviewParameter(DiscoveryCoursesStateProvider::class) state: DiscoveryCoursesState) = ElementPreview {
    DiscoveryCoursesView(state, { _, _ -> }, {})
}
