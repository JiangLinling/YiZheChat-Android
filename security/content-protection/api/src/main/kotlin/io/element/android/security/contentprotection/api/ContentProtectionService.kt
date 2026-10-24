/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.AccountVaultId
import java.io.InputStream
import java.io.OutputStream

data class ProtectContentRequest(
    val contentId: ContentId,
    val vaultId: AccountVaultId,
    val kind: ContentKind,
    val policy: ContentPolicyReference,
    val accountIdHash: String,
    val roomId: String,
    val senderInstallationId: String,
    val createdAtEpochMillis: Long,
    val audienceAccountIdHashes: Set<String> = setOf(accountIdHash),
    val recipients: List<DeviceShareRecipient>,
    val offlineLeaseMode: OfflineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
    val lifecycle: ContentLifecycle = ContentLifecycle(),
    val roomLifecycle: ResolvedRoomLifecycle? = null,
)

data class ReleaseContentRequest(
    val header: ContentProtectionHeader,
    val vaultId: AccountVaultId,
    val accountIdHash: String,
    val roomId: String,
    val requestingInstallationId: String,
    val requestNonce: ByteArray,
    val burnSessionId: String? = null,
    /** Local scheduling metadata only. It is never serialized into the KMS request. */
    val timelineReleaseIntent: TimelineContentReleaseIntent? = null,
    /** Requests an independently signed, memory-only permission for this progressive opening. */
    val progressiveVideoPlayback: Boolean = false,
)

enum class TimelineContentReleaseClass {
    FOREGROUND_VISIBLE,
    /** Owned, finite initial render demand; never a claim of actual visibility or authorization. */
    FOREGROUND_BOOTSTRAP,
    HISTORY_PREFETCH,
}

/** Local consumer purpose; scheduling priority must not merge distinct authorization uses. */
enum class TimelineContentReadPurpose { TIMELINE, ROOM_PREVIEW }

/** Process-local capability issued only to the active owner of one timeline subscription scope. */
@JvmInline
value class TimelineReleaseScopeOwnerToken(val value: Long) {
    init {
        require(value > 0) { "Timeline release scope owner token must be positive" }
    }
}

/**
 * Identifies cancellable timeline-derived authorization work without changing the KMS protocol.
 * [generation] identifies the measured Compose viewport that classified this request. Generation
 * zero is reserved for the finite startup fallback before the first measured viewport arrives.
 */
data class TimelineContentReleaseIntent(
    val scopeId: String,
    val requestId: String,
    val eventId: String,
    val generation: Long,
    val releaseClass: TimelineContentReleaseClass,
    val roomId: String? = null,
    val scopeOwnerToken: TimelineReleaseScopeOwnerToken? = null,
    val readPurpose: TimelineContentReadPurpose = TimelineContentReadPurpose.TIMELINE,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(generation >= 0) { "generation must not be negative" }
        require(roomId == null || roomId.isNotBlank()) { "roomId must not be blank" }
    }
}

enum class ContentAccessDestroyReason {
    USER_WITHDRAWN,
    RETENTION_EXPIRED,
    DEVICE_REVOKED,
    ADMINISTRATIVE_REVOCATION,
    ACCOUNT_LOGOUT,
}

enum class TrustedRedactionReason {
    USER_WITHDRAWN,
    RETENTION_EXPIRED,
    ADMINISTRATIVE_REVOCATION,
    BURN_AFTER_READ_COMPLETED,
}

fun interface RedactionReasonResolver {
    /** Resolves only control-plane-signed terminal reasons keyed by a SHA-256 Matrix event id. */
    suspend fun resolve(eventIdSha256: Set<String>): Map<String, TrustedRedactionReason>

    data object Noop : RedactionReasonResolver {
        override suspend fun resolve(eventIdSha256: Set<String>): Map<String, TrustedRedactionReason> = emptyMap()
    }
}

interface ContentProtectionService {
    /** Same-boot deadline derived only by the issuer from its original trusted time anchor. */
    fun protectedMediaDeadlineElapsedRealtimeMillis(authority: ProtectedMediaReleaseAuthority): Long? = null

    /** Local export delegation keeps the original signed release and trusted deadline. It is not a new KMS grant. */
    fun deriveProtectedMediaExternalReadAuthority(
        authority: ProtectedMediaReleaseAuthority,
        binding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding,
    ): ProtectedMediaReleaseAuthority? = null

    /** Releases once for authenticated random reads. It cannot issue a whole-object verified capability. */
    suspend fun openChunkReadSession(request: ReleaseContentRequest): ProtectedChunkReadSession =
        throw ContentProtectionException.KmsUnavailable()

    /** Cancels only matching timeline-derived work; lifecycle-owned reliable actions are outside this scheduler. */
    fun cancelTimelineReleaseRequests(
        scopeId: String,
        requestIds: Set<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
    ) = Unit

    /** Reclassifies only owner-bound timeline work that has not yet been dispatched. */
    fun updateTimelineReleaseViewport(
        scopeId: String,
        generation: Long,
        visibleRequestIds: List<String>,
        ownerToken: TimelineReleaseScopeOwnerToken,
        bootstrapRequestIds: List<String> = emptyList(),
    ): Boolean = false

    /** Registers the active owner and returns its process-local, non-replayable capability. */
    fun openTimelineReleaseScope(scopeId: String): TimelineReleaseScopeOwnerToken? = null

    /** Cancels all timeline-derived work for a closed room/timeline subscription scope. */
    fun closeTimelineReleaseScope(scopeId: String, ownerToken: TimelineReleaseScopeOwnerToken) = Unit

    suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun assignCreatedRoomLifecycle(roomId: String, profileKind: RoomLifecycleProfileKind): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun registerMessageEvent(
        contentId: ContentId,
        roomId: String,
        eventId: String,
        mediaUris: Set<String> = emptySet(),
    ): Boolean = throw ContentProtectionException.KmsUnavailable()

    suspend fun confirmMatrixRedaction(roomId: String, eventId: String): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun registerCallRecord(roomId: String, eventId: String): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun protectBytes(request: ProtectContentRequest, plaintext: ByteArray): ProtectedContentEnvelope

    /** Commit the existing producer journal immediately before a KMS store can be dispatched. */
    suspend fun protectBytes(
        request: ProtectContentRequest,
        plaintext: ByteArray,
        beforeKmsStore: suspend () -> Unit,
    ): ProtectedContentEnvelope = throw ContentProtectionException.KmsUnavailable()

    suspend fun decryptBytes(request: ReleaseContentRequest, ciphertext: ByteArray): ByteArray

    suspend fun protectStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
    ): ContentProtectionHeader

    /** Commit the original media draft before any remote key share can be created. */
    suspend fun protectStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
        beforeKmsStore: suspend () -> Unit,
    ): ContentProtectionHeader = throw ContentProtectionException.KmsUnavailable()

    suspend fun decryptStream(
        request: ReleaseContentRequest,
        ciphertext: InputStream,
        plaintext: OutputStream,
    )

    /**
     * Decrypts a protected media stream and returns evidence from the same verified release that supplied the key.
     * The authority is issued only after the complete stream decrypt succeeds. Implementations must not reconstruct
     * it from header policy or caller-provided values.
     */
    suspend fun decryptMediaStreamWithAuthority(
        request: ReleaseContentRequest,
        ciphertext: InputStream,
        plaintext: OutputStream,
    ): ProtectedMediaReleaseAuthority = throw ContentProtectionException.KmsUnavailable()

    /** A fresh signed grant for a still-live internal attachment source; never revives or extends an old grant. */
    suspend fun renewProtectedMediaReadAuthority(authority: ProtectedMediaReleaseAuthority): ProtectedMediaReleaseAuthority? = null

    /**
     * Validates issuer ownership and the immutable release evidence. This is not the later resource/purpose delivery
     * gate: it cannot by itself authorize decoder access. [effectiveNowEpochMillis] is an untrusted compatibility
     * hint; implementations must derive expiry from internal trusted/monotonic time and must never let this value
     * extend or refresh the release authority.
     */
    fun validateProtectedMediaReleaseAuthority(
        authority: ProtectedMediaReleaseAuthority,
        expected: ProtectedMediaReleaseAuthorityDescription,
        effectiveNowEpochMillis: Long,
    ): ProtectedMediaReleaseAuthorityDecision = ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE

    suspend fun destroyContentAccess(vaultId: AccountVaultId, contentId: ContentId, reason: ContentAccessDestroyReason): Boolean

    /**
     * Permanently revokes content on this device without issuing a second remote KMS deletion.
     *
     * The management plane calls this after the server has already destroyed the remote share. Implementations must
     * clear process-memory keys and persist the local tombstone before returning true.
     */
    suspend fun revokeLocalContent(vaultId: AccountVaultId, contentId: ContentId): Boolean = false

    suspend fun beginBurnAfterRead(request: OpenBurnAfterReadRequest): BurnAfterReadSession =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun endBurnAfterRead(vaultId: AccountVaultId, request: EndBurnAfterReadRequest): BurnAfterReadEndResult =
        throw ContentProtectionException.KmsUnavailable()
}

interface ContentEnvelopeValidator {
    fun validate(header: ContentProtectionHeader): Result<Unit>
}

interface ContentEnvelopeCodec {
    fun encode(envelope: ProtectedContentEnvelope): ByteArray

    fun decode(encoded: ByteArray): ProtectedContentEnvelope
}

interface DeviceShareKeyService {
    suspend fun descriptor(): DeviceShareKeyDescriptor

    suspend fun wrapShare(
        recipient: DeviceShareRecipient,
        contentId: ContentId,
        immutableContextSha256: ByteArray,
        share: ByteArray,
    ): DeviceShareEnvelope

    suspend fun unwrapShare(
        envelope: DeviceShareEnvelope,
        contentId: ContentId,
        immutableContextSha256: ByteArray,
    ): ByteArray

    suspend fun destroy(): Boolean
}

interface KmsShareClient {
    suspend fun registerContentVersion(request: RegisterContentVersionRequest): RegisterContentVersionResult =
        throw ContentProtectionException.KmsUnavailable()

    /** Signed account-resource protocol, transported with the existing pinned KMS/device proof. */
    suspend fun requestPrivateResource(action: String, requestJson: String): String =
        throw ContentProtectionException.KmsUnavailable()

    /** Releases an existing authorized key; never creates or rotates a key generation. */
    suspend fun releasePrivateProfileKey(accountIdHash: String): AccountPrivateProfileKey =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun ensurePrivateProfileKey(accountIdHash: String, proposedKey: ByteArray): AccountPrivateProfileKey =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun fetchPrivateProfileDocument(accountIdHash: String): EncryptedPrivateProfileDocument? =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun putPrivateProfileDocument(
        accountIdHash: String,
        keyVersion: Long,
        expectedDocumentVersion: Long,
        ciphertext: ByteArray,
        nonce: ByteArray,
        associatedDataSha256: ByteArray,
    ): EncryptedPrivateProfileDocument = throw ContentProtectionException.KmsUnavailable()

    suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun assignCreatedRoomLifecycle(roomId: String, profileKind: RoomLifecycleProfileKind): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun registerMessageEvent(
        contentId: ContentId,
        roomId: String,
        eventId: String,
        mediaUris: Set<String> = emptySet(),
    ): Boolean = throw ContentProtectionException.KmsUnavailable()

    suspend fun confirmMatrixRedaction(roomId: String, eventId: String): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun registerCallRecord(roomId: String, eventId: String): Boolean =
        throw ContentProtectionException.KmsUnavailable()

    /** Starts a server-side re-wrap authorization; it never exports an old device or content key. */
    suspend fun startDeviceRecovery(request: StartDeviceRecoveryRequest): DeviceRecoveryStatus =
        throw ContentProtectionException.KmsUnavailable()

    /** Reads one existing recovery request without creating, renewing, or approving it. */
    suspend fun deviceRecoveryStatus(recoveryId: String): DeviceRecoveryStatus =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun pendingDeviceRecoveries(accountIdHash: String): List<PendingDeviceRecovery> =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun confirmDeviceRecovery(request: ConfirmDeviceRecoveryRequest): DeviceRecoveryStatus =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun acquireRoomEpoch(request: AcquireRoomEpochRequest): ReleasedRoomEpoch =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun releaseRoomEpoch(request: ReleaseRoomEpochRequest): ReleasedRoomEpoch =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun storeShare(request: StoreKmsShareRequest): KmsShareReference

    suspend fun releaseShare(request: ReleaseKmsShareRequest): ReleasedKmsShare

    /** Releases multiple independent shares in one transport operation when supported by the backend. */
    suspend fun releaseShares(requests: List<ReleaseKmsShareRequest>): List<ReleasedKmsShare> = requests.map { releaseShare(it) }

    suspend fun issueOfflineLease(request: ReleaseKmsShareRequest): OfflineLease

    suspend fun fetchRevocations(request: FetchRevocationsRequest): RemoteRevocationSnapshot

    suspend fun destroyShare(contentId: ContentId, reason: ContentAccessDestroyReason): Boolean

    suspend fun resolveRedactionReasons(eventIdSha256: Set<String>): Map<String, TrustedRedactionReason> = emptyMap()

    suspend fun openBurnAfterRead(request: OpenBurnAfterReadRequest): BurnAfterReadSession =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun endBurnAfterRead(request: EndBurnAfterReadRequest): BurnAfterReadEndResult =
        throw ContentProtectionException.KmsUnavailable()
}

data class RegisterContentVersionRequest(
    val contentId: ContentId,
    val rootContentId: ContentId,
    val parentContentId: ContentId?,
    val originOperationId: String,
    val operationId: String,
    val expectedEventIdSha256: String,
)

data class RegisterContentVersionResult(
    val contentId: ContentId,
    val rootContentId: ContentId,
    val parentContentId: ContentId?,
    val operationId: String,
    val expiresAtEpochMillis: Long,
    val eventIdSha256: String,
    val createdAtEpochMillis: Long,
)

data class AccountPrivateProfileKey(
    val keyVersion: Long,
    val keyMaterial: ByteArray,
)

data class EncryptedPrivateProfileDocument(
    val keyVersion: Long,
    val documentVersion: Long,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val associatedDataSha256: ByteArray,
    val updatedAtEpochMillis: Long,
)

interface OfflineLicenseService {
    /**
     * Checks permanent local/remote tombstones before any online or offline KMS release.
     *
     * This must also guard online releases: restoring an older KMS/database backup must never resurrect locally
     * revoked content.
     */
    suspend fun requireAccessAllowed(vaultId: AccountVaultId, contentId: ContentId)

    suspend fun releaseOrAcquire(vaultId: AccountVaultId, request: ReleaseKmsShareRequest): ReleasedKmsShare

    suspend fun inspect(
        vaultId: AccountVaultId,
        contentId: ContentId,
        reference: KmsShareReference,
    ): OfflineLicenseStatus = OfflineLicenseStatus(OfflineLicenseState.REFRESH_REQUIRED)

    suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): Boolean

    suspend fun clearVault(vaultId: AccountVaultId): Boolean
}

enum class OfflineLicenseState {
    VALID,
    REFRESH_REQUIRED,
    EXHAUSTED,
    EXPIRED,
    REVOKED,
    BINDING_INVALID,
    EPOCH_INVALID,
    REPLAY_REJECTED,
}

data class OfflineLicenseStatus(
    val state: OfflineLicenseState,
    val remainingUses: Int = 0,
    val expiresAtEpochMillis: Long? = null,
    val refreshAfterEpochMillis: Long? = null,
)

data class RevocationSyncResult(
    val sequence: Long,
    val deviceAccessState: RemoteDeviceAccessState,
    val revokedContentCount: Int,
    val revokedLeaseCount: Int,
)

interface RemoteRevocationService {
    suspend fun synchronize(vaultId: AccountVaultId, accountIdHash: String, installationId: String): RevocationSyncResult
}

data class DeviceRecipientRegistrySnapshot(
    val accountIdHash: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val recipients: List<DeviceShareRecipient>,
)

/** Approved public wrapping keys obtained from the signed management device registry. */
interface DeviceRecipientRegistry {
    suspend fun approvedRecipients(accountIdHash: String): DeviceRecipientRegistrySnapshot

    suspend fun approvedRecipients(accountIdHashes: Set<String>): Map<String, DeviceRecipientRegistrySnapshot> =
        accountIdHashes.associateWith { accountIdHash -> approvedRecipients(accountIdHash) }
}

/**
 * Describes why every active Matrix timeline must reconsider its protected projection.
 *
 * [HARD_INVALIDATION] is the fail-closed default for lock, logout, revocation, policy/trust
 * changes and every unknown caller. [SELECTIVE_CACHE_EXPIRY] is reserved for the process-memory
 * cache removing entries at their already-authenticated envelope deadline; it never authorizes a
 * cache miss and must not be used for an authorization boundary change. [HISTORY_RECOVERY_DELTA]
 * is an additive, account and room scoped authorization transition. It may retry previously hidden
 * immutable carriers, but it must retain current projections and never override a hard boundary or
 * a terminal content tombstone.
 */
enum class MatrixAuthorizationRefreshMode {
    HARD_INVALIDATION,
    SELECTIVE_CACHE_EXPIRY,

    /** The display gate reopened; retry missing projections without granting new authority. */
    PREVIEW_ACCESS_REOPENED,
    HISTORY_RECOVERY_DELTA,
}

data class MatrixAuthorizationRefresh(
    val revision: Long,
    val mode: MatrixAuthorizationRefreshMode,
    /** Latest hard boundary known when this event was emitted; lets out-of-order listeners fail closed. */
    val latestHardInvalidationRevision: Long,
    /** Present only for an additive history-recovery transition. Never contains a raw Matrix user id. */
    val accountScopeId: String? = null,
    /** Exact Matrix rooms whose hidden immutable carriers may be retried. */
    val roomScopeIds: Set<String> = emptySet(),
    /** Monotonic within [accountScopeId]; stale recovery work is ignored. */
    val recoveryGeneration: Long? = null,
)

/**
 * A permanent, content-scoped terminal transition which must not invalidate unrelated timeline
 * projections. The local revocation tombstone is persisted before this signal is emitted.
 */
data class MatrixContentInvalidation(
    val sequence: Long,
    val contentId: ContentId,
)

/** Tracks whether at least one live Matrix client has installed the protected send/receive mapping. */
interface MatrixContentProtectionRuntime {
    fun attach()

    fun detach()

    fun isConnected(): Boolean

    /** Clears process-memory material that is only valid while a protected session is unlocked. */
    fun clearSensitiveCaches() = Unit

    /** Registers a process-memory cache so app lock/logout/revocation can clear it fail-closed. */
    fun registerSensitiveCacheClearer(clearer: () -> Unit) = Unit

    /** Page-owned caches must unregister when their view is disposed. */
    fun registerScopedSensitiveCacheClearer(clearer: () -> Unit): AutoCloseable = AutoCloseable {}

    /** Registers a process-memory cache which can erase one permanently revoked content id. */
    fun registerSensitiveContentClearer(clearer: (ContentId) -> Unit) = Unit

    /** Current monotonic authorization generation used to reject stale in-flight projections. */
    fun authorizationRevision(): Long = 0L

    /** Registers an in-process refresh listener. Closing the handle removes the listener. */
    fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable = AutoCloseable {}

    /** Registers for terminal content-scoped invalidations without changing authorization state. */
    fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable = AutoCloseable {}

    /**
     * Requests that active timelines reconsider their encrypted items. The default is deliberately
     * hard fail-closed; only the cache-expiry owner may request the selective mode.
     */
    fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode = MatrixAuthorizationRefreshMode.HARD_INVALIDATION) = Unit

    /**
     * Rechecks only hidden history in [roomScopeIds] after a server-authorized recovery transition.
     * Implementations must reject an empty scope and stale generations. This is deliberately not
     * expressible through [requestAuthorizationRefresh], so cache expiry cannot grant new access.
     */
    fun requestHistoryRecoveryRefresh(
        accountScopeId: String,
        roomScopeIds: Set<String>,
        recoveryGeneration: Long,
    ) = Unit

    /**
     * Clears only [contentId] from process-memory projections, then notifies active timelines.
     * Callers must persist the local revocation tombstone before invoking this method.
     */
    fun requestContentInvalidation(contentId: ContentId) = Unit
}

fun interface KmsClientConfigurationProvider {
    fun configuration(): KmsClientConfiguration?
}

fun interface KmsAuthorizationProvider {
    suspend fun authorization(): KmsRequestAuthorization?

    /** Checks whether a proof can be minted without consuming a single-use KMS proof nonce. */
    suspend fun isAvailable(): Boolean = authorization() != null
}

fun interface DeviceRuntimeBindingProvider {
    fun bootCount(): Long
}

interface OfflineLicenseClock {
    fun wallClockEpochMillis(): Long

    fun elapsedRealtimeMillis(): Long
}
