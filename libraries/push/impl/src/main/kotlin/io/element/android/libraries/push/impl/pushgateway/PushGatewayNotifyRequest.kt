/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.pushgateway

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.push.api.gateway.PushGatewayFailure

interface PushGatewayNotifyRequest {
    data class Params(
        val url: String,
        val appId: String,
        val pushKey: String,
        val eventId: EventId,
        val roomId: RoomId,
    )

    suspend fun execute(params: Params)
}

@ContributesBinding(AppScope::class)
class DefaultPushGatewayNotifyRequest(
    private val pushGatewayApiFactory: PushGatewayApiFactory,
) : PushGatewayNotifyRequest {
    override suspend fun execute(params: PushGatewayNotifyRequest.Params) {
        val notifyPath = PushGatewayConfig.URI_PUSH_GATEWAY_PREFIX_PATH + "notify"
        val notifyPathIndex = params.url.indexOf(notifyPath)
        val hasExactNotifyEndpoint = notifyPathIndex >= 0 &&
            params.url.getOrNull(notifyPathIndex + notifyPath.length) in setOf(null, '?')
        val gatewayBaseUrl: String
        val notifyUrl: String
        if (hasExactNotifyEndpoint) {
            gatewayBaseUrl = params.url.substring(0, notifyPathIndex)
            notifyUrl = params.url.substring(notifyPathIndex)
        } else {
            gatewayBaseUrl = params.url.substringBefore(PushGatewayConfig.URI_PUSH_GATEWAY_PREFIX_PATH)
            notifyUrl = notifyPath
        }
        val pushGatewayApi = pushGatewayApiFactory.create(gatewayBaseUrl)
        val response = pushGatewayApi.notify(
            notifyUrl,
            PushGatewayNotifyBody(
                PushGatewayNotification(
                    eventId = params.eventId.value,
                    roomId = params.roomId.value,
                    devices = listOf(
                        PushGatewayDevice(
                            params.appId,
                            params.pushKey,
                            PusherData(mapOf(
                                "cs" to "A_FAKE_SECRET",
                            ))
                        )
                    ),
                )
            )
        )

        if (response.rejectedPushKeys.contains(params.pushKey)) {
            throw PushGatewayFailure.PusherRejected()
        }
    }
}
