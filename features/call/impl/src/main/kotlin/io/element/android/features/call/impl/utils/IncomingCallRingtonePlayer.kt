/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.features.call.impl.utils

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber

interface IncomingCallRingtonePlayer {
    fun start(notificationChannelId: String)
    fun stop()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultIncomingCallRingtonePlayer(
    @ApplicationContext private val context: Context,
) : IncomingCallRingtonePlayer {
    private var ringtone: Ringtone? = null

    override fun start(notificationChannelId: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val isDoNotDisturbActive = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (!isDoNotDisturbActive && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(notificationChannelId)
        } else {
            null
        }
        if (channel != null && channel.sound == null) {
            Timber.d("Incoming call ringtone is disabled by the notification channel")
            return
        }
        val ringtoneUri = channel?.sound ?: bundledCallRingtoneUri()

        stop()
        runCatchingExceptions {
            RingtoneManager.getRingtone(
                context,
                ringtoneUri,
            )?.also { incomingCallRingtone ->
                // Some OEMs post call notifications without starting their channel sound. Own the ringtone
                // lifecycle while the call is ringing; protected calls use the alarm path only during DND.
                incomingCallRingtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(
                        if (isDoNotDisturbActive) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    incomingCallRingtone.isLooping = true
                }
                incomingCallRingtone.play()
                ringtone = incomingCallRingtone
                Timber.d("Playing managed incoming call ringtone; dndActive=$isDoNotDisturbActive")
            }
        }.onFailure {
            Timber.e(it, "Failed to play managed incoming call ringtone")
        }
    }

    override fun stop() {
        runCatchingExceptions {
            ringtone?.stop()
        }.onFailure {
            Timber.e(it, "Failed to stop managed incoming call ringtone")
        }
        ringtone = null
    }

    private fun bundledCallRingtoneUri(): Uri = Uri.parse(
        "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/yizhe_call_ringtone"
    )
}
