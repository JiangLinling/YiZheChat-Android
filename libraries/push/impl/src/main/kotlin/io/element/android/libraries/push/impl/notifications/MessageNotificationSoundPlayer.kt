/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationManagerCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber

/**
 * Plays a single message alert on Xiaomi-family devices where HyperOS/MIUI accepts a noisy
 * notification but does not ask the audio service to play its channel sound.
 *
 * Huawei foreground banners also use this player while their system history record is silent. All of the
 * user's channel, DND, ringer-mode and notification-volume choices are checked before falling back.
 */
interface MessageNotificationSoundPlayer {
    fun playIfNeeded(notification: Notification)
    fun playForegroundAlert(notification: Notification) = Unit
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMessageNotificationSoundPlayer(
    @ApplicationContext private val context: Context,
) : MessageNotificationSoundPlayer {
    private var ringtone: Ringtone? = null

    override fun playIfNeeded(notification: Notification) {
        if (!isXiaomiFamilyDevice(Build.MANUFACTURER, Build.BRAND)) return
        playManagedAlert(notification)
    }

    override fun playForegroundAlert(notification: Notification) {
        if (!isHuaweiNotificationDevice(Build.MANUFACTURER, Build.BRAND)) return
        if (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        playManagedAlert(notification)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId) ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            audio.ringerMode != AudioManager.RINGER_MODE_SILENT &&
            channel.importance >= NotificationManager.IMPORTANCE_HIGH && channel.shouldVibrate()
        ) {
            runCatchingExceptions {
                context.getSystemService(Vibrator::class.java)?.vibrate(
                    VibrationEffect.createWaveform(channel.vibrationPattern ?: longArrayOf(0, 350, 250, 350), -1),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            }.onFailure { Timber.w("Unable to vibrate for foreground notification") }
        }
    }

    private fun playManagedAlert(notification: Notification) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(notification.channelId)
        } else {
            null
        }
        val soundUri = channel?.sound ?: notification.sound
        val channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT

        val shouldPlay = shouldPlayManagedMessageAlert(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            category = notification.category,
            flags = notification.flags,
            interruptionFilter = notificationManager.currentInterruptionFilter,
            ringerMode = audioManager.ringerMode,
            notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
            channelImportance = channelImportance,
            hasSound = soundUri != null,
        )
        if (!shouldPlay || soundUri == null) return

        stop()
        runCatchingExceptions {
            RingtoneManager.getRingtone(context, soundUri)?.also { messageRingtone ->
                messageRingtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    messageRingtone.isLooping = false
                }
                messageRingtone.play()
                ringtone = messageRingtone
                Timber.d("Playing managed message notification sound")
            }
        }.onFailure {
            Timber.e(it, "Failed to play managed message notification sound")
        }
    }

    private fun stop() {
        runCatchingExceptions {
            ringtone?.stop()
        }.onFailure {
            Timber.e(it, "Failed to stop previous managed message notification sound")
        }
        ringtone = null
    }
}

internal fun isXiaomiFamilyDevice(manufacturer: String, brand: String): Boolean {
    return manufacturer.equals("Xiaomi", ignoreCase = true) ||
        brand.equals("Xiaomi", ignoreCase = true) ||
        brand.equals("Redmi", ignoreCase = true) ||
        brand.equals("POCO", ignoreCase = true)
}

internal fun shouldPlayManagedMessageAlert(
    notificationsEnabled: Boolean,
    category: String?,
    flags: Int,
    interruptionFilter: Int,
    ringerMode: Int,
    notificationVolume: Int,
    channelImportance: Int,
    hasSound: Boolean,
): Boolean {
    return notificationsEnabled &&
        category == Notification.CATEGORY_MESSAGE &&
        flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
        ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        notificationVolume > 0 &&
        channelImportance >= NotificationManager.IMPORTANCE_DEFAULT &&
        hasSound
}
