/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

private class PeoplePreviewStates : PreviewParameterProvider<MemberPeopleState> {
    private val adult = MemberPersonRecord(PersonEditTarget(PersonEditKind.SELF, 1), null, MemberPersonDraft(fullName = "林禾"), "138****0000", 80, emptyList())
    private val child = MemberPersonRecord(PersonEditTarget(PersonEditKind.CHILD, 2), 3, MemberPersonDraft(fullName = "小禾"), "", 70, emptyList())
    override val values = sequenceOf(MemberPeopleState(), MemberPeopleState(loading = false, failed = true),
        MemberPeopleState(loading = false, self = adult), MemberPeopleState(loading = false, self = adult, children = listOf(child)),
        MemberPeopleState(loading = false, self = adult, children = listOf(child), resume = PersonEditTarget(PersonEditKind.NEW_CHILD, null)))
}

@PreviewsDayNight
@Composable
private fun PeoplePreview(@PreviewParameter(PeoplePreviewStates::class) state: MemberPeopleState) = ElementPreview {
    DiscoveryPeopleView(state, childrenOnly = true, onBack = {}, onEdit = {}, onRetry = {})
}
