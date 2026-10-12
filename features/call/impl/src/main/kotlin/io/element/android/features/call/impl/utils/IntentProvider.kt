/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.PendingIntentCompat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.DefaultElementCallEntryPoint
import io.element.android.features.call.impl.ui.ElementCallActivity

internal object IntentProvider {
    fun forRoomEntry(requested: CallData, active: CallData?): CallData =
        if (requested.notificationEventId == null && requested.attemptId == null &&
            active?.sessionId == requested.sessionId && active.roomId == requested.roomId
        ) active else requested

    fun canOpenInvitation(requested: CallData, active: CallData?): Boolean = requested.notificationEventId == null ||
        (requested.sessionId == active?.sessionId && requested.roomId == active.roomId &&
            requested.notificationEventId == active.notificationEventId)

    fun createIntent(context: Context, callData: CallData): Intent = Intent(context, ElementCallActivity::class.java).apply {
        putExtra(DefaultElementCallEntryPoint.EXTRA_CALL_TYPE,
            if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && callData.attemptId == null)
                callData.copy(attemptId = callData.notificationEventId ?: java.util.UUID.randomUUID().toString())
            else callData,
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    fun getPendingIntent(context: Context, callData: CallData): PendingIntent {
        return PendingIntentCompat.getActivity(
            context,
            DefaultElementCallEntryPoint.REQUEST_CODE,
            createIntent(context, callData).apply {
                data = android.net.Uri.parse("yizhe-call:invitation/" + android.net.Uri.encode(callData.notificationEventId ?: callData.roomId.value))
            },
            PendingIntent.FLAG_CANCEL_CURRENT,
            false
        )!!
    }
}
