/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageNotificationSoundPlayerTest {
    @Test
    fun `Xiaomi Redmi and POCO devices use the managed fallback`() {
        assertThat(isXiaomiFamilyDevice("Xiaomi", "Xiaomi")).isTrue()
        assertThat(isXiaomiFamilyDevice("unknown", "Redmi")).isTrue()
        assertThat(isXiaomiFamilyDevice("unknown", "POCO")).isTrue()
    }

    @Test
    fun `other manufacturers keep the standard notification sound path`() {
        assertThat(isXiaomiFamilyDevice("HUAWEI", "HUAWEI")).isFalse()
        assertThat(isXiaomiFamilyDevice("vivo", "vivo")).isFalse()
        assertThat(isXiaomiFamilyDevice("OPPO", "OPPO")).isFalse()
        assertThat(isXiaomiFamilyDevice("samsung", "samsung")).isFalse()
    }

    @Test
    fun `audible child message is eligible`() {
        assertThat(shouldPlayManagedMessageAlert()).isTrue()
    }

    @Test
    fun `summary and non-message notifications are not eligible`() {
        assertThat(shouldPlayManagedMessageAlert(flags = Notification.FLAG_GROUP_SUMMARY)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(category = Notification.CATEGORY_STATUS)).isFalse()
    }

    @Test
    fun `user and system sound restrictions are respected`() {
        assertThat(shouldPlayManagedMessageAlert(notificationsEnabled = false)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(ringerMode = AudioManager.RINGER_MODE_SILENT)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(notificationVolume = 0)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(channelImportance = NotificationManager.IMPORTANCE_LOW)).isFalse()
        assertThat(shouldPlayManagedMessageAlert(hasSound = false)).isFalse()
    }

    private fun shouldPlayManagedMessageAlert(
        notificationsEnabled: Boolean = true,
        category: String? = Notification.CATEGORY_MESSAGE,
        flags: Int = 0,
        interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
        ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        notificationVolume: Int = 10,
        channelImportance: Int = NotificationManager.IMPORTANCE_HIGH,
        hasSound: Boolean = true,
    ): Boolean = io.element.android.libraries.push.impl.notifications.shouldPlayManagedMessageAlert(
        notificationsEnabled = notificationsEnabled,
        category = category,
        flags = flags,
        interruptionFilter = interruptionFilter,
        ringerMode = ringerMode,
        notificationVolume = notificationVolume,
        channelImportance = channelImportance,
        hasSound = hasSound,
    )
}
