/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

enum class LifecycleOperationOwnerRole {
    PREVIEW,
    UPLOAD,
    EXTERNAL_READ,
}

enum class LifecycleOperationOwnerPhase {
    AVAILABLE,
    OWNED,
    RECOVERY_REQUIRED,
    COMPLETED,
}

data class LifecycleOperationOwnerDescriptor(
    val operationId: String,
    val lifecycleId: FullAppLifecycleId,
    val role: LifecycleOperationOwnerRole?,
    val phase: LifecycleOperationOwnerPhase,
    val generation: Long,
    val authoritativeOperationUpdatedAtEpochMillis: Long,
) {
    init {
        require(operationId.length in 16..256)
        require(
            lifecycleId == FullAppLifecycleId.LC_16 ||
            (lifecycleId == FullAppLifecycleId.LC_17 && role == null &&
                phase == LifecycleOperationOwnerPhase.AVAILABLE && generation == 0L)
        )
        require(generation >= 0)
        require(authoritativeOperationUpdatedAtEpochMillis > 0)
        when (phase) {
            LifecycleOperationOwnerPhase.AVAILABLE -> require(role == null)
            LifecycleOperationOwnerPhase.OWNED,
            LifecycleOperationOwnerPhase.RECOVERY_REQUIRED -> requireNotNull(role)
            LifecycleOperationOwnerPhase.COMPLETED -> require(role in setOf(LifecycleOperationOwnerRole.UPLOAD, LifecycleOperationOwnerRole.EXTERNAL_READ))
        }
    }
}

/**
 * Process-bound local ordering capability. Implementations created outside the owning authority are rejected.
 * This is not a content, KMS, secure-media, upload, or lifecycle-receipt authority.
 */
interface LifecycleOperationOwnerCapability {
    val descriptor: LifecycleOperationOwnerDescriptor
}

enum class LifecycleOperationOwnerCancelReason {
    USER_CANCELLED,
    ATTACHMENT_REMOVED,
    ATTACHMENT_REPLACED,
    SOURCE_UNAVAILABLE,
    PREVIEW_FAILED,
    UPLOAD_FAILED,
    OPERATION_REVOKED,
    PROCESS_RECOVERY_ABORTED,
}

enum class LifecycleOperationOwnerRejection {
    OPERATION_NOT_FOUND,
    UNSUPPORTED_LIFECYCLE,
    OPERATION_TERMINAL,
    OPERATION_NOT_OWNABLE,
    OPERATION_SUBJECTS_INCOMPLETE,
    OWNER_ALREADY_HELD,
    TRANSITION_NOT_ALLOWED,
    CAPABILITY_INVALID,
    PROCESS_INSTANCE_MISMATCH,
    STALE_GENERATION,
    COMMAND_CONFLICT,
    RECOVERY_REQUIRED,
    RECOVERY_MISMATCH,
    CAPABILITY_PUBLICATION_FAILED,
    GENERATION_EXHAUSTED,
    STATE_STORE_UNAVAILABLE,
    AUTHORITY_UNAVAILABLE,
}

data class LifecycleOperationOwnerTransitionReceipt(
    val schemaVersion: Int = 1,
    val operationId: String,
    val commandIdSha256: String,
    val requestFingerprintSha256: String,
    val processInstanceIdSha256: String,
    val fromCapabilityIdSha256: String?,
    val toCapabilityIdSha256: String?,
    val from: LifecycleOperationOwnerDescriptor,
    val to: LifecycleOperationOwnerDescriptor,
    val storeRevision: Long,
) {
    init {
        require(schemaVersion == 1)
        require(operationId == from.operationId && operationId == to.operationId)
        require(from.lifecycleId == to.lifecycleId)
        require(to.generation > from.generation)
        require(storeRevision > 0)
        listOfNotNull(
            commandIdSha256,
            requestFingerprintSha256,
            processInstanceIdSha256,
            fromCapabilityIdSha256,
            toCapabilityIdSha256,
        ).forEach { require(SHA_256_HEX.matches(it)) }
    }
}

sealed interface LifecycleOperationOwnerAcquireResult {
    data class Granted(
        val capability: LifecycleOperationOwnerCapability,
        val transition: LifecycleOperationOwnerTransitionReceipt,
    ) : LifecycleOperationOwnerAcquireResult

    data class Rejected(
        val reason: LifecycleOperationOwnerRejection,
        val current: LifecycleOperationOwnerDescriptor?,
    ) : LifecycleOperationOwnerAcquireResult
}

sealed interface LifecycleOperationOwnerMutationResult {
    data class Transferred(
        val capability: LifecycleOperationOwnerCapability,
        val transition: LifecycleOperationOwnerTransitionReceipt,
    ) : LifecycleOperationOwnerMutationResult

    data class Released(
        val transition: LifecycleOperationOwnerTransitionReceipt,
    ) : LifecycleOperationOwnerMutationResult

    data class Rejected(
        val reason: LifecycleOperationOwnerRejection,
        val current: LifecycleOperationOwnerDescriptor?,
    ) : LifecycleOperationOwnerMutationResult
}

/** Additive authority implemented by the same AppScope object as [AppOperationalStateCoordinator]. */
interface LifecycleOperationOwnerAuthority {
    suspend fun ownerState(operationId: String): LifecycleOperationOwnerDescriptor?

    suspend fun acquireOwner(
        operationId: String,
        role: LifecycleOperationOwnerRole,
        commandId: String,
        expectedRecoveryGeneration: Long? = null,
    ): LifecycleOperationOwnerAcquireResult

    suspend fun transferOwner(
        capability: LifecycleOperationOwnerCapability,
        toRole: LifecycleOperationOwnerRole,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult

    suspend fun completeOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult

    suspend fun cancelOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
        reason: LifecycleOperationOwnerCancelReason,
    ): LifecycleOperationOwnerMutationResult
}

private val SHA_256_HEX = Regex("[0-9a-f]{64}")
