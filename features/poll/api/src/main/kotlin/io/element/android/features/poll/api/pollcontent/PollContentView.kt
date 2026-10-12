/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.poll.api.pollcontent

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.poll.PollKind
import kotlinx.collections.immutable.ImmutableList

@Composable
fun PollContentView(
    state: PollContentState,
    onSelectAnswer: (pollStartId: EventId, answerId: String) -> Unit,
    onEditPoll: (pollStartId: EventId) -> Unit,
    onEndPoll: (pollStartId: EventId) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSubmitAnswer: ((EventId, String, (Result<Unit>) -> Unit) -> Unit)? = null,
    onSubmitEnd: ((EventId, (Result<Unit>) -> Unit) -> Unit)? = null,
) {
    PollContentView(
        eventId = state.eventId,
        question = state.question,
        answerItems = state.answerItems,
        pollKind = state.pollKind,
        isPollEditable = state.isPollEditable,
        isPollEnded = state.isPollEnded,
        isMine = state.isMine,
        onEditPoll = onEditPoll,
        onSelectAnswer = onSelectAnswer,
        onEndPoll = onEndPoll,
        modifier = modifier,
        compact = compact,
        onSubmitAnswer = onSubmitAnswer,
        onSubmitEnd = onSubmitEnd,
    )
}

@Composable
fun PollContentView(
    eventId: EventId?,
    question: String,
    answerItems: ImmutableList<PollAnswerItem>,
    pollKind: PollKind,
    isPollEditable: Boolean,
    isPollEnded: Boolean,
    isMine: Boolean,
    onSelectAnswer: (pollStartId: EventId, answerId: String) -> Unit,
    onEditPoll: (pollStartId: EventId) -> Unit,
    onEndPoll: (pollStartId: EventId) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSubmitAnswer: ((EventId, String, (Result<Unit>) -> Unit) -> Unit)? = null,
    onSubmitEnd: ((EventId, (Result<Unit>) -> Unit) -> Unit)? = null,
) {
    PollInteractionView(
        eventId,
        question,
        answerItems,
        pollKind,
        isPollEditable,
        isPollEnded,
        isMine,
        onSelectAnswer,
        onEditPoll,
        onEndPoll,
        modifier,
        compact,
        onSubmitAnswer,
        onSubmitEnd,
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewUndisclosedPreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(showVotes = false),
        pollKind = PollKind.Undisclosed,
        isPollEnded = false,
        isPollEditable = false,
        isMine = false,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewDisclosedPreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(),
        pollKind = PollKind.Disclosed,
        isPollEnded = false,
        isPollEditable = false,
        isMine = false,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewEndedPreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(isEnded = true),
        pollKind = PollKind.Disclosed,
        isPollEnded = true,
        isPollEditable = false,
        isMine = false,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewCreatorEditablePreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(hasVotes = false, isEnded = false),
        pollKind = PollKind.Disclosed,
        isPollEnded = false,
        isPollEditable = true,
        isMine = true,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewCreatorPreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(isEnded = false),
        pollKind = PollKind.Disclosed,
        isPollEnded = false,
        isPollEditable = false,
        isMine = true,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}

@PreviewsDayNight
@Composable
internal fun PollContentViewCreatorEndedPreview() = ElementPreview {
    PollContentView(
        eventId = EventId("\$anEventId"),
        question = "What type of food should we have at the party?",
        answerItems = aPollAnswerItemList(isEnded = true),
        pollKind = PollKind.Disclosed,
        isPollEnded = true,
        isPollEditable = false,
        isMine = true,
        onSelectAnswer = { _, _ -> },
        onEditPoll = {},
        onEndPoll = {},
    )
}
