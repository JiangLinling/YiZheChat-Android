/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import io.element.android.features.discovery.impl.screens.courses.enrollment.formatEnrollmentAmount
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

internal data class DiscoveryMemberActions(
    val back: () -> Unit,
    val route: (MemberRoute) -> Unit,
    val course: (Long, Long) -> Unit,
    val courses: () -> Unit,
    val personal: (DiscoveryPersonalKind) -> Unit,
    val search: () -> Unit,
    val editPerson: (Long?) -> Unit,
    val selectIdentity: (Long) -> Unit,
    val addresses: () -> Unit,
    val activities: () -> Unit,
    val consult: () -> Unit,
    val dial: (String) -> Unit,
)

@Composable
internal fun DiscoveryMemberView(state: DiscoveryMemberState, actions: DiscoveryMemberActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.route !in setOf(MemberRoute.Profile, MemberRoute.Services)) {
                item { TextButton(onClick = actions.back) { Text(stringResource(R.string.screen_discovery_back)) } }
            }
            item { Text(stringResource(state.route.title()), style = ElementTheme.typography.fontHeadingLgBold) }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.failed) item {
                Text(stringResource(R.string.screen_discovery_unavailable))
                TextButton(onClick = state.reload) { Text(stringResource(R.string.screen_discovery_retry)) }
            }
            when (val page = state.page) {
                is MemberPage.Profile -> profile(page, actions)
                is MemberPage.Services -> item { ServiceHome(page, actions) }
                is MemberPage.Identities -> identities(page, state.route == MemberRoute.Children, actions)
                is MemberPage.Orders -> {
                    item { OrderFilters(page.filter) { actions.route(MemberRoute.Orders(it)) } }
                    if (page.rows.isEmpty()) item { Text(stringResource(R.string.screen_discovery_member_no_orders)) }
                    items(page.rows, key = { it.key }) { order -> OrderCard(order) { actions.route(order.route) } }
                    if (state.moreFailed) item { Text(stringResource(R.string.screen_discovery_unavailable)) }
                    if (state.loadingMore) item { CircularProgressIndicator() }
                    else if (page.nextCursor != null) item {
                        TextButton(onClick = state.more) { Text(stringResource(R.string.screen_discovery_member_more)) }
                    }
                }
                is MemberPage.Order -> {
                    item { MemberCard {
                        Text(page.summary.title, style = ElementTheme.typography.fontHeadingMdBold)
                        Text(page.summary.status)
                        Text(formatEnrollmentAmount(page.summary.amountFen))
                        Text(page.summary.number, color = ElementTheme.colors.textSecondary)
                        Text(page.summary.person)
                        Fields(page.fields)
                    } }
                    page.course?.let { course -> item {
                        TextButton(onClick = { actions.course(course.courseId, course.sessionId) }) {
                            Text(stringResource(R.string.screen_discovery_courses_detail))
                        }
                        page.services.forEach { kind -> MemberAction(stringResource(kind.title())) { actions.route(course.service(kind)) } }
                    } }
                    item { MemberAction(stringResource(R.string.screen_discovery_member_consult), onClick = actions.consult) }
                }
                is MemberPage.Service -> {
                    item { MemberCard {
                        Text(page.courseTitle, style = ElementTheme.typography.fontHeadingMdBold)
                        Text(page.participant)
                        Text(page.status, color = ElementTheme.colors.textSecondary)
                    } }
                    items(page.groups) { group -> MemberCard {
                        Text(group.title, style = ElementTheme.typography.fontHeadingSmMedium)
                        Fields(group.fields)
                    } }
                    items(page.teachers, key = { it.id }) { teacher -> MemberCard {
                        Text(teacher.name, style = ElementTheme.typography.fontHeadingSmMedium)
                        Text(teacher.role)
                        teacher.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                            MemberAction(stringResource(R.string.screen_discovery_member_call, teacher.name)) { actions.dial(phone) }
                        }
                    } }
                    item { MemberAction(stringResource(R.string.screen_discovery_member_consult), onClick = actions.consult) }
                }
                null -> Unit
            }
            if (state.page != null) item {
                TextButton(onClick = state.reload) { Text(stringResource(R.string.screen_discovery_member_refresh)) }
            }
        }
    }
}

private fun LazyListScope.profile(page: MemberPage.Profile, actions: DiscoveryMemberActions) {
    item { MemberCard {
        Text(page.identity.name, style = ElementTheme.typography.fontHeadingLgBold)
        Text(page.identity.subtitle, color = ElementTheme.colors.textSecondary)
        MemberAction(stringResource(R.string.screen_discovery_member_identities)) { actions.route(MemberRoute.Identities) }
        MemberAction(stringResource(R.string.screen_discovery_member_edit_profile)) { actions.editPerson(page.identity.id) }
    } }
    item { Text(stringResource(R.string.screen_discovery_member_my_courses), style = ElementTheme.typography.fontHeadingMdBold) }
    if (page.courses.isEmpty()) item {
        Text(stringResource(R.string.screen_discovery_member_no_courses))
        MemberAction(stringResource(R.string.screen_discovery_courses_browse), onClick = actions.courses)
    }
    items(page.courses, key = { it.enrollmentId }) { course -> MemberCard {
        CourseSummary(course)
        MemberAction(stringResource(R.string.screen_discovery_courses_detail)) { actions.course(course.courseId, course.sessionId) }
    } }
    item { MemberCard {
        Text(stringResource(R.string.screen_discovery_member_orders), style = ElementTheme.typography.fontHeadingMdBold)
        MemberOrderFilter.entries.forEach { filter ->
            val count = page.orderCounts[filter]
            MemberAction(stringResource(filter.title()) + (count?.let { " · $it" } ?: "")) { actions.route(MemberRoute.Orders(filter)) }
        }
    } }
    if (page.learningFacts.isNotEmpty()) item { MemberCard {
        Text(stringResource(R.string.screen_discovery_member_learning), style = ElementTheme.typography.fontHeadingMdBold)
        Fields(page.learningFacts)
    } }
    item { MemberCard {
        Text(stringResource(R.string.screen_discovery_member_reading), style = ElementTheme.typography.fontHeadingMdBold)
        MemberAction(stringResource(R.string.screen_discovery_my_favorites)) { actions.personal(DiscoveryPersonalKind.FAVORITES) }
        MemberAction(stringResource(R.string.screen_discovery_my_follows)) { actions.personal(DiscoveryPersonalKind.FOLLOWS) }
        MemberAction(stringResource(R.string.screen_discovery_my_history)) { actions.personal(DiscoveryPersonalKind.HISTORY) }
        MemberAction(stringResource(R.string.screen_discovery_my_replies)) { actions.personal(DiscoveryPersonalKind.REPLIES) }
        MemberAction(stringResource(R.string.screen_discovery_search_title), onClick = actions.search)
    } }
    item { MemberCard {
        MemberAction(stringResource(R.string.screen_discovery_member_activities), onClick = actions.activities)
        MemberAction(stringResource(R.string.screen_discovery_member_addresses), onClick = actions.addresses)
        MemberAction(stringResource(R.string.screen_discovery_member_children)) { actions.route(MemberRoute.Children) }
        MemberAction(stringResource(R.string.screen_discovery_member_consult), onClick = actions.consult)
    } }
}

@Composable
private fun ServiceHome(page: MemberPage.Services, actions: DiscoveryMemberActions) {
    var selected by remember(page) { mutableStateOf(page.sessions.firstOrNull()?.course?.enrollmentId) }
    val summary = page.sessions.firstOrNull { it.course.enrollmentId == selected }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (page.sessions.isEmpty()) {
            Text(stringResource(R.string.screen_discovery_member_no_services))
            MemberAction(stringResource(R.string.screen_discovery_courses_browse), onClick = actions.courses)
        }
        if (page.sessions.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            page.sessions.forEach { item -> FilterChip(
                selected = selected == item.course.enrollmentId,
                onClick = { selected = item.course.enrollmentId },
                label = { Text("${item.course.title} · ${item.course.participant}") },
            ) }
        }
        summary?.let { selectedSummary ->
            MemberCard {
                CourseSummary(selectedSummary.course)
                if (selectedSummary.bring.isNotBlank()) Text(stringResource(R.string.screen_discovery_member_bring, selectedSummary.bring))
                if (selectedSummary.note.isNotBlank()) Text(selectedSummary.note, color = ElementTheme.colors.textSecondary)
            }
            MemberCard {
                selectedSummary.items.forEach { item ->
                    MemberAction(stringResource(item.kind.title()), enabled = item.available,
                        subtitle = item.status) { actions.route(selectedSummary.course.service(item.kind)) }
                }
            }
        }
    }
}

private fun LazyListScope.identities(page: MemberPage.Identities, childrenOnly: Boolean, actions: DiscoveryMemberActions) {
    val people = page.people.filter { !childrenOnly || it.child }
    if (people.isEmpty()) item { Text(stringResource(R.string.screen_discovery_member_no_children)) }
    items(people, key = { it.id }) { person -> MemberCard {
        Text(person.name, style = ElementTheme.typography.fontHeadingMdBold)
        Text(person.subtitle, color = ElementTheme.colors.textSecondary)
        person.completion?.let { Text(stringResource(R.string.screen_discovery_member_completion, it)) }
        if (person.id == page.personId) Text(stringResource(R.string.screen_discovery_member_current_identity))
        else MemberAction(stringResource(R.string.screen_discovery_member_use_identity)) { actions.selectIdentity(person.id) }
        MemberAction(stringResource(R.string.screen_discovery_member_edit_profile)) { actions.editPerson(person.id) }
    } }
    item { MemberAction(stringResource(R.string.screen_discovery_enrollment_add_child)) { actions.editPerson(null) } }
}

@Composable private fun CourseSummary(course: MemberCourse) {
    Text(course.title, style = ElementTheme.typography.fontHeadingMdBold)
    Text(course.participant)
    Text(course.period, color = ElementTheme.colors.textSecondary)
    Text(course.status)
    if (course.location.isNotBlank()) Text(course.location, color = ElementTheme.colors.textSecondary)
}

@Composable private fun OrderCard(order: MemberOrderSummary, open: () -> Unit) = MemberCard {
    Text(order.title, style = ElementTheme.typography.fontHeadingMdBold)
    Text(order.person)
    Text(order.status)
    Text(formatEnrollmentAmount(order.amountFen))
    MemberAction(stringResource(R.string.screen_discovery_enrollment_view_order), onClick = open)
}

@Composable private fun OrderFilters(selected: MemberOrderFilter, onSelect: (MemberOrderFilter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MemberOrderFilter.entries.forEach { filter -> FilterChip(selected = selected == filter,
            onClick = { onSelect(filter) }, label = { Text(stringResource(filter.title())) }) }
    }
}

@Composable internal fun MemberCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun Fields(fields: List<MemberField>) {
    fields.filter { it.value.isNotBlank() }.forEach { field ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, color = ElementTheme.colors.textSecondary)
            Text(field.value)
        }
    }
}

@Composable internal fun MemberAction(title: String, enabled: Boolean = true, subtitle: String = "", onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = ElementTheme.typography.fontBodyLgMedium)
            if (subtitle.isNotBlank()) Text(subtitle, color = ElementTheme.colors.textSecondary)
        }
    }
}

internal fun MemberRoute.title(): Int = when (this) {
    MemberRoute.Services -> R.string.screen_discovery_root_services
    MemberRoute.Profile -> R.string.screen_discovery_root_profile
    is MemberRoute.Orders -> R.string.screen_discovery_member_orders
    is MemberRoute.Order -> R.string.screen_discovery_member_order_detail
    MemberRoute.Identities -> R.string.screen_discovery_member_identities
    MemberRoute.Children -> R.string.screen_discovery_member_children
    MemberRoute.Addresses -> R.string.screen_discovery_member_addresses
    is MemberRoute.EditPerson -> when (kind) {
        PersonEditKind.SELF -> R.string.screen_discovery_member_edit_profile
        PersonEditKind.CHILD -> R.string.screen_discovery_person_edit_child
        PersonEditKind.NEW_CHILD -> R.string.screen_discovery_enrollment_add_child
    }
    is MemberRoute.Service -> kind.title()
}
internal fun MemberOrderFilter.title(): Int = when (this) {
    MemberOrderFilter.ALL -> R.string.screen_discovery_member_all_orders
    MemberOrderFilter.PENDING_PAYMENT -> R.string.screen_discovery_enrollment_pending_payment
    MemberOrderFilter.USABLE -> R.string.screen_discovery_member_usable
    MemberOrderFilter.SHIPPING -> R.string.screen_discovery_member_shipping
}
internal fun MemberServiceKind.title(): Int = when (this) {
    MemberServiceKind.PASS -> R.string.screen_discovery_member_pass
    MemberServiceKind.GROUP -> R.string.screen_discovery_member_group
    MemberServiceKind.MEALS -> R.string.screen_discovery_courses_meal
    MemberServiceKind.ACCOMMODATION -> R.string.screen_discovery_courses_accommodation
    MemberServiceKind.MATERIALS -> R.string.screen_discovery_member_materials
    MemberServiceKind.NOTICES -> R.string.screen_discovery_member_notices
    MemberServiceKind.PICKUP -> R.string.screen_discovery_courses_pickup
    MemberServiceKind.TEACHERS -> R.string.screen_discovery_member_teachers
    MemberServiceKind.FEEDBACK -> R.string.screen_discovery_member_feedback
    MemberServiceKind.CONSULT -> R.string.screen_discovery_member_consult
}
