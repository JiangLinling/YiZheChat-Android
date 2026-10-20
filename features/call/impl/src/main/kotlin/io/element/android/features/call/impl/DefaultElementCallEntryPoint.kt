/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.ElementCallEntryPoint
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.IntentProvider
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.libraries.di.annotations.AppCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

@ContributesBinding(AppScope::class)
class DefaultElementCallEntryPoint(
    @ApplicationContext private val context: Context,
    private val activeCallManager: ActiveCallManager,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) : ElementCallEntryPoint {
    companion object {
        const val EXTRA_CALL_TYPE = "EXTRA_CALL_TYPE"
        const val REQUEST_CODE = 2255
    }

    private val roomEntry = Mutex()

    override fun startCall(callData: CallData) {
        // The room Join button must accept the same invitation as the full-screen
        // answer button. A fresh attempt here replaces the ringing generation.
        if (!io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
            context.startActivity(IntentProvider.createIntent(context, callData))
            return
        }
        if (!roomEntry.tryLock()) return
        coroutineScope.launch {
            try {
                val target = activeCallManager.resolveRoomEntry(callData)
                context.startActivity(IntentProvider.createIntent(context, target))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                timber.log.Timber.w(failure, "Unable to resolve room call entry")
                android.widget.Toast.makeText(context, R.string.call_connection_failed, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                roomEntry.unlock()
            }
        }
    }

    override suspend fun handleIncomingCall(
        callData: CallData,
        eventId: EventId,
        senderId: UserId,
        roomName: String?,
        senderName: String?,
        avatarUrl: String?,
        timestamp: Long,
        expirationTimestamp: Long,
        notificationChannelId: String,
        textContent: String?,
    ) {
        val hideIdentity = !io.element.android.appconfig.NextDeploymentConfig.isNextDeployment &&
            dataProtectionPolicyProvider.current().policy.notificationExposure == NotificationExposure.HIDDEN
        val incomingCallNotificationData = CallNotificationData(
            sessionId = callData.sessionId,
            roomId = callData.roomId,
            eventId = eventId,
            senderId = senderId,
            roomName = roomName.takeUnless { hideIdentity },
            senderName = if (hideIdentity) context.getString(R.string.call_private_caller_name) else senderName,
            avatarUrl = avatarUrl.takeUnless { hideIdentity },
            timestamp = timestamp,
            expirationTimestamp = expirationTimestamp,
            notificationChannelId = notificationChannelId,
            textContent = textContent.takeUnless { hideIdentity },
            audioOnly = callData.isAudioCall,
        )
        activeCallManager.registerIncomingCall(notificationData = incomingCallNotificationData)
    }
}
