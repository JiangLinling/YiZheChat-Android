/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl.components

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** These describe requested destinations, never proof of the page an OEM actually displayed. */
internal enum class StartupSettingsDestination { ServiceDetails, ServiceList, AccessibilityHome }

internal data class StartupSettingsRoute(val destination: StartupSettingsDestination, val intent: Intent)

private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
private const val FRAGMENT_ARGS = ":settings:show_fragment_args"

private fun Intent.highlightService(component: ComponentName): Intent = apply {
    // AOSP-derived settings can scroll to/highlight this preference. OEMs may ignore it.
    // Do not inject an unverified fragment class into other vendors' settings activities.
    val key = component.flattenToString()
    putExtra(FRAGMENT_ARG_KEY, key)
    putExtra(FRAGMENT_ARGS, Bundle().apply { putString(FRAGMENT_ARG_KEY, key) })
}

internal fun startupAccelerationSettingsRoutes(
    family: DeviceFamily,
    component: ComponentName,
    title: CharSequence,
    overviewOnly: Boolean = false,
): List<StartupSettingsRoute> = buildList {
    if (!overviewOnly) {
        if (family == DeviceFamily.XIAOMI) {
            add(StartupSettingsRoute(
                StartupSettingsDestination.ServiceDetails,
                startupAccelerationXiaomiSettingsIntent(component, title),
            ))
        }
        // Includes OPPO/OnePlus/realme, vivo/iQOO, Huawei Android/Honor, Samsung,
        // Motorola and unknown brands. Try the ROM's own details resolver; many ROMs
        // restrict it to system callers, in which case the launcher falls through.
        add(StartupSettingsRoute(StartupSettingsDestination.ServiceDetails, startupAccelerationSettingsIntent(component)))
        if (family == DeviceFamily.SAMSUNG) {
            // One UI's installed-services list is closer to the switch than its
            // accessibility home. This is a list, NOT a verified direct switch page.
            add(StartupSettingsRoute(
                StartupSettingsDestination.ServiceList,
                Intent("com.samsung.accessibility.installed_service")
                    .setPackage("com.android.settings")
                    .highlightService(component),
            ))
        }
        add(StartupSettingsRoute(
            StartupSettingsDestination.ServiceList,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).highlightService(component),
        ))
    }
    // Always last, without vendor extras or a hard-coded settings package/activity.
    add(StartupSettingsRoute(StartupSettingsDestination.AccessibilityHome, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
}

internal fun launchStartupAccelerationSettings(
    routes: List<StartupSettingsRoute>,
    launch: (Intent) -> Unit,
): StartupSettingsDestination? {
    for (route in routes) {
        try {
            launch(Intent(route.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return route.destination
        } catch (_: ActivityNotFoundException) {
            // Removed/renamed vendor activity: try the next route.
        } catch (_: SecurityException) {
            // System-only entry: do not request elevated permissions.
        } catch (_: IllegalArgumentException) {
            // This ROM rejects these optional parameters.
        }
    }
    return null
}
