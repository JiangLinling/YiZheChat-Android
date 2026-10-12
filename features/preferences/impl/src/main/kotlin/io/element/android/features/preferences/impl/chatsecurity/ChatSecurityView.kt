/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.chatsecurity

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.androidutils.background.AutostartPermissionState
import io.element.android.libraries.androidutils.background.xiaomiApplicationDetailsSettingsIntent
import io.element.android.libraries.androidutils.background.xiaomiAutostartPermissionState
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.PendingDeviceRecovery

internal enum class ChatCapability(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    MESSAGE_NOTIFICATIONS(R.string.screen_chat_security_notifications, R.string.screen_chat_security_notifications_description),
    MICROPHONE_CAMERA(R.string.screen_chat_security_media, R.string.screen_chat_security_media_description),
    CALL_ALERTS(R.string.screen_chat_security_call_alerts, R.string.screen_chat_security_call_alerts_description),
    DO_NOT_DISTURB(R.string.screen_chat_security_dnd, R.string.screen_chat_security_dnd_description),
    BACKGROUND_RUNTIME(R.string.screen_chat_security_background, R.string.screen_chat_security_background_description),
    AUTOSTART(R.string.screen_chat_security_autostart, R.string.screen_chat_security_autostart_description),
    BACKGROUND_DATA(R.string.screen_chat_security_data, R.string.screen_chat_security_data_description),
    LOCK_SCREEN(R.string.screen_chat_security_lock_screen, R.string.screen_chat_security_lock_screen_description),
}

private enum class CapabilityState { ENABLED, ACTION_REQUIRED, CHECK_IN_SETTINGS }

@Composable
fun ChatSecurityView(
    state: ChatSecurityState,
    onBackClick: () -> Unit,
    onRestoreHistoryClick: () -> Unit,
    onEncryptionClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var recoveryToConfirm by remember { mutableStateOf<PendingDeviceRecovery?>(null) }
    var showLocalHistoryCleanupConfirmation by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshKey++
    }
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            refreshKey++
            state.eventSink(ChatSecurityEvent.Refresh)
        }
    }

    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        title = stringResource(R.string.screen_chat_security_title),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(CommonStrings.common_encryption)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Key())),
            onClick = onEncryptionClick,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_chat_security_access_verification)) },
            supportingContent = { Text(state.deviceSecurityState.label()) },
            trailingContent = ListItemContent.Text(
                stringResource(
                    if (state.isRefreshing) R.string.screen_chat_security_refreshing else R.string.screen_chat_security_refresh
                )
            ),
            onClick = { state.eventSink(ChatSecurityEvent.Refresh) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_chat_security_history_recovery)) },
            supportingContent = { Text(state.historyRecoveryState.label()) },
            trailingContent = if (state.historyRecoveryState is PublicHistoryRecoveryState.AVAILABLE) {
                ListItemContent.Text(stringResource(R.string.screen_chat_security_restore_action))
            } else {
                ListItemContent.Text(stringResource(R.string.screen_chat_security_recheck))
            },
            onClick = {
                if (state.historyRecoveryState is PublicHistoryRecoveryState.AVAILABLE) {
                    onRestoreHistoryClick()
                } else {
                    state.eventSink(ChatSecurityEvent.Refresh)
                }
            },
        )
        if (state.historyRecoveryState.canClearUnavailableHistoryLocally()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_chat_security_clear_unavailable_history)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.screen_chat_security_clear_unavailable_history_description,
                            state.unavailableHistoryCount,
                        )
                    )
                },
                trailingContent = ListItemContent.Text(
                    stringResource(
                        if (state.isLocalHistoryCleanupRunning) {
                            R.string.screen_chat_security_recovery_processing
                        } else {
                            R.string.screen_chat_security_clear_action
                        }
                    )
                ),
                onClick = {
                    if (!state.isLocalHistoryCleanupRunning) showLocalHistoryCleanupConfirmation = true
                },
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_chat_security_device_code)) },
            supportingContent = {
                Text(
                    state.currentDeviceCode?.let {
                        stringResource(R.string.screen_chat_security_device_code_value, it)
                    } ?: stringResource(R.string.screen_chat_security_device_code_unavailable)
                )
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_chat_security_request_device_recovery)) },
            supportingContent = {
                Text(
                    state.deviceRecoveryStatus?.recoveryLabel()
                        ?: stringResource(R.string.screen_chat_security_request_device_recovery_description)
                )
            },
            trailingContent = ListItemContent.Text(
                stringResource(
                    if (state.isRecoveryActionRunning) {
                        R.string.screen_chat_security_recovery_processing
                    } else {
                        R.string.screen_chat_security_request_action
                    }
                )
            ),
            onClick = {
                if (!state.isRecoveryActionRunning) state.eventSink(ChatSecurityEvent.RequestDeviceRecovery)
            },
        )
        state.pendingDeviceRecoveries.forEach { recovery ->
            val deviceCode = recovery.newInstallationId.takeLast(8).uppercase()
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_chat_security_confirm_new_device)) },
                supportingContent = {
                    Text(stringResource(R.string.screen_chat_security_confirm_new_device_description, deviceCode))
                },
                trailingContent = ListItemContent.Text(stringResource(R.string.screen_chat_security_confirm_action)),
                onClick = { recoveryToConfirm = recovery },
            )
        }
        HorizontalDivider()
        ChatCapability.entries.forEach { capability ->
            val capabilityState = remember(capability, refreshKey) { context.capabilityState(capability) }
            ListItem(
                headlineContent = { Text(stringResource(capability.titleRes)) },
                supportingContent = { Text(stringResource(capability.descriptionRes)) },
                trailingContent = ListItemContent.Text(capabilityState.label()),
                onClick = {
                    when (capability) {
                        ChatCapability.MESSAGE_NOTIFICATIONS -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                            ) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            } else {
                                context.openNotificationSettings()
                            }
                        }
                        ChatCapability.MICROPHONE_CAMERA -> permissionLauncher.launch(mediaRuntimePermissions())
                        ChatCapability.CALL_ALERTS -> context.openCallAlertSettings()
                        ChatCapability.DO_NOT_DISTURB -> context.openDoNotDisturbSettings()
                        ChatCapability.BACKGROUND_RUNTIME -> context.openBatteryOptimizationSettings()
                        ChatCapability.AUTOSTART -> context.openAutostartSettings()
                        ChatCapability.BACKGROUND_DATA -> context.openBackgroundDataSettings()
                        ChatCapability.LOCK_SCREEN -> context.openNotificationSettings()
                    }
                },
            )
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_chat_security_location_title)) },
            supportingContent = { Text(stringResource(R.string.screen_chat_security_location_boundary)) },
        )
    }
    recoveryToConfirm?.let { recovery ->
        val deviceCode = recovery.newInstallationId.takeLast(8).uppercase()
        ConfirmationDialog(
            title = stringResource(R.string.screen_chat_security_confirm_dialog_title),
            content = stringResource(R.string.screen_chat_security_confirm_dialog_content, deviceCode),
            submitText = stringResource(R.string.screen_chat_security_confirm_action),
            onSubmitClick = {
                state.eventSink(ChatSecurityEvent.ConfirmDeviceRecovery(recovery.recoveryId))
                recoveryToConfirm = null
            },
            onDismiss = { recoveryToConfirm = null },
        )
    }
    if (showLocalHistoryCleanupConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.screen_chat_security_clear_unavailable_history_dialog_title),
            content = stringResource(
                R.string.screen_chat_security_clear_unavailable_history_dialog_content,
                state.unavailableHistoryCount,
            ),
            submitText = stringResource(R.string.screen_chat_security_clear_action),
            onSubmitClick = {
                state.eventSink(ChatSecurityEvent.ClearUnavailableHistoryLocally)
                showLocalHistoryCleanupConfirmation = false
            },
            onDismiss = { showLocalHistoryCleanupConfirmation = false },
        )
    }
    state.recoveryActionFailure?.let { failure ->
        AlertDialog(
            title = stringResource(R.string.screen_chat_security_recovery_error_title),
            content = stringResource(failure.messageResource()),
            onDismiss = { state.eventSink(ChatSecurityEvent.DismissRecoveryError) },
        )
    }
    if (state.localHistoryCleanupFailed) {
        AlertDialog(
            title = stringResource(R.string.screen_chat_security_clear_unavailable_history_error_title),
            content = stringResource(R.string.screen_chat_security_clear_unavailable_history_error_content),
            onDismiss = { state.eventSink(ChatSecurityEvent.DismissLocalHistoryCleanupError) },
        )
    }
}

@Composable
private fun DeviceRecoveryStatus.recoveryLabel(): String = stringResource(
    when (state) {
        DeviceRecoveryState.CONFIDENTIAL_AUTHORIZED -> R.string.screen_chat_security_recovery_confidential_authorized
        DeviceRecoveryState.AWAITING_ADMIN_APPROVAL -> R.string.screen_chat_security_recovery_awaiting_admin
        DeviceRecoveryState.STANDARD_AUTHORIZED -> R.string.screen_chat_security_recovery_standard_authorized
        DeviceRecoveryState.AWAITING_OLD_DEVICE -> R.string.screen_chat_security_recovery_awaiting_old_device
        DeviceRecoveryState.EXPIRED -> if (standardAuthorized) {
            R.string.screen_chat_security_recovery_standard_authorized_confidential_expired
        } else {
            R.string.screen_chat_security_recovery_expired
        }
    }
)

@Composable
private fun PublicDeviceSecurityState.label(): String = stringResource(
    when (this) {
        PublicDeviceSecurityState.NOT_STARTED -> R.string.screen_chat_security_status_not_started
        PublicDeviceSecurityState.PROCESSING -> R.string.screen_chat_security_status_processing
        PublicDeviceSecurityState.PASSED -> R.string.screen_chat_security_status_passed
        PublicDeviceSecurityState.NOT_PASSED -> R.string.screen_chat_security_status_not_passed
        PublicDeviceSecurityState.ACTION_REQUIRED -> R.string.screen_chat_security_status_action_required
        PublicDeviceSecurityState.SERVICE_UNAVAILABLE -> R.string.screen_chat_security_status_service_unavailable
    }
)

@Composable
private fun CapabilityState.label(): String = stringResource(
    when (this) {
        CapabilityState.ENABLED -> R.string.screen_chat_security_enabled
        CapabilityState.ACTION_REQUIRED -> R.string.screen_chat_security_needs_action
        CapabilityState.CHECK_IN_SETTINGS -> R.string.screen_chat_security_check_settings
    }
)

@Composable
private fun PublicHistoryRecoveryState.label(): String = when (this) {
    PublicHistoryRecoveryState.CHECKING -> stringResource(R.string.screen_chat_security_recovery_checking)
    is PublicHistoryRecoveryState.AVAILABLE -> stringResource(
        R.string.screen_chat_security_recovery_available,
        messageCount,
    )
    is PublicHistoryRecoveryState.RECOVERING -> stringResource(
        R.string.screen_chat_security_recovery_in_progress,
        messageCount,
    )
    is PublicHistoryRecoveryState.WAITING_FOR_OLD_DEVICE -> stringResource(
        R.string.screen_chat_security_recovery_waiting_old_device_with_count,
        messageCount,
    )
    is PublicHistoryRecoveryState.WAITING_FOR_ADMIN_APPROVAL -> stringResource(
        R.string.screen_chat_security_recovery_waiting_admin_with_count,
        messageCount,
    )
    is PublicHistoryRecoveryState.FAILED_CLOSED -> stringResource(
        R.string.screen_chat_security_recovery_failed_closed_with_count,
        messageCount,
    )
    is PublicHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE -> stringResource(
        R.string.screen_chat_security_recovery_identity_reset_with_count,
        messageCount,
    )
    PublicHistoryRecoveryState.NONE -> stringResource(R.string.screen_chat_security_recovery_none)
    PublicHistoryRecoveryState.NO_MATRIX_BACKUP -> stringResource(R.string.screen_chat_security_recovery_no_matrix_backup)
    PublicHistoryRecoveryState.DEVICE_VERIFICATION_REQUIRED ->
        stringResource(R.string.screen_chat_security_recovery_device_verification_required)
    PublicHistoryRecoveryState.SERVICE_UNAVAILABLE ->
        stringResource(R.string.screen_chat_security_recovery_service_unavailable)
}

private fun PublicHistoryRecoveryState.canClearUnavailableHistoryLocally(): Boolean = when (this) {
    is PublicHistoryRecoveryState.AVAILABLE,
    is PublicHistoryRecoveryState.FAILED_CLOSED,
    is PublicHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE,
    PublicHistoryRecoveryState.NO_MATRIX_BACKUP -> true
    else -> false
}

@StringRes
private fun PublicRecoveryActionFailure.messageResource(): Int = when (this) {
    PublicRecoveryActionFailure.SERVICE_UNAVAILABLE -> R.string.screen_chat_security_recovery_error_service_unavailable
    PublicRecoveryActionFailure.REQUEST_EXPIRED -> R.string.screen_chat_security_recovery_error_expired
    PublicRecoveryActionFailure.APPROVAL_REQUIRED -> R.string.screen_chat_security_recovery_error_approval_required
    PublicRecoveryActionFailure.DEVICE_NOT_ELIGIBLE -> R.string.screen_chat_security_recovery_error_device_not_eligible
    PublicRecoveryActionFailure.SECURITY_REJECTED -> R.string.screen_chat_security_recovery_error_security_rejected
    PublicRecoveryActionFailure.UNKNOWN -> R.string.screen_chat_security_recovery_error_content
}

private fun Context.capabilityState(capability: ChatCapability): CapabilityState = when (capability) {
    ChatCapability.MESSAGE_NOTIFICATIONS -> {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (runtimeGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            CapabilityState.ENABLED
        } else {
            CapabilityState.ACTION_REQUIRED
        }
    }
    ChatCapability.MICROPHONE_CAMERA -> {
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        if (hasPermission(Manifest.permission.RECORD_AUDIO) && hasPermission(Manifest.permission.CAMERA) && bluetoothGranted) {
            CapabilityState.ENABLED
        } else {
            CapabilityState.ACTION_REQUIRED
        }
    }
    ChatCapability.CALL_ALERTS -> {
        val fullScreenGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            NotificationManagerCompat.from(this).canUseFullScreenIntent()
        if (fullScreenGranted && isXiaomiBackgroundStartAllowed()) {
            CapabilityState.ENABLED
        } else {
            CapabilityState.ACTION_REQUIRED
        }
    }
    ChatCapability.DO_NOT_DISTURB -> if (
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    ) {
        CapabilityState.ENABLED
    } else {
        CapabilityState.ACTION_REQUIRED
    }
    ChatCapability.BACKGROUND_RUNTIME -> if (
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    ) {
        CapabilityState.ENABLED
    } else if (isXiaomiDevice()) {
        // A missing platform exemption does not establish the separate OEM battery policy.
        CapabilityState.CHECK_IN_SETTINGS
    } else {
        CapabilityState.ACTION_REQUIRED
    }
    ChatCapability.AUTOSTART -> when (xiaomiAutostartPermissionState()) {
        AutostartPermissionState.ALLOWED -> CapabilityState.ENABLED
        AutostartPermissionState.BLOCKED -> CapabilityState.ACTION_REQUIRED
        AutostartPermissionState.UNKNOWN -> CapabilityState.CHECK_IN_SETTINGS
    }
    ChatCapability.BACKGROUND_DATA,
    ChatCapability.LOCK_SCREEN -> CapabilityState.CHECK_IN_SETTINGS
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun mediaRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

private fun Context.openApplicationDetails() {
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

private fun Context.openNotificationSettings() {
    if (!startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))) {
        openApplicationDetails()
    }
}

private fun Context.openCallAlertSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !NotificationManagerCompat.from(this).canUseFullScreenIntent() &&
        startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()))
    ) {
        return
    }
    if (isXiaomiDevice()) {
        val intent = Intent(MIUI_PERMISSION_EDITOR_ACTION)
            .setClassName(MIUI_SECURITY_CENTER_PACKAGE, MIUI_PERMISSION_EDITOR_ACTIVITY)
            .putExtra(MIUI_EXTRA_PACKAGE_NAME, packageName)
        if (startSafely(intent)) return
    }
    openNotificationSettings()
}

private fun Context.openDoNotDisturbSettings() {
    if (!startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))) openApplicationDetails()
}

@SuppressLint("BatteryLife")
private fun Context.openBatteryOptimizationSettings() {
    xiaomiApplicationDetailsSettingsIntent()?.let { if (startSafely(it)) return }
    if (!startSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))) {
        startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun Context.openAutostartSettings() {
    xiaomiApplicationDetailsSettingsIntent()?.let { if (startSafely(it)) return }
    val intents = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
    )
    if (intents.none(::startSafely)) openApplicationDetails()
}

private fun Context.openBackgroundDataSettings() {
    if (!startSafely(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, "package:$packageName".toUri()))) {
        openApplicationDetails()
    }
}

private fun Context.startSafely(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun Context.isXiaomiBackgroundStartAllowed(): Boolean {
    if (!isXiaomiDevice()) return true
    return runCatching {
        val manager = getSystemService(AppOpsManager::class.java)
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        method.invoke(manager, MIUI_OP_BACKGROUND_START_ACTIVITY, applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

private fun isXiaomiDevice(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021
private const val MIUI_PERMISSION_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
private const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
private const val MIUI_PERMISSION_EDITOR_ACTIVITY = "com.miui.permcenter.permissions.PermissionsEditorActivity"
private const val MIUI_EXTRA_PACKAGE_NAME = "extra_pkgname"

@PreviewsDayNight
@Composable
private fun ChatSecurityViewPassedPreview() = ElementPreview {
    ChatSecurityView(
        state = ChatSecurityState(
            PublicDeviceSecurityState.PASSED,
            PublicHistoryRecoveryState.AVAILABLE(18),
            currentDeviceCode = "A1B2C3D4",
            deviceRecoveryStatus = null,
            pendingDeviceRecoveries = emptyList(),
            isRefreshing = false,
            isRecoveryActionRunning = false,
            recoveryActionFailure = null,
        ) {},
        onBackClick = {},
        onRestoreHistoryClick = {},
        onEncryptionClick = {},
    )
}

@PreviewsDayNight
@Composable
private fun ChatSecurityViewProcessingPreview() = ElementPreview {
    ChatSecurityView(
        state = ChatSecurityState(
            PublicDeviceSecurityState.PROCESSING,
            PublicHistoryRecoveryState.CHECKING,
            currentDeviceCode = null,
            deviceRecoveryStatus = null,
            pendingDeviceRecoveries = emptyList(),
            isRefreshing = true,
            isRecoveryActionRunning = false,
            recoveryActionFailure = null,
        ) {},
        onBackClick = {},
        onRestoreHistoryClick = {},
        onEncryptionClick = {},
    )
}
