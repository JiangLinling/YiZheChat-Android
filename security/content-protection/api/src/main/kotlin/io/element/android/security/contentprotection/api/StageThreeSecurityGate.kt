/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.AccountVaultId

enum class StageThreeOperation {
    PROTECT,
    RELEASE,
}

/**
 * Identifies the trusted component that must enforce the message-retention upper bound on release.
 *
 * [LOCAL_POLICY] is the fail-closed default and is required for legacy and bounded-offline envelopes.
 * [ONLINE_KMS] is reserved for a validated room-epoch envelope whose key release cannot proceed without
 * the pinned, authenticated KMS. The KMS checks the immutable envelope context against the stored V3
 * lifecycle contract before returning any key material.
 */
enum class StageThreeReleaseRetentionAuthority {
    LOCAL_POLICY,
    ONLINE_KMS,
}

enum class StageThreeDenialReason {
    REMOTE_POLICY_UNAVAILABLE,
    POLICY_NOT_ACTIVE,
    POLICY_MISMATCH,
    RETENTION_EXCEEDED,
    OFFLINE_POLICY_MISMATCH,
    POLICY_HARDWARE_REQUIREMENT_TOO_WEAK,
    DEVICE_NOT_TRUSTED,
    INSTALLATION_MISMATCH,
    HARDWARE_NOT_TRUSTED,
    VAULT_LOCKED,
    DEVICE_SHARE_KEY_MISMATCH,
    RECIPIENT_REGISTRY_NOT_VERIFIED,
    KMS_NOT_CONFIGURED,
    KMS_TRANSPORT_NOT_PINNED,
    KMS_AUTHORIZATION_UNAVAILABLE,
    MATRIX_MAPPING_NOT_READY,
}

class StageThreeGateDenied(
    val reason: StageThreeDenialReason,
) : ContentProtectionException("Stage-three content protection gate denied access.")

data class StageThreeGateRequest(
    val operation: StageThreeOperation,
    val vaultId: AccountVaultId,
    val accountIdHash: String,
    val installationId: String,
    val policy: ContentPolicyReference,
    val contentCreatedAtEpochMillis: Long,
    val offlineLeaseMode: OfflineLeaseMode,
    val audienceAccountIdHashes: Set<String> = setOf(accountIdHash),
    val recipients: List<DeviceShareRecipient> = emptyList(),
    /**
     * Server-signed lifecycle contract already verified by the KMS client.
     *
     * Only protocol-v3 protection requests may use this value to replace the legacy global retention ceiling.
     * Release requests and requests without a complete v3 contract remain on the legacy fail-closed path.
     */
    val roomLifecycle: ResolvedRoomLifecycle? = null,
    val releaseRetentionAuthority: StageThreeReleaseRetentionAuthority = StageThreeReleaseRetentionAuthority.LOCAL_POLICY,
)

data class StageThreeReadiness(
    val policyId: String,
    val policyVersion: Long,
    val installationId: String,
    val deviceShareKeyId: String,
)

/** Opaque local admission created only by a gate after full hardware checks. No network/key grant is added. */
interface StageThreeReadAuthority {
    suspend fun requireAccess()
}

interface StageThreeSecurityGate {
    suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness
    /** Implementations without a retained admission keep doing the original complete check. */
    suspend fun openReadAuthority(request: StageThreeGateRequest): StageThreeReadAuthority? = null
}

enum class MatrixContentProtectionStatus {
    NOT_CONNECTED,
    READY,
}

/**
 * Proof supplied by the Matrix integration layer, not by an incoming Matrix event.
 *
 * A production implementation must only report READY after send, receive, redaction and backup-restore paths map
 * the inner envelope format, and must source recipients from the verified device registry.
 */
interface MatrixContentProtectionBinding {
    fun status(): MatrixContentProtectionStatus

    suspend fun recipientsApproved(accountIdHashes: Set<String>, recipients: List<DeviceShareRecipient>): Boolean
}
