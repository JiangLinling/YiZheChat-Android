/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package ui

import base.ComposablePreviewProvider
import base.PaparazziPreviewRule
import base.ScreenshotTest
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/** Renders the production composables; these images do not prove device or delivery acceptance. */
@RunWith(TestParameterInjector::class)
class PollRelayFormReviewTest(
    @TestParameter(valuesProvider = PollRelayFormPreviewProvider::class)
    val preview: ComposablePreview<AndroidPreviewInfo>,
) {
    @get:Rule
    val paparazzi = PaparazziPreviewRule.createFor(preview, locale = "zh")

    @Test
    fun snapshot() = ScreenshotTest.runTest(paparazzi, preview, localeStr = "zh")
}

class PollRelayFormPreviewProvider : TestParameterValuesProvider() {
    override fun provideValues(context: Context): List<ComposablePreview<AndroidPreviewInfo>> =
        ComposablePreviewProvider.values.map { it.value }.filter {
            it.methodName in setOf("CreatePollFormReviewPreview", "RelayCreateDialogPreview", "RelayEditorDialogPreview", "RelayClosedDialogPreview")
        }
}
