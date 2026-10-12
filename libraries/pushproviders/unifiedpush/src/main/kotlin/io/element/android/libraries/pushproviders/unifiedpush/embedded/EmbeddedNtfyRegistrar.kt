/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.os.Build
import dev.zacsweers.metro.Inject
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.auth.OpenIdToken
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.api.DeviceRootKeyService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Inject
class EmbeddedNtfyRegistrar(
    private val jsonProvider: JsonProvider,
    private val dispatchers: CoroutineDispatchers,
    private val deviceRootKeyService: DeviceRootKeyService,
) {
    private val registrarBaseUrl = if (NextDeploymentConfig.isNextDeployment) {
        NextDeploymentConfig.pushRegistrarBaseUrl
    } else {
        STABLE_REGISTRAR_BASE_URL
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val deliveryHttpClient = createPushDeliveryHttpClient()

    suspend fun register(
        openIdToken: OpenIdToken,
        clientSecret: String,
        matrixDeviceIdHash: String,
    ): Result<EmbeddedNtfyRegistration> = withContext(dispatchers.io) {
        try {
            val operation = currentLifecycleOperationContext()
                ?: error("Built-in push registration requires a lifecycle operation.")
            // A client secret intentionally rotates when a Matrix session is removed.  It is
            // therefore not a device identity: using it here made one phone consume another
            // registrar slot at every re-login.  The DRK installation id is stable for this
            // installed app and is sent only as a one-way digest.
            val installationKey = deviceRootKeyService.ensureReady().installationId.sha256()
            val requestBody = buildJsonObject {
                put("device_label", "Android ${Build.MODEL}")
                put("device_key", installationKey)
                put("operation_id", operation.operationId)
                put("matrix_device_id_hash", matrixDeviceIdHash)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$registrarBaseUrl/v1/devices/register")
                .header("Authorization", "Bearer ${openIdToken.accessToken}")
                .header("Cache-Control", "no-store")
                .header("X-YiZhe-Lifecycle-Operation-Id", operation.operationId)
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(RegistrarException(response.code))
                }
                val body = response.body.string()
                val registration = jsonProvider().decodeFromString<RegistrarRegistrationResponse>(body)
                Result.success(
                    EmbeddedNtfyRegistration(
                        clientSecret = clientSecret,
                        server = registration.server,
                        topic = registration.topic,
                        username = registration.username,
                        password = registration.password,
                        endpoint = registration.endpoint,
                        gateway = registration.gateway,
                        managementToken = registration.managementToken,
                        expiresAt = registration.expiresAt,
                        strategyVersion = registration.strategyVersion,
                    )
                )
            }
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun unregister(managementToken: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            val operation = currentLifecycleOperationContext()
                ?: error("Built-in push revocation requires a lifecycle operation.")
            val requestBody = buildJsonObject {
                put("management_token", managementToken)
                put("operation_id", operation.operationId)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$registrarBaseUrl/v1/devices/register")
                .header("X-YiZhe-Lifecycle-Operation-Id", operation.operationId)
                .delete(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit) else Result.failure(RegistrarException(response.code))
            }
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun unregisterAccount(openIdToken: OpenIdToken): Result<Unit> = withContext(dispatchers.io) {
        try {
            val operation = currentLifecycleOperationContext()
                ?: error("Built-in push account revocation requires a lifecycle operation.")
            val requestBody = buildJsonObject {
                put("operation_id", operation.operationId)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$registrarBaseUrl/v1/accounts/registrations")
                .header("Authorization", "Bearer ${openIdToken.accessToken}")
                .header("Cache-Control", "no-store")
                .header("X-YiZhe-Lifecycle-Operation-Id", operation.operationId)
                .delete(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit) else Result.failure(RegistrarException(response.code))
            }
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun confirm(managementToken: String): Result<Unit> = withContext(dispatchers.io) {
        val operation = currentLifecycleOperationContext()
            ?: return@withContext Result.failure(
                IllegalStateException("Built-in push confirmation requires a lifecycle operation.")
            )
        val requestBody = buildJsonObject {
            put("operation_id", operation.operationId)
        }.toString()
        postManagementRequest(
            url = "$registrarBaseUrl/v1/devices/confirm",
            managementToken = managementToken,
            requestBody = requestBody,
            client = httpClient,
            operationId = operation.operationId,
        ).map { }
    }

    suspend fun registerVendor(managementToken: String, provider: String, token: String): Result<Unit> = withContext(dispatchers.io) {
        val requestBody = buildJsonObject {
            put("provider", provider)
            put("token", token)
        }.toString()
        postManagementRequest("$registrarBaseUrl/v1/devices/vendor", managementToken, requestBody, httpClient).map { }
    }

    suspend fun claimDelivery(
        managementToken: String,
        eventId: String,
        roomId: String,
    ): Result<DeliveryClaimDecision> = withContext(dispatchers.io) {
        postDeliveryRequest("$registrarBaseUrl/v1/deliveries/claim", managementToken, eventId, roomId).mapCatching { responseBody ->
            val response = jsonProvider().decodeFromString<RegistrarDeliveryDecisionResponse>(responseBody)
            when (response.decision) {
                "claimed" -> DeliveryClaimDecision.CLAIMED
                "uncoordinated" -> DeliveryClaimDecision.PROCESS_UNCOORDINATED
                "suppress" -> DeliveryClaimDecision.SUPPRESS
                else -> error("Unknown delivery claim decision")
            }
        }
    }

    suspend fun completeDelivery(managementToken: String, eventId: String, roomId: String): Result<Unit> = withContext(dispatchers.io) {
        postDeliveryRequest("$registrarBaseUrl/v1/deliveries/complete", managementToken, eventId, roomId).map { }
    }

    suspend fun releaseDelivery(managementToken: String, eventId: String, roomId: String): Result<Unit> = withContext(dispatchers.io) {
        postDeliveryRequest("$registrarBaseUrl/v1/deliveries/release", managementToken, eventId, roomId).map { }
    }

    private suspend fun postDeliveryRequest(
        url: String,
        managementToken: String,
        eventId: String,
        roomId: String,
    ): Result<String> {
        val operation = currentLifecycleOperationContext()
            ?: return Result.failure(IllegalStateException("Built-in push delivery requires a lifecycle operation."))
        val requestBody = buildJsonObject {
            put("event_id", eventId)
            put("room_id", roomId)
            put("operation_id", operation.operationId)
        }.toString()
        return postManagementRequest(
            url = url,
            managementToken = managementToken,
            requestBody = requestBody,
            client = deliveryHttpClient,
            operationId = operation.operationId,
        )
    }

    private fun postManagementRequest(
        url: String,
        managementToken: String,
        requestBody: String,
        client: OkHttpClient,
        operationId: String? = null,
    ): Result<String> {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $managementToken")
                .header("Cache-Control", "no-store")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            if (operationId != null) {
                requestBuilder.header("X-YiZhe-Lifecycle-Operation-Id", operationId)
            }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(response.body.string())
                } else {
                    Result.failure(RegistrarException(response.code))
                }
            }
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private class RegistrarException(statusCode: Int) : Exception("Built-in push registration failed with HTTP $statusCode")

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val STABLE_REGISTRAR_BASE_URL = "https://push.yizhesy.cn/yizhe-push"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// The registrar commits the Push authority receipt before returning. Its
// bounded callback may take 10 seconds; a 1.5-second client budget can abandon
// an already committed delivery before Android persists its completion fact.
internal fun createPushDeliveryHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
