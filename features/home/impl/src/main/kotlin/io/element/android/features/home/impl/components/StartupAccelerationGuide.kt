/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.element.android.libraries.androidutils.background.BackgroundBindingState
import io.element.android.libraries.androidutils.background.backgroundBindingComponent
import io.element.android.libraries.androidutils.background.backgroundBindingState
import io.element.android.libraries.androidutils.background.readBackgroundBindingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal typealias StartupAccelerationState = BackgroundBindingState

internal fun startupAccelerationState(
    available: Boolean,
    authorized: Boolean?,
    foreground: Boolean?,
): StartupAccelerationState = backgroundBindingState(available, authorized, foreground)

// Only the installed variant declaring this service gets this optional reminder.
// This does not introduce accessibility services or permissions into other variants.
internal fun Context.startupAccelerationComponent() = backgroundBindingComponent()

internal fun startupAccelerationSettingsIntent(component: ComponentName): Intent =
    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)

// MIUI exports SubSettings and permits this accessibility fragment for ordinary
// callers. Its standard details action instead requires a system-only permission.
// This only selects our service; the system still owns the switch and consent UI.
internal fun startupAccelerationXiaomiSettingsIntent(component: ComponentName, title: CharSequence): Intent =
    Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName("com.android.settings", "com.android.settings.SubSettings"))
        .putExtra(":settings:show_fragment", "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment")
        .putExtra(":settings:show_fragment_title", title.toString())
        .putExtra(":settings:show_fragment_args", Bundle().apply {
            putParcelable("component_name", component)
            putString("preference_key", component.flattenToString())
            putString("title", title.toString())
        })

@Composable
internal fun rememberStartupAccelerationState(context: Context, refresh: Int, resumed: Boolean): StartupAccelerationState {
    var state by remember(context) { mutableStateOf(StartupAccelerationState.Unknown) }
    LaunchedEffect(context, refresh, resumed) {
        if (!resumed) return@LaunchedEffect
        // Read after composition and off the UI thread; never gate the room list.
        // Allow the system binding and its existing finite foreground retries to settle.
        for (waitMs in listOf(0L, 1_000L, 2_000L, 7_000L)) {
            if (waitMs > 0) delay(waitMs)
            val observed = withContext(Dispatchers.IO) { context.readBackgroundBindingState() }
            if (observed != StartupAccelerationState.Unknown || !state.needsReminder) state = observed
            if (observed != StartupAccelerationState.Recovering) break
        }
    }
    return state
}
