/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.services

import android.app.Notification
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext

interface IncomingCallForegroundServiceController {
    fun start(notification: Notification)
    fun stop()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultIncomingCallForegroundServiceController(
    @ApplicationContext private val context: Context,
) : IncomingCallForegroundServiceController {
    override fun start(notification: Notification) {
        IncomingCallForegroundService.start(context, notification)
    }

    override fun stop() {
        IncomingCallForegroundService.stop(context)
    }
}
