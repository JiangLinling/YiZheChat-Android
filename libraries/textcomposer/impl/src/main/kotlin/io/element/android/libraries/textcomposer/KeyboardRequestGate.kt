/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Latest-wins gate for keyboard requests which may suspend while editor focus is acquired.
 */
internal class KeyboardRequestGate {
    private val generation = AtomicInteger()

    fun begin(): Int = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(requestGeneration: Int): Boolean = generation.get() == requestGeneration

    /**
     * Runs [completion] only while [requestGeneration] still owns the keyboard request.
     *
     * Keyboard focus and the later [android.view.View.post] callback happen in different
     * frames. Keeping the final UI mutation behind the same generation check prevents a
     * stale request from closing a newer emoji, voice, or attachment surface.
     */
    fun completeIfCurrent(requestGeneration: Int, completion: () -> Unit): Boolean {
        if (!isCurrent(requestGeneration)) return false
        completion()
        return true
    }
}
