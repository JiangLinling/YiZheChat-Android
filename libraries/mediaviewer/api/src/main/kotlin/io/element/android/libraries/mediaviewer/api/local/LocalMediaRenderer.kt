/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.api.local

import androidx.compose.runtime.Composable

interface LocalMediaRenderer {
    @Composable
    fun Render(
        localMedia: LocalMedia,
        accountId: String? = null,
        deviceId: String? = null,
        renderMode: LocalMediaRenderMode = LocalMediaRenderMode.STANDALONE,
    )
}

/** Controls whether the renderer owns its surrounding viewer chrome. */
enum class LocalMediaRenderMode {
    /** The full-screen media viewer supplies overlay controls, so Office content reserves their height. */
    STANDALONE,

    /** A parent screen already supplies a top app bar and its content insets. */
    EMBEDDED,
}
