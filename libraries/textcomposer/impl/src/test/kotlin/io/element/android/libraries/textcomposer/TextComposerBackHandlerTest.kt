/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.libraries.textcomposer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.textcomposer.model.aMarkdownTextEditorState
import io.element.android.tests.testutils.pressBackKey
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextComposerBackHandlerTest {
    @Test
    fun `empty composer Back hides IME then exits room`() = runAndroidComposeUiTest {
        var keyboardVisible by mutableStateOf(true)
        var keyboardHideCount = 0
        var roomExitCount = 0
        setContent {
            BackHandler { roomExitCount++ }
            TextComposerBackHandler(
                showCommonPanel = false,
                showEmojiPanel = false,
                isKeyboardVisible = keyboardVisible,
                onCloseCommonPanel = {},
                onCloseEmojiPanel = {},
                onHideKeyboard = { keyboardHideCount++; keyboardVisible = false },
            )
        }

        pressBackKey()
        waitForIdle()
        assertThat(keyboardHideCount).isEqualTo(1)
        assertThat(roomExitCount).isEqualTo(0)

        pressBackKey()
        assertThat(roomExitCount).isEqualTo(1)
    }

    @Test
    fun `draft remains intact when Back hides IME`() = runAndroidComposeUiTest {
        var keyboardVisible by mutableStateOf(true)
        val draft = aMarkdownTextEditorState(initialText = "unsent draft", initialFocus = true)
        var roomExitCount = 0
        setContent {
            BackHandler { roomExitCount++ }
            TextComposerBackHandler(
                showCommonPanel = false,
                showEmojiPanel = false,
                isKeyboardVisible = keyboardVisible,
                onCloseCommonPanel = {},
                onCloseEmojiPanel = {},
                onHideKeyboard = { keyboardVisible = false },
            )
        }

        pressBackKey()
        waitForIdle()
        assertThat(draft.text.value().toString()).isEqualTo("unsent draft")
        assertThat(roomExitCount).isEqualTo(0)
    }

    @Test
    fun `emoji panel Back closes panel before room`() = runAndroidComposeUiTest {
        var emojiVisible by mutableStateOf(true)
        var roomExitCount = 0
        setContent {
            BackHandler { roomExitCount++ }
            TextComposerBackHandler(
                showCommonPanel = false,
                showEmojiPanel = emojiVisible,
                isKeyboardVisible = false,
                onCloseCommonPanel = {},
                onCloseEmojiPanel = { emojiVisible = false },
                onHideKeyboard = {},
            )
        }

        pressBackKey()
        waitForIdle()
        assertThat(emojiVisible).isFalse()
        assertThat(roomExitCount).isEqualTo(0)

        pressBackKey()
        assertThat(roomExitCount).isEqualTo(1)
    }

    @Test
    fun `common panel has priority over IME and room`() = runAndroidComposeUiTest {
        var commonVisible by mutableStateOf(true)
        var keyboardVisible by mutableStateOf(true)
        var keyboardHideCount = 0
        var roomExitCount = 0
        setContent {
            BackHandler { roomExitCount++ }
            TextComposerBackHandler(
                showCommonPanel = commonVisible,
                showEmojiPanel = false,
                isKeyboardVisible = keyboardVisible,
                onCloseCommonPanel = { commonVisible = false },
                onCloseEmojiPanel = {},
                onHideKeyboard = { keyboardHideCount++; keyboardVisible = false },
            )
        }

        pressBackKey()
        waitForIdle()
        assertThat(commonVisible).isFalse()
        assertThat(keyboardHideCount).isEqualTo(0)
        assertThat(roomExitCount).isEqualTo(0)

        pressBackKey()
        waitForIdle()
        assertThat(keyboardHideCount).isEqualTo(1)
        assertThat(roomExitCount).isEqualTo(0)
    }
}
