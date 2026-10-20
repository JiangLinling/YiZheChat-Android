/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class LifecycleOperationRequest(
    val operationId: String = UUID.randomUUID().toString(),
    val lifecycleId: FullAppLifecycleId,
    val trigger: String,
    val idempotencyKey: String,
    val accountIdHash: String? = null,
    val matrixDeviceIdHash: String? = null,
    val roomIdHash: String? = null,
    val eventIdHash: String? = null,
    val deadlineEpochMillis: Long,
    val roomCreationProtocol: String? = null,
    val mediaCleanupProtocol: String? = null,
) {
    init {
        require(operationId.length in 16..256) { "Lifecycle operation identity length is invalid." }
        require(AppLifecycleEntrypoints.isRegistered(lifecycleId, trigger)) {
            "Lifecycle trigger is not registered for the supplied lifecycle."
        }
        require(idempotencyKey.length in 16..256) { "Lifecycle idempotency key length is invalid." }
        require(deadlineEpochMillis > 0) { "Lifecycle deadline is invalid." }
        require(roomCreationProtocol == null || (roomCreationProtocol == "managed-v1" && lifecycleId == FullAppLifecycleId.LC_12 && trigger == "room_create")) {
            "Managed room creation protocol requires LC-12 room_create."
        }
        require(mediaCleanupProtocol == null || (mediaCleanupProtocol == MEDIA_RESOURCE_CLOSURE_PROTOCOL &&
            ((lifecycleId == FullAppLifecycleId.LC_16 && trigger != "media_select") ||
                (lifecycleId == FullAppLifecycleId.LC_17 && trigger == "live_location_stop")) &&
            accountIdHash != null && matrixDeviceIdHash != null)) {
            "Media cleanup protocol requires an account-bound resource-owning LC16 action."
        }
        require((accountIdHash == null) == (matrixDeviceIdHash == null)) {
            "Account and Matrix device hashes must be supplied together."
        }
        LifecycleSubjectHashes(
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
            roomIdHash = roomIdHash,
            eventIdHash = eventIdHash,
        )
    }
}

data class LifecycleClientReceipt(
    val operationId: String,
    val component: LifecycleComponent,
    val phase: LifecyclePhase,
    val resultCode: String,
    val receiptReference: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(component in setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)) {
            "Only Android components can submit client lifecycle receipts."
        }
        require(phase in setOf(LifecyclePhase.CLIENT_APPLIED, LifecyclePhase.RECEIPTED)) {
            "Client lifecycle receipt phase is invalid."
        }
    }
}

sealed interface AppOperationalCoordinatorState {
    data object Discovering : AppOperationalCoordinatorState
    data class Available(val snapshot: AppOperationalSnapshot) : AppOperationalCoordinatorState
    data class RetryableFailure(
        val reasonCode: String,
        val lastVerifiedSnapshot: AppOperationalSnapshot?,
    ) : AppOperationalCoordinatorState
}

interface AppOperationalStateCoordinator {
    val state: StateFlow<AppOperationalCoordinatorState>
    val operations: StateFlow<Map<String, LifecycleOperation>>
    val uiStates: StateFlow<Map<FullAppLifecycleId, AppLifecycleUiState>>

    /** Observe only this original operation. Absence is unknown, never a successful receipt. */
    fun observeOperation(operationId: String): Flow<LifecycleOperation?> =
        operations.map { it[operationId] }.distinctUntilChanged()

    fun observeLifecycleOperations(lifecycleId: FullAppLifecycleId): Flow<Map<String, LifecycleOperation>> =
        operations.map { entries -> entries.filterValues { it.lifecycleId == lifecycleId } }.distinctUntilChanged()

    suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot

    /** Installation-owned startup reconciliation; it cannot issue a protected entry grant. */
    suspend fun reconcileFirstActivation(accountIdHash: String, matrixDeviceIdHash: String) {
        error("First activation reconciliation is unavailable.")
    }

    suspend fun start(request: LifecycleOperationRequest): LifecycleOperation

    /** LC14 local storage owns the action. Production persists its exact intent before applying it, then queues actual facts. */
    suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit): Unit = perform(request, block)

    /** Validate the retained local intent and current installation before a crash recovery write. */
    suspend fun validateLocalDraftRecovery(operationId: String, accountIdHash: String, matrixDeviceIdHash: String, roomIdHash: String): Unit =
        error("Local draft recovery validation is unavailable.")

    /** Import an already-committed version; this method must never write draft content. */
    suspend fun recoverLocalDraftApplied(fact: LifecycleDraftApplied): Boolean = false

    suspend fun operation(operationId: String): LifecycleOperation

    /** Only authorizes retransmission of the exact retained ciphertext; never grants a content key or receipt. */
    suspend fun authorizePreparedRelayRetry(query: ProtectedEventOriginQuery, operationId: String, roomId: String): Unit =
        error("Prepared relay retry authorization is unavailable.")

    suspend fun protectedEventOrigin(query: ProtectedEventOriginQuery): LifecycleOperationContext? = null

    suspend fun reconcileRelayPreparation(query: ProtectedRelayPreparationQuery): LifecycleOperation =
        error("Original relay preparation reconciliation is unavailable.")

    suspend fun resume(operationId: String): LifecycleOperation

    suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation

    /** Separate from component completion: the durable object journal retains responsibility until this succeeds. */
    suspend fun recordMediaObjectCleanup(fact: LifecycleMediaObjectCleanup): String =
        error("Media object cleanup acknowledgement is unavailable.")

    /** Persist original intent before admission. Callers may create resources only after this returns successfully. */
    suspend fun reserveMediaResources(request: LifecycleMediaReservation): Unit = error("Media resource ownership is unavailable.")

    /** Persist the immutable producer manifest and await its signed acknowledgement plus any retained release facts.
     * Failure leaves the same durable facts pending. This acknowledgement is not a terminal lifecycle receipt.
     */
    suspend fun sealMediaResources(operationId: String, outcome: LifecycleMediaOutcome): Unit = error("Media resource sealing is unavailable.")

    /** Persist actual local release before returning; transport failure leaves the original fact pending. */
    suspend fun retainMediaRuntimeCleanup(fact: LifecycleMediaRuntimeCleanup): Unit = error("Media runtime cleanup retention is unavailable.")

    /** Commit a complete batch before synchronous transport. Legacy implementations submit synchronously. */
    suspend fun persistClientReceipts(receipts: List<LifecycleClientReceipt>) = Unit

    /**
     * Persist already-applied client facts before returning, then transport them independently of
     * message display. Enqueuing is not a server acknowledgement or lifecycle completion.
     * Implementations without a durable queue must retain synchronous submission semantics.
     */
    suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) {
        receipts.forEach { recordClientReceipt(it) }
    }

    /**
     * Continue the newest non-terminal operation for this lifecycle. Implementations must
     * coalesce concurrent callers at the durable-operation boundary. A new operation may be
     * created only when the caller declares a real new startup intent and no resumable operation
     * exists.
     */
    suspend fun <T> performResuming(
        request: LifecycleOperationRequest,
        hasNewStartupIntent: Boolean = true,
        block: suspend (operationId: String) -> T,
    ): T
}

/** Carries the one server-created operation identity through existing service calls without another coordinator. */
class LifecycleOperationContext(
    val operationId: String,
    val lifecycleId: FullAppLifecycleId,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LifecycleOperationContext>
}

suspend fun currentLifecycleOperationContext(): LifecycleOperationContext? =
    currentCoroutineContext()[LifecycleOperationContext]

suspend fun <T> AppOperationalStateCoordinator.withOperation(
    request: LifecycleOperationRequest,
    block: suspend (operationId: String) -> T,
): T {
    val parent = currentLifecycleOperationContext()
    if (parent?.lifecycleId == request.lifecycleId) {
        if (request.lifecycleId == FullAppLifecycleId.LC_16) {
            val original = operation(parent.operationId)
            require(original.lifecycleId == request.lifecycleId && original.operationId == parent.operationId)
            require(!original.status.isTerminal && original.deadlineEpochMillis > System.currentTimeMillis()) {
                "Original media operation is no longer active."
            }
            require(original.subjects.accountIdHash == request.accountIdHash &&
                original.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
                original.subjects.roomIdHash == request.roomIdHash && original.subjects.eventIdHash == request.eventIdHash) {
                "Nested media action does not belong to the original operation."
            }
        }
        return block(parent.operationId)
    }
    val operation = start(request)
    return withContext(LifecycleOperationContext(operation.operationId, operation.lifecycleId)) {
        block(operation.operationId)
    }
}

suspend fun <T> AppOperationalStateCoordinator.perform(
    request: LifecycleOperationRequest,
    block: suspend (operationId: String) -> T,
): T {
    return withOperation(request) { operationId ->
        performOnExistingOperation(
            operationId = operationId,
            components = listOf(
                LifecycleComponent.ANDROID_SERVICE,
                LifecycleComponent.ANDROID_FRONTEND,
            ),
            // Only the signed registration can prove both clients participate. Narrow
            // observer-only triggers retain serial submission and terminal short-circuiting.
            parallelReceipts = request.lifecycleId in setOf(FullAppLifecycleId.LC_01, FullAppLifecycleId.LC_02, FullAppLifecycleId.LC_06) &&
                (operations.value[operationId]?.let { admitted ->
                    !admitted.status.isTerminal && admitted.steps.filter { it.required }.map { it.component }.containsAll(
                        listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
                    )
                } == true),
            block = block,
        )
    }
}

/** Retry one LC-15 relay edit using fresh admission and the existing encrypted producer intent. */
suspend fun AppOperationalStateCoordinator.performIdempotentRelayEdit(
    request: LifecycleOperationRequest,
    verifyAcceptedIntent: suspend () -> Unit,
    reconcileExpired: suspend () -> Unit = {},
    block: suspend (operationId: String) -> Unit,
) {
    require(request.lifecycleId == FullAppLifecycleId.LC_15 && request.trigger == "relay_operation")
    require(listOf(request.accountIdHash, request.matrixDeviceIdHash, request.roomIdHash, request.eventIdHash).all { it != null })
    val parent = currentLifecycleOperationContext()
    require(parent == null || parent.operationId == request.operationId && parent.lifecycleId == request.lifecycleId)
    // Use fresh signed admission, not a cached terminal UI projection.
    val operation = start(request)
    val required = requireOriginalRelayEditOperation(request, operation)
    withContext(LifecycleOperationContext(operation.operationId, operation.lifecycleId)) {
        when {
            operation.status == LifecycleOperationStatus.SUCCEEDED -> {
                requireRelayEditSuccess(operation, required)
                // An immutable producer intent must match the requested payload; a
                // terminal operation cannot accept a different edit with a reused UUID.
                verifyAcceptedIntent()
            }
            operation.status.isTerminal -> throw RelayEditException(RelayEditException.Reason.TERMINAL_FAILURE)
            else -> {
                if (operation.deadlineEpochMillis <= System.currentTimeMillis()) {
                    // Orphan erasure may happen after the original dispatch deadline.
                    // Observe its actual terminal fact without extending the deadline or dispatching again.
                    reconcileExpired()
                    throw RelayEditException(RelayEditException.Reason.EXPIRED)
                }
                performOnExistingOperation(operation.operationId,
                    listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)) { operationId ->
                    if (required.filter { it.component !in setOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND) }
                        .all { it.status == LifecycleStepStatus.COMPLETE && !it.authorityRevision.isNullOrBlank() && !it.receiptReference.isNullOrBlank() }
                    ) {
                        // The original carrier is accepted but process loss may have
                        // prevented local acknowledgements. Observe its encrypted
                        // canonical intent now, then persist genuine current Android
                        // application facts under that original ID, without resending.
                        verifyAcceptedIntent()
                    } else block(operationId)
                }
            }
        }
    }
}


/** Observe only the original authority operation; never admit, prepare, dispatch or fabricate receipts. */
suspend fun AppOperationalStateCoordinator.reconcileOriginalRelayEdit(
    request: LifecycleOperationRequest,
    verifyAcceptedIntent: suspend () -> Unit,
    resolveOriginal: suspend () -> Unit,
): Boolean {
    require(request.lifecycleId == FullAppLifecycleId.LC_15 && request.trigger == "relay_operation")
    require(listOf(request.accountIdHash, request.matrixDeviceIdHash, request.roomIdHash, request.eventIdHash).all { it != null })
    val operation = operation(request.operationId)
    val required = requireOriginalRelayEditOperation(request, operation)
    return withContext(LifecycleOperationContext(operation.operationId, operation.lifecycleId)) {
        when {
            operation.status == LifecycleOperationStatus.SUCCEEDED -> {
                requireRelayEditSuccess(operation, required)
                verifyAcceptedIntent()
                resolveOriginal()
                true
            }
            operation.status.isTerminal -> throw RelayEditException(RelayEditException.Reason.TERMINAL_FAILURE)
            operation.deadlineEpochMillis <= System.currentTimeMillis() -> throw RelayEditException(RelayEditException.Reason.EXPIRED)
            else -> false // UNKNOWN is retained even if a matching roster entry is visible.
        }
    }
}

private fun requireOriginalRelayEditOperation(request: LifecycleOperationRequest, operation: LifecycleOperation): List<LifecycleStep> {
    require(operation.operationId == request.operationId && operation.lifecycleId == request.lifecycleId)
    require(operation.subjects.accountIdHash == request.accountIdHash &&
        operation.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
        operation.subjects.roomIdHash == request.roomIdHash && operation.subjects.eventIdHash == request.eventIdHash)
    val idempotencyHash = java.security.MessageDigest.getInstance("SHA-256").digest(request.idempotencyKey.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(operation.idempotencyKey == request.idempotencyKey || operation.idempotencyKey == idempotencyHash)
    val required = operation.steps.filter { it.required }
    require(required.size == 6 && required.map { it.component }.toSet() == setOf(
        LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE,
        LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE, LifecycleComponent.KMS,
    ))
    return required
}

private fun requireRelayEditSuccess(operation: LifecycleOperation, required: List<LifecycleStep>) {
    require(!operation.terminalReceiptId.isNullOrBlank() && required.all {
        // The installation-authenticated client-receipts v1 protocol carries no
        // authority revision for Android facts. External authorities do provide
        // revisions. Both still require their original, complete receipt.
        val clientFact = it.component in setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
        it.status == LifecycleStepStatus.COMPLETE && !it.receiptReference.isNullOrBlank() &&
            (clientFact || !it.authorityRevision.isNullOrBlank())
    })
}

/** LC-25 product facts are durable before receipt transport; terminal success still requires all receipts. */
suspend fun <T> AppOperationalStateCoordinator.performPrivateWithDeferredReceipts(
    request: LifecycleOperationRequest,
    block: suspend (operationId: String) -> T,
): T {
    require(request.lifecycleId == FullAppLifecycleId.LC_25)
    return withOperation(request) { operationId ->
        val result = block(operationId)
        withContext(NonCancellable) {
            enqueueClientReceipts(listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
                LifecycleClientReceipt(operationId, component, LifecyclePhase.CLIENT_APPLIED, "${component.name}_APPLIED",
                    "${component.name.lowercase().replace('_', '-')}:$operationId", System.currentTimeMillis())
            })
        }
        result
    }
}

/** Delegation is published only after the encrypted TEXT version intent has been persisted. */
class TextVersionReceiptOwner(
    val operationId: String,
    val accountIdHash: String?,
    val matrixDeviceIdHash: String?,
    val roomIdHash: String?,
) : AbstractCoroutineContextElement(Key) {
    @Volatile var delegated: Boolean = false
    companion object Key : CoroutineContext.Key<TextVersionReceiptOwner>
}

/** LC-13 product completion does not wait for non-authorizing receipt transport; other lifecycles retain their ordering. */
suspend fun <T> AppOperationalStateCoordinator.performMessageWithDeferredReceipts(
    request: LifecycleOperationRequest,
    block: suspend (operationId: String) -> T,
): T {
    if (request.lifecycleId != FullAppLifecycleId.LC_13) return perform(request, block)
    return withOperation(request) { operationId ->
        val owner = TextVersionReceiptOwner(operationId, request.accountIdHash, request.matrixDeviceIdHash, request.roomIdHash)
        val result = withContext(owner) { block(operationId) }
        withContext(NonCancellable) {
            val components = if (owner.delegated) listOf(LifecycleComponent.ANDROID_FRONTEND)
                else listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
            enqueueClientReceipts(components.map { component ->
                LifecycleClientReceipt(
                    operationId,
                    component,
                    LifecyclePhase.CLIENT_APPLIED,
                    "${component.name}_APPLIED",
                    "${component.name.lowercase().replace('_', '-')}:$operationId",
                    System.currentTimeMillis(),
                )
            })
        }
        result
    }
}

/**
 * Executes a lifecycle product action owned by one Android component and submits only that
 * component's authoritative receipt. Specialized server-side triggers may deliberately register
 * a narrower component set than the lifecycle's generic entrypoint; callers must not fabricate a
 * receipt for an Android component that did not participate in the action.
 */
suspend fun <T> AppOperationalStateCoordinator.performForComponent(
    request: LifecycleOperationRequest,
    component: LifecycleComponent,
    block: suspend (operationId: String) -> T,
): T {
    require(component in setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)) {
        "Only Android components can perform client lifecycle actions."
    }
    return withOperation(request) { operationId ->
        performOnExistingOperation(
            operationId = operationId,
            components = listOf(component),
            block = block,
        )
    }
}

private suspend fun <T> AppOperationalStateCoordinator.performOnExistingOperation(
    operationId: String,
    components: List<LifecycleComponent>,
    parallelReceipts: Boolean = false,
    block: suspend (operationId: String) -> T,
): T {
    val result = block(operationId)
    val occurredAt = System.currentTimeMillis()
    val receipts = components.map { component ->
        LifecycleClientReceipt(
            operationId = operationId,
            component = component,
            phase = LifecyclePhase.CLIENT_APPLIED,
            resultCode = "${component.name}_APPLIED",
            receiptReference = "${component.name.lowercase().replace('_', '-')}:$operationId",
            occurredAtEpochMillis = occurredAt,
        )
    }
    // Once the product action has completed, its Android receipt intents are authoritative.
    // UI/navigation/configuration cancellation must not strand any required receipt.
    withContext(NonCancellable) {
        // Persist every completed component before the first transport can suspend or fail.
        // The original operation identity and occurrence time survive process recreation.
        persistClientReceipts(receipts)
        if (parallelReceipts) {
            // Each client fact already exists durably. Neither response grants early
            // product entry: await both, including when one transport fails.
            val failures = coroutineScope {
                receipts.map { receipt ->
                    async {
                        try {
                            recordClientReceipt(receipt)
                            null
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            failure
                        }
                    }
                }.awaitAll()
            }
            failures.firstOrNull { it != null }?.let { throw it }
        } else {
            var firstFailure: Throwable? = null
            var operationIsTerminal = false
            receipts.forEach { receipt ->
                if (!operationIsTerminal) {
                    try {
                        operationIsTerminal = recordClientReceipt(receipt).status.isTerminal
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                    }
                }
            }
            firstFailure?.let { throw it }
        }
    }
    return result
}

val NON_TERMINAL_RESUMABLE_STATUSES = setOf(
    LifecycleOperationStatus.PENDING,
    LifecycleOperationStatus.RUNNING,
    LifecycleOperationStatus.WAITING_EXTERNAL,
    LifecycleOperationStatus.RETRYABLE_FAILURE,
)

fun lifecycleOperationRequest(
    lifecycleId: FullAppLifecycleId,
    trigger: String,
    accountIdHash: String? = null,
    matrixDeviceIdHash: String? = null,
    roomIdHash: String? = null,
    eventIdHash: String? = null,
    operationId: String = UUID.randomUUID().toString(),
    deadlineEpochMillis: Long = System.currentTimeMillis() + DEFAULT_LIFECYCLE_OPERATION_TIMEOUT_MILLIS,
): LifecycleOperationRequest = LifecycleOperationRequest(
    operationId = operationId,
    lifecycleId = lifecycleId,
    trigger = trigger,
    idempotencyKey = operationId,
    accountIdHash = accountIdHash,
    matrixDeviceIdHash = matrixDeviceIdHash,
    roomIdHash = roomIdHash,
    eventIdHash = eventIdHash,
    deadlineEpochMillis = deadlineEpochMillis,
)

private const val DEFAULT_LIFECYCLE_OPERATION_TIMEOUT_MILLIS = 10 * 60_000L

enum class LifecycleUiAction {
    NONE,
    RETRY,
    OPEN_SETTINGS,
    OPEN_INSTALLER,
    VERIFY_DEVICE,
    WAIT_FOR_AUTHORITY,
    OPEN_DIAGNOSTICS,
    VIEW_RECEIPT,
}

data class AppLifecycleUiState(
    val lifecycleId: FullAppLifecycleId,
    val status: AppOperationalStatus,
    val operationId: String?,
    val reasonCode: String?,
    val primaryAction: LifecycleUiAction,
    val terminalReceiptId: String?,
)

object AppLifecycleUiProjector {
    fun projectAll(
        snapshot: AppOperationalSnapshot?,
        operations: Map<String, LifecycleOperation>,
    ): Map<FullAppLifecycleId, AppLifecycleUiState> {
        val latestByLifecycle = operations.values
            .groupBy(LifecycleOperation::lifecycleId)
            .mapValues { (_, candidates) -> candidates.maxBy(LifecycleOperation::updatedAtEpochMillis) }
        return FullAppLifecycleId.entries.associateWith { lifecycleId ->
            project(
                lifecycleId = lifecycleId,
                snapshot = snapshot,
                operation = latestByLifecycle[lifecycleId],
            )
        }
    }

    fun project(
        lifecycleId: FullAppLifecycleId,
        snapshot: AppOperationalSnapshot?,
        operation: LifecycleOperation?,
    ): AppLifecycleUiState {
        require(operation == null || operation.lifecycleId == lifecycleId) {
            "Lifecycle UI cannot consume an operation from another lifecycle."
        }
        val status = operation?.status?.toOperationalStatus() ?: snapshot?.overallStatus ?: AppOperationalStatus.UNKNOWN
        val reason = operation
            ?.steps
            ?.filter { it.required && it.status !in setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED) }
            ?.mapNotNull { it.resultCode }
            ?.firstOrNull()
            ?: snapshot?.blockers?.firstOrNull()
        return AppLifecycleUiState(
            lifecycleId = lifecycleId,
            status = status,
            operationId = operation?.operationId,
            reasonCode = reason,
            primaryAction = if (operation?.terminalReceiptId != null) {
                LifecycleUiAction.VIEW_RECEIPT
            } else {
                action(status, reason)
            },
            terminalReceiptId = operation?.terminalReceiptId,
        )
    }

    private fun LifecycleOperationStatus.toOperationalStatus(): AppOperationalStatus = when (this) {
        LifecycleOperationStatus.PENDING,
        LifecycleOperationStatus.RUNNING -> AppOperationalStatus.DISCOVERING
        LifecycleOperationStatus.WAITING_EXTERNAL -> AppOperationalStatus.WAITING_EXTERNAL
        LifecycleOperationStatus.RETRYABLE_FAILURE -> AppOperationalStatus.RETRYABLE_FAILURE
        LifecycleOperationStatus.BLOCKED,
        LifecycleOperationStatus.FAILED_FINAL,
        LifecycleOperationStatus.CANCELLED -> AppOperationalStatus.BLOCKED
        LifecycleOperationStatus.SUCCEEDED -> AppOperationalStatus.READY
        LifecycleOperationStatus.TERMINATED -> AppOperationalStatus.TERMINATED
    }

    private fun action(status: AppOperationalStatus, reasonCode: String?): LifecycleUiAction = when (status) {
        AppOperationalStatus.UNKNOWN,
        AppOperationalStatus.DISCOVERING -> LifecycleUiAction.NONE
        AppOperationalStatus.READY -> LifecycleUiAction.NONE
        AppOperationalStatus.LIMITED -> LifecycleUiAction.OPEN_DIAGNOSTICS
        AppOperationalStatus.WAITING_EXTERNAL -> LifecycleUiAction.WAIT_FOR_AUTHORITY
        AppOperationalStatus.RETRYABLE_FAILURE -> LifecycleUiAction.RETRY
        AppOperationalStatus.TERMINATING -> LifecycleUiAction.WAIT_FOR_AUTHORITY
        AppOperationalStatus.TERMINATED -> LifecycleUiAction.VIEW_RECEIPT
        AppOperationalStatus.BLOCKED -> when (reasonCode) {
            "RELEASE_UPDATE_REQUIRED" -> LifecycleUiAction.OPEN_INSTALLER
            "MATRIX_IDENTITY_REJECTED", "DEVICE_TRUST_PENDING" -> LifecycleUiAction.VERIFY_DEVICE
            "LOCATION_REQUIRED" -> LifecycleUiAction.OPEN_SETTINGS
            else -> LifecycleUiAction.OPEN_DIAGNOSTICS
        }
    }
}

object AppLifecycleEntrypoints {
    private val triggers = mapOf(
        FullAppLifecycleId.LC_01 to setOf("cold_start", "warm_start", "periodic_release_check", "installer_return"),
        FullAppLifecycleId.LC_02 to setOf("process_create", "process_reclaim", "foreground", "background", "task_restore"),
        FullAppLifecycleId.LC_03 to setOf("feature_requires_permission", "permission_result", "settings_return", "permission_revoked"),
        FullAppLifecycleId.LC_04 to setOf("deep_link", "notification_tap", "share_intent", "invitation_link"),
        FullAppLifecycleId.LC_05 to setOf("invitation_open", "registration_submit", "matrix_login", "first_activation"),
        FullAppLifecycleId.LC_06 to setOf("post_login", "device_sync", "policy_changed", "trust_changed", "reactivation"),
        FullAppLifecycleId.LC_07 to setOf("session_issue", "heartbeat", "expiry", "device_revoke", "account_revoke"),
        FullAppLifecycleId.LC_08 to setOf("verification_required", "new_device", "recovery_key_entered", "old_device_approval", "identity_reset_confirmed", "admin_approval"),
        FullAppLifecycleId.LC_09 to setOf("foreground", "lock_timeout", "capture_attempt", "clipboard_action", "policy_changed"),
        FullAppLifecycleId.LC_10 to setOf("home_enter", "matrix_sync", "filter_change", "network_change", "account_state_change"),
        FullAppLifecycleId.LC_11 to setOf("search", "invitation_accept", "friendship_change", "direct_room_create"),
        FullAppLifecycleId.LC_12 to setOf("room_create", "member_join", "member_leave", "member_ban", "trusted_device_change"),
        FullAppLifecycleId.LC_13 to setOf("send", "receive", "edit", "reply", "redact", "retry"),
        FullAppLifecycleId.LC_14 to setOf("draft_change", "room_switch", "account_switch", "send_press", "process_reclaim"),
        FullAppLifecycleId.LC_15 to
            setOf(
                "reaction",
                "poll",
                "media_caption",
                "forward",
                "live_location_operation",
                "relay_operation",
                "call_operation",
                "call_recording",
            ),
        FullAppLifecycleId.LC_16 to setOf("media_select", "upload", "download", "preview", "local_preview", "external_open", "cancel"),
        FullAppLifecycleId.LC_17 to setOf("record_start", "record_stop", "location_share", "live_location_start", "live_location_update", "live_location_stop"),
        FullAppLifecycleId.LC_18 to setOf("network_lost", "network_available", "matrix_sync_error", "control_sync_error", "clock_change"),
        FullAppLifecycleId.LC_19 to setOf("push_register", "token_rotate", "delivery_create", "delivery_claim", "delivery_complete", "device_revoke"),
        FullAppLifecycleId.LC_20 to setOf("call_invite", "ring", "accept", "reject", "join", "reconnect", "hangup", "access_revoke"),
        FullAppLifecycleId.LC_21 to setOf("message_commit", "expiry_due", "burn_consumed", "user_redact", "admin_destroy", "history_recovery"),
        FullAppLifecycleId.LC_22 to setOf("logout", "remove_local_account", "permanent_delete", "admin_destroy"),
        FullAppLifecycleId.LC_23 to setOf("settings_open", "diagnostics_refresh", "support_export", "component_state_changed"),
        FullAppLifecycleId.LC_25 to setOf("private_open", "private_create", "private_edit", "private_pin", "private_reset_order", "private_usage_commit", "private_sync", "private_delete", "favourite_add", "favourite_remove"),
        FullAppLifecycleId.LC_24 to setOf("locale_change", "font_scale_change", "screen_reader_change", "lifecycle_state_changed"),
    )

    val lifecycleIds: Set<FullAppLifecycleId> = triggers.keys

    fun isRegistered(lifecycleId: FullAppLifecycleId, trigger: String): Boolean = trigger in triggers[lifecycleId].orEmpty()
}
