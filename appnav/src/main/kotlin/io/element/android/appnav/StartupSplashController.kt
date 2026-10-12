/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView

/**
 * Controls only the Android system splash curtain. Lifecycle services remain authoritative for
 * deciding which destination may be shown.
 */
fun interface StartupSplashController {
    fun revealPreparedDestination()
}

val LocalStartupSplashController = staticCompositionLocalOf<StartupSplashController> {
    StartupSplashController {}
}

/**
 * Releases the system splash only after the selected destination has completed layout.
 */
@Composable
fun Modifier.revealStartupSplashWhenLaidOut(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val controller = LocalStartupSplashController.current
    val view = LocalView.current
    // Replacing a same-sized Appyx destination need not trigger placement again. Once the
    // prepared destination is committed, the next native traversal can draw it directly.
    // Waiting for another child layout callback would deadlock behind the splash pre-draw gate.
    SideEffect {
        if (view.isAttachedToWindow && view.isLaidOut && view.width > 0 && view.height > 0) {
            controller.revealPreparedDestination()
        }
    }
    // Global-position dispatch can wait for a draw that the system splash is blocking.
    // Placement runs during layout, before that pre-draw curtain, and needs real bounds.
    return onPlaced { coordinates ->
        if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
            controller.revealPreparedDestination()
        }
    }
}

/** Presentation only; never changes admission, session restoration or App lock decisions. */
val LocalCompactStartup = staticCompositionLocalOf { false }
