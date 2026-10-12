/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.poll.impl.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.poll.impl.R
import io.element.android.features.poll.api.pollcontent.InteractionFormField
import io.element.android.features.poll.api.pollcontent.InteractionFormSection
import io.element.android.features.poll.api.pollcontent.InteractionNumberBadge
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.persistentListOf
import io.element.android.features.poll.api.pollcontent.InteractionSubmitButton as Button
import io.element.android.features.poll.api.R as PollR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePollView(state: CreatePollState, modifier: Modifier = Modifier) {
    val busy = state.saveAction is AsyncAction.Loading
    val navBack = { if (!busy) state.eventSink(CreatePollEvent.ConfirmNavBack) }
    BackHandler(onBack = navBack)
    if (state.showBackConfirmation) {
        ConfirmationDialog(
            title = stringResource(PollR.string.screen_interaction_discard_title),
            content = stringResource(PollR.string.screen_interaction_discard_content),
            submitText = stringResource(PollR.string.screen_interaction_discard),
            cancelText = stringResource(PollR.string.screen_interaction_continue),
            destructiveSubmit = true,
            onSubmitClick = { state.eventSink(CreatePollEvent.NavBack) },
            onDismiss = { state.eventSink(CreatePollEvent.HideConfirmation) },
        )
    }
    if (state.showDeleteConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.screen_edit_poll_delete_confirmation_title),
            content = stringResource(R.string.screen_edit_poll_delete_confirmation),
            onSubmitClick = { state.eventSink(CreatePollEvent.Delete(confirmed = true)) },
            onDismiss = { state.eventSink(CreatePollEvent.HideConfirmation) },
        )
    }
    val lastAnswerFocus = remember { FocusRequester() }
    var previousAnswerCount by remember { mutableIntStateOf(state.answers.size) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.answers.size) {
        val addedAnswer = state.answers.size > previousAnswerCount
        previousAnswerCount = state.answers.size
        if (addedAnswer) {
            listState.animateScrollToItem(1)
            lastAnswerFocus.requestFocus()
        }
    }
    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = ElementTheme.colors.bgSubtleSecondaryLevel0,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (state.mode == CreatePollState.Mode.New) R.string.screen_poll_create_heading else R.string.screen_edit_poll_title),
                        Modifier.semantics { heading() },
                        style = ElementTheme.typography.fontHeadingSmMedium.copy(fontSize = 17.sp),
                    )
                },
                navigationIcon = { BackButton(enabled = !busy, onClick = navBack) },
            )
        },
        bottomBar = {
            Column(Modifier.background(ElementTheme.colors.bgCanvasDefault).navigationBarsPadding().padding(16.dp)) {
                if (state.saveAction is AsyncAction.Failure) {
                    Text(
                        stringResource(R.string.screen_poll_publish_failed),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textCriticalPrimary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    text = stringResource(if (state.mode == CreatePollState.Mode.New) R.string.screen_poll_publish else CommonStrings.action_done),
                    enabled = state.canSave && !busy,
                    showProgress = busy,
                    onClick = { state.eventSink(CreatePollEvent.Save) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                InteractionFormSection(stringResource(R.string.screen_poll_topic)) {
                    InteractionFormField(
                        state.question,
                        stringResource(R.string.screen_create_poll_question_hint),
                        onChange = { state.eventSink(CreatePollEvent.SetQuestion(it)) },
                        enabled = !busy,
                    )
                }
            }
            item {
                InteractionFormSection(stringResource(R.string.screen_poll_options)) {
                    state.answers.forEachIndexed { index, answer ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            InteractionNumberBadge(index + 1)
                            InteractionFormField(
                                value = answer.text,
                                hint = stringResource(R.string.screen_create_poll_answer_hint, index + 1),
                                onChange = { state.eventSink(CreatePollEvent.SetAnswer(index, it)) },
                                enabled = !busy,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                                    .then(if (index == state.answers.lastIndex) Modifier.focusRequester(lastAnswerFocus) else Modifier),
                            )
                            if (answer.canDelete) {
                                IconButton(enabled = !busy, onClick = { state.eventSink(CreatePollEvent.RemoveAnswer(index)) }) {
                                    Icon(CompoundIcons.Minus(), contentDescription = stringResource(R.string.screen_create_poll_delete_option_a11y, answer.text))
                                }
                            }
                        }
                        if (index < state.answers.lastIndex || state.canAddAnswer) HorizontalDivider(color = ElementTheme.colors.borderDisabled)
                    }
                    if (state.canAddAnswer) {
                        TextButton(
                            stringResource(R.string.screen_create_poll_add_option_btn),
                            enabled = !busy,
                            onClick = { state.eventSink(CreatePollEvent.AddAnswer) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                InteractionFormSection(stringResource(R.string.screen_poll_settings)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.screen_poll_hide_results), Modifier.weight(1f), style = ElementTheme.typography.fontBodyMdRegular)
                        Switch(
                            checked = state.pollKind == PollKind.Undisclosed,
                            enabled = !busy,
                            onCheckedChange = { state.eventSink(CreatePollEvent.SetPollKind(if (it) PollKind.Undisclosed else PollKind.Disclosed)) },
                        )
                    }
                    Text(
                        stringResource(R.string.screen_poll_hide_results_hint),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
                if (state.canDelete) {
                    TextButton(
                        stringResource(CommonStrings.action_delete_poll),
                        enabled = !busy,
                        onClick = { state.eventSink(CreatePollEvent.Delete(confirmed = false)) },
                    )
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun CreatePollViewPreview(@PreviewParameter(CreatePollStateProvider::class) state: CreatePollState) = ElementPreview {
    CreatePollView(state)
}

@PreviewsDayNight
@Composable
internal fun CreatePollFormReviewPreview() = ElementPreview {
    CreatePollView(
        CreatePollStateProvider().values.first().copy(
            question = "周六聚餐，大家想吃什么？",
            answers = persistentListOf(Answer("火锅", true), Answer("烧烤", true), Answer("家常菜", true)),
            canSave = true,
        )
    )
}
