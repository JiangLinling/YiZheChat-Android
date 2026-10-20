/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.libraries.androidutils.background

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

enum class BackgroundBindingState {
    Unavailable, Unknown, Disabled, Recovering, Ready;

    val needsReminder: Boolean get() = this == Disabled || this == Recovering
}

fun backgroundBindingState(available: Boolean, authorized: Boolean?, foreground: Boolean?): BackgroundBindingState = when {
    !available -> BackgroundBindingState.Unavailable
    authorized == null -> BackgroundBindingState.Unknown
    !authorized -> BackgroundBindingState.Disabled
    foreground == null -> BackgroundBindingState.Unknown
    foreground -> BackgroundBindingState.Ready
    else -> BackgroundBindingState.Recovering
}

fun Context.backgroundBindingComponent(): ComponentName = ComponentName(
    packageName,
    "io.element.android.x.backgroundtest.BackgroundBindingTestService",
)

/** A system query, not a saved acknowledgement. Call off the UI thread. */
@Suppress("DEPRECATION")
fun Context.readBackgroundBindingState(): BackgroundBindingState {
    val component = backgroundBindingComponent()
    val available = try {
        val service = packageManager.getServiceInfo(component, 0)
        service.enabled && service.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        return BackgroundBindingState.Unknown
    }
    if (!available) return BackgroundBindingState.Unavailable
    val authorized = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    } catch (_: RuntimeException) {
        null
    }
    val foreground = if (authorized == true) runCatching {
        getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service == component && it.foreground }
    }.getOrNull() else null
    return backgroundBindingState(available, authorized, foreground)
}
