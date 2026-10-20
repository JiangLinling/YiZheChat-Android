/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl.components

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupAccelerationSettingsTest {
    private val service = ComponentName("com.yizhe.chat.next.debug", "io.element.android.x.backgroundtest.BackgroundBindingTestService")

    @Test
    fun `every brand including unknown ends with unmodified accessibility home`() {
        for (family in DeviceFamily.entries) {
            val route = startupAccelerationSettingsRoutes(family, service, "Startup acceleration").last()
            assertThat(route.destination).isEqualTo(StartupSettingsDestination.AccessibilityHome)
            assertThat(route.intent.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            assertThat(route.intent.extras).isNull()
            assertThat(route.intent.component).isNull()
            assertThat(route.intent.`package`).isNull()
        }
    }

    @Test
    fun `generic details comes first and xiaomi fallback never leaks to other brands`() {
        for (family in DeviceFamily.entries) {
            val routes = startupAccelerationSettingsRoutes(family, service, "Startup acceleration")
            assertThat(routes.first().intent.action).isEqualTo("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            val fragments = routes.filter { it.intent.hasExtra(":settings:show_fragment") }
            assertThat(fragments.size).isEqualTo(if (family == DeviceFamily.XIAOMI) 1 else 0)
            if (family == DeviceFamily.XIAOMI) assertThat(routes[1]).isEqualTo(fragments.single())
        }
    }

    @Test
    fun `samsung gets installed services before general accessibility settings`() {
        val routes = startupAccelerationSettingsRoutes(DeviceFamily.SAMSUNG, service, "Startup acceleration")
        val installed = routes[1]
        assertThat(installed.destination).isEqualTo(StartupSettingsDestination.ServiceList)
        assertThat(installed.intent.action).isEqualTo("com.samsung.accessibility.installed_service")
        assertThat(installed.intent.`package`).isEqualTo("com.android.settings")
        for (family in DeviceFamily.entries.filterNot { it == DeviceFamily.SAMSUNG }) {
            assertThat(startupAccelerationSettingsRoutes(family, service, "Startup acceleration")
                .any { it.intent.action == installed.intent.action }).isFalse()
        }
    }

    @Test
    fun `service highlighting always uses exact installed flavor identity`() {
        for (packageName in listOf("com.yizhe.chat.next.debug", "com.yizhe.chat.debug")) {
            val component = ComponentName(packageName, service.className)
            val routes = startupAccelerationSettingsRoutes(DeviceFamily.SAMSUNG, component, "Startup acceleration")
            for (route in routes.filter { it.destination == StartupSettingsDestination.ServiceList }) {
                val key = component.flattenToString()
                assertThat(route.intent.getStringExtra(":settings:fragment_args_key")).isEqualTo(key)
                assertThat(route.intent.getBundleExtra(":settings:show_fragment_args")
                    ?.getString(":settings:fragment_args_key")).isEqualTo(key)
            }
        }
    }

    @Test
    fun `system-only details missing vendor activity and rejected extras reach plain home`() {
        val attempted = mutableListOf<Intent>()
        val result = launchStartupAccelerationSettings(
            startupAccelerationSettingsRoutes(DeviceFamily.SAMSUNG, service, "Startup acceleration"),
        ) {
            attempted += it
            when (attempted.size) {
                1 -> throw SecurityException("system callers only")
                2 -> throw ActivityNotFoundException("not on this One UI version")
                3 -> throw IllegalArgumentException("unsupported extra")
            }
        }
        assertThat(result).isEqualTo(StartupSettingsDestination.AccessibilityHome)
        assertThat(attempted).hasSize(4)
        assertThat(attempted.last().extras).isNull()
        assertThat(attempted.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 }).isTrue()
    }

    @Test
    fun `successful dispatch stops attempts without treating it as authorization`() {
        var launches = 0
        val result = launchStartupAccelerationSettings(
            startupAccelerationSettingsRoutes(DeviceFamily.XIAOMI, service, "Startup acceleration"),
        ) { launches++ }
        assertThat(launches).isEqualTo(1)
        assertThat(result).isEqualTo(StartupSettingsDestination.ServiceDetails)
        assertThat(startupAccelerationState(true, false, false)).isEqualTo(StartupAccelerationState.Disabled)
    }

    @Test
    fun `manual fallback skips every direct and highlight route for all brands`() {
        for (family in DeviceFamily.entries) {
            val routes = startupAccelerationSettingsRoutes(family, service, "Startup acceleration", overviewOnly = true)
            assertThat(routes).hasSize(1)
            assertThat(routes.single().intent.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            assertThat(routes.single().intent.extras).isNull()
        }
    }

    @Test
    fun `missing settings application returns failure without crashing or looping`() {
        var launches = 0
        val result = launchStartupAccelerationSettings(
            startupAccelerationSettingsRoutes(DeviceFamily.GENERIC, service, "Startup acceleration"),
        ) {
            launches++
            throw ActivityNotFoundException()
        }
        assertThat(result).isNull()
        assertThat(launches).isEqualTo(3)
    }
}
