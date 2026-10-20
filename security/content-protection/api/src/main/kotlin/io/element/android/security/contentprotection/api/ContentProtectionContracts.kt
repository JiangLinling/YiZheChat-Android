/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority

@JvmInline
value class ContentId(val value: String)

@JvmInline
value class PolicyId(val value: String)

@JvmInline
value class KmsShareReference(val value: String)

data class RoomEpochReference(
    val epochId: String,
    val generation: Long,
    val audienceDigestSha256: ByteArray,
)

enum class ContentKind {
    TEXT,
    IMAGE,
    VOICE_MESSAGE,
    VIDEO,
    ATTACHMENT,
    LOCATION,
    REACTION,
    POLL,
    POLL_OPERATION,
    MEDIA_CAPTION,
    FORWARD,
    LIVE_LOCATION,
    LIVE_LOCATION_OPERATION,
    STICKER,
    RELAY,
    RELAY_OPERATION,
    CALL_OPERATION,
    CALL_RECORDING,
}

enum class ContentCipherSuite {
    AES_256_GCM_HKDF_SHA256,
    CHUNKED_AES_256_GCM_HKDF_SHA256,
}

data class ContentPolicyReference(
    val policyId: PolicyId,
    val policyVersion: Long,
    val expiresAtEpochMillis: Long,
)

enum class OfflineLeaseMode {
    ONLINE_REQUIRED,
    BOUNDED_OFFLINE,
}

enum class MessageLifecycleMode {
    FOLLOW_ROOM,
    BURN_AFTER_READ,
}

enum class ConversationKind {
    DIRECT,
    GROUP,
}

data class ContentLifecycle(
    val mode: MessageLifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
    val conversationKind: ConversationKind? = null,
) {
    init {
        require((mode == MessageLifecycleMode.BURN_AFTER_READ) == (conversationKind != null))
    }
}

enum class RoomLifecycleProfileKind {
    STANDARD,
    CONFIDENTIAL,
}

enum class RoomLifecycleRecoveryPolicy {
    OLD_DEVICE_OR_RECOVERY_KEY,
    OLD_DEVICE_AND_ADMIN_APPROVAL,
}

/** Short-lived, server-signed room policy used before a content key or expiry is created. */
data class ResolvedRoomLifecycle(
    val roomIdSha256: ByteArray,
    val profileId: String,
    val profileKind: RoomLifecycleProfileKind,
    val retentionMillis: Long,
    val maximumOfflineMillis: Long,
    val recoveryPolicy: RoomLifecycleRecoveryPolicy,
    val messageExpiresAtEpochMillis: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val protocolVersion: Int = 1,
    val contractId: String? = null,
    val profileRevision: Long? = null,
    val contractSha256: ByteArray? = null,
    val signingKeyId: String? = null,
)

/** Public wrapping key published by an approved device registry, never supplied by an untrusted message event. */
data class DeviceShareRecipient(
    val installationId: String,
    val keyId: String,
    val algorithm: String,
    val publicKeyDer: ByteArray,
    /** True only when the signed device registry says this installation may receive bounded-offline content. */
    val allowsBoundedOffline: Boolean,
)

data class DeviceShareKeyDescriptor(
    val installationId: String,
    val keyId: String,
    val algorithm: String,
    val publicKeyDer: ByteArray,
    val hardwareSecurityLevel: HardwareSecurityLevel,
)

data class DeviceShareEnvelope(
    val recipientInstallationId: String,
    val recipientKeyId: String,
    val algorithm: String,
    val ciphertext: ByteArray,
)

/**
 * Authenticated, versioned metadata for one inner content ciphertext.
 *
 * [immutableContextSha256] binds the envelope to the Matrix room and all immutable fields. The canonical header is
 * also supplied as AEAD associated data, so a header, recipient envelope or KMS reference cannot be substituted.
 */
data class ContentProtectionHeader(
    val formatVersion: Int,
    val contentId: ContentId,
    val kind: ContentKind,
    val cipherSuite: ContentCipherSuite,
    val policy: ContentPolicyReference,
    val senderInstallationId: String,
    val audienceAccountIdHashes: Set<String> = emptySet(),
    val createdAtEpochMillis: Long,
    val immutableContextSha256: ByteArray,
    val nonce: ByteArray,
    val deviceShareEnvelopes: List<DeviceShareEnvelope>,
    val roomEpoch: RoomEpochReference? = null,
    val kmsShareReference: KmsShareReference,
    val offlineLeaseMode: OfflineLeaseMode,
    val lifecycle: ContentLifecycle = ContentLifecycle(),
)

data class ProtectedContentEnvelope(
    val header: ContentProtectionHeader,
    val ciphertext: ByteArray,
)

data class StoreKmsShareRequest(
    val contentId: ContentId,
    val roomId: String? = null,
    val kind: ContentKind,
    val policy: ContentPolicyReference,
    val accountIdHash: String,
    val senderInstallationId: String,
    val audienceAccountIdHashes: Set<String> = setOf(accountIdHash),
    val authorizedInstallationIds: Set<String> = setOf(senderInstallationId),
    val immutableContextSha256: ByteArray,
    val kmsShare: ByteArray,
    val leaseMode: OfflineLeaseMode,
    val roomEpoch: RoomEpochReference? = null,
    val lifecycle: ContentLifecycle = ContentLifecycle(),
    val roomLifecycle: ResolvedRoomLifecycle? = null,
    val clientCreatedAtEpochMillis: Long? = null,
    val matrixTransactionIdSha256: ByteArray? = null,
)

data class StartDeviceRecoveryRequest(
    val accountIdHash: String,
    val newInstallationId: String,
)

data class ConfirmDeviceRecoveryRequest(
    val recoveryId: String,
    val accountIdHash: String,
)

enum class DeviceRecoveryState {
    AWAITING_OLD_DEVICE,
    STANDARD_AUTHORIZED,
    AWAITING_ADMIN_APPROVAL,
    CONFIDENTIAL_AUTHORIZED,
    EXPIRED,
}

data class DeviceRecoveryStatus(
    val recoveryId: String,
    val accountIdHash: String,
    val newInstallationId: String,
    val state: DeviceRecoveryState,
    val standardAuthorized: Boolean,
    val oldDeviceConfirmed: Boolean,
    val administratorApproved: Boolean,
    val confidentialAuthorized: Boolean,
    val requestExpiresAtEpochMillis: Long,
)

data class PendingDeviceRecovery(
    val recoveryId: String,
    val newInstallationId: String,
    val standardAuthorized: Boolean,
    val createdAtEpochMillis: Long,
    val requestExpiresAtEpochMillis: Long,
)

data class AcquireRoomEpochRequest(
    val roomId: String,
    val roomIdSha256: ByteArray,
    val accountIdHash: String,
    val audienceAccountIdHashes: Set<String>,
    val authorizedInstallationIds: Set<String>,
    val requestingInstallationId: String,
    val requestingKeyId: String,
    val requestNonce: ByteArray,
    val minimumActiveMillis: Long = 60_000L,
)

data class ReleaseRoomEpochRequest(
    val reference: RoomEpochReference,
    val roomIdSha256: ByteArray,
    val accountIdHash: String,
    val requestingInstallationId: String,
    val requestingKeyId: String,
    val requestNonce: ByteArray,
)

/** Volatile plaintext epoch material. Callers must zero [epochSecret] as soon as it is cached or used. */
data class ReleasedRoomEpoch(
    val reference: RoomEpochReference,
    val roomIdSha256: ByteArray,
    val accountIdHash: String,
    val installationId: String,
    val recipientKeyId: String,
    val requestNonce: ByteArray,
    val activeUntilEpochMillis: Long,
    val releaseUntilEpochMillis: Long,
    val serverTimeEpochMillis: Long,
    val epochSecret: ByteArray,
)

data class ReleaseKmsShareRequest(
    val reference: KmsShareReference,
    val contentId: ContentId,
    val policy: ContentPolicyReference,
    val accountIdHash: String,
    val requestingInstallationId: String,
    val requestingKeyId: String,
    val immutableContextSha256: ByteArray,
    val requestNonce: ByteArray,
    val bootCount: Long,
    val burnSessionId: String? = null,
    val progressiveVideoPlayback: Boolean = false,
)

enum class BurnAfterReadWindowMode {
    FIXED_WINDOW,
    PLAYBACK_SESSION,
}

data class OpenBurnAfterReadRequest(
    val contentId: ContentId,
    val accountIdHash: String,
    val requestingInstallationId: String,
    val sessionId: String,
    val requestNonce: ByteArray,
)

data class BurnAfterReadSession(
    val contentId: ContentId,
    val accountIdHash: String,
    val installationId: String,
    val sessionId: String,
    val contentKind: ContentKind,
    val windowMode: BurnAfterReadWindowMode,
    val openedAtEpochMillis: Long,
    val viewExpiresAtEpochMillis: Long,
    val globalExpiresAtEpochMillis: Long,
    val serverTimeEpochMillis: Long,
)

enum class BurnAfterReadEndReason {
    VIEW_CLOSED,
    WINDOW_EXPIRED,
    PLAYBACK_ENDED,
    APP_LOCKED,
}

data class EndBurnAfterReadRequest(
    val contentId: ContentId,
    val accountIdHash: String,
    val requestingInstallationId: String,
    val sessionId: String,
    val reason: BurnAfterReadEndReason,
)

data class BurnAfterReadEndResult(
    val contentId: ContentId,
    val accountIdHash: String,
    val sessionId: String,
    val state: String,
    val endedAtEpochMillis: Long,
    val allAccessEnded: Boolean,
    val serverTimeEpochMillis: Long,
    val rootOperationId: String = "",
    val viewOperationId: String = "",
    val terminalState: String = "ACCESS_ENDED",
    val keyDestroyed: Boolean = false,
    val cleanupPending: Boolean = false,
    val receiptReferences: List<String> = emptyList(),
)

/** A verified KMS grant. [kmsShare] must be held only for the active decrypt operation and then zeroed. */
data class ReleasedKmsShare(
    val grantId: String,
    val reference: KmsShareReference,
    val contentId: ContentId,
    val policyVersion: Long,
    val installationId: String,
    val immutableContextSha256: ByteArray,
    val requestNonce: ByteArray,
    val mode: OfflineLeaseMode,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    /**
     * Exact local deadline of this already-authorized release. For bounded-offline releases this is the earliest of
     * the signed lease expiry, refresh deadline and policy expiry; it must never be reconstructed from the header.
     */
    val authorizationDeadlineEpochMillis: Long,
    val serverTimeEpochMillis: Long,
    val bootCount: Long,
    val kmsShare: ByteArray,
    val burnSessionId: String? = null,
    val progressivePlaybackDeadlineEpochMillis: Long? = null,
)

/** Immutable description of authority already established by a successful KMS/offline release. */
data class ProtectedMediaReleaseAuthorityDescription(
    val authorityId: String,
    val reference: KmsShareReference,
    val contentId: ContentId,
    val policyVersion: Long,
    val accountIdHash: String,
    val installationId: String,
    val immutableContextSha256Hex: String,
    val requestNonceSha256Hex: String,
    val mode: OfflineLeaseMode,
    val issuedAtEpochMillis: Long,
    val releaseExpiresAtEpochMillis: Long,
    val authorizationDeadlineEpochMillis: Long,
    val serverTimeEpochMillis: Long,
    val bootCount: Long,
    val burnSessionId: String? = null,
)

/**
 * Opaque process capability issued by [ContentProtectionService]. Its public description is not authority: callers
 * can copy data classes or implement this interface, but production validation accepts only a live instance issued
 * by the same service instance after a fully successful authenticated decrypt.
 */
interface ProtectedMediaReleaseAuthority : SecureMediaReleaseAuthority {
    val description: ProtectedMediaReleaseAuthorityDescription
    val externalReadBinding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding? get() = null
    val localAuthorityId: String? get() = null

    override fun close()
}

enum class ProtectedMediaReleaseAuthorityDecision {
    VALIDATED,
    OWNER_MISMATCH,
    RELEASE_MISMATCH,
    EXPIRED,
    BINDING_INVALID,
    CLOSED,
    AUTHORITY_UNAVAILABLE,
}

/** Signed wire lease. It contains a device-wrapped KMS share, never a persistent plaintext share. */
data class OfflineLease(
    val leaseId: String,
    val reference: KmsShareReference,
    val contentId: ContentId,
    val accountIdHash: String,
    val installationId: String,
    val recipientKeyId: String,
    val mode: OfflineLeaseMode,
    val policyVersion: Long,
    val immutableContextSha256: ByteArray,
    val requestNonce: ByteArray,
    val bootCount: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val serverTimeEpochMillis: Long,
    val refreshAfterEpochMillis: Long,
    val revocationSequence: Long,
    val maximumUses: Int,
    val encryptedKmsShare: ByteArray,
    val serverSignature: ByteArray,
    val burnSessionId: String? = null,
    val progressivePlaybackDeadlineEpochMillis: Long? = null,
)

enum class RemoteDeviceAccessState {
    ACTIVE,
    STALE,
    REVOKED,
}

data class FetchRevocationsRequest(
    val accountIdHash: String,
    val installationId: String,
    val knownSequence: Long,
    val requestNonce: ByteArray,
)

/** A full, signed revocation snapshot. Revoked IDs are permanent tombstones and must not disappear in later snapshots. */
data class RemoteRevocationSnapshot(
    val accountIdHash: String,
    val installationId: String,
    val sequence: Long,
    val issuedAtEpochMillis: Long,
    val refreshAfterEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val requestNonce: ByteArray,
    val deviceAccessState: RemoteDeviceAccessState,
    val revokedLeaseIds: Set<String>,
    val revokedContentIds: Set<ContentId>,
    val serverSignature: ByteArray,
)

data class KmsClientConfiguration(
    val baseUrl: String,
    val signingPublicKeyDer: ByteArray,
    val certificatePinsSha256: Set<String> = emptySet(),
    val maximumOfflineLeaseMillis: Long = 0,
    val maximumOfflineLicenseUses: Int = 0,
    val clockRollbackToleranceMillis: Long = 0,
    val allowCleartextForTests: Boolean = false,
)

class KmsRequestAuthorization private constructor(private val headerValue: String) {
    fun asHeaderValue(): String = headerValue

    override fun toString(): String = "KmsRequestAuthorization([REDACTED])"

    companion object {
        fun bearer(token: String): KmsRequestAuthorization {
            require(token.isNotBlank())
            return KmsRequestAuthorization("Bearer $token")
        }
    }
}

sealed class ContentProtectionException(message: String, cause: Throwable? = null) : SecurityException(message, cause) {
    class ContactRestricted(val blockedBySelf: Boolean) : ContentProtectionException(
        if (blockedBySelf) "CONTACT_BLOCKED_BY_YOU" else "CONTACT_UNAVAILABLE",
    )
    class InvalidEnvelope(cause: Throwable? = null) : ContentProtectionException("Invalid protected content envelope.", cause)

    class DeviceKeyUnavailable(cause: Throwable? = null) : ContentProtectionException("Device content key is unavailable.", cause)

    class RecipientDeviceUnavailable : ContentProtectionException("A recipient has no approved device available.")

    class KmsUnavailable(cause: Throwable? = null) : ContentProtectionException("KMS is unavailable or not configured.", cause)
    class SendBindingPending : ContentProtectionException("Original send event binding is awaiting confirmation.")

    /** A rejected epoch grants no key material. A later retry must rebuild the current audience and device projection. */
    class RoomEpochConflict(val reason: Reason) : ContentProtectionException("Room security state changed; synchronize and retry.") {
        enum class Reason { MEMBERSHIP_CHANGED, TRUSTED_DEVICES_CHANGED, UNKNOWN }
    }

    /**
     * The short-lived, signed room lifecycle contract expired before the KMS accepted the share.
     *
     * This is intentionally distinct from a generic transport failure: callers may resolve one fresh contract and
     * rebuild the complete authenticated envelope once. It must never be used to retry only the HTTP body or to
     * downgrade to an older protocol.
     */
    class LifecycleContractRefreshRequired :
        ContentProtectionException("The room lifecycle contract expired and must be refreshed.")

    class AccessDenied : ContentProtectionException("KMS denied access to protected content.")

    /**
     * The current installation is valid, but an existing account-private key belongs to another
     * installation and LC-08 must complete before LC-05 may continue.
     */
    class DeviceRecoveryRequired :
        ContentProtectionException("Device recovery must complete before protected content activation can continue.")

    /** A valid recovery request exists, but its standard grant is still awaiting another device. */
    class DeviceRecoveryWaiting(val recoveryId: String, val requestExpiresAtEpochMillis: Long) :
        ContentProtectionException("Device recovery is awaiting trusted old-device confirmation.")

    class RecoveryApprovalRequired :
        ContentProtectionException("Confidential content recovery requires old-device confirmation and administrator approval.")

    /** The retained epoch key is unavailable; this does not assert that the content TTL elapsed. */
    class RoomEpochMaterialUnavailable : ContentProtectionException("The historical content key is unavailable.")

    class Expired : ContentProtectionException("Protected content access has expired.")

    class Revoked : ContentProtectionException("Protected content or this device has been revoked.")

    class OfflineLicenseUnavailable(cause: Throwable? = null) :
        ContentProtectionException("No valid offline content license is available.", cause)

    class ClockRollbackDetected : ContentProtectionException("A local clock rollback invalidated the offline license.")

    class ReplayRejected : ContentProtectionException("A stale or replayed offline license was rejected.")

    class AuthenticationFailed(cause: Throwable? = null) : ContentProtectionException("Protected content authentication failed.", cause)
}

object ContentProtectionFormat {
    const val LEGACY_DEVICE_ENVELOPE_VERSION = 2
    const val CURRENT_VERSION = 3
    const val CONTENT_KEY_SIZE_BYTES = 32
    const val SHARE_SIZE_BYTES = 32
    const val SHA256_SIZE_BYTES = 32
    const val GCM_NONCE_SIZE_BYTES = 12
    const val REQUEST_NONCE_MIN_SIZE_BYTES = 16
    const val MAX_ROOM_AUDIENCE_ACCOUNTS = 1_000
    const val MAX_ROOM_AUTHORIZED_INSTALLATIONS = 2_048
    const val MAX_LEGACY_DEVICE_SHARE_ENVELOPES = 512
    const val DEVICE_SHARE_ALGORITHM = "RSA-OAEP-3072-SHA256-MGF1SHA1"
    const val KMS_SIGNATURE_ALGORITHM = "SHA256withECDSA"
}
