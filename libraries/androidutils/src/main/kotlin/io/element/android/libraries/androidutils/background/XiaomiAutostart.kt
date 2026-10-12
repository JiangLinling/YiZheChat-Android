/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.androidutils.background

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class AutostartPermissionState { ALLOWED, BLOCKED, UNKNOWN }

/** Read the current OEM grant. Unsupported APIs and default modes are never treated as grants. */
fun Context.xiaomiAutostartPermissionState(): AutostartPermissionState {
    if (!isXiaomiFamily()) return AutostartPermissionState.UNKNOWN
    return runCatching {
        val manager = getSystemService(AppOpsManager::class.java) ?: return AutostartPermissionState.UNKNOWN
        val checkOp = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        autostartStateFromAppOp(checkOp.invoke(manager, MIUI_OP_AUTO_START, applicationInfo.uid, packageName) as? Int)
    }.getOrDefault(AutostartPermissionState.UNKNOWN)
}

/** Xiaomi's app details page exposes this app's autostart and battery controls. */
fun Context.xiaomiApplicationDetailsSettingsIntent(): Intent? = if (isXiaomiFamily()) {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
} else {
    null
}

internal fun autostartStateFromAppOp(mode: Int?): AutostartPermissionState = when (mode) {
    AppOpsManager.MODE_ALLOWED -> AutostartPermissionState.ALLOWED
    AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AutostartPermissionState.BLOCKED
    else -> AutostartPermissionState.UNKNOWN
}

private fun isXiaomiFamily(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    listOf("Xiaomi", "Redmi", "POCO").any { Build.BRAND.equals(it, ignoreCase = true) }

private const val MIUI_OP_AUTO_START = 10008
