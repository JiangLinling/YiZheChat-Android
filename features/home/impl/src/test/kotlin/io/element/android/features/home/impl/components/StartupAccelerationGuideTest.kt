/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl.components

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupAccelerationGuideTest {
    @Test
    fun `variants without the experimental service never request accessibility`() {
        assertThat(startupAccelerationState(false, false, false).needsReminder).isFalse()
    }

    @Test
    fun `revoked permission wins over a stale running service observation`() {
        assertThat(startupAccelerationState(true, false, true)).isEqualTo(StartupAccelerationState.Disabled)
    }

    @Test
    fun `permission alone cannot complete the guide before service recovery`() {
        assertThat(startupAccelerationState(true, true, false)).isEqualTo(StartupAccelerationState.Recovering)
        assertThat(startupAccelerationState(true, true, null)).isEqualTo(StartupAccelerationState.Unknown)
        assertThat(startupAccelerationState(true, true, true)).isEqualTo(StartupAccelerationState.Ready)
    }

    @Test
    fun `unreadable authorization is unknown rather than disabled or complete`() {
        val state = startupAccelerationState(true, null, true)
        assertThat(state).isEqualTo(StartupAccelerationState.Unknown)
        assertThat(state.needsReminder).isFalse()
    }

    @Test
    fun `settings round trip follows actual state including later revocation`() {
        val reminders = listOf(false to false, true to false, true to true, false to false)
            .map { (authorized, running) -> startupAccelerationState(true, authorized, running).needsReminder }
        assertThat(reminders).containsExactly(true, true, false, true).inOrder()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `detail intent targets exactly the installed package service without forcing a settings activity`() {
        val service = ComponentName("com.yizhe.chat.next.debug", "io.element.android.x.backgroundtest.BackgroundBindingTestService")
        val intent = startupAccelerationSettingsIntent(service)
        assertThat(intent.action).isEqualTo("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        assertThat(intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)).isEqualTo(service)
        assertThat(intent.component).isNull()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `xiaomi entry selects only our service and never changes its authorization`() {
        val service = ComponentName("com.yizhe.chat.next.debug", "io.element.android.x.backgroundtest.BackgroundBindingTestService")
        val intent = startupAccelerationXiaomiSettingsIntent(service, "Startup acceleration")
        assertThat(intent.component).isEqualTo(ComponentName("com.android.settings", "com.android.settings.SubSettings"))
        assertThat(intent.getStringExtra(":settings:show_fragment"))
            .isEqualTo("com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment")
        val args = intent.getBundleExtra(":settings:show_fragment_args")!!
        assertThat(args.getParcelable<ComponentName>("component_name")).isEqualTo(service)
        assertThat(args.getString("preference_key")).isEqualTo(service.flattenToString())
        assertThat(args.keySet()).containsExactly("component_name", "preference_key", "title")
    }
}
