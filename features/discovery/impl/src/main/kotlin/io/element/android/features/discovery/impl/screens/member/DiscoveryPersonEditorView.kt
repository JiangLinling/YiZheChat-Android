/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import java.time.LocalDate

@Composable
internal fun DiscoveryPersonEditorView(
    state: PersonEditorState,
    today: LocalDate,
    onEdit: ((MemberPersonDraft) -> MemberPersonDraft) -> Unit,
    onSave: (Boolean) -> Unit,
    onRecover: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onAvatar: (() -> Unit)?,
    modifier: Modifier = Modifier,
    allowUse: Boolean = true,
    onFinish: (Boolean) -> Unit = { onRecover() },
) {
    val child = state.target.kind != PersonEditKind.SELF
    val editable = state.phase == PersonEditPhase.EDITING
    val draft = state.draft
    Scaffold(modifier.fillMaxSize().safeDrawingPadding().imePadding(), containerColor = ElementTheme.colors.bgCanvasDefault,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary).padding(16.dp)) {
                when (state.phase) {
                    PersonEditPhase.EDITING -> {
                        Button(onClick = { onSave(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_discovery_person_save))
                        }
                    }
                    PersonEditPhase.SAVING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.screen_discovery_person_saving))
                    }
                    PersonEditPhase.SAVED -> {
                        Button(onClick = { onFinish(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_discovery_person_done))
                        }
                        if (child && allowUse) TextButton(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_discovery_person_use_saved))
                        }
                    }
                    PersonEditPhase.UNCERTAIN, PersonEditPhase.SAVED_REFRESH -> Button(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_enrollment_check))
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
                item { Text(stringResource(when (state.target.kind) {
                    PersonEditKind.SELF -> R.string.screen_discovery_member_edit_profile
                    PersonEditKind.CHILD -> R.string.screen_discovery_person_edit_child
                    PersonEditKind.NEW_CHILD -> R.string.screen_discovery_enrollment_add_child
                }), style = ElementTheme.typography.fontHeadingLgBold) }
                if (child) item { Text(stringResource(R.string.screen_discovery_person_child_help), color = ElementTheme.colors.textSecondary) }
                if (state.phase == PersonEditPhase.LOADING) item { CircularProgressIndicator() }
                state.notice?.let { notice -> item {
                    Text(stringResource(when (notice) {
                        PersonEditNotice.LOAD_FAILED -> R.string.screen_discovery_person_load_failed
                        PersonEditNotice.CHECK_ORIGINAL -> R.string.screen_discovery_person_check_original
                        PersonEditNotice.REJECTED -> R.string.screen_discovery_person_rejected
                        PersonEditNotice.SAVED_REFRESH_FAILED -> R.string.screen_discovery_person_saved_refresh_failed
                    }))
                    if (state.phase == PersonEditPhase.FAILED) TextButton(onClick = onRetry) { Text(stringResource(R.string.screen_discovery_retry)) }
                } }
                if (draft != null) {
                    if (onAvatar != null && state.target.kind != PersonEditKind.NEW_CHILD) item {
                        MemberAction(stringResource(R.string.screen_discovery_person_avatar), enabled = editable, onClick = onAvatar)
                    }
                    if (state.maskedPhone.isNotBlank() && !child) item {
                        Text(stringResource(R.string.screen_discovery_person_bound_phone, state.maskedPhone), color = ElementTheme.colors.textSecondary)
                    }
                    items(PersonEditField.fields(state.target.kind)) { field ->
                        val keyboard = when (field) {
                            PersonEditField.EMAIL -> KeyboardType.Email
                            PersonEditField.EMERGENCY_PHONE -> KeyboardType.Phone
                            else -> KeyboardType.Text
                        }
                        OutlinedTextField(value = field.value(draft), onValueChange = { value ->
                            if (value.length <= field.limit) onEdit { field.update(it, value) }
                        }, modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                            label = { Text(stringResource(field.label())) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
                    }
                    item {
                        Text(stringResource(R.string.screen_discovery_person_gender))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MemberGender.entries.forEach { gender -> FilterChip(selected = draft.gender == gender, enabled = editable,
                                onClick = { onEdit { it.copy(gender = gender) } }, label = { Text(stringResource(gender.label())) }) }
                        }
                    }
                    if (child) item {
                        Text(stringResource(R.string.screen_discovery_person_relation))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MemberRelation.entries.forEach { relation -> FilterChip(selected = draft.relation == relation, enabled = editable,
                                onClick = { onEdit { it.copy(relation = relation) } }, label = { Text(stringResource(relation.label())) }) }
                        }
                        Row(Modifier.fillMaxWidth().toggleable(value = draft.primaryGuardian, enabled = editable, role = Role.Checkbox,
                            onValueChange = { checked -> onEdit { it.copy(primaryGuardian = checked) } }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.primaryGuardian, onCheckedChange = null, enabled = editable)
                            Text(stringResource(R.string.screen_discovery_person_primary_guardian), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (state.showIssues) items(draft.normalized().issues(state.target, today).toList()) { issue ->
                        Text(stringResource(when (issue) {
                            PersonEditIssue.NAME -> R.string.screen_discovery_person_issue_name
                            PersonEditIssue.BIRTHDATE -> R.string.screen_discovery_person_issue_date
                            PersonEditIssue.EMAIL -> R.string.screen_discovery_person_issue_email
                            PersonEditIssue.LENGTH -> R.string.screen_discovery_person_issue_length
                        }))
                    }
                }
                if (state.phase == PersonEditPhase.SAVED) item {
                    Text(stringResource(R.string.screen_discovery_person_saved), style = ElementTheme.typography.fontHeadingMdBold)
                    state.result?.let { Text(it.person.name, style = ElementTheme.typography.fontBodyLgMedium) }
                }
            }
        }
    }
}

internal fun PersonEditField.label(): Int = when (this) {
    PersonEditField.FULL_NAME -> R.string.screen_discovery_person_name
    PersonEditField.NICKNAME -> R.string.screen_discovery_person_nickname
    PersonEditField.EMAIL -> R.string.screen_discovery_person_email
    PersonEditField.BIRTHDATE -> R.string.screen_discovery_person_birthdate
    PersonEditField.CITY -> R.string.screen_discovery_person_city
    PersonEditField.OCCUPATION -> R.string.screen_discovery_person_occupation
    PersonEditField.WECHAT -> R.string.screen_discovery_person_wechat
    PersonEditField.EMERGENCY_NAME -> R.string.screen_discovery_person_emergency_name
    PersonEditField.EMERGENCY_PHONE -> R.string.screen_discovery_person_emergency_phone
    PersonEditField.SCHOOL -> R.string.screen_discovery_person_school
    PersonEditField.GRADE -> R.string.screen_discovery_person_grade
}
internal fun MemberGender.label(): Int = when (this) {
    MemberGender.UNSET -> R.string.screen_discovery_person_gender_unset
    MemberGender.MALE -> R.string.screen_discovery_person_gender_male
    MemberGender.FEMALE -> R.string.screen_discovery_person_gender_female
    MemberGender.OTHER -> R.string.screen_discovery_person_gender_other
}
internal fun MemberRelation.label(): Int = when (this) {
    MemberRelation.FATHER -> R.string.screen_discovery_person_relation_father
    MemberRelation.MOTHER -> R.string.screen_discovery_person_relation_mother
    MemberRelation.GUARDIAN -> R.string.screen_discovery_person_relation_guardian
    MemberRelation.OTHER -> R.string.screen_discovery_person_relation_other
}
