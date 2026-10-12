/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAcquireResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAuthority
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCapability
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerDescriptor
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerMutationResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRejection
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerTransitionReceipt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

class FakeLifecycleOperationOwnerAuthority : LifecycleOperationOwnerAuthority {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, LifecycleOperationOwnerDescriptor>()
    private val capabilities = IdentityHashMap<FakeCapability, String>()
    private val receipts = mutableMapOf<String, LifecycleOperationOwnerTransitionReceipt>()
    private val sequence = AtomicLong()

    val transitions: List<LifecycleOperationOwnerTransitionReceipt>
        get() = receipts.values.toList()

    override suspend fun ownerState(operationId: String): LifecycleOperationOwnerDescriptor? = mutex.withLock {
        states[operationId]
    }

    override suspend fun acquireOwner(
        operationId: String,
        role: LifecycleOperationOwnerRole,
        commandId: String,
        expectedRecoveryGeneration: Long?,
    ): LifecycleOperationOwnerAcquireResult = mutex.withLock {
        val current = states[operationId] ?: descriptor(operationId)
        if (current.phase == LifecycleOperationOwnerPhase.OWNED) {
            return@withLock acquireRejected(
                LifecycleOperationOwnerRejection.OWNER_ALREADY_HELD,
                current,
            )
        }
        if (current.phase == LifecycleOperationOwnerPhase.COMPLETED) {
            return@withLock acquireRejected(
                LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED,
                current,
            )
        }
        if (current.phase == LifecycleOperationOwnerPhase.RECOVERY_REQUIRED &&
            (expectedRecoveryGeneration != current.generation || role != current.role)
        ) {
            return@withLock acquireRejected(LifecycleOperationOwnerRejection.RECOVERY_MISMATCH, current)
        }
        val next = current.copy(role = role, phase = LifecycleOperationOwnerPhase.OWNED, generation = current.generation + 1)
        val capability = FakeCapability(next)
        val receipt = receipt("ACQUIRE", commandId, current, next, null, capability.id)
        states[operationId] = next
        capabilities[capability] = capability.id
        receipts[hash(commandId)] = receipt
        LifecycleOperationOwnerAcquireResult.Granted(capability, receipt)
    }

    override suspend fun transferOwner(
        capability: LifecycleOperationOwnerCapability,
        toRole: LifecycleOperationOwnerRole,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult = mutate(capability, commandId, "TRANSFER") { current ->
        if (current.role != LifecycleOperationOwnerRole.PREVIEW || toRole != LifecycleOperationOwnerRole.UPLOAD) {
            null
        } else {
            current.copy(role = LifecycleOperationOwnerRole.UPLOAD, generation = current.generation + 1)
        }
    }

    override suspend fun completeOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult = mutate(capability, commandId, "COMPLETE") { current ->
        if (current.role != LifecycleOperationOwnerRole.UPLOAD) {
            null
        } else {
            current.copy(phase = LifecycleOperationOwnerPhase.COMPLETED, generation = current.generation + 1)
        }
    }

    override suspend fun cancelOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
        reason: LifecycleOperationOwnerCancelReason,
    ): LifecycleOperationOwnerMutationResult = mutate(capability, commandId, "CANCEL:${reason.name}") { current ->
        current.copy(role = null, phase = LifecycleOperationOwnerPhase.AVAILABLE, generation = current.generation + 1)
    }

    private suspend fun mutate(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
        kind: String,
        transition: (LifecycleOperationOwnerDescriptor) -> LifecycleOperationOwnerDescriptor?,
    ): LifecycleOperationOwnerMutationResult = mutex.withLock {
        val fake = capability as? FakeCapability
            ?: return@withLock mutationRejected(LifecycleOperationOwnerRejection.CAPABILITY_INVALID, capability.descriptor)
        val current = states[fake.descriptor.operationId]
            ?: return@withLock mutationRejected(LifecycleOperationOwnerRejection.STALE_GENERATION, null)
        if (capabilities[fake] == null || current != fake.descriptor) {
            return@withLock mutationRejected(LifecycleOperationOwnerRejection.STALE_GENERATION, current)
        }
        val next = transition(current)
            ?: return@withLock mutationRejected(LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED, current)
        capabilities.remove(fake)
        val nextCapability = if (kind == "TRANSFER") FakeCapability(next) else null
        val receipt = receipt(kind, commandId, current, next, fake.id, nextCapability?.id)
        states[current.operationId] = next
        receipts[hash(commandId)] = receipt
        if (nextCapability == null) {
            LifecycleOperationOwnerMutationResult.Released(receipt)
        } else {
            capabilities[nextCapability] = nextCapability.id
            LifecycleOperationOwnerMutationResult.Transferred(nextCapability, receipt)
        }
    }

    private fun descriptor(operationId: String) = LifecycleOperationOwnerDescriptor(
        operationId = operationId,
        lifecycleId = FullAppLifecycleId.LC_16,
        role = null,
        phase = LifecycleOperationOwnerPhase.AVAILABLE,
        generation = 0,
        authoritativeOperationUpdatedAtEpochMillis = 1,
    )

    private fun receipt(
        kind: String,
        commandId: String,
        from: LifecycleOperationOwnerDescriptor,
        to: LifecycleOperationOwnerDescriptor,
        fromCapability: String?,
        toCapability: String?,
    ) = LifecycleOperationOwnerTransitionReceipt(
        operationId = from.operationId,
        commandIdSha256 = hash(commandId),
        requestFingerprintSha256 = hash("$kind\u0000${hash(commandId)}\u0000${from.generation}"),
        processInstanceIdSha256 = PROCESS_ID,
        fromCapabilityIdSha256 = fromCapability,
        toCapabilityIdSha256 = toCapability,
        from = from,
        to = to,
        storeRevision = to.generation,
    )

    private fun acquireRejected(reason: LifecycleOperationOwnerRejection, current: LifecycleOperationOwnerDescriptor?) =
        LifecycleOperationOwnerAcquireResult.Rejected(reason, current)

    private fun mutationRejected(reason: LifecycleOperationOwnerRejection, current: LifecycleOperationOwnerDescriptor?) =
        LifecycleOperationOwnerMutationResult.Rejected(reason, current)

    private inner class FakeCapability(
        override val descriptor: LifecycleOperationOwnerDescriptor,
        val id: String = hash("fake-capability-${sequence.incrementAndGet()}"),
    ) : LifecycleOperationOwnerCapability

    private companion object {
        val PROCESS_ID = hash("fake-owner-process")
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
