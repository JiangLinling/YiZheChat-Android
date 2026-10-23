package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.RecoveryIntentAuthority
import io.element.android.security.dataprotection.api.RecoveryIntentJournal
import io.element.android.security.dataprotection.api.RecoveryIntentRecord
import io.element.android.security.dataprotection.api.RecoveryIntentRejected
import io.element.android.security.dataprotection.api.RecoveryIntentScope
import io.element.android.security.dataprotection.api.RecoveryOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultRecoveryIntentAuthority(
    private val journal: RecoveryIntentJournal,
    private val coordinator: AppOperationalStateCoordinator,
    private val management: ManagementControlService,
) : RecoveryIntentAuthority {
    private val mutation = Mutex()
    override suspend fun retireExpiredForIdentityReset(scope: RecoveryIntentScope) = mutation.withLock {
        val record = journal.read(scope) ?: return@withLock
        // A reset cannot admit or replace a recovery attempt. Only an already-bound original
        // operation may be closed through the existing server-owned expiry producer.
        check(record.identityGeneration != null) { "RECOVERY_RESET_RETIREMENT_REQUIRES_ADMITTED_INTENT" }
        var context = management.recoveryOperationContext(record)
        validateBinding(record, context)
        if (context.deadlineEpochMillis > context.serverTimeEpochMillis) {
            throw RecoveryIntentRejected("RECOVERY_APPROVAL_PENDING")
        }
        if (!context.status.isTerminal) {
            management.expireRecoveryOperation(record)
            context = management.recoveryOperationContext(record)
            validateBinding(record, context)
        }
        // The reset has changed the Matrix root. That must never authorize old content, but
        // does not invalidate a nonce-bound denial for this exact expired operation.
        check(context.status == LifecycleOperationStatus.FAILED_FINAL && context.terminalReceiptId != null &&
            context.terminalResultCode == "RECOVERY_OPERATION_EXPIRED" &&
            context.deadlineEpochMillis <= context.serverTimeEpochMillis) { "RECOVERY_RESET_RETIREMENT_NOT_CONFIRMED" }
        check(journal.compareAndSet(scope, record.revision, null))
    }
    override suspend fun restore(scope: RecoveryIntentScope): RecoveryIntentRecord? = mutation.withLock {
        val record = journal.read(scope) ?: return@withLock null
        if (!ensureAdmitted(record)) return@withLock null
        val context = management.recoveryOperationContext(record)
        validateBinding(record, context)
        if (!context.scopeValid && context.currentRootValid &&
            (context.currentIdentityGeneration != context.identityGeneration || context.currentMobileGeneration != context.mobileGeneration)) {
            check(journal.compareAndSet(scope, record.revision, null))
            return@withLock null // Signed generation change invalidates the LOCAL owner only; no server facts are rewritten.
        }
        validateIdentity(record, context)
        if (context.status == LifecycleOperationStatus.SUCCEEDED) {
            check(context.terminalReceiptId != null)
            check(journal.compareAndSet(scope, record.revision, null))
            return@withLock null
        }
        if (context.status == LifecycleOperationStatus.FAILED_FINAL) {
            throw RecoveryIntentRejected(context.terminalResultCode ?: "RECOVERY_OPERATION_NOT_RUNNABLE")
        }
        if (!context.status.isTerminal && context.deadlineEpochMillis <= context.serverTimeEpochMillis) {
            throw RecoveryIntentRejected("RECOVERY_OPERATION_EXPIRED")
        }
        bindGeneration(record, context)
    }
    override suspend fun admit(scope: RecoveryIntentScope): RecoveryIntentRecord = admitOwned(scope, null)
    override suspend fun admit(scope: RecoveryIntentScope, mode: io.element.android.security.dataprotection.api.RecoveryHistoryMode): RecoveryIntentRecord =
        admitOwned(scope, mode)

    private suspend fun admitOwned(scope: RecoveryIntentScope, mode: io.element.android.security.dataprotection.api.RecoveryHistoryMode?): RecoveryIntentRecord = mutation.withLock {
        var record = journal.read(scope)
        if (record == null) {
            val request = lifecycleOperationRequest(
                FullAppLifecycleId.LC_08,
                "recovery_key_entered",
                accountIdHash = scope.accountIdHash,
                matrixDeviceIdHash = scope.matrixDeviceIdHash
            )
            val draft = RecoveryIntentRecord(scope, request.operationId, request.idempotencyKey, request.deadlineEpochMillis, 1, recoveryMode = mode)
            record = if (journal.compareAndSet(scope, null, draft)) draft else checkNotNull(journal.read(scope))
        }
        var owned = checkNotNull(record)
        if (!diagnosticStage("ADMISSION_RESOLUTION") { ensureAdmitted(owned) }) {
            // Only an explicit user attempt creates a new intent after signed, expired non-admission.
            owned = newDraft(scope, mode)
            check(ensureAdmitted(owned))
        }
        var context = diagnosticStage("CONTEXT_READ") { management.recoveryOperationContext(owned) }
        diagnosticStage("CONTEXT_VALIDATE") { validateIdentity(owned, context) }
        if (!context.status.isTerminal && context.deadlineEpochMillis <= context.serverTimeEpochMillis) {
            diagnosticStage("EXPIRE_REQUEST") { management.expireRecoveryOperation(owned) }
            context = management.recoveryOperationContext(owned)
            diagnosticStage("CONTEXT_VALIDATE") { validateIdentity(owned, context) }
        }
        if (context.status == LifecycleOperationStatus.FAILED_FINAL &&
            context.terminalReceiptId != null && context.terminalResultCode in setOf("RECOVERY_SECRET_STORAGE_REJECTED", "RECOVERY_OPERATION_EXPIRED")) {
            // A new explicitly submitted key is a new attempt, after the original signed rejection.
            check(journal.compareAndSet(scope, owned.revision, null))
            val request = lifecycleOperationRequest(
                FullAppLifecycleId.LC_08,
                "recovery_key_entered",
                accountIdHash = scope.accountIdHash,
                matrixDeviceIdHash = scope.matrixDeviceIdHash
            )
            val draft = RecoveryIntentRecord(scope, request.operationId, request.idempotencyKey, request.deadlineEpochMillis, 1, recoveryMode = mode)
            check(journal.compareAndSet(scope, null, draft))
            coordinator.start(draft.request())
            val fresh = management.recoveryOperationContext(draft)
            validateIdentity(draft, fresh)
            requireRunnable(fresh)
            return@withLock bindGeneration(draft, fresh)
        }
        if (mode != null && (owned.recoveryMode ?: io.element.android.security.dataprotection.api.RecoveryHistoryMode.STANDARD) != mode) {
            throw RecoveryIntentRejected("RECOVERY_METHOD_CHANGE_REQUIRES_TERMINAL")
        }
        diagnosticStage("RUNNABLE_VALIDATE") { requireRunnable(context) }
        bindGeneration(owned, context)
    }
    override suspend fun verify(record: RecoveryIntentRecord): RecoveryOperationContext = mutation.withLock {
        val current = checkNotNull(journal.read(record.scope))
        check(current.operationId == record.operationId && current.identityGeneration == record.identityGeneration)
        check(current.recoveryMode == record.recoveryMode) { "RECOVERY_AUTHORITY_SCOPE_REJECTED" }
        val context = management.recoveryOperationContext(current)
        validateIdentity(current, context)
        requireRunnable(context)
        context
    }
    override suspend fun waiting(record: RecoveryIntentRecord, recoveryId: String, expiresAtEpochMillis: Long): RecoveryIntentRecord = mutation.withLock {
        val current = checkNotNull(journal.read(record.scope))
        check(current.operationId == record.operationId)
        val next = current.copy(revision = current.revision + 1, recoveryId = recoveryId, recoveryExpiresAtEpochMillis = expiresAtEpochMillis)
        check(journal.compareAndSet(record.scope, current.revision, next))
        next
    }
    override suspend fun completed(record: RecoveryIntentRecord) = mutation.withLock {
        val current = checkNotNull(journal.read(record.scope))
        check(current.operationId == record.operationId)
        val context = management.recoveryOperationContext(current)
        validateIdentity(current, context)
        check(context.status == LifecycleOperationStatus.SUCCEEDED && context.terminalReceiptId != null)
        check(journal.compareAndSet(record.scope, current.revision, null))
    }
    private suspend fun <T> diagnosticStage(stage: String, action: suspend () -> T): T = try {
        action()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Phase and class only. Never log token/key values, exception messages or full SDK objects.
        timber.log.Timber.w("YZRECOVERY_STAGE_FAILURE=%s type=%s", stage, failure.javaClass.simpleName)
        throw failure
    }

    private fun newDraft(scope: RecoveryIntentScope, mode: io.element.android.security.dataprotection.api.RecoveryHistoryMode?): RecoveryIntentRecord {
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_08,
            "recovery_key_entered",
            accountIdHash = scope.accountIdHash,
            matrixDeviceIdHash = scope.matrixDeviceIdHash
        )
        val draft = RecoveryIntentRecord(scope, request.operationId, request.idempotencyKey, request.deadlineEpochMillis, 1, recoveryMode = mode)
        check(journal.compareAndSet(scope, null, draft))
        return draft
    }
    private suspend fun ensureAdmitted(record: RecoveryIntentRecord): Boolean {
        if (record.identityGeneration != null) {
            coordinator.operation(record.operationId)
            return true
        }
        val resolution = management.resolveRecoveryIntent(record)
        if (resolution.operationId != record.operationId || resolution.scope != record.scope ||
            resolution.deadlineEpochMillis != record.deadlineEpochMillis || resolution.serverTimeEpochMillis <= 0 || resolution.recoveryMode != record.recoveryMode ||
            resolution.idempotencyKeySha256 != java.security.MessageDigest.getInstance("SHA-256").digest(record.idempotencyKey.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }) {
            throw RecoveryIntentRejected("RECOVERY_RESOLUTION_SCOPE_REJECTED")
        }
        if (resolution.admitted) {
            coordinator.operation(record.operationId)
            return true
        }
        if (record.deadlineEpochMillis <= resolution.serverTimeEpochMillis) {
            check(journal.compareAndSet(record.scope, record.revision, null))
            return false // Local draft only; no server operation, receipt or history is rewritten.
        }
        coordinator.start(record.request())
        return true
    }
    private fun validateIdentity(record: RecoveryIntentRecord, context: RecoveryOperationContext) {
        validateBinding(record, context)
        if (!context.scopeValid || !context.rootValid) throw RecoveryIntentRejected("RECOVERY_AUTHORITY_SCOPE_REJECTED")
    }
    private fun validateBinding(record: RecoveryIntentRecord, context: RecoveryOperationContext) {
        if (context.operationId != record.operationId || context.scope != record.scope || context.trigger != "recovery_key_entered" || context.recoveryMode != record.recoveryMode ||
            context.deadlineEpochMillis != record.deadlineEpochMillis ||
            context.idempotencyKeySha256 != java.security.MessageDigest.getInstance("SHA-256").digest(record.idempotencyKey.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) } ||
            context.status.isTerminal != (context.terminalReceiptId != null) ||
            (record.identityGeneration != null && context.identityGeneration != record.identityGeneration) ||
            (record.mobileGeneration != null && context.mobileGeneration != record.mobileGeneration)) {
            throw RecoveryIntentRejected("RECOVERY_AUTHORITY_SCOPE_REJECTED")
        }
    }
    private fun requireRunnable(context: RecoveryOperationContext) {
        if (context.deadlineEpochMillis <= context.serverTimeEpochMillis) throw RecoveryIntentRejected("RECOVERY_OPERATION_EXPIRED")
        if (context.status.isTerminal || context.deadlineEpochMillis <= context.serverTimeEpochMillis ||
            context.status !in setOf(
                LifecycleOperationStatus.PENDING,
                LifecycleOperationStatus.RUNNING,
                LifecycleOperationStatus.WAITING_EXTERNAL,
                LifecycleOperationStatus.RETRYABLE_FAILURE
            )) {
            throw RecoveryIntentRejected("RECOVERY_OPERATION_NOT_RUNNABLE")
        }
    }
    private fun bindGeneration(record: RecoveryIntentRecord, context: RecoveryOperationContext): RecoveryIntentRecord {
        if (record.identityGeneration != null) return record
        val next = record.copy(revision = record.revision + 1, identityGeneration = context.identityGeneration, mobileGeneration = context.mobileGeneration)
        check(journal.compareAndSet(record.scope, record.revision, next))
        return next
    }
}
