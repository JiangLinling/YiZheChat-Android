/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.discovery.impl.R
import io.element.android.features.discovery.impl.screens.mall.*
import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

internal enum class ServicesPreview { LOADING, FAILED, EMPTY, COURSE, TEACHERS }
internal class ServicesPreviewProvider : PreviewParameterProvider<ServicesPreview> { override val values = ServicesPreview.entries.asSequence() }

@PreviewsDayNight
@Composable
private fun ServicesPreview(@PreviewParameter(ServicesPreviewProvider::class) state: ServicesPreview) = ElementPreview {
    MallLayout(R.string.screen_discovery_root_services, {}) {
        when (state) {
            ServicesPreview.LOADING -> item { CircularProgressIndicator() }
            ServicesPreview.FAILED -> item { MallFailure {} }
            ServicesPreview.EMPTY -> item { Text("暂无可用课程服务") }
            ServicesPreview.COURSE -> item { ServiceEnrollmentCard(ServiceEnrollment(FeedbackTarget(1, 2, 3), "原文共读", "周末班", "共读伙伴", "self", "paid",
                1792886400000, 1792893600000), {}, {}) }
            ServicesPreview.TEACHERS -> {
                item { TeacherCard(ServiceTeacher(4, "带班老师", "lead", "13800000000")) }
                item { TeacherCard(ServiceTeacher(4, "带班老师", "assistant", null)) }
            }
        }
    }
}
