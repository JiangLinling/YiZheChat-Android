/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.androidutils.background

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
class XiaomiAutostartTest {
    @Test
    fun `only an explicit OS allow result is granted`() {
        assertThat(autostartStateFromAppOp(AppOpsManager.MODE_ALLOWED)).isEqualTo(AutostartPermissionState.ALLOWED)
        assertThat(autostartStateFromAppOp(AppOpsManager.MODE_IGNORED)).isEqualTo(AutostartPermissionState.BLOCKED)
        assertThat(autostartStateFromAppOp(AppOpsManager.MODE_ERRORED)).isEqualTo(AutostartPermissionState.BLOCKED)
        listOf(null, AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND, 10008).forEach { mode ->
            assertThat(autostartStateFromAppOp(mode)).isEqualTo(AutostartPermissionState.UNKNOWN)
        }
    }

    @Test
    fun `Xiaomi settings opens the current package instead of a global app list`() {
        ShadowBuild.setManufacturer("Xiaomi")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = context.xiaomiApplicationDetailsSettingsIntent()
        assertThat(intent?.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent?.data?.schemeSpecificPart).isEqualTo(context.packageName)
        assertThat(intent?.flags?.and(Intent.FLAG_ACTIVITY_CLEAR_TOP)).isEqualTo(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Test
    fun `unrelated OEMs do not use Xiaomi permission results or settings`() {
        ShadowBuild.setManufacturer("Samsung")
        ShadowBuild.setBrand("Samsung")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.xiaomiAutostartPermissionState()).isEqualTo(AutostartPermissionState.UNKNOWN)
        assertThat(context.xiaomiApplicationDetailsSettingsIntent()).isNull()
    }
}
