/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Consume Back inside the composer before the room's exit handler sees it. */
@Composable
internal fun TextComposerBackHandler(
    showCommonPanel: Boolean,
    showEmojiPanel: Boolean,
    isKeyboardVisible: Boolean,
    onCloseCommonPanel: () -> Unit,
    onCloseEmojiPanel: () -> Unit,
    onHideKeyboard: () -> Unit,
) {
    BackHandler(enabled = showCommonPanel || showEmojiPanel || isKeyboardVisible) {
        when {
            showCommonPanel -> onCloseCommonPanel()
            showEmojiPanel -> onCloseEmojiPanel()
            else -> onHideKeyboard()
        }
    }
}
