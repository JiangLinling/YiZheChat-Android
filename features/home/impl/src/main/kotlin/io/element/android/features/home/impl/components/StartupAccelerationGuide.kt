/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl.components

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class StartupAccelerationState {
    Unavailable, Unknown, Disabled, Recovering, Ready;

    val needsReminder: Boolean get() = this == Disabled || this == Recovering
}

internal fun startupAccelerationState(
    available: Boolean,
    authorized: Boolean?,
    foreground: Boolean?,
): StartupAccelerationState = when {
    !available -> StartupAccelerationState.Unavailable
    authorized == null -> StartupAccelerationState.Unknown
    !authorized -> StartupAccelerationState.Disabled
    foreground == true -> StartupAccelerationState.Ready
    else -> StartupAccelerationState.Recovering
}

// Only the installed variant declaring this service gets this optional reminder.
// This does not introduce accessibility services or permissions into other variants.
internal fun Context.startupAccelerationComponent() = ComponentName(
    packageName,
    "io.element.android.x.backgroundtest.BackgroundBindingTestService",
)

internal fun startupAccelerationSettingsIntent(component: ComponentName): Intent =
    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)

@Suppress("DEPRECATION")
private fun Context.readStartupAccelerationState(): StartupAccelerationState {
    val component = startupAccelerationComponent()
    val available = try {
        val service = packageManager.getServiceInfo(component, 0)
        service.enabled && service.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        return StartupAccelerationState.Unknown
    }
    if (!available) return StartupAccelerationState.Unavailable
    val authorized = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    } catch (_: RuntimeException) {
        null
    }
    // The service promotes itself only after onServiceConnected. A saved preference
    // or the unrelated ntfy notification cannot prove this connection is working.
    val foreground = if (authorized == true) runCatching {
        getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service == component && it.foreground }
    }.getOrNull() else null
    return startupAccelerationState(available, authorized, foreground)
}

@Composable
internal fun rememberStartupAccelerationState(context: Context, refresh: Int, resumed: Boolean): StartupAccelerationState {
    var state by remember(context) { mutableStateOf(StartupAccelerationState.Unknown) }
    LaunchedEffect(context, refresh, resumed) {
        if (!resumed) return@LaunchedEffect
        // Read after composition and off the UI thread; never gate the room list.
        // Allow the system binding and its existing finite foreground retries to settle.
        for (waitMs in listOf(0L, 1_000L, 2_000L, 7_000L)) {
            if (waitMs > 0) delay(waitMs)
            val observed = withContext(Dispatchers.IO) { context.readStartupAccelerationState() }
            if (observed != StartupAccelerationState.Unknown || !state.needsReminder) state = observed
            if (observed != StartupAccelerationState.Recovering) break
        }
    }
    return state
}
