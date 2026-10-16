/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.test

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.appconfig.PushConfig
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.push.impl.pushgateway.PushGatewayNotifyRequest
import io.element.android.libraries.push.impl.troubleshoot.DiagnosticPushHandler
import io.element.android.libraries.pushproviders.api.Config

interface TestPush {
    suspend fun execute(config: Config)
}

@ContributesBinding(AppScope::class)
class DefaultTestPush(
    private val pushGatewayNotifyRequest: PushGatewayNotifyRequest,
    private val diagnosticPushHandler: DiagnosticPushHandler,
) : TestPush {
    override suspend fun execute(config: Config) {
        pushGatewayNotifyRequest.execute(
            PushGatewayNotifyRequest.Params(
                url = NextDeploymentConfig.clientReachablePushGatewayUrl(config.url),
                appId = PushConfig.PUSHER_APP_ID,
                pushKey = config.pushKey,
                // A new explicit self-test is a new delivery. Reusing one event ID causes
                // legitimate delivery deduplication/terminal receipts to suppress later tests.
                eventId = diagnosticPushHandler.beginTest(),
                roomId = TEST_ROOM_ID,
            )
        )
    }

    companion object {
        val TEST_EVENT_ID = EventId("\$THIS_IS_A_FAKE_EVENT_ID")
        val TEST_ROOM_ID = RoomId("!room:domain")
    }
}
