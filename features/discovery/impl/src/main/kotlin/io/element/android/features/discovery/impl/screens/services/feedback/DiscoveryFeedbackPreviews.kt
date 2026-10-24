/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

private class FeedbackPreviewStates : PreviewParameterProvider<FeedbackState> {
    private val target = FeedbackTarget(1, 2, 3)
    private val draft = FeedbackDraft(5, 4, 5, 4, 0, "愿意把课上所学用于日常生活。", tags = setOf(FeedbackTag.CONTENT))
    private val page = FeedbackPage(target, "秋季课程", "林禾", FeedbackAvailability.OPEN, true, null, null, FeedbackDraft())
    override val values = sequenceOf(
        FeedbackState(), FeedbackState(FeedbackPhase.FAILED),
        FeedbackState(FeedbackPhase.EDITING, page, page.draft, showIssues = true),
        FeedbackState(FeedbackPhase.EDITING, page, draft),
        FeedbackState(FeedbackPhase.SAVING, page, draft),
        FeedbackState(FeedbackPhase.UNCERTAIN, page, draft),
        FeedbackState(FeedbackPhase.SAVED, receipt = FeedbackSaved(target, 4, "a".repeat(64))),
        FeedbackState(FeedbackPhase.READ_ONLY, page.copy(availability = FeedbackAvailability.CLOSED, canSubmit = false), draft),
    )
}

@PreviewsDayNight
@Composable
private fun FeedbackPreview(@PreviewParameter(FeedbackPreviewStates::class) state: FeedbackState) = ElementPreview {
    DiscoveryFeedbackView(state, {}, {}, {}, {}, {})
}
