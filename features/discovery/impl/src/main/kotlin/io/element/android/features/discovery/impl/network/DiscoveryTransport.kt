/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class DiscoveryTarget { HOST, GATEWAY }

internal interface DiscoveryTransport {
    suspend fun post(target: DiscoveryTarget, path: String, body: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray
    suspend fun getHome(headers: Map<String, String>): ByteArray = throw DiscoveryFailure(503)
    suspend fun queryCourses(body: ByteArray, headers: Map<String, String>): ByteArray = throw DiscoveryFailure(503)
    suspend fun queryArticles(body: ByteArray, headers: Map<String, String>): ByteArray = throw DiscoveryFailure(503)
    suspend fun operation(operationId: String, headers: Map<String, String>): ByteArray = throw DiscoveryFailure(503)
}

@Inject
@SingleIn(AppScope::class)
internal class PinnedDiscoveryTransport(private val configuration: DiscoveryConfiguration) : DiscoveryTransport {
    private val clients by lazy {
        val endpoints = requireNotNull(configuration.endpoints)
        mapOf(DiscoveryTarget.HOST to endpoints.host, DiscoveryTarget.GATEWAY to endpoints.guest).mapValues { (_, endpoint) ->
            OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
                .certificatePinner(CertificatePinner.Builder().add(endpoint.origin.host, endpoint.certificatePin).build())
                .build()
        }
    }

    override suspend fun post(target: DiscoveryTarget, path: String, body: ByteArray, headers: Map<String, String>): ByteArray {
        return request(target, path, body, headers)
    }

    override suspend fun getHome(headers: Map<String, String>): ByteArray =
        request(DiscoveryTarget.GATEWAY, "/v1/discovery/business/home", null, headers)

    override suspend fun queryCourses(body: ByteArray, headers: Map<String, String>): ByteArray =
        request(DiscoveryTarget.GATEWAY, "/v1/discovery/business/courses/query", body, headers)

    override suspend fun queryArticles(body: ByteArray, headers: Map<String, String>): ByteArray =
        request(DiscoveryTarget.GATEWAY, "/v1/discovery/business/articles/query", body, headers)

    override suspend fun operation(operationId: String, headers: Map<String, String>): ByteArray {
        require(UUID.fromString(operationId).toString() == operationId)
        return request(DiscoveryTarget.GATEWAY, "/v1/discovery/operations/$operationId", null, headers)
    }

    private suspend fun request(target: DiscoveryTarget, path: String, body: ByteArray?, headers: Map<String, String>): ByteArray {
        val endpoints = configuration.endpoints ?: throw DiscoveryFailure(503)
        val endpoint = if (target == DiscoveryTarget.HOST) endpoints.host else endpoints.guest
        val allowed = if (target == DiscoveryTarget.HOST) setOf("/v1/discovery/login-codes") else setOf(
            "/v1/discovery/capabilities", "/v1/discovery/sessions/exchange", "/v1/discovery/sessions/revoke",
            "/v1/discovery/business/articles/query",
            "/v1/discovery/business/courses/query",
            "/v1/discovery/business/articles/interactions",
            "/v1/discovery/business/course-quotes",
            "/v1/discovery/business/course-enrollments",
            "/v1/discovery/business/member/query", "/v1/discovery/business/member-addresses",
            "/v1/discovery/business/member-profile", "/v1/discovery/business/member-children",
            "/v1/discovery/business/mall/query", "/v1/discovery/business/mall-orders", "/v1/discovery/business/mall-order-cancellations",
            "/v1/discovery/business/mall-order-receipts", "/v1/discovery/business/mall-after-sales",
            "/v1/discovery/business/services/query",
            "/v1/discovery/business/course-feedback",
        )
        val isHome = target == DiscoveryTarget.GATEWAY && path == "/v1/discovery/business/home" && body == null
        val isOperation = target == DiscoveryTarget.GATEWAY && body == null &&
            path.matches(Regex("/v1/discovery/operations/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        require(isHome || isOperation || (path in allowed && body != null && body.size <= 16_384))
        val request = Request.Builder().url(endpoint.origin.newBuilder().encodedPath(endpoint.origin.encodedPath.trimEnd('/') + path).build())
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .apply { if (body == null) get() else post(body.toRequestBody("application/json".toMediaType())) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = clients.getValue(target).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(DiscoveryFailure(503))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw DiscoveryFailure(it.code)
                            if (it.header("Content-Encoding", "identity") != "identity") throw DiscoveryFailure(503)
                            if (it.header("Content-Type").orEmpty().substringBefore(';') != "application/json") throw DiscoveryFailure(503)
                            val output = ByteArrayOutputStream()
                            it.body.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    val limit = if (path == "/v1/discovery/business/mall/query") 1_048_576
                                    else if (path == "/v1/discovery/business/services/query") 131_072
                                    else if (isHome || path in setOf("/v1/discovery/business/articles/query", "/v1/discovery/business/courses/query",
                                        "/v1/discovery/business/member/query")) 65_536 else 16_384
                                    if (output.size() + count > limit) throw DiscoveryFailure(503)
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                    }
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it as? DiscoveryFailure ?: DiscoveryFailure(503)) },
                    )
                }
            })
        }
    }
}
