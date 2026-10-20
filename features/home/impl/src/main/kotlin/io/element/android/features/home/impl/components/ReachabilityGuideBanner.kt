/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import io.element.android.features.home.impl.R
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsEvents
import io.element.android.libraries.androidutils.background.AutostartPermissionState
import io.element.android.libraries.androidutils.background.xiaomiAutostartPermissionState
import io.element.android.libraries.designsystem.components.Announcement
import io.element.android.libraries.designsystem.components.AnnouncementType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent

/** One reminder at a time, on the same room-list surface as the recovery-key reminder. */
@Composable
internal fun ReachabilityGuideBanner(callPermissions: FullScreenIntentPermissionsState): Boolean {
    val context = LocalContext.current
    val family = remember { deviceFamily(Build.MANUFACTURER, Build.BRAND) }
    val preferences = remember(context) { context.getSharedPreferences("reachability_guide", Context.MODE_PRIVATE) }
    var refresh by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    var deferred by remember { mutableStateOf(preferences.getStringSet("deferred_v2", emptySet()).orEmpty().toSet()) }
    var openedStep by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Requery the OS, including a denial; the callback is not a fabricated grant.
        refresh++
    }
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            resumed = true
            deferred = preferences.getStringSet("deferred_v2", emptySet()).orEmpty().toSet()
            refresh++
            callPermissions.eventSink(FullScreenIntentPermissionsEvents.Refresh)
        } else if (event == Lifecycle.Event.ON_PAUSE) resumed = false
    }
    val acceleration = rememberStartupAccelerationState(context, refresh, resumed)
    val next = remember(context, refresh, deferred, family, acceleration, callPermissions) {
        ReachabilityStep.entries.firstOrNull { step ->
            val essentialNotification = step in setOf(ReachabilityStep.NOTIFICATIONS, ReachabilityStep.FLOATING_NOTIFICATION,
                ReachabilityStep.CALL_NOTIFICATION)
            (!callPermissions.shouldDisplayHomeBanner || essentialNotification) &&
                shouldGuide(context.readiness(step, family, acceleration, callPermissions), step.name in deferred)
        }
    } ?: return false
    val accelerationOverview = next == ReachabilityStep.STARTUP_ACCELERATION && openedStep == next.name
    val serviceLabel = remember(context) {
        runCatching {
            context.packageManager.getServiceInfo(context.startupAccelerationComponent(), 0)
                .loadLabel(context.packageManager).toString()
        }.getOrDefault(context.getString(R.string.screen_reachability_startup))
    }
    val settingsHelp = if (next == ReachabilityStep.STARTUP_ACCELERATION) {
        stringResource(R.string.screen_reachability_startup_path, serviceLabel)
    } else {
        stringResource(family.pathFor(next))
    }
    ReachabilityAnnouncement(
        title = stringResource(next.title),
        description = stringResource(next.description) + "\n" + settingsHelp,
        actionText = stringResource(when {
            accelerationOverview -> R.string.screen_reachability_startup_open_overview
            next == ReachabilityStep.STARTUP_ACCELERATION -> R.string.screen_reachability_startup_open
            else -> R.string.screen_reachability_open
        }),
        onDismissClick = {
            deferred = deferred + next.name
            preferences.edit().putStringSet("deferred_v2", deferred).apply()
            openedStep = null
        },
        onActionClick = {
            if (next == ReachabilityStep.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("notifications_requested_v2", false)
            ) {
                preferences.edit().putBoolean("notifications_requested_v2", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val channelId = when (next) {
                    ReachabilityStep.FLOATING_NOTIFICATION -> callPermissions.messageNotificationChannelId
                    ReachabilityStep.CALL_NOTIFICATION -> callPermissions.ringingNotificationChannelId
                    else -> null
                }
                context.openReachabilitySettings(next, family, accelerationOverview, channelId)
                openedStep = next.name
            }
        },
    )
    return true
}

@Composable
private fun ReachabilityAnnouncement(
    title: String,
    description: String,
    actionText: String,
    onDismissClick: () -> Unit,
    onActionClick: () -> Unit,
) {
    Announcement(
        title = title,
        description = description,
        type = AnnouncementType.Actionable(
            actionText = actionText,
            onDismissClick = onDismissClick,
            onActionClick = onActionClick,
        ),
        modifier = androidx.compose.ui.Modifier.roomListBannerPadding(),
    )
}

@PreviewsDayNight
@Composable
private fun ReachabilityAnnouncementPreview() = ElementPreview {
    ReachabilityAnnouncement(
        title = "Show message pop-ups",
        description = "Turn on floating notifications.\nSettings > Notifications > YiZheChat",
        actionText = "Open settings",
        onDismissClick = {},
        onActionClick = {},
    )
}

internal enum class DeviceFamily { XIAOMI, VIVO, OPPO, REALME, SAMSUNG, MOTOROLA, HONOR, HUAWEI_ANDROID, GENERIC }

internal fun deviceFamily(manufacturer: String, brand: String): DeviceFamily {
    val values = listOf(manufacturer, brand).map { it.lowercase() }
    return when {
        values.any { it in setOf("xiaomi", "redmi", "poco") } -> DeviceFamily.XIAOMI
        values.any { it in setOf("vivo", "iqoo") } -> DeviceFamily.VIVO
        values.any { it == "realme" } -> DeviceFamily.REALME
        values.any { it in setOf("oppo", "oneplus") } -> DeviceFamily.OPPO
        values.any { it == "samsung" } -> DeviceFamily.SAMSUNG
        values.any { it in setOf("motorola", "moto") } -> DeviceFamily.MOTOROLA
        values.any { it == "honor" } -> DeviceFamily.HONOR
        values.any { it == "huawei" } -> DeviceFamily.HUAWEI_ANDROID
        else -> DeviceFamily.GENERIC
    }
}

private enum class ReachabilityStep(val title: Int, val description: Int) {
    NOTIFICATIONS(R.string.screen_reachability_notifications, R.string.screen_reachability_notifications_detail),
    FLOATING_NOTIFICATION(R.string.screen_reachability_floating, R.string.screen_reachability_floating_detail),
    LOCK_SCREEN_NOTIFICATION(R.string.screen_reachability_lock_screen, R.string.screen_reachability_lock_screen_detail),
    SCREEN_OFF_WAKE(R.string.screen_reachability_screen_off, R.string.screen_reachability_screen_off_detail),
    CALL_NOTIFICATION(R.string.screen_reachability_call, R.string.screen_reachability_call_detail),
    STARTUP_ACCELERATION(R.string.screen_reachability_startup, R.string.screen_reachability_startup_detail),
    BACKGROUND_POPUP(R.string.screen_reachability_background_popup, R.string.screen_reachability_background_popup_detail),
    OVERLAY(R.string.screen_reachability_overlay, R.string.screen_reachability_overlay_detail),
    AUTOSTART(R.string.screen_reachability_autostart, R.string.screen_reachability_autostart_detail),
    RECENTS_LOCK(R.string.screen_reachability_recents_lock, R.string.screen_reachability_recents_lock_detail),
}

private fun Context.readiness(
    step: ReachabilityStep,
    family: DeviceFamily,
    acceleration: StartupAccelerationState,
    callPermissions: FullScreenIntentPermissionsState,
): ReachabilityReadiness = when (step) {
    ReachabilityStep.NOTIFICATIONS -> runCatching {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) ReachabilityReadiness.Ready else ReachabilityReadiness.NeedsUser
    }.getOrDefault(ReachabilityReadiness.Unknown)
    ReachabilityStep.FLOATING_NOTIFICATION -> channelReadiness(callPermissions.messageNotificationChannelId)
    ReachabilityStep.CALL_NOTIFICATION -> channelReadiness(callPermissions.ringingNotificationChannelId)
    ReachabilityStep.STARTUP_ACCELERATION -> when (acceleration) {
        StartupAccelerationState.Disabled -> ReachabilityReadiness.NeedsUser
        StartupAccelerationState.Ready -> ReachabilityReadiness.Ready
        StartupAccelerationState.Unavailable -> ReachabilityReadiness.Unsupported
        // Authorization is already enabled while the existing service retries; do not request it again.
        StartupAccelerationState.Recovering, StartupAccelerationState.Unknown -> ReachabilityReadiness.Unknown
    }
    ReachabilityStep.AUTOSTART -> if (family == DeviceFamily.XIAOMI) {
        when (xiaomiAutostartPermissionState()) {
            AutostartPermissionState.ALLOWED -> ReachabilityReadiness.Ready
            AutostartPermissionState.BLOCKED -> ReachabilityReadiness.NeedsUser
            AutostartPermissionState.UNKNOWN -> ReachabilityReadiness.Unknown
        }
    } else ReachabilityReadiness.Unknown
    // FullScreenIntentPermissionsPresenter owns incoming-call permission guidance; do not duplicate it here.
    ReachabilityStep.BACKGROUND_POPUP -> ReachabilityReadiness.Unknown
    // Optional enhancements and unreadable OEM switches stay in settings, not a compulsory onboarding queue.
    ReachabilityStep.OVERLAY, ReachabilityStep.SCREEN_OFF_WAKE,
    ReachabilityStep.RECENTS_LOCK, ReachabilityStep.LOCK_SCREEN_NOTIFICATION -> ReachabilityReadiness.Unknown
}

private fun DeviceFamily.pathFor(step: ReachabilityStep): Int = when (step) {
    ReachabilityStep.STARTUP_ACCELERATION -> R.string.screen_reachability_startup_path
    ReachabilityStep.FLOATING_NOTIFICATION -> if (this == DeviceFamily.XIAOMI) {
        R.string.screen_reachability_path_xiaomi_floating
    } else notificationPath()
    ReachabilityStep.LOCK_SCREEN_NOTIFICATION -> if (this == DeviceFamily.XIAOMI) {
        R.string.screen_reachability_path_xiaomi_lock_screen
    } else notificationPath()
    ReachabilityStep.AUTOSTART -> when (this) {
        DeviceFamily.XIAOMI -> R.string.screen_reachability_path_xiaomi_autostart
        DeviceFamily.VIVO -> R.string.screen_reachability_path_vivo_autostart
        DeviceFamily.OPPO -> R.string.screen_reachability_path_oppo_autostart
        DeviceFamily.REALME -> R.string.screen_reachability_path_realme_autostart
        DeviceFamily.SAMSUNG -> R.string.screen_reachability_path_samsung_background_activity
        DeviceFamily.MOTOROLA -> R.string.screen_reachability_path_motorola_background_activity
        DeviceFamily.HONOR, DeviceFamily.HUAWEI_ANDROID -> R.string.screen_reachability_path_huawei_autostart
        DeviceFamily.GENERIC -> R.string.screen_reachability_path_generic_autostart
    }
    ReachabilityStep.BACKGROUND_POPUP -> when (this) {
        DeviceFamily.XIAOMI -> R.string.screen_reachability_path_xiaomi_background
        DeviceFamily.VIVO -> R.string.screen_reachability_path_vivo_background
        DeviceFamily.OPPO -> R.string.screen_reachability_path_oppo_background
        DeviceFamily.REALME, DeviceFamily.SAMSUNG, DeviceFamily.MOTOROLA -> R.string.screen_reachability_path_generic_background
        DeviceFamily.HONOR, DeviceFamily.HUAWEI_ANDROID -> R.string.screen_reachability_path_huawei_background
        DeviceFamily.GENERIC -> R.string.screen_reachability_path_generic_background
    }
    ReachabilityStep.SCREEN_OFF_WAKE -> when (this) {
        DeviceFamily.VIVO -> R.string.screen_reachability_path_vivo_wake
        DeviceFamily.HUAWEI_ANDROID -> R.string.screen_reachability_path_huawei_wake
        else -> R.string.screen_reachability_path_generic_wake
    }
    ReachabilityStep.OVERLAY -> R.string.screen_reachability_path_overlay
    ReachabilityStep.RECENTS_LOCK -> R.string.screen_reachability_path_xiaomi_recents_lock
    else -> notificationPath()
}

private fun DeviceFamily.notificationPath(): Int = when (this) {
        DeviceFamily.XIAOMI -> R.string.screen_reachability_path_xiaomi_notification
        DeviceFamily.VIVO -> R.string.screen_reachability_path_vivo_notification
        DeviceFamily.OPPO -> R.string.screen_reachability_path_oppo_notification
        DeviceFamily.REALME, DeviceFamily.SAMSUNG, DeviceFamily.MOTOROLA -> R.string.screen_reachability_path_generic_notification
        DeviceFamily.HONOR -> R.string.screen_reachability_path_honor_notification
        DeviceFamily.HUAWEI_ANDROID -> R.string.screen_reachability_path_huawei_notification
        DeviceFamily.GENERIC -> R.string.screen_reachability_path_generic_notification
}

private fun Context.openReachabilitySettings(step: ReachabilityStep, family: DeviceFamily, accelerationOverview: Boolean = false, channelId: String? = null): Boolean {
    if (step == ReachabilityStep.STARTUP_ACCELERATION) {
        return launchStartupAccelerationSettings(
            startupAccelerationSettingsRoutes(
                family, startupAccelerationComponent(), getString(R.string.screen_reachability_startup), accelerationOverview,
            ),
            launch = { startActivity(it) },
        ) != null
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
    val notificationSettings = Intent(if (channelId == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .apply { if (channelId != null) putExtra(Settings.EXTRA_CHANNEL_ID, channelId) }
    val candidate = when (step) {
        ReachabilityStep.STARTUP_ACCELERATION -> return false // Handled above, with an accessibility-specific fallback.
        ReachabilityStep.NOTIFICATIONS, ReachabilityStep.FLOATING_NOTIFICATION,
        ReachabilityStep.LOCK_SCREEN_NOTIFICATION, ReachabilityStep.CALL_NOTIFICATION -> notificationSettings
        ReachabilityStep.SCREEN_OFF_WAKE -> Intent(Settings.ACTION_SETTINGS)
        ReachabilityStep.RECENTS_LOCK -> appDetails
        ReachabilityStep.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        ReachabilityStep.BACKGROUND_POPUP -> if (family == DeviceFamily.XIAOMI) {
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName)
        } else appDetails
        ReachabilityStep.AUTOSTART -> when (family) {
            DeviceFamily.XIAOMI -> appDetails
            DeviceFamily.VIVO -> Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            DeviceFamily.OPPO -> Intent().setComponent(ComponentName(
                "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            DeviceFamily.REALME, DeviceFamily.MOTOROLA -> appDetails
            DeviceFamily.SAMSUNG -> Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool")
                .putExtra("activity_type", 2)
            DeviceFamily.HONOR, DeviceFamily.HUAWEI_ANDROID -> Intent().setComponent(ComponentName(
                "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            DeviceFamily.GENERIC -> appDetails
        }
    }
    return startSafely(candidate) || startSafely(appDetails) || startSafely(Intent(Settings.ACTION_SETTINGS))
}

private fun Context.startSafely(intent: Intent): Boolean = try {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
