/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.poll.api.pollcontent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.poll.api.R
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList
import io.element.android.features.poll.api.pollcontent.InteractionSubmitButton as Button

@Composable
internal fun PollInteractionView(
    eventId: EventId?,
    question: String,
    answerItems: ImmutableList<PollAnswerItem>,
    pollKind: PollKind,
    isPollEditable: Boolean,
    isPollEnded: Boolean,
    isMine: Boolean,
    onSelectAnswer: (EventId, String) -> Unit,
    onEditPoll: (EventId) -> Unit,
    onEndPoll: (EventId) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSubmitAnswer: ((EventId, String, (Result<Unit>) -> Unit) -> Unit)? = null,
    onSubmitEnd: ((EventId, (Result<Unit>) -> Unit) -> Unit)? = null,
) {
    var showDetails by remember(eventId) { mutableStateOf(false) }
    var optionsExpanded by remember(eventId) { mutableStateOf(false) }
    // Draft selection never changes the authoritative result or sends an operation.
    val confirmedAnswer = answerItems.firstOrNull { it.isSelected }?.answer?.id
    var draftAnswer by remember(eventId) { mutableStateOf(confirmedAnswer) }
    var submittedAnswer by remember(eventId) { mutableStateOf<String?>(null) }
    var voteAction by remember(eventId) { mutableStateOf<AsyncAction<Unit>>(AsyncAction.Uninitialized) }
    var endAction by remember(eventId) { mutableStateOf<AsyncAction<Unit>>(AsyncAction.Uninitialized) }
    var confirmEnd by remember(eventId) { mutableStateOf(false) }
    LaunchedEffect(confirmedAnswer) {
        if (submittedAnswer == confirmedAnswer) {
            submittedAnswer = null
            voteAction = AsyncAction.Uninitialized
        }
        if (draftAnswer == null) draftAnswer = confirmedAnswer
    }
    val displayedAnswer = if (isPollEnded) confirmedAnswer else draftAnswer
    val waitingForProjection = voteAction is AsyncAction.Success && submittedAnswer != null
    val busy = voteAction is AsyncAction.Loading || endAction is AsyncAction.Loading
    val ending = endAction is AsyncAction.Loading || endAction is AsyncAction.Success
    val canVote = eventId != null && !isPollEnded && !busy && !ending && !waitingForProjection &&
        draftAnswer != null && draftAnswer != confirmedAnswer && answerItems.any { it.answer.id == draftAnswer && it.isEnabled }

    fun submitVote() {
        if (!canVote) return
        val id = eventId ?: return
        val answer = draftAnswer ?: return
        submittedAnswer = answer
        voteAction = AsyncAction.Loading
        val complete: (Result<Unit>) -> Unit = { result ->
            voteAction = result.fold({ AsyncAction.Success(Unit) }, { AsyncAction.Failure(it) })
        }
        if (onSubmitAnswer != null) {
            onSubmitAnswer(id, answer, complete)
        } else {
            onSelectAnswer(id, answer)
            complete(Result.success(Unit))
        }
    }

    @Composable
    fun Actions() {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    voteAction is AsyncAction.Failure -> stringResource(R.string.screen_poll_submit_failed)
                    busy -> stringResource(R.string.screen_poll_submitting)
                    waitingForProjection || (endAction is AsyncAction.Success && !isPollEnded) -> stringResource(R.string.screen_poll_pending)
                    canVote -> stringResource(R.string.screen_poll_selection_hint, answerItems.first { it.answer.id == draftAnswer }.answer.text)
                    confirmedAnswer != null -> stringResource(R.string.screen_poll_voted)
                    else -> stringResource(
                        if (isPollEnded || pollKind == PollKind.Disclosed) {
                            CommonStrings.common_poll_total_votes
                        } else {
                            CommonStrings.common_poll_undisclosed_text
                        },
                        answerItems.sumOf { it.votesCount },
                    )
                },
                style = ElementTheme.typography.fontBodySmRegular,
                color = if (voteAction is AsyncAction.Failure) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isPollEnded) {
                Button(
                    text = stringResource(
                        when {
                            confirmedAnswer == null -> R.string.screen_poll_vote
                            draftAnswer == confirmedAnswer -> R.string.screen_poll_voted
                            else -> R.string.screen_poll_change_vote
                        }
                    ),
                    onClick = ::submitVote,
                    enabled = canVote,
                    showProgress = voteAction is AsyncAction.Loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isMine && eventId != null && !isPollEnded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (isPollEditable) {
                        TextButton(
                        text = stringResource(CommonStrings.action_edit_poll),
                        enabled = !busy && !ending && !waitingForProjection,
                        onClick = {
                            showDetails = false
                            onEditPoll(eventId)
                        },
                    )
                    }
                    TextButton(
                        text = stringResource(CommonStrings.action_end_poll),
                        enabled = !busy && endAction !is AsyncAction.Success,
                        onClick = { confirmEnd = true },
                    )
                }
            }
            if (endAction is AsyncAction.Failure) {
                Text(
                stringResource(R.string.screen_poll_submit_failed),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textCriticalPrimary,
            )
            }
        }
    }

    @Composable
    fun Details(includeActions: Boolean = true) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InteractionFormCard {
                Text(
                    stringResource(if (isPollEnded) R.string.screen_poll_ended else R.string.screen_poll_single_choice),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textActionAccent,
                    modifier = Modifier.background(ElementTheme.colors.bgAccentRest.copy(alpha = 0.09f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(question, Modifier.padding(top = 12.dp), style = ElementTheme.typography.fontHeadingSmMedium.copy(fontSize = 17.sp, lineHeight = 25.sp))
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                answerItems.forEach { answer ->
                    val selected = displayedAnswer == answer.answer.id
                    val shape = RoundedCornerShape(10.dp)
                    PollAnswerView(
                        answerItem = answer.copy(isSelected = selected),
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) ElementTheme.colors.bgAccentRest.copy(alpha = 0.07f) else ElementTheme.colors.bgCanvasDefault)
                            .border(1.dp, if (selected) ElementTheme.colors.textActionAccent else ElementTheme.colors.borderDisabled, shape)
                            .selectable(
                                selected = selected,
                                enabled = answer.isEnabled && !busy && !ending && !waitingForProjection && !isPollEnded,
                                role = Role.RadioButton,
                                onClick = {
                                    draftAnswer = answer.answer.id
                                    voteAction = AsyncAction.Uninitialized
                                },
                            )
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }
            if (includeActions) Actions()
        }
    }

    if (compact) {
        Column(modifier.widthIn(max = 264.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.screen_poll_card_title),
                    Modifier.weight(1f),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textActionAccent
                )
                Text(
                    stringResource(if (isPollEnded) R.string.screen_poll_ended else R.string.screen_poll_open),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary
                )
            }
            Text(question, style = ElementTheme.typography.fontBodyMdMedium.copy(fontSize = 14.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (pollKind == PollKind.Disclosed) R.string.screen_poll_disclosed_hint else R.string.screen_poll_undisclosed_hint),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val visibleOptions = if (optionsExpanded) answerItems else answerItems.take(6)
                visibleOptions.forEachIndexed { index, answer ->
                    val selected = answer.isSelected
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) ElementTheme.colors.bgAccentRest.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = eventId != null) {
                                if (answer.isEnabled && !isPollEnded) draftAnswer = answer.answer.id
                                showDetails = true
                            }
                            .heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text((index + 1).toString(), Modifier.width(16.dp),
                            style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
                        Text(answer.answer.text, Modifier.weight(1f),
                            style = ElementTheme.typography.fontBodySmRegular.copy(fontSize = 13.sp, lineHeight = 20.sp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (answer.showVotes) {
                            Text(answer.votesCount.toString(), style = ElementTheme.typography.fontBodyXsRegular,
                                color = if (selected) ElementTheme.colors.textActionAccent else ElementTheme.colors.textSecondary)
                        }
                    }
                }
                if (answerItems.size > 6) {
                    TextButton(
                        text = if (optionsExpanded) stringResource(R.string.screen_poll_collapse_options)
                        else stringResource(R.string.screen_poll_more_options, answerItems.size - 6),
                        onClick = { optionsExpanded = !optionsExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            HorizontalDivider(color = ElementTheme.colors.borderDisabled)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (isPollEnded || pollKind == PollKind.Disclosed) {
                        CommonStrings.common_poll_total_votes
                    } else {
                        R.string.screen_poll_undisclosed_hint
                    }, answerItems.sumOf { it.votesCount }),
                    modifier = Modifier.weight(1f),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                )
                androidx.compose.material3.TextButton(
                    enabled = eventId != null,
                    onClick = { showDetails = true },
                ) {
                    Text(
                        text = stringResource(
                        when {
                        isPollEnded -> R.string.screen_poll_view_results
                        confirmedAnswer != null -> R.string.screen_poll_voted
                        else -> R.string.screen_poll_go_vote
                    }
                    ),
                        style = ElementTheme.typography.fontBodySmMedium.copy(fontSize = 13.sp),
                        color = ElementTheme.colors.textActionAccent,
                    )
                }
            }
        }
        if (showDetails) {
            Dialog(
            onDismissRequest = { if (!busy) showDetails = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !busy,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            ),
        ) {
            InteractionDialogSystemBars()
            Surface(Modifier.fillMaxSize(), color = ElementTheme.colors.bgSubtleSecondaryLevel0) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault).heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackButton(enabled = !busy, onClick = { showDetails = false })
                        Text(stringResource(R.string.screen_poll_card_title), style = ElementTheme.typography.fontHeadingSmMedium.copy(fontSize = 17.sp))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { Details(includeActions = false) }
                    Column(Modifier.background(ElementTheme.colors.bgCanvasDefault).padding(16.dp)) { Actions() }
                }
            }
        }
        }
    } else {
        Details()
    }

    if (confirmEnd) {
        ConfirmationDialog(
        content = stringResource(CommonStrings.common_poll_end_confirmation),
        onDismiss = { confirmEnd = false },
        onSubmitClick = {
            confirmEnd = false
            eventId?.let { id ->
                if (!busy && endAction !is AsyncAction.Success) {
                    endAction = AsyncAction.Loading
                    val complete: (Result<Unit>) -> Unit = { result -> endAction = result.fold({ AsyncAction.Success(Unit) }, { AsyncAction.Failure(it) }) }
                    if (onSubmitEnd != null) {
                        onSubmitEnd(id, complete)
                    } else {
                        onEndPoll(id)
                        complete(Result.success(Unit))
                    }
                }
            }
        },
    )
    }
}
