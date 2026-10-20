/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.components

import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.fullscreenintent.api.MissingCallPermission
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReachabilityReadinessTest {
    @Test
    fun `missing channel and absent adapter never become a denied permission`() {
        val context = RuntimeEnvironment.getApplication()
        assertThat(context.channelReadiness(null)).isEqualTo(ReachabilityReadiness.Unknown)
        assertThat(context.channelReadiness("not-created")).isEqualTo(ReachabilityReadiness.Unknown)
        assertThat(channelReadiness(NotificationManager.IMPORTANCE_UNSPECIFIED)).isEqualTo(ReachabilityReadiness.Unknown)
    }

    @Test
    fun `only channel used for delivery affects the reminder`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("legacy", "Old", NotificationManager.IMPORTANCE_NONE))
        manager.createNotificationChannel(NotificationChannel("active", "Messages", NotificationManager.IMPORTANCE_HIGH))
        assertThat(context.channelReadiness("active")).isEqualTo(ReachabilityReadiness.Ready)
        assertThat(context.channelReadiness("legacy")).isEqualTo(ReachabilityReadiness.NeedsUser)
    }

    @Test
    fun `quiet channel needs user choice and is not silently rewritten`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("quiet", "Quiet", NotificationManager.IMPORTANCE_LOW))
        assertThat(context.channelReadiness("quiet")).isEqualTo(ReachabilityReadiness.NeedsUser)
        assertThat(manager.getNotificationChannel("quiet").importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun `ready unknown unsupported and dismissed steps never demand confirmation`() {
        ReachabilityReadiness.entries.forEach {
            assertThat(shouldGuide(it, deferred = true)).isFalse()
            assertThat(shouldGuide(it, deferred = false)).isEqualTo(it == ReachabilityReadiness.NeedsUser)
        }
    }

    @Test
    fun `defer persists and manual review can reenable a genuinely missing reminder`() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("readiness-test", 0)
        preferences.edit().putStringSet("deferred_v2", setOf("NOTIFICATIONS")).commit()
        val reopened = RuntimeEnvironment.getApplication().getSharedPreferences("readiness-test", 0)
        assertThat(shouldGuide(ReachabilityReadiness.NeedsUser, "NOTIFICATIONS" in reopened.getStringSet("deferred_v2", emptySet())!!)).isFalse()
        reopened.edit().remove("deferred_v2").commit()
        assertThat(shouldGuide(ReachabilityReadiness.NeedsUser, "NOTIFICATIONS" in reopened.getStringSet("deferred_v2", emptySet())!!)).isTrue()
    }

    @Test
    fun `optional dnd no longer blocks home but real call restrictions remain actionable`() {
        fun state(missing: MissingCallPermission?) = FullScreenIntentPermissionsState(
            permissionGranted = false, doNotDisturbAccessGranted = false, missingPermission = missing,
            shouldDisplayBanner = true, eventSink = {},
        )
        assertThat(state(MissingCallPermission.DoNotDisturbAccess).shouldDisplayHomeBanner).isFalse()
        assertThat(state(null).shouldDisplayHomeBanner).isFalse()
        assertThat(state(MissingCallPermission.FullScreenIntent).shouldDisplayHomeBanner).isTrue()
        assertThat(state(MissingCallPermission.BackgroundActivity).shouldDisplayHomeBanner).isTrue()
    }

    @Test
    @Config(sdk = [24])
    fun `devices without notification channels do not receive channel setup prompts`() {
        assertThat(RuntimeEnvironment.getApplication().channelReadiness("unused")).isEqualTo(ReachabilityReadiness.Unsupported)
    }
}
