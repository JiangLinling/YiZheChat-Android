/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KmsTransportTimingTest {
    @Test
    fun `server timing rejects missing malformed duplicate and inconsistent durations`() {
        for (value in listOf(
                null,
                "kms_app;dur=NaN",
                "kms_app;dur=1, kms_binding;dur=2",
                "kms_app;dur=1, kms_app;dur=1",
            "kms_app;dur=120001",
                "x".repeat(1025)
            )) {
            assertThat(KmsTransportTrace.parseServerTiming(value)).isEmpty()
        }
        assertThat(KmsTransportTrace.parseServerTiming("kms_app;dur=12.345, kms_binding;dur=2.000, kms_binding_gate;dur=3.000"))
            .containsExactly("kms_app", 12345L, "kms_binding", 2000L, "kms_binding_gate", 3000L)
    }

    @Test
    fun `server detail keeps worker queue and pool inclusive time separate and unknown fields private`() {
        val values = KmsTransportTrace.parseServerTiming(
            "kms_app;dur=40.000, kms_release_worker_queue;dur=20.000, kms_release_worker;dur=10.000, " +
                "kms_db_pool_commit;dur=8.000, private_duration;dur=3.000"
        )
        assertThat(values).containsExactly(
            "kms_app",
            40000L,
            "kms_release_worker_queue",
            20000L,
            "kms_release_worker",
            10000L,
            "kms_db_pool_commit",
            8000L
        )
        assertThat(values).doesNotContainKey("kms_receipt_worker")
        assertThat(KmsTransportTrace.parseServerTiming("kms_app;dur=1.000, kms_release_worker;dur=2.000")).isEmpty()
        assertThat(KmsTransportTrace.parseServerTiming("kms_app;dur=2, kms_prepare;dur=1, kms_prepare;dur=1")).isEmpty()
    }

    @Test
    fun `dispatcher queue is measured separately and concurrent operations keep separate traces`() {
        val lines = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        val server = MockWebServer()
        val arrived = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/first") {
                    arrived.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                }
                return MockResponse().setBody("private-synthetic-body").setHeader(
                    "Server-Timing",
                    "kms_app;dur=1.000, kms_binding;dur=0.000, kms_binding_gate;dur=0.000"
                )
            }
        }
        server.start()
        val dispatcher = Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }
        val client = OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor(KmsTransportTiming.interceptor)
            .eventListenerFactory(KmsTransportTiming.listenerFactory).build()
        val first = KmsTransportTrace("operation-first", "epoch_release")
        val second = KmsTransportTrace("operation-second", "share_release")
        val done = CountDownLatch(2)
        val failures = CopyOnWriteArrayList<IOException>()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failures += e
                done.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { it.body!!.string() }
                done.countDown()
            }
        }
        Timber.plant(tree)
        try {
            client.newCall(Request.Builder().url(server.url("/first")).tag(KmsTransportTrace::class.java, first)
                .header("Authorization", "private-proof-one").build()).enqueue(callback)
            check(arrived.await(5, TimeUnit.SECONDS))
            client.newCall(Request.Builder().url(server.url("/second")).tag(KmsTransportTrace::class.java, second)
                .header("Authorization", "private-proof-two").build()).enqueue(callback)
            Thread.sleep(80)
            assertThat(dispatcher.queuedCallsCount()).isEqualTo(1)
            unblock.countDown()
            check(done.await(5, TimeUnit.SECONDS))
            assertThat(failures).isEmpty()
            val queue = lines.single { "phase=finish stage=kms_dispatch_queue identity=${second.requestId}" in it }
            assertThat(Regex("duration_us=(\\d+)").find(queue)!!.groupValues[1].toLong()).isAtLeast(70000L)
            assertThat(queue).contains("operation=operation-second")
            assertThat(lines.any { "stage=kms_network_total identity=${second.requestId}" in it }).isTrue()
            assertThat(lines.joinToString()).doesNotContain("private-")
            val requests = listOf(server.takeRequest(), server.takeRequest())
            assertThat(requests.map { it.getHeader("X-Request-Id") }).containsExactly(first.requestId, second.requestId)
            assertThat(requests.map { it.getHeader("Authorization") }).containsExactly("private-proof-one", "private-proof-two")
        } finally {
            unblock.countDown()
            Timber.uproot(tree)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
