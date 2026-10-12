/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.poll.api.pollcontent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.element.android.compound.theme.ElementTheme

/** Keep system icons readable in the interaction dialog's own edge-to-edge window. */
@Composable
fun InteractionDialogSystemBars() {
    val view = LocalView.current
    val lightTheme = ElementTheme.isLightTheme
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightTheme
                isAppearanceLightNavigationBars = lightTheme
            }
        }
    }
}
