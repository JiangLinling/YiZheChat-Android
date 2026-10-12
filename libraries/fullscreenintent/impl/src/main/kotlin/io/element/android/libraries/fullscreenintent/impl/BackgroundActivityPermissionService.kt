/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.fullscreenintent.impl

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext

/**
 * Handles the additional background activity permission used by Xiaomi and HyperOS.
 *
 * Android's standard full-screen intent permission can be granted while MIUI still strips the activity
 * [android.app.PendingIntent] from incoming call notifications. On affected devices, app-op 10021 controls
 * whether the system may display that activity while the app is in the background.
 */
interface BackgroundActivityPermissionService {
    fun isPermissionGranted(): Boolean
    fun createPermissionSettingsIntent(): Intent
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultBackgroundActivityPermissionService(
    @ApplicationContext private val context: Context,
) : BackgroundActivityPermissionService {
    companion object {
        private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021
        private const val MIUI_PERMISSION_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
        private const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        private const val MIUI_PERMISSION_EDITOR_ACTIVITY = "com.miui.permcenter.permissions.PermissionsEditorActivity"
        private const val EXTRA_PACKAGE_NAME = "extra_pkgname"
    }

    override fun isPermissionGranted(): Boolean {
        if (!isXiaomiDevice()) return true
        return runCatching {
            val appOpsManager = context.getSystemService(AppOpsManager::class.java)
            val checkOpNoThrow = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            checkOpNoThrow.invoke(
                appOpsManager,
                MIUI_OP_BACKGROUND_START_ACTIVITY,
                context.applicationInfo.uid,
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true)
    }

    override fun createPermissionSettingsIntent(): Intent {
        return Intent(MIUI_PERMISSION_EDITOR_ACTION)
            .setClassName(MIUI_SECURITY_CENTER_PACKAGE, MIUI_PERMISSION_EDITOR_ACTIVITY)
            .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
    }

    private fun isXiaomiDevice(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)
    }
}
