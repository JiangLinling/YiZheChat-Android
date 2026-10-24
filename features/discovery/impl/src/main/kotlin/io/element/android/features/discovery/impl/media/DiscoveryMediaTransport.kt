/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.network.DiscoveryConfiguration
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resumeWithException

/** Separate bounded media dispatcher; does not change the JSON limits, CP client or original chat networking. */
@Inject
@SingleIn(AppScope::class)
internal class DiscoveryMediaTransport(private val configuration: DiscoveryConfiguration) {
    private val client by lazy {
        val endpoint = requireNotNull(configuration.endpoints).guest
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
            .certificatePinner(CertificatePinner.Builder().add(endpoint.origin.host, endpoint.certificatePin).build()).build()
    }

    suspend fun read(range: DiscoveryMediaRange, bearer: String, remainingMillis: Long): ByteArray {
        require(remainingMillis in 1..5000)
        val endpoint = configuration.endpoints?.guest ?: throw DiscoveryFailure(503)
        val bytes = range.request()
        try {
            require(bytes.size <= 4096)
            val request = Request.Builder().url(endpoint.origin.newBuilder().encodedPath("/v1/discovery/business/media/read").build())
                .header("Authorization", "Bearer $bearer").header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store").post(bytes.toRequestBody("application/json".toMediaType())).build()
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                call.timeout().timeout(remainingMillis, TimeUnit.MILLISECONDS)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(DiscoveryFailure(503)) }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { readDiscoveryMediaRange(it, range) } }
                        result.fold(onSuccess = { body ->
                            continuation.resume(body, onCancellation = { _, discarded, _ -> discarded.fill(0) })
                        }, onFailure = { continuation.resumeWithException(it as? DiscoveryFailure ?: DiscoveryFailure(503)) })
                    }
                })
            }
        } finally { bytes.fill(0) }
    }
}

/** A complete authenticated transport range; whole-file SHA verification is still required before decoding/caching. */
internal fun readDiscoveryMediaRange(response: Response, range: DiscoveryMediaRange): ByteArray {
    if (response.code != 206) throw DiscoveryFailure(if (response.code in 400..599) response.code else 503)
    fun exact(name: String, value: String) { require(response.headers.values(name) == listOf(value)) }
    exact("Content-Type", "application/octet-stream")
    exact("Content-Length", range.length.toString())
    exact("Content-Range", "bytes ${range.start}-${range.endInclusive}/${range.reference.byteLength}")
    exact("Accept-Ranges", "bytes")
    exact("X-Content-SHA256", range.reference.sha256)
    require(response.headers.values("Content-Encoding").let { it.isEmpty() || it == listOf("identity") })
    val cache = response.headers.values("Cache-Control").flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
    require("no-store" in cache && "private" in cache)
    require(response.headers.values("Transfer-Encoding").isEmpty())
    val bytes = ByteArray(range.length)
    try {
        response.body.byteStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, minOf(32768, bytes.size - offset))
                require(count > 0)
                offset += count
            }
            require(input.read() == -1)
        }
        return bytes
    } catch (failure: Throwable) {
        bytes.fill(0)
        throw failure
    }
}
