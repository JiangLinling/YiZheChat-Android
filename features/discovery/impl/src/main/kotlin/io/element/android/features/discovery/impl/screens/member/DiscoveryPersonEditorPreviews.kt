/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import java.time.LocalDate

private class PersonEditorPreviewStates : PreviewParameterProvider<PersonEditorState> {
    private val self = PersonEditTarget(PersonEditKind.SELF, 1)
    private val child = PersonEditTarget(PersonEditKind.NEW_CHILD, null)
    override val values = sequenceOf(
        PersonEditorState(self),
        PersonEditorState(self, phase = PersonEditPhase.FAILED, notice = PersonEditNotice.LOAD_FAILED),
        PersonEditorState(self, phase = PersonEditPhase.EDITING, draft = MemberPersonDraft(fullName = "林禾", nickname = "小林"), maskedPhone = "138****0000"),
        PersonEditorState(child, phase = PersonEditPhase.EDITING, draft = MemberPersonDraft(), showIssues = true),
        PersonEditorState(child, phase = PersonEditPhase.SAVING, draft = MemberPersonDraft(fullName = "小禾"), useAfterSave = true),
        PersonEditorState(child, phase = PersonEditPhase.UNCERTAIN, notice = PersonEditNotice.CHECK_ORIGINAL),
        PersonEditorState(child, phase = PersonEditPhase.SAVED_REFRESH, notice = PersonEditNotice.SAVED_REFRESH_FAILED),
        PersonEditorState(child, phase = PersonEditPhase.SAVED),
    )
}

@PreviewsDayNight
@Composable
private fun PersonEditorPreview(@PreviewParameter(PersonEditorPreviewStates::class) state: PersonEditorState) = ElementPreview {
    DiscoveryPersonEditorView(state, LocalDate.of(2026, 10, 24), {}, {}, {}, {}, {}, null)
}
