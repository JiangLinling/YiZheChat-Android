/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

enum class ManagementCommandType {
    LOCK,
    REVOKE,
    CLEAR_ACCOUNT,
    CLEAR_CONTENT,
}

data class VerifiedManagementCommand(
    val commandId: String,
    val installationId: String,
    val type: ManagementCommandType,
    val accountIdHash: String?,
    val contentId: String?,
    val version: Long,
    val issuedAtEpochMillis: Long,
    val notBeforeEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val supersedesCommandId: String? = null,
    val targetMatrixDeviceIdHash: String? = null,
    val mobileGeneration: Long? = null,
    val reasonCode: String? = null,
) {
    fun targetsAccountSession(accountHash: String, matrixDeviceHash: String): Boolean =
        type == ManagementCommandType.CLEAR_ACCOUNT && accountIdHash == accountHash &&
            (targetMatrixDeviceIdHash == null || targetMatrixDeviceIdHash == matrixDeviceHash)
}

enum class ManagementCommandState {
    RECEIVED,
    IN_PROGRESS,
    WAITING_RESTART,
    COMPLETE,
    FAILED,
}

data class ManagementCommandExecutionReport(
    val commandId: String,
    val commandType: ManagementCommandType,
    val state: ManagementCommandState,
    val progress: Int,
    val errorCode: String? = null,
) {
    init {
        require(progress in 0..100)
        require(errorCode == null || errorCode.matches(Regex("[A-Z0-9_]{1,128}")))
    }
}

enum class ManagementSyncStatus {
    DISABLED,
    SYNCHRONIZED,
    RETRY_REQUIRED,
    SECURITY_REJECTED,
}

enum class StartupSecurityReadinessState {
    UNKNOWN,
    BOOTSTRAPPING,
    READY_FOR_LC01,
    RETRYABLE_FAILURE,
    SECURITY_REJECTED,
    DISABLED,
}

/**
 * A short-lived, client-side projection of the verified management state required before LC-01.
 * It is coordination state only; policy, trust, release and lifecycle ledgers remain authoritative
 * in their existing services.
 */
data class StartupSecurityReadiness(
    val state: StartupSecurityReadinessState,
    val reasonCode: String? = null,
    val operationId: String? = null,
    val policyVersion: Long? = null,
    val installationId: String? = null,
    val deviceSecurityState: ManagedDeviceSecurityState? = null,
    val observedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val retryAtEpochMillis: Long? = null,
    val offlineEligible: Boolean = false,
) {
    init {
        require(observedAtEpochMillis > 0) { "Readiness observation time is invalid." }
        require(reasonCode == null || reasonCode.matches(Regex("[A-Z0-9_]{1,128}"))) {
            "Readiness reason code is invalid."
        }
        require(operationId == null || operationId.length in 16..256) {
            "Readiness operation identity length is invalid."
        }
        require(policyVersion == null || policyVersion >= 0) { "Readiness policy version is invalid." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= observedAtEpochMillis) {
            "Readiness expiry precedes its observation."
        }
        require(retryAtEpochMillis == null || retryAtEpochMillis >= observedAtEpochMillis) {
            "Readiness retry time precedes its observation."
        }
        require(!offlineEligible || state == StartupSecurityReadinessState.RETRYABLE_FAILURE) {
            "Only a retryable readiness result may authorize bounded offline startup."
        }
    }

    fun isFresh(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis

    /**
     * Returns the consumable projection. An expired READY projection is explicitly downgraded
     * before any caller can use it, so expiry cannot be represented as a stale READY decision.
     */
    fun effectiveFor(nowEpochMillis: Long = System.currentTimeMillis()): StartupSecurityReadiness =
        if (state == StartupSecurityReadinessState.READY_FOR_LC01 && !isFresh(nowEpochMillis)) {
            copy(
                state = StartupSecurityReadinessState.UNKNOWN,
                reasonCode = "STARTUP_READINESS_EXPIRED",
                expiresAtEpochMillis = null,
                offlineEligible = false,
            )
        } else {
            this
        }

    fun canStartLc01(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        effectiveFor(nowEpochMillis).state == StartupSecurityReadinessState.READY_FOR_LC01
}

enum class ManagementHeartbeatStatus {
    DISABLED,
    ACTIVE,
    POLICY_REFRESH_REQUIRED,
    REAUTH_REQUIRED,
    REVOKED,
    SECURITY_REJECTED,
    RETRY_REQUIRED,
}

enum class DeviceLocationEvidenceState {
    REQUIRED,
    ACCEPTED,
    REFRESH_REQUIRED,
}

enum class ManagedDeviceSecurityState {
    LOCATION_REQUIRED,
    VALIDATION_PENDING,
    READY,
    ACTION_REQUIRED,
    MANAGED_APPROVAL_PENDING,
}

data class DeviceLocationEvidence(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochMillis: Long,
    val provider: String,
    val isMock: Boolean,
    val timezoneOffsetMinutes: Int,
)

data class ManagementSyncReport(
    val status: ManagementSyncStatus,
    val acceptedPolicyVersion: Long? = null,
    val pendingCommandCount: Int = 0,
    val errorCode: String? = null,
    val deviceSecurityState: ManagedDeviceSecurityState? = null,
    val locationEvidenceState: DeviceLocationEvidenceState? = null,
    val heartbeatIntervalMillis: Long? = null,
    val retryAfterMillis: Long? = null,
    val accountHandoverRequiredAccountHashes: Set<String> = emptySet(),
)

enum class ManagementPolicyApplicationState {
    PENDING_EFFECTIVE,
    PENDING_APPLY,
    BLOCKED_VERSION,
    FAILED,
    APPLIED_CLEANUP_PENDING,
    APPLIED,
}

enum class PolicyCleanupOperationState {
    PENDING,
    COMPLETE,
}

data class PolicyCleanupOperation(
    val operationId: String,
    val kind: String,
    val state: PolicyCleanupOperationState = PolicyCleanupOperationState.PENDING,
) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9._:-]{8,256}")))
        require(kind.matches(Regex("[A-Z0-9_]{1,128}")))
    }
}

/**
 * Local, verified R05 application work. This is coordination state, not a server receipt.
 */
data class ManagementPolicyApplication(
    val operationId: String,
    val policyDomain: String,
    val version: Long,
    val digest: String,
    val effectiveAtEpochMillis: Long,
    val requiredCapabilities: List<String>,
    val state: ManagementPolicyApplicationState,
    val cleanupOperations: List<PolicyCleanupOperation> = emptyList(),
    val applyHandshakeAtEpochMillis: Long? = null,
)

data class PolicyApplicationEvidence(
    val version: Long,
    val appliedAtEpochMillis: Long?,
    val enforcedCapabilities: List<String>,
    val refusedCapabilities: List<String>,
    val result: ManagementPolicyApplicationState,
) {
    init {
        require(result in setOf(
            ManagementPolicyApplicationState.APPLIED,
            ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
            ManagementPolicyApplicationState.BLOCKED_VERSION,
            ManagementPolicyApplicationState.FAILED,
        ))
    }
}

data class ManagementHeartbeatReport(
    val status: ManagementHeartbeatStatus,
    val nextHeartbeatDelayMillis: Long = 60_000,
    val serverPolicyVersion: Long? = null,
)

data class OfficePreviewGrantRequest(
    val accountIdHash: String,
    val fileSha256: String,
    val fileSizeBytes: Long,
    val fileExtension: String,
) {
    init {
        require(accountIdHash.matches(Regex("[0-9a-f]{64}")))
        require(fileSha256.matches(Regex("[0-9a-f]{64}")))
        require(fileSizeBytes in 1..20L * 1024L * 1024L)
        require(fileExtension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx"))
    }
}

data class SignedOfficePreviewGrant(
    /** Exact signed envelope forwarded to MindOrigin. It must never be logged or persisted. */
    val envelopeJson: String,
    val expiresAtEpochMillis: Long,
    val previewExpiresAtEpochMillis: Long,
)

data class AccountReactivationRequest(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val clientScopeId: String,
    val clientGeneration: Long,
    val scopeStartedAtEpochMillis: Long,
    val idempotencyKey: String,
    /**
     * True only after the user completed Matrix's explicit destructive identity-reset flow.
     *
     * The DAK-signed PREPARE request carries this assertion to the control plane. It never
     * restores old history; it lets the signed activation receipt close that history as
     * permanently unverifiable while allowing the new identity to become CORE_READY.
     */
    val explicitIdentityResetConfirmed: Boolean = false,
)

/**
 * Durable client-side identity for one account activation attempt.
 *
 * The generation and idempotency key are persisted before any PREPARE request is sent. This keeps
 * delayed callbacks and process recreation from reusing an older logical activation attempt.
 */
data class AccountReactivationAttempt(
    val accountIdHash: String,
    val installationId: String,
    val matrixDeviceIdHash: String,
    val clientScopeId: String,
    val clientGeneration: Long,
    val scopeStartedAtEpochMillis: Long,
    val idempotencyKey: String,
)

enum class AccountReactivationCheckpointState {
    ALLOCATED,
    PREPARED,
    COMMITTED,
}

/**
 * Device-root-key protected progress for one activation transaction.
 *
 * A PREPARED checkpoint lets a restarted process retry the exact same server transaction. A
 * COMMITTED checkpoint contains only the already verified receipt and can be reused only after
 * the coordinator has independently rechecked every current security prerequisite.
 */
data class AccountReactivationCheckpoint(
    val attempt: AccountReactivationAttempt,
    val state: AccountReactivationCheckpointState,
    val prepared: PreparedAccountReactivation? = null,
    val receipt: AccountActivationReceipt? = null,
    val historyRecoveryGeneration: Long = 0L,
    /** SHA-256 room scopes only; raw room ids are never persisted here. */
    val pendingHistoryRoomScopeHashes: Set<String> = emptySet(),
    /**
     * Durable account-removal boundary for this installation.
     *
     * It is set before a user-initiated logout can destroy local account state and is cleared
     * only by a newly verified COMMITTED activation receipt. Process death therefore cannot
     * turn an interrupted re-login into the first-login fast path.
     */
    val reactivationRequired: Boolean = false,
    /** Survives process death until a signed activation receipt consumes this reset assertion. */
    val pendingExplicitIdentityReset: Boolean = false,
    /** Persisted before the destructive SDK call; completion requires a different identity. */
    val identityResetPreviousKeyHash: String? = null,
)

data class HistoryRecoveryAttempt(
    val accountIdHash: String,
    val installationId: String,
    val matrixDeviceIdHash: String,
    val generation: Long,
    val roomScopeHashes: Set<String>,
)

interface AccountReactivationScopeStore {
    /** Called only after the SDK cancels and the unchanged identity is independently read. */
    suspend fun cancelIdentityReset(accountIdHash: String, installationId: String, matrixDeviceIdHash: String, previousKeyHash: String): Boolean =
        error("Identity reset cancellation storage is unavailable")
    suspend fun beginIdentityReset(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        previousKeyHash: String,
    ): Boolean = error("Identity reset intent storage is unavailable")
    /** Returns the latest authenticated checkpoint for this exact account/installation/device. */
    suspend fun current(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationCheckpoint?

    /**
     * Returns whether this login must be held behind the signed CORE_READY gate.
     *
     * Existing authenticated sessions remain on the established startup path. An explicit
     * account-removal marker, a previous checkpoint for another Matrix device, or an
     * unauthenticated/corrupt retained checkpoint must never be treated as a fresh login.
     */
    suspend fun requiresCoreReadyEnforcement(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean

    /** Durably records normal account removal without erasing installation continuity. */
    suspend fun markAccountRemoved(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean

    /** Rolls back a removal marker only when the remote logout itself did not take effect. */
    suspend fun restoreAccountActive(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean

    /**
     * Records the successful end of Matrix's user-confirmed identity-reset flow.
     *
     * This operation also invalidates an older PREPARE generation. A reset can therefore never
     * accidentally commit a transaction that was prepared for the previous Matrix identity.
     */
    suspend fun markExplicitIdentityReset(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean

    /** Atomically persists and returns the next generation for this account/device scope. */
    suspend fun nextAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationAttempt

    /** Atomically advances ALLOCATED to PREPARED for the exact attempt. */
    suspend fun markPrepared(
        attempt: AccountReactivationAttempt,
        prepared: PreparedAccountReactivation,
    ): AccountReactivationCheckpoint

    /** Atomically advances PREPARED to COMMITTED for the exact verified receipt. */
    suspend fun markCommitted(
        attempt: AccountReactivationAttempt,
        receipt: AccountActivationReceipt,
    ): AccountReactivationCheckpoint

    /** Persists the next room-scoped history generation before the UI refresh signal is emitted. */
    suspend fun nextHistoryRecoveryAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        roomScopeIds: Set<String>,
    ): HistoryRecoveryAttempt

    /**
     * Irreversibly removes every local activation checkpoint for this account and installation.
     *
     * This is intentionally separate from logout: a normal logout may be followed by an approved reactivation,
     * while permanent account deletion must never leave a reusable CORE_READY receipt behind.
     */
    suspend fun clearPermanentlyDeletedAccount(
        accountIdHash: String,
        installationId: String,
    ): Boolean
}

/** The authority proved this prepare expired without committing; a new attempt is safe. */
class AccountActivationExpired : Exception("ACCOUNT_ACTIVATION_EXPIRED")

data class PreparedAccountReactivation(
    val activationId: String,
    val expiresAtEpochMillis: Long,
)

enum class AccountIdentityContinuity {
    /** No earlier signed identity baseline was available for this account. */
    FIRST_ACTIVATION,

    /** The current cross-signing identity matches an earlier trusted account activation. */
    CONTINUOUS,

    /** The user explicitly reset identity; pre-commit history is permanently unverifiable. */
    RESET_CONFIRMED,
}

data class AccountActivationReceipt(
    val activationId: String,
    val accountIdHash: String,
    val installationId: String,
    val matrixDeviceIdHash: String,
    val clientScopeId: String,
    val clientGeneration: Long,
    val attestationVersion: Long,
    val policyVersion: Long,
    val matrixCrossSigningKeyHash: String,
    val recipientKeyId: String,
    val identityContinuity: AccountIdentityContinuity = AccountIdentityContinuity.FIRST_ACTIVATION,
    /** Zero only for legacy schema v1/v2 receipts created before single-mobile generations existed. */
    val accountMobileGeneration: Long = 0,
    val committedAtEpochMillis: Long,
)

/** Session-scoped bridge called only after Matrix proves an explicit identity reset completed. */
interface AccountIdentityResetLifecycleRecorder {
    suspend fun cancelExplicitReset(): Result<Unit> = Result.success(Unit)
    suspend fun beginExplicitReset(): Result<String> = Result.failure(IllegalStateException("Reset intent recorder unavailable"))
    suspend fun resumeCompletedReset(): Result<Boolean> = Result.success(false)
    suspend fun recordExplicitReset(): Result<Unit>
}

data class AccountDeletionPreparationRequest(
    val accountIdHash: String,
    val idempotencyKey: String,
    val confirmationTokenSha256: String,
    val eraseData: Boolean,
)

data class RecoverableAccountDeletion(
    val operationId: String,
    val deletionIntentId: String,
    val accountIdHash: String,
    val installationId: String,
    val eraseData: Boolean,
    val freezeAtEpochMillis: Long,
)

data class AccountDeletionRecoveryRequest(
    val operationId: String,
    val accountIdHash: String,
    val idempotencyKey: String,
    val confirmationTokenSha256: String,
    val eraseData: Boolean,
)

data class PreparedAccountDeletion(
    val deletionIntentId: String,
    val accountIdHash: String,
    val installationId: String,
    val eraseData: Boolean,
    val preparedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

data class AccountDeletionReceipt(
    val deletionIntentId: String,
    val accountIdHash: String,
    val installationId: String,
    val globalDestroyJobId: String,
    val eraseData: Boolean,
    val committedAtEpochMillis: Long,
)

interface ManagementControlService {
    /** Exact single local session eligible for an explicit startup handover intent; never an access grant. */
    suspend fun managedAuthorizationHandoverSubjects(): LifecycleSubjectHashes? = null

    /** Explicit account-authenticated certificate transfer. Does not grant account or history access. */
    suspend fun transferManagedDeviceAuthorization(accountIdHash: String, matrixDeviceIdHash: String): Unit =
        throw UnsupportedOperationException("Managed authorization handover unavailable")

    suspend fun expireRecoveryOperation(record: RecoveryIntentRecord): Unit = throw UnsupportedOperationException("Recovery expiry unavailable")
    /** Signed read-only LC08 inspection; no KMS or client receipt is manufactured by this call. */
    suspend fun resolveRecoveryIntent(record: RecoveryIntentRecord): RecoveryIntentResolution =
        throw UnsupportedOperationException("Recovery intent resolution is unavailable")

    suspend fun recoveryOperationContext(record: RecoveryIntentRecord): RecoveryOperationContext =
        throw RecoveryIntentRejected("RECOVERY_CONTEXT_UNSUPPORTED")

    /**
     * Completes the installation-scoped device enrollment prerequisite when local trust has not
     * yet been enrolled. Implementations must coalesce concurrent enrollment attempts.
     */
    suspend fun ensureDeviceEnrollment() = Unit

    suspend fun synchronize(): ManagementSyncReport

    /** Returns only the locally verified R05 application record; it never implies server receipt delivery. */
    suspend fun policyApplication(): ManagementPolicyApplication? = null

    /**
     * Persists the exact-version apply handshake for cleanup identities already stored with policy acceptance.
     * Callers must replay that same durable operation set; they cannot derive or replace it after synchronization.
     */
    suspend fun stagePolicyCleanup(
        version: Long,
        operations: List<PolicyCleanupOperation>,
    ): ManagementPolicyApplication = throw UnsupportedOperationException("Policy cleanup staging is not available.")

    /** Marks one already-persisted cleanup identity terminal without creating an application receipt. */
    suspend fun completePolicyCleanup(version: Long, operationId: String): ManagementPolicyApplication =
        throw UnsupportedOperationException("Policy cleanup completion is not available.")

    /**
     * Constructs and durably stores a boot-bound, DAK-signed receipt locally. Implementations must not
     * transmit it as a side effect of this call.
     */
    suspend fun preparePolicyApplicationReceipt(evidence: PolicyApplicationEvidence): Boolean =
        throw UnsupportedOperationException("Policy application receipts are not available.")

    suspend fun appOperationalSnapshot(
        accountIdHash: String,
        matrixDeviceIdHash: String,
    ): AppOperationalSnapshot = throw UnsupportedOperationException("App operational snapshots are not available.")

    /** Installation-owned startup intents only. This index grants no account or content access. */
    suspend fun startupLifecycleOperations(
        accountIdHash: String,
        matrixDeviceIdHash: String,
        trigger: String,
        previousOperationId: String? = null,
    ): List<LifecycleOperation> = throw UnsupportedOperationException("Startup lifecycle reconciliation is not available.")

    /** Next uses the signed LC-12 create transport; legacy clients retain their Matrix SDK path. */
    val supportsManagedRoomCreation: Boolean get() = false

    suspend fun friendshipRemovalPreview(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String): FriendshipRemovalSnapshot? = null
    suspend fun beginFriendshipRemoval(snapshot: FriendshipRemovalSnapshot, rooms: Set<String>): FriendshipRemovalIntent =
        error("Friendship removal authority unavailable")
    suspend fun completeFriendshipRemoval(intent: FriendshipRemovalIntent): Unit = error("Friendship removal authority unavailable")

    suspend fun prepareRoomMembership(
        accountIdHash: String, matrixDeviceIdHash: String, roomId: String, targetUserId: String, action: String, reason: String? = null,
    ): RoomMembershipIntent? = null

    suspend fun createManagedRoom(request: ManagedRoomCreationRequest): String? = null

    /** Resolve the original LC11 generation; create uses managed-v1, never the SDK createRoom retry path. */
    suspend fun managedDirectRoom(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String, create: Boolean): ManagedDirectRoomResult? =
        error("Managed direct room authority unavailable")

    /** Changes the server restriction first. Matrix ignored-user data is a UI projection. */
    suspend fun setContactBlocked(accountIdHash: String, matrixDeviceIdHash: String, peerUserId: String, blocked: Boolean): Unit =
        error("Contact policy authority unavailable")

    /** Fresh signed contact check for call preparation; final RTC admission still owns authorization. */
    suspend fun requireContactRoomAllowed(accountIdHash: String, matrixDeviceIdHash: String, roomId: String): Unit =
        error("Contact policy authority unavailable")

    /** Signed terminal observation of the original call. This never grants a new admission. */
    suspend fun contactCallTerminationReason(accountIdHash: String, matrixDeviceIdHash: String, roomId: String, operationId: String): String? =
        error("Call status authority unavailable")

    suspend fun startLifecycleOperation(
        request: LifecycleOperationRequest,
    ): LifecycleOperation = throw UnsupportedOperationException("App lifecycle operations are not available.")

    /** Confirms only an immutable signed coordination timeout, never access or successful completion. */
    suspend fun verifyStartupCoordinationExpiry(operation: LifecycleOperation, trigger: String): Boolean = false

    suspend fun lifecycleOperation(operationId: String): LifecycleOperation =
        throw UnsupportedOperationException("App lifecycle operations are not available.")

    /** Only authorizes retransmission of the exact retained ciphertext; never grants a content key or receipt. */
    suspend fun authorizePreparedRelayRetry(query: ProtectedEventOriginQuery, operationId: String, roomId: String): Unit =
        error("Prepared relay retry authorization is unavailable.")

    suspend fun protectedEventOrigin(query: ProtectedEventOriginQuery): LifecycleOperationContext? = null

    /** Reconcile the original preparation using actual KMS destruction; never prepares or releases a key. */
    suspend fun reconcileRelayPreparation(query: ProtectedRelayPreparationQuery): LifecycleOperation =
        error("Original relay preparation reconciliation is unavailable.")

    suspend fun resumeLifecycleOperation(operationId: String): LifecycleOperation =
        throw UnsupportedOperationException("App lifecycle operations are not available.")

    suspend fun recordLifecycleClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation =
        throw UnsupportedOperationException("App lifecycle client receipts are not available.")

    /** Returns only after verifying the authority's acknowledgement of this exact immutable object fact. */
    suspend fun recordMediaObjectCleanup(fact: LifecycleMediaObjectCleanup): String =
        error("Media object cleanup acknowledgement is unavailable.")

    suspend fun reserveMediaResources(request: LifecycleMediaReservation): Unit = error("Media resource admission is unavailable.")
    suspend fun sealMediaResources(request: LifecycleMediaSeal): String = error("Media resource seal acknowledgement is unavailable.")
    suspend fun recordMediaRuntimeCleanup(fact: LifecycleMediaRuntimeCleanup): String = error("Media runtime acknowledgement is unavailable.")

    /** Lightweight foreground liveness check for an already issued, hardware-bound access ticket. */
    suspend fun heartbeat(): ManagementHeartbeatReport

    /** Uploads one user-consented location sample over a DAK-signed request. It does not enable background tracking. */
    suspend fun submitDeviceLocation(evidence: DeviceLocationEvidence): DeviceLocationEvidenceState

    /** True when the last signed management policy no longer permits this device to stay offline. */
    suspend fun isOfflineDeadlineExceeded(): Boolean

    /**
     * Returns the version of the currently usable server-signed policy from the verified local cache.
     * A missing value must never be treated as permission to start offline.
     */
    suspend fun cachedSignedPolicyVersion(): Long? = null

    /** Verified, subject-bound UI entry permission. It never grants an offline content key. */
    suspend fun offlineEntryStatus(): OfflineEntryStatus? = null

    suspend fun localListEntryStatus(): LocalChatListPermission? = null

    suspend fun pendingCommands(): List<VerifiedManagementCommand>

    /** UI explanation only, derived from a verified command for this exact retired login. */
    suspend fun isMobileSessionSuperseded(accountIdHash: String, matrixDeviceIdHash: String): Boolean = false

    suspend fun reportCommand(report: ManagementCommandExecutionReport): Boolean

    /** Returns only a server-signed, newer release that is valid for this installed package. */
    suspend fun latestAppRelease(): ManagedAppRelease?

    /** Returns the last still-valid server-signed release manifest without performing network I/O. */
    suspend fun cachedAppRelease(): ManagedAppRelease? = null

    /** True only when a still-valid signed bootstrap manifest is cached, including a manifest for this version. */
    suspend fun hasValidCachedAppReleaseManifest(): Boolean = false

    /** Downloads and verifies the digest, package identity, version and APK signing certificate. */
    suspend fun downloadAndVerifyAppRelease(
        release: ManagedAppRelease,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit = {},
    ): PreparedAppUpdate

    suspend fun issueGroupShare(userId: String, roomId: String): GroupShareCapability =
        throw UnsupportedOperationException("Group sharing is unavailable")
    suspend fun redeemGroupShare(userId: String, roomId: String, token: String): Unit =
        throw UnsupportedOperationException("Group sharing is unavailable")

    /** Creates a one-hour, single-use invitation from the currently trusted inviter device. */
    suspend fun createOnboardingInvitation(
        inviterUserId: String,
        generationRequestId: String? = null,
    ): OnboardingInvitation =
        throw UnsupportedOperationException("Onboarding invitations are not available.")

    /** Verifies an official invitation locator without registering a Matrix account. */
    suspend fun verifyOnboardingInvitation(publicId: String? = null, code: String? = null): VerifiedOnboardingInvitation =
        throw UnsupportedOperationException("Onboarding invitations are not available.")

    /** Returns a non-reserved preview; the activation endpoint remains authoritative under concurrency. */
    suspend fun suggestOnboardingUsername(
        activationSession: String,
        displayName: String,
        preferredStem: String,
    ): OnboardingUsernameSuggestion = throw UnsupportedOperationException("Onboarding invitations are not available.")

    /** Creates the account once and records the inviter friendship before normal Matrix login. */
    suspend fun activateOnboardingInvitation(
        activationSession: String,
        displayName: String,
        preferredStem: String,
        password: String,
        idempotencyKey: String,
        consent: RegistrationConsent? = null,
    ): OnboardingActivation = throw UnsupportedOperationException("Onboarding invitations are not available.")

    suspend fun revokeOnboardingInvitation(publicId: String, inviterUserId: String): OnboardingInvitation =
        throw UnsupportedOperationException("Onboarding invitations are not available.")

    /**
     * Creates a five-minute, purpose-bound grant for one ordinary Office attachment.
     * The control plane sees metadata only; document bytes go directly to the preview service.
     */
    suspend fun createOfficePreviewGrant(request: OfficePreviewGrantRequest): SignedOfficePreviewGrant =
        throw UnsupportedOperationException("Office preview grants are not available.")

    suspend fun prepareAccountReactivation(request: AccountReactivationRequest): PreparedAccountReactivation =
        throw UnsupportedOperationException("Account reactivation is not available.")

    suspend fun commitAccountReactivation(
        request: AccountReactivationRequest,
        prepared: PreparedAccountReactivation,
    ): AccountActivationReceipt = throw UnsupportedOperationException("Account reactivation is not available.")

    suspend fun preparePermanentAccountDeletion(
        request: AccountDeletionPreparationRequest,
    ): PreparedAccountDeletion = throw UnsupportedOperationException("Permanent account deletion is not available.")

    suspend fun pendingPermanentAccountDeletion(accountIdHash: String): RecoverableAccountDeletion? =
        throw UnsupportedOperationException("Permanent account deletion recovery is not available.")

    suspend fun recoverPermanentAccountDeletion(
        request: AccountDeletionRecoveryRequest,
    ): PreparedAccountDeletion = throw UnsupportedOperationException("Permanent account deletion recovery is not available.")

    suspend fun commitPermanentAccountDeletion(
        prepared: PreparedAccountDeletion,
        confirmationToken: String,
    ): AccountDeletionReceipt = throw UnsupportedOperationException("Permanent account deletion is not available.")

    suspend fun permanentAccountDeletionStatus(operationId: String): PermanentAccountDeletionStatus =
        throw UnsupportedOperationException("Permanent account deletion status is not available.")

    suspend fun recordPermanentAccountDeletionClientReceipt(
        receipt: PermanentAccountDeletionClientReceipt,
    ): PermanentAccountDeletionClientReceiptResult =
        throw UnsupportedOperationException("Permanent account deletion client receipts are not available.")
}

data class OfflineEntryStatus(
    val policyVersion: Long,
    val expiresAtEpochMillis: Long,
    val remainingMillis: Long,
    val accountBindings: List<OfflineEntryAccountBinding> = emptyList(),
)

data class OfflineEntryAccountBinding(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val accountMobileGeneration: Long,
    val activationId: String,
)

data class OnboardingInvitation(
    val publicId: String,
    val inviteCode: String,
    val inviterUserId: String,
    val inviterDisplayName: String,
    val status: String,
    val expiresAtEpochMillis: Long,
    val invitationUrl: String,
)

data class VerifiedOnboardingInvitation(
    val activationSession: String,
    val inviteCode: String,
    val inviterUserId: String,
    val inviterDisplayName: String,
    val expiresAtEpochMillis: Long,
)

data class OnboardingUsernameSuggestion(
    val displayName: String,
    val loginAccount: String,
    val matrixUserId: String,
)

data class OnboardingActivation(
    val loginAccount: String,
    val matrixUserId: String,
    val displayName: String,
    val inviterUserId: String,
    val inviterDisplayName: String,
    val friendshipStatus: String,
    val directRoomId: String,
)

/**
 * Executes the complete public device-security refresh path.
 *
 * Unlike [ManagementControlService.synchronize], this boundary also applies verified management
 * commands and invalidates protected-content projections before returning the public report.
 */
interface ManagementSecurityRefreshService {
    val localEntryRestriction: kotlinx.coroutines.flow.Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    val entryIsOffline: kotlinx.coroutines.flow.Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    fun verifiedOnlineReadiness(): StartupSecurityReadiness? = null

    /** Known link loss invalidates online readiness without changing signed authority or operation deadlines. */
    fun onNetworkUnavailable() = Unit

    suspend fun refreshSecurityAccess(): ManagementSyncReport

    /**
     * Waits for verified startup readiness. Implementations with a process-wide coordinator
     * override this to coalesce callers; the default keeps existing test and feature fakes
     * source-compatible while retaining a bounded, fail-closed fallback.
     */
    suspend fun awaitStartupReadiness(deadlineEpochMillis: Long): StartupSecurityReadiness {
        val remainingMillis = deadlineEpochMillis - System.currentTimeMillis()
        if (remainingMillis <= 0) {
            return StartupSecurityReadiness(
                state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                reasonCode = "STARTUP_READINESS_TIMEOUT",
                observedAtEpochMillis = System.currentTimeMillis(),
                retryAtEpochMillis = System.currentTimeMillis(),
            )
        }
        return try {
            withTimeout(remainingMillis) {
                refreshSecurityAccess().toStartupSecurityReadiness()
            }
        } catch (_: TimeoutCancellationException) {
            StartupSecurityReadiness(
                state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                reasonCode = "STARTUP_READINESS_TIMEOUT",
                observedAtEpochMillis = System.currentTimeMillis(),
                retryAtEpochMillis = System.currentTimeMillis(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            StartupSecurityReadiness(
                state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                reasonCode = "CONTROL_PLANE_UNAVAILABLE",
                observedAtEpochMillis = System.currentTimeMillis(),
                retryAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }
}

fun ManagementSyncReport.toStartupSecurityReadiness(
    observedAtEpochMillis: Long = System.currentTimeMillis(),
    operationId: String? = null,
    installationId: String? = null,
    offlineEligible: Boolean = false,
): StartupSecurityReadiness {
    val hasValidInstallationBinding = !installationId.isNullOrBlank()
    val state = when (status) {
        ManagementSyncStatus.DISABLED -> StartupSecurityReadinessState.DISABLED
        ManagementSyncStatus.SYNCHRONIZED -> if (
            acceptedPolicyVersion != null &&
            deviceSecurityState != null &&
            deviceSecurityState != ManagedDeviceSecurityState.ACTION_REQUIRED &&
            hasValidInstallationBinding
        ) {
            StartupSecurityReadinessState.READY_FOR_LC01
        } else {
            StartupSecurityReadinessState.RETRYABLE_FAILURE
        }
        ManagementSyncStatus.RETRY_REQUIRED -> StartupSecurityReadinessState.RETRYABLE_FAILURE
        ManagementSyncStatus.SECURITY_REJECTED -> when (errorCode) {
            "CONTROL_SYNC_OLDER_SNAPSHOT" -> StartupSecurityReadinessState.UNKNOWN
            else -> StartupSecurityReadinessState.SECURITY_REJECTED
        }
    }
    val reasonCode = when {
        status == ManagementSyncStatus.DISABLED -> "STARTUP_CONFIGURATION_DISABLED"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "CONTROL_SYNC_OLDER_SNAPSHOT" -> "STARTUP_SNAPSHOT_STALE"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "CONTROL_SYNC_CONTRADICTORY_AUTHORITY" -> "STARTUP_AUTHORITY_CONTRADICTORY"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "CONTROL_SYNC_TIME_STATE_REJECTED" -> "SIGNED_POLICY_EXPIRED"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "CONTROL_SYNC_ACCESS_TICKET_REJECTED" -> "ACCESS_TICKET_EXPIRED"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "CONTROL_SYNC_TRUST_DECISION_REJECTED" -> "DEVICE_TRUST_NOT_READY"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "INSTALLATION_IDENTITY_MISMATCH" -> "INSTALLATION_IDENTITY_MISMATCH"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "LOCAL_ACCESS_RESTRICTED" -> "DEVICE_TRUST_NOT_READY"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "POLICY_APPLICATION_BLOCKED_VERSION" ->
            "UNSUPPORTED_POLICY_VERSION"
        status == ManagementSyncStatus.SECURITY_REJECTED && errorCode == "POLICY_APPLICATION_FAILED" ->
            "SIGNED_POLICY_FAILED"
        status == ManagementSyncStatus.SECURITY_REJECTED -> "SECURITY_POLICY_REJECTED"
        errorCode in setOf("COMPATIBILITY_POLICY_DISABLED", "COMPATIBILITY_EVIDENCE_REJECTED", "COMPATIBILITY_BINDING_CONFLICT") -> errorCode
        status == ManagementSyncStatus.SYNCHRONIZED && !hasValidInstallationBinding -> "INSTALLATION_IDENTITY_MISMATCH"
        status == ManagementSyncStatus.SYNCHRONIZED && acceptedPolicyVersion == null -> "SIGNED_POLICY_UNAVAILABLE"
        status == ManagementSyncStatus.SYNCHRONIZED && deviceSecurityState == ManagedDeviceSecurityState.ACTION_REQUIRED -> "DEVICE_TRUST_NOT_READY"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "CONTROL_SYNC_POLICY_REJECTED" -> "SIGNED_POLICY_UNAVAILABLE"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "COMMAND_EXECUTION_PENDING" -> "REMOTE_COMMAND_EXECUTION_PENDING"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "POLICY_APPLICATION_HANDSHAKE_PENDING" ->
            "REMOTE_POLICY_APPLICATION_PENDING"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "POLICY_APPLICATION_EVIDENCE_PENDING" ->
            "REMOTE_POLICY_APPLICATION_PENDING"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "COMMAND_REPORT_PENDING" -> "REMOTE_COMMAND_REPORT_PENDING"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode == "COMMAND_EXECUTION_FAILED" -> "REMOTE_COMMAND_EXECUTION_FAILED"
        status == ManagementSyncStatus.RETRY_REQUIRED && errorCode in STARTUP_TRANSPORT_REASON_CODES -> errorCode
        status == ManagementSyncStatus.RETRY_REQUIRED -> "CONTROL_PLANE_UNAVAILABLE"
        else -> null
    }
    return StartupSecurityReadiness(
        state = state,
        reasonCode = reasonCode,
        operationId = operationId,
        installationId = installationId,
        policyVersion = acceptedPolicyVersion,
        deviceSecurityState = deviceSecurityState,
        observedAtEpochMillis = observedAtEpochMillis,
        expiresAtEpochMillis = if (state == StartupSecurityReadinessState.READY_FOR_LC01) {
            observedAtEpochMillis + STARTUP_READINESS_CACHE_TTL_MILLIS
        } else {
            null
        },
        retryAtEpochMillis = retryAfterMillis?.let { runCatching { Math.addExact(observedAtEpochMillis, it.coerceAtLeast(0)) }.getOrDefault(Long.MAX_VALUE) },
        offlineEligible = offlineEligible && state == StartupSecurityReadinessState.RETRYABLE_FAILURE,
    )
}

private const val STARTUP_READINESS_CACHE_TTL_MILLIS = 10_000L

private val STARTUP_TRANSPORT_REASON_CODES = setOf(
    "CONTROL_DNS_UNAVAILABLE",
    "CONTROL_CONNECTION_FAILED",
    "CONTROL_REQUEST_TIMEOUT",
    "CONTROL_NETWORK_IO",
    "CONTROL_TLS_REJECTED",
    "CONTROL_SERVER_REJECTED",
    "CONTROL_SERVER_UNAVAILABLE",
    "CONTROL_RATE_LIMITED",
    "DEVICE_SIGNING_KEY_UNAVAILABLE",
)

data class ManagedAppRelease(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val mandatory: Boolean,
    val apkUrl: String,
    val apkFallbackUrl: String,
    val apkSha256: String,
    val buildId: String,
    val signingCertificateSha256: String,
    val packageName: String,
)

data class PreparedAppUpdate(
    val release: ManagedAppRelease,
    val apkFile: File,
)

data class AppUpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
