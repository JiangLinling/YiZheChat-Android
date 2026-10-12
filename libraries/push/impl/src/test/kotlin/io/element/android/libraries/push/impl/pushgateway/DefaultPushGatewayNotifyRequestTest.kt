/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.pushgateway

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.push.api.gateway.PushGatewayFailure
import io.element.android.libraries.push.impl.test.DefaultTestPush
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DefaultPushGatewayNotifyRequestTest {
    @Test
    fun `notify success`() = runTest {
        val factory = FakePushGatewayApiFactory(
            notifyResponse = {
                PushGatewayNotifyResponse(
                    rejectedPushKeys = emptyList()
                )
            }
        )
        val pushGatewayNotifyRequest = DefaultPushGatewayNotifyRequest(
            pushGatewayApiFactory = factory,
        )
        pushGatewayNotifyRequest.execute(
            PushGatewayNotifyRequest.Params(
                url = "aUrl",
                appId = "anAppId",
                pushKey = "aPushKey",
                eventId = DefaultTestPush.TEST_EVENT_ID,
                roomId = DefaultTestPush.TEST_ROOM_ID,
            )
        )
        assertThat(factory.baseUrlParameter).isEqualTo("aUrl")
        assertThat(factory.notifyUrlParameter).isEqualTo("_matrix/push/v1/notify")
    }

    @Test
    fun `notify success, url is stripped`() = runTest {
        val factory = FakePushGatewayApiFactory(
            notifyResponse = {
                PushGatewayNotifyResponse(
                    rejectedPushKeys = emptyList()
                )
            }
        )
        val pushGatewayNotifyRequest = DefaultPushGatewayNotifyRequest(
            pushGatewayApiFactory = factory,
        )
        pushGatewayNotifyRequest.execute(
            PushGatewayNotifyRequest.Params(
                url = "aUrl" + PushGatewayConfig.URI_PUSH_GATEWAY_PREFIX_PATH,
                appId = "anAppId",
                pushKey = "aPushKey",
                eventId = DefaultTestPush.TEST_EVENT_ID,
                roomId = DefaultTestPush.TEST_ROOM_ID,
            )
        )
        assertThat(factory.baseUrlParameter).isEqualTo("aUrl")
        assertThat(factory.notifyUrlParameter).isEqualTo("_matrix/push/v1/notify")
    }

    @Test
    fun `notify preserves endpoint query parameters`() = runTest {
        val factory = FakePushGatewayApiFactory(
            notifyResponse = {
                PushGatewayNotifyResponse(rejectedPushKeys = emptyList())
            }
        )
        val pushGatewayNotifyRequest = DefaultPushGatewayNotifyRequest(
            pushGatewayApiFactory = factory,
        )
        val endpoint = "https://next.example.org/push/registrar/_matrix/push/v1/notify?provider=coordinator"

        pushGatewayNotifyRequest.execute(
            PushGatewayNotifyRequest.Params(
                url = endpoint,
                appId = "anAppId",
                pushKey = "aPushKey",
                eventId = DefaultTestPush.TEST_EVENT_ID,
                roomId = DefaultTestPush.TEST_ROOM_ID,
            )
        )

        assertThat(factory.baseUrlParameter).isEqualTo("https://next.example.org/push/registrar/")
        assertThat(factory.notifyUrlParameter).isEqualTo("_matrix/push/v1/notify?provider=coordinator")
    }

    @Test
    fun `notify sends standard path when origin has no trailing slash`() = runTest {
        val server = MockWebServer()
        server.enqueue(matrixGatewayResponse())
        server.start()
        try {
            val originWithoutSlash = server.url("/").toString().removeSuffix("/")
            executeRealRequest(originWithoutSlash)

            assertThat(server.takeRequest().path).isEqualTo("/_matrix/push/v1/notify")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `notify sends proxy prefix and coordinator query over HTTP`() = runTest {
        val server = MockWebServer()
        server.enqueue(matrixGatewayResponse())
        server.start()
        try {
            val endpoint = server.url(
                "/push/registrar/_matrix/push/v1/notify?provider=coordinator"
            ).toString()
            executeRealRequest(endpoint)

            assertThat(server.takeRequest().path)
                .isEqualTo("/push/registrar/_matrix/push/v1/notify?provider=coordinator")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `notify with rejected push key should throw expected Exception`() {
        val factory = FakePushGatewayApiFactory(
            notifyResponse = {
                PushGatewayNotifyResponse(
                    rejectedPushKeys = listOf("aPushKey")
                )
            }
        )
        val pushGatewayNotifyRequest = DefaultPushGatewayNotifyRequest(
            pushGatewayApiFactory = factory,
        )
        assertThrows(PushGatewayFailure.PusherRejected::class.java) {
            runTest {
                pushGatewayNotifyRequest.execute(
                    PushGatewayNotifyRequest.Params(
                        url = "aUrl",
                        appId = "anAppId",
                        pushKey = "aPushKey",
                        eventId = DefaultTestPush.TEST_EVENT_ID,
                        roomId = DefaultTestPush.TEST_ROOM_ID,
                    )
                )
            }
        }
        assertThat(factory.baseUrlParameter).isEqualTo("aUrl")
        assertThat(factory.notifyUrlParameter).isEqualTo("_matrix/push/v1/notify")
    }

    private suspend fun executeRealRequest(url: String) {
        val factory = object : PushGatewayApiFactory {
            override fun create(baseUrl: String): PushGatewayAPI {
                val normalizedBaseUrl = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
                return Retrofit.Builder()
                    .baseUrl(normalizedBaseUrl)
                    .addConverterFactory(
                        Json.asConverterFactory("application/json".toMediaType())
                    )
                    .build()
                    .create(PushGatewayAPI::class.java)
            }
        }
        DefaultPushGatewayNotifyRequest(factory).execute(
            PushGatewayNotifyRequest.Params(
                url = url,
                appId = "anAppId",
                pushKey = "aPushKey",
                eventId = DefaultTestPush.TEST_EVENT_ID,
                roomId = DefaultTestPush.TEST_ROOM_ID,
            )
        )
    }

    private fun matrixGatewayResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"rejected":[]}""")
}
