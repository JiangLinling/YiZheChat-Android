/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.impl.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.test.FakeLockScreenService
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FloatingMessageBannerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lock = FakeLockScreenService()
    private val banner = FloatingMessageBanner(context, lock, FakeDataProtectionPolicyProvider())
    private val windows: ShadowWindowManagerImpl = Shadow.extract(context.getSystemService(WindowManager::class.java))

    @Before
    fun setup() {
        ShadowSettings.setCanDrawOverlays(true)
        lock.setLockState(LockScreenLockState.Unlocked)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        manager.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test
    fun `missing permission locked screens and do not disturb retain ordinary notifications`() {
        ShadowSettings.setCanDrawOverlays(false)
        assertThat(enqueue()).isFalse()
        ShadowSettings.setCanDrawOverlays(true)
        lock.setLockState(LockScreenLockState.Locked)
        assertThat(enqueue()).isFalse()
        lock.setLockState(LockScreenLockState.Unlocked)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertThat(enqueue()).isFalse()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertThat(enqueue()).isFalse()
    }

    @Test
    fun `cancellation queued before rendering cannot revive a revoked notification`() {
        var shown = 0
        var cancelled = 0
        assertThat(banner.enqueue("room", 1, notification(), { shown++ }, {})).isTrue()
        banner.cancel("room", 1) { cancelled++ }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shown).isEqualTo(0)
        assertThat(cancelled).isEqualTo(1)
        assertThat(windows.views).isEmpty()
    }

    @Test
    fun `locking an already mounted preview removes the window and plaintext children`() {
        var shown = 0
        banner.enqueue("room", 1, notification(), { shown++ }, {})
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shown).isEqualTo(1)
        assertThat(windows.views).hasSize(1)
        lock.setLockState(LockScreenLockState.Locked)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(windows.views).isEmpty()
    }

    private fun enqueue() = banner.enqueue("room", 1, notification(), {}, {})

    private fun notification(): Notification {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, android.app.Activity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, "messages").setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("QA sender").setContentText("Already sanitized preview")
            .addExtras(Bundle().apply { putParcelable(FloatingMessageBanner.FLOATING_INTENT, intent) }).build()
    }
}
