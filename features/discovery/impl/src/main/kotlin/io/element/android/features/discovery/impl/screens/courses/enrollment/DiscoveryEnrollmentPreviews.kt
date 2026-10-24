/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import java.time.LocalDate

/** Preview fixtures only. Never used by a repository, a controller or a production fallback. */
private fun previewEnrollmentOptions() = EnrollmentOptions(
    1, 10, "preview", "周末亲子共读", EnrollmentMode.PARENT_CHILD,
    listOf(EnrollmentPerson(1, "家长", EnrollmentRelation.SELF, "本人", true, true),
        EnrollmentPerson(2, "小禾", EnrollmentRelation.CHILD, "一年级", true, true)),
    (1..3).map { LocalDate.of(2026, 11, it) }, EnrollmentRoom.entries.toSet(),
    listOf(EnrollmentMeal("lunch", LocalDate.of(2026, 11, 1), "午餐", 2000, EnrollmentMealKind.LUNCH)),
    setOf(EnrollmentTravel.TRAIN, EnrollmentTravel.FLIGHT), "书院", true,
)

private class EnrollmentPreviewStates : PreviewParameterProvider<DiscoveryEnrollmentState> {
    private val options = previewEnrollmentOptions()
    private val draft = options.initialDraft().copy(personIds = setOf(2), guardian = true)
    override val values = sequenceOf(
        DiscoveryEnrollmentState(),
        DiscoveryEnrollmentState(phase = EnrollmentPhase.FAILED, notice = EnrollmentNotice.LOAD_FAILED),
        DiscoveryEnrollmentState(phase = EnrollmentPhase.EDITING, options = options, draft = draft),
        DiscoveryEnrollmentState(phase = EnrollmentPhase.QUOTED, options = options, draft = draft,
            quote = EnrollmentQuote("preview", draft.request(options), listOf(EnrollmentFeeLine("课程报名费", 10000)), 10000, Long.MAX_VALUE)),
        DiscoveryEnrollmentState(phase = EnrollmentPhase.UNCERTAIN, notice = EnrollmentNotice.CHECK_RESULT),
    )
}

@PreviewsDayNight
@Composable
private fun EnrollmentPreview(@PreviewParameter(EnrollmentPreviewStates::class) state: DiscoveryEnrollmentState) = ElementPreview {
    DiscoveryEnrollmentView(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@PreviewsDayNight
@Composable
private fun EnrollmentPeoplePreview() = ElementPreview {
    DiscoveryEnrollmentPersonView(previewEnrollmentOptions(), setOf(2), {}, {}, {}, {})
}

private class EnrollmentReceiptPreviewStates : PreviewParameterProvider<EnrollmentReceiptStatus> {
    override val values = EnrollmentReceiptStatus.entries.asSequence()
}

@PreviewsDayNight
@Composable
private fun EnrollmentResultPreview(@PreviewParameter(EnrollmentReceiptPreviewStates::class) status: EnrollmentReceiptStatus) = ElementPreview {
    DiscoveryEnrollmentResultView(EnrollmentReceipt(1, 10, "E-PREVIEW", "O-PREVIEW", "周末亲子共读", status, 10000,
        listOf("家长", "小禾")), {}, {}, {}, {})
}
