/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import timber.log.Timber

internal fun handleAttachmentSystemBack(dialogView: View?, previewOpen: Boolean, onBack: () -> Unit) {
    val focused = dialogView?.findFocus()
    val imeVisible = dialogView?.let { ViewCompat.getRootWindowInsets(it) }
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    // WebView delegates this check to the focused HTML editor. IME may already
    // report hidden by the time the Dialog dispatches its Back callback.
    val editorFocused = focused?.isFocused == true && focused.onCheckIsTextEditor()
    Timber.d("Attachment system Back preview=%s ime=%s editor=%s", previewOpen, imeVisible, editorFocused)
    if (previewOpen && dialogView != null && (imeVisible || editorFocused)) {
        val input = dialogView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(focused?.windowToken ?: dialogView.windowToken, 0)
        focused?.clearFocus()
    } else {
        onBack()
    }
}
