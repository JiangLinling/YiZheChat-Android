/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.components.markdown

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.AppCompatEditText
import io.element.android.libraries.androidutils.clipboard.ClipboardIngress
import io.element.android.libraries.androidutils.clipboard.InAppClipboard

internal class MarkdownEditText(
    context: Context,
) : AppCompatEditText(context) {
    var onSelectionChangeListener: ((Int, Int) -> Unit)? = null

    private var isModifyingText = false

    init {
        val callback = InAppClipboardActionModeCallback()
        customInsertionActionModeCallback = callback
        customSelectionActionModeCallback = callback
    }

    fun updateEditableText(charSequence: CharSequence) {
        isModifyingText = true
        editableText.clear()
        editableText.append(charSequence)
        isModifyingText = false
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        isModifyingText = true
        super.setText(text, type)
        isModifyingText = false
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!isModifyingText) {
            onSelectionChangeListener?.invoke(selStart, selEnd)
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.copy -> copySelectionToInAppClipboard(cut = false)
        android.R.id.cut -> copySelectionToInAppClipboard(cut = true)
        android.R.id.paste,
        android.R.id.pasteAsPlainText -> pasteFromInAppClipboard()
        else -> super.onTextContextMenuItem(id)
    }

    private fun copySelectionToInAppClipboard(cut: Boolean): Boolean {
        val editable = editableText ?: return true
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        if (start == end) return true

        if (!InAppClipboard.copyPlainText(editable.subSequence(start, end))) return true
        if (cut) {
            editable.delete(start, end)
            setSelection(start)
        }
        return true
    }

    private fun pasteFromInAppClipboard(): Boolean {
        val text = ClipboardIngress.getPlainText(context) ?: return true
        val editable = editableText ?: return true
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        editable.replace(start, end, text)
        setSelection(start + text.length)
        return true
    }

    private inner class InAppClipboardActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            updatePasteAction(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updatePasteAction(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            android.R.id.copy,
            android.R.id.cut,
            android.R.id.paste,
            android.R.id.pasteAsPlainText -> {
                onTextContextMenuItem(item.itemId)
                mode.finish()
                true
            }
            else -> false
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit

        private fun updatePasteAction(menu: Menu) {
            menu.removeItem(android.R.id.paste)
            menu.removeItem(android.R.id.pasteAsPlainText)
            if (ClipboardIngress.hasPlainText(context)) {
                menu.add(Menu.NONE, android.R.id.paste, PASTE_MENU_ORDER, android.R.string.paste)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
    }

    // When using the EditText within a Compose layout, we need to override focusSearch to prevent the default behavior
    // Otherwise it can try searching for focusable nodes in the Compose hierarchy while they're being laid out, which will crash
    override fun focusSearch(direction: Int): View? {
        return null
    }

    private companion object {
        const val PASTE_MENU_ORDER = 0
    }
}
