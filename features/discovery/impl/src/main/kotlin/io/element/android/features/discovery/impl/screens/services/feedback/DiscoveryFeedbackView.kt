/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import io.element.android.features.discovery.impl.util.discoveryCodePointCount

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryFeedbackView(state: FeedbackState, onEdit: ((FeedbackDraft) -> FeedbackDraft) -> Unit,
    onSubmit: () -> Unit, onRecover: () -> Unit, onReload: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
    onFinish: () -> Unit = onBack, onContinueEditing: () -> Unit = onReload, onReloadConflict: () -> Unit = onReload,
    onResolveConflict: (Boolean) -> Unit = { onReload() }) {
    val editable = state.phase == FeedbackPhase.EDITING
    Scaffold(modifier.fillMaxSize().safeDrawingPadding(), containerColor = ElementTheme.colors.bgCanvasDefault,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault).imePadding().padding(16.dp)) {
                when (state.phase) {
                    FeedbackPhase.EDITING -> Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.page?.feedbackId == null) R.string.screen_discovery_feedback_submit else R.string.screen_discovery_feedback_update))
                    }
                    FeedbackPhase.UNCERTAIN -> Button(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_feedback_recover))
                    }
                    FeedbackPhase.SAVING -> CircularProgressIndicator()
                    FeedbackPhase.SAVED, FeedbackPhase.FAILED -> Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.screen_discovery_retry))
                    }
                    FeedbackPhase.READ_ONLY -> if (state.receipt != null) {
                        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_discovery_feedback_done)) }
                        if (state.page?.canSubmit == true) TextButton(onClick = onContinueEditing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_discovery_feedback_edit_again))
                        }
                    }
                    FeedbackPhase.CONFLICT -> {
                        if (state.conflict != null) {
                            Button(onClick = { onResolveConflict(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_discovery_feedback_keep_draft)) }
                            TextButton(onClick = { onResolveConflict(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_discovery_feedback_use_latest)) }
                        } else if (state.conflictReadFailed) Button(onClick = onReloadConflict, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_discovery_retry))
                        }
                    }
                    else -> Unit
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
            item { Text(stringResource(R.string.screen_discovery_feedback_title), style = ElementTheme.typography.fontHeadingLgBold) }
            state.page?.let { page -> item {
                Text(page.courseTitle, style = ElementTheme.typography.fontHeadingMdBold)
                Text(page.personName, color = ElementTheme.colors.textSecondary)
            } }
            if (state.phase == FeedbackPhase.LOADING) item { CircularProgressIndicator() }
            if (state.receipt != null) item { Text(stringResource(R.string.screen_discovery_feedback_saved)) }
            if (state.phase == FeedbackPhase.FAILED) item { Text(stringResource(R.string.screen_discovery_feedback_load_failed)) }
            if (state.phase == FeedbackPhase.UNCERTAIN) item { Text(stringResource(R.string.screen_discovery_feedback_unknown)) }
            if (state.rejected && state.phase != FeedbackPhase.CONFLICT) item { Text(stringResource(R.string.screen_discovery_feedback_rejected)) }
            if (state.phase == FeedbackPhase.UNAVAILABLE) item { Text(stringResource(R.string.screen_discovery_feedback_unavailable)) }
            if (state.phase == FeedbackPhase.CONFLICT) item {
                Text(stringResource(R.string.screen_discovery_feedback_conflict), style = ElementTheme.typography.fontHeadingSmMedium)
                state.conflict?.let { latest ->
                    Text(stringResource(R.string.screen_discovery_feedback_latest))
                    Text("${latest.draft.contentScore} / ${latest.draft.teachingScore} / ${latest.draft.serviceScore} / ${latest.draft.outcomeScore}")
                    Text(latest.draft.gain)
                    if (latest.draft.improvement.isNotBlank()) Text(latest.draft.improvement)
                }
                if (state.conflictReadFailed) Text(stringResource(R.string.screen_discovery_feedback_load_failed))
                else if (state.conflict == null) CircularProgressIndicator()
            }
            if (state.phase == FeedbackPhase.READ_ONLY && state.receipt == null) item {
                Text(stringResource(when (state.page?.availability) {
                    FeedbackAvailability.NOT_OPEN -> R.string.screen_discovery_feedback_not_open
                    FeedbackAvailability.CLOSED -> R.string.screen_discovery_feedback_closed
                    else -> R.string.screen_discovery_feedback_read_only
                }))
            }
            state.draft?.let { draft ->
                item { ScoreRow(R.string.screen_discovery_feedback_content, draft.contentScore, 1..5, editable) { value -> onEdit { it.copy(contentScore = value) } } }
                item { ScoreRow(R.string.screen_discovery_feedback_teaching, draft.teachingScore, 1..5, editable) { value -> onEdit { it.copy(teachingScore = value) } } }
                item { ScoreRow(R.string.screen_discovery_feedback_service, draft.serviceScore, 1..5, editable) { value -> onEdit { it.copy(serviceScore = value) } } }
                item { ScoreRow(R.string.screen_discovery_feedback_outcome, draft.outcomeScore, 1..5, editable) { value -> onEdit { it.copy(outcomeScore = value) } } }
                item { ScoreRow(R.string.screen_discovery_feedback_recommend, draft.recommendScore, 0..10, editable) { value -> onEdit { it.copy(recommendScore = value) } } }
                item { OutlinedTextField(draft.gain, { value -> onEdit { it.copy(gain = value) } }, Modifier.fillMaxWidth(), enabled = editable,
                    label = { Text(stringResource(R.string.screen_discovery_feedback_gain)) }, supportingText = { Text("${draft.gain.trim().discoveryCodePointCount() ?: 0}/300") },
                    minLines = 3, maxLines = 6, isError = state.showIssues && FeedbackIssue.GAIN in draft.issues()) }
                item { OutlinedTextField(draft.improvement, { value -> onEdit { it.copy(improvement = value) } }, Modifier.fillMaxWidth(), enabled = editable,
                    label = { Text(stringResource(R.string.screen_discovery_feedback_improvement)) }, supportingText = { Text("${draft.improvement.trim().discoveryCodePointCount() ?: 0}/300") },
                    minLines = 2, maxLines = 6, isError = state.showIssues && FeedbackIssue.IMPROVEMENT in draft.issues()) }
                item {
                    Text(stringResource(R.string.screen_discovery_feedback_tags))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FeedbackTag.entries.forEach { tag ->
                        FilterChip(selected = tag in draft.tags, enabled = editable, onClick = { onEdit {
                            it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
                        } }, label = { Text(stringResource(tag.label())) })
                    } }
                }
                item { Row(Modifier.fillMaxWidth().toggleable(draft.wantsContact, enabled = editable, role = Role.Checkbox,
                    onValueChange = { value -> onEdit { it.copy(wantsContact = value) } })) {
                    Checkbox(draft.wantsContact, onCheckedChange = null, enabled = editable)
                    Text(stringResource(R.string.screen_discovery_feedback_contact), Modifier.padding(12.dp))
                } }
                if (state.showIssues && draft.issues().isNotEmpty()) item { Text(stringResource(R.string.screen_discovery_feedback_incomplete)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreRow(label: Int, score: Int, values: IntRange, enabled: Boolean, onValue: (Int) -> Unit) {
    val title = stringResource(label)
    Text(title, style = ElementTheme.typography.fontBodyLgRegular)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = score == value, enabled = enabled, onClick = { onValue(value) },
            modifier = Modifier.semantics { contentDescription = "$title $value" }, label = { Text(value.toString()) }) }
    }
}

private fun FeedbackTag.label(): Int = when (this) {
    FeedbackTag.CONTENT -> R.string.screen_discovery_feedback_content
    FeedbackTag.TEACHING -> R.string.screen_discovery_feedback_teaching
    FeedbackTag.SERVICE -> R.string.screen_discovery_feedback_service
    FeedbackTag.VENUE -> R.string.screen_discovery_feedback_venue
    FeedbackTag.MEALS_STAY -> R.string.screen_discovery_feedback_meals_stay
    FeedbackTag.PICKUP -> R.string.screen_discovery_feedback_pickup
    FeedbackTag.OTHER -> R.string.screen_discovery_feedback_other
}
