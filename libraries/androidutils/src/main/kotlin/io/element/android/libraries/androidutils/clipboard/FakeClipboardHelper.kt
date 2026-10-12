/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

class FakeClipboardHelper : ClipboardHelper {
    var clipboardContents: Any? = null
    var allowCopy = true
    var protectedSource: ProtectedClipboardSource? = null

    override fun copyPlainText(text: String): Boolean {
        if (!allowCopy) return false
        clipboardContents = text
        protectedSource = null
        return true
    }

    override fun copyProtectedPlainText(text: String, source: ProtectedClipboardSource): Boolean {
        if (!allowCopy) return false
        clipboardContents = text
        protectedSource = source
        return true
    }
}
