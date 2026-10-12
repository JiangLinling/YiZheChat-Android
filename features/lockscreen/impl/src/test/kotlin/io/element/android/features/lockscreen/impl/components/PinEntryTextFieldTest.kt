/*
 * Copyright 2026 New Vector Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.lockscreen.impl.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.features.lockscreen.impl.pin.model.PinEntry
import io.element.android.libraries.designsystem.preview.ElementPreview
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PinEntryTextFieldTest {
    @Test
    fun `secured PIN exposes password semantics and masked editable text`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementPreview {
                PinEntryTextField(PinEntry.createEmpty(4).fillWith("87"), isSecured = true, onValueChange = {})
            }
        }
        onNode(hasSetTextAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher("editable PIN is masked") {
                it.config[SemanticsProperties.EditableText].text == "\u2022\u2022"
            })
    }

    @Test
    fun `secured input remains editable without exposing new digits`() = runAndroidComposeUiTest<ComponentActivity> {
        val entry = mutableStateOf(PinEntry.createEmpty(4))
        setContent {
            ElementPreview {
                PinEntryTextField(entry.value, isSecured = true, onValueChange = { entry.value = entry.value.fillWith(it) })
            }
        }
        onNode(hasSetTextAction()).performTextReplacement("8765")
        runOnIdle { assertEquals("8765", entry.value.toText()) }
        onNode(hasSetTextAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher("updated PIN is masked") {
                it.config[SemanticsProperties.EditableText].text == "\u2022\u2022\u2022\u2022"
            })
    }
}
