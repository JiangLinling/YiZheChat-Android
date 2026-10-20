/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.DeviceRecipientRegistrySnapshot
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpDeviceRecipientRegistry(
    private val configurationProvider: KmsClientConfigurationProvider,
    private val authorizationProvider: KmsAuthorizationProvider,
    private val clock: SystemClock,
) : DeviceRecipientRegistry {
    private val cache = ConcurrentHashMap<String, CachedRegistry>()
    @Volatile private var cachedApi: CachedApi? = null

    override suspend fun approvedRecipients(accountIdHash: String): DeviceRecipientRegistrySnapshot =
        requireNotNull(approvedRecipients(setOf(accountIdHash))[accountIdHash])

    override suspend fun approvedRecipients(accountIdHashes: Set<String>): Map<String, DeviceRecipientRegistrySnapshot> {
        require(accountIdHashes.size in 1..ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS)
        require(accountIdHashes.all(ACCOUNT_HASH::matches))
        val now = clock.epochMillis()
        val resolved = linkedMapOf<String, DeviceRecipientRegistrySnapshot>()
        val missing = mutableListOf<String>()
        for (accountIdHash in accountIdHashes.sorted()) {
            val cached = cache[accountIdHash]?.takeIf { now < it.snapshot.expiresAtEpochMillis }
            if (cached == null) {
                missing += accountIdHash
            } else {
                resolved[accountIdHash] = copySnapshot(cached.snapshot)
            }
        }
        if (missing.isEmpty()) return resolved
        val authorization = authorizationProvider.authorization() ?: throw ContentProtectionException.KmsUnavailable()
        val responses = try {
            if (missing.size == 1) {
                listOf(requireBody(api().recipients(missing.single(), authorization.asHeaderValue())))
            } else {
                requireBody(api().recipientsBatch(RegistryBatchRequest(missing), authorization.asHeaderValue()))
            }
        } catch (failure: ContentProtectionException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentProtectionException.KmsUnavailable(failure)
        }
        if (responses.size != missing.size || responses.map { it.accountIdHash }.toSet() != missing.toSet()) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        responses.associateBy { it.accountIdHash }.forEach { (accountIdHash, body) ->
            resolved[accountIdHash] = decodeAndCache(body, accountIdHash, now)
        }
        return accountIdHashes.sorted().associateWith { accountIdHash ->
            copySnapshot(requireNotNull(resolved[accountIdHash]))
        }
    }

    @Suppress("ComplexCondition")
    private fun decodeAndCache(body: RegistryResponse, expectedAccountIdHash: String, now: Long): DeviceRecipientRegistrySnapshot {
        val recipients = body.recipients.sortedBy { it.installationId }.map { wire ->
            val publicKey = wire.publicKeyDerB64.decodeBase64(MAX_PUBLIC_KEY_BYTES)
            if (wire.algorithm != ContentProtectionFormat.DEVICE_SHARE_ALGORITHM ||
                wire.installationId.isBlank() || wire.keyId.isBlank() ||
                !MessageDigest.isEqual(MessageDigest.getInstance(SHA256).digest(publicKey).toHex().encodeToByteArray(), wire.keyId.encodeToByteArray())
            ) {
                publicKey.fill(0)
                throw ContentProtectionException.AuthenticationFailed()
            }
            DeviceShareRecipient(
                installationId = wire.installationId,
                keyId = wire.keyId,
                algorithm = wire.algorithm,
                publicKeyDer = publicKey,
                allowsBoundedOffline = wire.attestationState in BOUNDED_OFFLINE_ATTESTATION_STATES,
            )
        }
        try {
            if (body.protocolVersion != PROTOCOL_VERSION || body.accountIdHash != expectedAccountIdHash ||
                body.issuedAtEpochMillis > now + MAX_CLOCK_SKEW_MILLIS || now >= body.expiresAtEpochMillis ||
                body.expiresAtEpochMillis - body.issuedAtEpochMillis !in 1..MAX_REGISTRY_TTL_MILLIS ||
                recipients.isEmpty() || recipients.size > MAX_RECIPIENTS ||
                recipients.map { it.installationId }.distinct().size != recipients.size
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            verifySignature(requireConfiguration(), signaturePayload(body, recipients), body.serverSignatureB64.decodeBase64(MAX_SIGNATURE_BYTES))
            val snapshot = DeviceRecipientRegistrySnapshot(
                accountIdHash = body.accountIdHash,
                issuedAtEpochMillis = body.issuedAtEpochMillis,
                expiresAtEpochMillis = body.expiresAtEpochMillis,
                recipients = recipients.map(::copyRecipient),
            )
            cache.put(expectedAccountIdHash, CachedRegistry(snapshot))?.snapshot?.recipients?.forEach { it.publicKeyDer.fill(0) }
            return copySnapshot(snapshot)
        } finally {
            recipients.forEach { it.publicKeyDer.fill(0) }
        }
    }

    private fun api(): RegistryApi {
        val configuration = requireConfiguration()
        val identity = configuration.baseUrl + ":" + MessageDigest.getInstance(SHA256).digest(configuration.signingPublicKeyDer).toHex()
        cachedApi?.takeIf { it.identity == identity }?.let { return it.api }
        return synchronized(this) {
            cachedApi?.takeIf { it.identity == identity }?.api ?: createApi(configuration).also { cachedApi = CachedApi(identity, it) }
        }
    }

    private fun createApi(configuration: KmsClientConfiguration): RegistryApi {
        val baseUrl = configuration.baseUrl.toHttpUrl()
        if (!configuration.allowCleartextForTests && !baseUrl.isHttps) throw ContentProtectionException.KmsUnavailable()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (configuration.certificatePinsSha256.isNotEmpty()) {
                    certificatePinner(CertificatePinner.Builder().apply {
                        configuration.certificatePinsSha256.forEach { add(baseUrl.host, it) }
                    }.build())
                }
            }
            .build()
        val json = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RegistryApi::class.java)
    }

    private fun requireConfiguration(): KmsClientConfiguration = configurationProvider.configuration()?.also {
        require(it.signingPublicKeyDer.isNotEmpty())
    } ?: throw ContentProtectionException.KmsUnavailable()

    private fun <T> requireBody(response: Response<T>): T {
        if (response.code() == 401 || response.code() == 403) throw ContentProtectionException.AccessDenied()
        if (response.code() == 409) {
            // Other conflicts (for example a pending authority projection) are not proof of recipient logout.
            val detail = runCatching {
                Json.parseToJsonElement(response.errorBody()?.string().orEmpty()).jsonObject["detail"]?.jsonPrimitive?.content
            }.getOrNull()
            if (detail in setOf("account has no approved recipient devices", "an account has no approved recipient devices")) {
                throw ContentProtectionException.RecipientDeviceUnavailable()
            }
            throw ContentProtectionException.KmsUnavailable()
        }
        if (!response.isSuccessful) throw ContentProtectionException.KmsUnavailable()
        return response.body() ?: throw ContentProtectionException.KmsUnavailable()
    }

    private fun verifySignature(configuration: KmsClientConfiguration, payload: ByteArray, signature: ByteArray) {
        try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(configuration.signingPublicKeyDer))
            if (!Signature.getInstance(ContentProtectionFormat.KMS_SIGNATURE_ALGORITHM).run {
                    initVerify(publicKey)
                    update(payload)
                    verify(signature)
                }
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
        } catch (failure: ContentProtectionException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        } finally {
            payload.fill(0)
            signature.fill(0)
        }
    }

    private fun signaturePayload(body: RegistryResponse, recipients: List<DeviceShareRecipient>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(REGISTRY_MAGIC)
                output.writeString(body.accountIdHash)
                output.writeLong(body.issuedAtEpochMillis)
                output.writeLong(body.expiresAtEpochMillis)
                output.writeInt(recipients.size)
                recipients.sortedBy { it.installationId }.forEachIndexed { index, recipient ->
                    val wire = body.recipients.sortedBy { it.installationId }[index]
                    output.writeString(recipient.installationId)
                    output.writeString(recipient.keyId)
                    output.writeString(recipient.algorithm)
                    output.writeBytes(recipient.publicKeyDer)
                    output.writeString(wire.hardwareSecurityLevel)
                    output.writeString(wire.attestationState)
                }
            }
            bytes.toByteArray()
        }

    private fun copyRecipient(value: DeviceShareRecipient) = value.copy(publicKeyDer = value.publicKeyDer.copyOf())
    private fun copySnapshot(value: DeviceRecipientRegistrySnapshot) =
        value.copy(recipients = value.recipients.map(::copyRecipient))
    private fun DataOutputStream.writeString(value: String) = writeBytes(value.encodeToByteArray())
    private fun DataOutputStream.writeBytes(value: ByteArray) {
        require(value.size <= MAX_WIRE_BYTES)
        writeInt(value.size)
        write(value)
    }
    private fun String.decodeBase64(maximum: Int): ByteArray = try {
        Base64.getDecoder().decode(this).also { require(it.size <= maximum) }
    } catch (failure: Exception) {
        throw ContentProtectionException.AuthenticationFailed(failure)
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class CachedRegistry(val snapshot: DeviceRecipientRegistrySnapshot)
    private data class CachedApi(val identity: String, val api: RegistryApi)

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val SHA256 = "SHA-256"
        const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L
        const val MAX_REGISTRY_TTL_MILLIS = 10 * 60 * 1000L
        const val MAX_RECIPIENTS = 512
        const val MAX_PUBLIC_KEY_BYTES = 8192
        const val MAX_SIGNATURE_BYTES = 256
        const val MAX_WIRE_BYTES = 8192
        val ACCOUNT_HASH = Regex("[0-9a-f]{64}")
        val BOUNDED_OFFLINE_ATTESTATION_STATES = setOf("HARDWARE_VERIFIED", "TEST_ONLY_VERIFIED")
        val REGISTRY_MAGIC = "YZDR0001".encodeToByteArray()
    }
}

private interface RegistryApi {
    @GET("v1/device-registry/{accountIdHash}")
    suspend fun recipients(@Path("accountIdHash") accountIdHash: String, @Header("Authorization") authorization: String): Response<RegistryResponse>

    @POST("v1/device-registry/batch")
    suspend fun recipientsBatch(
        @Body body: RegistryBatchRequest,
        @Header("Authorization") authorization: String,
    ): Response<List<RegistryResponse>>
}

@Serializable
private data class RegistryBatchRequest(
    @SerialName("account_id_hashes") val accountIdHashes: List<String>,
)

@Serializable
private data class RegistryResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("recipients") val recipients: List<RegistryRecipient>,
    @SerialName("server_signature_b64") val serverSignatureB64: String,
)

@Serializable
private data class RegistryRecipient(
    @SerialName("installation_id") val installationId: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("algorithm") val algorithm: String,
    @SerialName("public_key_der_b64") val publicKeyDerB64: String,
    @SerialName("hardware_security_level") val hardwareSecurityLevel: String,
    @SerialName("attestation_state") val attestationState: String,
)
