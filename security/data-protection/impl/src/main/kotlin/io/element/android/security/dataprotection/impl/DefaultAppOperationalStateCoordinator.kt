/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.security.dataprotection.api.AppLifecycleUiProjector
import io.element.android.security.dataprotection.api.AppLifecycleUiState
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
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
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AppOperationalStateCoordinator>())
@ContributesBinding(AppScope::class, binding = binding<LifecycleOperationOwnerAuthority>())
class DefaultAppOperationalStateCoordinator internal constructor(
    private val managementControlService: ManagementControlService,
    private val operationIdStore: LifecycleOperationIdStore,
    private val ownerStateStore: LifecycleOperationOwnerStateStore,
    private val processInstanceIdProvider: ProcessInstanceIdProvider,
    private val ownerNonceSource: LifecycleOperationOwnerNonceSource,
    private val receiptScope: CoroutineScope? = null,
    private val receiptDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pendingStartRetryClock: () -> Long = System::currentTimeMillis,
    private val draftInstallationProvider: (suspend () -> String)? = null,
) : AppOperationalStateCoordinator, LifecycleOperationOwnerAuthority {
    @Inject
    internal constructor(
        managementControlService: ManagementControlService,
        operationIdStore: LifecycleOperationIdStore,
        ownerStateStore: AtomicFileLifecycleOperationOwnerStateStore,
        processInstanceIdProvider: SecureRandomProcessInstanceIdProvider,
        ownerNonceSource: SecureRandomLifecycleOperationOwnerNonceSource,
        @AppCoroutineScope receiptScope: CoroutineScope,
        deviceTrustService: DeviceTrustService,
    ) : this(
        managementControlService = managementControlService,
        operationIdStore = operationIdStore,
        ownerStateStore = ownerStateStore as LifecycleOperationOwnerStateStore,
        processInstanceIdProvider = processInstanceIdProvider as ProcessInstanceIdProvider,
        ownerNonceSource = ownerNonceSource as LifecycleOperationOwnerNonceSource,
        receiptScope = receiptScope,
        draftInstallationProvider = { deviceTrustService.current().installationId },
    )

    internal constructor(managementControlService: ManagementControlService) :
        this(managementControlService, NoopLifecycleOperationIdStore)

    internal constructor(
        managementControlService: ManagementControlService,
        operationIdStore: LifecycleOperationIdStore,
        receiptScope: CoroutineScope? = null,
        receiptDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        pendingStartRetryClock: () -> Long = System::currentTimeMillis,
        draftInstallationProvider: (suspend () -> String)? = null,
    ) : this(
        managementControlService = managementControlService,
        operationIdStore = operationIdStore,
        ownerStateStore = FailClosedLifecycleOperationOwnerStateStore,
        processInstanceIdProvider = SecureRandomProcessInstanceIdProvider(),
        ownerNonceSource = SecureRandomLifecycleOperationOwnerNonceSource(),
        receiptScope = receiptScope,
        receiptDispatcher = receiptDispatcher,
        pendingStartRetryClock = pendingStartRetryClock,
        draftInstallationProvider = draftInstallationProvider,
    )

    private val mutation = Mutex()
    private val mediaClosureOwner = DurableMediaClosureOwner(
        managementControlService,
        ownerStateStore,
        mutation,
        processInstanceIdProvider,
        pendingStartRetryClock,
        ::retireRecoveredExternalOwner,
        ::operation,
    )
    private val mediaClosureWakeup = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow<AppOperationalCoordinatorState>(
        AppOperationalCoordinatorState.Discovering
    )
    private val operationProjections = LifecycleOperationProjections()
    private val mutableOperations = operationProjections.state
    private val verifiedOperationVersions = mutableMapOf<String, LifecycleOperation>()
    // A bounded snapshot is a projection, not authority to revoke an admitted execution.
    // Count overlapping callers so one cancellation cannot discard another caller's owner.
    private val resumingOperationUsers = mutableMapOf<String, Int>()
    private val mutableUiStates = MutableStateFlow(
        AppLifecycleUiProjector.projectAll(snapshot = null, operations = emptyMap())
    )
    private var lastVerifiedSnapshot: AppOperationalSnapshot? = null
    private var snapshotRevision = 0L
    private val pendingStarts = mutableMapOf<String, LifecycleOperationRequest>()
    private val pendingClientReceipts = linkedMapOf<Pair<String, LifecycleComponent>, LifecycleClientReceipt>()
    private var durableReceiptsLoaded = false
    private val pendingDraftStarts = linkedMapOf<String, PendingLifecycleDraftStart>()
    private val receiptWakeup = Channel<Unit>(Channel.CONFLATED)
    private val receiptFlush = Mutex()
    private val receiptTransport = Semaphore(2)
    private val activeReceiptSubmissions = mutableMapOf<LifecycleClientReceipt, CompletableDeferred<LifecycleOperation>>()
    private val receiptRetryAt = mutableMapOf<Pair<String, LifecycleComponent>, Long>()
    private val receiptFailures = mutableMapOf<Pair<String, LifecycleComponent>, Int>()
    private data class ResumingScope(
        val lifecycleId: FullAppLifecycleId,
        val trigger: String,
        val account: String?,
        val matrixDevice: String?,
        val room: String?,
        val event: String?,
    )
    private val pendingResumingStarts = mutableMapOf<ResumingScope, CompletableDeferred<LifecycleOperation>>()
    private val activeOwnerCapabilities = IdentityHashMap<DefaultLifecycleOperationOwnerCapability, OwnerCapabilityEntry>()
    private val retiredOwnerCapabilities = IdentityHashMap<DefaultLifecycleOperationOwnerCapability, OwnerCapabilityEntry>()
    internal var beforeOwnerCapabilityPublish: (() -> Unit)? = null

    override val state: StateFlow<AppOperationalCoordinatorState> = mutableState.asStateFlow()
    override val operations: StateFlow<Map<String, LifecycleOperation>> = mutableOperations
    override fun observeOperation(operationId: String) = operationProjections.observe(operationId)
    override fun observeLifecycleOperations(lifecycleId: FullAppLifecycleId) = operationProjections.observeLifecycle(lifecycleId)
    override val uiStates: StateFlow<Map<FullAppLifecycleId, AppLifecycleUiState>> = mutableUiStates.asStateFlow()

    init {
        if (ownerStateStore !== FailClosedLifecycleOperationOwnerStateStore) {
            receiptScope?.launch(receiptDispatcher) {
                for (wakeup in mediaClosureWakeup) {
                    try {
                        val result = mediaClosureOwner.flushBatch()
                        if (result.hasMore) {
                            mediaClosureWakeup.trySend(Unit)
                        } else if (result.needsRetry) {
                            delay(30_000)
                            mediaClosureWakeup.trySend(Unit)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        timber.log.Timber.tag("YiZheLifecycle").w("YZMEDIA_CLOSURE_DISCOVERY_PENDING")
                        delay(30_000)
                        mediaClosureWakeup.trySend(Unit)
                    }
                }
            }
            mediaClosureWakeup.trySend(Unit)
        }
        receiptScope?.launch {
            for (wakeup in receiptWakeup) {
                try {
                    flushPendingClientReceipts()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Persistence/transport failure never manufactures a successful receipt.
                    timber.log.Timber.tag("YiZheLifecycle").w("YZLC_RECEIPT_RECOVERY_PENDING")
                }
                if (mutation.withLock { pendingClientReceipts.isNotEmpty() }) {
                    delay(2_000)
                    receiptWakeup.trySend(Unit)
                }
            }
        }
    }

    override suspend fun reconcileFirstActivation(accountIdHash: String, matrixDeviceIdHash: String) {
        // A rejected start persists a local provisional ID that may never have existed remotely.
        // The signed index reconciles that exact ID, including live anonymous authentication,
        // before permitting a replacement. Transport/authority errors do not mean absence.
        val previousId = operationIdStore.latest(FullAppLifecycleId.LC_05)
        val recovered = managementControlService.startupLifecycleOperations(
            accountIdHash,
            matrixDeviceIdHash,
            "first_activation",
            previousId
        )
        recovered.forEach {
            check(
                it.lifecycleId == FullAppLifecycleId.LC_05 && it.subjects.accountIdHash == accountIdHash &&
                it.subjects.matrixDeviceIdHash == matrixDeviceIdHash
            )
        }
        mutation.withLock {
            if (previousId != null && recovered.none { it.operationId == previousId }) {
                // Retire only the stale local retry; no server terminal receipt is manufactured.
                pendingStarts.remove(previousId)
                operationProjections.replace(mutableOperations.value - previousId)
            }
            recovered.sortedBy(LifecycleOperation::createdAtEpochMillis).forEach(::remember)
        }
    }

    override suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot {
        val (previous, revisionBeforeRefresh, operationsBeforeRefresh) = mutation.withLock {
            val verifiedSnapshot = lastVerifiedSnapshot
            if (verifiedSnapshot == null) {
                mutableState.value = AppOperationalCoordinatorState.Discovering
            }
            Triple(verifiedSnapshot, snapshotRevision, mutableOperations.value.keys)
        }
        return try {
            val snapshot = managementControlService.appOperationalSnapshot(accountIdHash, matrixDeviceIdHash)
            val activeIds = snapshot.activeOperationIds.toSet()
            val recovered = activeIds.associateWith { operationId ->
                managementControlService.lifecycleOperation(operationId)
            }.toMutableMap()
            operationIdStore.latest(FullAppLifecycleId.LC_05)
                ?.takeUnless(activeIds::contains)
                ?.let { persistedOperationId ->
                    runCatching { managementControlService.lifecycleOperation(persistedOperationId) }
                        .getOrNull()
                        ?.takeIf { operation ->
                            operation.lifecycleId == FullAppLifecycleId.LC_05 &&
                                operation.subjects.accountIdHash == accountIdHash &&
                                operation.subjects.matrixDeviceIdHash == matrixDeviceIdHash
                        }
                        ?.let { recovered[persistedOperationId] = it }
                }
            mutation.withLock {
                if ((lastVerifiedSnapshot?.issuedAtEpochMillis ?: Long.MIN_VALUE) > snapshot.issuedAtEpochMillis) {
                    return@withLock
                }
                lastVerifiedSnapshot = snapshot
                if (previous?.accountIdHash != snapshot.accountIdHash || previous?.matrixDeviceIdHash != snapshot.matrixDeviceIdHash) {
                    receiptRetryAt.clear()
                    receiptFailures.clear()
                }
                timber.log.Timber.tag("YiZheLifecycle").d(
                    "YZLC_SNAPSHOT state=%s blockers=%s",
                    snapshot.overallStatus,
                    snapshot.blockers.filter { it.matches(Regex("[A-Z0-9_]{1,80}")) }.joinToString(","),
                )
                snapshotRevision += 1
                mutableState.value = AppOperationalCoordinatorState.Available(snapshot)
                operationProjections.replace(mutableOperations.value
                    .filterValues { operation ->
                        operation.status.isTerminal ||
                            operation.operationId in pendingDraftStarts ||
                            operation.operationId in resumingOperationUsers ||
                            pendingClientReceipts.keys.any { it.first == operation.operationId } ||
                            operation.operationId in activeIds ||
                            operation.operationId !in operationsBeforeRefresh
                    })
                // Operation replies can predate the snapshot or a concurrently applied receipt.
                // Use the same monotonic authority guard as direct start/resume/receipt replies.
                recovered.values.forEach(::remember)
                updateUiStates(snapshot)
            }
            if (receiptScope == null) flushPendingClientReceipts() else receiptWakeup.trySend(Unit)
            snapshot
        } catch (failure: Exception) {
            mutation.withLock {
                if (snapshotRevision == revisionBeforeRefresh) {
                    mutableState.value = AppOperationalCoordinatorState.RetryableFailure(
                        reasonCode = "APP_OPERATIONAL_SNAPSHOT_RETRY_REQUIRED",
                        lastVerifiedSnapshot = previous,
                    )
                }
            }
            throw failure
        }
    }

    override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation {
        val provisional = request.toProvisionalOperation()
        mutation.withLock {
            pendingStarts[request.operationId] = request
            rememberProvisional(provisional)
        }
        return try {
            val operation = LifecycleStageTiming.measure("lifecycle_start", request.trigger, operationId = request.operationId) {
                managementControlService.startLifecycleOperation(request)
            }
            mutation.withLock {
                if (pendingStarts[request.operationId] == request) {
                    pendingStarts.remove(request.operationId)
                }
                if (operation.operationId != request.operationId) {
                    operationProjections.replace(mutableOperations.value - request.operationId)
                }
                remember(operation)
            }
            operation
        } catch (failure: Exception) {
            // Cancellation cannot abandon original start bookkeeping at an already-cancelled mutex.
            // This is a local retry intent, never a claim that the server cancelled/completed the operation.
            withContext(NonCancellable) {
                mutation.withLock {
                    if (pendingStarts[request.operationId] == request) {
                        rememberProvisional(
                            provisional.copy(
                                status = LifecycleOperationStatus.RETRYABLE_FAILURE,
                                updatedAtEpochMillis = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
            throw failure
        }
    }

    override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit) {
        val installation = draftInstallationProvider?.invoke() ?: mutation.withLock { lastVerifiedSnapshot?.installationId }
        val entry = PendingLifecycleDraftStart(request, checkNotNull(installation) { "Draft installation identity is unavailable." })
        require(request.deadlineEpochMillis > pendingStartRetryClock()) { "Draft intent has expired." }
        val shouldApply = withContext(NonCancellable + receiptDispatcher) {
            mutation.withLock {
                loadDurableReceiptsLocked()
                val previous = pendingDraftStarts[request.operationId]
                check(previous == null || previous == entry) { "Draft lifecycle intent is immutable." }
                if (previous != null) {
                    check(pendingClientReceipts.keys.any { it.first == request.operationId }) { "Original draft outcome is unresolved." }
                    false
                } else {
                    val next = pendingDraftStarts + (request.operationId to entry)
                    operationIdStore.savePendingDraftState(next.values.toList(), pendingClientReceipts.values.toList())
                    pendingDraftStarts[request.operationId] = entry
                    rememberProvisional(request.toProvisionalOperation())
                    true
                }
            }
        }
        if (!shouldApply) {
            receiptWakeup.trySend(Unit)
            return
        }
        val localResult = io.element.android.security.dataprotection.api.LifecycleDraftWriteContext(request.operationId)
        withContext(LifecycleOperationContext(request.operationId, FullAppLifecycleId.LC_14) + localResult) { block(request.operationId) }
        if (localResult.hasDurableResultOwner) return // The store's atomic result journal survives a process exit here.
        val occurredAt = pendingStartRetryClock()
        enqueueClientReceipts(listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
            LifecycleClientReceipt(
                request.operationId,
                component,
                LifecyclePhase.CLIENT_APPLIED,
                "${component.name}_APPLIED",
                "${component.name.lowercase().replace('_', '-')}:${request.operationId}",
                occurredAt
            )
        })
    }

    override suspend fun operation(operationId: String): LifecycleOperation {
        val operation = managementControlService.lifecycleOperation(operationId)
        mutation.withLock { remember(operation) }
        return operation
    }

    override suspend fun validateLocalDraftRecovery(operationId: String, accountIdHash: String, matrixDeviceIdHash: String, roomIdHash: String) {
        val installation = draftInstallationProvider?.invoke() ?: mutation.withLock { lastVerifiedSnapshot?.installationId }
        mutation.withLock {
            loadDurableReceiptsLocked()
            val original = checkNotNull(pendingDraftStarts[operationId]) { "Original local draft intent is unavailable." }
            require(original.installationId == installation && original.request.accountIdHash == accountIdHash &&
                original.request.matrixDeviceIdHash == matrixDeviceIdHash && original.request.roomIdHash == roomIdHash) {
                "Original local draft scope does not match this installation."
            }
        }
    }

    override suspend fun recoverLocalDraftApplied(fact: io.element.android.security.dataprotection.api.LifecycleDraftApplied): Boolean {
        val original = mutation.withLock {
            loadDurableReceiptsLocked()
            pendingDraftStarts[fact.operationId]
        }
        if (original == null) {
            // A crash after handoff may leave a duplicate store fact after the outbox was acknowledged.
            // Only the exact signed authority result can retire that duplicate.
            val operation = operation(fact.operationId)
            val installation = draftInstallationProvider?.invoke() ?: mutation.withLock { lastVerifiedSnapshot?.installationId }
            require(
                operation.lifecycleId == FullAppLifecycleId.LC_14 && operation.subjects.installationId == installation &&
                operation.subjects.accountIdHash == fact.accountIdHash && operation.subjects.matrixDeviceIdHash == fact.matrixDeviceIdHash &&
                operation.subjects.roomIdHash == fact.roomIdHash
            )
            return fact.receipts().all { receipt ->
                operation.steps.any { step ->
                step.component == receipt.component && step.status == io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE &&
                    step.receiptReference == receipt.receiptReference
            }
            }
        }
        validateLocalDraftRecovery(fact.operationId, fact.accountIdHash, fact.matrixDeviceIdHash, fact.roomIdHash)
        withContext(NonCancellable + receiptDispatcher) {
            mutation.withLock {
                check(pendingDraftStarts[fact.operationId] == original) { "Draft result handoff changed during recovery." }
                val next = pendingClientReceipts.toMutableMap()
                fact.receipts().forEach { receipt ->
                    val previous = next[receipt.operationId to receipt.component]
                    require(previous == null || previous == receipt) { "Original draft applied fact is immutable." }
                    next[receipt.operationId to receipt.component] = receipt
                }
                operationIdStore.savePendingDraftState(pendingDraftStarts.values.toList(), next.values.toList())
                pendingClientReceipts.clear()
                pendingClientReceipts.putAll(next)
                // A recovered fact may predate this process and the original deadline.
                // Do not invent a new provisional creation time; replay reads the original authority state.
            }
        }
        receiptWakeup.trySend(Unit)
        return true
    }

    override suspend fun authorizePreparedRelayRetry(query: io.element.android.security.dataprotection.api.ProtectedEventOriginQuery,
        operationId: String, roomId: String,
    ) = managementControlService.authorizePreparedRelayRetry(query, operationId, roomId)

    override suspend fun protectedEventOrigin(query: io.element.android.security.dataprotection.api.ProtectedEventOriginQuery): LifecycleOperationContext? =
        managementControlService.protectedEventOrigin(query)

    override suspend fun recordMediaObjectCleanup(fact: io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup): String =
        managementControlService.recordMediaObjectCleanup(fact)

    override suspend fun reserveMediaResources(request: LifecycleMediaReservation) = withContext(receiptDispatcher) {
        mediaClosureOwner.reserve(request)
    }

    override suspend fun sealMediaResources(operationId: String, outcome: LifecycleMediaOutcome) =
        withContext(NonCancellable + receiptDispatcher) {
            mediaClosureOwner.seal(operationId, outcome)
            mediaClosureWakeup.trySend(Unit)
            try {
                mediaClosureOwner.awaitSealAcknowledgement(operationId)
            } finally {
                // Failure is pending durable work, never local stop success.
                mediaClosureWakeup.trySend(Unit)
            }
            Unit
        }

    override suspend fun retainMediaRuntimeCleanup(fact: LifecycleMediaRuntimeCleanup) =
        withContext(NonCancellable + receiptDispatcher) {
            mediaClosureOwner.retain(fact)
            mediaClosureWakeup.trySend(Unit)
            Unit
        }

    override suspend fun reconcileRelayPreparation(
        query: io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery,
    ): LifecycleOperation {
        val operation = managementControlService.reconcileRelayPreparation(query)
        mutation.withLock { remember(operation) }
        return operation
    }

    override suspend fun resume(operationId: String): LifecycleOperation {
        val pending = mutation.withLock { pendingStarts[operationId] }
        val operation = if (pending != null) {
            managementControlService.startLifecycleOperation(pending)
        } else {
            managementControlService.resumeLifecycleOperation(operationId)
        }
        mutation.withLock {
            if (pending != null && pendingStarts[operationId] == pending) {
                pendingStarts.remove(operationId)
            }
            remember(operation)
        }
        return operation
    }

    override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation {
        val key = receipt.operationId to receipt.component
        val durable = withContext(NonCancellable + receiptDispatcher) {
            mutation.withLock {
                loadDurableReceiptsLocked()
                val next = pendingClientReceipts.toMutableMap()
                next.putIfAbsent(key, receipt)
                operationIdStore.savePendingClientReceipts(next.values.toList())
                pendingClientReceipts.clear()
                pendingClientReceipts.putAll(next)
                next.getValue(key)
            }
        }
        try {
            return submitClientReceipt(durable)
        } finally {
            // Transport failure or caller cancellation retains the committed fact and wakes
            // the existing bounded outbox; no new operation or applied fact is manufactured.
            receiptWakeup.trySend(Unit)
        }
    }

    /** Direct startup submission and the recovery outbox share only the identical immutable fact. */
    private suspend fun submitClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation {
        val (submission, ownsSubmission) = mutation.withLock {
            activeReceiptSubmissions[receipt]?.let { it to false } ?: CompletableDeferred<LifecycleOperation>().let {
                activeReceiptSubmissions[receipt] = it
                it to true
            }
        }
        if (!ownsSubmission) return submission.await()
        try {
            val operation = LifecycleStageTiming.measure("receipt_submit", receipt.component.name, operationId = receipt.operationId) {
                managementControlService.recordLifecycleClientReceipt(receipt)
            }
            check(operation.operationId == receipt.operationId)
            acknowledgeClientReceipt(receipt.operationId to receipt.component, receipt, operation)
            submission.complete(operation)
            return operation
        } catch (failure: Throwable) {
            submission.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                mutation.withLock { activeReceiptSubmissions.remove(receipt, submission) }
            }
        }
    }

    override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) {
        persistClientReceipts(receipts)
        receiptWakeup.trySend(Unit)
    }

    override suspend fun persistClientReceipts(receipts: List<LifecycleClientReceipt>) {
        withContext(NonCancellable + receiptDispatcher) {
            mutation.withLock {
                loadDurableReceiptsLocked()
                val next = pendingClientReceipts.toMutableMap()
                receipts.forEach { receipt ->
                    // Projection membership may have disappeared during actual product work.
                    // Only an authority-verified admission (or the existing durable local-draft
                    // intent) can own facts. A response-lost provisional start is not admission.
                    check(verifiedOperationVersions.containsKey(receipt.operationId) || receipt.operationId in pendingDraftStarts) {
                        "Client receipts require an admitted operation."
                    }
                    next.putIfAbsent(receipt.operationId to receipt.component, receipt)
                }
                operationIdStore.savePendingClientReceipts(next.values.toList())
                pendingClientReceipts.clear()
                pendingClientReceipts.putAll(next)
            }
        }
    }

    override suspend fun <T> performResuming(
        request: LifecycleOperationRequest,
        hasNewStartupIntent: Boolean,
        block: suspend (operationId: String) -> T,
    ): T {
        val loginAccountIdHash = request.accountIdHash
        val loginMatrixDeviceIdHash = request.matrixDeviceIdHash
        if (request.lifecycleId == FullAppLifecycleId.LC_06 &&
            loginAccountIdHash != null && loginMatrixDeviceIdHash != null
        ) {
            val hasKnownAttempt = mutation.withLock {
                mutableOperations.value.values.any {
                    it.lifecycleId == FullAppLifecycleId.LC_06 && it.status in NON_TERMINAL_RESUMABLE_STATUSES &&
                        it.subjects.accountIdHash == request.accountIdHash && it.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
                        it.subjects.roomIdHash == request.roomIdHash && it.subjects.eventIdHash == request.eventIdHash
                }
            }
            if (!hasKnownAttempt) {
                // A fresh process has an empty in-memory map, not evidence that no startup
                // operation exists. Reconcile the signed server snapshot before admitting a
                // new login intent. A failed read must not create a competing operation.
                // Known response-lost submissions retain their original request and deadline.
                if (request.trigger in setOf("post_login", "reactivation")) {
                    // A candidate installation has not earned an active account binding yet.
                    // Reconcile only its signed LC06 intent index, without an admitted-state snapshot.
                    val recovered = managementControlService.startupLifecycleOperations(
                        loginAccountIdHash,
                        loginMatrixDeviceIdHash,
                        request.trigger
                    )
                    mutation.withLock {
                        recovered.forEach { operation ->
                            check(
                                operation.lifecycleId == FullAppLifecycleId.LC_06 &&
                                operation.subjects.accountIdHash == loginAccountIdHash &&
                                operation.subjects.matrixDeviceIdHash == loginMatrixDeviceIdHash
                            )
                            remember(operation)
                        }
                    }
                } else {
                    refresh(loginAccountIdHash, loginMatrixDeviceIdHash)
                }
            }
        }
        val scope =
            ResumingScope(request.lifecycleId, request.trigger, request.accountIdHash, request.matrixDeviceIdHash, request.roomIdHash, request.eventIdHash)
        val resolution = mutation.withLock {
            val existing = mutableOperations.value.values
                .asSequence()
                .filter {
                    it.lifecycleId == request.lifecycleId && it.status in NON_TERMINAL_RESUMABLE_STATUSES &&
                        ((it.operationId == request.operationId && pendingStarts.containsKey(it.operationId)) || (
                        it.subjects.accountIdHash == request.accountIdHash && it.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
                        it.subjects.roomIdHash == request.roomIdHash && it.subjects.eventIdHash == request.eventIdHash &&
                        pendingStarts[it.operationId]?.trigger?.let { trigger -> trigger == request.trigger } != false
                        ))
                }
                .maxByOrNull(LifecycleOperation::updatedAtEpochMillis)
            if (existing != null) {
                val pending = pendingStarts[existing.operationId]
                val now = pendingStartRetryClock()
                if (hasNewStartupIntent && request.lifecycleId == FullAppLifecycleId.LC_02 &&
                    request.trigger == "process_create" && pending?.trigger == request.trigger &&
                    pending.deadlineEpochMillis <= now && request.deadlineEpochMillis > now &&
                    pending.accountIdHash == request.accountIdHash && pending.matrixDeviceIdHash == request.matrixDeviceIdHash &&
                    pending.roomIdHash == request.roomIdHash && pending.eventIdHash == request.eventIdHash
                ) {
                    // Only the still-unconfirmed LC-02 start submission gets the current caller's
                    // bounded retry deadline. Keep its operation/idempotency identity and subjects.
                    // An already-admitted server operation is returned idempotently with its original
                    // authoritative deadline; this cannot extend it or revive a terminal operation.
                    pendingStarts[existing.operationId] = pending.copy(deadlineEpochMillis = request.deadlineEpochMillis)
                }
                ResumingResolution.Existing(existing.operationId)
            } else {
                pendingResumingStarts[scope]?.let { ResumingResolution.WaitFor(it) }
                    ?: CompletableDeferred<LifecycleOperation>().also {
                        pendingResumingStarts[scope] = it
                    }.let { ResumingResolution.StartNew(it) }
            }
        }
        val operation = when (resolution) {
            is ResumingResolution.Existing -> resume(resolution.operationId)
            is ResumingResolution.WaitFor -> resolution.deferred.await()
            is ResumingResolution.StartNew -> {
                if (!hasNewStartupIntent) {
                    val failure = IllegalStateException("A new lifecycle operation requires a new startup intent.")
                    resolution.deferred.completeExceptionally(failure)
                    mutation.withLock { pendingResumingStarts.remove(scope, resolution.deferred) }
                    throw failure
                }
                try {
                    start(request).also { resolution.deferred.complete(it) }
                } catch (failure: Throwable) {
                    resolution.deferred.completeExceptionally(failure)
                    throw failure
                } finally {
                    mutation.withLock { pendingResumingStarts.remove(scope, resolution.deferred) }
                }
            }
        }
        if (request.lifecycleId == FullAppLifecycleId.LC_06 && resolution is ResumingResolution.Existing) {
            require(operation.operationId == resolution.operationId) { "Startup resume must preserve the original operation." }
        }
        val installation = if (request.lifecycleId == FullAppLifecycleId.LC_06) draftInstallationProvider?.invoke() else null
        val admitted = mutation.withLock {
            val latest = checkNotNull(verifiedOperationVersions[operation.operationId]) { "Lifecycle admission is unavailable." }
            if (request.lifecycleId == FullAppLifecycleId.LC_06) {
                require(latest.lifecycleId == request.lifecycleId &&
                    latest.subjects.accountIdHash == request.accountIdHash && latest.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
                    latest.subjects.roomIdHash == request.roomIdHash && latest.subjects.eventIdHash == request.eventIdHash &&
                    (installation == null || latest.subjects.installationId == installation)) {
                    "Startup admission does not match the current intent."
                }
                require(!latest.status.isTerminal && latest.deadlineEpochMillis > pendingStartRetryClock()) {
                    "Original startup operation is no longer active."
                }
            }
            resumingOperationUsers[latest.operationId] = (resumingOperationUsers[latest.operationId] ?: 0) + 1
            remember(latest)
            latest
        }
        try {
            return withContext(LifecycleOperationContext(admitted.operationId, admitted.lifecycleId)) {
                perform(request, block)
            }
        } finally {
            withContext(NonCancellable) {
                mutation.withLock {
                    val users = resumingOperationUsers.getValue(admitted.operationId)
                    if (users == 1) resumingOperationUsers.remove(admitted.operationId)
                    else resumingOperationUsers[admitted.operationId] = users - 1
                }
            }
        }
    }

    override suspend fun ownerState(operationId: String): LifecycleOperationOwnerDescriptor? {
        require(operationId.length in 16..256)
        val exists = try {
            mutation.withLock { ownerStateStore.read(operationId) != null }
        } catch (failure: Exception) {
            throw IllegalStateException("Unable to read lifecycle owner state.", failure)
        }
        if (!exists) return null
        val operation = operation(operationId)
        return try {
            mutation.withLock { reconcileOwnerLocked(ownerStateStore.read(operationId), operation)?.descriptor }
        } catch (failure: Exception) {
            throw IllegalStateException("Unable to reconcile lifecycle owner state.", failure)
        }
    }

    override suspend fun acquireOwner(
        operationId: String,
        role: LifecycleOperationOwnerRole,
        commandId: String,
        expectedRecoveryGeneration: Long?,
    ): LifecycleOperationOwnerAcquireResult {
        require(operationId.length in 16..256)
        require(commandId.length in 16..256)
        require(expectedRecoveryGeneration == null || expectedRecoveryGeneration >= 0)
        val operation = loadOwnerOperation(operationId)
            ?: return LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.AUTHORITY_UNAVAILABLE,
                current = null,
            )
        return try {
            mutation.withLock {
                val currentRecord = reconcileOwnerLocked(ownerStateStore.read(operationId), operation)
                ownerOperationRejection(operation)?.let {
                    return@withLock LifecycleOperationOwnerAcquireResult.Rejected(it, currentRecord?.descriptor)
                }
                acquireOwnerLocked(currentRecord, operation, role, commandId, expectedRecoveryGeneration)
            }
        } catch (failure: OwnerGenerationExhaustedException) {
            LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.GENERATION_EXHAUSTED,
                current = runCatching { ownerStateStore.read(operationId)?.descriptor }.getOrNull(),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE,
                current = null,
            )
        }
    }

    override suspend fun transferOwner(
        capability: LifecycleOperationOwnerCapability,
        toRole: LifecycleOperationOwnerRole,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult = mutateOwner(
        capability = capability,
        commandId = commandId,
        kind = OwnerMutationKind.TRANSFER,
        toRole = toRole,
        cancelReason = null,
    )

    override suspend fun completeOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
    ): LifecycleOperationOwnerMutationResult = mutateOwner(
        capability = capability,
        commandId = commandId,
        kind = OwnerMutationKind.COMPLETE,
        toRole = null,
        cancelReason = null,
    )

    override suspend fun cancelOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
        reason: LifecycleOperationOwnerCancelReason,
    ): LifecycleOperationOwnerMutationResult = mutateOwner(
        capability = capability,
        commandId = commandId,
        kind = OwnerMutationKind.CANCEL,
        toRole = null,
        cancelReason = reason,
    )

    private suspend fun mutateOwner(
        capability: LifecycleOperationOwnerCapability,
        commandId: String,
        kind: OwnerMutationKind,
        toRole: LifecycleOperationOwnerRole?,
        cancelReason: LifecycleOperationOwnerCancelReason?,
    ): LifecycleOperationOwnerMutationResult {
        require(commandId.length in 16..256)
        val privateCapability = capability as? DefaultLifecycleOperationOwnerCapability
            ?: return rejectedOwnerMutation(LifecycleOperationOwnerRejection.CAPABILITY_INVALID, capability.descriptor)
        if (privateCapability.processInstanceIdSha256 != processInstanceIdProvider.sha256) {
            return rejectedOwnerMutation(LifecycleOperationOwnerRejection.PROCESS_INSTANCE_MISMATCH, capability.descriptor)
        }
        val operation = loadOwnerOperation(capability.descriptor.operationId)
            ?: return rejectedOwnerMutation(LifecycleOperationOwnerRejection.AUTHORITY_UNAVAILABLE, capability.descriptor)
        return try {
            mutation.withLock {
                val record = reconcileOwnerLocked(ownerStateStore.read(operation.operationId), operation)
                val entry = activeOwnerCapabilities[privateCapability]
                    ?: retiredOwnerCapabilities[privateCapability]
                    ?: return@withLock rejectedOwnerMutation(
                        LifecycleOperationOwnerRejection.CAPABILITY_INVALID,
                        record?.descriptor,
                    )
                val commandHash = sha256Hex(commandId)
                record?.transitions?.firstOrNull { it.commandIdSha256 == commandHash }?.let { receipt ->
                    val replayFingerprint = ownerRequestFingerprint(
                        kind = kind.name,
                        operationId = operation.operationId,
                        commandIdSha256 = commandHash,
                        from = receipt.from,
                        toRole = toRole,
                        expectedRecoveryGeneration = null,
                        cancelReason = cancelReason,
                        fromCapabilityIdSha256 = entry.capabilityIdSha256,
                    )
                    if (receipt.requestFingerprintSha256 != replayFingerprint ||
                        receipt.fromCapabilityIdSha256 != entry.capabilityIdSha256
                    ) {
                        return@withLock rejectedOwnerMutation(
                            LifecycleOperationOwnerRejection.COMMAND_CONFLICT,
                            record.descriptor,
                        )
                    }
                    return@withLock replayOwnerMutation(kind, receipt, record)
                }
                if (activeOwnerCapabilities[privateCapability] == null) {
                    return@withLock rejectedOwnerMutation(
                        LifecycleOperationOwnerRejection.STALE_GENERATION,
                        record?.descriptor,
                    )
                }
                ownerOperationRejection(operation)?.let {
                    return@withLock rejectedOwnerMutation(it, record?.descriptor)
                }
                if (record == null || record.descriptor != entry.descriptor ||
                    record.currentCapabilityIdSha256 != entry.capabilityIdSha256
                ) {
                    return@withLock rejectedOwnerMutation(
                        LifecycleOperationOwnerRejection.STALE_GENERATION,
                        record?.descriptor,
                    )
                }
                mutateOwnerLocked(record, operation, privateCapability, entry, commandHash, kind, toRole, cancelReason)
            }
        } catch (failure: OwnerGenerationExhaustedException) {
            rejectedOwnerMutation(
                LifecycleOperationOwnerRejection.GENERATION_EXHAUSTED,
                runCatching { ownerStateStore.read(operation.operationId)?.descriptor }.getOrNull(),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            rejectedOwnerMutation(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE, current = null)
        }
    }

    private fun acquireOwnerLocked(
        record: StoredLifecycleOperationOwnerState?,
        operation: LifecycleOperation,
        role: LifecycleOperationOwnerRole,
        commandId: String,
        expectedRecoveryGeneration: Long?,
    ): LifecycleOperationOwnerAcquireResult {
        val commandHash = sha256Hex(commandId)
        record?.transitions?.firstOrNull { it.commandIdSha256 == commandHash }?.let { receipt ->
            val fingerprint = ownerRequestFingerprint(
                kind = "ACQUIRE",
                operationId = operation.operationId,
                commandIdSha256 = commandHash,
                from = receipt.from,
                toRole = role,
                expectedRecoveryGeneration = expectedRecoveryGeneration,
                cancelReason = null,
                fromCapabilityIdSha256 = null,
            )
            if (receipt.requestFingerprintSha256 != fingerprint || receipt.fromCapabilityIdSha256 != null) {
                return LifecycleOperationOwnerAcquireResult.Rejected(
                    LifecycleOperationOwnerRejection.COMMAND_CONFLICT,
                    record.descriptor,
                )
            }
            findActiveCapability(receipt.toCapabilityIdSha256)?.let {
                return LifecycleOperationOwnerAcquireResult.Granted(it, receipt)
            }
            val reason = when {
                record.descriptor.phase == LifecycleOperationOwnerPhase.RECOVERY_REQUIRED ->
                    LifecycleOperationOwnerRejection.RECOVERY_REQUIRED
                receipt.toCapabilityIdSha256?.let(::hasRetiredCapability) == true ->
                    LifecycleOperationOwnerRejection.STALE_GENERATION
                else -> LifecycleOperationOwnerRejection.CAPABILITY_PUBLICATION_FAILED
            }
            return LifecycleOperationOwnerAcquireResult.Rejected(reason, record.descriptor)
        }

        val from = record?.descriptor ?: LifecycleOperationOwnerDescriptor(
            operationId = operation.operationId,
            lifecycleId = operation.lifecycleId,
            role = null,
            phase = LifecycleOperationOwnerPhase.AVAILABLE,
            generation = 0,
            authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
        )
        if (role == LifecycleOperationOwnerRole.EXTERNAL_READ &&
            operation.authorityRevisions["external_read_handoff"] != "uid-bound-v1") {
            return LifecycleOperationOwnerAcquireResult.Rejected(LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED, from)
        }
        when (from.phase) {
            LifecycleOperationOwnerPhase.OWNED -> return LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.OWNER_ALREADY_HELD,
                from,
            )
            LifecycleOperationOwnerPhase.COMPLETED -> return LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED,
                from,
            )
            LifecycleOperationOwnerPhase.RECOVERY_REQUIRED -> {
                if (expectedRecoveryGeneration != from.generation || role != from.role) {
                    return LifecycleOperationOwnerAcquireResult.Rejected(
                        LifecycleOperationOwnerRejection.RECOVERY_MISMATCH,
                        from,
                    )
                }
            }
            LifecycleOperationOwnerPhase.AVAILABLE -> {
                if (expectedRecoveryGeneration != null || record == null && role !in
                    setOf(LifecycleOperationOwnerRole.PREVIEW, LifecycleOperationOwnerRole.EXTERNAL_READ)) {
                    return LifecycleOperationOwnerAcquireResult.Rejected(
                        LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED,
                        from,
                    )
                }
            }
        }
        val capability = newOwnerCapability(
            from.copy(
                role = role,
                phase = LifecycleOperationOwnerPhase.OWNED,
                generation = nextOwnerGeneration(from.generation),
                authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
            )
        )
        val revision = (record?.storeRevision ?: 0L) + 1L
        val receipt = ownerTransitionReceipt(
            kind = "ACQUIRE",
            commandHash = commandHash,
            from = from,
            to = capability.descriptor,
            revision = revision,
            fromCapabilityIdSha256 = null,
            toCapabilityIdSha256 = capability.capabilityIdSha256,
            expectedRecoveryGeneration = expectedRecoveryGeneration,
            cancelReason = null,
        )
        val next = StoredLifecycleOperationOwnerState(
            descriptor = capability.descriptor,
            processInstanceIdSha256 = processInstanceIdProvider.sha256,
            currentCapabilityIdSha256 = capability.capabilityIdSha256,
            storeRevision = revision,
            transitions = record?.transitions.orEmpty() + receipt,
            mediaClosure = record?.mediaClosure,
        )
        if (!ownerStateStore.compareAndSet(operation.operationId, record?.storeRevision, next)) {
            return LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE,
                record?.descriptor,
            )
        }
        return try {
            beforeOwnerCapabilityPublish?.invoke()
            activateCapability(capability)
            LifecycleOperationOwnerAcquireResult.Granted(capability, receipt)
        } catch (failure: Exception) {
            val recovered = recoverPublicationFailureLocked(next, operation)
            LifecycleOperationOwnerAcquireResult.Rejected(
                LifecycleOperationOwnerRejection.CAPABILITY_PUBLICATION_FAILED,
                recovered.descriptor,
            )
        }
    }

    private fun mutateOwnerLocked(
        record: StoredLifecycleOperationOwnerState,
        operation: LifecycleOperation,
        capability: DefaultLifecycleOperationOwnerCapability,
        entry: OwnerCapabilityEntry,
        commandHash: String,
        kind: OwnerMutationKind,
        toRole: LifecycleOperationOwnerRole?,
        cancelReason: LifecycleOperationOwnerCancelReason?,
    ): LifecycleOperationOwnerMutationResult {
        val from = record.descriptor
        val to = when (kind) {
            OwnerMutationKind.TRANSFER -> {
                if (from.role != LifecycleOperationOwnerRole.PREVIEW || toRole != LifecycleOperationOwnerRole.UPLOAD) {
                    return rejectedOwnerMutation(LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED, from)
                }
                from.copy(
                    role = LifecycleOperationOwnerRole.UPLOAD,
                    generation = nextOwnerGeneration(from.generation),
                    authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
                )
            }
            OwnerMutationKind.COMPLETE -> {
                if (from.role !in setOf(LifecycleOperationOwnerRole.UPLOAD, LifecycleOperationOwnerRole.EXTERNAL_READ)) {
                    return rejectedOwnerMutation(LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED, from)
                }
                from.copy(
                    phase = LifecycleOperationOwnerPhase.COMPLETED,
                    generation = nextOwnerGeneration(from.generation),
                    authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
                )
            }
            OwnerMutationKind.CANCEL -> from.copy(
                role = null,
                phase = LifecycleOperationOwnerPhase.AVAILABLE,
                generation = nextOwnerGeneration(from.generation),
                authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
            )
        }
        val newCapability = if (kind == OwnerMutationKind.TRANSFER) newOwnerCapability(to) else null
        val revision = record.storeRevision + 1L
        val receipt = ownerTransitionReceipt(
            kind = kind.name,
            commandHash = commandHash,
            from = from,
            to = to,
            revision = revision,
            fromCapabilityIdSha256 = entry.capabilityIdSha256,
            toCapabilityIdSha256 = newCapability?.capabilityIdSha256,
            expectedRecoveryGeneration = null,
            cancelReason = cancelReason,
        )
        val next = record.copy(
            descriptor = to,
            processInstanceIdSha256 = processInstanceIdProvider.sha256,
            currentCapabilityIdSha256 = newCapability?.capabilityIdSha256,
            storeRevision = revision,
            transitions = record.transitions + receipt,
            failureCode = null,
        )
        if (!ownerStateStore.compareAndSet(operation.operationId, record.storeRevision, next)) {
            return rejectedOwnerMutation(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE, from)
        }
        if (newCapability == null) {
            retireCapability(capability)
            return LifecycleOperationOwnerMutationResult.Released(receipt)
        }
        return try {
            beforeOwnerCapabilityPublish?.invoke()
            retireCapability(capability)
            activateCapability(newCapability)
            LifecycleOperationOwnerMutationResult.Transferred(newCapability, receipt)
        } catch (failure: Exception) {
            retireCapability(capability)
            val recovered = recoverPublicationFailureLocked(next, operation)
            rejectedOwnerMutation(
                LifecycleOperationOwnerRejection.CAPABILITY_PUBLICATION_FAILED,
                recovered.descriptor,
            )
        }
    }

    private fun replayOwnerMutation(
        kind: OwnerMutationKind,
        receipt: LifecycleOperationOwnerTransitionReceipt,
        record: StoredLifecycleOperationOwnerState,
    ): LifecycleOperationOwnerMutationResult = if (kind == OwnerMutationKind.TRANSFER) {
        findActiveCapability(receipt.toCapabilityIdSha256)?.let {
            LifecycleOperationOwnerMutationResult.Transferred(it, receipt)
        } ?: rejectedOwnerMutation(
            if (record.descriptor.phase == LifecycleOperationOwnerPhase.RECOVERY_REQUIRED) {
                LifecycleOperationOwnerRejection.RECOVERY_REQUIRED
            } else {
                LifecycleOperationOwnerRejection.STALE_GENERATION
            },
            record.descriptor,
        )
    } else {
        LifecycleOperationOwnerMutationResult.Released(receipt)
    }

    private fun reconcileOwnerLocked(
        record: StoredLifecycleOperationOwnerState?,
        operation: LifecycleOperation,
    ): StoredLifecycleOperationOwnerState? {
        if (record?.descriptor?.phase != LifecycleOperationOwnerPhase.OWNED) return record
        val liveCapability = record.currentCapabilityIdSha256?.let(::findActiveCapability)
        if (record.processInstanceIdSha256 == processInstanceIdProvider.sha256 && liveCapability != null) return record
        return recoverOwnerLocked(
            record = record,
            operation = operation,
            failureCode = if (record.processInstanceIdSha256 == processInstanceIdProvider.sha256) {
                "CAPABILITY_PUBLICATION_FAILED"
            } else {
                "PROCESS_REPLACED"
            },
        )
    }

    private fun recoverPublicationFailureLocked(
        record: StoredLifecycleOperationOwnerState,
        operation: LifecycleOperation,
    ): StoredLifecycleOperationOwnerState {
        retireCapabilitiesForOperation(operation.operationId)
        return runCatching { recoverOwnerLocked(record, operation, "CAPABILITY_PUBLICATION_FAILED") }
            .getOrDefault(record)
    }

    private fun recoverOwnerLocked(
        record: StoredLifecycleOperationOwnerState,
        operation: LifecycleOperation,
        failureCode: String,
    ): StoredLifecycleOperationOwnerState {
        val from = record.descriptor
        val to = from.copy(
            phase = LifecycleOperationOwnerPhase.RECOVERY_REQUIRED,
            generation = nextOwnerGeneration(from.generation),
            authoritativeOperationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
        )
        val revision = record.storeRevision + 1L
        val commandHash = sha256Hex("SYSTEM_OWNER_RECOVERY:${record.storeRevision}:$failureCode")
        val receipt = ownerTransitionReceipt(
            kind = "SYSTEM_RECOVERY",
            commandHash = commandHash,
            from = from,
            to = to,
            revision = revision,
            fromCapabilityIdSha256 = record.currentCapabilityIdSha256,
            toCapabilityIdSha256 = null,
            expectedRecoveryGeneration = null,
            cancelReason = null,
        )
        val next = record.copy(
            descriptor = to,
            processInstanceIdSha256 = processInstanceIdProvider.sha256,
            currentCapabilityIdSha256 = null,
            storeRevision = revision,
            transitions = record.transitions + receipt,
            failureCode = failureCode,
        )
        check(ownerStateStore.compareAndSet(operation.operationId, record.storeRevision, next))
        retireCapabilitiesForOperation(operation.operationId)
        return next
    }

    private suspend fun retireRecoveredExternalOwner(operationId: String) = mutation.withLock {
        val record = requireNotNull(ownerStateStore.read(operationId))
        if (record.descriptor.phase == LifecycleOperationOwnerPhase.COMPLETED) return@withLock
        check(
            record.descriptor.role == LifecycleOperationOwnerRole.EXTERNAL_READ &&
            record.processInstanceIdSha256 != processInstanceIdProvider.sha256
        )
        val closure = requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode)
        check(closure.producerProcessSha256 != processInstanceIdProvider.sha256 && closure.seal != null)
        check(
            closure.reservation.resources.filter { it.type == io.element.android.security.dataprotection.api.LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME }
            .all { it.recordSha256 in closure.runtimeFacts }
        )
        val from = record.descriptor
        val to = from.copy(phase = LifecycleOperationOwnerPhase.COMPLETED, generation = nextOwnerGeneration(from.generation))
        val transition = ownerTransitionReceipt(
            "RECOVERY_ABORT",
            sha256Hex("external-recovery-$operationId"),
            from,
            to,
            record.storeRevision + 1,
            record.currentCapabilityIdSha256,
            null,
            from.generation,
            LifecycleOperationOwnerCancelReason.PROCESS_RECOVERY_ABORTED
        )
        check(ownerStateStore.compareAndSet(operationId, record.storeRevision, record.copy(
            descriptor = to,
            processInstanceIdSha256 = processInstanceIdProvider.sha256,
            currentCapabilityIdSha256 = null,
            storeRevision = record.storeRevision + 1,
            transitions = record.transitions + transition
        )))
        retireCapabilitiesForOperation(operationId)
    }

    private fun ownerOperationRejection(operation: LifecycleOperation): LifecycleOperationOwnerRejection? = when {
        operation.lifecycleId != FullAppLifecycleId.LC_16 -> LifecycleOperationOwnerRejection.UNSUPPORTED_LIFECYCLE
        operation.status.isTerminal -> LifecycleOperationOwnerRejection.OPERATION_TERMINAL
        operation.status !in NON_TERMINAL_RESUMABLE_STATUSES -> LifecycleOperationOwnerRejection.OPERATION_NOT_OWNABLE
        operation.subjects.accountIdHash == null ||
            operation.subjects.matrixDeviceIdHash == null ||
            operation.subjects.roomIdHash == null -> LifecycleOperationOwnerRejection.OPERATION_SUBJECTS_INCOMPLETE
        else -> null
    }

    private suspend fun loadOwnerOperation(operationId: String): LifecycleOperation? = try {
        operation(operationId)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun ownerTransitionReceipt(
        kind: String,
        commandHash: String,
        from: LifecycleOperationOwnerDescriptor,
        to: LifecycleOperationOwnerDescriptor,
        revision: Long,
        fromCapabilityIdSha256: String?,
        toCapabilityIdSha256: String?,
        expectedRecoveryGeneration: Long?,
        cancelReason: LifecycleOperationOwnerCancelReason?,
    ): LifecycleOperationOwnerTransitionReceipt = LifecycleOperationOwnerTransitionReceipt(
        operationId = from.operationId,
        commandIdSha256 = commandHash,
        requestFingerprintSha256 = ownerRequestFingerprint(
            kind = kind,
            operationId = from.operationId,
            commandIdSha256 = commandHash,
            from = from,
            toRole = if (kind == "ACQUIRE" || kind == OwnerMutationKind.TRANSFER.name) to.role else null,
            expectedRecoveryGeneration = expectedRecoveryGeneration,
            cancelReason = cancelReason,
            fromCapabilityIdSha256 = fromCapabilityIdSha256,
        ),
        processInstanceIdSha256 = processInstanceIdProvider.sha256,
        fromCapabilityIdSha256 = fromCapabilityIdSha256,
        toCapabilityIdSha256 = toCapabilityIdSha256,
        from = from,
        to = to,
        storeRevision = revision,
    )

    private fun ownerRequestFingerprint(
        kind: String,
        operationId: String,
        commandIdSha256: String,
        from: LifecycleOperationOwnerDescriptor,
        toRole: LifecycleOperationOwnerRole?,
        expectedRecoveryGeneration: Long?,
        cancelReason: LifecycleOperationOwnerCancelReason?,
        fromCapabilityIdSha256: String?,
    ): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            listOf(
                "owner-command-v1",
                kind,
                operationId,
                FullAppLifecycleId.LC_16.name,
                commandIdSha256,
                from.phase.name,
                from.role?.name,
                from.generation.toString(),
                from.authoritativeOperationUpdatedAtEpochMillis.toString(),
                toRole?.name,
                expectedRecoveryGeneration?.toString(),
                cancelReason?.name,
                fromCapabilityIdSha256,
            ).forEach { value ->
                if (value == null) {
                    data.writeInt(-1)
                } else {
                    val bytes = value.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
        }
        return sha256Hex(output.toByteArray())
    }

    private fun newOwnerCapability(descriptor: LifecycleOperationOwnerDescriptor): DefaultLifecycleOperationOwnerCapability =
        DefaultLifecycleOperationOwnerCapability(
            descriptor = descriptor,
            capabilityIdSha256 = sha256Hex(ownerNonceSource.next()),
            processInstanceIdSha256 = processInstanceIdProvider.sha256,
        )

    private fun activateCapability(capability: DefaultLifecycleOperationOwnerCapability) {
        activeOwnerCapabilities[capability] = OwnerCapabilityEntry(
            descriptor = capability.descriptor,
            capabilityIdSha256 = capability.capabilityIdSha256,
        )
    }

    private fun retireCapability(capability: DefaultLifecycleOperationOwnerCapability) {
        activeOwnerCapabilities.remove(capability)?.let { retiredOwnerCapabilities[capability] = it }
    }

    private fun retireCapabilitiesForOperation(operationId: String) {
        activeOwnerCapabilities.entries
            .filter { it.value.descriptor.operationId == operationId }
            .map { it.key }
            .forEach(::retireCapability)
    }

    private fun findActiveCapability(capabilityIdSha256: String?): DefaultLifecycleOperationOwnerCapability? =
        capabilityIdSha256?.let { expected ->
            activeOwnerCapabilities.entries.firstOrNull { it.value.capabilityIdSha256 == expected }?.key
        }

    private fun hasRetiredCapability(capabilityIdSha256: String): Boolean =
        retiredOwnerCapabilities.values.any { it.capabilityIdSha256 == capabilityIdSha256 }

    private fun rejectedOwnerMutation(
        reason: LifecycleOperationOwnerRejection,
        current: LifecycleOperationOwnerDescriptor?,
    ): LifecycleOperationOwnerMutationResult.Rejected = LifecycleOperationOwnerMutationResult.Rejected(reason, current)

    private fun nextOwnerGeneration(current: Long): Long {
        if (current == Long.MAX_VALUE) throw OwnerGenerationExhaustedException()
        return current + 1L
    }

    private sealed interface ResumingResolution {
        data class Existing(val operationId: String) : ResumingResolution
        data class WaitFor(val deferred: CompletableDeferred<LifecycleOperation>) : ResumingResolution
        data class StartNew(val deferred: CompletableDeferred<LifecycleOperation>) : ResumingResolution
    }

    private enum class OwnerMutationKind {
        TRANSFER,
        COMPLETE,
        CANCEL,
    }

    private data class OwnerCapabilityEntry(
        val descriptor: LifecycleOperationOwnerDescriptor,
        val capabilityIdSha256: String,
    )

    private class DefaultLifecycleOperationOwnerCapability(
        override val descriptor: LifecycleOperationOwnerDescriptor,
        val capabilityIdSha256: String,
        val processInstanceIdSha256: String,
    ) : LifecycleOperationOwnerCapability

    private class OwnerGenerationExhaustedException : IllegalStateException()

    private fun loadDurableReceiptsLocked() {
        if (durableReceiptsLoaded) return
        operationIdStore.pendingDraftStarts().forEach { entry ->
            check(pendingDraftStarts.putIfAbsent(entry.request.operationId, entry) == null) { "Duplicate persisted draft intent." }
        }
        operationIdStore.pendingClientReceipts().forEach { receipt ->
            pendingClientReceipts.putIfAbsent(receipt.operationId to receipt.component, receipt)
        }
        durableReceiptsLoaded = true
    }

    private suspend fun acknowledgeClientReceipt(
        key: Pair<String, LifecycleComponent>,
        receipt: LifecycleClientReceipt,
        operation: LifecycleOperation,
    ) = withContext(receiptDispatcher) {
        mutation.withLock {
            requireTerminalClientFactAcknowledged(receipt, operation)
            if (pendingClientReceipts[key] == receipt) {
                val next = pendingClientReceipts.toMutableMap().also { it.remove(key) }
                val nextDrafts = pendingDraftStarts.toMutableMap()
                if (next.keys.none { it.first == receipt.operationId }) nextDrafts.remove(receipt.operationId)
                if (durableReceiptsLoaded) {
                    if (pendingDraftStarts.containsKey(receipt.operationId)) {
                        operationIdStore.savePendingDraftState(nextDrafts.values.toList(), next.values.toList())
                    } else {
                        operationIdStore.savePendingClientReceipts(next.values.toList())
                    }
                }
                pendingDraftStarts.clear()
                pendingDraftStarts.putAll(nextDrafts)
                pendingClientReceipts.remove(key)
                receiptRetryAt.remove(key)
                receiptFailures.remove(key)
            }
            remember(operation)
        }
    }

    private suspend fun flushPendingClientReceipts() = receiptFlush.withLock {
        val pending = withContext(receiptDispatcher) {
            mutation.withLock {
                loadDurableReceiptsLocked()
                val now = System.nanoTime()
                pendingClientReceipts.filterKeys { now >= (receiptRetryAt[it] ?: Long.MIN_VALUE) }
            }
        }
        val blockedDrafts = prepareDraftReceiptReplay(pending.values.toList())
        coroutineScope {
            pending.map { (key, receipt) ->
                async {
                receiptTransport.withPermit {
                    if (receipt.operationId in blockedDrafts) return@withPermit
                    try {
                        val (known, snapshot, draft) = mutation.withLock {
                            Triple(mutableOperations.value[receipt.operationId], lastVerifiedSnapshot, pendingDraftStarts[receipt.operationId])
                        }
                        // After process death, fetch the real operation before replay. Old-account
                        // receipt intents cannot be transported with another account's identity.
                        val verified = known ?: if (snapshot != null) {
                            managementControlService.lifecycleOperation(receipt.operationId)
                        } else {
                            return@withPermit
                        }
                        if (draft != null && (snapshot == null || draft.installationId != snapshot.installationId ||
                            verified.subjects.installationId != snapshot.installationId ||
                            verified.subjects.accountIdHash != snapshot.accountIdHash || verified.subjects.matrixDeviceIdHash != snapshot.matrixDeviceIdHash)) {
                            mutation.withLock { receiptRetryAt[key] = Long.MAX_VALUE }
                            return@withPermit
                        }
                        // A verified terminal outcome can retire an exact acknowledged fact or
                        // a non-participating observer. The acknowledgement gate below retains
                        // any registered step whose original fact is still absent or conflicting.
                        // Do not transport an old installation's facts under the current account.
                        if (verified.status.isTerminal && verified.terminalReceiptId != null &&
                            (snapshot == null && known != null ||
                                snapshot != null && verified.subjects.installationId == snapshot.installationId)) {
                            check(verified.operationId == receipt.operationId)
                            acknowledgeClientReceipt(key, receipt, verified)
                            return@withPermit
                        }
                        // A rejected public login has no account/device subject. It still belongs
                        // to this installation and must close even if a previous account snapshot
                        // remains cached, or another login has since succeeded. Never use this
                        // exception for APPLIED success or an account-bound receipt.
                        val installationLoginRejection = snapshot != null &&
                            verified.lifecycleId == FullAppLifecycleId.LC_05 &&
                            verified.subjects.installationId == snapshot.installationId &&
                            verified.subjects.accountIdHash == null && verified.subjects.matrixDeviceIdHash == null &&
                            verified.subjects.roomIdHash == null && verified.subjects.eventIdHash == null &&
                            receipt.component == LifecycleComponent.ANDROID_SERVICE &&
                            receipt.phase == LifecyclePhase.CLIENT_APPLIED &&
                            receipt.resultCode == "MATRIX_AUTHENTICATION_REJECTED" &&
                            receipt.receiptReference == "android-login-rejected:${receipt.operationId}"
                        val installationRestore = snapshot != null &&
                            verified.lifecycleId == FullAppLifecycleId.LC_02 &&
                            verified.subjects.installationId == snapshot.installationId &&
                            verified.subjects.accountIdHash == null && verified.subjects.matrixDeviceIdHash == null &&
                            verified.subjects.roomIdHash == null && verified.subjects.eventIdHash == null
                        if (snapshot != null && !installationLoginRejection && !installationRestore && (
                                verified.subjects.accountIdHash != snapshot.accountIdHash ||
                                    verified.subjects.matrixDeviceIdHash != snapshot.matrixDeviceIdHash
                                )) {
                            mutation.withLock { receiptRetryAt[key] = Long.MAX_VALUE }
                            return@withPermit
                        }
                        val operation = if (verified.status.isTerminal) {
                            verified
                        } else {
                            submitClientReceipt(receipt)
                        }
                        check(operation.operationId == receipt.operationId)
                        acknowledgeClientReceipt(key, receipt, operation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Exact metadata stays durably pending, with the original identity/time.
                        mutation.withLock {
                            val failures = ((receiptFailures[key] ?: 0) + 1).coerceAtMost(6)
                            receiptFailures[key] = failures
                            receiptRetryAt[key] = System.nanoTime() + (1_000L shl failures).coerceAtMost(60_000L) * 1_000_000L
                        }
                    }
                }
            }
            }.awaitAll()
        }
    }

    private suspend fun prepareDraftReceiptReplay(receipts: List<LifecycleClientReceipt>): Set<String> {
        val (drafts, snapshot) = mutation.withLock { pendingDraftStarts.toMap() to lastVerifiedSnapshot }
        val relevant = drafts.filterKeys { id -> receipts.any { it.operationId == id } }
        val blocked = relevant.keys.toMutableSet()
        if (snapshot == null || relevant.isEmpty()) return blocked
        val installation = try {
            draftInstallationProvider?.invoke() ?: snapshot.installationId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return blocked
        }
        for ((id, entry) in relevant) {
            val request = entry.request
            if (entry.installationId != installation || entry.installationId != snapshot.installationId ||
                request.accountIdHash != snapshot.accountIdHash || request.matrixDeviceIdHash != snapshot.matrixDeviceIdHash) {
                    continue
                }
            try {
                val current = pendingStartRetryClock()
                val oldestFact = receipts.filter { it.operationId == id }.minOf { it.occurredAtEpochMillis }
                // Outside the original admission/clock window only inspect the original; never extend or recreate it.
                val operation = if (request.deadlineEpochMillis > current && oldestFact >= current - 5 * 60_000L) {
                    managementControlService.startLifecycleOperation(request)
                } else {
                    managementControlService.lifecycleOperation(id)
                }
                check(
                    operation.operationId == id && operation.lifecycleId == FullAppLifecycleId.LC_14 &&
                    // The operation snapshot carries the digest; the persisted request retains the original key.
                    operation.idempotencyKey == sha256Hex(request.idempotencyKey) && operation.deadlineEpochMillis == request.deadlineEpochMillis &&
                    operation.subjects.installationId == entry.installationId && operation.subjects.accountIdHash == request.accountIdHash &&
                    operation.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash && operation.subjects.roomIdHash == request.roomIdHash &&
                    operation.subjects.eventIdHash == null
                ) { "Draft authority returned another intent." }
                val installationAfter = draftInstallationProvider?.invoke() ?: mutation.withLock { lastVerifiedSnapshot?.installationId }
                val stillCurrent = mutation.withLock {
                    val latest = lastVerifiedSnapshot
                    if (installationAfter != entry.installationId || latest?.installationId != entry.installationId ||
                        latest.accountIdHash != request.accountIdHash || latest.matrixDeviceIdHash != request.matrixDeviceIdHash) {
                            false
                        } else {
                        remember(operation)
                        true
                        }
                }
                if (!stillCurrent) continue
                blocked.remove(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Original intent and actual occurrence times stay committed; lookup failure does not prove absence.
                mutation.withLock {
                    receipts.filter { it.operationId == id }.forEach { receipt ->
                        val key = id to receipt.component
                        val failures = ((receiptFailures[key] ?: 0) + 1).coerceAtMost(6)
                        receiptFailures[key] = failures
                        receiptRetryAt[key] = System.nanoTime() + (1_000L shl failures).coerceAtMost(60_000L) * 1_000_000L
                    }
                }
            }
        }
        return blocked
    }

    private fun rememberProvisional(operation: LifecycleOperation) {
        if (verifiedOperationVersions.containsKey(operation.operationId)) return
        operationIdStore.remember(operation)
        operationProjections.put(operation)
        updateUiStates((mutableState.value as? AppOperationalCoordinatorState.Available)?.snapshot)
    }

    private fun remember(operation: LifecycleOperation) {
        val previous = verifiedOperationVersions[operation.operationId]
        if (previous != null && (
                previous.updatedAtEpochMillis > operation.updatedAtEpochMillis ||
                    previous.status.isTerminal && !operation.status.isTerminal ||
                    previous.updatedAtEpochMillis == operation.updatedAtEpochMillis &&
                    (previous.currentPhase.ordinal > operation.currentPhase.ordinal ||
                        previous.steps.any { old ->
                            old.status in setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED) &&
                                operation.steps.none { new -> new.component == old.component && new.status == old.status }
                        })
                )) {
            // Rediscovery after projection pruning must restore the newer verified original.
            // A stale reply cannot replace it or authorize a replacement operation.
            operationProjections.put(previous)
            updateUiStates((mutableState.value as? AppOperationalCoordinatorState.Available)?.snapshot)
            return
        }
        // Replies are signed independently and can arrive in reverse order. Keep the
        // newest authoritative projection; never invent a merged operation or receipt.
        verifiedOperationVersions[operation.operationId] = operation
        operationIdStore.remember(operation)
        operationProjections.put(operation)
        updateUiStates((mutableState.value as? AppOperationalCoordinatorState.Available)?.snapshot)
    }

    private fun updateUiStates(snapshot: AppOperationalSnapshot?) {
        val latest = operationProjections.latestByLifecycle()
        mutableUiStates.value = FullAppLifecycleId.entries.associateWith { lifecycleId ->
            AppLifecycleUiProjector.project(lifecycleId, snapshot, latest[lifecycleId])
        }
    }

    private fun LifecycleOperationRequest.toProvisionalOperation(): LifecycleOperation {
        val current = System.currentTimeMillis()
        return LifecycleOperation(
            operationId = operationId,
            lifecycleId = lifecycleId,
            idempotencyKey = idempotencyKey,
            subjects = LifecycleSubjectHashes(
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                roomIdHash = roomIdHash,
                eventIdHash = eventIdHash,
            ),
            status = LifecycleOperationStatus.PENDING,
            currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = deadlineEpochMillis,
            createdAtEpochMillis = current,
            updatedAtEpochMillis = current,
        )
    }
}
