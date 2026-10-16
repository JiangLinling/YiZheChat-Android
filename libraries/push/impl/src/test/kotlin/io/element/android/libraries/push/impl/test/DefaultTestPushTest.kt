/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.test

import io.element.android.appconfig.PushConfig
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.push.impl.troubleshoot.DiagnosticPushHandler
import io.element.android.libraries.push.impl.pushgateway.PushGatewayNotifyRequest
import io.element.android.libraries.pushproviders.test.aSessionPushConfig
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultTestPushTest {
    @Test
    fun `each explicit test owns a unique delivery with the same gateway and registration`() = runTest {
        val requests = mutableListOf<PushGatewayNotifyRequest.Params>()
        val handler = DiagnosticPushHandler()
        val defaultTestPush = DefaultTestPush(
            pushGatewayNotifyRequest = FakePushGatewayNotifyRequest(
                executeResult = { requests.add(it) },
            ),
            diagnosticPushHandler = handler,
        )
        val aConfig = aSessionPushConfig()
        defaultTestPush.execute(aConfig)
        defaultTestPush.execute(aConfig)
        assertThat(requests).hasSize(2)
        assertThat(requests[0].eventId).isNotEqualTo(requests[1].eventId)
        assertThat(handler.isCurrentTest(requests[0].eventId)).isFalse()
        assertThat(handler.isCurrentTest(requests[1].eventId)).isTrue()
        requests.forEach {
            assertThat(it.url).isEqualTo(aConfig.url)
            assertThat(it.appId).isEqualTo(PushConfig.PUSHER_APP_ID)
            assertThat(it.pushKey).isEqualTo(aConfig.pushKey)
            assertThat(it.roomId).isEqualTo(DefaultTestPush.TEST_ROOM_ID)
            assertThat(DiagnosticPushHandler.isDiagnosticEvent(it.eventId)).isTrue()
        }
    }
}
