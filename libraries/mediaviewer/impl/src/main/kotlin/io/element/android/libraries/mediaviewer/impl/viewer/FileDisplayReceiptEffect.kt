/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

/** A rendered offscreen page remains unacknowledged until the user actually displays it. */
@Composable
internal fun FileDisplayReceiptEffect(
    rendered: Boolean,
    displayed: Boolean,
    loaded: Boolean,
    oneTime: Boolean,
    onDisplayed: () -> Unit,
) {
    val currentOnDisplayed = rememberUpdatedState(onDisplayed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(rendered, displayed, loaded, oneTime, lifecycle) {
        if (!rendered || !displayed || !loaded || oneTime) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            currentOnDisplayed.value()
            awaitCancellation()
        }
    }
}
