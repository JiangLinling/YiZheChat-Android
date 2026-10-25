/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.dataprotection.api.APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountDeletionPreparationRequest
import io.element.android.security.dataprotection.api.AccountDeletionReceipt
import io.element.android.security.dataprotection.api.AccountDeletionRecoveryRequest
import io.element.android.security.dataprotection.api.AccountReactivationRequest
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.DeletionScopedClientCapability
import io.element.android.security.dataprotection.api.DeviceLocationEvidence
import io.element.android.security.dataprotection.api.DeviceLocationEvidenceState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleCompensationStatus
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaSeal
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagedRoomCreationRequest
import io.element.android.security.dataprotection.api.ManagementCommandExecutionReport
import io.element.android.security.dataprotection.api.ManagementCommandState
import io.element.android.security.dataprotection.api.ManagementCommandType
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementHeartbeatReport
import io.element.android.security.dataprotection.api.ManagementHeartbeatStatus
import io.element.android.security.dataprotection.api.ManagementPolicyApplication
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.OfficePreviewGrantRequest
import io.element.android.security.dataprotection.api.OnboardingActivation
import io.element.android.security.dataprotection.api.OnboardingInvitation
import io.element.android.security.dataprotection.api.OnboardingPasswordPolicy
import io.element.android.security.dataprotection.api.OnboardingUsernameSuggestion
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingGlobalDestroyException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingMatrixException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceipt
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceiptResult
import io.element.android.security.dataprotection.api.PermanentAccountDeletionPreparationExpiredException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionState
import io.element.android.security.dataprotection.api.PermanentAccountDeletionStatus
import io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException
import io.element.android.security.dataprotection.api.PolicyApplicationEvidence
import io.element.android.security.dataprotection.api.PolicyCleanupOperation
import io.element.android.security.dataprotection.api.PolicyCleanupOperationState
import io.element.android.security.dataprotection.api.PreparedAccountDeletion
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.dataprotection.api.PreparedAppUpdate
import io.element.android.security.dataprotection.api.RecoverableAccountDeletion
import io.element.android.security.dataprotection.api.SecurityErasureMilestone
import io.element.android.security.dataprotection.api.SignedOfficePreviewGrant
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.DeviceAttestationEvidence
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpManagementControlService(
    @ApplicationContext private val context: Context,
    private val configuration: ManagementControlConfiguration,
    private val signatureVerifier: ManagementSignatureVerifier,
    private val repository: SignedManagementStateRepository,
    private val deviceTrustService: DeviceTrustService,
    private val sessionStore: SessionStore,
    private val matrixClientProvider: () -> MatrixClientProvider,
    private val buildMeta: BuildMeta,
    private val kmsConfigurationProvider: KmsClientConfigurationProvider,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val deviceRuntimeBindingProvider: DeviceRuntimeBindingProvider,
) : ManagementControlService {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }
    private val random = SecureRandom()
    private val client: OkHttpClient by lazy(::createClient)
    private val bootstrapClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
    private val onboardingActivationClient: OkHttpClient by lazy { createOnboardingActivationClient(bootstrapClient) }
    @Volatile private var accessTicket: DeviceAccessTicketWire? = null
    private val enrollmentMutex = Mutex()
    private val policyApplicationReceiptDelivery = PolicyApplicationReceiptDelivery(
        loadPending = repository::pendingPolicyApplicationReceipt,
        sendAndValidate = ::sendAndValidatePolicyApplicationReceipt,
        markDelivered = repository::markPolicyApplicationReceiptDelivered,
    )

    override suspend fun ensureDeviceEnrollment(): Unit = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) return@withContext
        ensureDeviceEnrollmentLocked(
            forceRemoteEnrollment = false,
            observedAttestationVersion = deviceTrustService.current().attestationVersion,
        )
    }

    override suspend fun recheckPendingDeviceEnrollment(): Unit = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) return@withContext
        val observed = deviceTrustService.current()
        ensureDeviceEnrollmentLocked(
            forceRemoteEnrollment = observed.trustState == DeviceTrustState.PENDING,
            observedAttestationVersion = observed.attestationVersion,
            forcePendingOnly = true,
        )
    }

    private val synchronizationMutex = kotlinx.coroutines.sync.Mutex()
    private var restoredAccessConfirmationPending = false

    override suspend fun synchronize(): ManagementSyncReport = withContext(Dispatchers.IO) {
        synchronizationMutex.withLock {
        if (!configuration.isConfigured) return@withContext ManagementSyncReport(ManagementSyncStatus.DISABLED)
        runCatchingExceptions {
            ensureDeviceEnrollment()
            policyApplicationReceiptDelivery.flush()
            val trustBeforeSync = deviceTrustService.current()
            val response = runCatchingExceptions { performSync() }.recoverCatching { failure ->
                if (failure !is EnrollmentRequiredException) throw failure
                ensureDeviceEnrollmentLocked(
                    forceRemoteEnrollment = true,
                    observedAttestationVersion = deviceTrustService.current().attestationVersion,
                )
                performSync()
            }.getOrThrow()
            var report = acceptSyncResponse(response)
            if (trustBeforeSync.trustState == DeviceTrustState.STALE && deviceTrustService.current().allowsLocalVaultAccess) {
                restoredAccessConfirmationPending = true
            }
            if (restoredAccessConfirmationPending && deviceTrustService.current().allowsLocalVaultAccess && repository.pendingCommands().isEmpty()) {
                // The first signed decision restores LOCAL trust. KMS still sees the STALE state
                // reported in that request. Confirm the restored state in this same lifecycle,
                // before the coordinator publishes access recovery or retries room projections.
                report = acceptSyncResponse(performSync())
                check(!deviceTrustService.current().allowsLocalVaultAccess || accessTicket != null) {
                    "Restored device access confirmation is pending."
                }
                restoredAccessConfirmationPending = false
            } else if (!deviceTrustService.current().allowsLocalVaultAccess) {
                restoredAccessConfirmationPending = false
            }
            policyApplicationReceiptDelivery.flush()
            report
        }.getOrElse { failure ->
            if (restoredAccessConfirmationPending) {
                accessTicket = null
                deviceTrustService.applyRemoteRestriction(DeviceTrustState.STALE, System.currentTimeMillis())
            }
            logDebugFailure(failure)
            val classifiedReason = managementSyncFailureReasonCode(failure)
            val securityFailure = failure is SecurityException || failure is IllegalStateException ||
                classifiedReason in setOf("CONTROL_TLS_REJECTED", "DEVICE_SIGNING_KEY_UNAVAILABLE") ||
                failure is HttpStatusException && failure.statusCode in setOf(400, 401, 403)
            if (securityFailure) repository.clearOfflineEntry()
            val httpReason = (failure as? HttpStatusException)?.let {
                when {
                    it.statusCode == 429 -> "CONTROL_RATE_LIMITED"
                    it.statusCode == 408 || it.statusCode in 500..599 -> "CONTROL_SERVER_UNAVAILABLE"
                    else -> "CONTROL_SERVER_REJECTED"
                }
            }
            ManagementSyncReport(
                status = if (securityFailure) ManagementSyncStatus.SECURITY_REJECTED else ManagementSyncStatus.RETRY_REQUIRED,
                acceptedPolicyVersion = repository.currentPolicyVersion(),
                pendingCommandCount = repository.pendingCommands().size,
                errorCode = (failure as? ManagementSyncStageException)?.errorCode
                    ?: httpReason ?: classifiedReason
                    ?: if (securityFailure) ERROR_SECURITY_REJECTED else ERROR_NETWORK_RETRY,
                retryAfterMillis = (failure as? HttpStatusException)?.retryAfterMillis,
            )
        }
        }
    }

    override suspend fun appOperationalSnapshot(
        accountIdHash: String,
        matrixDeviceIdHash: String,
    ): AppOperationalSnapshot = withContext(Dispatchers.IO) {
        val request = LifecycleSubjectHashesWire(
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        val responseText = executeSignedPost(
            APP_OPERATIONAL_SNAPSHOT_PATH,
            json.encodeToString(request).encodeToByteArray(),
        ).decodeToString()
        val payload = json.parseToJsonElement(responseText).jsonObject
        val wire = json.decodeFromJsonElement(AppOperationalSnapshotWire.serializer(), payload)
        val signedPayload = JsonObject(payload.filterKeys { it != "signature_b64" })
        check(signatureVerifier.verifyDetached(signedPayload, wire.signingKeyId, wire.signatureBase64)) {
            "App operational snapshot signature verification failed."
        }
        val trust = deviceTrustService.current()
        check(wire.installationId == trust.installationId) { "App operational snapshot targets another installation." }
        check(wire.accountIdHash == accountIdHash) { "App operational snapshot targets another account." }
        check(wire.matrixDeviceIdHash == matrixDeviceIdHash) { "App operational snapshot targets another Matrix device." }
        check(wire.activeOperationIds.size <= 128 && wire.activeOperationIds.distinct().size == wire.activeOperationIds.size)
        wire.toApi().copy(verifiedSignedJson = payload.toString()).also {
            check(it.isUsableAt(System.currentTimeMillis(), APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS)) {
                "App operational snapshot is outside its accepted time window."
            }
        }
    }

    override suspend fun expireRecoveryOperation(record: io.element.android.security.dataprotection.api.RecoveryIntentRecord) {
        withContext(Dispatchers.IO) {
            val request = kotlinx.serialization.json.buildJsonObject {
                put("operation_id", record.operationId)
                put("account_id_hash", record.scope.accountIdHash)
                put("matrix_device_id_hash", record.scope.matrixDeviceIdHash)
            }
            executeSignedPost(
                "$LIFECYCLE_OPERATIONS_PATH/${record.operationId}/expire-recovery",
                request.toString().encodeToByteArray(),
                record.operationId
            )
        }
    }

    override suspend fun managedAuthorizationHandoverSubjects(): LifecycleSubjectHashes? {
        val session = sessionStore.getAllSessions().singleOrNull()?.takeIf { it.isTokenValid } ?: return null
        return LifecycleSubjectHashes(
            accountIdHash = accountIdHash(session.userId),
            matrixDeviceIdHash = sha256Hex(session.deviceId.encodeToByteArray()),
        )
    }

    override suspend fun transferManagedDeviceAuthorization(accountIdHash: String, matrixDeviceIdHash: String) {
        withContext(Dispatchers.IO) {
            val token = recoveryMatrixToken(accountIdHash, matrixDeviceIdHash)
            val request = kotlinx.serialization.json.buildJsonObject {
                put("account_id_hash", accountIdHash)
                put("matrix_device_id_hash", matrixDeviceIdHash)
                put("matrix_access_token", token)
                put("confirmed", true)
            }
            // The caller owns a persisted LC06 intent. HTTP success does not unlock
            // anything: synchronize must validate the signed license and trust decision.
            executeSignedPost("/v1/device/managed-license-handover", request.toString().encodeToByteArray())
        }
    }

    internal suspend fun recoveryMatrixToken(accountHash: String, matrixHash: String): String {
        val matches = sessionStore.getAllSessions().filter {
            it.isTokenValid && accountIdHash(it.userId) == accountHash && sha256Hex(it.deviceId.encodeToByteArray()) == matrixHash
        }
        check(matches.size == 1) { "RECOVERY_MATRIX_SESSION_UNAVAILABLE" }
        val stored = matches.single()
        val provider = matrixClientProvider()
        val client = provider.getOrNull(UserId(stored.userId))
            ?: provider.getOrRestore(UserId(stored.userId)).getOrThrow()
        check(client.sessionId.value == stored.userId && client.deviceId.value == stored.deviceId) {
            "RECOVERY_MATRIX_SESSION_UNAVAILABLE"
        }
        return client.recoverySessionAccessToken().getOrThrow().also {
            check(it.length in 16..8192 && '\r' !in it && '\n' !in it && !it.startsWith("yzvault1:")) {
                "RECOVERY_MATRIX_SESSION_UNAVAILABLE"
            }
        }
    }

    override suspend fun resolveRecoveryIntent(
        record: io.element.android.security.dataprotection.api.RecoveryIntentRecord,
    ): io.element.android.security.dataprotection.api.RecoveryIntentResolution = withContext(Dispatchers.IO) {
        val nonce = ByteArray(24).also(random::nextBytes).toBase64()
        val token = recoveryMatrixToken(record.scope.accountIdHash, record.scope.matrixDeviceIdHash)
        val idempotencyHash = sha256Hex(record.idempotencyKey.encodeToByteArray())
        val request = kotlinx.serialization.json.buildJsonObject {
            put("operation_id", record.operationId)
            put("account_id_hash", record.scope.accountIdHash)
            put("matrix_device_id_hash", record.scope.matrixDeviceIdHash)
            put("idempotency_key_sha256", idempotencyHash)
            put("deadline_ms", record.deadlineEpochMillis)
            put("request_nonce_b64", nonce)
            put("matrix_access_token", token)
            record.recoveryMode?.let { put("recovery_mode", it.name) }
        }
        // The draft may never have been admitted; signing its operation header would create a bootstrap cycle.
        val response = executeSignedPost("/v1/lifecycle/recovery-intents/resolve", request.toString().encodeToByteArray(), bindLifecycle = false)
        val envelope = json.parseToJsonElement(response.decodeToString()).jsonObject
        val payload = envelope.getValue("resolution").jsonObject
        check(signatureVerifier.verifyDetached(
            payload,
            envelope.getValue("key_id").jsonPrimitive.content,
            envelope.getValue("signature_b64").jsonPrimitive.content
        )) { "RECOVERY_RESOLUTION_SIGNATURE_REJECTED" }
        fun text(name: String) = payload.getValue(name).jsonPrimitive.content
        check(text("protocol_version") == "1" && text("request_nonce_b64") == nonce)
        val serverTime = text("server_time_ms").toLong()
        check(kotlin.math.abs(System.currentTimeMillis() - serverTime) <= 300_000L)
        val admitted = when (text("admitted")) {
            "true" -> true
            "false" -> false
            else -> error("RECOVERY_RESOLUTION_INVALID")
        }
        check(text("operation_id") == record.operationId && text("account_id_hash") == record.scope.accountIdHash &&
            text("installation_id") == record.scope.installationId && text("matrix_device_id_hash") == record.scope.matrixDeviceIdHash &&
            text("idempotency_key_sha256") == idempotencyHash && text("deadline_ms").toLong() == record.deadlineEpochMillis)
        io.element.android.security.dataprotection.api.RecoveryIntentResolution(
            record.operationId,
            record.scope,
            idempotencyHash,
            record.deadlineEpochMillis,
            serverTime,
            admitted,
            payload["recovery_mode"]?.jsonPrimitive?.content?.let { io.element.android.security.dataprotection.api.RecoveryHistoryMode.valueOf(it) },
        )
    }

    override suspend fun recoveryOperationContext(
        record: io.element.android.security.dataprotection.api.RecoveryIntentRecord,
    ): io.element.android.security.dataprotection.api.RecoveryOperationContext = withContext(Dispatchers.IO) {
        val nonce = ByteArray(24).also(random::nextBytes).toBase64()
        val token = recoveryMatrixToken(record.scope.accountIdHash, record.scope.matrixDeviceIdHash)
        val request = kotlinx.serialization.json.buildJsonObject {
            put("operation_id", record.operationId)
            put("account_id_hash", record.scope.accountIdHash)
            put("matrix_device_id_hash", record.scope.matrixDeviceIdHash)
            put("request_nonce_b64", nonce)
            put("matrix_access_token", token)
            record.recoveryId?.let { put("recovery_id", it) }
        }
        val response = executeSignedPost(
            "$LIFECYCLE_OPERATIONS_PATH/${record.operationId}/recovery-context",
            request.toString().encodeToByteArray(),
            record.operationId
        )
        val envelope = json.parseToJsonElement(response.decodeToString()).jsonObject
        val payload = envelope.getValue("context").jsonObject
        check(signatureVerifier.verifyDetached(
            payload,
            envelope.getValue("key_id").jsonPrimitive.content,
            envelope.getValue("signature_b64").jsonPrimitive.content
        )) { "RECOVERY_CONTEXT_SIGNATURE_REJECTED" }
        fun text(name: String) = payload.getValue(name).jsonPrimitive.content
        fun number(name: String) = text(name).toLong()
        fun boolean(name: String) = when (text(name)) {
            "true" -> true
            "false" -> false
            else -> error("RECOVERY_CONTEXT_INVALID")
        }
        fun optional(name: String) = payload[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        check(text("protocol_version") == "1" && text("request_nonce_b64") == nonce)
        val serverTime = number("server_time_ms")
        check(kotlin.math.abs(System.currentTimeMillis() - serverTime) <= 300_000L)
        io.element.android.security.dataprotection.api.RecoveryOperationContext(
            text("operation_id"),
            io.element.android.security.dataprotection.api.RecoveryIntentScope(text("account_id_hash"), text("installation_id"), text("matrix_device_id_hash")),
            text("trigger"),
            text("identity_generation"),
            number("mobile_generation"),
            text("current_identity_generation"),
            number("current_mobile_generation"),
            boolean("scope_valid"),
            boolean("root_valid"),
            LifecycleOperationStatus.valueOf(text("operation_state")),
            optional("terminal_receipt_id"),
            number("deadline_ms"),
            serverTime,
            boolean("matrix_complete"),
            optional("recovery_id"),
            optional("recovery_expires_at_ms")?.toLong(),
            optional("terminal_result_code"),
            text("idempotency_key_sha256"),
            boolean("current_root_valid"),
            optional("recovery_mode")?.let { io.element.android.security.dataprotection.api.RecoveryHistoryMode.valueOf(it) },
        ).also { context ->
            check(
                context.operationId == record.operationId && context.scope == record.scope &&
                    context.trigger == "recovery_key_entered" && context.deadlineEpochMillis == record.deadlineEpochMillis &&
                    context.recoveryMode == record.recoveryMode
            )
            check(context.identityGeneration.matches(Regex("[a-f0-9]{64}")) && context.mobileGeneration > 0)
            check(context.currentIdentityGeneration.matches(Regex("[a-f0-9]{64}")) && context.currentMobileGeneration > 0)
            check(context.idempotencyKeySha256 == MessageDigest.getInstance("SHA-256").digest(record.idempotencyKey.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) })
            check(context.status.isTerminal == (context.terminalReceiptId != null))
        }
    }

    override suspend fun startupLifecycleOperations(
        accountIdHash: String,
        matrixDeviceIdHash: String,
        trigger: String,
        previousOperationId: String?,
    ): List<LifecycleOperation> = withContext(Dispatchers.IO) {
        require(trigger in setOf("post_login", "reactivation", "first_activation"))
        val lifecycleId = if (trigger == "first_activation") FullAppLifecycleId.LC_05 else FullAppLifecycleId.LC_06
        val request = kotlinx.serialization.json.buildJsonObject {
            put("account_id_hash", accountIdHash)
            put("matrix_device_id_hash", matrixDeviceIdHash)
            put("trigger", trigger)
            previousOperationId?.let { put("previous_operation_id", it) }
        }
        val payload = json.parseToJsonElement(
            executeSignedPost("/v1/lifecycle/startup-operations", request.toString().encodeToByteArray()).decodeToString()
        ).jsonObject
        fun value(name: String) = checkNotNull(payload[name]).jsonPrimitive.content
        check(signatureVerifier.verifyDetached(
            JsonObject(payload.filterKeys { it != "signature_b64" }),
            value("signing_key_id"),
            value("signature_b64")
        )) { "Startup operation index signature verification failed." }
        val issued = value("issued_at_ms").toLong()
        val expires = value("expires_at_ms").toLong()
        val now = System.currentTimeMillis()
        check(
            value("schema_version") == "1" &&
                value("purpose") == (if (trigger == "first_activation") "LC05_STARTUP_RECONCILIATION" else "LC06_STARTUP_RECONCILIATION")
        )
        check(value("installation_id") == deviceTrustService.current().installationId)
        check(value("account_id_hash") == accountIdHash && value("matrix_device_id_hash") == matrixDeviceIdHash)
        check(value("trigger") == trigger)
        if (previousOperationId != null) {
            check(trigger == "first_activation" && value("previous_operation_id") == previousOperationId)
            check(value("previous_operation_state") in setOf("NOT_FOUND", "TERMINAL", "LIVE_MATCHING", "UNRELATED"))
        }
        check(issued <= now + APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS &&
            now < expires && expires > issued && expires - issued <= 60_000L)
        val ids = json.decodeFromJsonElement<List<String>>(checkNotNull(payload["operation_ids"]))
        check(ids.size <= 128 && ids.distinct().size == ids.size)
        readStartupOperationIndex(ids) { operationId ->
            lifecycleOperation(operationId).also { operation ->
                check(operation.operationId == operationId && operation.lifecycleId == lifecycleId)
                check(operation.subjects.installationId == value("installation_id"))
                check(operation.subjects.accountIdHash == accountIdHash && operation.subjects.matrixDeviceIdHash == matrixDeviceIdHash)
            }
        }
    }

    override suspend fun startLifecycleOperation(
        request: LifecycleOperationRequest,
    ): LifecycleOperation = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            LifecycleOperationStartWire(
                operationId = request.operationId,
                lifecycleId = request.lifecycleId.wireValue,
                trigger = request.trigger,
                idempotencyKey = request.idempotencyKey,
                accountIdHash = request.accountIdHash,
                matrixDeviceIdHash = request.matrixDeviceIdHash,
                roomIdHash = request.roomIdHash,
                eventIdHash = request.eventIdHash,
                deadlineEpochMillis = request.deadlineEpochMillis,
                mediaCleanupProtocol = request.mediaCleanupProtocol,
                recoveryMode = request.recoveryMode?.name,
            )
        ).let { encoded ->
            if (request.roomCreationProtocol != null && configuration.isNextDeployment) {
                check(request.lifecycleId == FullAppLifecycleId.LC_12 && request.trigger == "room_create")
                kotlinx.serialization.json.buildJsonObject {
                    json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
                    put("room_creation_protocol", request.roomCreationProtocol)
                }.toString()
            } else if (request.lifecycleId == FullAppLifecycleId.LC_08 && request.trigger == "recovery_key_entered") {
                val token = recoveryMatrixToken(checkNotNull(request.accountIdHash), checkNotNull(request.matrixDeviceIdHash))
                kotlinx.serialization.json.buildJsonObject {
                    json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
                    put("matrix_access_token", token)
                }.toString()
            } else {
                encoded
            }
        }.encodeToByteArray()
        val observedAttestationVersion = deviceTrustService.current().attestationVersion
        val response = try {
            // A new recovery intent must not inherit the old failed operation header.
            // The body still carries the exact candidate subjects and runtime Matrix session proof.
            executeSignedPost(
                LIFECYCLE_OPERATIONS_PATH,
                body,
                bindLifecycle = !(request.lifecycleId == FullAppLifecycleId.LC_08 && request.trigger == "recovery_key_entered")
            )
        } catch (failure: HttpStatusException) {
            logDebugFailure(failure)
            if (
                request.lifecycleId != FullAppLifecycleId.LC_02 ||
                !isStableEnrollmentBootstrapFailure(failure.statusCode, failure.safeDetail)
            ) {
                throw failure
            }
            ensureDeviceEnrollmentLocked(
                forceRemoteEnrollment = true,
                observedAttestationVersion = observedAttestationVersion,
            )
            // This is continuation of the same durable LC-02 intent, not a timing retry:
            // body, operationId and idempotency key remain byte-for-byte identical.
            executeSignedPost(LIFECYCLE_OPERATIONS_PATH, body)
        }
        json.decodeFromString(LifecycleOperationWire.serializer(), response.decodeToString()).toApi().also {
            check(it.authorityRevisions["media_cleanup_protocol"] == request.mediaCleanupProtocol) {
                "Original media cleanup protocol was not acknowledged."
            }
        }
    }

    private suspend fun groupShareRequest(userId: String, roomId: String, token: String? = null): kotlinx.serialization.json.JsonObject {
        val operation = checkNotNull(currentLifecycleOperationContext())
        check(operation.lifecycleId == FullAppLifecycleId.LC_04)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("operation_id", operation.operationId)
            put("user_id", userId)
            put("room_id", roomId)
            token?.let { put("token", it) }
        }
        val response = try {
            executeSignedPost(
                "/v1/group-shares/" + if (token == null) "issue" else "redeem",
                body.toString().encodeToByteArray(),
                operation.operationId
            )
        } catch (failure: HttpStatusException) {
            logDebugFailure(failure)
            if (failure.statusCode == 403 && failure.safeDetail == "GROUP_SHARE_INVITE_FORBIDDEN") {
                throw io.element.android.security.dataprotection.api.GroupSharePermissionException()
            }
            throw failure
        }
        return json.parseToJsonElement(response.decodeToString()).jsonObject.also {
            check(it.getValue("room_id").jsonPrimitive.content == roomId)
        }
    }

    override suspend fun issueGroupShare(userId: String, roomId: String) = withContext(Dispatchers.IO) {
        val response = groupShareRequest(userId, roomId)
        io.element.android.security.dataprotection.api.GroupShareCapability(
            roomId,
            response.getValue("token").jsonPrimitive.content,
            response.getValue("expires_at_ms").jsonPrimitive.content.toLong()
        )
    }

    override suspend fun redeemGroupShare(userId: String, roomId: String, token: String): Unit = withContext(Dispatchers.IO) {
        check(groupShareRequest(userId, roomId, token).getValue("state").jsonPrimitive.content == "INVITED")
    }

    override val supportsManagedRoomCreation: Boolean get() = configuration.isNextDeployment

    private fun friendshipRemovalClient() = FriendshipRemovalClient(
        signatureVerifier,
        { deviceTrustService.current().installationId }
    ) { query ->
        val token = recoveryMatrixToken(
            query.getValue("account_id_hash").jsonPrimitive.content,
            query.getValue("matrix_device_id_hash").jsonPrimitive.content
        )
        val body = JsonObject(query + ("matrix_access_token" to kotlinx.serialization.json.JsonPrimitive(token)))
        json.parseToJsonElement(executeSignedPost(
            "/v1/lifecycle/friendship-removal",
            body.toString().encodeToByteArray(),
            bindLifecycle = false
        ).decodeToString()).jsonObject
    }

    override suspend fun friendshipRemovalPreview(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String) =
        withContext(Dispatchers.IO) {
            if (!configuration.isNextDeployment) return@withContext null
            friendshipRemovalClient().preview(accountIdHash, matrixDeviceIdHash, peerUserId)
        }

    override suspend fun beginFriendshipRemoval(snapshot: io.element.android.security.dataprotection.api.FriendshipRemovalSnapshot,
        rooms: Set<String>) = withContext(Dispatchers.IO) { friendshipRemovalClient().begin(snapshot, rooms) }

    override suspend fun completeFriendshipRemoval(intent: io.element.android.security.dataprotection.api.FriendshipRemovalIntent) =
        withContext(Dispatchers.IO) { friendshipRemovalClient().complete(intent) }

    override suspend fun prepareRoomMembership(
        accountIdHash: String, matrixDeviceIdHash: String, roomId: String, targetUserId: String, action: String, reason: String?,
    ): io.element.android.security.dataprotection.api.RoomMembershipIntent? = withContext(Dispatchers.IO) {
        if (!configuration.isNextDeployment) return@withContext null
        val token = io.element.android.security.dataprotection.api.membershipStage(
            io.element.android.security.dataprotection.api.MembershipStage.MATRIX_SESSION,
        ) { recoveryMatrixToken(accountIdHash, matrixDeviceIdHash) }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("room_id", roomId)
            put("target_user_id", targetUserId)
            put("action", action)
            reason?.let { put("reason", it) }
            put("matrix_access_token", token)
            currentLifecycleOperationContext()?.takeIf { it.lifecycleId == FullAppLifecycleId.LC_11 }?.let {
                put("friendship_operation_id", it.operationId)
            }
        }.toString().encodeToByteArray()
        val result = io.element.android.security.dataprotection.api.membershipStage(
            io.element.android.security.dataprotection.api.MembershipStage.LC12_PREPARE,
        ) {
            val response = try {
                executeSignedPost("/v1/lifecycle/membership/prepare", body, bindLifecycle = false)
            } catch (failure: HttpStatusException) {
                throw io.element.android.security.dataprotection.api.RoomMembershipFailure(
                    io.element.android.security.dataprotection.api.MembershipStage.LC12_PREPARE,
                    membershipHttpCode(failure.statusCode, failure.safeDetail),
                    failure,
                )
            }
            json.parseToJsonElement(response.decodeToString()).jsonObject
        }
        val operationId = result.getValue("operation_id").jsonPrimitive.content
        check(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        val dispatch = result.getValue("dispatch").jsonPrimitive.content.toBooleanStrict()
        io.element.android.security.dataprotection.api.RoomMembershipIntent(operationId, dispatch)
    }

    override suspend fun managedDirectRoom(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String, create: Boolean) =
        withContext(Dispatchers.IO) {
            check(configuration.isNextDeployment) { "Managed direct protocol is unavailable" }
            ManagedDirectRoomClient(signatureVerifier, { deviceTrustService.current().installationId }) { query ->
                val token = recoveryMatrixToken(accountIdHash, matrixDeviceIdHash)
                val body = JsonObject(query + ("matrix_access_token" to kotlinx.serialization.json.JsonPrimitive(token)))
                json.parseToJsonElement(executeSignedPost(
                    "/v1/lifecycle/direct-room",
                    body.toString().encodeToByteArray(),
                    bindLifecycle = false
                ).decodeToString()).jsonObject
            }.execute(accountIdHash, matrixDeviceIdHash, peerUserId, create)
        }

    override suspend fun setContactBlocked(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String, blocked: Boolean) =
        withContext(Dispatchers.IO) {
            check(configuration.isNextDeployment) { "Contact policy protocol is unavailable" }
            ManagedContactPolicyClient(signatureVerifier, { deviceTrustService.current().installationId }) { query ->
                val token = recoveryMatrixToken(accountIdHash, matrixDeviceIdHash)
                val body = JsonObject(query + ("matrix_access_token" to kotlinx.serialization.json.JsonPrimitive(token)))
                json.parseToJsonElement(executeSignedPost(
                    "/v1/lifecycle/contact-policy",
                    body.toString().encodeToByteArray(),
                    bindLifecycle = false
                ).decodeToString()).jsonObject
            }.setBlocked(accountIdHash, matrixDeviceIdHash, peerUserId, blocked)
        }

    override suspend fun requireContactRoomAllowed(accountIdHash: String, matrixDeviceIdHash: String, roomId: String) =
        withContext(Dispatchers.IO) {
            check(configuration.isNextDeployment) { "Contact policy protocol is unavailable" }
            ManagedContactPolicyClient(signatureVerifier, { deviceTrustService.current().installationId }) { query ->
                val token = recoveryMatrixToken(accountIdHash, matrixDeviceIdHash)
                val body = JsonObject(query + ("matrix_access_token" to kotlinx.serialization.json.JsonPrimitive(token)))
                json.parseToJsonElement(executeSignedPost(
                    "/v1/lifecycle/contact-policy",
                    body.toString().encodeToByteArray(),
                    bindLifecycle = false
                ).decodeToString()).jsonObject
            }.requireRoomAllowed(accountIdHash, matrixDeviceIdHash, roomId)
        }

    override suspend fun contactCallTerminationReason(accountIdHash: String, matrixDeviceIdHash: String, roomId: String, operationId: String) =
        withContext(Dispatchers.IO) {
            check(configuration.isNextDeployment) { "Contact policy protocol is unavailable" }
            ManagedContactPolicyClient(signatureVerifier, { deviceTrustService.current().installationId }) { query ->
                val token = recoveryMatrixToken(accountIdHash, matrixDeviceIdHash)
                val body = JsonObject(query + ("matrix_access_token" to kotlinx.serialization.json.JsonPrimitive(token)))
                json.parseToJsonElement(executeSignedPost(
                    "/v1/lifecycle/contact-policy",
                    body.toString().encodeToByteArray(),
                    bindLifecycle = false
                ).decodeToString()).jsonObject
            }.callTerminationReason(accountIdHash, matrixDeviceIdHash, roomId, operationId)
        }

    override suspend fun createManagedRoom(request: ManagedRoomCreationRequest): String? = withContext(Dispatchers.IO) {
        if (!configuration.isNextDeployment) return@withContext null
        val context = checkNotNull(currentLifecycleOperationContext()) { "Managed room creation requires its original lifecycle." }
        check(context.lifecycleId == FullAppLifecycleId.LC_12)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("protocol_version", 1)
            put("operation_id", context.operationId)
            put("creator_user_id", request.creatorUserId)
            put("name", request.name)
            request.topic?.let { put("topic", it) }
            put("invitees", kotlinx.serialization.json.JsonArray(request.invitees.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("profile_kind", request.profileKind)
            put("visibility", request.visibility)
            put("join_rule", request.joinRule)
            request.parentRoomId?.let { put("parent_room_id", it) }
            request.roomAliasName?.let { put("room_alias_name", it) }
            request.avatarUrl?.let { put("avatar_url", it) }
        }.toString().encodeToByteArray()
        val response = json.parseToJsonElement(
            executeSignedPost("/v1/message-lifecycle/rooms/create", body, context.operationId).decodeToString(),
        ).jsonObject
        check(response.getValue("operation_id").jsonPrimitive.content == context.operationId)
        response.getValue("room_id").jsonPrimitive.content.also { roomId ->
            check(roomId.startsWith("!") && ':' in roomId && roomId.length <= 255 && roomId.none(Char::isWhitespace))
        }
    }

    override suspend fun verifyStartupCoordinationExpiry(operation: LifecycleOperation, trigger: String): Boolean = withContext(Dispatchers.IO) {
        val receiptId = operation.terminalReceiptId ?: return@withContext false
        require(receiptId.matches(Regex("[A-Za-z0-9_-]{8,256}")))
        val receipt = json.parseToJsonElement(executeSignedGet(
            "/v1/lifecycle/receipts/$receiptId",
            operation.operationId,
        ).decodeToString()).jsonObject
        verifyStartupExpiryReceipt(
            receipt,
            operation,
            trigger,
            deviceTrustService.current().installationId,
            System.currentTimeMillis()
        ) { payload, signature ->
            signatureVerifier.verifyDetached(payload, configuration.signingKeyId, signature)
        }
    }

    override suspend fun lifecycleOperationsForSnapshot(
        snapshot: AppOperationalSnapshot,
        operationIds: List<String>,
    ): List<LifecycleOperation>? = withContext(Dispatchers.IO) {
        val proof = json.parseToJsonElement(checkNotNull(snapshot.verifiedSignedJson)).jsonObject
        require(operationIds.size in 1..128 && operationIds.distinct().size == operationIds.size)
        require(operationIds.all { it.matches(LIFECYCLE_OPERATION_ID_PATTERN) && it in snapshot.activeOperationIds })
        check(snapshot.installationId == deviceTrustService.current().installationId)
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes).toBase64Url()
        val request = kotlinx.serialization.json.buildJsonObject {
            put("snapshot", proof)
            put("operation_ids", kotlinx.serialization.json.JsonArray(operationIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("nonce", nonce)
        }.toString().encodeToByteArray()
        require(request.size <= OPERATION_SNAPSHOT_MAX_REQUEST_BYTES)
        val response = try {
            executeSignedPost(OPERATION_SNAPSHOT_PATH, request, bindLifecycle = false)
        } catch (failure: HttpStatusException) {
            if (failure.statusCode == 404 || failure.statusCode == 405) return@withContext null
            throw failure
        }
        val payload = json.parseToJsonElement(response.decodeToString()).jsonObject
        decodeOperationalRecoveryBatch(
            json,
            payload,
            snapshot,
            operationIds,
            nonce,
            sha256Hex(signatureVerifier.canonicalJson(proof)),
            System.currentTimeMillis(),
            signatureVerifier::verifyDetached,
        )
            .map { it.toApi() }
    }

    override suspend fun lifecycleOperation(operationId: String): LifecycleOperation = withContext(Dispatchers.IO) {
        require(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        json.decodeFromString(
            LifecycleOperationWire.serializer(),
            executeSignedGet("$LIFECYCLE_OPERATIONS_PATH/$operationId", operationId).decodeToString(),
        ).toApi()
    }

    override suspend fun authorizePreparedRelayRetry(
        query: io.element.android.security.dataprotection.api.ProtectedEventOriginQuery, operationId: String, roomId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val operation = currentLifecycleOperationContext()
        require(operation?.lifecycleId == FullAppLifecycleId.LC_15 && operation.operationId == operationId)
        require(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        val request = kotlinx.serialization.json.buildJsonObject {
            put("account_id_hash", query.accountIdHash)
            put("matrix_device_id_hash", query.matrixDeviceIdHash)
            put("room_id_hash", query.roomIdHash)
            put("event_id_hash", query.eventIdHash)
            put("carrier_sha256", query.carrierSha256)
            put("immutable_context_sha256", query.immutableContextSha256)
            put("content_id", query.contentId)
            put("content_kind", query.contentKind)
            put("kms_reference", query.kmsReference)
            put("generation", query.generation?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("operation_id", operationId)
            put("room_id", roomId)
            put(
                "request_nonce",
                java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "")
            )
        }
        val payload = json.parseToJsonElement(executeSignedPost(
            "/v1/lifecycle/protected-relay-retry",
            request.toString().encodeToByteArray(),
            bindLifecycle = false,
        ).decodeToString()).jsonObject
        check(signatureVerifier.verifyDetached(
            JsonObject(payload.filterKeys { it != "signature_b64" }),
            payload.getValue("signing_key_id").jsonPrimitive.content,
            payload.getValue("signature_b64").jsonPrimitive.content
        ))
        check(
            payload.getValue("schema_version").jsonPrimitive.content == "1" &&
            payload.getValue("purpose").jsonPrimitive.content == "PROTECTED_RELAY_RETRY_AUTHORIZATION"
        )
        check(payload.getValue("query") == request && payload.getValue("authorized").jsonPrimitive.content == "true")
        check(payload.getValue("installation_id").jsonPrimitive.content == deviceTrustService.current().installationId)
        val issued = payload.getValue("issued_at_ms").jsonPrimitive.content.toLong()
        val expires = payload.getValue("expires_at_ms").jsonPrimitive.content.toLong()
        val now = System.currentTimeMillis()
        check(issued <= now + 5_000 && expires > now && expires > issued && expires - issued <= 15_000)
    }

    override suspend fun reconcileRelayPreparation(
        query: io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery,
    ): LifecycleOperation = withContext(Dispatchers.IO) {
        val context = currentLifecycleOperationContext()
        require(context?.lifecycleId == FullAppLifecycleId.LC_15 && context.operationId == query.operationId)
        val request = kotlinx.serialization.json.buildJsonObject {
            put("account_id_hash", query.accountIdHash)
            put("matrix_device_id_hash", query.matrixDeviceIdHash)
            put("room_id_hash", query.roomIdHash)
            put("event_id_hash", query.eventIdHash)
            put("operation_id", query.operationId)
            put("room_id", query.roomId)
            put("content_id", query.contentId)
            put(
                "request_nonce",
                java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "")
            )
        }
        val payload = json.parseToJsonElement(executeSignedPost(
            "/v1/lifecycle/protected-relay-preparation",
            request.toString().encodeToByteArray(),
            bindLifecycle = false,
        ).decodeToString()).jsonObject
        check(signatureVerifier.verifyDetached(
            JsonObject(payload.filterKeys { it != "signature_b64" }),
            payload.getValue("signing_key_id").jsonPrimitive.content,
            payload.getValue("signature_b64").jsonPrimitive.content
        ))
        check(
            payload.getValue("schema_version").jsonPrimitive.content == "1" &&
            payload.getValue("purpose").jsonPrimitive.content == "PROTECTED_RELAY_PREPARATION_RECONCILIATION"
        )
        check(payload.getValue("query") == request)
        val installation = deviceTrustService.current().installationId
        check(payload.getValue("installation_id").jsonPrimitive.content == installation)
        val issued = payload.getValue("issued_at_ms").jsonPrimitive.content.toLong()
        val expires = payload.getValue("expires_at_ms").jsonPrimitive.content.toLong()
        val now = System.currentTimeMillis()
        check(issued <= now + 5_000 && expires > now && expires > issued && expires - issued <= 15_000)
        val result = json.decodeFromJsonElement(LifecycleOperationWire.serializer(), payload.getValue("operation")).toApi()
        check(
            result.operationId == query.operationId && result.lifecycleId == FullAppLifecycleId.LC_15 &&
            result.subjects.installationId == installation && result.subjects.accountIdHash == query.accountIdHash &&
            result.subjects.matrixDeviceIdHash == query.matrixDeviceIdHash && result.subjects.roomIdHash == query.roomIdHash &&
            result.subjects.eventIdHash == query.eventIdHash
        )
        check(result.status.isTerminal == !result.terminalReceiptId.isNullOrBlank())
        val required = result.steps.filter { it.required }
        check(required.size == 6 && required.map { it.component }.toSet() == setOf(
            LifecycleComponent.ANDROID_FRONTEND,
            LifecycleComponent.ANDROID_SERVICE,
            LifecycleComponent.CONTROL_PLANE,
            LifecycleComponent.DATABASE,
            LifecycleComponent.MATRIX,
            LifecycleComponent.KMS,
        ))
        result
    }

    override suspend fun protectedEventOrigin(
        query: io.element.android.security.dataprotection.api.ProtectedEventOriginQuery,
    ): io.element.android.security.dataprotection.api.ProtectedEventOrigin? = withContext(Dispatchers.IO) {
        val request = kotlinx.serialization.json.buildJsonObject {
            put("account_id_hash", query.accountIdHash)
            put("matrix_device_id_hash", query.matrixDeviceIdHash)
            put("room_id_hash", query.roomIdHash)
            put("event_id_hash", query.eventIdHash)
            put("carrier_sha256", query.carrierSha256)
            put("immutable_context_sha256", query.immutableContextSha256)
            put("content_id", query.contentId)
            put("content_kind", query.contentKind)
            put("kms_reference", query.kmsReference)
            put("generation", query.generation?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        val payload = json.parseToJsonElement(executeSignedPost(
            "/v1/lifecycle/protected-event-origin",
            request.toString().encodeToByteArray(),
            bindLifecycle = false,
        ).decodeToString()).jsonObject
        val signedPayload = JsonObject(payload.filterKeys { it != "signature_b64" })
        check(signatureVerifier.verifyDetached(
            signedPayload,
            payload.getValue("signing_key_id").jsonPrimitive.content,
            payload.getValue("signature_b64").jsonPrimitive.content
        )) { "Protected origin signature was rejected." }
        check(
            payload.getValue("schema_version").jsonPrimitive.content == "1" &&
            payload.getValue("purpose").jsonPrimitive.content == "PROTECTED_SEND_ORIGIN_LOOKUP"
        )
        check(payload.getValue("query") == request) { "Protected origin query identity changed." }
        check(payload.getValue("installation_id").jsonPrimitive.content == deviceTrustService.current().installationId)
        val issued = payload.getValue("issued_at_ms").jsonPrimitive.content.toLong()
        val expires = payload.getValue("expires_at_ms").jsonPrimitive.content.toLong()
        val now = System.currentTimeMillis()
        check(issued <= now + 5_000 && expires > now && expires > issued && expires - issued <= 60_000)
        val raw = payload.getValue("origin")
        if (raw == kotlinx.serialization.json.JsonNull) return@withContext null
        val origin = raw.jsonObject
        val resolution = origin["scope_resolution"]
        check(resolution == null || resolution.jsonPrimitive.content in setOf("LEGACY_INSTALLATION_AND_AUTHORITY_FACTS", "PREVIOUS_MATRIX_SESSION")) {
            "Protected origin scope resolution was rejected."
        }
        val previousMatrixDevice = origin["producer_matrix_device_id_hash"]?.jsonPrimitive?.content
        if (resolution?.jsonPrimitive?.content == "PREVIOUS_MATRIX_SESSION") {
            check(previousMatrixDevice?.matches(Regex("[0-9a-f]{64}")) == true && previousMatrixDevice != query.matrixDeviceIdHash) {
                "Historical producer must identify a different Matrix session."
            }
        } else {
            check(previousMatrixDevice == null) { "Unexpected historical producer identity." }
        }
        val operationId = origin.getValue("operation_id").jsonPrimitive.content
        check(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        val lifecycleId = FullAppLifecycleId.entries.single { it.wireValue == origin.getValue("lifecycle_id").jsonPrimitive.content }
        check(
            lifecycleId == FullAppLifecycleId.LC_13 || lifecycleId == FullAppLifecycleId.LC_15 ||
            lifecycleId == FullAppLifecycleId.LC_17 && query.contentKind in setOf("LOCATION", "LIVE_LOCATION", "LIVE_LOCATION_OPERATION")
        )
        io.element.android.security.dataprotection.api.ProtectedEventOrigin(operationId, lifecycleId, previousMatrixDevice)
    }

    override suspend fun resumeLifecycleOperation(operationId: String): LifecycleOperation = withContext(Dispatchers.IO) {
        require(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        val body = json.encodeToString(LifecycleOperationResumeWire(operationId = operationId)).encodeToByteArray()
        json.decodeFromString(
            LifecycleOperationWire.serializer(),
            executeSignedPost("$LIFECYCLE_OPERATIONS_PATH/$operationId/resume", body, operationId).decodeToString(),
        ).toApi()
    }

    override suspend fun recordLifecycleClientReceipt(
        receipt: LifecycleClientReceipt,
    ): LifecycleOperation = withContext(Dispatchers.IO) {
        // An older authority must reject an unsupported failure endpoint rather than accidentally
        // treating a local recovery rejection as an APPLIED success receipt.
        val receiptRoute = if (receipt.resultCode in setOf("RECOVERY_SECRET_STORAGE_REJECTED", "MATRIX_AUTHENTICATION_REJECTED")) {
            "client-failures"
        } else {
            "client-receipts"
        }
        val body = json.encodeToString(
            LifecycleClientReceiptWire(
                operationId = receipt.operationId,
                component = receipt.component.name,
                phase = receipt.phase.name,
                resultCode = receipt.resultCode,
                receiptReference = receipt.receiptReference,
                occurredAtEpochMillis = receipt.occurredAtEpochMillis,
            )
        ).encodeToByteArray()
        json.decodeFromString(
            LifecycleOperationWire.serializer(),
            executeSignedPost(
                "$LIFECYCLE_OPERATIONS_PATH/${receipt.operationId}/$receiptRoute",
                body,
                receipt.operationId,
            ).decodeToString(),
        ).toApi()
    }

    override suspend fun recordMediaObjectCleanup(
        fact: io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup,
    ): String = withContext(Dispatchers.IO) {
        val installation = deviceTrustService.current().installationId
        check(fact.subjects.installationId == installation) { "Media cleanup belongs to another installation." }
        val request = mediaCleanupRequest(fact)
        // This is a past cleanup fact, not authorization to resume the original (possibly terminal) operation.
        val payload = json.parseToJsonElement(executeSignedPost(
            "/v1/lifecycle/media-object-cleanup",
            request.toString().encodeToByteArray(),
            bindLifecycle = false,
        ).decodeToString()).jsonObject
        requireMediaCleanupAcknowledgement(request, payload, signatureVerifier)
    }

    override suspend fun reserveMediaResources(request: LifecycleMediaReservation) {
        sendMediaResourceFact(
            "/v1/lifecycle/media-resources/reserve",
            request.subjects.installationId,
            mediaResourceRequest(request),
            "MEDIA_RESOURCE_RESERVATION_ACK"
        )
    }

    override suspend fun sealMediaResources(request: LifecycleMediaSeal): String = checkNotNull(
        sendMediaResourceFact(
            "/v1/lifecycle/media-resources/seal",
            request.reservation.subjects.installationId,
            mediaSealRequest(request),
            "MEDIA_RESOURCE_SEAL_ACK"
        )
    )

    override suspend fun recordMediaRuntimeCleanup(fact: LifecycleMediaRuntimeCleanup): String = checkNotNull(
        sendMediaResourceFact(
            "/v1/lifecycle/media-runtime-cleanup",
            fact.subjects.installationId,
            mediaRuntimeRequest(fact),
            "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK"
        )
    )

    private suspend fun sendMediaResourceFact(path: String, installationId: String?, request: JsonObject, purpose: String): String? =
        withContext(Dispatchers.IO) {
            try {
                check(installationId == deviceTrustService.current().installationId) { "Media resource belongs to another installation." }
                val response = try {
                    executeSignedPost(path, request.toString().encodeToByteArray(), bindLifecycle = false)
                } catch (failure: HttpStatusException) {
                    throw MediaClosureDeliveryFailure(purpose, "SERVER_REJECTED", failure.statusCode)
                }
                val payload = try {
                    json.parseToJsonElement(response.decodeToString()).jsonObject
                } catch (_: Exception) {
                    throw MediaClosureDeliveryFailure(purpose, "ACK_FORMAT_REJECTED")
                }
                try {
                    requireMediaResourceAcknowledgement(request, payload, signatureVerifier, purpose)
                } catch (_: Exception) {
                    throw MediaClosureDeliveryFailure(purpose, "ACK_VERIFICATION_REJECTED")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: MediaClosureDeliveryFailure) {
                throw failure
            } catch (failure: Exception) {
                throw MediaClosureDeliveryFailure(purpose, managementSyncFailureReasonCode(failure) ?: "CLIENT_SCOPE_OR_SIGNING_REJECTED")
            }
        }

    override suspend fun heartbeat(): ManagementHeartbeatReport = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) {
            return@withContext ManagementHeartbeatReport(ManagementHeartbeatStatus.DISABLED)
        }
        val ticket = accessTicket
            ?: return@withContext ManagementHeartbeatReport(ManagementHeartbeatStatus.REAUTH_REQUIRED)
        if (ticket.expiresAtEpochMillis <= System.currentTimeMillis()) {
            accessTicket = null
            return@withContext ManagementHeartbeatReport(ManagementHeartbeatStatus.REAUTH_REQUIRED)
        }
        runCatchingExceptions {
            val body = json.encodeToString(DeviceHeartbeatPulseWire(ticketId = ticket.ticketId)).encodeToByteArray()
            val envelope = json.decodeFromString(
                SignedJsonEnvelopeWire.serializer(),
                executeSignedPost(HEARTBEAT_PATH, body).decodeToString(),
            )
            check(signatureVerifier.verify(envelope)) { "Device heartbeat receipt signature verification failed." }
            val receipt = json.decodeFromJsonElement(DeviceHeartbeatReceiptWire.serializer(), envelope.payload())
            val trust = deviceTrustService.current()
            val validated = Validators.validateHeartbeatReceipt(ticket, receipt, trust.installationId)
            val report = validated.withPolicyRefreshStatus(repository.currentPolicyVersion())
            report.also {
                if (report.status == ManagementHeartbeatStatus.REAUTH_REQUIRED || report.status == ManagementHeartbeatStatus.REVOKED) {
                    accessTicket = null
                }
            }
        }.getOrElse { failure ->
            val securityFailure = failure is SecurityException || failure is IllegalStateException
            ManagementHeartbeatReport(
                status = if (securityFailure) {
                    accessTicket = null
                    ManagementHeartbeatStatus.SECURITY_REJECTED
                } else {
                    ManagementHeartbeatStatus.RETRY_REQUIRED
                },
                nextHeartbeatDelayMillis = (ticket.heartbeatIntervalMillis / 2)
                    .coerceIn(MIN_HEARTBEAT_INTERVAL_MILLIS, MAX_HEARTBEAT_INTERVAL_MILLIS),
            )
        }
    }

    override suspend fun submitDeviceLocation(evidence: DeviceLocationEvidence): DeviceLocationEvidenceState = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(evidence.latitude in -90.0..90.0)
        require(evidence.longitude in -180.0..180.0)
        require(evidence.accuracyMeters > 0f)
        require(evidence.provider.matches(Regex("[A-Za-z0-9._-]{1,32}")))
        val body = json.encodeToString(
            DeviceLocationEvidenceWire(
                latitude = evidence.latitude,
                longitude = evidence.longitude,
                accuracyMeters = evidence.accuracyMeters,
                capturedAtEpochMillis = evidence.capturedAtEpochMillis,
                provider = evidence.provider,
                isMock = evidence.isMock,
                timezoneOffsetMinutes = evidence.timezoneOffsetMinutes,
            )
        ).encodeToByteArray()
        val response = json.decodeFromString(
            DeviceLocationEvidenceResponseWire.serializer(),
            executeSignedPost(LOCATION_EVIDENCE_PATH, body).decodeToString(),
        )
        DeviceLocationEvidenceState.valueOf(response.locationEvidenceState)
    }

    override suspend fun createOfficePreviewGrant(request: OfficePreviewGrantRequest): SignedOfficePreviewGrant = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        val body = json.encodeToString(
            OfficePreviewGrantRequestWire(
                accountIdHash = request.accountIdHash,
                fileSha256 = request.fileSha256,
                fileSizeBytes = request.fileSizeBytes,
                fileExtension = request.fileExtension,
            )
        ).encodeToByteArray()
        val lifecycleOperationId = checkNotNull(resolveLifecycleOperationId("POST", OFFICE_PREVIEW_GRANT_PATH)) {
            "Office preview requires a lifecycle operation identity."
        }
        val responseText = executeSignedPost(
            path = OFFICE_PREVIEW_GRANT_PATH,
            body = body,
            lifecycleOperationId = lifecycleOperationId,
        ).decodeToString()
        val envelope = json.decodeFromString(SignedJsonEnvelopeWire.serializer(), responseText)
        check(signatureVerifier.verify(envelope)) { "Office preview grant signature verification failed." }
        val grant = json.decodeFromJsonElement(OfficePreviewGrantWire.serializer(), envelope.payload())
        val trust = deviceTrustService.current()
        check(grant.schemaVersion == 1)
        check(grant.purpose == "office-preview-upload")
        check(grant.operationId == lifecycleOperationId)
        check(grant.installationId == trust.installationId)
        check(grant.accountIdHash == request.accountIdHash)
        check(grant.fileSha256 == request.fileSha256)
        check(grant.fileSizeBytes == request.fileSizeBytes)
        check(grant.fileExtension == request.fileExtension)
        check(grant.contentScope == "ORDINARY_CHAT_ATTACHMENT")
        check(grant.grantId.matches(Regex("[A-Za-z0-9_-]{24,128}")))
        val current = System.currentTimeMillis()
        check(grant.issuedAtEpochMillis <= current + MAX_CLOCK_SKEW_MILLIS)
        check(grant.expiresAtEpochMillis > current)
        check(grant.expiresAtEpochMillis <= grant.issuedAtEpochMillis + MAX_OFFICE_PREVIEW_GRANT_MILLIS)
        check(grant.previewExpiresAtEpochMillis > grant.expiresAtEpochMillis)
        check(grant.previewExpiresAtEpochMillis <= grant.issuedAtEpochMillis + MAX_OFFICE_PREVIEW_SESSION_MILLIS)
        SignedOfficePreviewGrant(
            envelopeJson = responseText,
            expiresAtEpochMillis = grant.expiresAtEpochMillis,
            previewExpiresAtEpochMillis = grant.previewExpiresAtEpochMillis,
        )
    }

    override suspend fun prepareAccountReactivation(
        request: AccountReactivationRequest,
    ): PreparedAccountReactivation = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        validateAccountReactivationRequest(request)
        val body = json.encodeToString(
            AccountReactivationPrepareWire(
                accountIdHash = request.accountIdHash,
                matrixDeviceIdHash = request.matrixDeviceIdHash,
                clientScopeId = request.clientScopeId,
                clientGeneration = request.clientGeneration,
                scopeStartedAtEpochMillis = request.scopeStartedAtEpochMillis,
                idempotencyKey = request.idempotencyKey,
                explicitIdentityResetConfirmed = request.explicitIdentityResetConfirmed,
            )
        ).encodeToByteArray()
        val response = json.decodeFromString(
            AccountReactivationResponseWire.serializer(),
            executeSignedPost(ACCOUNT_REACTIVATION_PREPARE_PATH, body).decodeToString(),
        )
        check(response.state in setOf("PREPARED", "COMMITTED"))
        check(response.activationId.matches(ACTIVATION_ID_PATTERN))
        check(response.expiresAtEpochMillis > 0)
        PreparedAccountReactivation(response.activationId, response.expiresAtEpochMillis)
    }

    override suspend fun commitAccountReactivation(
        request: AccountReactivationRequest,
        prepared: PreparedAccountReactivation,
    ): AccountActivationReceipt = withContext(Dispatchers.IO) {
        validateAccountReactivationRequest(request)
        check(prepared.activationId.matches(ACTIVATION_ID_PATTERN))
        // A lost COMMIT response may be recovered after PREPARE's deadline.
        // Only the authority can distinguish COMMITTED from an expired attempt.
        val body = json.encodeToString(
            AccountReactivationCommitWire(
                activationId = prepared.activationId,
                accountIdHash = request.accountIdHash,
            )
        ).encodeToByteArray()
        val response = json.decodeFromString(
            AccountReactivationResponseWire.serializer(),
            try {
                executeSignedPost(ACCOUNT_REACTIVATION_COMMIT_PATH, body).decodeToString()
            } catch (failure: HttpStatusException) {
                if (failure.statusCode == 410 && failure.safeDetail == "reactivation transaction expired") {
                    throw io.element.android.security.dataprotection.api.AccountActivationExpired()
                }
                throw failure
            },
        )
        check(response.state == "COMMITTED" && response.activationId == prepared.activationId)
        val envelope = SignedJsonEnvelopeWire(
            receipt = checkNotNull(response.receipt),
            keyId = checkNotNull(response.keyId),
            signatureBase64 = checkNotNull(response.signatureBase64),
        )
        val receipt = repository.acceptActivationReceipt(envelope)
        val trust = deviceTrustService.current()
        val shareKey = deviceShareKeyService.descriptor()
        check(receipt.activationId == prepared.activationId)
        check(receipt.accountIdHash == request.accountIdHash)
        check(receipt.installationId == trust.installationId)
        check(receipt.matrixDeviceIdHash == request.matrixDeviceIdHash)
        check(receipt.clientScopeId == request.clientScopeId)
        check(receipt.clientGeneration == request.clientGeneration)
        check(receipt.attestationVersion == trust.attestationVersion)
        check(receipt.policyVersion == repository.currentPolicyVersion())
        check(receipt.recipientKeyId == shareKey.keyId && shareKey.installationId == trust.installationId)
        check(receipt.matrixCrossSigningKeyHash.matches(ACCOUNT_HASH_PATTERN))
        if (receipt.schemaVersion == 3) check((receipt.accountMobileGeneration ?: 0) > 0)
        val identityContinuity = io.element.android.security.dataprotection.api.AccountIdentityContinuity.valueOf(
            receipt.identityContinuity
        )
        check(
            request.explicitIdentityResetConfirmed ==
                (identityContinuity == io.element.android.security.dataprotection.api.AccountIdentityContinuity.RESET_CONFIRMED)
        )
        check(receipt.committedAtEpochMillis in 1..System.currentTimeMillis() + MAX_CLOCK_SKEW_MILLIS)
        AccountActivationReceipt(
            activationId = receipt.activationId,
            accountIdHash = receipt.accountIdHash,
            installationId = receipt.installationId,
            matrixDeviceIdHash = receipt.matrixDeviceIdHash,
            clientScopeId = receipt.clientScopeId,
            clientGeneration = receipt.clientGeneration,
            attestationVersion = receipt.attestationVersion,
            policyVersion = receipt.policyVersion,
            matrixCrossSigningKeyHash = receipt.matrixCrossSigningKeyHash,
            recipientKeyId = receipt.recipientKeyId,
            identityContinuity = identityContinuity,
            accountMobileGeneration = receipt.accountMobileGeneration ?: 0,
            committedAtEpochMillis = receipt.committedAtEpochMillis,
        )
    }

    override suspend fun preparePermanentAccountDeletion(
        request: AccountDeletionPreparationRequest,
    ): PreparedAccountDeletion = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(request.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
        require(request.idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN))
        require(request.confirmationTokenSha256.matches(ACCOUNT_HASH_PATTERN))
        val body = json.encodeToString(
            AccountDeletionPrepareWire(
                accountIdHash = request.accountIdHash,
                idempotencyKey = request.idempotencyKey,
                confirmationTokenSha256 = request.confirmationTokenSha256,
                eraseData = request.eraseData,
            )
        ).encodeToByteArray()
        val response = json.decodeFromString(
            AccountDeletionResponseWire.serializer(),
            executeSignedPost(ACCOUNT_DELETION_PREPARE_PATH, body).decodeToString(),
        )
        check(response.state == "PREPARED")
        check(response.accountState == "FROZEN")
        check(response.irreversible == true)
        val envelope = SignedJsonEnvelopeWire(
            receipt = response.receipt,
            keyId = response.keyId,
            signatureBase64 = response.signatureBase64,
        )
        check(signatureVerifier.verify(envelope)) { "Account deletion prepare receipt signature verification failed." }
        val receipt = json.decodeFromJsonElement(AccountDeletionPrepareReceiptWire.serializer(), response.receipt)
        val trust = deviceTrustService.current()
        val current = System.currentTimeMillis()
        check(receipt.schemaVersion == 1 && receipt.purpose == "PERMANENT_ACCOUNT_DELETION_PREPARE")
        check(receipt.contractRevision == "lc22-v2")
        check(receipt.operationId == currentLifecycleOperationContext()?.operationId)
        check(receipt.deletionIntentId.matches(DELETION_ID_PATTERN))
        check(receipt.accountIdHash == request.accountIdHash)
        check(receipt.installationId == trust.installationId)
        check(receipt.eraseData == request.eraseData)
        check(receipt.preparedAtEpochMillis in 1..current + MAX_CLOCK_SKEW_MILLIS)
        check(receipt.freezeAtEpochMillis == receipt.preparedAtEpochMillis)
        if (receipt.expiresAtEpochMillis <= current) {
            throw PermanentAccountDeletionPreparationExpiredException()
        }
        check(receipt.expiresAtEpochMillis <= receipt.preparedAtEpochMillis + MAX_ACCOUNT_DELETION_PREPARE_MILLIS)
        PreparedAccountDeletion(
            deletionIntentId = receipt.deletionIntentId,
            accountIdHash = receipt.accountIdHash,
            installationId = receipt.installationId,
            eraseData = receipt.eraseData,
            preparedAtEpochMillis = receipt.preparedAtEpochMillis,
            expiresAtEpochMillis = receipt.expiresAtEpochMillis,
        )
    }

    override suspend fun pendingPermanentAccountDeletion(
        accountIdHash: String,
    ): RecoverableAccountDeletion? = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(accountIdHash.matches(ACCOUNT_HASH_PATTERN))
        val body = json.encodeToString(
            AccountDeletionPendingWire(accountIdHash = accountIdHash)
        ).encodeToByteArray()
        val responseBytes = try {
            // Discovery must remain outside lifecycle binding. During a recovery attempt the caller
            // already carries the frozen LC-22 context, but /pending is intentionally not a lifecycle
            // mutation route and the server rejects a lifecycle header on it.
            executeSignedPost(ACCOUNT_DELETION_PENDING_PATH, body, bindLifecycle = false)
        } catch (failure: HttpStatusException) {
            if (failure.statusCode == 404) return@withContext null
            throw failure
        }
        val response = json.decodeFromString(AccountDeletionResponseWire.serializer(), responseBytes.decodeToString())
        check(response.state == "PREPARED")
        check(response.accountState == "FROZEN")
        check(response.irreversible == true)
        val envelope = SignedJsonEnvelopeWire(
            receipt = response.receipt,
            keyId = response.keyId,
            signatureBase64 = response.signatureBase64,
        )
        check(signatureVerifier.verify(envelope)) { "Account deletion pending receipt signature verification failed." }
        val receipt = json.decodeFromJsonElement(AccountDeletionPendingReceiptWire.serializer(), response.receipt)
        val trust = deviceTrustService.current()
        check(receipt.schemaVersion == 1 && receipt.purpose == "PERMANENT_ACCOUNT_DELETION_PENDING")
        check(receipt.contractRevision == "lc22-v2")
        check(receipt.operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        check(receipt.deletionIntentId.matches(DELETION_ID_PATTERN))
        check(receipt.accountIdHash == accountIdHash)
        check(receipt.installationId == trust.installationId)
        check(receipt.eraseData)
        check(receipt.freezeAtEpochMillis in 1..System.currentTimeMillis() + MAX_CLOCK_SKEW_MILLIS)
        RecoverableAccountDeletion(
            operationId = receipt.operationId,
            deletionIntentId = receipt.deletionIntentId,
            accountIdHash = receipt.accountIdHash,
            installationId = receipt.installationId,
            eraseData = receipt.eraseData,
            freezeAtEpochMillis = receipt.freezeAtEpochMillis,
        )
    }

    override suspend fun recoverPermanentAccountDeletion(
        request: AccountDeletionRecoveryRequest,
    ): PreparedAccountDeletion = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(request.operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        require(request.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
        require(request.idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN))
        require(request.confirmationTokenSha256.matches(ACCOUNT_HASH_PATTERN))
        check(currentLifecycleOperationContext()?.operationId == request.operationId)
        check(currentLifecycleOperationContext()?.lifecycleId == FullAppLifecycleId.LC_22)
        val body = json.encodeToString(
            AccountDeletionRecoverWire(
                operationId = request.operationId,
                accountIdHash = request.accountIdHash,
                idempotencyKey = request.idempotencyKey,
                confirmationTokenSha256 = request.confirmationTokenSha256,
                eraseData = request.eraseData,
            )
        ).encodeToByteArray()
        val response = json.decodeFromString(
            AccountDeletionResponseWire.serializer(),
            executeSignedPost(ACCOUNT_DELETION_RECOVER_PATH, body, request.operationId).decodeToString(),
        )
        check(response.state == "PREPARED")
        check(response.accountState == "FROZEN")
        check(response.irreversible == true)
        val envelope = SignedJsonEnvelopeWire(
            receipt = response.receipt,
            keyId = response.keyId,
            signatureBase64 = response.signatureBase64,
        )
        check(signatureVerifier.verify(envelope)) { "Account deletion recovery receipt signature verification failed." }
        val receipt = json.decodeFromJsonElement(AccountDeletionRecoveryReceiptWire.serializer(), response.receipt)
        val trust = deviceTrustService.current()
        val current = System.currentTimeMillis()
        check(receipt.schemaVersion == 1 && receipt.purpose == "PERMANENT_ACCOUNT_DELETION_RECOVERY")
        check(receipt.contractRevision == "lc22-v2")
        check(receipt.operationId == request.operationId)
        check(receipt.deletionIntentId.matches(DELETION_ID_PATTERN))
        check(receipt.accountIdHash == request.accountIdHash)
        check(receipt.installationId == trust.installationId)
        check(receipt.eraseData == request.eraseData)
        check(receipt.freezeAtEpochMillis in 1..receipt.recoveredAtEpochMillis)
        check(receipt.recoveredAtEpochMillis in 1..current + MAX_CLOCK_SKEW_MILLIS)
        if (receipt.expiresAtEpochMillis <= current) throw PermanentAccountDeletionPreparationExpiredException()
        check(receipt.expiresAtEpochMillis <= receipt.recoveredAtEpochMillis + MAX_ACCOUNT_DELETION_PREPARE_MILLIS)
        PreparedAccountDeletion(
            deletionIntentId = receipt.deletionIntentId,
            accountIdHash = receipt.accountIdHash,
            installationId = receipt.installationId,
            eraseData = receipt.eraseData,
            preparedAtEpochMillis = receipt.recoveredAtEpochMillis,
            expiresAtEpochMillis = receipt.expiresAtEpochMillis,
        )
    }

    override suspend fun commitPermanentAccountDeletion(
        prepared: PreparedAccountDeletion,
        confirmationToken: String,
    ): AccountDeletionReceipt = withContext(Dispatchers.IO) {
        require(prepared.deletionIntentId.matches(DELETION_ID_PATTERN))
        require(prepared.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
        require(confirmationToken.matches(CONFIRMATION_TOKEN_PATTERN))
        val trust = deviceTrustService.current()
        check(prepared.installationId == trust.installationId)
        val body = json.encodeToString(
            AccountDeletionCommitWire(
                deletionIntentId = prepared.deletionIntentId,
                accountIdHash = prepared.accountIdHash,
                confirmationToken = confirmationToken,
            )
        ).encodeToByteArray()
        val responseBytes = try {
            executeSignedPost(ACCOUNT_DELETION_COMMIT_PATH, body)
        } catch (failure: HttpStatusException) {
            if (failure.statusCode == 409 && failure.safeDetail == MATRIX_NOT_DEACTIVATED_DETAIL) {
                throw PermanentAccountDeletionAwaitingMatrixException()
            }
            if (failure.statusCode == 409 && failure.safeDetail == GLOBAL_DESTROY_NOT_TERMINAL_DETAIL) {
                throw PermanentAccountDeletionAwaitingGlobalDestroyException()
            }
            if (failure.statusCode == 410 && failure.safeDetail == DELETION_PREPARATION_EXPIRED_DETAIL) {
                throw PermanentAccountDeletionPreparationExpiredException()
            }
            throw failure
        }
        val response = json.decodeFromString(AccountDeletionResponseWire.serializer(), responseBytes.decodeToString())
        check(response.state == "COMMITTED")
        val envelope = SignedJsonEnvelopeWire(
            receipt = response.receipt,
            keyId = response.keyId,
            signatureBase64 = response.signatureBase64,
        )
        check(signatureVerifier.verify(envelope)) { "Account deletion commit receipt signature verification failed." }
        val receipt = json.decodeFromJsonElement(AccountDeletionCommitReceiptWire.serializer(), response.receipt)
        check(receipt.schemaVersion == 1 && receipt.purpose == "PERMANENT_ACCOUNT_DELETION_COMMIT")
        check(receipt.contractRevision == "lc22-v2")
        check(receipt.operationId == currentLifecycleOperationContext()?.operationId)
        check(receipt.deletionIntentId == prepared.deletionIntentId)
        check(receipt.accountIdHash == prepared.accountIdHash)
        check(receipt.installationId == prepared.installationId)
        check(receipt.eraseData == prepared.eraseData)
        check(receipt.state == "DELETION_IN_PROGRESS")
        check(receipt.globalDestroyJobId.matches(DELETION_ID_PATTERN))
        check(receipt.committedAtEpochMillis in 1..System.currentTimeMillis() + MAX_CLOCK_SKEW_MILLIS)
        AccountDeletionReceipt(
            deletionIntentId = receipt.deletionIntentId,
            accountIdHash = receipt.accountIdHash,
            installationId = receipt.installationId,
            globalDestroyJobId = receipt.globalDestroyJobId,
            eraseData = receipt.eraseData,
            committedAtEpochMillis = receipt.committedAtEpochMillis,
        )
    }

    override suspend fun permanentAccountDeletionStatus(
        operationId: String,
    ): PermanentAccountDeletionStatus = withContext(Dispatchers.IO) {
        require(operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        val path = "$ACCOUNT_DELETION_OPERATIONS_PATH/$operationId"
        val wire = json.decodeFromString(
            AccountDeletionStatusWire.serializer(),
            executeSignedGet(path, operationId).decodeToString(),
        )
        wire.toApi(operationId)
    }

    override suspend fun recordPermanentAccountDeletionClientReceipt(
        receipt: PermanentAccountDeletionClientReceipt,
    ): PermanentAccountDeletionClientReceiptResult = withContext(Dispatchers.IO) {
        require(receipt.operationId.matches(LIFECYCLE_OPERATION_ID_PATTERN))
        require(receipt.installationId.length in 16..256)
        require(receipt.resourceScopeHash.matches(ACCOUNT_HASH_PATTERN))
        require(receipt.manifestDigestSha256.matches(ACCOUNT_HASH_PATTERN))
        require(receipt.attestationEvidenceHash.matches(ACCOUNT_HASH_PATTERN))
        require(receipt.occurredAtEpochMillis > 0)
        require(receipt.deletionScopedToken.length in 32..4096)
        val unsignedPayload = canonicalDeletionClientReceiptPayload(receipt)
        val signature = deviceTrustService.signControlStatement(unsignedPayload).signature.toBase64()
        val body = json.encodeToString(
            AccountDeletionClientReceiptWire(
                operationId = receipt.operationId,
                installationId = receipt.installationId,
                resourceScopeHash = receipt.resourceScopeHash,
                manifestDigestSha256 = receipt.manifestDigestSha256,
                attestationEvidenceHash = receipt.attestationEvidenceHash,
                occurredAtEpochMillis = receipt.occurredAtEpochMillis,
                clientSignatureBase64 = signature,
            )
        ).encodeToByteArray()
        val path = "$ACCOUNT_DELETION_OPERATIONS_PATH/${receipt.operationId}/client-receipts"
        check(isDeletionScopedCapabilityUseAllowed("POST", path, receipt.operationId))
        val request = Request.Builder()
            .url(configuration.baseUrl + path)
            .header("Authorization", "Bearer ${receipt.deletionScopedToken}")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = json.decodeFromString(
            AccountDeletionClientReceiptResponseWire.serializer(),
            execute(request).decodeToString(),
        )
        check(response.accepted)
        PermanentAccountDeletionClientReceiptResult(
            accepted = response.accepted,
            idempotentReplay = response.idempotentReplay,
            status = response.status.toApi(receipt.operationId),
        )
    }

    private fun AccountDeletionStatusWire.toApi(expectedOperationId: String): PermanentAccountDeletionStatus =
        Validators.validateAccountDeletionStatus(
            wire = this,
            expectedOperationId = expectedOperationId,
            nowEpochMillis = System.currentTimeMillis(),
            verifyMilestone = { envelope -> signatureVerifier.verify(envelope) },
        )

    private fun validateAccountReactivationRequest(request: AccountReactivationRequest) {
        require(request.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
        require(request.matrixDeviceIdHash.matches(ACCOUNT_HASH_PATTERN))
        require(request.clientScopeId.matches(CLIENT_SCOPE_PATTERN))
        require(request.clientGeneration > 0)
        require(request.scopeStartedAtEpochMillis > 0)
        require(request.idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN))
    }

    /**
     * Emits only exception type names on debuggable builds. It deliberately excludes URLs, request bodies,
     * account identifiers, installation identifiers and exception messages so field diagnostics cannot create
     * another sensitive-data carrier in production or test logs.
     */
    private fun logDebugFailure(failure: Throwable) {
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val isInternalPackage = context.packageName.endsWith(".internal")
        if (!isDebuggable && !isInternalPackage) return
        val failureChain = generateSequence(failure) { it.cause }
            .take(MAX_DIAGNOSTIC_CAUSES)
            .joinToString(separator = ">") {
                if (it is HttpStatusException) {
                    "HTTP_${it.statusCode}_${it.safeDetail.orEmpty().replace(' ', '_')}"
                } else {
                    val origin = it.stackTrace.firstOrNull()
                    // Release builds obfuscate this class name. Keeping the full obfuscated name lets mapping.txt
                    // identify the rejected validator without logging request, identity, key, token or response data.
                    val location = origin?.let { frame -> "@${frame.className}:${frame.lineNumber}" }.orEmpty()
                    it.javaClass.simpleName.ifBlank { "AnonymousFailure" } + location
                }
            }
        Log.w(DIAGNOSTIC_TAG, "YZMGMT_SYNC_RETRY failure_chain=$failureChain")
    }

    override suspend fun pendingCommands(): List<VerifiedManagementCommand> = withContext(Dispatchers.IO) {
        repository.pendingCommands()
    }

    override suspend fun isMobileSessionSuperseded(accountIdHash: String, matrixDeviceIdHash: String): Boolean = withContext(Dispatchers.IO) {
        repository.isMobileSessionSuperseded(accountIdHash, matrixDeviceIdHash)
    }

    override suspend fun isOfflineDeadlineExceeded(): Boolean = withContext(Dispatchers.IO) {
        repository.isOfflineDeadlineExceeded()
    }

    override suspend fun cachedSignedPolicyVersion(): Long? = withContext(Dispatchers.IO) {
        repository.currentPolicyVersion()
    }

    override suspend fun offlineEntryStatus(): io.element.android.security.dataprotection.api.OfflineEntryStatus? = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) return@withContext null
        val (decision, remaining) = repository.cachedOfflineEntry() ?: return@withContext null
        val trust = deviceTrustService.current()
        if (!trust.allowsLocalVaultAccess || trust.trustState !in setOf(DeviceTrustState.TRUSTED, DeviceTrustState.MANAGED) ||
            trust.installationId != decision.installationId || trust.attestationVersion != decision.attestationVersion
        ) {
            return@withContext null
        }
        val grant = checkNotNull(decision.offlineEntry)
        if (trust.attestationPublicKeySha256 != grant.devicePublicKeySha256) return@withContext null
        if (deviceShareKeyService.descriptor().keyId != grant.contentShareKeyId) return@withContext null
        val sessions = sessionStore.getAllSessions()
        val bindings = sessions.map {
            MatrixDeviceBindingWire(accountIdHash(it.userId), sha256Hex(it.deviceId.encodeToByteArray()))
        }.toSet()
        if (bindings.isEmpty() || bindings != grant.accountBindings.map { MatrixDeviceBindingWire(it.accountIdHash, it.matrixDeviceIdHash) }.toSet() ||
            grant.accountBindings.any { it.accountMobileGeneration <= 0 || it.activationId.isBlank() }
        ) {
            return@withContext null
        }
        if (trust.trustState == DeviceTrustState.MANAGED && trust.managedLicense?.isActive(System.currentTimeMillis()) != true) {
            return@withContext null
        }
        io.element.android.security.dataprotection.api.OfflineEntryStatus(
            grant.policyVersion,
            grant.expiresAtEpochMillis,
            remaining,
            grant.accountBindings.map {
                io.element.android.security.dataprotection.api.OfflineEntryAccountBinding(
                it.accountIdHash,
                    it.matrixDeviceIdHash,
                    it.accountMobileGeneration,
                    it.activationId,
            )
            }
        )
    }

    private val localListDiagnosticBudget = java.util.concurrent.atomic.AtomicInteger(8)

    private fun localListUnavailable(reason: String): Nothing? {
        if (localListDiagnosticBudget.getAndDecrement() > 0) {
            timber.log.Timber.tag("YiZheStartup").i("[Startup] Startup.localList.service.unavailable reason=%s", reason)
        }
        return null
    }

    override suspend fun localListEntryStatus(): io.element.android.security.dataprotection.api.LocalChatListPermission? = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) return@withContext localListUnavailable("NOT_CONFIGURED")
        val (decision, remaining) = repository.cachedLocalListEntry() ?: return@withContext localListUnavailable("REPOSITORY_UNAVAILABLE")
        val trust = deviceTrustService.current()
        if (!trust.allowsLocalVaultAccess || trust.trustState !in setOf(DeviceTrustState.TRUSTED, DeviceTrustState.MANAGED) ||
            trust.installationId != decision.installationId || trust.attestationVersion != decision.attestationVersion
        ) {
            return@withContext localListUnavailable("TRUST_BINDING")
        }
        val grant = checkNotNull(decision.localListEntry)
        if (trust.attestationPublicKeySha256 != grant.devicePublicKeySha256) return@withContext localListUnavailable("DEVICE_KEY_BINDING")
        if (deviceShareKeyService.descriptor().keyId != grant.contentShareKeyId) return@withContext localListUnavailable("SHARE_KEY_BINDING")
        val sessions = sessionStore.getAllSessions()
        val bindings = sessions.map {
            MatrixDeviceBindingWire(accountIdHash(it.userId), sha256Hex(it.deviceId.encodeToByteArray()))
        }.toSet()
        if (bindings.isEmpty() || bindings != grant.accountBindings.map { MatrixDeviceBindingWire(it.accountIdHash, it.matrixDeviceIdHash) }.toSet() ||
            grant.accountBindings.any { it.accountMobileGeneration <= 0 || it.activationId.isBlank() }
        ) {
            return@withContext localListUnavailable("SESSION_BINDING")
        }
        if (trust.trustState == DeviceTrustState.MANAGED && trust.managedLicense?.isActive(System.currentTimeMillis()) != true) {
            return@withContext localListUnavailable("MANAGED_LICENSE")
        }
        io.element.android.security.dataprotection.api.LocalChatListPermission(
            grant.policyVersion,
            grant.entryPolicyVersion,
            remaining,
            grant.accountBindings.map {
                io.element.android.security.dataprotection.api.OfflineEntryAccountBinding(
                it.accountIdHash,
                    it.matrixDeviceIdHash,
                    it.accountMobileGeneration,
                    it.activationId,
            )
            }
        )
    }

    override suspend fun reportCommand(report: ManagementCommandExecutionReport): Boolean = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) return@withContext false
        val globalChild = if (report.commandType == ManagementCommandType.CLEAR_ACCOUNT) {
            repository.pendingCommands().singleOrNull { it.commandId == report.commandId }?.takeIf {
                it.type == ManagementCommandType.CLEAR_ACCOUNT && it.reasonCode == "GLOBAL_ACCOUNT_DESTROY" &&
                    it.globalDestroyOperationId != null
            }?.also { check(it.installationId == deviceTrustService.current().installationId) }
        } else {
            null
        }
        val expectedLifecycleId = when (report.commandType) {
            ManagementCommandType.LOCK -> FullAppLifecycleId.LC_09
            ManagementCommandType.REVOKE -> FullAppLifecycleId.LC_07
            ManagementCommandType.CLEAR_ACCOUNT -> FullAppLifecycleId.LC_22
            ManagementCommandType.CLEAR_CONTENT -> FullAppLifecycleId.LC_21
        }
        check(globalChild != null || currentLifecycleOperationContext()?.lifecycleId == expectedLifecycleId) {
            "Management command report requires its command-specific lifecycle context."
        }
        val body = json.encodeToString(
            CommandReportWire(
                commandId = report.commandId,
                state = report.state.name,
                progress = report.progress,
                errorCode = report.errorCode,
            )
        ).encodeToByteArray()
        runCatchingExceptions {
            // This exact verified child belongs to the server's original global root.
            // Its device signature reports local facts; it must not borrow an ambient
            // heartbeat operation or implicitly create a second destruction root.
            executeSignedPost(COMMAND_REPORT_PATH, body, bindLifecycle = globalChild == null)
            repository.recordCommandReport(report, removeWhenComplete = report.state == ManagementCommandState.COMPLETE)
            true
        }.getOrElse { failure ->
            logDebugFailure(failure)
            false
        }
    }

    override suspend fun createOnboardingInvitation(
        inviterUserId: String,
        generationRequestId: String?,
    ): OnboardingInvitation = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(inviterUserId.matches(MATRIX_USER_ID_PATTERN))
        generationRequestId?.let { require(it.matches(IDEMPOTENCY_KEY_PATTERN)) }
        val body = json.encodeToString(
            CreateOnboardingInvitationWire(inviterUserId, generationRequestId)
        ).encodeToByteArray()
        json.decodeFromString(
            OnboardingInvitationWire.serializer(),
            executeSignedPost(ONBOARDING_INVITATIONS_PATH, body).decodeToString(),
        ).toApi()
    }

    override suspend fun verifyOnboardingInvitation(
        publicId: String?,
        code: String?,
    ): VerifiedOnboardingInvitation = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require((publicId == null) xor (code == null))
        publicId?.let { require(it.matches(PUBLIC_INVITATION_ID_PATTERN)) }
        code?.let { require(it.matches(INVITATION_CODE_PATTERN)) }
        val installationId = deviceTrustService.initialize().installationId
        val body = json.encodeToString(
            VerifyOnboardingInvitationWire(publicId = publicId, code = code, installationId = installationId)
        ).encodeToByteArray()
        val wire = json.decodeFromString(
            VerifiedOnboardingInvitationWire.serializer(),
            executePublicPost(ONBOARDING_VERIFY_PATH, body).decodeToString(),
        )
        check(wire.activationSession.length in 48..2048)
        check(wire.inviteCode.matches(INVITATION_CODE_PATTERN))
        check(wire.inviterUserId.matches(MATRIX_USER_ID_PATTERN))
        check(wire.inviterDisplayName.isNotBlank())
        check(wire.expiresAtEpochMillis > System.currentTimeMillis())
        VerifiedOnboardingInvitation(
            activationSession = wire.activationSession,
            inviteCode = wire.inviteCode,
            inviterUserId = wire.inviterUserId,
            inviterDisplayName = wire.inviterDisplayName,
            expiresAtEpochMillis = wire.expiresAtEpochMillis,
            installationId = installationId,
        )
    }

    override suspend fun suggestOnboardingUsername(
        activationSession: String,
        displayName: String,
        preferredStem: String,
    ): OnboardingUsernameSuggestion = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        val installationId = deviceTrustService.initialize().installationId
        val body = json.encodeToString(
            OnboardingUsernameRequestWire(
                activationSession = activationSession,
                displayName = displayName,
                preferredStem = preferredStem,
                installationId = installationId,
            )
        ).encodeToByteArray()
        val wire = json.decodeFromString(
            OnboardingUsernameSuggestionWire.serializer(),
            executePublicPost(ONBOARDING_USERNAME_PATH, body).decodeToString(),
        )
        check(wire.loginAccount.matches(LOCALPART_PATTERN))
        check(wire.matrixUserId.matches(MATRIX_USER_ID_PATTERN))
        OnboardingUsernameSuggestion(wire.displayName, wire.loginAccount, wire.matrixUserId)
    }

    override suspend fun activateOnboardingInvitation(
        activationSession: String,
        displayName: String,
        preferredStem: String,
        password: String,
        idempotencyKey: String,
        consent: io.element.android.security.dataprotection.api.RegistrationConsent?,
    ): OnboardingActivation = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        require(OnboardingPasswordPolicy.isSatisfiedBy(password))
        require(idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN))
        if (consent == null) throw io.element.android.security.dataprotection.api.RegistrationAgreementRequired()
        val installationId = deviceTrustService.initialize().installationId
        val body = json.encodeToString(
            ActivateOnboardingInvitationWire(
                agreementAccepted = true,
                agreementVersion = consent.version,
                agreementSha256 = consent.sha256,
                activationSession = activationSession,
                displayName = displayName,
                preferredStem = preferredStem,
                installationId = installationId,
                password = password,
                idempotencyKey = idempotencyKey,
            )
        ).encodeToByteArray()
        val wire = json.decodeFromString(
            OnboardingActivationWire.serializer(),
            executePublicPost(ONBOARDING_ACTIVATE_PATH, body).decodeToString(),
        )
        check(wire.loginAccount.matches(LOCALPART_PATTERN))
        check(wire.matrixUserId.matches(MATRIX_USER_ID_PATTERN))
        check(wire.friendship.userId.matches(MATRIX_USER_ID_PATTERN))
        check(wire.friendship.status == "ACCEPTED")
        check(wire.friendship.matrixRoomId.startsWith("!") && ':' in wire.friendship.matrixRoomId)
        OnboardingActivation(
            loginAccount = wire.loginAccount,
            matrixUserId = wire.matrixUserId,
            displayName = wire.displayName,
            inviterUserId = wire.friendship.userId,
            inviterDisplayName = wire.friendship.displayName,
            friendshipStatus = wire.friendship.status,
            directRoomId = wire.friendship.matrixRoomId,
        )
    }

    override suspend fun revokeOnboardingInvitation(publicId: String, inviterUserId: String): OnboardingInvitation =
        withContext(Dispatchers.IO) {
            check(configuration.isConfigured) { "Management service is not configured." }
            require(publicId.matches(PUBLIC_INVITATION_ID_PATTERN))
            require(inviterUserId.matches(MATRIX_USER_ID_PATTERN))
            val path = "$ONBOARDING_INVITATIONS_PATH/$publicId/revoke"
            val body = json.encodeToString(RevokeOnboardingInvitationWire(inviterUserId)).encodeToByteArray()
            json.decodeFromString(
                OnboardingInvitationWire.serializer(),
                executeSignedPost(path, body).decodeToString(),
            ).toApi()
        }

    override suspend fun latestAppRelease(): ManagedAppRelease? = withContext(Dispatchers.IO) {
        check(configuration.isConfigured) { "Management service is not configured." }
        val request = Request.Builder()
            .url(
                configuration.baseUrl + PUBLIC_APP_RELEASE_PATH +
                    "?package_name=${context.packageName}&app_version_code=${buildMeta.versionCode}"
            )
            .get()
            .build()
        val response = try {
            execute(request, bootstrapClient)
        } catch (failure: HttpStatusException) {
            if (failure.statusCode == 404) return@withContext null
            throw failure
        }
        val envelope = runCatchingExceptions {
            json.decodeFromString(SignedJsonEnvelopeWire.serializer(), response.decodeToString())
        }.getOrElse {
            Log.w(DIAGNOSTIC_TAG, "YZMGMT_RELEASE_FAILURE=ENVELOPE_DECODE")
            throw it
        }
        val wire = runCatchingExceptions {
            repository.acceptRelease(envelope, System.currentTimeMillis())
        }.getOrElse {
            Log.w(DIAGNOSTIC_TAG, "YZMGMT_RELEASE_FAILURE=SIGNED_STATE_ACCEPT")
            throw it
        }
        runCatchingExceptions {
            wire.toManagedAppReleaseOrNull()
        }.getOrElse {
            Log.w(DIAGNOSTIC_TAG, "YZMGMT_RELEASE_FAILURE=RELEASE_IDENTITY")
            throw it
        }.also { release ->
            Log.i(DIAGNOSTIC_TAG, "YZMGMT_RELEASE_ACCEPTED update_required=${release != null}")
        }
    }

    /** Fetches the existing pinned public route without filtering out this installed version. */
    internal suspend fun readCurrentNextInstalledArtifact(trustedNowMs: Long): VerifiedNextInstalledArtifact = withContext(Dispatchers.IO) {
        check(configuration.isConfigured && trustedNowMs > 0)
        val request = Request.Builder()
            .url(
                configuration.baseUrl + PUBLIC_APP_RELEASE_PATH +
                "?package_name=${context.packageName}&app_version_code=${buildMeta.versionCode}"
            )
            .get().build()
        val original = json.decodeFromString(JsonObject.serializer(), execute(request, bootstrapClient).decodeToString())
        val installed = VerifiedNextInstalledArtifact.inspect(
            AndroidNextInstalledPackageSource(context),
            original,
            trustedNowMs,
            signatureVerifier,
        )
        val envelope = json.decodeFromJsonElement(SignedJsonEnvelopeWire.serializer(), original)
        repository.acceptRelease(envelope, trustedNowMs)
        check(repository.currentNextReleaseEnvelope(trustedNowMs) == original)
        installed
    }

    /** Expected session/activation scope is independent of a newly received policy challenge. */
    internal suspend fun readCurrentNextAccountAuthority(
        expected: NextCurrentAccountScope,
        trustedNowMs: Long,
    ): VerifiedNextCurrentAccountAuthority? = withContext(Dispatchers.IO) {
        val before = deviceTrustService.current()
        val shareKey = deviceShareKeyService.descriptor()
        check(before.installationId == expected.installationId && before.attestationVersion == expected.currentAttestationVersion)
        check(shareKey.installationId == before.installationId && shareKey.keyId == expected.recipientKeyId)
        val authority = repository.currentNextAccountAuthority(expected, trustedNowMs)
        val after = deviceTrustService.current()
        val currentShareKey = deviceShareKeyService.descriptor()
        check(after.installationId == before.installationId && after.attestationVersion == before.attestationVersion &&
            after.attestationPublicKeySha256 == before.attestationPublicKeySha256 &&
            after.trustState == before.trustState && after.riskSignals == before.riskSignals)
        check(currentShareKey.installationId == shareKey.installationId && currentShareKey.keyId == shareKey.keyId)
        authority
    }

    private val nextPolicyTransport by lazy {
        NextPolicyLifecycleTransport(
            lifecycleJournal = NextPolicyLifecycleJournal(AndroidNextPolicyJournalFile(context, NextPolicyJournalKind.LIFECYCLE)),
            requests = NextPolicyRequestJournal(context),
            start = { original ->
                // Creation has no existing LC header. This is the original signed lifecycle
                // creation route; every modern phase below binds its explicit durable LC ID.
                json.decodeFromString(
                    LifecycleOperationWire.serializer(),
                    executeSignedPost(LIFECYCLE_OPERATIONS_PATH, original, bindLifecycle = false).decodeToString()
                ).toApi()
            },
            post = { path, original, operationId -> executeSignedPost(path, original, operationId) },
        )
    }

    /** Original-byte transport only. The coordinator still owns full current authority,
     * signed-response validation, six actual observations and lifecycle completion receipts.
     * No aggregate APPLIED evidence or HTTP-success lifecycle receipt is generated here.
     */
    internal suspend fun transmitNextPolicyRequest(
        scope: NextPolicyLifecycleScope,
        phase: NextPolicyRequestPhase,
        originalBody: ByteArray,
        validateCurrentAuthority: suspend () -> Unit,
        validateOriginalBody: (ByteArray) -> Unit,
    ): ByteArray = withContext(Dispatchers.IO) {
        check(configuration.isConfigured)
        nextPolicyTransport.send(scope, phase, originalBody, {
            val trust = deviceTrustService.current()
            check(trust.installationId == scope.installationId && trust.attestationVersion > 0)
            val matching = sessionStore.getAllSessions().filter {
                accountIdHash(it.userId) == scope.accountIdHash && sha256Hex(it.deviceId.encodeToByteArray()) == scope.matrixDeviceIdHash
            }
            check(matching.size == 1) { "Current Matrix session differs from the durable policy job." }
            validateCurrentAuthority()
        }, validateOriginalBody)
    }

    override suspend fun cachedAppRelease(): ManagedAppRelease? = withContext(Dispatchers.IO) {
        repository.cachedRelease()?.toManagedAppReleaseOrNull()
    }

    override suspend fun hasValidCachedAppReleaseManifest(): Boolean = withContext(Dispatchers.IO) {
        repository.cachedRelease()?.let { wire ->
            runCatchingExceptions {
                wire.validateAppReleaseIdentity()
                true
            }.getOrDefault(false)
        } ?: false
    }

    private fun AppReleaseWire.toManagedAppReleaseOrNull(): ManagedAppRelease? {
        validateAppReleaseIdentity()
        if (versionCode <= buildMeta.versionCode) return null
        return ManagedAppRelease(
            versionCode = versionCode,
            versionName = versionName,
            releaseNotes = releaseNotes,
            mandatory = mandatory,
            apkUrl = apkUrl,
            apkFallbackUrl = apkFallbackUrl,
            apkSha256 = apkSha256,
            buildId = buildId,
            signingCertificateSha256 = signingCertificateSha256,
            packageName = packageName,
        )
    }

    private fun AppReleaseWire.validateAppReleaseIdentity() {
        check(packageName == context.packageName) { "Release package does not match this application." }
        check(buildId.matches(BUILD_ID_PATTERN)) { "Release build identity is invalid." }
        check(signingCertificateSha256.matches(ACCOUNT_HASH_PATTERN)) { "Release signing certificate identity is invalid." }
        check(isAllowedReleaseApkUrl(apkUrl, releaseCdnBaseUrl())) { "Release CDN APK URL is not allowed." }
        check(isAllowedReleaseApkUrl(apkFallbackUrl, releaseFallbackBaseUrl())) { "Release fallback APK URL is not allowed." }
    }

    override suspend fun downloadAndVerifyAppRelease(
        release: ManagedAppRelease,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit,
    ): PreparedAppUpdate = withContext(Dispatchers.IO) {
        try {
            Timber.tag(DIAGNOSTIC_TAG).i("YZMGMT_UPDATE_SOURCE=CDN")
            downloadWithRetry(release, release.apkUrl, ".cdn.part", onProgress)
        } catch (failure: Throwable) {
            if (!isFallbackEligible(failure)) throw failure
            File(context.cacheDir, "updates/yizhechat-${release.versionCode}.apk.cdn.part").delete()
            Timber.tag(DIAGNOSTIC_TAG).i("YZMGMT_UPDATE_SOURCE=ORIGIN_FALLBACK")
            downloadWithRetry(release, release.apkFallbackUrl, ".origin.part", onProgress)
        }
    }

    private suspend fun downloadWithRetry(
        release: ManagedAppRelease,
        downloadUrl: String,
        temporarySuffix: String,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit,
    ): PreparedAppUpdate {
        var lastFailure: Throwable? = null
        repeat(APP_RELEASE_DOWNLOAD_ATTEMPTS) {
            try {
                return downloadAndVerifyFromUrl(release, downloadUrl, temporarySuffix, onProgress)
            } catch (failure: Throwable) {
                if (!isFallbackEligible(failure)) throw failure
                lastFailure = failure
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun releaseCdnBaseUrl(): String = releaseBaseUrl(
        nextBaseUrl = configuration.updateCdnBaseUrl,
        stableHost = RELEASE_CDN_HOST,
        label = "CDN",
    )

    private fun releaseFallbackBaseUrl(): String = releaseBaseUrl(
        nextBaseUrl = configuration.updateFallbackBaseUrl,
        stableHost = RELEASE_FALLBACK_HOST,
        label = "fallback",
    )

    private fun releaseBaseUrl(nextBaseUrl: String, stableHost: String, label: String): String {
        if (!configuration.isNextDeployment) return "https://$stableHost"
        if (configuration.updateChannel == "internal") {
            check(nextBaseUrl == "https://security.yizhesy.cn") {
                "Internal update $label endpoint does not match the formal authority."
            }
            return nextBaseUrl
        }
        val uri = runCatching { URI(nextBaseUrl) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val path = uri?.path
        check(uri?.scheme == "https" && host.isNotBlank() && uri?.port in 1024..65535 &&
            (path.isNullOrEmpty() || path == "/") && uri?.userInfo == null && uri?.query == null && uri?.fragment == null) {
            "Next update $label endpoint is not a dedicated HTTPS high-port origin."
        }
        check(host !in setOf(RELEASE_CDN_HOST, RELEASE_FALLBACK_HOST, "security.yizhesy.cn", "www.yizhesy.cn") &&
            !host.matches(Regex("[0-9.]+")) && !host.contains(':') && host != "localhost") {
            "Next update $label endpoint must not use a Stable host."
        }
        return nextBaseUrl
    }

    private fun isAllowedReleaseApkUrl(value: String, expectedBaseUrl: String): Boolean = runCatching {
        val uri = URI(value)
        val expectedUri = URI(expectedBaseUrl)
        val portMatches = if (expectedUri.port == -1) uri.port == -1 || uri.port == 443 else uri.port == expectedUri.port
        uri.scheme == "https" && uri.host.equals(expectedUri.host, ignoreCase = true) && portMatches &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.startsWith(RELEASE_APK_PATH_PREFIX) && uri.path.endsWith(".apk")
    }.getOrDefault(false)

    private fun isFallbackEligible(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        it is HttpStatusException && (it.statusCode == 404 || it.statusCode == 408 || it.statusCode == 429 || it.statusCode in 500..599) ||
            it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.SocketTimeoutException || it is javax.net.ssl.SSLException
    }

    private suspend fun downloadAndVerifyFromUrl(
        release: ManagedAppRelease,
        downloadUrl: String,
        temporarySuffix: String,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit,
    ): PreparedAppUpdate {
        check(release.packageName == context.packageName)
        check(release.versionCode > buildMeta.versionCode)
        val target = File(context.cacheDir, "updates/yizhechat-${release.versionCode}.apk")
        val temporary = File(target.parentFile, "${target.name}$temporarySuffix")
        target.parentFile?.mkdirs()
        val resumeOffset = temporary.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .apply {
                if (resumeOffset > 0L) header("Range", "bytes=$resumeOffset-")
            }
            .build()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && resumeOffset > 0L) {
                    temporary.delete()
                    throw IOException("Saved APK fragment is outside the server range; retry from the beginning.")
                }
                if (!response.isSuccessful) {
                    val errorBody = response.body.bytes()
                    throw HttpStatusException(response.code, safeServerDetail(errorBody), parseRetryAfterMillis(response.header("Retry-After")))
                }
                val body = response.body
                val isResuming = resumeOffset > 0L && response.code == 206
                val rangeTotal = if (isResuming) {
                    try {
                        parseContentRangeTotal(response.header("Content-Range"), resumeOffset)
                    } catch (failure: IOException) {
                        temporary.delete()
                        throw failure
                    }
                } else {
                    null
                }
                if (resumeOffset > 0L && response.code != 206) {
                    // Some intermediaries ignore Range. Restart safely instead of duplicating bytes.
                    temporary.delete()
                }
                val initialBytes = if (isResuming) resumeOffset else 0L
                val totalBytes = rangeTotal ?: body.contentLength().takeIf { it >= 0L }?.plus(initialBytes) ?: -1L
                if (isResuming) {
                    temporary.inputStream().buffered().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                var downloadedBytes = initialBytes
                var lastReportedBytes = -DOWNLOAD_PROGRESS_REPORT_BYTES
                onProgress(AppUpdateDownloadProgress(downloadedBytes, totalBytes))
                lastReportedBytes = downloadedBytes
                body.byteStream().use { input ->
                    FileOutputStream(temporary, isResuming).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloadedBytes += count
                            if (downloadedBytes - lastReportedBytes >= DOWNLOAD_PROGRESS_REPORT_BYTES ||
                                (totalBytes > 0L && downloadedBytes >= totalBytes)
                            ) {
                                onProgress(AppUpdateDownloadProgress(downloadedBytes, totalBytes))
                                lastReportedBytes = downloadedBytes
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(totalBytes <= 0L || downloadedBytes == totalBytes) { "Downloaded APK length mismatch." }
                if (downloadedBytes != lastReportedBytes) {
                    onProgress(AppUpdateDownloadProgress(downloadedBytes, totalBytes))
                }
            }
            if (digest.digest().toHex() != release.apkSha256) {
                temporary.delete()
                error("Downloaded APK digest mismatch.")
            }
            target.delete()
            check(temporary.renameTo(target)) { "Downloaded APK could not be finalized." }
            try {
                verifyArchiveIdentity(target, release)
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        } catch (failure: Throwable) {
            // Network failures intentionally keep the partial file for the next resume attempt.
            throw failure
        }
        return PreparedAppUpdate(release, target)
    }

    private fun parseContentRangeTotal(value: String?, expectedStart: Long): Long {
        val match = CONTENT_RANGE_PATTERN.matchEntire(value.orEmpty())
            ?: throw IOException("Resume response is missing a valid Content-Range header.")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLong()
        if (start != expectedStart || end < start || end >= total) {
            throw IOException("Resume response range does not match the saved APK fragment.")
        }
        return total
    }

    @Suppress("DEPRECATION")
    private fun verifyArchiveIdentity(apk: File, release: ManagedAppRelease) {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: throw SecurityException("Downloaded APK cannot be parsed.")
        check(archive.packageName == release.packageName)
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == release.versionCode)
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(context.packageName, flags)
        }
        val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        check(archiveSigners == setOf(release.signingCertificateSha256)) {
            "APK signing certificate does not match the signed release manifest."
        }
        check(archiveSigners == installedSigners) { "APK signing certificate does not match the installed app." }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private suspend fun performSync(): SyncResponseWire {
        val trust = deviceTrustService.current()
        if (trust.attestationVersion <= 0) throw EnrollmentRequiredException()
        val sessions = sessionStore.getAllSessions().filter { it.isTokenValid }
        val heartbeat = HeartbeatWire(
            appVersionCode = buildMeta.versionCode,
            attestationVersion = trust.attestationVersion,
            deviceCorrelationIdSha256 = deviceCorrelationIdSha256(),
            accountIdHashes = sessions.map { accountIdHash(it.userId) }.distinct().sorted(),
            accountUserIds = sessions.map { it.userId }.distinct().sorted(),
            matrixDeviceBindings = sessions.map { session ->
                MatrixDeviceBindingWire(
                    accountIdHash = accountIdHash(session.userId),
                    matrixDeviceIdHash = sha256Hex(session.deviceId.encodeToByteArray()),
                )
            }.distinct().sortedWith(compareBy(MatrixDeviceBindingWire::accountIdHash, MatrixDeviceBindingWire::matrixDeviceIdHash)),
            matrixIdentityState = aggregateMatrixIdentityState(
                sessions.map { session ->
                    runCatchingExceptions {
                        val sessionId = UserId(session.userId)
                        val provider = matrixClientProvider()
                        val client = provider.getOrNull(sessionId)
                            ?: provider.getOrRestore(sessionId).getOrThrow()
                        client.sessionVerificationService.sessionVerifiedStatus.value
                    }.getOrDefault(SessionVerifiedStatus.Unknown)
                },
            ),
            kmsState = kmsState(),
            attestationState = trust.trustState.name,
            policyVersion = repository.currentPolicyVersion(),
            tombstoneSequence = repository.currentTombstoneSequence(),
        )
        val body = json.encodeToString(heartbeat).encodeToByteArray()
        return try {
            json.decodeFromString(SyncResponseWire.serializer(), executeSignedPost(SYNC_PATH, body).decodeToString())
        } catch (failure: HttpStatusException) {
            if (isStableEnrollmentBootstrapFailure(failure.statusCode, failure.safeDetail)) {
                throw EnrollmentRequiredException()
            }
            throw failure
        }
    }

    private suspend fun enrollDevice() {
        val trust = deviceTrustService.initialize()
        check(trust.installationId.isNotBlank())
        val challengeRequest = Request.Builder()
            .url(configuration.baseUrl + "/v1/device/challenge/" + trust.installationId)
            .get()
            .build()
        val challenge = json.decodeFromString(
            ChallengeWire.serializer(),
            execute(challengeRequest).decodeToString(),
        )
        check(challenge.expiresAtEpochMillis > System.currentTimeMillis())
        val evidence = deviceTrustService.refresh(Base64.decode(challenge.challengeBase64, Base64.DEFAULT))
        val contentShareKey = deviceShareKeyService.descriptor()
        check(contentShareKey.installationId == evidence.installationId)
        val body = json.encodeToString(evidence.toEnrollmentWire(challenge.challengeId, contentShareKey)).encodeToByteArray()
        val request = Request.Builder()
            .url(configuration.baseUrl + ENROLL_PATH)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    private suspend fun ensureDeviceEnrollmentLocked(
        forceRemoteEnrollment: Boolean,
        observedAttestationVersion: Long,
        forcePendingOnly: Boolean = false,
    ) {
        enrollmentMutex.withLock {
            val current = deviceTrustService.current()
            when (
                deviceEnrollmentDecision(
                    forceRemoteEnrollment = forceRemoteEnrollment && (!forcePendingOnly || current.trustState == DeviceTrustState.PENDING),
                    observedAttestationVersion = observedAttestationVersion,
                    currentAttestationVersion = current.attestationVersion,
                    currentTrustState = current.trustState,
                )
            ) {
                DeviceEnrollmentDecision.SKIP -> return
                DeviceEnrollmentDecision.BLOCK -> throw SecurityException(
                    "Device trust state is not eligible for enrollment.",
                )
                DeviceEnrollmentDecision.ENROLL -> enrollDevice()
            }
        }
    }

    private suspend fun executeSignedPost(
        path: String,
        body: ByteArray,
        lifecycleOperationId: String? = null,
        bindLifecycle: Boolean = true,
    ): ByteArray {
        val lifecycleBinding = if (bindLifecycle) {
            lifecycleOperationId
                ?.let { ResolvedLifecycleBinding(it, createdImplicitly = false) }
                ?: resolveLifecycleBinding("POST", path)
        } else {
            null
        }
        val boundOperationId = lifecycleBinding?.operationId
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes).toBase64Url()
        val statementParts = mutableListOf("POST", path, timestamp, nonce, sha256Hex(body))
        boundOperationId?.let(statementParts::add)
        val statement = statementParts.joinToString("\n").encodeToByteArray()
        val signed = deviceTrustService.signControlStatement(statement)
        val request = Request.Builder()
            .url(configuration.baseUrl + path)
            .apply { if (path == "/v1/lifecycle/direct-room") header("X-YiZhe-Request-Id", java.util.UUID.randomUUID().toString()) }
            .header("X-YiZhe-Installation", signed.installationId)
            .header("X-YiZhe-Timestamp", timestamp)
            .header("X-YiZhe-Nonce", nonce)
            .header("X-YiZhe-Signature", signed.signature.toBase64())
            .apply {
                boundOperationId?.let { header("X-YiZhe-Lifecycle-Operation-Id", it) }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request).also {
            if (lifecycleBinding?.createdImplicitly == true) {
                recordImplicitServiceReceipt(lifecycleBinding.operationId, "POST", path)
            }
        }
    }

    private suspend fun executeSignedGet(path: String, lifecycleOperationId: String? = null): ByteArray {
        val lifecycleBinding = lifecycleOperationId
            ?.let { ResolvedLifecycleBinding(it, createdImplicitly = false) }
            ?: resolveLifecycleBinding("GET", path)
        val boundOperationId = lifecycleBinding?.operationId
        val body = ByteArray(0)
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes).toBase64Url()
        val statementParts = mutableListOf("GET", path, timestamp, nonce, sha256Hex(body))
        boundOperationId?.let(statementParts::add)
        val signed = deviceTrustService.signControlStatement(statementParts.joinToString("\n").encodeToByteArray())
        val request = Request.Builder()
            .url(configuration.baseUrl + path)
            .header("X-YiZhe-Installation", signed.installationId)
            .header("X-YiZhe-Timestamp", timestamp)
            .header("X-YiZhe-Nonce", nonce)
            .header("X-YiZhe-Signature", signed.signature.toBase64())
            .apply {
                boundOperationId?.let { header("X-YiZhe-Lifecycle-Operation-Id", it) }
            }
            .get()
            .build()
        return execute(request).also {
            if (lifecycleBinding?.createdImplicitly == true) {
                recordImplicitServiceReceipt(lifecycleBinding.operationId, "GET", path)
            }
        }
    }

    private suspend fun resolveLifecycleOperationId(method: String, path: String): String? =
        resolveLifecycleBinding(method, path)?.operationId

    private suspend fun resolveLifecycleBinding(method: String, path: String): ResolvedLifecycleBinding? {
        currentLifecycleOperationContext()?.let {
            return ResolvedLifecycleBinding(it.operationId, createdImplicitly = false)
        }
        val route = implicitLifecycleRoute(method, path) ?: return null
        val operation = startLifecycleOperation(
            lifecycleOperationRequest(
                lifecycleId = route.lifecycleId,
                trigger = route.trigger,
            )
        )
        return ResolvedLifecycleBinding(operation.operationId, createdImplicitly = true)
    }

    private suspend fun recordImplicitServiceReceipt(operationId: String, method: String, path: String) {
        recordLifecycleClientReceipt(
            LifecycleClientReceipt(
                operationId = operationId,
                component = LifecycleComponent.ANDROID_SERVICE,
                phase = LifecyclePhase.CLIENT_APPLIED,
                resultCode = "ANDROID_SERVICE_HTTP_APPLIED",
                receiptReference = "android-service:$operationId:${method.lowercase()}:${sha256Hex(path.encodeToByteArray())}",
                occurredAtEpochMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun implicitLifecycleRoute(method: String, path: String): LifecycleRoute? = when {
        method == "POST" && path == SYNC_PATH -> LifecycleRoute(FullAppLifecycleId.LC_06, "device_sync")
        method == "POST" && path == HEARTBEAT_PATH -> LifecycleRoute(FullAppLifecycleId.LC_07, "heartbeat")
        method == "POST" && path == LOCATION_EVIDENCE_PATH -> LifecycleRoute(FullAppLifecycleId.LC_05, "first_activation")
        method == "POST" && path == OFFICE_PREVIEW_GRANT_PATH -> LifecycleRoute(FullAppLifecycleId.LC_16, "preview")
        method == "POST" && path in setOf(ACCOUNT_REACTIVATION_PREPARE_PATH, ACCOUNT_REACTIVATION_COMMIT_PATH) ->
            LifecycleRoute(FullAppLifecycleId.LC_06, "reactivation")
        method == "POST" && path in setOf(ACCOUNT_DELETION_PREPARE_PATH, ACCOUNT_DELETION_COMMIT_PATH) ->
            LifecycleRoute(FullAppLifecycleId.LC_22, "permanent_delete")
        // Command reports must inherit the command-specific lifecycle context. A static fallback
        // would mislabel content/account destruction as device revocation and must fail closed.
        method == "POST" && path == ONBOARDING_INVITATIONS_PATH -> LifecycleRoute(FullAppLifecycleId.LC_05, "registration_submit")
        method == "POST" && path.startsWith("$ONBOARDING_INVITATIONS_PATH/") && path.endsWith("/revoke") ->
            LifecycleRoute(FullAppLifecycleId.LC_05, "registration_submit")
        else -> null
    }

    private data class LifecycleRoute(val lifecycleId: FullAppLifecycleId, val trigger: String)

    private data class ResolvedLifecycleBinding(val operationId: String, val createdImplicitly: Boolean)

    private fun executePublicPost(path: String, body: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(configuration.baseUrl + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, onboardingPublicPostClient(path, bootstrapClient, onboardingActivationClient))
    }

    private fun OnboardingInvitationWire.toApi(): OnboardingInvitation {
        check(publicId.matches(PUBLIC_INVITATION_ID_PATTERN))
        check(inviteCode.matches(INVITATION_CODE_PATTERN))
        check(inviterUserId.matches(MATRIX_USER_ID_PATTERN))
        check(status in setOf("ACTIVE", "CONSUMED", "REVOKED", "EXPIRED"))
        val uri = URI(invitationUrl)
        check(isAllowedInvitationUrl(uri, publicId))
        return OnboardingInvitation(
            publicId = publicId,
            inviteCode = inviteCode,
            inviterUserId = inviterUserId,
            inviterDisplayName = inviterDisplayName,
            status = status,
            expiresAtEpochMillis = expiresAtEpochMillis,
            invitationUrl = invitationUrl,
        )
    }

    private fun isAllowedInvitationUrl(uri: URI, publicId: String): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path != "/i/$publicId") {
            return false
        }
        if (!configuration.isNextDeployment) return uri.host in OFFICIAL_INVITATION_HOSTS
        val nextOrigin = runCatching { URI(configuration.baseUrl) }.getOrNull() ?: return false
        val uriPort = if (uri.port == -1) 443 else uri.port
        val nextPort = if (nextOrigin.port == -1) 443 else nextOrigin.port
        return uri.host.equals(nextOrigin.host, ignoreCase = true) && uriPort == nextPort
    }

    private fun execute(request: Request, httpClient: OkHttpClient = client): ByteArray {
        return httpClient.newCall(request).execute().use { response ->
            val bytes = if (request.url.encodedPath == OPERATION_SNAPSHOT_PATH) {
                val source = response.body.source()
                check(!source.request(OPERATION_SNAPSHOT_MAX_RESPONSE_BYTES.toLong() + 1)) { "Operation recovery response is too large." }
                source.readByteArray()
            } else {
                response.body.bytes()
            }
            if (!response.isSuccessful) {
                if (request.url.encodedPath == ONBOARDING_ACTIVATE_PATH) {
                    onboardingActivationError(response.code, safeServerDetail(bytes))?.let { throw it }
                }
                if (request.url.encodedPath.endsWith(ACCOUNT_DELETION_PREPARE_PATH) &&
                    isPermanentDeletionSubmissionBlocked(response.code, bytes)
                ) {
                    throw PermanentAccountDeletionSubmissionBlockedException()
                }
                if (request.url.encodedPath == "/v1/lifecycle/direct-room") {
                    Timber.tag(
                        "YZDirectRoom"
                    ).w(managedDirectRoomFailureDiagnostic(response.code, safeServerDetail(bytes), request.header("X-YiZhe-Request-Id")))
                }
                throw HttpStatusException(response.code, safeServerDetail(bytes), parseRetryAfterMillis(response.header("Retry-After")))
            }
            bytes
        }
    }

    private fun safeServerDetail(body: ByteArray): String? = runCatchingExceptions {
        json.parseToJsonElement(body.decodeToString()).jsonObject["detail"]?.jsonPrimitive?.content
            ?.takeIf { it.matches(SAFE_SERVER_DETAIL) }
    }.getOrNull()

    private suspend fun acceptSyncResponse(response: SyncResponseWire): ManagementSyncReport {
        acceptSyncStage(ERROR_SYNC_ENVELOPE) { check(response.serverTimeEpochMillis > 0) }
        acceptSyncStage(ERROR_SYNC_TIME_STATE) { repository.updateTrustedTime(response.serverTimeEpochMillis) }
        // Persist target-bound destructive commands before applying a trust projection that may reject this
        // now-superseded installation. Otherwise the valid CLEAR_ACCOUNT command is lost with the sync failure.
        val installationId = acceptSyncStage(ERROR_SYNC_TRUST_SNAPSHOT) { deviceTrustService.current().installationId }
        response.tombstones?.let { envelope ->
            acceptSyncStage(ERROR_SYNC_ACCOUNT_TOMBSTONES) {
                repository.acceptAccountTombstoneChanges(envelope, installationId)
            }
        }
        response.commands.forEach { envelope ->
            acceptSyncStage(ERROR_SYNC_COMMAND) {
                val command = repository.acceptCommand(envelope, response.serverTimeEpochMillis)
                check(command.installationId == installationId) { "Management command targets another installation." }
            }
        }
        val trustDecision = acceptSyncStage(ERROR_SYNC_TRUST_DECISION) {
            acceptTrustDecision(response.deviceTrust, response.serverTimeEpochMillis)
        }
        val acceptedAccessTicket = response.accessTicket?.let {
            acceptSyncStage(ERROR_SYNC_ACCESS_TICKET) { acceptAccessTicket(it, response.serverTimeEpochMillis) }
        }
        accessTicket = acceptedAccessTicket
        var r05ApplicationState: LocalPolicyApplicationState? = null
        val policyVersion = response.policy?.let { policyObject ->
            acceptSyncStage(ERROR_SYNC_POLICY) {
                if (policyObject["contract"] != null) {
                    val envelope = json.decodeFromJsonElement(
                        SignedPolicyRevisionEnvelopeWire.serializer(),
                        policyObject,
                    )
                    val application = repository.acceptPolicyRevisionEnvelope(
                        envelope = envelope,
                        serverTimeEpochMillis = response.serverTimeEpochMillis,
                        localSecurityEpoch = SECURITY_PROTOCOL_EPOCH,
                        supportedCapabilities = supportedR05PolicyCapabilities(),
                    )
                    r05ApplicationState = LocalPolicyApplicationState.valueOf(application.state)
                    prepareTerminalPolicyApplicationReceipt(application)
                    envelope.version
                } else {
                    val legacyEnvelope = json.decodeFromJsonElement(
                        SignedJsonEnvelopeWire.serializer(),
                        policyObject,
                    )
                    repository.acceptPolicy(legacyEnvelope, response.serverTimeEpochMillis)
                }
            }
        }
        val terminalPolicyError = when (r05ApplicationState) {
            LocalPolicyApplicationState.BLOCKED_VERSION -> "POLICY_APPLICATION_BLOCKED_VERSION"
            LocalPolicyApplicationState.FAILED -> "POLICY_APPLICATION_FAILED"
            else -> null
        }
        if (terminalPolicyError == null && trustDecision.toManagedDeviceSecurityState() == ManagedDeviceSecurityState.READY &&
            repository.pendingCommands().isEmpty() && trustDecision.restrictionReasonCode != "REGISTRATION_PENDING"
        ) {
            repository.acceptOfflineEntry(response.deviceTrust)
            repository.acceptLocalListEntry(response.deviceTrust)
        } else {
            repository.clearOfflineEntry()
            repository.acceptLocalListEntry(response.deviceTrust, permitted = false)
        }
        val restrictionReason = terminalPolicyError ?: trustDecision.restrictionReasonCode
        return ManagementSyncReport(
            status = managementSyncStatusForRestriction(restrictionReason),
            acceptedPolicyVersion = policyVersion ?: repository.currentPolicyVersion(),
            pendingCommandCount = repository.pendingCommands().size,
            errorCode = restrictionReason ?: trustDecision.automaticLicenseErrorCode,
            deviceSecurityState = trustDecision.toManagedDeviceSecurityState(),
            accountHandoverRequiredAccountHashes = trustDecision.accountHandoverRequiredAccountHashes.toSet(),
            locationEvidenceState = DeviceLocationEvidenceState.valueOf(trustDecision.locationEvidenceState),
            heartbeatIntervalMillis = acceptedAccessTicket?.heartbeatIntervalMillis,
        )
    }

    private suspend fun <T> acceptSyncStage(errorCode: String, block: suspend () -> T): T =
        runCatchingExceptions { block() }.getOrElse { failure ->
            throw ManagementSyncStageException(errorCode, failure)
        }

    private suspend fun acceptAccessTicket(
        envelope: SignedJsonEnvelopeWire,
        serverTimeEpochMillis: Long,
    ): DeviceAccessTicketWire {
        check(signatureVerifier.verify(envelope)) { "Device access ticket signature verification failed." }
        val ticket = json.decodeFromJsonElement(DeviceAccessTicketWire.serializer(), envelope.payload())
        val trust = deviceTrustService.current()
        return Validators.validateAccessTicket(
            ticket = ticket,
            serverTimeEpochMillis = serverTimeEpochMillis,
            expectedInstallationId = trust.installationId,
            expectedAttestationVersion = trust.attestationVersion,
            expectedDevicePublicKeySha256 = trust.attestationPublicKeySha256,
        )
    }

    internal object Validators {
        internal fun validateAccountDeletionStatus(
            wire: AccountDeletionStatusWire,
            expectedOperationId: String,
            nowEpochMillis: Long,
            verifyMilestone: (SignedJsonEnvelopeWire) -> Boolean,
        ): PermanentAccountDeletionStatus {
            check(wire.operationId == expectedOperationId)
            check(wire.accountState == "FROZEN")
            check(wire.generation > 0)
            val state = PermanentAccountDeletionState.valueOf(wire.deletionState)
            val milestone = wire.securityErasureReceipt?.let { envelope ->
                check(verifyMilestone(SignedJsonEnvelopeWire(
                    milestone = envelope.milestone,
                    keyId = envelope.keyId,
                    signatureBase64 = envelope.signatureBase64,
                ))) { "Security erasure milestone signature verification failed." }
                Json.decodeFromJsonElement(SecurityErasureMilestoneWire.serializer(), envelope.milestone).also { value ->
                    check(value.schemaVersion == 1 && value.contractRevision == "lc22-v2")
                    check(value.purpose == "SECURITY_ERASURE_COMPLETE")
                    check(value.operationId == wire.operationId)
                    check(value.generation == wire.generation)
                    check(value.policyVersion == wire.policyVersion)
                    check(value.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
                    check(value.postconditionHash.matches(ACCOUNT_HASH_PATTERN))
                    check(value.completedAtEpochMillis in 1..nowEpochMillis + MAX_CLOCK_SKEW_MILLIS)
                }
            }
            check(wire.securityErasureComplete == (milestone != null))
            val capability = wire.clientReceiptCapability?.also { value ->
                check(value.capability == "CLIENT_CLEAR")
                check(value.resourceScopeHash.matches(ACCOUNT_HASH_PATTERN))
                check(value.token.length in 32..4096)
                check(value.expiresAtEpochMillis > nowEpochMillis)
            }
            return PermanentAccountDeletionStatus(
                operationId = wire.operationId,
                accountState = wire.accountState,
                deletionState = state,
                generation = wire.generation,
                policyVersion = wire.policyVersion,
                securityErasureMilestone = milestone?.let { value ->
                    SecurityErasureMilestone(
                        operationId = value.operationId,
                        accountIdHash = value.accountIdHash,
                        generation = value.generation,
                        policyVersion = value.policyVersion,
                        completedAtEpochMillis = value.completedAtEpochMillis,
                        postconditionHash = value.postconditionHash,
                    )
                },
                clientReceiptCapability = capability?.let { value ->
                    DeletionScopedClientCapability(
                        token = value.token,
                        resourceScopeHash = value.resourceScopeHash,
                        expiresAtEpochMillis = value.expiresAtEpochMillis,
                    )
                },
                retryable = wire.retryable,
                finalReceiptPresent = wire.finalReceipt != null,
            )
        }

        internal fun validateHeartbeatReceipt(
            ticket: DeviceAccessTicketWire,
            receipt: DeviceHeartbeatReceiptWire,
            expectedInstallationId: String,
        ): ManagementHeartbeatReport {
            check(receipt.schemaVersion == 1)
            check(receipt.ticketId == ticket.ticketId)
            check(receipt.installationId == expectedInstallationId)
            check(receipt.serverTimeEpochMillis > 0)
            check(receipt.nextHeartbeatAtEpochMillis > receipt.serverTimeEpochMillis)
            check(receipt.heartbeatGraceExpiresAtEpochMillis > receipt.nextHeartbeatAtEpochMillis)
            check(receipt.managementPolicyVersion == null || receipt.managementPolicyVersion > 0)
            val delay = receipt.nextHeartbeatAtEpochMillis - receipt.serverTimeEpochMillis
            return when (receipt.accessState) {
                "ACTIVE" -> {
                    // An ACTIVE receipt must preserve the schedule and expiry that were signed into the ticket.
                    // Otherwise a compromised or misconfigured endpoint could silently stretch a 60-second session.
                    check(delay == ticket.heartbeatIntervalMillis)
                    check(receipt.heartbeatGraceExpiresAtEpochMillis - receipt.nextHeartbeatAtEpochMillis == ticket.heartbeatGraceMillis)
                    check(receipt.ticketExpiresAtEpochMillis == ticket.expiresAtEpochMillis)
                    ManagementHeartbeatReport(
                        status = ManagementHeartbeatStatus.ACTIVE,
                        nextHeartbeatDelayMillis = delay,
                        serverPolicyVersion = receipt.managementPolicyVersion,
                    )
                }
                "REAUTH_REQUIRED" -> ManagementHeartbeatReport(
                    ManagementHeartbeatStatus.REAUTH_REQUIRED,
                    delay.coerceIn(MIN_HEARTBEAT_INTERVAL_MILLIS, MAX_HEARTBEAT_INTERVAL_MILLIS),
                    receipt.managementPolicyVersion,
                )
                "REVOKED" -> ManagementHeartbeatReport(
                    ManagementHeartbeatStatus.REVOKED,
                    delay.coerceIn(MIN_HEARTBEAT_INTERVAL_MILLIS, MAX_HEARTBEAT_INTERVAL_MILLIS),
                    receipt.managementPolicyVersion,
                )
                else -> throw SecurityException("Unsupported device heartbeat access state.")
            }
        }

        internal fun validateAccessTicket(
            ticket: DeviceAccessTicketWire,
            serverTimeEpochMillis: Long,
            expectedInstallationId: String,
            expectedAttestationVersion: Long,
            expectedDevicePublicKeySha256: String?,
        ): DeviceAccessTicketWire {
            check(ticket.schemaVersion == 1)
            check(ticket.ticketId.length in 24..128)
            check(ticket.installationId == expectedInstallationId)
            check(ticket.attestationVersion == expectedAttestationVersion)
            check(ticket.devicePublicKeySha256 == expectedDevicePublicKeySha256)
            check(ticket.accessPolicyVersion > 0)
            check(ticket.issuedAtEpochMillis <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS)
            check(ticket.expiresAtEpochMillis > serverTimeEpochMillis)
            check(ticket.expiresAtEpochMillis - ticket.issuedAtEpochMillis in 1..MAX_TICKET_TTL_MILLIS)
            check(ticket.heartbeatIntervalMillis in MIN_HEARTBEAT_INTERVAL_MILLIS..MAX_HEARTBEAT_INTERVAL_MILLIS)
            check(ticket.heartbeatGraceMillis in MIN_HEARTBEAT_GRACE_MILLIS..MAX_HEARTBEAT_GRACE_MILLIS)
            check(ticket.heartbeatIntervalMillis + ticket.heartbeatGraceMillis < ticket.expiresAtEpochMillis - ticket.issuedAtEpochMillis)
            return ticket
        }
    }

    private suspend fun acceptTrustDecision(envelope: SignedJsonEnvelopeWire, serverTimeEpochMillis: Long): TrustDecisionWire {
        check(signatureVerifier.verify(envelope)) { "Device trust decision signature verification failed." }
        val decision = json.decodeFromJsonElement(TrustDecisionWire.serializer(), envelope.payload())
        check(decision.schemaVersion in 1..3)
        check(decision.issuedAtEpochMillis <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS)
        check(decision.expiresAtEpochMillis > serverTimeEpochMillis)
        check(
            decision.restrictionReasonCode == null ||
                decision.restrictionReasonCode == "DEVICE_ADMINISTRATIVELY_LOCKED" && decision.trustState == "STALE" ||
                decision.restrictionReasonCode == "REGISTRATION_PENDING" && decision.trustState in setOf("TRUSTED", "MANAGED")
        ) {
            "Signed device restriction contradicts the trust decision."
        }
        check(decision.accountHandoverRequiredAccountHashes.size <= 32)
        check(decision.accountHandoverRequiredAccountHashes.distinct().size == decision.accountHandoverRequiredAccountHashes.size)
        check(decision.accountHandoverRequiredAccountHashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        val current = deviceTrustService.current()
        check(decision.installationId == current.installationId)
        check(decision.attestationVersion == current.attestationVersion)
        when (decision.trustState) {
            "TRUSTED" -> deviceTrustService.applyRemoteTrust(decision.attestationVersion, decision.issuedAtEpochMillis)
            "STALE" -> deviceTrustService.applyRemoteRestriction(
                DeviceTrustState.STALE,
                decision.issuedAtEpochMillis,
            )
            "MANAGED" -> {
                check(decision.managedLicenseState == "ACTIVE")
                val contentShareKey = deviceShareKeyService.descriptor()
                val sessions = sessionStore.getAllSessions().filter { it.isTokenValid }
                val currentAccountHashes = sessions.map { accountIdHash(it.userId) }.toSet()
                val license = if (decision.schemaVersion == 3) {
                    val expectedBindings = sessions.map { session ->
                        MatrixDeviceBindingWire(
                            accountIdHash = accountIdHash(session.userId),
                            matrixDeviceIdHash = sha256Hex(session.deviceId.encodeToByteArray()),
                        )
                    }.toSet()
                    val handoverAccounts = decision.accountHandoverRequiredAccountHashes.toSet()
                    check(decision.accountBindings.toSet() == expectedBindings.filterNot { it.accountIdHash in handoverAccounts }.toSet()) {
                        "Signed account-device bindings do not match the active Matrix sessions."
                    }
                    acceptManagedLicenseEnvelope(
                        envelope = checkNotNull(decision.managedLicenseEnvelope),
                        decision = decision,
                        serverTimeEpochMillis = serverTimeEpochMillis,
                    )
                } else {
                    check(decision.schemaVersion == 2)
                    check(decision.managedLicenseAccountHashes.isNotEmpty())
                    check(currentAccountHashes.all(decision.managedLicenseAccountHashes.toSet()::contains))
                    ManagedDeviceLicense(
                        licenseId = checkNotNull(decision.managedLicenseId),
                        installationId = decision.installationId,
                        devicePublicKeySha256 = checkNotNull(decision.managedLicenseDevicePublicKeySha256),
                        contentShareKeyId = checkNotNull(decision.managedLicenseContentShareKeyId),
                        appPackageName = checkNotNull(decision.managedLicenseAppPackage),
                        appVersionCode = checkNotNull(decision.managedLicenseAppVersion),
                        revision = checkNotNull(decision.managedLicenseRevision),
                        allowedScopes = decision.managedLicenseScopes.toSet(),
                        offlineAllowed = checkNotNull(decision.managedLicenseOfflineAllowed),
                        issuedAtEpochMillis = checkNotNull(decision.managedLicenseIssuedAtEpochMillis),
                        expiresAtEpochMillis = checkNotNull(decision.managedLicenseExpiresAtEpochMillis),
                        accountIdHashes = decision.managedLicenseAccountHashes.toSet(),
                    )
                }
                check(license.contentShareKeyId == contentShareKey.keyId)
                check(license.appPackageName == context.packageName)
                check(license.appVersionCode <= buildMeta.versionCode)
                check(license.isActive(serverTimeEpochMillis))
                deviceTrustService.applyRemoteManagedTrust(
                    decision.attestationVersion,
                    license,
                    decision.issuedAtEpochMillis,
                )
            }
            "REVOKED", "REJECTED" -> deviceTrustService.applyRemoteRestriction(
                DeviceTrustState.REVOKED,
                decision.issuedAtEpochMillis,
            )
            "PENDING" -> if (decision.managedLicenseState == "PENDING") {
                deviceTrustService.applyRemoteManagedPending(
                    decision.attestationVersion,
                    decision.issuedAtEpochMillis,
                )
            } else {
                Unit
            }
            else -> throw SecurityException("Unsupported remote device trust decision.")
        }
        return decision
    }

    private fun acceptManagedLicenseEnvelope(
        envelope: SignedJsonEnvelopeWire,
        decision: TrustDecisionWire,
        serverTimeEpochMillis: Long,
    ): ManagedDeviceLicense {
        check(signatureVerifier.verify(envelope)) { "Managed compatibility license signature verification failed." }
        val wire = json.decodeFromJsonElement(ManagedDeviceLicenseWire.serializer(), envelope.payload())
        check(wire.schemaVersion in 1..2)
        check(wire.licenseId.length in 24..128)
        check(wire.installationId == decision.installationId)
        check(wire.devicePublicKeySha256.matches(ACCOUNT_HASH_PATTERN))
        check(wire.contentShareKeyId.matches(ACCOUNT_HASH_PATTERN))
        check(wire.assuranceClass == "MANAGED_EXCEPTION")
        check(!wire.offlineAllowed)
        check(wire.licenseRevision > 0)
        check(wire.allowedScopes.isNotEmpty())
        check(wire.issuedAtEpochMillis <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS)
        check(wire.expiresAtEpochMillis > serverTimeEpochMillis)
        val minimumVersion = if (wire.schemaVersion == 1) {
            checkNotNull(wire.legacyAppVersion)
        } else {
            check(wire.appVersionAtIssue != null && wire.appVersionAtIssue > 0)
            val minimumSecurityProtocolEpoch = checkNotNull(wire.securityProtocolEpochMin)
            val maximumSecurityProtocolEpoch = checkNotNull(wire.securityProtocolEpochMax)
            check(SECURITY_PROTOCOL_EPOCH in minimumSecurityProtocolEpoch..maximumSecurityProtocolEpoch)
            val installedSigners = installedSigningCertificateSha256()
            check(installedSigners.isNotEmpty() && wire.appSigningCertificateSha256.toSet() == installedSigners)
            checkNotNull(wire.minimumAppVersion)
        }
        check(minimumVersion > 0 && minimumVersion <= buildMeta.versionCode)
        return ManagedDeviceLicense(
            licenseId = wire.licenseId,
            installationId = wire.installationId,
            devicePublicKeySha256 = wire.devicePublicKeySha256,
            contentShareKeyId = wire.contentShareKeyId,
            appPackageName = wire.appPackage,
            appVersionCode = minimumVersion,
            revision = wire.licenseRevision,
            allowedScopes = wire.allowedScopes.toSet(),
            offlineAllowed = wire.offlineAllowed,
            issuedAtEpochMillis = wire.issuedAtEpochMillis,
            expiresAtEpochMillis = wire.expiresAtEpochMillis,
            accountIdHashes = emptySet(),
        )
    }

    @Suppress("DEPRECATION")
    private fun installedSigningCertificateSha256(): Set<String> {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(context.packageName, flags)
        }
        return installed.signingInfo?.apkContentsSigners.orEmpty()
            .map { sha256Hex(it.toByteArray()) }
            .toSet()
    }

    private fun TrustDecisionWire.toManagedDeviceSecurityState(): ManagedDeviceSecurityState = if (automaticLicenseErrorCode != null) {
        ManagedDeviceSecurityState.ACTION_REQUIRED
    } else {
        managedDeviceSecurityState(
        trustState = trustState,
        locationEvidenceState = locationEvidenceState,
        managedLicenseState = managedLicenseState,
        )
    }

    private fun createClient(): OkHttpClient {
        check(configuration.isConfigured)
        val host = checkNotNull(URI(configuration.baseUrl).host)
        return OkHttpClient.Builder()
            .certificatePinner(CertificatePinner.Builder().add(host, configuration.certificatePin).build())
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun kmsState(): String {
        val configuration = kmsConfigurationProvider.configuration() ?: return "NOT_CONFIGURED"
        val transportPinned = configuration.baseUrl.startsWith("https://") &&
            configuration.certificatePinsSha256.isNotEmpty() &&
            configuration.certificatePinsSha256.all { it.startsWith("sha256/") } &&
            !configuration.allowCleartextForTests
        return if (transportPinned && configuration.signingPublicKeyDer.isNotEmpty()) {
            "PINNED_AND_SIGNED"
        } else {
            "INVALID_CONFIGURATION"
        }
    }

    override suspend fun policyApplication(): ManagementPolicyApplication? = withContext(Dispatchers.IO) {
        repository.latestPolicyApplication()?.toApiPolicyApplication()
    }

    override suspend fun stagePolicyCleanup(
        version: Long,
        operations: List<PolicyCleanupOperation>,
    ): ManagementPolicyApplication = withContext(Dispatchers.IO) {
        require(version > 0)
        val persisted = operations
            .sortedBy(PolicyCleanupOperation::operationId)
            .map { operation ->
                require(operation.state == PolicyCleanupOperationState.PENDING)
                PersistedPolicyCleanupOperationWire(
                    operationId = operation.operationId,
                    kind = operation.kind,
                    state = operation.state.name,
                )
            }
        repository.stagePolicyCleanup(
            version = version,
            applyHandshakeAtEpochMillis = System.currentTimeMillis(),
            operations = persisted,
        ).toApiPolicyApplication()
    }

    override suspend fun completePolicyCleanup(
        version: Long,
        operationId: String,
    ): ManagementPolicyApplication = withContext(Dispatchers.IO) {
        repository.completePolicyCleanup(version, operationId).toApiPolicyApplication()
    }

    override suspend fun preparePolicyApplicationReceipt(evidence: PolicyApplicationEvidence): Boolean =
        withContext(Dispatchers.IO) {
            val application = checkNotNull(repository.latestPolicyApplication()) {
                "No verified R05 policy application is available."
            }
            application.pendingReceipt?.let { pending ->
                return@withContext policyApplicationReceiptMatchesEvidence(pending, application, evidence)
            }
            application.deliveredReceipt
                ?.takeIf { delivered -> policyApplicationReceiptMatchesEvidence(delivered, application, evidence) }
                ?.let { return@withContext true }
            val bootCount = deviceRuntimeBindingProvider.bootCount()
            check(bootCount >= 0) { "Android boot identity is unavailable for policy application evidence." }
            val trust = deviceTrustService.current()
            val unsigned = buildUnsignedPolicyApplicationReceipt(
                application = application,
                evidence = evidence,
                installationId = trust.installationId,
                bootCount = bootCount,
                nonce = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes).toBase64Url(),
            )
            val signature = deviceTrustService
                .signControlStatement(canonicalPolicyApplicationReceiptPayload(unsigned, signatureVerifier))
                .signature
                .toBase64()
            repository.persistPolicyApplicationReceipt(unsigned.copy(deviceSignature = signature))
            true
        }

    private suspend fun prepareTerminalPolicyApplicationReceipt(application: PersistedPolicyApplicationWire) {
        if (application.pendingReceipt != null || application.deliveredReceipt != null) return
        val state = LocalPolicyApplicationState.valueOf(application.state)
        if (state !in setOf(LocalPolicyApplicationState.BLOCKED_VERSION, LocalPolicyApplicationState.FAILED)) return
        val statement = json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        preparePolicyApplicationReceipt(
            PolicyApplicationEvidence(
                version = statement.version,
                appliedAtEpochMillis = null,
                enforcedCapabilities = emptyList(),
                refusedCapabilities = statement.requiredCapabilities,
                result = ManagementPolicyApplicationState.valueOf(state.name),
            )
        )
    }

    private suspend fun sendAndValidatePolicyApplicationReceipt(receipt: PolicyApplicationReceiptWire) {
        val application = checkNotNull(repository.latestPolicyApplication()) {
            "No verified R05 policy application exists for pending delivery."
        }
        check(application.pendingReceipt == receipt) {
            "Pending R05 policy application receipt changed before delivery."
        }
        val response = try {
            executeSignedPost(
                path = POLICY_APPLICATION_RECEIPT_PATH,
                body = json.encodeToString(receipt).encodeToByteArray(),
                bindLifecycle = false,
            )
        } catch (failure: HttpStatusException) {
            if (isTerminalPolicyApplicationReceiptServerRejection(failure.statusCode)) {
                throw PolicyApplicationReceiptDeliveryRejectedException(
                    "Control Plane rejected the R05 policy application receipt.",
                    failure,
                )
            }
            throw failure
        }
        val acknowledgement = try {
            json.decodeFromString(
                PolicyApplicationReceiptAcknowledgementWire.serializer(),
                response.decodeToString(),
            )
        } catch (failure: SerializationException) {
            throw PolicyApplicationReceiptDeliveryRejectedException(
                "Control Plane returned a malformed R05 policy application acknowledgement.",
                failure,
            )
        }
        try {
            validatePolicyApplicationReceiptAcknowledgement(
                receipt = receipt,
                application = application,
                acknowledgement = acknowledgement,
                verifier = signatureVerifier,
            )
        } catch (failure: IllegalStateException) {
            throw PolicyApplicationReceiptDeliveryRejectedException(
                "Control Plane returned a mismatched R05 policy application acknowledgement.",
                failure,
            )
        }
    }

    private fun supportedR05PolicyCapabilities(): Set<String> = buildSet {
        // CAPTURE_BLOCK is enforced entirely on-device by the existing screen-capture policy path.
        add(CAPABILITY_CAPTURE_BLOCK)
        // CONTENT_ENVELOPE crosses the client/KMS boundary. Do not advertise it from client code
        // alone: the pinned and signed KMS configuration is part of the capability precondition.
        if (kmsState() == "PINNED_AND_SIGNED") add(CAPABILITY_CONTENT_ENVELOPE)
    }

    private fun PersistedPolicyApplicationWire.toApiPolicyApplication(): ManagementPolicyApplication {
        val statement = json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            publication.publication,
        )
        return ManagementPolicyApplication(
            operationId = statement.operationId,
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            effectiveAtEpochMillis = statement.effectiveAt,
            requiredCapabilities = statement.requiredCapabilities,
            state = ManagementPolicyApplicationState.valueOf(state),
            cleanupOperations = cleanupOperations.map { operation ->
                PolicyCleanupOperation(
                    operationId = operation.operationId,
                    kind = operation.kind,
                    state = PolicyCleanupOperationState.valueOf(operation.state),
                )
            },
            applyHandshakeAtEpochMillis = applyHandshakeAtEpochMillis,
        )
    }

    private fun DeviceAttestationEvidence.toEnrollmentWire(
        challengeId: String,
        contentShareKey: DeviceShareKeyDescriptor,
    ): EnrollmentWire = EnrollmentWire(
        challengeId = challengeId,
        installationId = installationId,
        attestationVersion = attestationVersion,
        challengeSha256Base64 = challengeSha256.toBase64(),
        statementBase64 = statement.toBase64(),
        signedStatementBase64 = signedStatement.toBase64(),
        certificateChainDerBase64 = certificateChainDer.map { it.toBase64() },
        hardwareSecurityLevel = hardwareSecurityLevel.name,
        appPackageName = appPackageName,
        appVersionCode = appVersionCode,
        appSigningCertificateSha256Base64 = appSigningCertificateSha256.map { it.toBase64() },
        riskSignals = riskSignals.map { it.name }.sorted(),
        contentShareKeyId = contentShareKey.keyId,
        contentShareAlgorithm = contentShareKey.algorithm,
        contentSharePublicKeyDerBase64 = contentShareKey.publicKeyDer.toBase64(),
        deviceManufacturer = Build.MANUFACTURER.ifBlank { "UNKNOWN" },
        deviceModel = Build.MODEL.ifBlank { "UNKNOWN" },
        osVersion = Build.VERSION.RELEASE.ifBlank { "UNKNOWN" },
        apiLevel = Build.VERSION.SDK_INT,
    )

    private fun LifecycleOperationWire.toApi(): LifecycleOperation = LifecycleOperation(
        schemaVersion = schemaVersion,
        catalogId = catalogId,
        operationId = operationId,
        lifecycleId = FullAppLifecycleId.fromWireValue(lifecycleId),
        idempotencyKey = idempotencyKey,
        subjects = LifecycleSubjectHashes(
            accountIdHash = subjects.accountIdHash,
            installationId = subjects.installationId,
            matrixDeviceIdHash = subjects.matrixDeviceIdHash,
            roomIdHash = subjects.roomIdHash,
            eventIdHash = subjects.eventIdHash,
        ),
        status = LifecycleOperationStatus.valueOf(state),
        currentPhase = LifecyclePhase.valueOf(currentPhase),
        authorityRevisions = authorityRevisions,
        steps = steps.map { step ->
            LifecycleStep(
                stepId = step.stepId,
                component = LifecycleComponent.valueOf(step.component),
                required = step.required,
                status = LifecycleStepStatus.valueOf(step.state),
                attemptCount = step.attemptCount,
                authorityRevision = step.authorityRevision,
                resultCode = step.resultCode,
                resultReference = step.resultReference,
                receiptReference = step.receiptReference,
                updatedAtEpochMillis = step.updatedAtEpochMillis,
            )
        },
        deadlineEpochMillis = deadlineEpochMillis,
        nextReconcileAtEpochMillis = nextReconcileAtEpochMillis,
        compensationStatus = LifecycleCompensationStatus.valueOf(compensationState),
        terminalReceiptId = terminalReceiptId,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun AppOperationalSnapshotWire.toApi(): AppOperationalSnapshot = AppOperationalSnapshot(
        schemaVersion = schemaVersion,
        catalogId = catalogId,
        accountIdHash = accountIdHash,
        installationId = installationId,
        matrixDeviceIdHash = matrixDeviceIdHash,
        issuedAtEpochMillis = issuedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        overallStatus = AppOperationalStatus.valueOf(overallState),
        components = components.mapValues { (_, component) ->
            ComponentOperationalState(
                status = AppOperationalStatus.valueOf(component.state),
                authority = component.authority,
                authorityRevision = component.authorityRevision,
                observedAtEpochMillis = component.observedAtEpochMillis,
                expiresAtEpochMillis = component.expiresAtEpochMillis,
                reasonCode = component.reasonCode,
                operationId = component.operationId,
                receiptReference = component.receiptReference,
            )
        },
        activeOperationIds = activeOperationIds,
        blockers = blockers,
        signingKeyId = signingKeyId,
        signatureBase64 = signatureBase64,
    )

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun ByteArray.toBase64Url(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    private fun deviceCorrelationIdSha256(): String? {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != INVALID_ANDROID_ID }
            ?: return null
        return sha256Hex("$DEVICE_CORRELATION_DOMAIN\u0000${context.packageName}\u0000$androidId".encodeToByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class EnrollmentRequiredException : Exception()
    private class HttpStatusException(
        val statusCode: Int,
        val safeDetail: String? = null,
        val retryAfterMillis: Long? = null,
    ) : Exception("HTTP $statusCode${safeDetail?.let { ": $it" }.orEmpty()}")
    private class ManagementSyncStageException(
        val errorCode: String,
        cause: Throwable,
    ) : IllegalStateException(errorCode, cause)

    private companion object {
        const val SYNC_PATH = "/v1/device/sync"
        const val POLICY_APPLICATION_RECEIPT_PATH = "/v2/policy-application-receipts"
        const val HEARTBEAT_PATH = "/v1/device/heartbeat"
        const val OFFICE_PREVIEW_GRANT_PATH = "/v1/office-preview/grants"
        const val ACCOUNT_REACTIVATION_PREPARE_PATH = "/v1/account-reactivation/prepare"
        const val ACCOUNT_REACTIVATION_COMMIT_PATH = "/v1/account-reactivation/commit"
        const val ACCOUNT_DELETION_PREPARE_PATH = "/v1/account-deletion/prepare"
        const val ACCOUNT_DELETION_PENDING_PATH = "/v1/account-deletion/pending"
        const val ACCOUNT_DELETION_RECOVER_PATH = "/v1/account-deletion/recover"
        const val ACCOUNT_DELETION_COMMIT_PATH = "/v1/account-deletion/commit"
        const val ACCOUNT_DELETION_OPERATIONS_PATH = "/v1/account-deletions/operations"
        const val GLOBAL_DESTROY_NOT_TERMINAL_DETAIL = "account destruction has not reached terminal state"
        const val ENROLL_PATH = "/v1/device/enroll"
        const val LOCATION_EVIDENCE_PATH = "/v1/device/location"
        const val COMMAND_REPORT_PATH = "/v1/device/commands/report"
        const val PUBLIC_APP_RELEASE_PATH = "/v1/public/app/releases/latest"
        const val ONBOARDING_INVITATIONS_PATH = "/v1/onboarding/invitations"
        const val ONBOARDING_VERIFY_PATH = "/v1/onboarding/invitations/verify"
        const val ONBOARDING_USERNAME_PATH = "/v1/onboarding/usernames/suggest"
        const val ONBOARDING_ACTIVATE_PATH = "/v1/onboarding/activate"
        const val APP_OPERATIONAL_SNAPSHOT_PATH = "/v1/app-state/snapshot"
        const val LIFECYCLE_OPERATIONS_PATH = "/v1/lifecycle/operations"
        const val RELEASE_CDN_HOST = "download.yizhesy.cn"
        const val RELEASE_FALLBACK_HOST = "security.yizhesy.cn"
        const val RELEASE_APK_PATH_PREFIX = "/v1/app/releases/files/"
        const val APP_RELEASE_DOWNLOAD_ATTEMPTS = 3
        const val SECURITY_PROTOCOL_EPOCH = 3
        const val CAPABILITY_CAPTURE_BLOCK = "CAPTURE_BLOCK"
        const val CAPABILITY_CONTENT_ENVELOPE = "CONTENT_ENVELOPE"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1_024
        const val DOWNLOAD_PROGRESS_REPORT_BYTES = 256 * 1_024L
        const val NONCE_LENGTH_BYTES = 24
        const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
        const val MAX_TICKET_TTL_MILLIS = 60 * 60 * 1_000L
        const val MAX_OFFICE_PREVIEW_GRANT_MILLIS = 5 * 60 * 1_000L
        const val MAX_OFFICE_PREVIEW_SESSION_MILLIS = 60 * 60 * 1_000L
        const val MAX_ACCOUNT_DELETION_PREPARE_MILLIS = 10 * 60 * 1_000L
        const val MATRIX_NOT_DEACTIVATED_DETAIL = "Matrix account deactivation has not been verified"
        const val DELETION_PREPARATION_EXPIRED_DETAIL = "account deletion intent expired before Matrix deactivation"
        const val MIN_HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val MAX_HEARTBEAT_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val MIN_HEARTBEAT_GRACE_MILLIS = 60_000L
        const val MAX_HEARTBEAT_GRACE_MILLIS = 10 * 60 * 1_000L
        const val ERROR_SECURITY_REJECTED = "CONTROL_SECURITY_REJECTED"
        const val ERROR_NETWORK_RETRY = "CONTROL_SYNC_RETRY_REQUIRED"
        const val ERROR_SYNC_ENVELOPE = "CONTROL_SYNC_ENVELOPE_REJECTED"
        const val ERROR_SYNC_TIME_STATE = "CONTROL_SYNC_TIME_STATE_REJECTED"
        const val ERROR_SYNC_TRUST_DECISION = "CONTROL_SYNC_TRUST_DECISION_REJECTED"
        const val ERROR_SYNC_ACCESS_TICKET = "CONTROL_SYNC_ACCESS_TICKET_REJECTED"
        const val ERROR_SYNC_POLICY = "CONTROL_SYNC_POLICY_REJECTED"
        const val ERROR_SYNC_TRUST_SNAPSHOT = "CONTROL_SYNC_TRUST_SNAPSHOT_REJECTED"
        const val ERROR_SYNC_ACCOUNT_TOMBSTONES = "CONTROL_SYNC_ACCOUNT_TOMBSTONES_REJECTED"
        const val ERROR_SYNC_COMMAND = "CONTROL_SYNC_COMMAND_REJECTED"
        const val DIAGNOSTIC_TAG = "YiZheManagement"
        const val MAX_DIAGNOSTIC_CAUSES = 6
        const val DEVICE_CORRELATION_DOMAIN = "yizhechat-device-correlation-v1"
        const val INVALID_ANDROID_ID = "9774d56d682e549c"
        val SAFE_SERVER_DETAIL = Regex("[A-Za-z0-9 ._-]{1,128}")
        val ACCOUNT_HASH_PATTERN = Regex("[0-9a-f]{64}")
        val BUILD_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val CLIENT_SCOPE_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
        val ACTIVATION_ID_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
        val DELETION_ID_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
        val CONFIRMATION_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
        val CONTENT_RANGE_PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        val PUBLIC_INVITATION_ID_PATTERN = Regex("[A-Za-z0-9_-]{20,64}")
        val INVITATION_CODE_PATTERN = Regex("[0-9]{6}")
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
        val LOCALPART_PATTERN = Regex("yizhe[a-z0-9]{2,58}")
        val MATRIX_USER_ID_PATTERN = Regex("@[a-z0-9._=-]{1,64}:[a-z0-9.-]{1,255}")
        val OFFICIAL_INVITATION_HOSTS = setOf("www.yizhesy.cn", "yizhesy.cn")
        val LIFECYCLE_OPERATION_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,256}")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal class PolicyApplicationReceiptDeliveryRejectedException(
    message: String,
    cause: Throwable,
) : SecurityException(message, cause)

/**
 * A 2xx response is not application success. Only a content-bound acknowledgement may retire the
 * durable pending receipt. Client faults are terminal security rejections; transient transport and
 * server faults retain the same signed nonce for a later synchronization retry.
 */
internal fun isTerminalPolicyApplicationReceiptServerRejection(statusCode: Int): Boolean =
    statusCode in 400..499 && statusCode != 408 && statusCode != 429

internal fun managementSyncFailureReasonCode(failure: Throwable): String? {
    val chain = generateSequence(failure) { it.cause }.take(8).toList()
    return when {
        chain.any { it is UnknownHostException } -> "CONTROL_DNS_UNAVAILABLE"
        chain.any { it is ConnectException } -> "CONTROL_CONNECTION_FAILED"
        chain.any { it is SocketTimeoutException } -> "CONTROL_REQUEST_TIMEOUT"
        chain.any { it is SSLException } -> "CONTROL_TLS_REJECTED"
        chain.any { it is GeneralSecurityException } -> "DEVICE_SIGNING_KEY_UNAVAILABLE"
        chain.any { it.javaClass.simpleName == "HttpStatusException" } -> "CONTROL_SERVER_REJECTED"
        chain.any { it is IOException } -> "CONTROL_NETWORK_IO"
        else -> null
    }
}

/**
 * Enrollment is a trust mutation. It may only follow the Control Plane's stable
 * protocol code, never a generic HTTP status or untrusted human-readable text.
 */
internal fun isStableEnrollmentBootstrapFailure(statusCode: Int, reasonCode: String?): Boolean =
    statusCode == 401 && reasonCode in setOf("DEVICE_NOT_ENROLLED", "DEVICE_ATTESTATION_REFRESH_REQUIRED")

/**
 * A deletion-scoped bearer is not an account access token. Its entire authorization surface is one
 * idempotent CLIENT_CLEAR receipt endpoint for the operation embedded in the capability.
 */
internal fun isDeletionScopedCapabilityUseAllowed(method: String, path: String, operationId: String): Boolean =
    method == "POST" && path == "/v1/account-deletions/operations/$operationId/client-receipts"

internal fun canonicalDeletionClientReceiptPayload(receipt: PermanentAccountDeletionClientReceipt): ByteArray =
    buildString {
        append("{\"attestation_evidence_hash\":\"")
        append(receipt.attestationEvidenceHash)
        append("\",\"installation_id\":\"")
        append(receipt.installationId)
        append("\",\"manifest_digest_sha256\":\"")
        append(receipt.manifestDigestSha256)
        append("\",\"occurred_at_epoch_millis\":")
        append(receipt.occurredAtEpochMillis)
        append(",\"operation_id\":\"")
        append(receipt.operationId)
        append("\",\"protocol_version\":1,\"resource_scope_hash\":\"")
        append(receipt.resourceScopeHash)
        append("\"}")
    }.encodeToByteArray()

internal fun isPermanentDeletionSubmissionBlocked(statusCode: Int, body: ByteArray): Boolean {
    if (statusCode != 503) return false
    return runCatchingExceptions {
        Json.parseToJsonElement(body.decodeToString()).jsonObject["detail"]
            ?.jsonObject?.get("code")?.jsonPrimitive?.content == "LC22_UPGRADE_IN_PROGRESS"
    }.getOrDefault(false)
}

internal enum class DeviceEnrollmentDecision {
    ENROLL,
    SKIP,
    BLOCK,
}

/**
 * Local clean-data enrollment is allowed only for an explicit NOT_ENROLLED state. A remote
 * re-enrollment is allowed only after the authenticated request received a stable enrollment
 * or upgrade-attestation reason. A version advance means another caller completed the same work.
 */
internal fun deviceEnrollmentDecision(
    forceRemoteEnrollment: Boolean,
    observedAttestationVersion: Long,
    currentAttestationVersion: Long,
    currentTrustState: DeviceTrustState,
): DeviceEnrollmentDecision {
    require(observedAttestationVersion >= 0 && currentAttestationVersion >= 0)
    if (currentAttestationVersion > observedAttestationVersion) return DeviceEnrollmentDecision.SKIP
    if (currentTrustState in setOf(
            DeviceTrustState.INTEGRITY_WARNING,
            DeviceTrustState.REVOKED,
            DeviceTrustState.LOST,
        )
    ) {
        return DeviceEnrollmentDecision.BLOCK
    }
    if (forceRemoteEnrollment) return DeviceEnrollmentDecision.ENROLL
    return if (
        currentTrustState == DeviceTrustState.NOT_ENROLLED && currentAttestationVersion == 0L
    ) {
        DeviceEnrollmentDecision.ENROLL
    } else {
        DeviceEnrollmentDecision.SKIP
    }
}

internal fun ManagementHeartbeatReport.withPolicyRefreshStatus(localPolicyVersion: Long?): ManagementHeartbeatReport {
    val remotePolicyVersion = serverPolicyVersion ?: return this
    if (localPolicyVersion != null && remotePolicyVersion < localPolicyVersion) {
        throw SecurityException("Management policy version moved backwards.")
    }
    // Access revocation and re-authentication are stronger than a policy refresh. A newer policy version
    // must never turn either signed access state back into an ACTIVE-like heartbeat result.
    if (status != ManagementHeartbeatStatus.ACTIVE) return this
    return if (localPolicyVersion == null || remotePolicyVersion > localPolicyVersion) {
        copy(status = ManagementHeartbeatStatus.POLICY_REFRESH_REQUIRED)
    } else {
        this
    }
}

internal fun managedDeviceSecurityState(
    trustState: String,
    locationEvidenceState: String,
    managedLicenseState: String?,
): ManagedDeviceSecurityState = when {
    trustState in setOf("TRUSTED", "MANAGED") && locationEvidenceState == "ACCEPTED" -> ManagedDeviceSecurityState.READY
    locationEvidenceState != "ACCEPTED" -> ManagedDeviceSecurityState.LOCATION_REQUIRED
    trustState == "PENDING" && managedLicenseState == "PENDING" -> ManagedDeviceSecurityState.MANAGED_APPROVAL_PENDING
    trustState == "PENDING" -> ManagedDeviceSecurityState.VALIDATION_PENDING
    else -> ManagedDeviceSecurityState.ACTION_REQUIRED
}

internal fun aggregateMatrixIdentityState(statuses: List<SessionVerifiedStatus>): String = when {
    statuses.isEmpty() -> "UNKNOWN"
    statuses.all { it is SessionVerifiedStatus.Verified } -> "VERIFIED"
    statuses.any { it is SessionVerifiedStatus.NotVerified } -> "UNVERIFIED"
    else -> "UNKNOWN"
}

internal fun managementSyncStatusForRestriction(reason: String?): ManagementSyncStatus = when (reason) {
    null -> ManagementSyncStatus.SYNCHRONIZED
    "REGISTRATION_PENDING" -> ManagementSyncStatus.SYNCHRONIZED
    else -> ManagementSyncStatus.SECURITY_REJECTED
}

internal fun onboardingActivationError(status: Int, detail: String?): Throwable? = when {
    status == 401 && detail == "activation session has expired" -> io.element.android.security.dataprotection.api.RegistrationActivationExpired()
    detail == "REGISTRATION_AGREEMENT_REQUIRED" -> io.element.android.security.dataprotection.api.RegistrationAgreementRequired()
    detail == "REGISTRATION_AGREEMENT_CHANGED" -> io.element.android.security.dataprotection.api.RegistrationAgreementChanged()
    status == 503 && detail in setOf("REGISTRATION_PENDING", "account creation is temporarily unavailable") ||
        status == 409 && detail == "registration is already being processed" -> io.element.android.security.dataprotection.api.RegistrationPending()
    status == 403 && detail == "REGISTRATION_REJECTED" ||
        status == 409 && detail == "invitation is no longer available" -> io.element.android.security.dataprotection.api.RegistrationRejected()
    else -> null
}

internal fun parseRetryAfterMillis(value: String?, nowEpochMillis: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim() ?: return null
    val seconds = raw.toLongOrNull()
    if (seconds != null) return runCatching { Math.multiplyExact(seconds.coerceAtLeast(0), 1_000L) }.getOrDefault(Long.MAX_VALUE)
    return runCatching {
        (java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowEpochMillis)
            .coerceAtLeast(0)
    }.getOrNull()
}
