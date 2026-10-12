/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.security.contentprotection.api.SegmentedMatrixEncryptedFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

internal class VerifiedMatrixCiphertext(
    val ciphertext: ByteArray,
    val mxcAuthority: String,
    val homeserverAuthority: String,
)

/** Shared by manifest-backed segments and ordinary encrypted media. No SDK cache or temporary file. */
internal suspend fun fetchVerifiedMatrixCiphertext(
    httpClient: OkHttpClient,
    mxcUri: String,
    homeserverUrl: String,
    accessToken: String,
    expectedSha256Hex: String,
    expectedLength: Long?,
    maximumBytes: Long,
): VerifiedMatrixCiphertext = withOwnedMediaIo(cleanup = { it.ciphertext.fill(0) }) {
    require(maximumBytes in 1L..Int.MAX_VALUE.toLong())
    require(expectedLength == null || expectedLength in 1L..maximumBytes)
    require(expectedSha256Hex.matches(Regex("[0-9a-f]{64}")))
    require(accessToken.isNotBlank())
    check(httpClient.cache == null && !httpClient.followRedirects && !httpClient.followSslRedirects)
    val mxc = URI(mxcUri)
    require(mxc.scheme == "mxc" && !mxc.rawAuthority.isNullOrBlank() && mxc.userInfo == null && mxc.query == null && mxc.fragment == null)
    val mediaId = mxc.rawPath.orEmpty().removePrefix("/")
    require(mediaId.isNotBlank() && !mediaId.contains('/'))
    val homeserver = homeserverUrl.toHttpUrlOrNull() ?: throw SecurityException("Invalid Matrix homeserver.")
    require(homeserver.isHttps && homeserver.username.isEmpty() && homeserver.password.isEmpty())
    val url = homeserver.newBuilder().encodedPath("/").query(null).fragment(null)
        .addPathSegments("_matrix/client/v1/media/download")
        .addPathSegment(requireNotNull(mxc.rawAuthority)).addPathSegment(mediaId).build()
    check(url.scheme == homeserver.scheme && url.host == homeserver.host && url.port == homeserver.port)
    val call = httpClient.newCall(Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
        .header("Cache-Control", "no-store").get().build())
    call.awaitResponseCancellable().consumeCancellable(call, onCancelledValue = { it.ciphertext.fill(0) }) { response ->
        if (response.isRedirect) throw SecurityException("Matrix media redirects are forbidden.")
        if (!response.isSuccessful) throw IOException("Matrix media request failed with HTTP ${response.code}.")
        val body = response.body ?: throw IOException("Matrix media response has no body.")
        val declaredLength = body.contentLength()
        // A response length is framing, not an authority receipt. An event hash still authenticates every byte.
        val length = expectedLength ?: declaredLength.takeIf { it in 1L..maximumBytes }
            ?: throw SecurityException("Uncached Matrix media requires a bounded response length.")
        if (declaredLength >= 0 && declaredLength != length) throw SecurityException("Matrix media length mismatch.")
        val bytes = body.byteStream().use { readVerifiedCiphertext(it, length, expectedSha256Hex) }
        VerifiedMatrixCiphertext(bytes, requireNotNull(mxc.rawAuthority), homeserver.host)
    }
}

/** Covers prompt cancellation while dispatching the result back, after HTTP consumption has finished. */
internal suspend fun <T : Any> withOwnedMediaIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    cleanup: (T) -> Unit,
    block: suspend () -> T,
): T {
    val owner = AtomicReference<T?>()
    return try {
        withContext(dispatcher) { block().also(owner::set) }.also { owner.set(null) }
    } finally {
        owner.getAndSet(null)?.let(cleanup)
    }
}

internal suspend fun loadUncachedEncryptedMatrixContent(
    source: MediaSource,
    homeserverUrl: String,
    accessToken: String,
    httpClient: OkHttpClient,
): ByteArray = withOwnedMediaIo(cleanup = { it.fill(0) }) {
    val descriptor = parseEncryptedMatrixSource(source)
    val fetched = fetchVerifiedMatrixCiphertext(httpClient, descriptor.url, homeserverUrl, accessToken,
        descriptor.sha256Hex, expectedLength = null, maximumBytes = 64L * 1024 * 1024)
    try {
        decryptSegmentedMatrixOuter(fetched.ciphertext, descriptor.encryptedFile)
    } finally {
        fetched.ciphertext.fill(0)
    }
}

internal class EncryptedMatrixSourceDescriptor(val url: String, val sha256Hex: String, val encryptedFile: SegmentedMatrixEncryptedFile)

internal fun parseEncryptedMatrixSource(source: MediaSource): EncryptedMatrixSourceDescriptor = try {
    val raw = requireNotNull(source.json)
    require(raw.length <= 16_384)
    val root = Json.parseToJsonElement(raw).jsonObject
    require(root.keys == setOf("file"))
    val file = root.getValue("file").jsonObject
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    val url = file.text("url")
    require(url == source.safeUrl)
    val key = file.getValue("key").jsonObject
    val hash = Base64.getDecoder().decode(file.getValue("hashes").jsonObject.text("sha256"))
    require(hash.size == 32)
    val encrypted = SegmentedMatrixEncryptedFile(
        version = file.text("v"), algorithm = key.text("alg"), keyType = key.text("kty"),
        keyOperations = key.getValue("key_ops").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        extractable = key.getValue("ext").jsonPrimitive.boolean, keyBase64Url = key.text("k"), ivBase64 = file.text("iv"),
    )
    require(encrypted.version == "v2" && encrypted.algorithm == "A256CTR" && encrypted.keyType == "oct" && encrypted.extractable)
    require(encrypted.keyOperations == setOf("encrypt", "decrypt"))
    require(encrypted.keyBase64Url.length in 43..44 && encrypted.ivBase64.length in 22..24)
    EncryptedMatrixSourceDescriptor(url, hash.joinToString("") { "%02x".format(it) }, encrypted)
} catch (_: Exception) {
    // Parser failures must not retain/print the secret-bearing JSON in an exception message or cause.
    throw SecurityException("Invalid encrypted Matrix media descriptor.")
}
