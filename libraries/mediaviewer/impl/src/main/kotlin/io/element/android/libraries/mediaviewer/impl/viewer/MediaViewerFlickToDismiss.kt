/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.compound.theme.ElementTheme

/**
 * Stable media-viewer container.
 *
 * Flick-to-dismiss could classify the opening pointer sequence as a dismiss gesture on some
 * devices, immediately returning to the timeline. Back and the explicit close action remain
 * available, while image zoom and video seeking keep the complete pointer stream.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MediaViewerFlickToDismiss(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDragging: () -> Unit = {},
    onResetting: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(ElementTheme.colors.bgCanvasDefault),
        content = content,
    )
}
