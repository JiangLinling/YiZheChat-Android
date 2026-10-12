/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import io.element.android.security.dataprotection.api.LifecycleStageTiming
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Diagnostic metadata only. Never holds a proof, nonce, grant, key, body, URL, or authorization result. */
internal class KmsTransportTrace(val operationId: String, val action: String) {
    val requestId: String = UUID.randomUUID().toString()
    @Volatile private var attemptStartNs = 0L
    @Volatile private var callStartNs = 0L
    @Volatile private var dequeuedNs = 0L
    @Volatile private var responseEndNs = 0L
    private val starts = ConcurrentHashMap<String, Long>()
    @Volatile private var serverDurations: Map<String, Long> = emptyMap()

    fun startAttempt() {
        attemptStartNs = System.nanoTime()
    }
    fun startCall() {
        callStartNs = System.nanoTime()
        interval("kms_client_prepare", attemptStartNs, callStartNs)
    }

    fun dequeued() {
        dequeuedNs = System.nanoTime()
        interval("kms_dispatch_queue", callStartNs, dequeuedNs)
    }

    fun start(stage: String, key: String = stage) {
        starts[key] = System.nanoTime()
    }

    fun finish(stage: String, key: String = stage) {
        starts.remove(key)?.let { interval(stage, it, System.nanoTime()) }
    }

    fun headers(value: String?, status: Int) {
        if (status in 100..599) LifecycleStageTiming.observed("kms_http_status", requestId, status.toString(), operationId)
        serverDurations = parseServerTiming(value)
    }

    fun endCall() {
        val now = System.nanoTime()
        responseEndNs = now
        interval("kms_transport_total", callStartNs, now)
        if (dequeuedNs == 0L) interval("kms_dispatch_queue", callStartNs, now)
        if (dequeuedNs != 0L) {
            interval("kms_network_total", dequeuedNs, now)
            val networkUs = (now - dequeuedNs) / 1000
            val app = serverDurations["kms_app"]
            // Server values are untrusted diagnostics. Missing/inconsistent metrics remain unknown.
            if (app != null && app <= networkUs + 5000) {
                serverDurations.forEach { (name, value) ->
                    LifecycleStageTiming.reportedDuration(name, value, requestId, action, operationId)
                }
                serverDurations["kms_binding"]?.let { binding ->
                    LifecycleStageTiming.reportedDuration("kms_server_active", app - binding, requestId, action, operationId)
                }
                // This is an estimate (server ends at response headers); it includes response transfer and client scheduling.
                LifecycleStageTiming.reportedDuration(
                    "kms_roundtrip_remainder",
                    (networkUs - app).coerceAtLeast(0),
                    requestId,
                    action,
                    operationId,
                    clock = "derived"
                )
            }
        }
    }

    fun converted() {
        if (responseEndNs != 0L) interval("kms_response_decode", responseEndNs, System.nanoTime())
    }

    private fun interval(stage: String, start: Long, end: Long) {
        if (start != 0L) LifecycleStageTiming.interval(stage, start, end, requestId, action, operationId)
    }

    companion object {
        private val serverTimingNames = setOf(
            "kms_app",
            "kms_binding",
            "kms_binding_gate",
            "kms_device_signature",
            "kms_release",
            "kms_prepare",
            "kms_db_pool_commit",
            "kms_release_worker_queue",
            "kms_release_worker",
            "kms_receipt_worker_queue",
            "kms_receipt_worker",
            "kms_refresh_worker_queue",
            "kms_refresh_worker",
        )

        /** Only fixed diagnostic durations are accepted; no arbitrary headers or descriptions. */
        internal fun parseServerTiming(value: String?): Map<String, Long> {
            if (value == null || value.length > 1024) return emptyMap()
            val result = mutableMapOf<String, Long>()
            for (part in value.split(',')) {
                val match = Regex("\\s*([a-z_]+);dur=([0-9]+(?:\\.[0-9]{1,3})?)\\s*").matchEntire(part) ?: continue
                if (match.groupValues[1] !in serverTimingNames) continue
                val durationMs = match.groupValues[2].toDoubleOrNull() ?: return emptyMap()
                if (!durationMs.isFinite() || durationMs !in 0.0..120000.0) return emptyMap()
                if (result.put(match.groupValues[1], (durationMs * 1000).toLong()) != null) return emptyMap()
            }
            val app = result["kms_app"] ?: return emptyMap()
            if (result.values.any { it > app }) return emptyMap()
            return result
        }
    }
}

internal object KmsTransportTiming {
    val interceptor = Interceptor { chain ->
        val trace = chain.request().tag(KmsTransportTrace::class.java)
        if (trace == null) {
            chain.proceed(chain.request())
        } else {
            runCatching { trace.dequeued() }
            chain.proceed(chain.request().newBuilder().header("X-Request-Id", trace.requestId).build())
        }
    }

    val listenerFactory = EventListener.Factory { call ->
        call.request().tag(KmsTransportTrace::class.java)?.let(::TraceEvents) ?: EventListener.NONE
    }

    private class TraceEvents(private val trace: KmsTransportTrace) : EventListener() {
        override fun callStart(call: Call) {
            runCatching { trace.startCall() }
        }
        override fun dnsStart(call: Call, domainName: String) {
            runCatching { trace.start("kms_dns") }
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            runCatching { trace.finish("kms_dns") }
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            runCatching { trace.start("kms_connect", "$inetSocketAddress:$proxy") }
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            runCatching { trace.finish("kms_connect", "$inetSocketAddress:$proxy") }
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            runCatching { trace.finish("kms_connect", "$inetSocketAddress:$proxy") }
        }
        override fun secureConnectStart(call: Call) {
            runCatching { trace.start("kms_tls") }
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            runCatching { trace.finish("kms_tls") }
        }
        override fun requestHeadersStart(call: Call) {
            runCatching { trace.start("kms_response_wait") }
        }
        override fun responseHeadersStart(call: Call) {
            runCatching { trace.finish("kms_response_wait") }
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            runCatching { trace.headers(response.header("Server-Timing"), response.code) }
        }
        override fun responseBodyStart(call: Call) {
            runCatching { trace.start("kms_body_read") }
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            runCatching { trace.finish("kms_body_read") }
        }
        override fun callEnd(call: Call) {
            runCatching { trace.endCall() }
        }
        override fun callFailed(call: Call, ioe: IOException) {
            runCatching {
                trace.endCall()
                LifecycleStageTiming.observed("kms_transport_failed", trace.requestId, trace.action, trace.operationId)
            }
        }
    }
}
