/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.push.api.notifications.ForegroundServiceType
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import timber.log.Timber

/**
 * Owns the high-priority incoming call notification while a call is ringing.
 *
 * Android may show its full-screen intent while the device is not in use, or a persistent heads-up call UI
 * while the user is active. Posting it through a foreground service is required on affected Android 15 OEM builds.
 */
class IncomingCallForegroundService : Service() {
    companion object {
        private const val EXTRA_NOTIFICATION = "EXTRA_NOTIFICATION"

        fun start(context: Context, notification: Notification) {
            val intent = Intent(context, IncomingCallForegroundService::class.java)
                .putExtra(EXTRA_NOTIFICATION, notification)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IncomingCallForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java)
        } ?: run {
            Timber.e("Incoming call foreground service started without a notification")
            stopSelf()
            return START_NOT_STICKY
        }
        val notificationId = NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.INCOMING_CALL)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            0
        }
        runCatchingExceptions {
            ServiceCompat.startForeground(this, notificationId, notification, serviceType)
            Timber.d("Incoming call foreground service is displaying the call notification")
        }.onFailure {
            Timber.e(it, "Failed to start incoming call foreground service")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
