/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

internal data class MemberPeopleState(val loading: Boolean = true, val failed: Boolean = false,
    val self: MemberPersonRecord? = null, val children: List<MemberPersonRecord> = emptyList(), val resume: PersonEditTarget? = null)

@Composable
internal fun DiscoveryPeopleView(state: MemberPeopleState, childrenOnly: Boolean, onBack: () -> Unit,
    onEdit: (PersonEditTarget) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
        item { Text(stringResource(if (childrenOnly) R.string.screen_discovery_member_children else R.string.screen_discovery_member_identities),
            style = ElementTheme.typography.fontHeadingLgBold) }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item { MemberCard {
            Text(stringResource(R.string.screen_discovery_person_load_failed))
            MemberAction(stringResource(R.string.screen_discovery_retry), onClick = onRetry)
        } }
        state.resume?.let { target -> item { MemberCard {
            MemberAction(stringResource(R.string.screen_discovery_person_resume)) { onEdit(target) }
        } } }
        val people = (if (childrenOnly) emptyList() else listOfNotNull(state.self)) + state.children
        items(people, key = { requireNotNull(it.target.userId) }) { person -> MemberCard {
            Text(person.identity.name, style = ElementTheme.typography.fontHeadingMdBold)
            if (person.maskedPhone.isNotBlank()) Text(person.maskedPhone, color = ElementTheme.colors.textSecondary)
            Text(stringResource(R.string.screen_discovery_member_completion, person.completion))
            MemberAction(stringResource(R.string.screen_discovery_member_edit_profile), enabled = state.resume == null || state.resume == person.target) {
                onEdit(person.target)
            }
        } }
        if (!state.loading && !state.failed) {
            if (childrenOnly && state.children.isEmpty()) item { Text(stringResource(R.string.screen_discovery_member_no_children)) }
            item { MemberAction(stringResource(R.string.screen_discovery_enrollment_add_child), enabled = state.resume == null) {
                onEdit(PersonEditTarget(PersonEditKind.NEW_CHILD, null))
            } }
        }
    }
    }
}
