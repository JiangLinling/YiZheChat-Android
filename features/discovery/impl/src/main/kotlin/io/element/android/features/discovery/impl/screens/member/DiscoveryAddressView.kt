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

@Composable
internal fun DiscoveryAddressView(
    state: AddressState, chooseForOrder: Boolean,
    onEdit: ((MemberAddressDraft) -> MemberAddressDraft) -> Unit,
    onBegin: (Long?) -> Unit, onSave: () -> Unit, onRecover: () -> Unit, onReload: () -> Unit, onBack: () -> Unit,
    onPaste: (String) -> Unit, onParse: () -> Unit, onName: (Long) -> Unit, onChoose: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = state.phase == AddressPhase.EDITING
    val names = state.book?.names.orEmpty()
    Scaffold(modifier.fillMaxSize().safeDrawingPadding().imePadding(), containerColor = ElementTheme.colors.bgCanvasDefault,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary).padding(16.dp)) {
                when (state.phase) {
                    AddressPhase.READY -> Button(onClick = { onBegin(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_address_add))
                    }
                    AddressPhase.EDITING -> Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (chooseForOrder) R.string.screen_discovery_address_save_use else R.string.screen_discovery_person_save))
                    }
                    AddressPhase.SAVING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.screen_discovery_person_saving))
                    }
                    AddressPhase.UNCERTAIN -> Button(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_enrollment_check))
                    }
                    AddressPhase.SAVED -> Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_member_refresh))
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
                item { Text(stringResource(R.string.screen_discovery_member_addresses), style = ElementTheme.typography.fontHeadingLgBold) }
                if (state.phase == AddressPhase.LOADING) item { CircularProgressIndicator() }
                if (state.phase == AddressPhase.FAILED) item {
                    Text(stringResource(R.string.screen_discovery_address_load_failed))
                    TextButton(onClick = onReload) { Text(stringResource(R.string.screen_discovery_retry)) }
                }
                if (state.phase == AddressPhase.UNCERTAIN) item { Text(stringResource(R.string.screen_discovery_person_check_original)) }
                if (state.rejected) item { Text(stringResource(R.string.screen_discovery_person_rejected)) }
                if (state.savedId != null) item { Text(stringResource(R.string.screen_discovery_person_saved)) }
                if (state.phase == AddressPhase.READY) {
                    if (state.book?.rows.isNullOrEmpty()) item { Text(stringResource(R.string.screen_discovery_address_empty)) }
                    items(state.book?.rows.orEmpty(), key = { it.id }) { row -> MemberCard {
                        Text(row.draft.name, style = ElementTheme.typography.fontHeadingMdBold)
                        Text(row.draft.phone.replace(Regex("^(\\d{3})\\d{4}(\\d{4})$"), "$1****$2"))
                        Text(listOf(row.draft.province, row.draft.city, row.draft.district, row.draft.detail).joinToString(" "))
                        if (row.draft.isDefault) Text(stringResource(R.string.screen_discovery_address_default), color = ElementTheme.colors.textSecondary)
                        MemberAction(stringResource(R.string.screen_discovery_address_edit)) { onBegin(row.id) }
                        if (chooseForOrder) MemberAction(stringResource(R.string.screen_discovery_address_use)) { onChoose(row.id) }
                    } }
                    item { TextButton(onClick = onReload) { Text(stringResource(R.string.screen_discovery_member_refresh)) } }
                }
                state.draft?.let { draft ->
                    item {
                        OutlinedTextField(state.paste, onPaste, Modifier.fillMaxWidth(), enabled = editing,
                            label = { Text(stringResource(R.string.screen_discovery_address_paste)) }, minLines = 2, maxLines = 4)
                        TextButton(onClick = onParse, enabled = editing && state.paste.isNotBlank()) { Text(stringResource(R.string.screen_discovery_address_parse)) }
                    }
                    if (names.isNotEmpty()) item {
                        Text(stringResource(R.string.screen_discovery_address_names), color = ElementTheme.colors.textSecondary)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            names.forEach { name -> TextButton(onClick = { onName(name.personId) }, enabled = editing) { Text(name.name) } }
                        }
                    }
                    item { AddressField(R.string.screen_discovery_address_name, draft.name, editing, 80) { value -> onEdit { it.copy(name = value) } } }
                    item { AddressField(R.string.screen_discovery_address_phone, draft.phone, editing, 30, KeyboardType.Phone) { value -> onEdit { it.copy(phone = value) } } }
                    item { AddressField(R.string.screen_discovery_address_province, draft.province, editing, 80) { value -> onEdit { it.copy(province = value) } } }
                    item { AddressField(R.string.screen_discovery_person_city, draft.city, editing, 80) { value -> onEdit { it.copy(city = value) } } }
                    item { AddressField(R.string.screen_discovery_address_district, draft.district, editing, 80) { value -> onEdit { it.copy(district = value) } } }
                    item { AddressField(R.string.screen_discovery_address_detail, draft.detail, editing, 300) { value -> onEdit { it.copy(detail = value) } } }
                    item { AddressField(R.string.screen_discovery_address_house, draft.houseNumber, editing, 300) { value -> onEdit { it.copy(houseNumber = value) } } }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.isDefault, { value -> onEdit { it.copy(isDefault = value) } }, enabled = editing)
                        Text(stringResource(R.string.screen_discovery_address_default))
                    } }
                    if (state.showIssues) items(draft.normalized().issues().toList()) { issue -> Text(stringResource(when (issue) {
                        AddressIssue.NAME -> R.string.screen_discovery_address_issue_name
                        AddressIssue.PHONE -> R.string.screen_discovery_address_issue_phone
                        AddressIssue.REGION -> R.string.screen_discovery_address_issue_region
                        AddressIssue.DETAIL -> R.string.screen_discovery_address_issue_detail
                        AddressIssue.LENGTH -> R.string.screen_discovery_address_issue_length
                    })) }
                }
            }
        }
    }
}

@Composable
private fun AddressField(label: Int, value: String, enabled: Boolean, limit: Int, keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= limit) change(it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(stringResource(label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
