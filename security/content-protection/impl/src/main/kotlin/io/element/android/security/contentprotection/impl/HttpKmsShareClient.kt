/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import android.content.Context
import android.content.pm.PackageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.contentprotection.api.AccountPrivateProfileKey
import io.element.android.security.contentprotection.api.RecoveryReleaseAuthorityChanged
import io.element.android.security.contentprotection.api.AcquireRoomEpochRequest
import io.element.android.security.contentprotection.api.BurnAfterReadEndResult
import io.element.android.security.contentprotection.api.BurnAfterReadSession
import io.element.android.security.contentprotection.api.BurnAfterReadWindowMode
import io.element.android.security.contentprotection.api.ConfirmDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.EncryptedPrivateProfileDocument
import io.element.android.security.contentprotection.api.EndBurnAfterReadRequest
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsRequestAuthorization
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.OfflineLease
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OpenBurnAfterReadRequest
import io.element.android.security.contentprotection.api.PendingDeviceRecovery
import io.element.android.security.contentprotection.api.RegisterContentVersionRequest
import io.element.android.security.contentprotection.api.RegisterContentVersionResult
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleaseRoomEpochRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.ReleasedRoomEpoch
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RemoteRevocationSnapshot
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomEpochReference
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticSink
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
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
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Tag
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpKmsShareClient(
    private val configurationProvider: KmsClientConfigurationProvider,
    private val authorizationProvider: KmsAuthorizationProvider,
    private val deviceShareKeyService: DeviceShareKeyService,
) : KmsShareClient {
    @Volatile private var cachedApi: CachedApi? = null
    private val secureRandom = java.security.SecureRandom()

    override suspend fun registerContentVersion(request: RegisterContentVersionRequest): RegisterContentVersionResult {
        require(request.operationId == currentLifecycleOperationContext()?.operationId) {
            "Content-version operation does not match the authenticated lifecycle operation."
        }
        val response = requireBody(executeAuthorized { api, authorization ->
            api.registerContentVersion(authorization, RegisterContentVersionBody(
                contentId = request.contentId.value,
                rootContentId = request.rootContentId.value,
                parentContentId = request.parentContentId?.value,
                originOperationId = request.originOperationId,
                operationId = request.operationId,
                expectedEventIdSha256 = request.expectedEventIdSha256,
            ))
        }).node
        check(response.contentId == request.contentId.value)
        check(response.rootContentId == request.rootContentId.value)
        check(response.parentContentId == request.parentContentId?.value)
        check(response.operationId == request.operationId)
        check(response.matrixEventIdSha256 == request.expectedEventIdSha256)
        return RegisterContentVersionResult(
            ContentId(response.contentId),
            ContentId(response.rootContentId),
            response.parentContentId?.let(::ContentId),
            response.operationId,
            response.expiresAtEpochMillis,
            response.matrixEventIdSha256,
            response.createdAtEpochMillis,
        )
    }

    override suspend fun requestPrivateResource(action: String, requestJson: String): String {
        require(action in setOf("prepare", "release", "fetch", "open", "sync", "delete", "put"))
        val request = ERROR_JSON.parseToJsonElement(requestJson).jsonObject
        val response = executeAuthorized { api, authorization ->
            if (action == "put") api.putPrivateResource(authorization, request)
            else api.privateResource(action, authorization, request)
        }
        if (response.code() == 409) throw IllegalStateException("PRIVATE_RESOURCE_CONFLICT")
        val envelope = requirePrivateProfileEnsureBody(response)
        val payload = Base64.getDecoder().decode(requireNotNull(envelope["payload_b64"]).jsonPrimitive.content)
        val signature = Base64.getDecoder().decode(requireNotNull(envelope["signature_b64"]).jsonPrimitive.content)
        try {
            val configuration = requireConfiguration()
            val expectedKeyId = MessageDigest.getInstance(SHA256).digest(configuration.signingPublicKeyDer).hex().take(16)
            if (envelope["key_id"]?.jsonPrimitive?.content != expectedKeyId) throw ContentProtectionException.AuthenticationFailed()
            verifySignature(configuration, payload.copyOf(), signature.copyOf())
            val body = ERROR_JSON.parseToJsonElement(payload.decodeToString()).jsonObject
            if (body["protocol_version"]?.jsonPrimitive?.content != "1" ||
                body["account_id_hash"] != request["account_id_hash"] || body["request_nonce_b64"] != request["request_nonce_b64"]
            ) throw ContentProtectionException.AuthenticationFailed()
            val serverTime = requireNotNull(body["server_time_ms"]).jsonPrimitive.long
            if (kotlin.math.abs(System.currentTimeMillis() - serverTime) > MAX_PRIVATE_PROFILE_RESPONSE_AGE_MILLIS) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            if (action != "sync" && body["resource_id"] != request["resource_id"]) throw ContentProtectionException.AuthenticationFailed()
            if (action == "prepare" || action == "release" || (action == "open" && body["encrypted_key_b64"] != null)) {
                val descriptor = deviceShareKeyService.descriptor()
                val issued = requireNotNull(body["grant_issued_at_ms"]).jsonPrimitive.long
                val expiry = requireNotNull(body["expires_at_ms"]).jsonPrimitive.long
                val common = body["resource_kind"]?.jsonPrimitive?.content in setOf("COMMON_BODY", "COMMON_INDEX", "COMMON_MANAGEMENT")
                val policy = body["policy_id"]?.jsonPrimitive?.content
                val extended = action != "prepare" && common && policy == "account-private-common-online-v2"
                val ceiling = if (extended) 300_000L else 60_000L
                if (body["installation_id"]?.jsonPrimitive?.content != descriptor.installationId ||
                    body["recipient_key_id"]?.jsonPrimitive?.content != descriptor.keyId || expiry <= serverTime ||
                    (action != "open" && body["resource_kind"] != request["resource_kind"]) ||
                    (action == "release" && body["version_id"] != request["version_id"]) ||
                    expiry - issued !in 1..ceiling || issued > serverTime ||
                    (!extended && policy != "account-private-resource-online-v1")
                ) throw ContentProtectionException.AuthenticationFailed()
            }
            return payload.decodeToString()
        } finally {
            payload.fill(0)
            signature.fill(0)
        }
    }

    override suspend fun releasePrivateProfileKey(accountIdHash: String): AccountPrivateProfileKey {
        require(ACCOUNT_HASH.matches(accountIdHash))
        val descriptor = deviceShareKeyService.descriptor()
        val requestNonce = ByteArray(24).also(secureRandom::nextBytes)
        try {
            val body = requirePrivateProfileReleaseBody(executeAuthorized { api, authorization ->
                api.releasePrivateProfileKey(
                    authorization,
                    PrivateProfileReleaseKeyBody(
                        PRIVATE_PROFILE_PROTOCOL_VERSION, accountIdHash,
                        descriptor.installationId, descriptor.keyId, requestNonce.base64(),
                    ),
                )
            })
            return decodePrivateProfileKey(body, descriptor, accountIdHash, requestNonce)
        } finally {
            requestNonce.fill(0)
        }
    }

    override suspend fun ensurePrivateProfileKey(accountIdHash: String, proposedKey: ByteArray): AccountPrivateProfileKey {
        require(ACCOUNT_HASH.matches(accountIdHash))
        require(proposedKey.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        val descriptor = deviceShareKeyService.descriptor()
        val requestNonce = ByteArray(24).also(secureRandom::nextBytes)
        val body = requirePrivateProfileEnsureBody(executeAuthorized { api, authorization ->
            api.ensurePrivateProfileKey(
                authorization,
                PrivateProfileEnsureKeyBody(
                    protocolVersion = PRIVATE_PROFILE_PROTOCOL_VERSION,
                    accountIdHash = accountIdHash,
                    requestingInstallationId = descriptor.installationId,
                    requestingKeyId = descriptor.keyId,
                    proposedKey = proposedKey.base64(),
                    requestNonce = requestNonce.base64(),
                ),
            )
        })
        return decodePrivateProfileKey(body, descriptor, accountIdHash, requestNonce)
    }

    override suspend fun fetchPrivateProfileDocument(accountIdHash: String): EncryptedPrivateProfileDocument? {
        require(ACCOUNT_HASH.matches(accountIdHash))
        val requestNonce = ByteArray(24).also(secureRandom::nextBytes)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.fetchPrivateProfileDocument(
                authorization,
                PrivateProfileFetchBody(PRIVATE_PROFILE_PROTOCOL_VERSION, accountIdHash, requestNonce.base64()),
            )
        })
        return decodePrivateProfileDocument(body, accountIdHash, requestNonce)
    }

    override suspend fun putPrivateProfileDocument(
        accountIdHash: String,
        keyVersion: Long,
        expectedDocumentVersion: Long,
        ciphertext: ByteArray,
        nonce: ByteArray,
        associatedDataSha256: ByteArray,
    ): EncryptedPrivateProfileDocument {
        require(ACCOUNT_HASH.matches(accountIdHash))
        require(keyVersion > 0 && expectedDocumentVersion >= 0)
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_PRIVATE_PROFILE_DOCUMENT_BYTES)
        require(nonce.size == 12 && associatedDataSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        val requestNonce = ByteArray(24).also(secureRandom::nextBytes)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.putPrivateProfileDocument(
                authorization,
                PrivateProfilePutBody(
                    protocolVersion = PRIVATE_PROFILE_PROTOCOL_VERSION,
                    accountIdHash = accountIdHash,
                    keyVersion = keyVersion,
                    expectedDocumentVersion = expectedDocumentVersion,
                    ciphertext = ciphertext.base64(),
                    nonce = nonce.base64(),
                    associatedDataSha256 = associatedDataSha256.hex(),
                    ciphertextSha256 = MessageDigest.getInstance(SHA256).digest(ciphertext).hex(),
                    requestNonce = requestNonce.base64(),
                ),
            )
        })
        return requireNotNull(decodePrivateProfileDocument(body, accountIdHash, requestNonce))
    }

    override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle {
        require(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        val body = requireBody(executeAuthorized { api, authorization ->
            api.resolveRoomLifecycle(
                authorization,
                RoomLifecycleResolveBody(ROOM_LIFECYCLE_PROTOCOL_VERSION, roomId),
            )
        })
        val roomDigest = MessageDigest.getInstance(SHA256).digest(roomId.encodeToByteArray())
        val responseDigest = body.roomIdSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        var contractDigest: ByteArray? = null
        var calculatedContractDigest: ByteArray? = null
        try {
            val configuration = requireConfiguration()
            val current = System.currentTimeMillis()
            verifySignature(configuration, roomLifecycleResponsePayload(body), signature.copyOf())
            val contractId = body.contractId
            val profileRevision = body.profileRevision
            val contractSha256 = body.contractSha256
            val signingKeyId = body.signingKeyId
            if (body.protocolVersion == ROOM_LIFECYCLE_PROTOCOL_VERSION) {
                if (contractId == null || profileRevision == null || contractSha256 == null || signingKeyId == null) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
                contractDigest = contractSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
                calculatedContractDigest = MessageDigest.getInstance(SHA256).digest(roomLifecycleContractPayload(body))
                val expectedSigningKeyId = MessageDigest.getInstance(SHA256)
                    .digest(configuration.signingPublicKeyDer)
                    .hex()
                    .take(SIGNING_KEY_ID_HEX_CHARS)
                if (!MessageDigest.isEqual(contractDigest, calculatedContractDigest) ||
                    signingKeyId != expectedSigningKeyId ||
                    !CONTRACT_ID.matches(contractId) ||
                    profileRevision <= 0
                ) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
            }
            if (body.protocolVersion != ROOM_LIFECYCLE_PROTOCOL_VERSION ||
                !MessageDigest.isEqual(roomDigest, responseDigest) ||
                body.profileId.length !in 3..128 || body.retentionMillis <= 0 ||
                body.maximumOfflineMillis < 0 || body.issuedAtEpochMillis >= body.expiresAtEpochMillis ||
                body.expiresAtEpochMillis - body.issuedAtEpochMillis > MAX_ROOM_LIFECYCLE_SNAPSHOT_MILLIS ||
                body.issuedAtEpochMillis > current + configuration.clockRollbackToleranceMillis ||
                body.expiresAtEpochMillis <= current - configuration.clockRollbackToleranceMillis ||
                body.retentionMillis > MAX_ROOM_RETENTION_MILLIS ||
                body.maximumOfflineMillis > MAX_ROOM_OFFLINE_MILLIS ||
                body.messageExpiresAtEpochMillis <= current ||
                body.messageExpiresAtEpochMillis < body.issuedAtEpochMillis + body.retentionMillis ||
                body.messageExpiresAtEpochMillis - body.issuedAtEpochMillis - body.retentionMillis >= ONE_DAY_MILLIS
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return ResolvedRoomLifecycle(
                roomIdSha256 = responseDigest.copyOf(),
                profileId = body.profileId,
                profileKind = RoomLifecycleProfileKind.valueOf(body.profileKind),
                retentionMillis = body.retentionMillis,
                maximumOfflineMillis = body.maximumOfflineMillis,
                recoveryPolicy = RoomLifecycleRecoveryPolicy.valueOf(body.recoveryPolicy),
                messageExpiresAtEpochMillis = body.messageExpiresAtEpochMillis,
                issuedAtEpochMillis = body.issuedAtEpochMillis,
                expiresAtEpochMillis = body.expiresAtEpochMillis,
                protocolVersion = body.protocolVersion,
                contractId = requireNotNull(contractId),
                profileRevision = requireNotNull(profileRevision),
                contractSha256 = requireNotNull(contractDigest).copyOf(),
                signingKeyId = requireNotNull(signingKeyId),
            )
        } catch (failure: IllegalArgumentException) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        } finally {
            roomDigest.fill(0)
            responseDigest.fill(0)
            signature.fill(0)
            contractDigest?.fill(0)
            calculatedContractDigest?.fill(0)
        }
    }

    override suspend fun assignCreatedRoomLifecycle(
        roomId: String,
        profileKind: RoomLifecycleProfileKind,
    ): Boolean {
        require(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        val response = requireBody(executeAuthorized { api, authorization ->
            api.assignCreatedRoomLifecycle(
                authorization,
                CreatedRoomLifecycleAssignmentBody(
                    protocolVersion = ROOM_LIFECYCLE_ASSIGNMENT_PROTOCOL_VERSION,
                    roomId = roomId,
                    profileKind = profileKind.name,
                ),
            )
        })
        val expectedRoomHash = MessageDigest.getInstance(SHA256).digest(roomId.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (response.roomIdSha256 != expectedRoomHash || response.profileKind != profileKind.name ||
            response.appliesTo != "NEW_MESSAGES_ONLY" || response.assignedAtEpochMillis <= 0
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        return true
    }

    override suspend fun registerMessageEvent(
        contentId: ContentId,
        roomId: String,
        eventId: String,
        mediaUris: Set<String>,
    ): Boolean {
        require(contentId.value.length in 16..256)
        require(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        require(eventId.startsWith("$") && eventId.length <= 512)
        require(mediaUris.size <= MAX_MESSAGE_MEDIA_URIS)
        require(mediaUris.all { it.startsWith("mxc://") && it.length <= MAX_MEDIA_URI_CHARS })
        val body = requireBody(executeAuthorized { api, authorization ->
            api.registerMessageEvent(
                authorization,
                MessageLifecycleEventBody(
                    protocolVersion = MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION,
                    contentId = contentId.value,
                    roomId = roomId,
                    eventId = eventId,
                    mediaUris = mediaUris.sorted(),
                ),
            )
        })
        if (body.contentId != contentId.value || body.eventAssetId.isBlank() ||
            body.mediaAssetIds.size != mediaUris.size || body.mediaAssetIds.any(String::isBlank)
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        return true
    }

    override suspend fun confirmMatrixRedaction(roomId: String, eventId: String): Boolean {
        require(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        require(eventId.startsWith("$") && eventId.length <= 512)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.confirmMatrixRedaction(
                authorization,
                MatrixRedactionConfirmationBody(
                    protocolVersion = MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION,
                    roomId = roomId,
                    eventId = eventId,
                ),
            )
        })
        val roomHash = MessageDigest.getInstance(SHA256).digest(roomId.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val eventHash = MessageDigest.getInstance(SHA256).digest(eventId.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (body.protocolVersion != MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION || body.state != "REDACTED" ||
            body.roomIdSha256 != roomHash || body.eventIdSha256 != eventHash ||
            body.evidenceSha256.length != ContentProtectionFormat.SHA256_SIZE_BYTES * 2 ||
            body.evidenceSha256.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        return true
    }

    override suspend fun registerCallRecord(roomId: String, eventId: String): Boolean {
        require(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        require(eventId.startsWith("$") && eventId.length <= 512)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.registerCallRecord(
                authorization,
                CallRecordLifecycleBody(
                    protocolVersion = MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION,
                    roomId = roomId,
                    eventId = eventId,
                ),
            )
        })
        val roomHash = MessageDigest.getInstance(SHA256).digest(roomId.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val eventHash = MessageDigest.getInstance(SHA256).digest(eventId.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (body.protocolVersion != MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION ||
            body.recordId.length != ContentProtectionFormat.SHA256_SIZE_BYTES * 2 ||
            body.roomIdSha256 != roomHash || body.eventIdSha256 != eventHash ||
            body.profileId.isBlank() || body.profileKind !in setOf("STANDARD", "CONFIDENTIAL") ||
            body.scheduledPurgeAtEpochMillis <= 0 || body.storeMedia || body.recordingEnabled
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        return true
    }

    override suspend fun startDeviceRecovery(request: StartDeviceRecoveryRequest): DeviceRecoveryStatus {
        require(ACCOUNT_HASH.matches(request.accountIdHash))
        require(request.newInstallationId.length in 16..256)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.startDeviceRecovery(
                authorization,
                DeviceRecoveryStartBody(request.accountIdHash, request.newInstallationId),
            )
        })
        return body.toStatus().also {
            // Fixed enum/boolean codes only: never log the response, account or installation.
            LifecycleStageTiming.point(
                "recovery_status", identity = it.state.name,
                scope = if (it.standardAuthorized) "STANDARD_AUTHORIZED" else "STANDARD_PENDING",
            )
            if (it.accountIdHash != request.accountIdHash || it.newInstallationId != request.newInstallationId) {
                LifecycleStageTiming.point("recovery_status_rejected", identity = "BINDING_MISMATCH")
                throw ContentProtectionException.AuthenticationFailed()
            }
        }
    }

    override suspend fun deviceRecoveryStatus(recoveryId: String): DeviceRecoveryStatus {
        require(recoveryId.length in 16..128)
        return requireBody(executeAuthorized { api, authorization ->
            api.deviceRecoveryStatus(authorization, recoveryId)
        }).toStatus().also { status ->
            if (status.recoveryId != recoveryId) throw ContentProtectionException.AuthenticationFailed()
        }
    }

    override suspend fun pendingDeviceRecoveries(accountIdHash: String): List<PendingDeviceRecovery> {
        require(ACCOUNT_HASH.matches(accountIdHash))
        val bodies = requireBody(executeAuthorized { api, authorization ->
            api.pendingDeviceRecoveries(authorization, DeviceRecoveryAccountBody(accountIdHash))
        })
        if (bodies.size > MAX_PENDING_RECOVERY_REQUESTS) throw ContentProtectionException.AuthenticationFailed()
        return bodies.map { body ->
            if (body.recoveryId.length !in 16..128 || body.newInstallationId.length !in 16..256 ||
                body.createdAtEpochMillis > body.requestExpiresAtEpochMillis
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            PendingDeviceRecovery(
                recoveryId = body.recoveryId,
                newInstallationId = body.newInstallationId,
                standardAuthorized = body.standardAuthorized,
                createdAtEpochMillis = body.createdAtEpochMillis,
                requestExpiresAtEpochMillis = body.requestExpiresAtEpochMillis,
            )
        }
    }

    override suspend fun confirmDeviceRecovery(request: ConfirmDeviceRecoveryRequest): DeviceRecoveryStatus {
        require(request.recoveryId.length in 16..128)
        require(ACCOUNT_HASH.matches(request.accountIdHash))
        val body = requireBody(executeAuthorized { api, authorization ->
            api.confirmDeviceRecovery(
                authorization,
                request.recoveryId,
                DeviceRecoveryAccountBody(request.accountIdHash),
            )
        })
        return body.toStatus().also {
            if (it.recoveryId != request.recoveryId || it.accountIdHash != request.accountIdHash || !it.oldDeviceConfirmed) {
                throw ContentProtectionException.AuthenticationFailed()
            }
        }
    }

    override suspend fun acquireRoomEpoch(request: AcquireRoomEpochRequest): ReleasedRoomEpoch {
        validateAcquireRoomEpochRequest(request)
        val response = executeAuthorized { api, authorization ->
            api.acquireRoomEpoch(
                authorization,
                AcquireRoomEpochBody(
                    protocolVersion = ROOM_EPOCH_PROTOCOL_VERSION,
                    roomId = request.roomId,
                    roomIdSha256 = request.roomIdSha256.hex(),
                    accountIdHash = request.accountIdHash,
                    audienceAccountIdHashes = request.audienceAccountIdHashes.sorted(),
                    authorizedInstallationIds = request.authorizedInstallationIds.sorted(),
                    requestingInstallationId = request.requestingInstallationId,
                    requestingKeyId = request.requestingKeyId,
                    requestNonce = request.requestNonce.base64(),
                    minimumActiveMillis = request.minimumActiveMillis,
                )
            )
        }
        return decodeRoomEpoch(
            requireRoomEpochBody(response, includeReleaseReasons = false),
            request.roomIdSha256,
            request.accountIdHash,
            request.requestingInstallationId,
            request.requestingKeyId,
            request.requestNonce,
        )
    }

    override suspend fun releaseRoomEpoch(request: ReleaseRoomEpochRequest): ReleasedRoomEpoch {
        validateReleaseRoomEpochRequest(request)
        val response = executeTracedAuthorized("epoch_release", request.reference.epochId) { api, authorization, trace ->
            api.releaseRoomEpoch(
                authorization,
                ReleaseRoomEpochBody(
                    protocolVersion = ROOM_EPOCH_PROTOCOL_VERSION,
                    epochId = request.reference.epochId,
                    generation = request.reference.generation,
                    roomIdSha256 = request.roomIdSha256.hex(),
                    audienceDigestSha256 = request.reference.audienceDigestSha256.hex(),
                    accountIdHash = request.accountIdHash,
                    requestingInstallationId = request.requestingInstallationId,
                    requestingKeyId = request.requestingKeyId,
                    requestNonce = request.requestNonce.base64(),
                ),
                trace,
            )
        }
        return LifecycleStageTiming.measure("receive_epoch_verify_and_unwrap", request.reference.epochId) {
            decodeRoomEpoch(
            requireRoomEpochBody(response, includeReleaseReasons = true),
            request.roomIdSha256,
            request.accountIdHash,
            request.requestingInstallationId,
            request.requestingKeyId,
            request.requestNonce,
            request.reference,
        )
        }
    }

    override suspend fun storeShare(request: StoreKmsShareRequest): KmsShareReference {
        validateStoreRequest(request)
        val lifecycleContract = request.roomLifecycle?.takeIf {
            it.protocolVersion == ROOM_LIFECYCLE_PROTOCOL_VERSION &&
                request.lifecycle.mode == io.element.android.security.contentprotection.api.MessageLifecycleMode.FOLLOW_ROOM
        }
        val storeProtocolVersion = if (lifecycleContract == null) KMS_PROTOCOL_VERSION else KMS_CONTRACT_PROTOCOL_VERSION
        val response = executeAuthorized { api, authorization ->
            api.storeShare(
                authorization,
                StoreShareBody(
                    protocolVersion = storeProtocolVersion,
                    contentId = request.contentId.value,
                    contentKind = request.kind.name,
                    policyId = request.policy.policyId.value,
                    policyVersion = request.policy.policyVersion,
                    expiresAtEpochMillis = request.policy.expiresAtEpochMillis,
                    accountIdHash = request.accountIdHash,
                    senderInstallationId = request.senderInstallationId,
                    audienceAccountIdHashes = request.audienceAccountIdHashes.sorted(),
                    authorizedInstallationIds = request.authorizedInstallationIds.sorted(),
                    immutableContextSha256 = request.immutableContextSha256.base64(),
                    kmsShare = request.kmsShare.base64(),
                    leaseMode = request.leaseMode.name,
                    roomId = request.roomId,
                    roomEpochId = request.roomEpoch?.epochId,
                    roomEpochGeneration = request.roomEpoch?.generation,
                    roomAudienceDigestSha256 = request.roomEpoch?.audienceDigestSha256?.hex(),
                    lifecycleMode = request.lifecycle.mode.name,
                    conversationKind = request.lifecycle.conversationKind?.name,
                    lifecycleContractId = lifecycleContract?.contractId,
                    lifecycleContractSha256 = lifecycleContract?.contractSha256?.hex(),
                    clientCreatedAtEpochMillis = lifecycleContract?.let { request.clientCreatedAtEpochMillis },
                    matrixTransactionIdSha256 = lifecycleContract?.let { request.matrixTransactionIdSha256?.hex() },
                )
            )
        }
        val body = requireStoreBody(response, canRefreshLifecycleContract = lifecycleContract != null)
        val configuration = requireConfiguration()
        verifySignature(
            configuration = configuration,
            payload = storeResponsePayload(body, request),
            signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES),
        )
        if (body.protocolVersion != storeProtocolVersion) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        require(body.reference.isNotBlank())
        return KmsShareReference(body.reference)
    }

    override suspend fun openBurnAfterRead(request: OpenBurnAfterReadRequest): BurnAfterReadSession {
        require(request.contentId.value.isNotBlank())
        require(ACCOUNT_HASH.matches(request.accountIdHash))
        require(request.requestingInstallationId.isNotBlank())
        require(request.sessionId.matches(SESSION_ID))
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
        val body = requireBody(executeAuthorized { api, authorization ->
            api.openBurnAfterRead(
                authorization,
                OpenBurnAfterReadBody(
                    protocolVersion = BURN_AFTER_READ_PROTOCOL_VERSION,
                    contentId = request.contentId.value,
                    accountIdHash = request.accountIdHash,
                    requestingInstallationId = request.requestingInstallationId,
                    sessionId = request.sessionId,
                    requestNonce = request.requestNonce.base64(),
                ),
            )
        })
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(requireConfiguration(), openBurnAfterReadPayload(body), signature.copyOf())
            if (body.protocolVersion != BURN_AFTER_READ_PROTOCOL_VERSION ||
                body.contentId != request.contentId.value || body.accountIdHash != request.accountIdHash ||
                body.sessionId != request.sessionId || body.openedAtEpochMillis > body.serverTimeEpochMillis ||
                body.serverTimeEpochMillis >= body.viewExpiresAtEpochMillis ||
                body.viewExpiresAtEpochMillis > body.globalExpiresAtEpochMillis ||
                body.rootOperationId?.isBlank() == true || body.grantState?.let { it != "VIEWING" } == true ||
                body.terminalState?.let { it != "ACCESS_ACTIVE" } == true ||
                body.viewOperationId?.let { it != currentLifecycleOperationContext()?.operationId } == true
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return BurnAfterReadSession(
                contentId = request.contentId,
                accountIdHash = request.accountIdHash,
                installationId = request.requestingInstallationId,
                sessionId = request.sessionId,
                contentKind = io.element.android.security.contentprotection.api.ContentKind.valueOf(body.contentKind),
                windowMode = BurnAfterReadWindowMode.valueOf(body.readWindowMode),
                openedAtEpochMillis = body.openedAtEpochMillis,
                viewExpiresAtEpochMillis = body.viewExpiresAtEpochMillis,
                globalExpiresAtEpochMillis = body.globalExpiresAtEpochMillis,
                serverTimeEpochMillis = body.serverTimeEpochMillis,
            )
        } catch (failure: IllegalArgumentException) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        } finally {
            signature.fill(0)
        }
    }

    override suspend fun endBurnAfterRead(request: EndBurnAfterReadRequest): BurnAfterReadEndResult {
        require(request.contentId.value.isNotBlank())
        require(ACCOUNT_HASH.matches(request.accountIdHash))
        require(request.requestingInstallationId.isNotBlank())
        require(request.sessionId.matches(SESSION_ID))
        val body = requireBody(executeAuthorized { api, authorization ->
            api.endBurnAfterRead(
                authorization,
                EndBurnAfterReadBody(
                    protocolVersion = BURN_AFTER_READ_PROTOCOL_VERSION,
                    contentId = request.contentId.value,
                    accountIdHash = request.accountIdHash,
                    requestingInstallationId = request.requestingInstallationId,
                    sessionId = request.sessionId,
                    reason = request.reason.name,
                ),
            )
        })
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(requireConfiguration(), endBurnAfterReadPayload(body), signature.copyOf())
            if (body.protocolVersion != BURN_AFTER_READ_PROTOCOL_VERSION ||
                body.contentId != request.contentId.value || body.accountIdHash != request.accountIdHash ||
                body.sessionId != request.sessionId || body.state !in setOf("REVOKED", "EXPIRED") ||
                body.endedAtEpochMillis > body.serverTimeEpochMillis ||
                body.rootOperationId?.isBlank() == true ||
                body.viewOperationId?.let { it != currentLifecycleOperationContext()?.operationId } == true ||
                body.grantState?.let { it != body.state } == true ||
                body.terminalState?.let { it !in BURN_TERMINAL_STATES } == true ||
                body.receiptReferences.any(String::isBlank)
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return BurnAfterReadEndResult(
                contentId = request.contentId,
                accountIdHash = request.accountIdHash,
                sessionId = request.sessionId,
                state = body.state,
                endedAtEpochMillis = body.endedAtEpochMillis,
                allAccessEnded = body.allAccessEnded,
                serverTimeEpochMillis = body.serverTimeEpochMillis,
                rootOperationId = body.rootOperationId.orEmpty(),
                viewOperationId = body.viewOperationId ?: currentLifecycleOperationContext()?.operationId.orEmpty(),
                terminalState = body.terminalState ?: if (body.allAccessEnded) "ACCESS_ENDED" else "ACCESS_ACTIVE",
                keyDestroyed = body.keyDestroyed,
                cleanupPending = body.cleanupPending,
                receiptReferences = body.receiptReferences,
            )
        } finally {
            signature.fill(0)
        }
    }

    override suspend fun releaseShare(request: ReleaseKmsShareRequest): ReleasedKmsShare = releaseShares(listOf(request)).single()

    override suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> {
        require(requests.isNotEmpty() && requests.size <= MAX_BATCH_RELEASE_SIZE)
        requests.forEach(::validateReleaseRequest)
        val response = executeReleaseBatchAuthorized(requests)
        val bodies = requireBody(response)
        if (bodies.size != requests.size) throw ContentProtectionException.AuthenticationFailed()
        val configuration = requireConfiguration()
        val released = ArrayList<ReleasedKmsShare>(requests.size)
        try {
            requests.zip(bodies).forEach { (request, body) ->
                val lease = LifecycleStageTiming.measure("receive_lease_verify", request.contentId.value) {
                    decodeLease(configuration, request, body)
                }
                released += LifecycleStageTiming.measure("receive_kms_share_unwrap", request.contentId.value) {
                    releaseOnlineShare(request, lease)
                }
            }
            return released
        } catch (failure: Throwable) {
            released.forEach { it.kmsShare.fill(0) }
            throw failure
        }
    }

    /**
     * A V3 share remains unavailable until its encrypted Matrix event is independently observed and bound by the
     * control plane. The event may reach a recipient just before that binding transaction commits. Only that exact,
     * structured 409 is retried; the server continues to withhold every key while the share is PREPARED.
     *
     * Every attempt goes back through [executeAuthorized], so it carries a newly minted, single-use device proof.
     * Cancellation is never swallowed and all other authorization, expiry, recovery and conflict responses remain
     * fail-closed.
     */
    private suspend fun executeReleaseBatchAuthorized(
        requests: List<ReleaseKmsShareRequest>,
    ): Response<List<ReleaseShareResponse>> {
        repeat(MAX_EVENT_BINDING_ATTEMPTS) { attempt ->
            val response = executeTracedAuthorized("share_release", requests.singleOrNull()?.contentId?.value.orEmpty()) { api, authorization, trace ->
                api.releaseShares(
                    authorization = authorization,
                    bodies = requests.map(::releaseShareBody),
                    trace = trace,
                )
            }
            if (attempt == MAX_EVENT_BINDING_ATTEMPTS - 1 || !response.isEventBindingPending()) {
                return response
            }
            LifecycleStageTiming.measure("kms_binding_backoff", scope = "attempt_${attempt + 1}") {
                delay(eventBindingRetryDelayMillis(attempt))
            }
        }
        throw ContentProtectionException.KmsUnavailable()
    }

    private fun Response<*>.isEventBindingPending(): Boolean {
        if (code() != 409) return false
        val error = runCatching {
            errorBody()?.use { body -> ERROR_JSON.decodeFromString<ControlPlaneErrorResponse>(body.string()) }
        }.getOrNull()
        return error?.detail?.code == MATRIX_EVENT_BINDING_PENDING && error.detail.retryable
    }

    private fun eventBindingRetryDelayMillis(attempt: Int): Long {
        val ceiling = (EVENT_BINDING_RETRY_BACKOFF_MILLIS shl attempt).coerceAtMost(MAX_EVENT_BINDING_RETRY_DELAY_MILLIS)
        return Random.nextLong(ceiling / 2, ceiling + 1)
    }

    private suspend fun releaseOnlineShare(request: ReleaseKmsShareRequest, lease: OfflineLease): ReleasedKmsShare {
        if (lease.mode != OfflineLeaseMode.ONLINE_REQUIRED) {
            lease.encryptedKmsShare.fill(0)
            lease.serverSignature.fill(0)
            logKmsDenied("ONLINE_MODE_MISMATCH")
            throw ContentProtectionException.AccessDenied()
        }
        try {
            val kmsShare = deviceShareKeyService.unwrapShare(
                envelope = DeviceShareEnvelope(
                    recipientInstallationId = lease.installationId,
                    recipientKeyId = lease.recipientKeyId,
                    algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
                    ciphertext = lease.encryptedKmsShare,
                ),
                contentId = request.contentId,
                immutableContextSha256 = lease.immutableContextSha256,
            )
            return ReleasedKmsShare(
                grantId = lease.leaseId,
                reference = lease.reference,
                contentId = lease.contentId,
                policyVersion = lease.policyVersion,
                installationId = lease.installationId,
                immutableContextSha256 = lease.immutableContextSha256.copyOf(),
                requestNonce = request.requestNonce.copyOf(),
                mode = lease.mode,
                issuedAtEpochMillis = lease.issuedAtEpochMillis,
                expiresAtEpochMillis = lease.expiresAtEpochMillis,
                authorizationDeadlineEpochMillis = lease.expiresAtEpochMillis,
                serverTimeEpochMillis = lease.serverTimeEpochMillis,
                bootCount = lease.bootCount,
                kmsShare = kmsShare,
                burnSessionId = lease.burnSessionId,
                progressivePlaybackDeadlineEpochMillis = lease.progressivePlaybackDeadlineEpochMillis,
            )
        } finally {
            lease.encryptedKmsShare.fill(0)
            lease.serverSignature.fill(0)
        }
    }

    override suspend fun issueOfflineLease(request: ReleaseKmsShareRequest): OfflineLease = requestLease(request).also { lease ->
        if (lease.mode != OfflineLeaseMode.BOUNDED_OFFLINE) {
            lease.encryptedKmsShare.fill(0)
            lease.serverSignature.fill(0)
            logKmsDenied("OFFLINE_MODE_MISMATCH")
            throw ContentProtectionException.AccessDenied()
        }
    }

    @Suppress("ComplexCondition")
    override suspend fun fetchRevocations(request: FetchRevocationsRequest): RemoteRevocationSnapshot {
        validateRevocationRequest(request)
        val response = executeAuthorized { api, authorization ->
            api.fetchRevocations(
                authorization,
                FetchRevocationsBody(
                    protocolVersion = KMS_PROTOCOL_VERSION,
                    accountIdHash = request.accountIdHash,
                    installationId = request.installationId,
                    knownSequence = request.knownSequence,
                    requestNonce = request.requestNonce.base64(),
                )
            )
        }
        val body = requireBody(response)
        val nonce = body.requestNonce.decodeBase64(MAX_REQUEST_NONCE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(requireConfiguration(), revocationResponsePayload(body), signature.copyOf())
            val deviceState = try {
                RemoteDeviceAccessState.valueOf(body.deviceAccessState)
            } catch (failure: IllegalArgumentException) {
                throw ContentProtectionException.AuthenticationFailed(failure)
            }
            if (body.protocolVersion != KMS_PROTOCOL_VERSION || body.accountIdHash != request.accountIdHash ||
                body.installationId != request.installationId || body.sequence < request.knownSequence ||
                !MessageDigest.isEqual(nonce, request.requestNonce) || body.issuedAtEpochMillis <= 0 ||
                body.refreshAfterEpochMillis <= body.issuedAtEpochMillis || body.expiresAtEpochMillis < body.refreshAfterEpochMillis ||
                body.revokedLeaseIds.size > MAX_REVOCATION_IDS || body.revokedContentIds.size > MAX_REVOCATION_IDS ||
                body.revokedLeaseIds.any(String::isBlank) || body.revokedContentIds.any(String::isBlank)
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return RemoteRevocationSnapshot(
                accountIdHash = body.accountIdHash,
                installationId = body.installationId,
                sequence = body.sequence,
                issuedAtEpochMillis = body.issuedAtEpochMillis,
                refreshAfterEpochMillis = body.refreshAfterEpochMillis,
                expiresAtEpochMillis = body.expiresAtEpochMillis,
                requestNonce = nonce.copyOf(),
                deviceAccessState = deviceState,
                revokedLeaseIds = body.revokedLeaseIds.toSet(),
                revokedContentIds = body.revokedContentIds.mapTo(mutableSetOf(), ::ContentId),
                serverSignature = signature.copyOf(),
            )
        } finally {
            nonce.fill(0)
            signature.fill(0)
        }
    }

    private suspend fun requestLease(request: ReleaseKmsShareRequest): OfflineLease {
        validateReleaseRequest(request)
        val response = executeAuthorized { api, authorization ->
            api.releaseShare(
                authorization,
                releaseShareBody(request),
            )
        }
        val body = requireBody(response)
        val configuration = requireConfiguration()
        return decodeLease(configuration, request, body)
    }

    private fun releaseShareBody(request: ReleaseKmsShareRequest) = ReleaseShareBody(
        protocolVersion = KMS_PROTOCOL_VERSION,
        reference = request.reference.value,
        contentId = request.contentId.value,
        policyVersion = request.policy.policyVersion,
        accountIdHash = request.accountIdHash,
        requestingInstallationId = request.requestingInstallationId,
        requestingKeyId = request.requestingKeyId,
        immutableContextSha256 = request.immutableContextSha256.base64(),
        requestNonce = request.requestNonce.base64(),
        bootCount = request.bootCount,
        burnSessionId = request.burnSessionId,
        progressiveVideoPlayback = if (request.progressiveVideoPlayback) "PROGRESSIVE_VIDEO_V1" else null,
    )

    private fun decodeLease(
        configuration: KmsClientConfiguration,
        request: ReleaseKmsShareRequest,
        body: ReleaseShareResponse,
    ): OfflineLease {
        val immutableContext = body.immutableContextSha256.decodeBase64(ContentProtectionFormat.SHA256_SIZE_BYTES)
        val requestNonce = body.requestNonce.decodeBase64(MAX_REQUEST_NONCE_BYTES)
        val encryptedKmsShare = body.encryptedKmsShare.decodeBase64(MAX_WRAPPED_SHARE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(configuration, releaseResponsePayload(body), signature.copyOf())
            validateReleaseResponse(configuration, request, body, immutableContext, requestNonce)
            val playbackDeadline = verifyProgressivePlayback(configuration, request, body)
            return OfflineLease(
                leaseId = body.grantId,
                reference = KmsShareReference(body.reference),
                contentId = ContentId(body.contentId),
                accountIdHash = body.accountIdHash,
                installationId = body.installationId,
                recipientKeyId = body.recipientKeyId,
                mode = OfflineLeaseMode.valueOf(body.leaseMode),
                policyVersion = body.policyVersion,
                immutableContextSha256 = immutableContext.copyOf(),
                requestNonce = requestNonce.copyOf(),
                bootCount = body.bootCount,
                issuedAtEpochMillis = body.issuedAtEpochMillis,
                expiresAtEpochMillis = body.expiresAtEpochMillis,
                serverTimeEpochMillis = body.serverTimeEpochMillis,
                refreshAfterEpochMillis = body.refreshAfterEpochMillis,
                revocationSequence = body.revocationSequence,
                maximumUses = body.maximumUses,
                encryptedKmsShare = encryptedKmsShare.copyOf(),
                serverSignature = signature.copyOf(),
                burnSessionId = body.burnSessionId,
                progressivePlaybackDeadlineEpochMillis = playbackDeadline,
            )
        } finally {
            immutableContext.fill(0)
            requestNonce.fill(0)
            encryptedKmsShare.fill(0)
            signature.fill(0)
        }
    }

    @Suppress("ComplexCondition")
    private suspend fun decodeRoomEpoch(
        body: RoomEpochResponse,
        expectedRoomIdSha256: ByteArray,
        expectedAccountIdHash: String,
        expectedInstallationId: String,
        expectedRecipientKeyId: String,
        expectedRequestNonce: ByteArray,
        expectedReference: RoomEpochReference? = null,
    ): ReleasedRoomEpoch {
        val roomIdSha256 = body.roomIdSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
        val audienceDigest = body.audienceDigestSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
        val requestNonce = body.requestNonce.decodeBase64(MAX_REQUEST_NONCE_BYTES)
        val encryptedSecret = body.encryptedEpochSecret.decodeBase64(MAX_WRAPPED_SHARE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        var epochSecret: ByteArray? = null
        try {
            verifySignature(requireConfiguration(), roomEpochResponsePayload(body), signature.copyOf())
            if (body.protocolVersion != ROOM_EPOCH_PROTOCOL_VERSION || body.epochId.isBlank() || body.generation <= 0 ||
                body.accountIdHash != expectedAccountIdHash || body.installationId != expectedInstallationId ||
                body.recipientKeyId != expectedRecipientKeyId ||
                body.wrappingAlgorithm != ContentProtectionFormat.DEVICE_SHARE_ALGORITHM ||
                !MessageDigest.isEqual(roomIdSha256, expectedRoomIdSha256) ||
                !MessageDigest.isEqual(requestNonce, expectedRequestNonce) ||
                body.serverTimeEpochMillis >= body.releaseUntilEpochMillis ||
                (expectedReference != null && (body.epochId != expectedReference.epochId ||
                    body.generation != expectedReference.generation ||
                    !MessageDigest.isEqual(audienceDigest, expectedReference.audienceDigestSha256)))
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            epochSecret = deviceShareKeyService.unwrapShare(
                envelope = DeviceShareEnvelope(
                    recipientInstallationId = body.installationId,
                    recipientKeyId = body.recipientKeyId,
                    algorithm = body.wrappingAlgorithm,
                    ciphertext = encryptedSecret,
                ),
                contentId = ContentId("room-epoch:${body.epochId}"),
                immutableContextSha256 = audienceDigest,
            )
            if (epochSecret.size != ContentProtectionFormat.SHARE_SIZE_BYTES) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            return ReleasedRoomEpoch(
                reference = RoomEpochReference(body.epochId, body.generation, audienceDigest.copyOf()),
                roomIdSha256 = roomIdSha256.copyOf(),
                accountIdHash = body.accountIdHash,
                installationId = body.installationId,
                recipientKeyId = body.recipientKeyId,
                requestNonce = requestNonce.copyOf(),
                activeUntilEpochMillis = body.activeUntilEpochMillis,
                releaseUntilEpochMillis = body.releaseUntilEpochMillis,
                serverTimeEpochMillis = body.serverTimeEpochMillis,
                epochSecret = epochSecret,
            )
        } catch (failure: Throwable) {
            epochSecret?.fill(0)
            throw failure
        } finally {
            roomIdSha256.fill(0)
            audienceDigest.fill(0)
            requestNonce.fill(0)
            encryptedSecret.fill(0)
            signature.fill(0)
        }
    }

    override suspend fun destroyShare(contentId: ContentId, reason: ContentAccessDestroyReason): Boolean {
        require(contentId.value.isNotBlank())
        val response = executeAuthorized { api, authorization ->
            api.destroyShare(
                authorization,
                DestroyShareBody(
                    protocolVersion = KMS_PROTOCOL_VERSION,
                    contentId = contentId.value,
                    reason = reason.name,
                )
            )
        }
        val body = requireBody(response)
        val configuration = requireConfiguration()
        verifySignature(configuration, destroyResponsePayload(body, contentId, reason), body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES))
        return body.destroyed
    }

    override suspend fun resolveRedactionReasons(
        eventIdSha256: Set<String>,
    ): Map<String, TrustedRedactionReason> {
        if (eventIdSha256.isEmpty()) return emptyMap()
        require(eventIdSha256.size <= MAX_REDACTION_REASON_HASHES)
        require(eventIdSha256.all(EVENT_ID_HASH::matches))
        val body = requireBody(executeTracedAuthorized("redaction_reason_lookup") { api, authorization, _ ->
            api.resolveRedactionReasons(
                authorization,
                RedactionReasonResolveBody(
                    protocolVersion = REDACTION_REASON_PROTOCOL_VERSION,
                    eventIdSha256 = eventIdSha256.sorted(),
                ),
            )
        })
        if (body.protocolVersion != REDACTION_REASON_PROTOCOL_VERSION || body.receipts.size > eventIdSha256.size) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        val configuration = requireConfiguration()
        val expectedKeyId = MessageDigest.getInstance(SHA256)
            .digest(configuration.signingPublicKeyDer)
            .hex()
            .take(SIGNING_KEY_ID_HEX_CHARS)
        val current = System.currentTimeMillis()
        val resolved = linkedMapOf<String, TrustedRedactionReason>()
        body.receipts.forEach { receipt ->
            val signature = receipt.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
            try {
                verifySignature(configuration, redactionReasonPayload(receipt), signature)
                val reason = runCatching { TrustedRedactionReason.valueOf(receipt.reasonCode) }
                    .getOrElse { throw ContentProtectionException.AuthenticationFailed() }
                if (receipt.protocolVersion != REDACTION_REASON_PROTOCOL_VERSION ||
                    receipt.eventIdSha256 !in eventIdSha256 || !EVENT_ID_HASH.matches(receipt.eventIdSha256) ||
                    !RECEIPT_ID.matches(receipt.lifecycleReceiptId) || receipt.keyId != expectedKeyId ||
                    receipt.issuedAtEpochMillis <= 0 ||
                    receipt.issuedAtEpochMillis > current + configuration.clockRollbackToleranceMillis ||
                    resolved.put(receipt.eventIdSha256, reason) != null
                ) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
            } finally {
                signature.fill(0)
            }
        }
        return resolved
    }

    /**
     * Retries only failures that can be caused by an upstream disappearing during a load-balancer handoff.
     * A fresh authorization proof is minted for every attempt because device proofs contain single-use nonces.
     * Store retries are safe because the control plane binds an idempotency digest to the content id.
     */
    private suspend fun <T> executeAuthorized(block: suspend (KmsApi, String) -> Response<T>): Response<T> =
        executeTracedAuthorized { api, authorization, _ -> block(api, authorization) }

    private suspend fun <T> executeTracedAuthorized(
        action: String = "other",
        logicalIdentity: String = "",
        block: suspend (KmsApi, String, KmsTransportTrace) -> Response<T>,
    ): Response<T> {
        var lastTransportFailure: Exception? = null
        repeat(MAX_TRANSIENT_ATTEMPTS) { attempt ->
            val trace = KmsTransportTrace(currentLifecycleOperationContext()?.operationId.orEmpty(), action)
            LifecycleStageTiming.point("kms_attempt_link", logicalIdentity, trace.requestId)
            val authorization = LifecycleStageTiming.measure("kms_device_proof", identity = trace.requestId, scope = "attempt_${attempt + 1}") {
                requireAuthorization(trace).asHeaderValue()
            }
            try {
                trace.startAttempt()
                val response = LifecycleStageTiming.measure("kms_http_attempt", identity = trace.requestId, scope = "attempt_${attempt + 1}") {
                    block(api(), authorization, trace)
                }
                runCatching { trace.converted() }
                LifecycleStageTiming.point(
                    "kms_http_response",
                    identity = response.headers()["X-YiZhe-Trace-Id"].orEmpty(),
                    scope = "http_${response.code()}"
                )
                if (response.code() !in TRANSIENT_HTTP_STATUSES || attempt == MAX_TRANSIENT_ATTEMPTS - 1) {
                    return response
                }
                response.errorBody()?.close()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ContentProtectionException) {
                throw failure
            } catch (failure: Exception) {
                lastTransportFailure = failure
                if (attempt == MAX_TRANSIENT_ATTEMPTS - 1) {
                    throw ContentProtectionException.KmsUnavailable(failure)
                }
            }
            LifecycleStageTiming.measure("kms_transport_backoff", identity = trace.requestId, scope = "attempt_${attempt + 1}") {
                delay(TRANSIENT_RETRY_BACKOFF_MILLIS * (attempt + 1))
            }
        }
        throw ContentProtectionException.KmsUnavailable(lastTransportFailure)
    }

    private fun api(): KmsApi {
        val configuration = requireConfiguration()
        val identity = configurationIdentity(configuration)
        cachedApi?.takeIf { it.identity == identity }?.let { return it.api }
        return synchronized(this) {
            cachedApi?.takeIf { it.identity == identity }?.api ?: createApi(configuration).also {
                cachedApi = CachedApi(identity, it)
            }
        }
    }

    private fun createApi(configuration: KmsClientConfiguration): KmsApi {
        val baseUrl = configuration.baseUrl.toHttpUrl()
        if (!configuration.allowCleartextForTests && !baseUrl.isHttps) throw ContentProtectionException.KmsUnavailable()
        val client = OkHttpClient.Builder()
            .addInterceptor(KmsTransportTiming.interceptor)
            .eventListenerFactory(KmsTransportTiming.listenerFactory)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply {
                if (configuration.certificatePinsSha256.isNotEmpty()) {
                    certificatePinner(
                        CertificatePinner.Builder().apply {
                            configuration.certificatePinsSha256.forEach { pin -> add(baseUrl.host, pin) }
                        }.build()
                    )
                }
            }
            // Deliberately no HTTP logging interceptor: KMS request bodies contain a KMS share.
            .build()
        val json = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(KmsApi::class.java)
    }

    private fun requireConfiguration(): KmsClientConfiguration = configurationProvider.configuration()?.also { configuration ->
        require(configuration.signingPublicKeyDer.isNotEmpty())
        require(configuration.maximumOfflineLeaseMillis >= 0)
        require(configuration.maximumOfflineLicenseUses >= 0)
        require(configuration.clockRollbackToleranceMillis >= 0)
    } ?: throw ContentProtectionException.KmsUnavailable()

    private suspend fun requireAuthorization(trace: KmsTransportTrace): KmsRequestAuthorization =
        (if (authorizationProvider is DeviceProofKmsAuthorizationProvider) {
            authorizationProvider.authorizationForTrace(trace)
        } else {
            authorizationProvider.authorization()
        }) ?: throw ContentProtectionException.KmsUnavailable()

    private fun <T> requireBody(response: Response<T>): T {
        if (response.code() == 403 || response.code() == 409) {
            val detail = runCatching {
                response.errorBody()?.use { body ->
                    val length = body.contentLength()
                    if (length !in 1..MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong()) return@use null
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(
                        body.source().readByteArray(length).decodeToString(),
                    ).detail
                }
            }.getOrNull()
            val reason = when (detail) {
                "CONTACT_BLOCKED_BY_YOU" -> throw ContentProtectionException.ContactRestricted(true)
                "CONTACT_UNAVAILABLE" -> throw ContentProtectionException.ContactRestricted(false)
                "content binding mismatch" -> "CONTENT_BINDING_MISMATCH"
                "DEVICE_RECOVERY_REQUIRED" -> "DEVICE_RECOVERY_REQUIRED"
                "device content key mismatch" -> "DEVICE_CONTENT_KEY_MISMATCH"
                "requesting installation mismatch" -> "REQUESTING_INSTALLATION_MISMATCH"
                "unknown content policy" -> "UNKNOWN_CONTENT_POLICY"
                "managed device license does not allow this content scope" -> "MANAGED_SCOPE_DENIED"
                "account is not bound to this device" -> "ACCOUNT_DEVICE_MISMATCH"
                "account is active on another mobile installation" -> "ACTIVE_INSTALLATION_MISMATCH"
                "device is not trusted" -> "DEVICE_UNTRUSTED"
                "offline access is disabled" -> "OFFLINE_ACCESS_DISABLED"
                "lifecycle operation belongs to another installation" -> "LIFECYCLE_INSTALLATION_MISMATCH"
                "lifecycle operation state cannot authorize this request" -> "LIFECYCLE_STATE_REJECTED"
                "expired lifecycle operation cannot authorize this request" -> "LIFECYCLE_EXPIRED"
                "lifecycle operation changed during request" -> "LIFECYCLE_CHANGED"
                "operationId is already bound to different lineage data" -> "LINEAGE_OPERATION_MISMATCH"
                else -> "UNKNOWN"
            }
            logKmsDenied("HTTP_DETAIL_$reason", response.code())
        }
        if (response.code() == 401 || response.code() == 403) {
            logKmsDenied("HTTP_AUTHORIZATION", response.code())
            throw ContentProtectionException.AccessDenied()
        }
        // The control plane uses 410 when a specific share is expired, destroyed, or no longer available.
        // A 404 can instead mean that this client is newer than the deployed control plane and the endpoint
        // itself does not exist. Treating that compatibility failure as content expiry is both misleading and
        // prevents a safe retry after the server is upgraded.
        if (response.code() == 410) throw ContentProtectionException.Expired()
        if (response.code() == 423) throw ContentProtectionException.RecoveryApprovalRequired()
        if (response.code() == 404) throw ContentProtectionException.KmsUnavailable()
        if (!response.isSuccessful) throw ContentProtectionException.KmsUnavailable()
        return response.body() ?: throw ContentProtectionException.KmsUnavailable()
    }

    private fun <T> requireRoomEpochBody(response: Response<T>, includeReleaseReasons: Boolean): T {
        if (includeReleaseReasons && response.code() == 410) {
            val detail = runCatching {
                response.errorBody()?.use { body ->
                    val length = body.contentLength()
                    if (length !in 1..MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong()) return@use null
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(body.source().readByteArray(length).decodeToString()).detail
                }
            }.getOrNull()
            // The old authority's generic 410 never proved TTL expiry. Unknown bodies stay fail closed.
            if (detail == "ROOM_EPOCH_RETENTION_EXPIRED") throw ContentProtectionException.Expired()
            throw ContentProtectionException.RoomEpochMaterialUnavailable()
        }
        if (response.code() == 409) {
            val detail = runCatching {
                response.errorBody()?.use { body ->
                    val length = body.contentLength()
                    if (length !in 1..MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong()) return@use null
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(body.source().readByteArray(length).decodeToString()).detail
                }
            }.getOrNull()
            val reason = when (detail) {
                "room membership changed; refresh recipients and retry" -> ContentProtectionException.RoomEpochConflict.Reason.MEMBERSHIP_CHANGED
                "trusted room device set changed; refresh recipients and retry" -> ContentProtectionException.RoomEpochConflict.Reason.TRUSTED_DEVICES_CHANGED
                else -> ContentProtectionException.RoomEpochConflict.Reason.UNKNOWN
            }
            logKmsDenied("ROOM_EPOCH_${reason.name}", response.code())
            throw ContentProtectionException.RoomEpochConflict(reason)
        }
        if (response.code() == 403) {
            val detail = runCatching {
                response.errorBody()?.use { body ->
                    val contentLength = body.contentLength()
                    if (contentLength !in 1..MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong()) return@use null
                    val bytes = body.source().readByteArray(contentLength)
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(bytes.decodeToString()).detail
                }
            }.getOrNull()
            val denialCode = ROOM_EPOCH_DENIAL_CODES[detail]
                ?: (if (includeReleaseReasons) ROOM_EPOCH_RELEASE_DENIAL_CODES[detail] else null)
                ?: ROOM_EPOCH_UNKNOWN
            logKmsDenied("ROOM_EPOCH_$denialCode", response.code())
            throw ContentProtectionException.AccessDenied()
        }
        return requireBody(response)
    }

    private fun <T> requirePrivateProfileReleaseBody(response: Response<T>): T {
        if (response.code() == 409) {
            val detail = runCatching {
                response.errorBody()?.use { body ->
                    val source = body.source()
                    source.request(MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong() + 1)
                    if (source.buffer.size > MAX_ROOM_EPOCH_ERROR_BODY_BYTES) return@use null
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(source.readUtf8()).detail
                }
            }.getOrNull()
            if (detail == "RECOVERY_RELEASE_AUTHORITY_CHANGED") throw RecoveryReleaseAuthorityChanged()
        }
        return requirePrivateProfileEnsureBody(response)
    }

    private fun <T> requirePrivateProfileEnsureBody(response: Response<T>): T {
        if (response.code() == 403) {
            val error = runCatching {
                response.errorBody()?.use { body ->
                    val length = body.contentLength()
                    if (length !in 1..MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong()) return@use null
                    ERROR_JSON.decodeFromString<PrivateProfileErrorResponse>(
                        body.source().readByteArray(length).decodeToString(),
                    )
                }
            }.getOrNull()
            if (error?.detail == DEVICE_RECOVERY_REQUIRED) {
                logKmsDenied(DEVICE_RECOVERY_REQUIRED, response.code())
                throw ContentProtectionException.DeviceRecoveryRequired()
            }
        }
        return requireBody(response)
    }

    private fun <T> requireStoreBody(response: Response<T>, canRefreshLifecycleContract: Boolean): T {
        if (response.code() == 409) {
            val payload = runCatching {
                response.errorBody()?.use { body ->
                    val source = body.source()
                    source.request(MAX_ROOM_EPOCH_ERROR_BODY_BYTES.toLong() + 1)
                    if (source.buffer.size > MAX_ROOM_EPOCH_ERROR_BODY_BYTES) return@use null
                    val bytes = source.readByteArray()
                    ERROR_JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
                }
            }.getOrNull()
            val detail = runCatching { payload?.get("detail")?.jsonPrimitive?.content }.getOrNull()
            if (detail in setOf("account has no approved recipient devices", "an account has no approved recipient devices",
                    "authorized device set changed; refresh recipients and retry")) {
                throw ContentProtectionException.RecipientDeviceUnavailable()
            }
            val error = payload?.let { runCatching { ERROR_JSON.decodeFromJsonElement(ControlPlaneErrorResponse.serializer(), it) }.getOrNull() }
            if (canRefreshLifecycleContract && error?.detail?.code == ROOM_LIFECYCLE_CONTRACT_EXPIRED &&
                error.detail.retryableAfterPolicyRefresh
            ) {
                throw ContentProtectionException.LifecycleContractRefreshRequired()
            }
        }
        return requireBody(response)
    }

    private suspend fun decodePrivateProfileKey(
        body: PrivateProfileKeyResponseBody,
        descriptor: DeviceShareKeyDescriptor,
        accountIdHash: String,
        expectedRequestNonce: ByteArray,
    ): AccountPrivateProfileKey {
        val context = body.immutableContextSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
        val requestNonce = body.requestNonce.decodeBase64(MAX_REQUEST_NONCE_BYTES)
        val encryptedKey = body.encryptedProfileKey.decodeBase64(MAX_WRAPPED_SHARE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(requireConfiguration(), privateProfileKeyResponsePayload(body), signature.copyOf())
            val expectedContext = privateProfileImmutableContext(accountIdHash, body.keyVersion)
            if (body.protocolVersion != PRIVATE_PROFILE_PROTOCOL_VERSION || body.accountIdHash != accountIdHash ||
                body.keyVersion <= 0 || body.installationId != descriptor.installationId ||
                body.recipientKeyId != descriptor.keyId || body.wrappingAlgorithm != ContentProtectionFormat.DEVICE_SHARE_ALGORITHM ||
                !MessageDigest.isEqual(context, expectedContext) || !MessageDigest.isEqual(requestNonce, expectedRequestNonce) ||
                kotlin.math.abs(System.currentTimeMillis() - body.serverTimeEpochMillis) > MAX_PRIVATE_PROFILE_RESPONSE_AGE_MILLIS
            ) {
                expectedContext.fill(0)
                throw ContentProtectionException.AuthenticationFailed()
            }
            expectedContext.fill(0)
            val key = deviceShareKeyService.unwrapShare(
                DeviceShareEnvelope(descriptor.installationId, descriptor.keyId, descriptor.algorithm, encryptedKey),
                privateProfileContentId(accountIdHash, body.keyVersion),
                context,
            )
            if (key.size != ContentProtectionFormat.SHARE_SIZE_BYTES) {
                key.fill(0)
                throw ContentProtectionException.AuthenticationFailed()
            }
            return AccountPrivateProfileKey(body.keyVersion, key)
        } finally {
            context.fill(0)
            requestNonce.fill(0)
            encryptedKey.fill(0)
            signature.fill(0)
        }
    }

    private fun decodePrivateProfileDocument(
        body: PrivateProfileDocumentResponseBody,
        accountIdHash: String,
        expectedRequestNonce: ByteArray,
    ): EncryptedPrivateProfileDocument? {
        val requestNonce = body.requestNonce.decodeBase64(MAX_REQUEST_NONCE_BYTES)
        val signature = body.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES)
        try {
            verifySignature(requireConfiguration(), privateProfileDocumentResponsePayload(body), signature.copyOf())
            if (body.protocolVersion != PRIVATE_PROFILE_PROTOCOL_VERSION || body.accountIdHash != accountIdHash ||
                body.keyVersion < 0 || body.documentVersion < 0 || !MessageDigest.isEqual(requestNonce, expectedRequestNonce)
            ) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            if (!body.exists) {
                if (body.documentVersion != 0L || body.ciphertext.isNotEmpty() || body.nonce.isNotEmpty() ||
                    body.associatedDataSha256.isNotEmpty() || body.ciphertextSha256.isNotEmpty() || body.updatedAtEpochMillis != 0L
                ) {
                    throw ContentProtectionException.AuthenticationFailed()
                }
                return null
            }
            if (body.keyVersion <= 0 || body.documentVersion <= 0 || body.updatedAtEpochMillis <= 0) {
                throw ContentProtectionException.AuthenticationFailed()
            }
            val ciphertext = body.ciphertext.decodeBase64(MAX_PRIVATE_PROFILE_DOCUMENT_BYTES)
            val nonce = body.nonce.decodeBase64(12)
            val aadHash = body.associatedDataSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
            val expectedCiphertextHash = body.ciphertextSha256.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES)
            val actualCiphertextHash = MessageDigest.getInstance(SHA256).digest(ciphertext)
            if (nonce.size != 12 || !MessageDigest.isEqual(expectedCiphertextHash, actualCiphertextHash)) {
                ciphertext.fill(0)
                nonce.fill(0)
                aadHash.fill(0)
                expectedCiphertextHash.fill(0)
                actualCiphertextHash.fill(0)
                throw ContentProtectionException.AuthenticationFailed()
            }
            expectedCiphertextHash.fill(0)
            actualCiphertextHash.fill(0)
            return EncryptedPrivateProfileDocument(
                keyVersion = body.keyVersion,
                documentVersion = body.documentVersion,
                ciphertext = ciphertext,
                nonce = nonce,
                associatedDataSha256 = aadHash,
                updatedAtEpochMillis = body.updatedAtEpochMillis,
            )
        } finally {
            requestNonce.fill(0)
            signature.fill(0)
        }
    }

    private fun validateStoreRequest(request: StoreKmsShareRequest) {
        require(request.contentId.value.isNotBlank())
        require(request.accountIdHash.isNotBlank())
        require(request.senderInstallationId.isNotBlank())
        require(request.accountIdHash in request.audienceAccountIdHashes)
        require(request.senderInstallationId in request.authorizedInstallationIds)
        val maximumAudienceAccounts = if (request.roomEpoch == null) {
            ContentProtectionFormat.MAX_LEGACY_DEVICE_SHARE_ENVELOPES
        } else {
            ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS
        }
        val maximumAuthorizedInstallations = if (request.roomEpoch == null) {
            ContentProtectionFormat.MAX_LEGACY_DEVICE_SHARE_ENVELOPES
        } else {
            ContentProtectionFormat.MAX_ROOM_AUTHORIZED_INSTALLATIONS
        }
        require(request.audienceAccountIdHashes.isNotEmpty() && request.audienceAccountIdHashes.size <= maximumAudienceAccounts)
        require(request.authorizedInstallationIds.isNotEmpty() && request.authorizedInstallationIds.size <= maximumAuthorizedInstallations)
        require(request.audienceAccountIdHashes.all { ACCOUNT_HASH.matches(it) })
        require(request.authorizedInstallationIds.all { it.length in 16..256 })
        require(request.immutableContextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(request.kmsShare.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        require(request.policy.policyVersion > 0)
        if (request.lifecycle.mode == io.element.android.security.contentprotection.api.MessageLifecycleMode.BURN_AFTER_READ) {
            require(request.lifecycle.conversationKind != null)
            require(request.leaseMode == OfflineLeaseMode.ONLINE_REQUIRED)
            require(request.kind in setOf(
                io.element.android.security.contentprotection.api.ContentKind.TEXT,
                io.element.android.security.contentprotection.api.ContentKind.IMAGE,
                io.element.android.security.contentprotection.api.ContentKind.VOICE_MESSAGE,
                io.element.android.security.contentprotection.api.ContentKind.VIDEO,
                io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT,
                io.element.android.security.contentprotection.api.ContentKind.LOCATION,
            ))
            if (request.lifecycle.conversationKind == io.element.android.security.contentprotection.api.ConversationKind.DIRECT) {
                require(request.audienceAccountIdHashes.size == 2)
            }
        }
        request.roomEpoch?.let { epoch ->
            require(request.leaseMode == OfflineLeaseMode.ONLINE_REQUIRED)
            require(epoch.epochId.isNotBlank() && epoch.generation > 0)
            require(epoch.audienceDigestSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        }
        // Burn-after-read requests can carry the resolved room lifecycle as local policy
        // context, but that contract is not bound into the KMS store protocol. Only
        // FOLLOW_ROOM messages opt into the v3 lifecycle-contract fields below.
        val resolvedRoomLifecycle = request.roomLifecycle
        val lifecycleContract = resolvedRoomLifecycle?.takeIf {
            request.lifecycle.mode == io.element.android.security.contentprotection.api.MessageLifecycleMode.FOLLOW_ROOM
        }
        if (lifecycleContract?.protocolVersion == ROOM_LIFECYCLE_PROTOCOL_VERSION) {
            require(request.lifecycle.mode == io.element.android.security.contentprotection.api.MessageLifecycleMode.FOLLOW_ROOM)
            val roomId = requireNotNull(request.roomId)
            require(MessageDigest.isEqual(lifecycleContract.roomIdSha256, MessageDigest.getInstance(SHA256).digest(roomId.encodeToByteArray())))
            require(request.policy.expiresAtEpochMillis == lifecycleContract.messageExpiresAtEpochMillis)
            require(CONTRACT_ID.matches(requireNotNull(lifecycleContract.contractId)))
            require(requireNotNull(lifecycleContract.profileRevision) > 0)
            require(requireNotNull(lifecycleContract.contractSha256).size == ContentProtectionFormat.SHA256_SIZE_BYTES)
            require(!lifecycleContract.signingKeyId.isNullOrBlank())
            require(requireNotNull(request.clientCreatedAtEpochMillis) > 0)
            require(requireNotNull(request.matrixTransactionIdSha256).size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        } else {
            require(request.clientCreatedAtEpochMillis == null || resolvedRoomLifecycle != null)
            require(request.matrixTransactionIdSha256 == null || resolvedRoomLifecycle != null)
        }
    }

    private fun validateAcquireRoomEpochRequest(request: AcquireRoomEpochRequest) {
        require(request.roomId.length in 3..255 && request.roomId.startsWith("!") && ':' in request.roomId)
        require(request.roomIdSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(request.accountIdHash in request.audienceAccountIdHashes)
        require(request.requestingInstallationId in request.authorizedInstallationIds)
        require(
            request.audienceAccountIdHashes.isNotEmpty() &&
                request.audienceAccountIdHashes.size <= ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS
        )
        require(
            request.authorizedInstallationIds.isNotEmpty() &&
                request.authorizedInstallationIds.size <= ContentProtectionFormat.MAX_ROOM_AUTHORIZED_INSTALLATIONS
        )
        require(request.audienceAccountIdHashes.all(ACCOUNT_HASH::matches))
        require(request.authorizedInstallationIds.all { it.length in 16..256 })
        require(request.requestingKeyId.isNotBlank())
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
        require(request.minimumActiveMillis in MIN_ROOM_EPOCH_ACTIVE_MILLIS..MAX_ROOM_EPOCH_ACTIVE_MILLIS)
    }

    private fun validateReleaseRoomEpochRequest(request: ReleaseRoomEpochRequest) {
        require(request.reference.epochId.isNotBlank() && request.reference.generation > 0)
        require(request.reference.audienceDigestSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(request.roomIdSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(ACCOUNT_HASH.matches(request.accountIdHash))
        require(request.requestingInstallationId.isNotBlank() && request.requestingKeyId.isNotBlank())
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
    }

    private fun validateReleaseRequest(request: ReleaseKmsShareRequest) {
        require(request.reference.value.isNotBlank())
        require(request.contentId.value.isNotBlank())
        require(request.accountIdHash.isNotBlank())
        require(request.requestingInstallationId.isNotBlank())
        require(request.requestingKeyId.isNotBlank())
        require(request.immutableContextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
        request.burnSessionId?.let { require(it.matches(SESSION_ID)) }
    }

    private fun validateRevocationRequest(request: FetchRevocationsRequest) {
        require(request.accountIdHash.isNotBlank())
        require(request.installationId.isNotBlank())
        require(request.knownSequence >= 0)
        require(request.requestNonce.size in ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES..MAX_REQUEST_NONCE_BYTES)
    }

    @Suppress("ComplexCondition")
    private fun validateReleaseResponse(
        configuration: KmsClientConfiguration,
        request: ReleaseKmsShareRequest,
        body: ReleaseShareResponse,
        context: ByteArray,
        nonce: ByteArray,
    ) {
        val mode = try {
            OfflineLeaseMode.valueOf(body.leaseMode)
        } catch (failure: IllegalArgumentException) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        }
        if (body.protocolVersion != KMS_PROTOCOL_VERSION ||
            body.reference != request.reference.value ||
            body.contentId != request.contentId.value ||
            body.accountIdHash != request.accountIdHash ||
            body.policyVersion != request.policy.policyVersion ||
            body.installationId != request.requestingInstallationId ||
            body.recipientKeyId != request.requestingKeyId ||
            body.wrappingAlgorithm != ContentProtectionFormat.DEVICE_SHARE_ALGORITHM ||
            body.bootCount != request.bootCount ||
            body.burnSessionId != request.burnSessionId ||
            !MessageDigest.isEqual(context, request.immutableContextSha256) ||
            !MessageDigest.isEqual(nonce, request.requestNonce)
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        if (body.issuedAtEpochMillis > body.serverTimeEpochMillis ||
            body.serverTimeEpochMillis >= body.expiresAtEpochMillis ||
            body.expiresAtEpochMillis > request.policy.expiresAtEpochMillis
        ) {
            throw ContentProtectionException.Expired()
        }
        if (mode == OfflineLeaseMode.BOUNDED_OFFLINE) {
            val earliestRefreshEpochMillis = body.serverTimeEpochMillis + 1
            val rejectedConstraint = when {
                request.bootCount < 0 -> "OFFLINE_BOOT_COUNT"
                configuration.maximumOfflineLeaseMillis <= 0 -> "OFFLINE_CONFIG_DURATION"
                configuration.maximumOfflineLicenseUses <= 0 -> "OFFLINE_CONFIG_USES"
                body.expiresAtEpochMillis - body.issuedAtEpochMillis > configuration.maximumOfflineLeaseMillis -> "OFFLINE_DURATION"
                body.maximumUses !in 1..configuration.maximumOfflineLicenseUses -> "OFFLINE_USES"
                body.refreshAfterEpochMillis !in earliestRefreshEpochMillis..body.expiresAtEpochMillis -> "OFFLINE_REFRESH_WINDOW"
                body.revocationSequence < 0 -> "OFFLINE_REVOCATION_SEQUENCE"
                else -> null
            }
            if (rejectedConstraint != null) {
                logKmsDenied(rejectedConstraint)
                throw ContentProtectionException.AccessDenied()
            }
        } else if (body.maximumUses != 1 || body.refreshAfterEpochMillis != body.expiresAtEpochMillis) {
            throw ContentProtectionException.AuthenticationFailed()
        }
    }

    private fun verifyProgressivePlayback(
        configuration: KmsClientConfiguration,
        request: ReleaseKmsShareRequest,
        body: ReleaseShareResponse,
    ): Long? {
        val permission = body.progressiveVideoPlayback
        if (!request.progressiveVideoPlayback) {
            if (permission != null) throw ContentProtectionException.AuthenticationFailed()
            return null
        }
        if (permission == null || permission.scope != "PROGRESSIVE_VIDEO_V1" ||
            body.leaseMode != OfflineLeaseMode.ONLINE_REQUIRED.name
        ) throw ContentProtectionException.AuthenticationFailed()
        val maximumDeadline = runCatching { Math.addExact(body.issuedAtEpochMillis, 3_600_000L) }
            .getOrElse { throw ContentProtectionException.AuthenticationFailed(it) }
        if (permission.expiresAtEpochMillis <= body.serverTimeEpochMillis ||
            permission.expiresAtEpochMillis < body.expiresAtEpochMillis ||
            permission.expiresAtEpochMillis > request.policy.expiresAtEpochMillis ||
            permission.expiresAtEpochMillis > maximumDeadline
        ) throw ContentProtectionException.Expired()
        val releasePayload = releaseResponsePayload(body)
        try {
            val payload = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("YZVP0001".encodeToByteArray())
                    output.write(releasePayload)
                    output.writeString(permission.scope)
                    output.writeLong(permission.expiresAtEpochMillis)
                }
                bytes.toByteArray()
            }
            verifySignature(configuration, payload, permission.serverSignature.decodeBase64(MAX_SIGNATURE_BYTES))
        } finally { releasePayload.fill(0) }
        return permission.expiresAtEpochMillis
    }

    private fun verifySignature(configuration: KmsClientConfiguration, payload: ByteArray, signature: ByteArray) {
        try {
            val publicKey = KeyFactory.getInstance(EC_ALGORITHM).generatePublic(X509EncodedKeySpec(configuration.signingPublicKeyDer))
            val valid = Signature.getInstance(ContentProtectionFormat.KMS_SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
            if (!valid) throw ContentProtectionException.AuthenticationFailed()
        } catch (failure: ContentProtectionException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        } finally {
            payload.fill(0)
            signature.fill(0)
        }
    }

    private fun storeResponsePayload(body: StoreShareResponse, request: StoreKmsShareRequest): ByteArray = canonicalPayload(STORE_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.reference)
        writeString(request.contentId.value)
        writeByteArray(request.immutableContextSha256)
        writeLong(body.storedAtEpochMillis)
    }

    private fun privateProfileKeyResponsePayload(body: PrivateProfileKeyResponseBody): ByteArray =
        canonicalPayload(PRIVATE_PROFILE_KEY_RESPONSE_MAGIC) {
            writeInt(body.protocolVersion)
            writeString(body.accountIdHash)
            writeLong(body.keyVersion)
            writeString(body.installationId)
            writeString(body.recipientKeyId)
            writeString(body.wrappingAlgorithm)
            writeString(body.immutableContextSha256)
            writeString(body.requestNonce)
            writeString(body.encryptedProfileKey)
            writeLong(body.serverTimeEpochMillis)
        }

    private fun privateProfileDocumentResponsePayload(body: PrivateProfileDocumentResponseBody): ByteArray =
        canonicalPayload(PRIVATE_PROFILE_DOCUMENT_RESPONSE_MAGIC) {
            writeInt(body.protocolVersion)
            writeString(body.accountIdHash)
            writeByte(if (body.exists) 1 else 0)
            writeLong(body.keyVersion)
            writeLong(body.documentVersion)
            writeString(body.ciphertext)
            writeString(body.nonce)
            writeString(body.associatedDataSha256)
            writeString(body.ciphertextSha256)
            writeLong(body.updatedAtEpochMillis)
            writeString(body.requestNonce)
        }

    private fun privateProfileImmutableContext(accountIdHash: String, keyVersion: Long): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("yizhe-account-private-profile-v1\u0000".encodeToByteArray())
                output.write(accountIdHash.decodeHex(ContentProtectionFormat.SHA256_SIZE_BYTES))
                output.writeLong(keyVersion)
            }
            MessageDigest.getInstance(SHA256).digest(bytes.toByteArray())
        }

    private fun privateProfileContentId(accountIdHash: String, keyVersion: Long): ContentId =
        ContentId("private-profile-key-v1:$accountIdHash:$keyVersion")

    private fun releaseResponsePayload(body: ReleaseShareResponse): ByteArray = canonicalPayload(RELEASE_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.grantId)
        writeString(body.reference)
        writeString(body.contentId)
        writeString(body.accountIdHash)
        writeLong(body.policyVersion)
        writeString(body.installationId)
        writeString(body.recipientKeyId)
        writeString(body.wrappingAlgorithm)
        writeString(body.immutableContextSha256)
        writeString(body.requestNonce)
        writeString(body.leaseMode)
        writeLong(body.bootCount)
        writeLong(body.issuedAtEpochMillis)
        writeLong(body.expiresAtEpochMillis)
        writeLong(body.serverTimeEpochMillis)
        writeLong(body.refreshAfterEpochMillis)
        writeLong(body.revocationSequence)
        writeInt(body.maximumUses)
        writeString(body.encryptedKmsShare)
        body.burnSessionId?.let {
            writeByte(1)
            writeString(it)
        }
    }

    private fun openBurnAfterReadPayload(body: OpenBurnAfterReadResponse): ByteArray = canonicalPayload(OPEN_BURN_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.contentId)
        writeString(body.accountIdHash)
        writeString(body.sessionId)
        writeString(body.contentKind)
        writeString(body.readWindowMode)
        writeLong(body.openedAtEpochMillis)
        writeLong(body.viewExpiresAtEpochMillis)
        writeLong(body.globalExpiresAtEpochMillis)
        writeLong(body.serverTimeEpochMillis)
    }

    private fun endBurnAfterReadPayload(body: EndBurnAfterReadResponse): ByteArray = canonicalPayload(END_BURN_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.contentId)
        writeString(body.accountIdHash)
        writeString(body.sessionId)
        writeString(body.state)
        writeLong(body.endedAtEpochMillis)
        writeByte(if (body.allAccessEnded) 1 else 0)
        writeLong(body.serverTimeEpochMillis)
    }

    private fun roomEpochResponsePayload(body: RoomEpochResponse): ByteArray = canonicalPayload(ROOM_EPOCH_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.epochId)
        writeLong(body.generation)
        writeString(body.roomIdSha256)
        writeString(body.audienceDigestSha256)
        writeString(body.accountIdHash)
        writeString(body.installationId)
        writeString(body.recipientKeyId)
        writeString(body.wrappingAlgorithm)
        writeString(body.requestNonce)
        writeLong(body.activeUntilEpochMillis)
        writeLong(body.releaseUntilEpochMillis)
        writeLong(body.serverTimeEpochMillis)
        writeString(body.encryptedEpochSecret)
    }

    private fun roomLifecycleResponsePayload(body: RoomLifecycleResolveResponse): ByteArray =
        canonicalPayload(if (body.protocolVersion == ROOM_LIFECYCLE_PROTOCOL_VERSION) ROOM_LIFECYCLE_RESPONSE_V3_MAGIC else ROOM_LIFECYCLE_RESPONSE_V1_MAGIC) {
            writeInt(body.protocolVersion)
            writeString(body.roomIdSha256)
            writeString(body.profileId)
            writeString(body.profileKind)
            writeLong(body.retentionMillis)
            writeLong(body.maximumOfflineMillis)
            writeString(body.recoveryPolicy)
            writeLong(body.messageExpiresAtEpochMillis)
            writeLong(body.issuedAtEpochMillis)
            writeLong(body.expiresAtEpochMillis)
            if (body.protocolVersion == ROOM_LIFECYCLE_PROTOCOL_VERSION) {
                writeString(requireNotNull(body.contractId))
                writeLong(requireNotNull(body.profileRevision))
                writeString(requireNotNull(body.contractSha256))
                writeString(requireNotNull(body.signingKeyId))
            }
        }

    private fun roomLifecycleContractPayload(body: RoomLifecycleResolveResponse): ByteArray =
        canonicalPayload(ROOM_LIFECYCLE_CONTRACT_MAGIC) {
            writeString(requireNotNull(body.contractId))
            writeString(body.roomIdSha256)
            writeString(body.profileId)
            writeString(body.profileKind)
            writeLong(requireNotNull(body.profileRevision))
            writeLong(body.retentionMillis)
            writeLong(body.maximumOfflineMillis)
            writeString(body.recoveryPolicy)
            writeLong(body.messageExpiresAtEpochMillis)
            writeLong(body.issuedAtEpochMillis)
            writeLong(body.expiresAtEpochMillis)
            writeString(requireNotNull(body.signingKeyId))
        }

    private fun revocationResponsePayload(body: FetchRevocationsResponse): ByteArray = canonicalPayload(REVOCATION_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(body.accountIdHash)
        writeString(body.installationId)
        writeLong(body.sequence)
        writeLong(body.issuedAtEpochMillis)
        writeLong(body.refreshAfterEpochMillis)
        writeLong(body.expiresAtEpochMillis)
        writeString(body.requestNonce)
        writeString(body.deviceAccessState)
        writeStringSet(body.revokedLeaseIds)
        writeStringSet(body.revokedContentIds)
    }

    private fun destroyResponsePayload(
        body: DestroyShareResponse,
        contentId: ContentId,
        reason: ContentAccessDestroyReason,
    ): ByteArray = canonicalPayload(DESTROY_RESPONSE_MAGIC) {
        writeInt(body.protocolVersion)
        writeString(contentId.value)
        writeString(reason.name)
        writeBoolean(body.destroyed)
        writeLong(body.tombstoneVersion)
        writeLong(body.serverTimeEpochMillis)
    }

    private fun redactionReasonPayload(body: RedactionReasonReceipt): ByteArray =
        canonicalPayload(REDACTION_REASON_MAGIC) {
            writeInt(body.protocolVersion)
            writeString(body.reasonCode)
            writeString(body.lifecycleReceiptId)
            writeString(body.eventIdSha256)
            writeLong(body.issuedAtEpochMillis)
            writeString(body.keyId)
        }

    private fun canonicalPayload(magic: ByteArray, block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.block()
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_WIRE_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeByteArray(value: ByteArray) {
        require(value.size <= MAX_WIRE_BINARY_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeStringSet(values: List<String>) {
        require(values.size <= MAX_REVOCATION_IDS)
        writeInt(values.size)
        values.sorted().forEach { writeString(it) }
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun String.decodeHex(expectedBytes: Int): ByteArray = try {
        require(length == expectedBytes * 2 && all { it in '0'..'9' || it in 'a'..'f' })
        ByteArray(expectedBytes) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    } catch (failure: Exception) {
        throw ContentProtectionException.AuthenticationFailed(failure)
    }

    private fun String.decodeBase64(maximumBytes: Int): ByteArray = try {
        require(length <= maximumBytes * 2)
        Base64.getDecoder().decode(this).also { require(it.size <= maximumBytes) }
    } catch (failure: Exception) {
        throw ContentProtectionException.AuthenticationFailed(failure)
    }

    private fun configurationIdentity(configuration: KmsClientConfiguration): String = buildString {
        append(configuration.baseUrl)
        append(':')
        append(MessageDigest.getInstance(SHA256).digest(configuration.signingPublicKeyDer).base64())
        append(':')
        append(configuration.certificatePinsSha256.sorted().joinToString())
    }

    private fun logKmsDenied(stage: String, httpStatus: Int? = null) {
        if (httpStatus == null) {
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_KMS_DENIED stage=%s", stage)
        } else {
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_KMS_DENIED stage=%s http_status=%d", stage, httpStatus)
        }
    }

    private data class CachedApi(val identity: String, val api: KmsApi)

    private companion object {
        const val KMS_PROTOCOL_VERSION = 2
        const val KMS_CONTRACT_PROTOCOL_VERSION = 3
        const val PRIVATE_PROFILE_PROTOCOL_VERSION = 1
        const val BURN_AFTER_READ_PROTOCOL_VERSION = 1
        val BURN_TERMINAL_STATES = setOf(
            "ACCESS_ACTIVE",
            "ACCESS_ENDED",
            "KEY_DESTROY_PENDING",
            "KEY_DESTROYED",
            "CLEANUP_PENDING",
            "PURGED",
            "FAILED",
        )
        const val ROOM_EPOCH_PROTOCOL_VERSION = 1
        const val ROOM_LIFECYCLE_ASSIGNMENT_PROTOCOL_VERSION = 1
        const val ROOM_LIFECYCLE_PROTOCOL_VERSION = 3
        const val ROOM_LIFECYCLE_CONTRACT_EXPIRED = "ROOM_LIFECYCLE_CONTRACT_EXPIRED"
        const val DEVICE_RECOVERY_REQUIRED = "DEVICE_RECOVERY_REQUIRED"
        const val MATRIX_EVENT_BINDING_PENDING = "MATRIX_EVENT_BINDING_PENDING"
        const val ROOM_EPOCH_UNKNOWN = "UNKNOWN"
        val ROOM_EPOCH_DENIAL_CODES = mapOf(
            "device is not eligible for protected-content access" to "DEVICE_INELIGIBLE",
            "device access ticket requires security synchronization" to "TICKET_SYNC_REQUIRED",
            "lifecycle operation belongs to another installation" to "LIFECYCLE_INSTALLATION_MISMATCH",
            "room identity hash mismatch" to "ROOM_IDENTITY_MISMATCH",
            "room epoch requesting device is not trusted" to "REQUESTING_DEVICE_UNTRUSTED",
            "room epoch account is not bound to this device" to "ACCOUNT_DEVICE_BINDING_MISMATCH",
            "account is active on another mobile installation" to "ACTIVE_MOBILE_INSTALLATION_MISMATCH",
            "device content key is unavailable" to "CONTENT_KEY_UNAVAILABLE",
            "device content key is not acceptable" to "CONTENT_KEY_UNACCEPTABLE",
            "device content key mismatch" to "CONTENT_KEY_MISMATCH",
        )
        val ROOM_EPOCH_RELEASE_DENIAL_CODES = mapOf(
            "room epoch binding mismatch" to "ROOM_EPOCH_BINDING_MISMATCH",
            "room epoch membership mismatch" to "ROOM_EPOCH_MEMBERSHIP_MISMATCH",
        )
        const val MESSAGE_LIFECYCLE_EVENT_PROTOCOL_VERSION = 1
        const val REDACTION_REASON_PROTOCOL_VERSION = 1
        const val PROTECTED_DIAGNOSTIC_TAG = "YiZheProtectedContent"
        const val EC_ALGORITHM = "EC"
        const val SHA256 = "SHA-256"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val MAX_SIGNATURE_BYTES = 256
        const val MAX_REQUEST_NONCE_BYTES = 64
        const val MAX_WRAPPED_SHARE_BYTES = 1024
        const val MAX_PRIVATE_PROFILE_DOCUMENT_BYTES = 1024 * 1024
        const val MAX_PRIVATE_PROFILE_RESPONSE_AGE_MILLIS = 5 * 60_000L
        const val MAX_WIRE_STRING_BYTES = 8192
        const val MAX_WIRE_BINARY_BYTES = 8192
        const val MAX_REVOCATION_IDS = 4096
        const val MAX_BATCH_RELEASE_SIZE = 32
        const val MAX_PENDING_RECOVERY_REQUESTS = 32
        const val MAX_MESSAGE_MEDIA_URIS = 16
        const val MAX_REDACTION_REASON_HASHES = 256
        const val MAX_MEDIA_URI_CHARS = 1024
        const val MAX_ROOM_EPOCH_ERROR_BODY_BYTES = 4096
        const val MAX_TRANSIENT_ATTEMPTS = 3
        const val TRANSIENT_RETRY_BACKOFF_MILLIS = 100L

        // A burst of queued Matrix sends can make the sender's authenticated event binding trail the
        // recipient timeline by several seconds. Keep this bounded below ten seconds and retry only
        // the server-signed MATRIX_EVENT_BINDING_PENDING transition; every other denial stays immediate.
        const val MAX_EVENT_BINDING_ATTEMPTS = 13
        const val EVENT_BINDING_RETRY_BACKOFF_MILLIS = 100L
        const val MAX_EVENT_BINDING_RETRY_DELAY_MILLIS = 1_000L
        val TRANSIENT_HTTP_STATUSES = setOf(502, 503, 504)
        val ERROR_JSON = Json { ignoreUnknownKeys = true }
        val ACCOUNT_HASH = Regex("[0-9a-f]{64}")
        val EVENT_ID_HASH = Regex("[0-9a-f]{64}")
        val CONTRACT_ID = Regex("[A-Za-z0-9_-]{16,128}")
        val RECEIPT_ID = Regex("[A-Za-z0-9_-]{16,128}")
        val SESSION_ID = Regex("[A-Za-z0-9_-]{16,128}")
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val STORE_RESPONSE_MAGIC = "YZKS0001".encodeToByteArray()
        val PRIVATE_PROFILE_KEY_RESPONSE_MAGIC = "YZPKR001".encodeToByteArray()
        val PRIVATE_PROFILE_DOCUMENT_RESPONSE_MAGIC = "YZPDR001".encodeToByteArray()
        val RELEASE_RESPONSE_MAGIC = "YZKR0001".encodeToByteArray()
        val DESTROY_RESPONSE_MAGIC = "YZKD0001".encodeToByteArray()
        val REDACTION_REASON_MAGIC = "YZREASON1\u0000".encodeToByteArray()
        val REVOCATION_RESPONSE_MAGIC = "YZKV0001".encodeToByteArray()
        val ROOM_EPOCH_RESPONSE_MAGIC = "YZER0001".encodeToByteArray()
        val ROOM_LIFECYCLE_RESPONSE_V1_MAGIC = "YZRL0001".encodeToByteArray()
        val ROOM_LIFECYCLE_RESPONSE_V3_MAGIC = "YZRL0003".encodeToByteArray()
        val ROOM_LIFECYCLE_CONTRACT_MAGIC = "YZRC0003".encodeToByteArray()
        val OPEN_BURN_RESPONSE_MAGIC = "YZBO0001".encodeToByteArray()
        val END_BURN_RESPONSE_MAGIC = "YZBE0001".encodeToByteArray()
        const val MIN_ROOM_EPOCH_ACTIVE_MILLIS = 5_000L
        const val MAX_ROOM_EPOCH_ACTIVE_MILLIS = 60_000L
        const val MAX_ROOM_LIFECYCLE_SNAPSHOT_MILLIS = 5 * 60_000L
        const val MAX_ROOM_RETENTION_MILLIS = 365L * 24 * 60 * 60_000
        const val MAX_ROOM_OFFLINE_MILLIS = 24L * 60 * 60_000
        const val ONE_DAY_MILLIS = 24L * 60 * 60_000
        const val SIGNING_KEY_ID_HEX_CHARS = 16
    }
}

private interface KmsApi {
    @POST("v1/content-version-lineage/versions")
    suspend fun registerContentVersion(
        @Header("Authorization") authorization: String,
        @Body body: RegisterContentVersionBody,
    ): Response<RegisterContentVersionResponse>
    @POST("v1/private-resources/{action}")
    suspend fun privateResource(
        @Path("action") action: String,
        @Header("Authorization") authorization: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    @PUT("v1/private-resources")
    suspend fun putPrivateResource(
        @Header("Authorization") authorization: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    @POST("v1/private-profile/key/release")
    suspend fun releasePrivateProfileKey(
        @Header("Authorization") authorization: String,
        @Body body: PrivateProfileReleaseKeyBody,
    ): Response<PrivateProfileKeyResponseBody>

    @POST("v1/private-profile/key/ensure")
    suspend fun ensurePrivateProfileKey(
        @Header("Authorization") authorization: String,
        @Body body: PrivateProfileEnsureKeyBody,
    ): Response<PrivateProfileKeyResponseBody>

    @POST("v1/private-profile/document/fetch")
    suspend fun fetchPrivateProfileDocument(
        @Header("Authorization") authorization: String,
        @Body body: PrivateProfileFetchBody,
    ): Response<PrivateProfileDocumentResponseBody>

    @PUT("v1/private-profile/document")
    suspend fun putPrivateProfileDocument(
        @Header("Authorization") authorization: String,
        @Body body: PrivateProfilePutBody,
    ): Response<PrivateProfileDocumentResponseBody>

    @POST("v1/message-lifecycle/rooms/resolve")
    suspend fun resolveRoomLifecycle(
        @Header("Authorization") authorization: String,
        @Body body: RoomLifecycleResolveBody,
    ): Response<RoomLifecycleResolveResponse>

    @POST("v1/message-lifecycle/rooms/assign-created")
    suspend fun assignCreatedRoomLifecycle(
        @Header("Authorization") authorization: String,
        @Body body: CreatedRoomLifecycleAssignmentBody,
    ): Response<CreatedRoomLifecycleAssignmentResponse>

    @POST("v1/message-lifecycle/events")
    suspend fun registerMessageEvent(
        @Header("Authorization") authorization: String,
        @Body body: MessageLifecycleEventBody,
    ): Response<MessageLifecycleEventResponse>

    @POST("v1/message-lifecycle/redactions/confirm")
    suspend fun confirmMatrixRedaction(
        @Header("Authorization") authorization: String,
        @Body body: MatrixRedactionConfirmationBody,
    ): Response<MatrixRedactionConfirmationResponse>

    @POST("v1/message-lifecycle/redaction-reasons/resolve")
    suspend fun resolveRedactionReasons(
        @Header("Authorization") authorization: String,
        @Body body: RedactionReasonResolveBody,
    ): Response<RedactionReasonResolveResponse>

    @POST("v1/message-lifecycle/call-records")
    suspend fun registerCallRecord(
        @Header("Authorization") authorization: String,
        @Body body: CallRecordLifecycleBody,
    ): Response<CallRecordLifecycleResponse>

    @POST("v1/device-recovery/start")
    suspend fun startDeviceRecovery(
        @Header("Authorization") authorization: String,
        @Body body: DeviceRecoveryStartBody,
    ): Response<DeviceRecoveryStatusBody>

    @GET("v1/device-recovery/{recoveryId}")
    suspend fun deviceRecoveryStatus(
        @Header("Authorization") authorization: String,
        @Path("recoveryId") recoveryId: String,
    ): Response<DeviceRecoveryStatusBody>

    @POST("v1/device-recovery/pending-old-device")
    suspend fun pendingDeviceRecoveries(
        @Header("Authorization") authorization: String,
        @Body body: DeviceRecoveryAccountBody,
    ): Response<List<PendingDeviceRecoveryBody>>

    @POST("v1/device-recovery/{recoveryId}/confirm-old-device")
    suspend fun confirmDeviceRecovery(
        @Header("Authorization") authorization: String,
        @Path("recoveryId") recoveryId: String,
        @Body body: DeviceRecoveryAccountBody,
    ): Response<DeviceRecoveryStatusBody>

    @POST("v1/burn-after-read/open")
    suspend fun openBurnAfterRead(
        @Header("Authorization") authorization: String,
        @Body body: OpenBurnAfterReadBody,
    ): Response<OpenBurnAfterReadResponse>

    @POST("v1/burn-after-read/end")
    suspend fun endBurnAfterRead(
        @Header("Authorization") authorization: String,
        @Body body: EndBurnAfterReadBody,
    ): Response<EndBurnAfterReadResponse>

    @POST("v1/room-epochs/acquire")
    suspend fun acquireRoomEpoch(
        @Header("Authorization") authorization: String,
        @Body body: AcquireRoomEpochBody,
    ): Response<RoomEpochResponse>

    @POST("v1/room-epochs/release")
    suspend fun releaseRoomEpoch(
        @Header("Authorization") authorization: String,
        @Body body: ReleaseRoomEpochBody,
        @Tag trace: KmsTransportTrace,
    ): Response<RoomEpochResponse>

    @POST("v1/content-shares")
    suspend fun storeShare(
        @Header("Authorization") authorization: String,
        @Body body: StoreShareBody,
    ): Response<StoreShareResponse>

    @POST("v1/content-shares/release")
    suspend fun releaseShare(
        @Header("Authorization") authorization: String,
        @Body body: ReleaseShareBody,
    ): Response<ReleaseShareResponse>

    @POST("v1/content-shares/release-batch")
    suspend fun releaseShares(
        @Header("Authorization") authorization: String,
        @Body bodies: List<ReleaseShareBody>,
        @Tag trace: KmsTransportTrace,
    ): Response<List<ReleaseShareResponse>>

    @POST("v1/content-shares/destroy")
    suspend fun destroyShare(
        @Header("Authorization") authorization: String,
        @Body body: DestroyShareBody,
    ): Response<DestroyShareResponse>

    @POST("v1/revocations/snapshot")
    suspend fun fetchRevocations(
        @Header("Authorization") authorization: String,
        @Body body: FetchRevocationsBody,
    ): Response<FetchRevocationsResponse>
}

@Serializable
private data class RegisterContentVersionBody(
    @SerialName("content_id") val contentId: String,
    @SerialName("root_content_id") val rootContentId: String,
    @SerialName("parent_content_id") val parentContentId: String?,
    @SerialName("origin_operation_id") val originOperationId: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_event_id_sha256") val expectedEventIdSha256: String,
)

@Serializable
private data class RegisterContentVersionResponse(
    val node: RegisterContentVersionNode,
)

@Serializable
private data class RegisterContentVersionNode(
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("owner_account_id_hash") val ownerAccountIdHash: String,
    @SerialName("sender_installation_id") val senderInstallationId: String,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("policy_id") val policyId: String,
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("immutable_context_digest") val immutableContextDigest: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("root_content_id") val rootContentId: String,
    @SerialName("parent_content_id") val parentContentId: String?,
    @SerialName("operation_id") val operationId: String,
    @SerialName("expires_at") val expiresAtEpochMillis: Long,
    @SerialName("matrix_event_id_sha256") val matrixEventIdSha256: String,
    @SerialName("created_at") val createdAtEpochMillis: Long,
)

@Serializable
private data class PrivateProfileEnsureKeyBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("requesting_key_id") val requestingKeyId: String,
    @SerialName("proposed_key_b64") val proposedKey: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class PrivateProfileReleaseKeyBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("requesting_key_id") val requestingKeyId: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class PrivateProfileFetchBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class PrivateProfilePutBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("key_version") val keyVersion: Long,
    @SerialName("expected_document_version") val expectedDocumentVersion: Long,
    @SerialName("ciphertext_b64") val ciphertext: String,
    @SerialName("nonce_b64") val nonce: String,
    @SerialName("aad_sha256") val associatedDataSha256: String,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class PrivateProfileKeyResponseBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("key_version") val keyVersion: Long,
    @SerialName("installation_id") val installationId: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("wrapping_algorithm") val wrappingAlgorithm: String,
    @SerialName("immutable_context_sha256") val immutableContextSha256: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("encrypted_profile_key_b64") val encryptedProfileKey: String,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class PrivateProfileDocumentResponseBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    val exists: Boolean,
    @SerialName("key_version") val keyVersion: Long,
    @SerialName("document_version") val documentVersion: Long,
    @SerialName("ciphertext_b64") val ciphertext: String,
    @SerialName("nonce_b64") val nonce: String,
    @SerialName("aad_sha256") val associatedDataSha256: String,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
    @SerialName("updated_at_ms") val updatedAtEpochMillis: Long,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class RoomLifecycleResolveBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id") val roomId: String,
)

@Serializable
private data class CreatedRoomLifecycleAssignmentBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id") val roomId: String,
    @SerialName("profile_kind") val profileKind: String,
)

@Serializable
private data class CreatedRoomLifecycleAssignmentResponse(
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("profile_kind") val profileKind: String,
    @SerialName("assigned_at_ms") val assignedAtEpochMillis: Long,
    @SerialName("applies_to") val appliesTo: String,
)

@Serializable
private data class RoomLifecycleResolveResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_kind") val profileKind: String,
    @SerialName("retention_millis") val retentionMillis: Long,
    @SerialName("maximum_offline_millis") val maximumOfflineMillis: Long,
    @SerialName("recovery_policy") val recoveryPolicy: String,
    @SerialName("message_expires_at_ms") val messageExpiresAtEpochMillis: Long,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("contract_id") val contractId: String? = null,
    @SerialName("profile_revision") val profileRevision: Long? = null,
    @SerialName("contract_sha256") val contractSha256: String? = null,
    @SerialName("signing_key_id") val signingKeyId: String? = null,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class MessageLifecycleEventBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("media_uris") val mediaUris: List<String>,
)

@Serializable
private data class MessageLifecycleEventResponse(
    @SerialName("content_id") val contentId: String,
    @SerialName("event_asset_id") val eventAssetId: String,
    @SerialName("media_asset_ids") val mediaAssetIds: List<String>,
)

@Serializable
private data class MatrixRedactionConfirmationBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id") val roomId: String,
    @SerialName("event_id") val eventId: String,
)

@Serializable
private data class MatrixRedactionConfirmationResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    val state: String,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("event_id_sha256") val eventIdSha256: String,
    @SerialName("evidence_sha256") val evidenceSha256: String,
)

@Serializable
private data class RedactionReasonResolveBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("event_id_sha256") val eventIdSha256: List<String>,
)

@Serializable
private data class RedactionReasonResolveResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    val receipts: List<RedactionReasonReceipt>,
)

@Serializable
private data class RedactionReasonReceipt(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("lifecycle_receipt_id") val lifecycleReceiptId: String,
    @SerialName("event_id_sha256") val eventIdSha256: String,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("key_id") val keyId: String,
    @SerialName("signature_b64") val serverSignature: String,
)

@Serializable
private data class CallRecordLifecycleBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id") val roomId: String,
    @SerialName("event_id") val eventId: String,
)

@Serializable
private data class CallRecordLifecycleResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("record_id") val recordId: String,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("event_id_sha256") val eventIdSha256: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_kind") val profileKind: String,
    @SerialName("scheduled_purge_at_ms") val scheduledPurgeAtEpochMillis: Long,
    @SerialName("store_media") val storeMedia: Boolean,
    @SerialName("recording_enabled") val recordingEnabled: Boolean,
)

@Serializable
private data class DeviceRecoveryStartBody(
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("new_installation_id") val newInstallationId: String,
)

@Serializable
private data class DeviceRecoveryAccountBody(
    @SerialName("account_id_hash") val accountIdHash: String,
)

@Serializable
private data class DeviceRecoveryStatusBody(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("new_installation_id") val newInstallationId: String,
    val state: String,
    @SerialName("standard_authorized") val standardAuthorized: Boolean,
    @SerialName("old_device_confirmed") val oldDeviceConfirmed: Boolean,
    @SerialName("admin_approved") val administratorApproved: Boolean,
    @SerialName("confidential_authorized") val confidentialAuthorized: Boolean,
    @SerialName("request_expires_at_ms") val requestExpiresAtEpochMillis: Long,
    @SerialName("restores_old_device_keys") val restoresOldDeviceKeys: Boolean,
    @SerialName("burn_after_read_recoverable") val burnAfterReadRecoverable: Boolean,
)

private suspend fun DeviceRecoveryStatusBody.toStatus(): DeviceRecoveryStatus {
    if (recoveryId.length !in 16..128 || newInstallationId.length !in 16..256 || requestExpiresAtEpochMillis <= 0) {
        LifecycleStageTiming.point("recovery_status_rejected", identity = "FORMAT_INVALID")
        throw ContentProtectionException.AuthenticationFailed()
    }
    // Recovery only authorizes fresh KMS re-wrapping. A server response claiming that old device
    // keys or burn-after-read content can be recovered violates the product's cryptographic-erasure
    // boundary and must fail closed instead of being ignored as an unknown compatibility field.
    if (restoresOldDeviceKeys || burnAfterReadRecoverable) {
        LifecycleStageTiming.point("recovery_status_rejected", identity = "ERASURE_FLAGS_INVALID")
        throw ContentProtectionException.AuthenticationFailed()
    }
    val parsedState = runCatching { DeviceRecoveryState.valueOf(state) }
        .getOrElse {
            LifecycleStageTiming.point("recovery_status_rejected", identity = "STATE_INVALID")
            throw ContentProtectionException.AuthenticationFailed(it)
        }
    val flagsAreConsistent = when (parsedState) {
        DeviceRecoveryState.AWAITING_OLD_DEVICE ->
            !standardAuthorized && !oldDeviceConfirmed && !administratorApproved && !confidentialAuthorized
        DeviceRecoveryState.STANDARD_AUTHORIZED ->
            standardAuthorized && !oldDeviceConfirmed && !administratorApproved && !confidentialAuthorized
        DeviceRecoveryState.AWAITING_ADMIN_APPROVAL ->
            standardAuthorized && oldDeviceConfirmed && !administratorApproved && !confidentialAuthorized
        DeviceRecoveryState.CONFIDENTIAL_AUTHORIZED ->
            standardAuthorized && oldDeviceConfirmed && administratorApproved && confidentialAuthorized
        DeviceRecoveryState.EXPIRED ->
            !confidentialAuthorized &&
                (!administratorApproved || oldDeviceConfirmed) &&
                (!oldDeviceConfirmed || standardAuthorized)
    }
    if (!flagsAreConsistent) {
        LifecycleStageTiming.point("recovery_status_rejected", identity = "FLAGS_INVALID")
        throw ContentProtectionException.AuthenticationFailed()
    }
    return DeviceRecoveryStatus(
        recoveryId = recoveryId,
        accountIdHash = accountIdHash,
        newInstallationId = newInstallationId,
        state = parsedState,
        standardAuthorized = standardAuthorized,
        oldDeviceConfirmed = oldDeviceConfirmed,
        administratorApproved = administratorApproved,
        confidentialAuthorized = confidentialAuthorized,
        requestExpiresAtEpochMillis = requestExpiresAtEpochMillis,
    )
}

@Serializable
private data class PendingDeviceRecoveryBody(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("new_installation_id") val newInstallationId: String,
    @SerialName("standard_authorized") val standardAuthorized: Boolean,
    @SerialName("created_at_ms") val createdAtEpochMillis: Long,
    @SerialName("request_expires_at_ms") val requestExpiresAtEpochMillis: Long,
)

@Serializable
private data class StoreShareBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("policy_id") val policyId: String,
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("sender_installation_id") val senderInstallationId: String,
    @SerialName("audience_account_id_hashes") val audienceAccountIdHashes: List<String>,
    @SerialName("authorized_installation_ids") val authorizedInstallationIds: List<String>,
    @SerialName("context_sha256") val immutableContextSha256: String,
    @SerialName("kms_share_b64") val kmsShare: String,
    @SerialName("lease_mode") val leaseMode: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_epoch_id") val roomEpochId: String? = null,
    @SerialName("room_epoch_generation") val roomEpochGeneration: Long? = null,
    @SerialName("room_audience_digest_sha256") val roomAudienceDigestSha256: String? = null,
    @SerialName("lifecycle_mode") val lifecycleMode: String,
    @SerialName("conversation_kind") val conversationKind: String? = null,
    @SerialName("lifecycle_contract_id") val lifecycleContractId: String? = null,
    @SerialName("lifecycle_contract_sha256") val lifecycleContractSha256: String? = null,
    @SerialName("client_created_at_ms") val clientCreatedAtEpochMillis: Long? = null,
    @SerialName("matrix_transaction_id_sha256") val matrixTransactionIdSha256: String? = null,
)

@Serializable
private data class OpenBurnAfterReadBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class OpenBurnAfterReadResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("read_window_mode") val readWindowMode: String,
    @SerialName("opened_at_ms") val openedAtEpochMillis: Long,
    @SerialName("view_expires_at_ms") val viewExpiresAtEpochMillis: Long,
    @SerialName("global_expires_at_ms") val globalExpiresAtEpochMillis: Long,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("root_operation_id") val rootOperationId: String? = null,
    @SerialName("view_operation_id") val viewOperationId: String? = null,
    @SerialName("grant_state") val grantState: String? = null,
    @SerialName("terminal_state") val terminalState: String? = null,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class EndBurnAfterReadBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("session_id") val sessionId: String,
    val reason: String,
)

@Serializable
private data class EndBurnAfterReadResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("session_id") val sessionId: String,
    val state: String,
    @SerialName("ended_at_ms") val endedAtEpochMillis: Long,
    @SerialName("all_access_ended") val allAccessEnded: Boolean,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("root_operation_id") val rootOperationId: String? = null,
    @SerialName("view_operation_id") val viewOperationId: String? = null,
    @SerialName("grant_state") val grantState: String? = null,
    @SerialName("terminal_state") val terminalState: String? = null,
    @SerialName("key_destroyed") val keyDestroyed: Boolean = false,
    @SerialName("cleanup_pending") val cleanupPending: Boolean = false,
    @SerialName("receipt_references") val receiptReferences: List<String> = emptyList(),
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class AcquireRoomEpochBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("room_id") val roomId: String,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("audience_account_id_hashes") val audienceAccountIdHashes: List<String>,
    @SerialName("authorized_installation_ids") val authorizedInstallationIds: List<String>,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("requesting_key_id") val requestingKeyId: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("minimum_active_millis") val minimumActiveMillis: Long,
)

@Serializable
private data class ReleaseRoomEpochBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("epoch_id") val epochId: String,
    val generation: Long,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("audience_digest_sha256") val audienceDigestSha256: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("requesting_key_id") val requestingKeyId: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class RoomEpochResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("epoch_id") val epochId: String,
    val generation: Long,
    @SerialName("room_id_sha256") val roomIdSha256: String,
    @SerialName("audience_digest_sha256") val audienceDigestSha256: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("wrapping_algorithm") val wrappingAlgorithm: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("active_until_ms") val activeUntilEpochMillis: Long,
    @SerialName("release_until_ms") val releaseUntilEpochMillis: Long,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("encrypted_epoch_secret_b64") val encryptedEpochSecret: String,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class StoreShareResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("reference") val reference: String,
    @SerialName("stored_at_ms") val storedAtEpochMillis: Long,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class ReleaseShareBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("reference") val reference: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("requesting_installation_id") val requestingInstallationId: String,
    @SerialName("requesting_key_id") val requestingKeyId: String,
    @SerialName("context_sha256") val immutableContextSha256: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("burn_session_id") val burnSessionId: String? = null,
    @SerialName("progressive_video_playback") val progressiveVideoPlayback: String? = null,
)

@Serializable
private data class ReleaseShareResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("grant_id") val grantId: String,
    @SerialName("reference") val reference: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("installation_id") val installationId: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("wrapping_algorithm") val wrappingAlgorithm: String,
    @SerialName("context_sha256") val immutableContextSha256: String,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("lease_mode") val leaseMode: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("refresh_after_ms") val refreshAfterEpochMillis: Long,
    @SerialName("revocation_sequence") val revocationSequence: Long,
    @SerialName("maximum_uses") val maximumUses: Int,
    @SerialName("encrypted_kms_share_b64") val encryptedKmsShare: String,
    @SerialName("burn_session_id") val burnSessionId: String? = null,
    @SerialName("server_signature_b64") val serverSignature: String,
    @SerialName("progressive_video_playback") val progressiveVideoPlayback: ProgressiveVideoPlaybackResponse? = null,
)

@Serializable
private data class ProgressiveVideoPlaybackResponse(
    @SerialName("scope") val scope: String,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class FetchRevocationsBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("known_sequence") val knownSequence: Long,
    @SerialName("request_nonce_b64") val requestNonce: String,
)

@Serializable
private data class FetchRevocationsResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("sequence") val sequence: Long,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("refresh_after_ms") val refreshAfterEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("request_nonce_b64") val requestNonce: String,
    @SerialName("device_access_state") val deviceAccessState: String,
    @SerialName("revoked_lease_ids") val revokedLeaseIds: List<String>,
    @SerialName("revoked_content_ids") val revokedContentIds: List<String>,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@Serializable
private data class DestroyShareBody(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("reason") val reason: String,
)

@Serializable
private data class DestroyShareResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("destroyed") val destroyed: Boolean,
    @SerialName("tombstone_version") val tombstoneVersion: Long,
    @SerialName("server_time_ms") val serverTimeEpochMillis: Long,
    @SerialName("server_signature_b64") val serverSignature: String,
)

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ManifestKmsClientConfigurationProvider(
    @ApplicationContext private val context: Context,
) : KmsClientConfigurationProvider {
    @Suppress("DEPRECATION")
    override fun configuration(): KmsClientConfiguration? = try {
        val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData ?: return null
        val baseUrl = metadata.getString(METADATA_BASE_URL)?.trim().orEmpty()
        val signingKey = metadata.getString(METADATA_SIGNING_KEY)?.trim().orEmpty()
        if (baseUrl.isBlank() || signingKey.isBlank()) return null
        val pins = metadata.getString(METADATA_CERTIFICATE_PINS).orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        KmsClientConfiguration(
            baseUrl = baseUrl,
            signingPublicKeyDer = Base64.getDecoder().decode(signingKey),
            certificatePinsSha256 = pins,
            maximumOfflineLeaseMillis = metadata.longValue(METADATA_MAXIMUM_OFFLINE_LEASE_MILLIS),
            maximumOfflineLicenseUses = metadata.getInt(METADATA_MAXIMUM_OFFLINE_LICENSE_USES, 0),
            clockRollbackToleranceMillis = metadata.longValue(METADATA_CLOCK_ROLLBACK_TOLERANCE_MILLIS),
            allowCleartextForTests = false,
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val METADATA_BASE_URL = "io.yizhechat.kms.BASE_URL"
        const val METADATA_SIGNING_KEY = "io.yizhechat.kms.SIGNING_PUBLIC_KEY_DER_B64"
        const val METADATA_CERTIFICATE_PINS = "io.yizhechat.kms.CERTIFICATE_PINS_SHA256"
        const val METADATA_MAXIMUM_OFFLINE_LEASE_MILLIS = "io.yizhechat.kms.MAXIMUM_OFFLINE_LEASE_MILLIS"
        const val METADATA_MAXIMUM_OFFLINE_LICENSE_USES = "io.yizhechat.kms.MAXIMUM_OFFLINE_LICENSE_USES"
        const val METADATA_CLOCK_ROLLBACK_TOLERANCE_MILLIS = "io.yizhechat.kms.CLOCK_ROLLBACK_TOLERANCE_MILLIS"
    }

    private fun android.os.Bundle.longValue(name: String): Long = when (val value = get(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }
}

@Serializable
private data class KmsDeviceProofWire(
    val version: Int,
    @SerialName("installation_id") val installationId: String,
    @SerialName("timestamp_ms") val timestampEpochMillis: Long,
    val nonce: String,
    @SerialName("operation_id") val operationId: String,
)

@Serializable
private data class ControlPlaneErrorResponse(
    val detail: ControlPlaneErrorDetail? = null,
)

@Serializable
private data class PrivateProfileErrorResponse(
    val detail: String? = null,
)

@Serializable
private data class ControlPlaneErrorDetail(
    val code: String? = null,
    @SerialName("retryable_after_policy_refresh") val retryableAfterPolicyRefresh: Boolean = false,
    val retryable: Boolean = false,
)

/** Short-lived proof-of-possession token signed by the hardware-backed device attestation key. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DeviceProofKmsAuthorizationProvider(
    private val deviceTrustService: DeviceTrustService,
) : KmsAuthorizationProvider {
    private val random = java.security.SecureRandom()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun authorization(): KmsRequestAuthorization? = authorizationForTrace(null)

    internal suspend fun authorizationForTrace(trace: KmsTransportTrace?): KmsRequestAuthorization? {
        var unavailableReason = "provider_exception"
        return try {
            val trust = proofStage(trace, "kms_proof_trust_current") {
                if (trace == null) deviceTrustService.current()
                else deviceTrustService.current(diagnosticSink(trace, DeviceTrustDiagnosticCall.CURRENT))
            }
            if (trust.attestationVersion <= 0 || trust.installationId.isBlank()) {
                proofUnavailable(trace, "not_enrolled")
                return null
            }
            val lifecycle = currentLifecycleOperationContext() ?: run {
                proofUnavailable(trace, "missing_operation")
                return null
            }
            val payload = json.encodeToString(
                KmsDeviceProofWire(
                    version = 2,
                    installationId = trust.installationId,
                    timestampEpochMillis = System.currentTimeMillis(),
                    nonce = ByteArray(24).also(random::nextBytes).base64Url(),
                    operationId = lifecycle.operationId,
                )
            ).encodeToByteArray()
            val signed = proofStage(trace, "kms_proof_sign_control") {
                if (trace == null) deviceTrustService.signControlStatement(KMS_PROOF_MAGIC + payload)
                else deviceTrustService.signControlStatement(
                    KMS_PROOF_MAGIC + payload,
                    diagnosticSink(trace, DeviceTrustDiagnosticCall.SIGN_CONTROL),
                )
            }
            if (signed.installationId != trust.installationId) unavailableReason = "identity_changed"
            check(signed.installationId == trust.installationId)
            KmsRequestAuthorization.bearer(payload.base64Url() + "." + signed.signature.base64Url())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            proofUnavailable(trace, unavailableReason)
            null
        }
    }

    private enum class DeviceTrustDiagnosticCall { CURRENT, SIGN_CONTROL }

    private fun diagnosticSink(trace: KmsTransportTrace, call: DeviceTrustDiagnosticCall): DeviceTrustDiagnosticSink =
        DeviceTrustDiagnosticSink { stage, start, end, outcome ->
            val operation = trace.operationId.ifEmpty { "unbound" }
            if (end < start || !DIAGNOSTIC_ID.matches(trace.requestId) || !DIAGNOSTIC_ID.matches(operation)) {
                return@DeviceTrustDiagnosticSink
            }
            runCatching {
                // Original request/operation only; no trust record, key, signature, payload, nonce or exception text.
                Timber.tag("YiZheDeviceTrust").d(
                    "stage=%s call=%s request=%s operation=%s start_ns=%d end_ns=%d duration_us=%d outcome=%s",
                    stage.name, call.name, trace.requestId, operation, start, end, (end - start) / 1_000L, outcome.name,
                )
            }
        }

    private suspend fun <T> proofStage(trace: KmsTransportTrace?, stage: String, block: suspend () -> T): T =
        if (trace == null) block() else LifecycleStageTiming.measure(
            stage, identity = trace.requestId, scope = trace.action, operationId = trace.operationId, block = block,
        )

    private fun proofUnavailable(trace: KmsTransportTrace?, reason: String) {
        if (trace != null) LifecycleStageTiming.observed(
            "kms_proof_unavailable", trace.requestId, reason, trace.operationId,
        )
    }

    override suspend fun isAvailable(): Boolean = runCatching {
        val trust = deviceTrustService.current()
        trust.attestationVersion > 0 && trust.installationId.isNotBlank() && trust.allowsLocalVaultAccess
    }.getOrDefault(false)

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private companion object {
        val DIAGNOSTIC_ID = Regex("[A-Za-z0-9_.:-]{1,160}")
        val KMS_PROOF_MAGIC = "YZKMS0002".encodeToByteArray()
    }
}
