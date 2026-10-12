/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.contentprotection.api.ContentVersionLineageStore
import io.element.android.security.contentprotection.api.ContentVersionNode
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.RegisterContentVersionRequest
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.TextVersionReceiptOwner
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface ProtectedCaptionLineageCoordinatorFactory {
    fun create(sessionId: SessionId, deviceId: DeviceId, roomId: RoomId): ProtectedCaptionLineageCoordinator
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultProtectedCaptionLineageCoordinatorFactory(
    private val pending: CaptionPendingStore,
    private val lineage: ContentVersionLineageStore,
    private val lifecycle: AppOperationalStateCoordinator,
    private val kms: KmsShareClient,
) : ProtectedCaptionLineageCoordinatorFactory {
    override fun create(sessionId: SessionId, deviceId: DeviceId, roomId: RoomId) =
        ProtectedCaptionLineageCoordinator(
            ContentVersionLineageScope(deriveAccountVaultId(sessionId.value, deviceId.value), roomId.value),
            pending, lineage, lifecycle, kms,
        )
}

/**
 * A caption edit is persisted before its KMS share and Matrix send. Only a confirmed own sync
 * event, after KMS event registration, can advance AWAITING_BINDING. The server's exact node is
 * journalled before local append; retries never send another Matrix event.
 */
class ProtectedCaptionLineageCoordinator(
    private val scope: ContentVersionLineageScope,
    private val pending: CaptionPendingStore,
    private val lineage: ContentVersionLineageStore,
    private val lifecycle: AppOperationalStateCoordinator,
    private val kms: KmsShareClient,
) : ProtectedCaptionLineageWiring {
    private val mutex = locks.computeIfAbsent("${scope.vaultId.value}\u0000${scope.roomId}") { Mutex() }

    /** Finish the exact durable root identity before any KMS share or Matrix send. */
    suspend fun prepareRoot(contentId: ContentId? = null): ContentId = mutex.withLock {
        val context = currentLifecycleOperationContext()
            ?: throw SecurityException("Initial protected caption requires a lifecycle operation.")
        require(context.lifecycleId in setOf(FullAppLifecycleId.LC_15, FullAppLifecycleId.LC_13)) {
            "Initial caption lineage registration requires LC-15 before KMS or Matrix effects."
        }
        val textOwner = currentCoroutineContext()[TextVersionReceiptOwner]?.takeIf { context.lifecycleId == FullAppLifecycleId.LC_13 }
        val receiptOwner = if (context.lifecycleId == FullAppLifecycleId.LC_13) {
            requireNotNull(textOwner) { "TEXT root requires its lifecycle receipt owner." }
            require(textOwner.operationId == context.operationId && textOwner.roomIdHash == digest(scope.roomId))
            CaptionServiceReceiptOwner(requireNotNull(textOwner.accountIdHash), requireNotNull(textOwner.matrixDeviceIdHash),
                requireNotNull(textOwner.roomIdHash))
        } else currentCoroutineContext()[MediaCaptionOperationContext]?.let { owner ->
            require(owner.captionOperationId == context.operationId && owner.roomIdHash == digest(scope.roomId))
            CaptionServiceReceiptOwner(owner.accountIdHash, owner.matrixDeviceIdHash, owner.roomIdHash)
        }
        val current = pending.all(scope)
        val reservation = pending.reserveRootOperation(scope, context.operationId, contentId?.value)
        val rootContentId = ContentId(reservation.contentId)
        if (reservation.phase != CaptionRootReservationPhase.RESERVED) {
            throw SecurityException("Initial caption operation already established a pending or committed root.")
        }
        require(contentId == null || contentId == rootContentId)
        val existing = current.firstOrNull { it.operationId == context.operationId }
        if (existing != null) {
            require(existing.contentId == rootContentId.value &&
                existing.rootContentId == rootContentId.value &&
                existing.parentContentId == null &&
                existing.phase == CaptionPendingPhase.AWAITING_BINDING.name &&
                existing.eventIdSha256 == null && existing.serviceReceiptOwner == receiptOwner) {
                "Initial caption operation already has a non-replayable pending state."
            }
            // A previous pending write succeeded but phase publication failed. No protect call ran.
            pending.advanceRootOperation(
                scope, context.operationId, rootContentId.value, CaptionRootReservationPhase.PENDING_ESTABLISHED,
            )
            textOwner?.delegated = true
            return@withLock rootContentId
        }
        require(current.none { it.contentId == rootContentId.value }) {
            "Initial caption root identity is already pending."
        }
        if (reservation.existing) {
            require(lineage.graph(scope, rootContentId) == null) {
                "Initial caption root is already committed locally."
            }
        }
        pending.put(scope, CaptionPending(
            operationId = context.operationId,
            targetEventId = "",
            contentId = rootContentId.value,
            rootContentId = rootContentId.value,
            parentContentId = null,
            originOperationId = context.operationId,
            lifecycleId = context.lifecycleId.name,
            parentExpiresAt = 0,
            serviceReceiptOwner = receiptOwner,
        ))
        // If this phase write fails, the pending record remains; the exact retry above finishes it.
        pending.advanceRootOperation(
            scope, context.operationId, rootContentId.value, CaptionRootReservationPhase.PENDING_ESTABLISHED,
        )
        textOwner?.delegated = true
        rootContentId
    }

    /** Resolve a confirmed current TEXT node, never guess a root from a shared event hash. */
    suspend fun prepareText(targetEventId: String, contentId: ContentId, expiresAt: Long): ProtectedCaptionEditPlan = mutex.withLock {
        val context = requireNotNull(currentLifecycleOperationContext())
        val owner = requireNotNull(currentCoroutineContext()[TextVersionReceiptOwner])
        require(context.lifecycleId == FullAppLifecycleId.LC_13 && owner.operationId == context.operationId &&
            owner.roomIdHash == digest(scope.roomId))
        val current = pending.all(scope)
        require(current.none { it.operationId == context.operationId || it.targetEventId == targetEventId }) {
            "TEXT edit outcome is pending; reconcile the original operation before another edit."
        }
        val rootId = lineage.rootForContent(scope, contentId)
            ?: throw SecurityException("TEXT version membership is unknown; historical lineage cannot be inferred.")
        val graph = requireNotNull(lineage.graph(scope, rootId))
        val node = graph.versions.singleOrNull { it.contentId == contentId }
            ?: throw SecurityException("TEXT target is not a registered version.")
        require(node.eventIdSha256 == digest(targetEventId) && node.expiresAtEpochMillis == expiresAt &&
            expiresAt > System.currentTimeMillis())
        require(graph.versions.none { it.parentContentId == contentId }) { "TEXT projection is not the current leaf." }
        val child = ContentId(UUID.randomUUID().toString())
        pending.put(scope, CaptionPending(context.operationId, targetEventId, child.value, rootId.value, contentId.value,
            graph.versions.single { it.parentContentId == null }.operationId, FullAppLifecycleId.LC_13.name, expiresAt,
            serviceReceiptOwner = CaptionServiceReceiptOwner(requireNotNull(owner.accountIdHash),
                requireNotNull(owner.matrixDeviceIdHash), requireNotNull(owner.roomIdHash))))
        owner.delegated = true
        ProtectedCaptionEditPlan(targetEventId, context.operationId, child)
    }

    suspend fun parentExpiry(contentId: ContentId): Long? = pending.all(scope)
        .singleOrNull { it.contentId == contentId.value && it.parentContentId != null }?.parentExpiresAt

    suspend fun textContentIdsForDestruction(contentId: ContentId): Set<ContentId> {
        val root = lineage.rootForContent(scope, contentId)
            ?: pending.all(scope).singleOrNull { it.contentId == contentId.value }?.rootContentId?.let(::ContentId)
            ?: throw SecurityException("TEXT destruction requires exact durable version membership.")
        return contentIdsForDestruction(root)
    }

    override suspend fun prepare(
        targetEventId: String,
        operationId: String,
        targetContentId: ContentId,
        targetExpiresAt: Long,
    ): ProtectedCaptionEditPlan = mutex.withLock {
        require(currentLifecycleOperationContext()?.let {
            it.operationId == operationId && it.lifecycleId == FullAppLifecycleId.LC_15
        } == true) { "Caption edit requires the active LC-15 operation." }
        require(targetEventId.isNotBlank() && targetExpiresAt > System.currentTimeMillis())
        pending.all(scope).firstOrNull { it.operationId == operationId }?.let { existing ->
            require(existing.targetEventId == targetEventId && existing.parentExpiresAt == targetExpiresAt)
            throw SecurityException("Caption operation is already pending authoritative sync; Matrix send will not be repeated.")
        }
        require(pending.all(scope).none { it.targetEventId == targetEventId }) { "A caption edit for this target is awaiting authoritative sync." }
        val graph = lineage.graph(scope, targetContentId)
            ?: throw SecurityException("The target caption lineage root is not registered.")
        val root = graph.versions.singleOrNull { it.parentContentId == null }
            ?: throw SecurityException("Caption lineage root is ambiguous.")
        require(root.contentId == targetContentId && root.eventIdSha256 == digest(targetEventId)) {
            "Caption target does not match its registered root event."
        }
        val parentIds = graph.versions.mapNotNull { it.parentContentId }.toSet()
        val leaf = graph.versions.filterNot { it.contentId in parentIds }.singleOrNull()
            ?: throw SecurityException("Caption lineage has competing leaves.")
        require(leaf.expiresAtEpochMillis <= targetExpiresAt) { "Caption target expiry is inconsistent." }
        val child = ContentId(UUID.randomUUID().toString())
        pending.put(scope, CaptionPending(
            operationId = operationId,
            targetEventId = targetEventId,
            contentId = child.value,
            rootContentId = root.contentId.value,
            parentContentId = leaf.contentId.value,
            originOperationId = root.operationId,
            parentExpiresAt = leaf.expiresAtEpochMillis,
        ))
        ProtectedCaptionEditPlan(targetEventId, operationId, child)
    }

    override suspend fun protectionOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) {
        protectionOutcomeUnknown(plan.childContentId)
    }

    override suspend fun matrixSendAccepted(plan: ProtectedCaptionEditPlan) {
        // The pending journal may already be retired by a faster authoritative sync.
    }

    override suspend fun matrixSendOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) {
        // An SDK exception cannot prove rejection. Preserve the encrypted pending record and KMS share.
        // The pending journal may already be retired by a faster authoritative sync.
    }

    /** Protection may have created a KMS share before it failed; retain the identity for reconciliation. */
    suspend fun protectionOutcomeUnknown(contentId: ContentId) = mutex.withLock {
        pending.all(scope).firstOrNull { it.contentId == contentId.value }
            ?.takeIf { it.phase == CaptionPendingPhase.AWAITING_BINDING.name }
            ?.let { pending.put(scope, it.copy(phase = CaptionPendingPhase.PROTECTION_OUTCOME_UNKNOWN.name)) }
    }

    suspend fun hasPendingContent(contentId: ContentId): Boolean =
        pending.all(scope).any { it.contentId == contentId.value }

    /** Called only after registerMessageEvent succeeded for this exact authenticated sync carrier. */
    suspend fun observeBoundEvent(contentId: ContentId, eventId: String, authenticatedExpiresAt: Long) = mutex.withLock {
        val item = pending.all(scope).firstOrNull { it.contentId == contentId.value } ?: return@withLock
        if (pending.isDestructionFenced(scope, item.contentId)) {
            if (item.phase != CaptionPendingPhase.DESTRUCTION_PENDING.name) {
                pending.put(scope, item.copy(phase = CaptionPendingPhase.DESTRUCTION_PENDING.name))
            }
            throw SecurityException("Caption destruction fence forbids event binding or local commit.")
        }
        require(eventId.isNotBlank() && authenticatedExpiresAt > 0 &&
            (item.parentContentId == null || authenticatedExpiresAt <= item.parentExpiresAt)) {
            "Bound caption expiry exceeds its registered parent."
        }
        val eventHash = digest(eventId)
        if (item.phase == CaptionPendingPhase.LINEAGE_COMMITTED.name) {
            require(item.eventIdSha256 == eventHash && item.expiresAt == authenticatedExpiresAt)
            deliverServiceReceipt(item)
            return@withLock
        }
        if (item.phase == CaptionPendingPhase.SERVER_REGISTERED.name) {
            if (item.lifecycleId != FullAppLifecycleId.LC_13.name) {
                throw SecurityException("Recovered server registration needs authoritative destroyed-state revalidation.")
            }
            require(item.eventIdSha256 == eventHash && item.expiresAt == authenticatedExpiresAt)
            val result = withContext(LifecycleOperationContext(item.operationId, FullAppLifecycleId.valueOf(item.lifecycleId))) {
                kms.registerContentVersion(RegisterContentVersionRequest(ContentId(item.contentId), ContentId(item.rootContentId),
                    item.parentContentId?.let(::ContentId), item.originOperationId, item.operationId, eventHash))
            }
            require(result.contentId.value == item.contentId && result.rootContentId.value == item.rootContentId &&
                result.parentContentId?.value == item.parentContentId && result.operationId == item.operationId &&
                result.eventIdSha256 == eventHash && result.expiresAtEpochMillis == authenticatedExpiresAt &&
                result.createdAtEpochMillis == item.createdAt)
            commitLocal(item)
            return@withLock
        }
        require(item.phase in setOf(CaptionPendingPhase.AWAITING_BINDING.name, CaptionPendingPhase.PROTECTION_OUTCOME_UNKNOWN.name) &&
            item.eventIdSha256 == null)
        val resumed = lifecycle.resume(item.operationId)
        val originalLifecycle = FullAppLifecycleId.valueOf(item.lifecycleId)
        require(resumed.lifecycleId == originalLifecycle && resumed.operationId == item.operationId) {
            "Caption lifecycle identity changed during recovery."
        }
        val result = withContext(LifecycleOperationContext(item.operationId, originalLifecycle)) {
            kms.registerContentVersion(RegisterContentVersionRequest(
                contentId = ContentId(item.contentId),
                rootContentId = ContentId(item.rootContentId),
                parentContentId = item.parentContentId?.let(::ContentId),
                originOperationId = item.originOperationId,
                operationId = item.operationId,
                expectedEventIdSha256 = eventHash,
            ))
        }
        require(result.contentId.value == item.contentId &&
            result.rootContentId.value == item.rootContentId &&
            result.parentContentId?.value == item.parentContentId &&
            result.operationId == item.operationId &&
            result.eventIdSha256 == eventHash &&
            result.expiresAtEpochMillis == authenticatedExpiresAt &&
            result.createdAtEpochMillis in 1..result.expiresAtEpochMillis) {
            "KMS version node differs from the authenticated Matrix binding."
        }
        val committed = item.copy(
            phase = CaptionPendingPhase.SERVER_REGISTERED.name,
            eventIdSha256 = result.eventIdSha256,
            expiresAt = result.expiresAtEpochMillis,
            createdAt = result.createdAtEpochMillis,
        )
        pending.put(scope, committed)
        commitLocal(committed)
    }

    /**
     * A server-registered record without a durable destruction fence is still ambiguous after
     * process death: both journal and fence writes could have failed before KMS erasure.
     * Without a proven destroyed-state API, automatic local append must fail closed.
     */
    suspend fun reconcileLocalCommits() = mutex.withLock {
        pending.all(scope).filter { it.phase == CaptionPendingPhase.LINEAGE_COMMITTED.name }.forEach { item ->
            deliverServiceReceipt(item)
        }
        pending.all(scope).filter { it.phase == CaptionPendingPhase.SERVER_REGISTERED.name }.forEach { item ->
            if (pending.isDestructionFenced(scope, item.contentId)) {
                pending.put(scope, item.copy(phase = CaptionPendingPhase.DESTRUCTION_PENDING.name))
            } else {
                throw SecurityException("Unfenced server-registered caption needs authoritative reconciliation.")
            }
        }
    }

    /**
     * LC-21 may destroy a share before its Matrix send outcome is known. Retain the encrypted
     * journal as cleanup debt and forbid a later sync from registering that destroyed version.
     */
    suspend fun markDestructionPending(contentId: ContentId) = mutex.withLock {
        val matches = pending.all(scope)
            .filter { it.contentId == contentId.value || it.rootContentId == contentId.value }
            .filter { it.phase in setOf(
                CaptionPendingPhase.AWAITING_BINDING.name,
                CaptionPendingPhase.PROTECTION_OUTCOME_UNKNOWN.name,
                CaptionPendingPhase.SERVER_REGISTERED.name,
                CaptionPendingPhase.LINEAGE_COMMITTED.name,
                CaptionPendingPhase.DESTRUCTION_PENDING.name,
            ) }
        var failure: Exception? = null
        for (item in matches) {
            // Independent encrypted tombstone first; a failed main-journal rewrite cannot erase it.
            try {
                pending.fenceDestruction(scope, item.contentId)
            } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
            if (item.phase != CaptionPendingPhase.DESTRUCTION_PENDING.name) {
                try {
                    pending.put(scope, item.copy(phase = CaptionPendingPhase.DESTRUCTION_PENDING.name))
                } catch (error: Exception) {
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    /** Keep debt visible through LC-21 destruction; no unreceipted local action can erase it. */
    suspend fun pendingForContentDestruction(contentId: ContentId): Boolean =
        pending.all(scope).any { it.contentId == contentId.value || it.rootContentId == contentId.value }

    /** Locally known caption versions and in-flight shares belonging to this exact room root. */
    suspend fun contentIdsForDestruction(rootContentId: ContentId): Set<ContentId> = mutex.withLock {
        val graph = lineage.graph(scope, rootContentId)
        check(graph == null || graph.rootContentId == rootContentId) { "Caption lineage returned a different root." }
        buildSet {
            add(rootContentId)
            graph?.versions?.forEach { node ->
                check(node.rootContentId == rootContentId) { "Caption lineage contains a foreign root." }
                add(node.contentId)
            }
            pending.all(scope).filter { it.rootContentId == rootContentId.value }.forEach { item ->
                add(ContentId(item.contentId))
            }
        }
    }

    private suspend fun commitLocal(item: CaptionPending) {
        require(item.phase == CaptionPendingPhase.SERVER_REGISTERED.name)
        check(!pending.isDestructionFenced(scope, item.contentId)) {
            "Caption destruction fence forbids local lineage append."
        }
        val hash = requireNotNull(item.eventIdSha256)
        val expiry = requireNotNull(item.expiresAt)
        val created = requireNotNull(item.createdAt)
        require(hash.matches(Regex("[0-9a-f]{64}")) && created in 1..expiry &&
            (item.parentContentId == null || expiry <= item.parentExpiresAt))
        lineage.append(scope, ContentVersionNode(
            contentId = ContentId(item.contentId),
            rootContentId = ContentId(item.rootContentId),
            parentContentId = item.parentContentId?.let(::ContentId),
            operationId = item.operationId,
            eventIdSha256 = hash,
            createdAtEpochMillis = created,
            expiresAtEpochMillis = expiry,
        ))
        if (item.parentContentId == null) {
            val reservation = pending.reserveRootOperation(scope, item.operationId, item.contentId)
            require(reservation.existing) { "Registered root lost its durable operation reservation." }
            if (reservation.phase == CaptionRootReservationPhase.RESERVED) {
                pending.advanceRootOperation(
                    scope, item.operationId, item.contentId, CaptionRootReservationPhase.PENDING_ESTABLISHED,
                )
            }
            if (reservation.phase != CaptionRootReservationPhase.COMMITTED) {
                // Publish COMMITTED before removing pending; a failed remove is safe to retry.
                pending.advanceRootOperation(
                    scope, item.operationId, item.contentId, CaptionRootReservationPhase.COMMITTED,
                )
            }
        }
        if (item.serviceReceiptOwner == null) {
            // Legacy/edit actions retain their existing owner. Only the explicit initial-media
            // handoff delegates the service receipt to this local lineage writer.
            pending.remove(scope, item.operationId)
        } else {
            val applied = item.copy(
                phase = CaptionPendingPhase.LINEAGE_COMMITTED.name,
                serviceAppliedAt = System.currentTimeMillis(),
            )
            pending.put(scope, applied)
            deliverServiceReceipt(applied)
        }
    }

    private suspend fun deliverServiceReceipt(item: CaptionPending) {
        require(item.phase == CaptionPendingPhase.LINEAGE_COMMITTED.name && item.lifecycleId in setOf(FullAppLifecycleId.LC_15.name, FullAppLifecycleId.LC_13.name))
        val owner = requireNotNull(item.serviceReceiptOwner)
        val appliedAt = requireNotNull(item.serviceAppliedAt)
        require(appliedAt > 0 && owner.roomIdHash == digest(scope.roomId))
        check(!pending.isDestructionFenced(scope, item.contentId)) { "Caption destruction fence forbids service completion." }
        val reference = if (item.lifecycleId == FullAppLifecycleId.LC_13.name) "android-service:${item.operationId}"
            else "android-service:caption-lineage:${item.operationId}"
        fun checkIdentity(operation: LifecycleOperation) {
            require(operation.operationId == item.operationId && operation.lifecycleId.name == item.lifecycleId &&
                operation.subjects.accountIdHash == owner.accountIdHash &&
                operation.subjects.matrixDeviceIdHash == owner.matrixDeviceIdHash &&
                operation.subjects.roomIdHash == owner.roomIdHash)
            require(!operation.status.isTerminal || operation.status == LifecycleOperationStatus.SUCCEEDED) {
                "A failed or cancelled caption operation cannot acknowledge local lineage completion."
            }
        }
        fun accepted(operation: LifecycleOperation): Boolean {
            checkIdentity(operation)
            val step = operation.steps.singleOrNull { it.component == LifecycleComponent.ANDROID_SERVICE }
                ?: throw SecurityException("Caption operation omitted its service receipt step.")
            if (step.status != LifecycleStepStatus.COMPLETE) return false
            require(step.required && step.receiptReference == reference && step.resultCode == "ANDROID_SERVICE_APPLIED") {
                "Caption service receipt belongs to a different action owner."
            }
            return true
        }
        // Read first: a prior delivery can have committed while its response was lost.
        val current = lifecycle.operation(item.operationId)
        if (!accepted(current)) {
            check(!current.status.isTerminal)
            val recorded = lifecycle.recordClientReceipt(LifecycleClientReceipt(
                operationId = item.operationId,
                component = LifecycleComponent.ANDROID_SERVICE,
                phase = LifecyclePhase.CLIENT_APPLIED,
                resultCode = "ANDROID_SERVICE_APPLIED",
                receiptReference = reference,
                occurredAtEpochMillis = appliedAt,
            ))
            check(accepted(recorded)) { "Caption service receipt was not acknowledged." }
        }
        check(!pending.isDestructionFenced(scope, item.contentId)) { "Caption destruction fence changed during completion." }
        pending.remove(scope, item.operationId)
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
