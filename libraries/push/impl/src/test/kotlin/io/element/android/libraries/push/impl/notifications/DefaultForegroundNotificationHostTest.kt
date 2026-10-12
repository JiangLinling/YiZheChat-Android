/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.impl.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.push.impl.notifications.factories.DefaultNotificationCreator
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class DefaultForegroundNotificationHostTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val host = DefaultForegroundNotificationHost(context)

    @Before
    fun setup() {
        ShadowBuild.setManufacturer("HUAWEI")
        ShadowBuild.setBrand("HUAWEI")
        manager.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        host.setResumed(true)
        host.setMounted(true)
    }

    @Test
    fun `foreground ntfy shows sanitized message and keeps silent history with original identity`() {
        val original = notification()
        val history = host.prepare("session", 1, original)!!
        assertThat(history.channelId).isEqualTo(DefaultForegroundNotificationHost.HISTORY_CHANNEL)
        assertThat(host.current.value!!.notification).isSameInstanceAs(original)
        assertThat(host.current.value!!.tag).isEqualTo("session")
        assertThat(host.current.value!!.id).isEqualTo(1)
        assertThat(original.channelId).isEqualTo("messages")
        assertThat(manager.getNotificationChannel(history.channelId).sound).isNull()
    }

    @Test
    fun `vendor deliveries and non Huawei devices keep system notifications`() {
        assertThat(host.prepare("s", 1, notification(ntfy = false))).isNull()
        ShadowBuild.setManufacturer("Xiaomi")
        ShadowBuild.setBrand("Redmi")
        assertThat(host.prepare("s", 1, notification())).isNull()
        assertThat(host.current.value).isNull()
    }

    @Test
    fun `background and locked host do not intercept`() {
        host.setResumed(false)
        assertThat(host.prepare("s", 1, notification())).isNull()
        host.setResumed(true)
        host.setMounted(false)
        assertThat(host.prepare("s", 1, notification())).isNull()
    }

    @Test
    fun `do not disturb muted channels and summaries do not show banner`() {
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertThat(host.prepare("s", 1, notification())).isNull()
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        manager.createNotificationChannel(NotificationChannel("quiet", "Quiet", NotificationManager.IMPORTANCE_LOW))
        assertThat(host.prepare("s", 1, NotificationCompat.Builder(context, notification()).setChannelId("quiet").build())).isNull()
        assertThat(host.prepare("s", 1, NotificationCompat.Builder(context, notification()).setGroupSummary(true).build())).isNull()
    }

    @Test
    fun `duplicate update does not restart banner and older timeout cannot dismiss newer message`() {
        host.prepare("s", 1, notification())
        val first = host.current.value!!
        host.prepare("s", 1, notification())
        assertThat(host.current.value).isSameInstanceAs(first)
        host.prepare("s", 1, notification(eventId = "new"))
        host.dismiss(first.sequence)
        assertThat(host.current.value!!.eventKey).isEqualTo("new")
    }

    @Test
    fun `room cancellation and pause clear banner without affecting another session`() {
        host.prepare("s", 1, notification())
        host.cancel("other", 1)
        assertThat(host.current.value).isNotNull()
        host.cancel("s", 1)
        assertThat(host.current.value).isNull()
        host.prepare("s", 1, notification())
        host.setResumed(false)
        assertThat(host.current.value).isNull()
    }

    @Test
    fun `foreground Huawei call is presented in app and can be cleared`() {
        val call = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentTitle("Private caller")
            .build()
        assertThat(host.showIncomingCall(7, call)).isTrue()
        assertThat(host.currentCall.value?.notification).isSameInstanceAs(call)
        host.clearIncomingCall(7)
        assertThat(host.currentCall.value).isNull()
    }

    @Test
    fun `background call stays on system notification path`() {
        host.setResumed(false)
        val call = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        assertThat(host.showIncomingCall(7, call)).isFalse()
        assertThat(host.currentCall.value).isNull()
    }

    private fun notification(ntfy: Boolean = true, eventId: String = "event"): Notification {
        val message = NotificationCompat.MessagingStyle.Message("New message", 1L, Person.Builder().setName("Hidden sender").build())
        message.extras.putString(DefaultNotificationCreator.MESSAGE_EVENT_ID, eventId)
        return NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup("session")
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Current user").build()).addMessage(message))
            .addExtras(Bundle().apply { putBoolean(DefaultNotificationCreator.EXTRA_NTFY_DELIVERY, ntfy) })
            .build()
    }
}
