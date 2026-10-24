/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import java.time.LocalDate

@Composable
internal fun DiscoveryEnrollmentView(
    state: DiscoveryEnrollmentState,
    onEdit: ((EnrollmentDraft) -> EnrollmentDraft) -> Unit,
    onQuote: () -> Unit,
    onSubmit: () -> Unit,
    onCheckResult: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onAddChild: (() -> Unit)?,
    onEditPerson: ((Long) -> Unit)?,
    onOrder: ((String) -> Unit)?,
    onCourses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingPerson by remember(state.options) { mutableStateOf(false) }
    BackHandler(enabled = pickingPerson) { pickingPerson = false }
    val options = state.options
    val draft = state.draft
    val receipt = state.receipt
    if (receipt != null) {
        DiscoveryEnrollmentResultView(receipt, onCheckResult, onOrder, onCourses, onBack, modifier,
            refreshFailed = state.notice == EnrollmentNotice.CHECK_RESULT)
        return
    }
    if (pickingPerson && options != null && draft != null && state.editable) {
        DiscoveryEnrollmentPersonView(options, draft.personIds,
            onConfirm = { ids -> onEdit { it.copy(personIds = ids) }; pickingPerson = false },
            onBack = { pickingPerson = false }, onAddChild = onAddChild, onEditPerson = onEditPerson, modifier = modifier)
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        containerColor = ElementTheme.colors.bgCanvasDefault,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.quote?.let { quote ->
                    Text(stringResource(R.string.screen_discovery_enrollment_total, formatEnrollmentAmount(quote.totalFen)),
                        style = ElementTheme.typography.fontHeadingMdBold)
                }
                when (state.phase) {
                    EnrollmentPhase.EDITING, EnrollmentPhase.QUOTED -> Button(
                        onClick = if (state.phase == EnrollmentPhase.QUOTED) onSubmit else onQuote,
                        enabled = options?.canEnroll == true, modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(if (state.phase == EnrollmentPhase.QUOTED) R.string.screen_discovery_enrollment_confirm
                        else R.string.screen_discovery_enrollment_quote)) }
                    EnrollmentPhase.UNCERTAIN -> Button(onClick = onCheckResult, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_enrollment_check))
                    }
                    EnrollmentPhase.QUOTING, EnrollmentPhase.SUBMITTING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(if (state.phase == EnrollmentPhase.SUBMITTING) R.string.screen_discovery_enrollment_submitting
                            else R.string.screen_discovery_enrollment_quoting))
                    }
                    else -> Unit
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
                item { Text(options?.title ?: stringResource(R.string.screen_discovery_enrollment_title), style = ElementTheme.typography.fontHeadingLgBold) }
                if (state.phase == EnrollmentPhase.LOADING) item { CircularProgressIndicator() }
                state.notice?.let { notice -> item {
                    Text(stringResource(state.businessReason?.enrollmentReasonLabel() ?: notice.label()), color = ElementTheme.colors.textSecondary)
                    if (state.phase == EnrollmentPhase.FAILED) TextButton(onClick = onRetry) { Text(stringResource(R.string.screen_discovery_retry)) }
                } }
                if (options != null && draft != null) {
                    if (!options.canEnroll) item {
                        Text(stringResource(options.eligibilityReason.orEmpty().enrollmentReasonLabel()), color = ElementTheme.colors.textSecondary)
                    }
                    item { EnrollmentSection(R.string.screen_discovery_enrollment_people) {
                        val participants = draft.participants(options)
                        Text(participants.joinToString("、") { it.name }.ifBlank { stringResource(R.string.screen_discovery_enrollment_choose_people) })
                        TextButton(onClick = { pickingPerson = true }, enabled = state.editable) { Text(stringResource(R.string.screen_discovery_enrollment_change_people)) }
                        if (options.mode == EnrollmentMode.PARENT_CHILD) EnrollmentToggle(
                            stringResource(R.string.screen_discovery_enrollment_guardian, options.self.name), draft.guardian, state.editable,
                        ) { checked -> onEdit { it.copy(guardian = checked) } }
                        if (participants.size > 10) Text(
                            stringResource(R.string.screen_discovery_enrollment_issue_person),
                            color = ElementTheme.colors.textCriticalPrimary,
                        )
                        participants.filter { !it.profileComplete || (draft.room == EnrollmentRoom.SHARED && !it.genderComplete) }.forEach { person ->
                            if (onEditPerson != null) TextButton(onClick = { onEditPerson(person.id) }, enabled = state.editable) {
                                Text(stringResource(R.string.screen_discovery_enrollment_complete_person, person.name))
                            }
                        }
                    } }
                    item { EnrollmentSection(R.string.screen_discovery_enrollment_dates) {
                        val dates = options.dates(draft.room)
                        val starts = dates.filter { date -> dates.lastOrNull()?.let {
                            options.endAt(it, draft.room) > options.startAt(date, draft.room)
                        } == true }
                        EnrollmentDateMenu(stringResource(R.string.screen_discovery_enrollment_start, draft.start.toString()),
                            starts, state.editable) { date ->
                            onEdit { it.copy(start = date, end = if (it.end in dates && options.endAt(it.end, it.room) > options.startAt(date, it.room)) it.end
                                else dates.first { day -> options.endAt(day, it.room) > options.startAt(date, it.room) }) }
                        }
                        EnrollmentDateMenu(stringResource(R.string.screen_discovery_enrollment_end, draft.end.toString()),
                            dates.filter { options.endAt(it, draft.room) > options.startAt(draft.start, draft.room) }, state.editable) { date ->
                            onEdit { it.copy(end = date) }
                        }
                    } }
                    item { EnrollmentSection(R.string.screen_discovery_courses_accommodation) {
                        EnrollmentRoom.entries.filter { it in options.rooms }.forEach { room ->
                            FilterChip(selected = draft.room == room, enabled = state.editable,
                                onClick = { onEdit { it.copy(room = room) } }, label = { Text(stringResource(room.label())) })
                        }
                        if (draft.room != EnrollmentRoom.NONE) Text(stringResource(R.string.screen_discovery_enrollment_stay_dates,
                            draft.start.toString(), draft.end.toString()), color = ElementTheme.colors.textSecondary)
                    } }
                    if (options.meals.isNotEmpty()) item { EnrollmentSection(R.string.screen_discovery_courses_meal) {
                        options.meals.forEach { meal ->
                            val inRange = meal.date >= draft.start && meal.date <= draft.end
                            EnrollmentToggle("${meal.date} · ${meal.title} · ${formatEnrollmentAmount(meal.priceFen)}",
                                meal.id in draft.mealIds, state.editable && (inRange || meal.id in draft.mealIds)) { selected ->
                                onEdit { it.copy(mealIds = if (selected) it.mealIds + meal.id else it.mealIds - meal.id) }
                            }
                        }
                    } }
                    if (options.pickupModes.isNotEmpty()) item { EnrollmentSection(R.string.screen_discovery_courses_pickup) {
                        EnrollmentToggle(stringResource(R.string.screen_discovery_enrollment_need_pickup), draft.pickup.enabled, state.editable) { checked ->
                            onEdit { it.copy(pickup = it.pickup.copy(enabled = checked,
                                mode = it.pickup.mode.takeIf { mode -> mode in options.pickupModes } ?: options.pickupModes.first())) }
                        }
                        if (draft.pickup.enabled) {
                            Text(stringResource(R.string.screen_discovery_enrollment_destination, options.pickupDestination))
                            EnrollmentTravel.entries.filter { it in options.pickupModes }.forEach { mode ->
                                FilterChip(selected = draft.pickup.mode == mode, enabled = state.editable,
                                    onClick = { onEdit { it.copy(pickup = it.pickup.copy(mode = mode)) } }, label = { Text(stringResource(mode.label())) })
                            }
                            EnrollmentField(R.string.screen_discovery_enrollment_pickup_date, draft.pickup.date, state.editable, 10) { value -> onEdit { it.copy(pickup = it.pickup.copy(date = value)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_pickup_time, draft.pickup.time, state.editable, 5) { value -> onEdit { it.copy(pickup = it.pickup.copy(time = value)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_origin, draft.pickup.origin, state.editable, 200) { value -> onEdit { it.copy(pickup = it.pickup.copy(origin = value, latitude = null, longitude = null)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_transport_number, draft.pickup.transportNumber, state.editable, 64) { value -> onEdit { it.copy(pickup = it.pickup.copy(transportNumber = value)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_pickup_count, draft.pickup.peopleCount, state.editable, 3, KeyboardType.Number) { value -> onEdit { it.copy(pickup = it.pickup.copy(peopleCount = value)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_luggage, draft.pickup.luggage, state.editable, 500) { value -> onEdit { it.copy(pickup = it.pickup.copy(luggage = value)) } }
                            EnrollmentField(R.string.screen_discovery_enrollment_pickup_needs, draft.pickup.needs, state.editable, 500) { value -> onEdit { it.copy(pickup = it.pickup.copy(needs = value)) } }
                        }
                    } }
                    item { EnrollmentSection(R.string.screen_discovery_enrollment_notes) {
                        EnrollmentField(R.string.screen_discovery_enrollment_diet, draft.dietNote, state.editable, 1000) { value -> onEdit { it.copy(dietNote = value) } }
                        EnrollmentField(R.string.screen_discovery_enrollment_special, draft.specialNote, state.editable, 1000) { value -> onEdit { it.copy(specialNote = value) } }
                    } }
                    if (state.showIssues) items(state.issues.toList()) { issue -> Text(stringResource(issue.label())) }
                    state.quote?.let { quote -> item { EnrollmentSection(R.string.screen_discovery_enrollment_fee_details) {
                        quote.lines.forEach { line -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(line.title, Modifier.weight(1f)); Text(formatEnrollmentAmount(line.amountFen))
                        } }
                        Text(stringResource(R.string.screen_discovery_enrollment_quote_binding), color = ElementTheme.colors.textSecondary)
                    } } }
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryEnrollmentPersonView(
    options: EnrollmentOptions,
    selectedIds: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    onAddChild: (() -> Unit)?,
    onEditPerson: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val eligible = options.people.filter { options.mode == EnrollmentMode.SINGLE || it.relation == EnrollmentRelation.CHILD }
    var selected by remember(options, selectedIds) { mutableStateOf(selectedIds.intersect(eligible.map { it.id }.toSet())) }
    Scaffold(modifier.fillMaxSize().safeDrawingPadding(), containerColor = ElementTheme.colors.bgCanvasDefault,
        bottomBar = { Button(onClick = { onConfirm(selected.toSet()) },
            enabled = selected.size in 1..10 && (options.mode == EnrollmentMode.PARENT_CHILD || selected.size == 1),
            modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.screen_discovery_enrollment_use_people, selected.size)) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
            item { Text(stringResource(R.string.screen_discovery_enrollment_choose_people), style = ElementTheme.typography.fontHeadingLgBold) }
            item { Text(stringResource(if (options.mode == EnrollmentMode.SINGLE) R.string.screen_discovery_enrollment_single_help else R.string.screen_discovery_enrollment_family_help)) }
            items(eligible, key = { it.id }) { person ->
                Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    EnrollmentToggle(person.name, person.id in selected, true) { checked ->
                        selected = if (options.mode == EnrollmentMode.SINGLE) setOf(person.id)
                        else if (checked) selected + person.id else selected - person.id
                    }
                    if (person.description.isNotBlank()) Text(person.description, color = ElementTheme.colors.textSecondary)
                    if (!person.profileComplete && onEditPerson != null) TextButton(onClick = { onEditPerson(person.id) }) {
                        Text(stringResource(R.string.screen_discovery_enrollment_complete_person, person.name))
                    }
                }
            }
            if (onAddChild != null) item { TextButton(onClick = onAddChild) { Text(stringResource(R.string.screen_discovery_enrollment_add_child)) } }
        }
    }
}

@Composable
internal fun EnrollmentSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = ElementTheme.typography.fontHeadingSmMedium)
        content()
    }
}

@Composable
private fun EnrollmentToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun EnrollmentDateMenu(label: String, dates: List<LocalDate>, enabled: Boolean, onDate: (LocalDate) -> Unit) {
    var expanded by remember(dates, enabled) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && dates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            dates.forEach { date -> DropdownMenuItem(text = { Text(date.toString()) }, onClick = { expanded = false; onDate(date) }) }
        }
    }
}

@Composable
private fun EnrollmentField(@StringRes label: Int, value: String, enabled: Boolean, limit: Int,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(limit)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = limit <= 80, maxLines = if (limit <= 80) 1 else 4)
}

internal fun EnrollmentRoom.label(): Int = when (this) {
    EnrollmentRoom.NONE -> R.string.screen_discovery_enrollment_room_none
    EnrollmentRoom.SINGLE -> R.string.screen_discovery_enrollment_room_single
    EnrollmentRoom.SHARED -> R.string.screen_discovery_enrollment_room_shared
}
internal fun EnrollmentTravel.label(): Int = when (this) {
    EnrollmentTravel.TRAIN -> R.string.screen_discovery_enrollment_train
    EnrollmentTravel.FLIGHT -> R.string.screen_discovery_enrollment_flight
    EnrollmentTravel.OTHER -> R.string.screen_discovery_enrollment_other_travel
}
internal fun EnrollmentNotice.label(): Int = when (this) {
    EnrollmentNotice.LOAD_FAILED -> R.string.screen_discovery_enrollment_load_failed
    EnrollmentNotice.QUOTE_FAILED -> R.string.screen_discovery_enrollment_quote_failed
    EnrollmentNotice.QUOTE_EXPIRED -> R.string.screen_discovery_enrollment_quote_expired
    EnrollmentNotice.REJECTED -> R.string.screen_discovery_enrollment_rejected
    EnrollmentNotice.CHECK_RESULT -> R.string.screen_discovery_enrollment_unknown
}

/** Fixed local explanations only. Never render an arbitrary server exception or internal detail. */
internal fun String.enrollmentReasonLabel(): Int = when (this) {
    "MULTIPERSON_SERVICE_UNPRICED" -> R.string.screen_discovery_enrollment_reason_group_services
    "OPTIONS_CHANGED", "QUOTE_CHANGED", "QUOTE_EXPIRED", "QUOTE_MISMATCH", "QUOTE_CONSUMED" -> R.string.screen_discovery_enrollment_reason_requote
    "ALREADY_ENROLLED" -> R.string.screen_discovery_enrollment_reason_already_enrolled
    "ENROLLMENT_FULL", "FULL" -> R.string.screen_discovery_enrollment_reason_full
    "ENROLLMENT_NOT_OPEN", "NOT_OPEN" -> R.string.screen_discovery_enrollment_reason_not_open
    "ENROLLMENT_CLOSED", "ENROLLMENT_UNPUBLISHED", "CLOSED", "UNPUBLISHED", "SESSION_UNAVAILABLE" -> R.string.screen_discovery_enrollment_reason_closed
    "ENROLLMENT_PROFILE_INCOMPLETE", "PROFILE_INCOMPLETE", "SHARED_ROOM_GENDER_REQUIRED", "PARTICIPANT_INVALID",
    "PARTICIPANT_MODE_INVALID", "INVALID_PARTICIPANT_COUNT", "GUARDIAN_WITHOUT_CHILD" -> R.string.screen_discovery_enrollment_reason_people
    "INVALID_SERVICE_DATE", "SERVICE_DATE_UNAVAILABLE", "ACCOMMODATION_DATES_UNAVAILABLE", "ACCOMMODATION_DATES_REQUIRED",
    "SERVICE_ACCOMMODATION_DATE_MISMATCH", "MEAL_DATE_OUTSIDE_SERVICE", "PICKUP_DATE_UNAVAILABLE", "PICKUP_DATE_INVALID" ->
        R.string.screen_discovery_enrollment_reason_dates
    "MEAL_OPTION_UNAVAILABLE", "PICKUP_UNAVAILABLE", "ACCOMMODATION_UNAVAILABLE", "ROOMING_GROUP_UNAVAILABLE",
    "ROOMING_GROUP_FULL", "ROOMING_GROUP_DATE_CHANGED" -> R.string.screen_discovery_enrollment_reason_services
    else -> R.string.screen_discovery_enrollment_rejected
}
internal fun EnrollmentIssue.label(): Int = when (this) {
    EnrollmentIssue.CLOSED -> R.string.screen_discovery_enrollment_issue_closed
    EnrollmentIssue.PERSON -> R.string.screen_discovery_enrollment_issue_person
    EnrollmentIssue.PROFILE -> R.string.screen_discovery_enrollment_issue_profile
    EnrollmentIssue.GENDER -> R.string.screen_discovery_enrollment_issue_gender
    EnrollmentIssue.DATES -> R.string.screen_discovery_enrollment_issue_dates
    EnrollmentIssue.ROOM -> R.string.screen_discovery_enrollment_issue_room
    EnrollmentIssue.MEALS -> R.string.screen_discovery_enrollment_issue_meals
    EnrollmentIssue.PICKUP_TIME -> R.string.screen_discovery_enrollment_issue_pickup_time
    EnrollmentIssue.PICKUP_ORIGIN -> R.string.screen_discovery_enrollment_issue_pickup_origin
    EnrollmentIssue.PICKUP_PEOPLE -> R.string.screen_discovery_enrollment_issue_pickup_people
    EnrollmentIssue.NOTES -> R.string.screen_discovery_enrollment_issue_notes
}
