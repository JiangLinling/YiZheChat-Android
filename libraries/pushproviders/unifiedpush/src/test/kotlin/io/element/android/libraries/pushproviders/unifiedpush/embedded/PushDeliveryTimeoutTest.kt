/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PushDeliveryTimeoutTest {
    @Test
    fun `committed receipt slower than old budget is accepted by delivery transport`() {
        fun request(client: OkHttpClient): Result<Int> = ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true, name = "qa-delayed-push-receipt") {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* Read only synthetic request headers. */ }
                    Thread.sleep(1_800)
                    runCatching {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                }
            }
            try {
                runCatching {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/qa-receipt").build())
                        .execute().use { it.code }
                }
            } finally {
                worker.join(5_000)
                client.connectionPool.evictAll()
            }
        }
        assertThat(request(OkHttpClient.Builder().callTimeout(1_500, TimeUnit.MILLISECONDS).build()).isFailure).isTrue()
        assertThat(request(createPushDeliveryHttpClient()).getOrThrow()).isEqualTo(200)
    }
}
