/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentSystemBackHandlerTest {
    @Test
    fun `first Back leaves preview open for focused editor even when IME insets are absent and second Back closes`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity).apply { isFocusableInTouchMode = true }
            val editor = EditText(activity)
            root.addView(editor, ViewGroup.LayoutParams(200, 100))
            activity.setContentView(root)
            editor.requestFocus()
            assertThat(editor.isFocused).isTrue()
            var closes = 0
            handleAttachmentSystemBack(root, previewOpen = true) { closes++ }
            assertThat(closes).isEqualTo(0)
            assertThat(editor.isFocused).isFalse()
            handleAttachmentSystemBack(root, previewOpen = true) { closes++ }
            assertThat(closes).isEqualTo(1)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `focused ordinary view does not intercept preview close`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            val content = View(activity).apply { isFocusableInTouchMode = true }
            root.addView(content)
            activity.setContentView(root)
            content.requestFocus()
            assertThat(content.isFocused).isTrue()
            var closes = 0
            handleAttachmentSystemBack(root, previewOpen = true) { closes++ }
            assertThat(closes).isEqualTo(1)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `folder Back keeps existing navigation when a text editor is focused`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            val editor = EditText(activity)
            root.addView(editor)
            activity.setContentView(root)
            editor.requestFocus()
            var navigations = 0
            handleAttachmentSystemBack(root, previewOpen = false) { navigations++ }
            assertThat(navigations).isEqualTo(1)
            assertThat(editor.isFocused).isTrue()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `missing dialog view still closes preview instead of trapping Back`() {
        var closes = 0
        handleAttachmentSystemBack(null, previewOpen = true) { closes++ }
        assertThat(closes).isEqualTo(1)
    }
}
