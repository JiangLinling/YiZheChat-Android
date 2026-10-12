/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.dataprotection.api.AccountDeletionPreparationRequest
import io.element.android.security.dataprotection.api.AccountDeletionRecoveryRequest
import io.element.android.security.dataprotection.api.AccountDeletionReceipt
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingMatrixException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingGlobalDestroyException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceipt
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionPreparationExpiredException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionRecoveryReport
import io.element.android.security.dataprotection.api.PermanentAccountDeletionState
import io.element.android.security.dataprotection.api.PermanentAccountDeletionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultPermanentAccountDeletionCoordinator(
    private val managementControlService: ManagementControlService,
    private val dataDeletionCoordinator: DataDeletionCoordinator,
    private val intentStore: PermanentAccountDeletionIntentStore,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
) : PermanentAccountDeletionCoordinator {
    private val mutex = Mutex()
    private val random = SecureRandom()

    override suspend fun prepare(
        localDeletionRequest: DeletionRequest,
        eraseData: Boolean,
    ): Result<PermanentAccountDeletionHandle> = runCatching {
        mutex.withLock {
            val accountScope = localDeletionRequest.scope as? DeletionScope.Account
                ?: error("Permanent deletion requires an account-scoped local cleanup request.")
            check(localDeletionRequest.reason == DeletionReason.USER_REQUEST)
            intentStore.loadAll().records.singleOrNull { it.accountIdHash == accountScope.accountIdHash }?.let { existing ->
                return@withLock ensurePrepared(existing).handle
            }
            val handle = PermanentAccountDeletionHandle(localDeletionRequest.operationId.value)
            val remote = managementControlService.pendingPermanentAccountDeletion(accountScope.accountIdHash)
            if (remote != null) {
                check(remote.operationId == handle.value) {
                    "Frozen account deletion must resume its original lifecycle operation."
                }
                check(remote.accountIdHash == accountScope.accountIdHash && remote.eraseData == eraseData)
            }
            val draft = PendingPermanentAccountDeletion(
                handle = handle,
                accountIdHash = accountScope.accountIdHash,
                eraseData = eraseData,
                idempotencyKey = randomOpaque(IDEMPOTENCY_BYTES),
                confirmationToken = randomOpaque(CONFIRMATION_TOKEN_BYTES),
                localDeletionRequest = localDeletionRequest,
                phase = if (remote == null) PermanentDeletionPhase.DRAFT else PermanentDeletionPhase.RECOVERY_DRAFT,
            )
            intentStore.save(draft)
            ensurePrepared(draft).handle
        }
    }

    override suspend fun pending(accountIdHash: String): PermanentAccountDeletionHandle? = mutex.withLock {
        intentStore.loadAll().records.singleOrNull { it.accountIdHash == accountIdHash }?.handle
    }

    override suspend fun pendingRecovery(accountIdHash: String): PermanentAccountDeletionHandle? = mutex.withLock {
        val local = intentStore.loadAll().records.singleOrNull {
            it.accountIdHash == accountIdHash && it.phase != PermanentDeletionPhase.SUBMISSION_BLOCKED
        }?.handle
        local ?: managementControlService.pendingPermanentAccountDeletion(accountIdHash)
            ?.let { PermanentAccountDeletionHandle(it.operationId) }
    }

    override suspend fun commitRemote(handle: PermanentAccountDeletionHandle): Result<AccountDeletionReceipt> = runCatching {
        mutex.withLock {
            val record = requireNotNull(intentStore.load(handle)) { "Permanent deletion intent is unavailable." }
            val preparedRecord = ensurePrepared(record)
            check(preparedRecord.phase != PermanentDeletionPhase.LOCAL_CLEARED) {
                "Local deletion is complete and only its scoped receipt may be retried."
            }
            if (preparedRecord.phase == PermanentDeletionPhase.REMOTE_COMMITTED) {
                // The signed commit is intentionally not persisted with the secret. Retrying the idempotent endpoint
                // returns the exact server-stored receipt and revalidates its signature.
                return@withLock commit(preparedRecord)
            }
            commit(preparedRecord)
        }
    }

    override suspend fun completeLocal(
        handle: PermanentAccountDeletionHandle,
        confirmedPreconditions: Set<DeletionStep>,
    ): DeletionReport = mutex.withLock {
        val record = requireNotNull(intentStore.load(handle)) { "Permanent deletion intent is unavailable." }
        check(record.phase in setOf(PermanentDeletionPhase.REMOTE_COMMITTED, PermanentDeletionPhase.LOCAL_CLEARED)) {
            "Local account data cannot be removed before irreversible server deletion has started."
        }
        eraseAndAcknowledge(record, confirmedPreconditions)
    }

    override suspend fun abandon(handle: PermanentAccountDeletionHandle): Boolean = mutex.withLock {
        val record = intentStore.load(handle) ?: return@withLock true
        check(record.phase in setOf(PermanentDeletionPhase.DRAFT, PermanentDeletionPhase.SUBMISSION_BLOCKED)) {
            "A frozen account deletion must retain its durable operation identity and confirmation secret."
        }
        intentStore.delete(handle)
    }

    override suspend fun resumePending(coldStart: Boolean): PermanentAccountDeletionRecoveryReport = mutex.withLock {
        val pending = intentStore.loadAll()
        var completed = 0
        var waitingForMatrix = 0
        var retryRequired = 0
        pending.records.forEach { initial ->
            // A definite pre-freeze rejection needs a fresh user retry. Reopening the app or
            // enabling the server release gate must not silently submit this destructive intent.
            if (initial.phase == PermanentDeletionPhase.SUBMISSION_BLOCKED) return@forEach
            val remote = runCatching {
                val prepared = ensurePrepared(initial)
                if (prepared.phase in setOf(
                        PermanentDeletionPhase.REMOTE_COMMITTED,
                        PermanentDeletionPhase.LOCAL_CLEARED,
                    )
                ) {
                    prepared
                } else {
                    commit(prepared)
                    requireNotNull(intentStore.load(prepared.handle))
                }
            }
            val record = remote.getOrElse { failure ->
                if (failure is PermanentAccountDeletionAwaitingMatrixException) {
                    waitingForMatrix += 1
                } else {
                    retryRequired += 1
                }
                return@forEach
            }
            val preconditions = if (coldStart) COLD_START_PRECONDITIONS else emptySet()
            val report = runCatching { eraseAndAcknowledge(record, preconditions) }.getOrElse { failure ->
                if (failure is PermanentAccountDeletionAwaitingGlobalDestroyException) {
                    retryRequired += 1
                } else {
                    retryRequired += 1
                }
                return@forEach
            }
            if (report.isComplete) completed += 1 else retryRequired += 1
        }
        PermanentAccountDeletionRecoveryReport(
            completedCount = completed,
            waitingForMatrixDeactivationCount = waitingForMatrix,
            retryRequiredCount = retryRequired,
            unreadableRecordCount = pending.unreadableRecordCount,
        )
    }

    private suspend fun ensurePrepared(
        record: PendingPermanentAccountDeletion,
        canRenewExpiredDraft: Boolean = true,
    ): PendingPermanentAccountDeletion {
        if (record.phase !in setOf(
                PermanentDeletionPhase.DRAFT,
                PermanentDeletionPhase.RECOVERY_DRAFT,
                PermanentDeletionPhase.SUBMISSION_BLOCKED,
            )
        ) return record
        val prepared = try {
            withContext(LifecycleOperationContext(record.handle.value, FullAppLifecycleId.LC_22)) {
                if (record.phase == PermanentDeletionPhase.RECOVERY_DRAFT) {
                    managementControlService.recoverPermanentAccountDeletion(
                        AccountDeletionRecoveryRequest(
                            operationId = record.handle.value,
                            accountIdHash = record.accountIdHash,
                            idempotencyKey = record.idempotencyKey,
                            confirmationTokenSha256 = sha256Hex(record.confirmationToken),
                            eraseData = record.eraseData,
                        )
                    )
                } else {
                    managementControlService.preparePermanentAccountDeletion(
                        AccountDeletionPreparationRequest(
                            accountIdHash = record.accountIdHash,
                            idempotencyKey = record.idempotencyKey,
                            confirmationTokenSha256 = sha256Hex(record.confirmationToken),
                            eraseData = record.eraseData,
                        )
                    )
                }
            }
        } catch (failure: PermanentAccountDeletionSubmissionBlockedException) {
            if (record.phase != PermanentDeletionPhase.RECOVERY_DRAFT) {
                intentStore.save(record.copy(phase = PermanentDeletionPhase.SUBMISSION_BLOCKED))
            }
            throw failure
        } catch (failure: PermanentAccountDeletionPreparationExpiredException) {
            if (!canRenewExpiredDraft) throw failure
            // A DRAFT means Matrix deactivation has not started: RustMatrixClient waits for prepare() to return.
            // It is therefore safe to rotate the client-owned secret and idempotency key, persist them first,
            // and create a fresh server intent. PREPARED/REMOTE_COMMITTED records are never rotated here.
            val renewed = record.copy(
                idempotencyKey = randomOpaque(IDEMPOTENCY_BYTES),
                confirmationToken = randomOpaque(CONFIRMATION_TOKEN_BYTES),
                deletionIntentId = null,
                installationId = null,
                preparedAtEpochMillis = null,
                expiresAtEpochMillis = null,
            )
            intentStore.save(renewed)
            return ensurePrepared(renewed, canRenewExpiredDraft = false)
        }
        check(prepared.accountIdHash == record.accountIdHash && prepared.eraseData == record.eraseData)
        val updated = record.copy(
            phase = PermanentDeletionPhase.PREPARED,
            deletionIntentId = prepared.deletionIntentId,
            installationId = prepared.installationId,
            preparedAtEpochMillis = prepared.preparedAtEpochMillis,
            expiresAtEpochMillis = prepared.expiresAtEpochMillis,
        )
        intentStore.save(updated)
        return updated
    }

    private suspend fun commit(record: PendingPermanentAccountDeletion): AccountDeletionReceipt {
        val receipt = withContext(LifecycleOperationContext(record.handle.value, FullAppLifecycleId.LC_22)) {
            managementControlService.commitPermanentAccountDeletion(
                prepared = io.element.android.security.dataprotection.api.PreparedAccountDeletion(
                    deletionIntentId = requireNotNull(record.deletionIntentId),
                    accountIdHash = record.accountIdHash,
                    installationId = requireNotNull(record.installationId),
                    eraseData = record.eraseData,
                    preparedAtEpochMillis = requireNotNull(record.preparedAtEpochMillis),
                    expiresAtEpochMillis = requireNotNull(record.expiresAtEpochMillis),
                ),
                confirmationToken = record.confirmationToken,
            )
        }
        val committed = record.copy(phase = PermanentDeletionPhase.REMOTE_COMMITTED)
        intentStore.save(committed)
        return receipt
    }

    private suspend fun eraseAndAcknowledge(
        initial: PendingPermanentAccountDeletion,
        confirmedPreconditions: Set<DeletionStep>,
    ): DeletionReport {
        var record = initial
        if (record.phase == PermanentDeletionPhase.REMOTE_COMMITTED) {
            val status = deletionStatus(record)
            val capability = status.clientReceiptCapability
            if (status.securityErasureMilestone == null || capability == null) {
                throw PermanentAccountDeletionAwaitingGlobalDestroyException()
            }
            record = record.copy(
                lastDeletionState = status.deletionState,
                clientReceiptToken = capability.token,
                clientReceiptScopeHash = capability.resourceScopeHash,
                clientReceiptTokenExpiresAtEpochMillis = capability.expiresAtEpochMillis,
            )
            intentStore.save(record)
            check(
                accountReactivationScopeStore.clearPermanentlyDeletedAccount(
                    accountIdHash = record.accountIdHash,
                    installationId = requireNotNull(record.installationId),
                )
            ) { "Unable to erase account activation checkpoints." }
            val report = dataDeletionCoordinator.delete(
                record.localDeletionRequest.copy(confirmedPreconditions = confirmedPreconditions)
            )
            if (!report.isComplete) return report
            val clearedAt = System.currentTimeMillis()
            val manifestDigest = localManifestDigest(record, report)
            val attestationEvidence = sha256Hex(
                "LC22_CLIENT_CLEAR\u0000${record.handle.value}\u0000${requireNotNull(record.installationId)}" +
                    "\u0000$manifestDigest\u0000$clearedAt"
            )
            record = record.copy(
                phase = PermanentDeletionPhase.LOCAL_CLEARED,
                idempotencyKey = REDACTED_IDEMPOTENCY_KEY,
                confirmationToken = REDACTED_CONFIRMATION_TOKEN,
                localManifestDigestSha256 = manifestDigest,
                localAttestationEvidenceHash = attestationEvidence,
                localClearedAtEpochMillis = clearedAt,
            )
            intentStore.save(record)
        }
        check(record.phase == PermanentDeletionPhase.LOCAL_CLEARED)
        val accepted = runCatching {
            withContext(LifecycleOperationContext(record.handle.value, FullAppLifecycleId.LC_22)) {
                managementControlService.recordPermanentAccountDeletionClientReceipt(
                    PermanentAccountDeletionClientReceipt(
                        operationId = record.handle.value,
                        installationId = requireNotNull(record.installationId),
                        resourceScopeHash = requireNotNull(record.clientReceiptScopeHash),
                        manifestDigestSha256 = requireNotNull(record.localManifestDigestSha256),
                        attestationEvidenceHash = requireNotNull(record.localAttestationEvidenceHash),
                        occurredAtEpochMillis = requireNotNull(record.localClearedAtEpochMillis),
                        deletionScopedToken = requireNotNull(record.clientReceiptToken),
                    )
                )
            }
        }.getOrNull()?.accepted == true
        if (accepted && intentStore.delete(record.handle)) {
            return DeletionReport(
                operationId = record.localDeletionRequest.operationId,
                completedSteps = DeletionStep.entries.toSet(),
                retryRequired = false,
            )
        }
        return DeletionReport(
            operationId = record.localDeletionRequest.operationId,
            completedSteps = DeletionStep.entries.toSet(),
            retryRequired = true,
            errorCode = "CLIENT_CLEAR_RECEIPT_PENDING",
        )
    }

    private suspend fun deletionStatus(record: PendingPermanentAccountDeletion): PermanentAccountDeletionStatus {
        val status = withContext(LifecycleOperationContext(record.handle.value, FullAppLifecycleId.LC_22)) {
            managementControlService.permanentAccountDeletionStatus(record.handle.value)
        }
        check(status.operationId == record.handle.value)
        check(status.securityErasureMilestone?.accountIdHash in setOf(null, record.accountIdHash))
        val previous = record.lastDeletionState
        check(previous == null || deletionStateRank(status.deletionState) >= deletionStateRank(previous)) {
            "Permanent account deletion state moved backwards."
        }
        return status
    }

    private fun deletionStateRank(state: PermanentAccountDeletionState): Int = when (state) {
        PermanentAccountDeletionState.PREPARED -> 0
        PermanentAccountDeletionState.FROZEN -> 1
        PermanentAccountDeletionState.MATRIX_VERIFIED -> 2
        PermanentAccountDeletionState.CRYPTO_ERASED,
        PermanentAccountDeletionState.AUTHORITIES_PENDING,
        PermanentAccountDeletionState.CLIENT_CLEAR_PENDING,
        -> 3
        PermanentAccountDeletionState.RETRYABLE_FAILURE,
        PermanentAccountDeletionState.BLOCKED,
        PermanentAccountDeletionState.CONTRADICTED,
        -> 3
        PermanentAccountDeletionState.COMPLETE -> 4
    }

    private fun localManifestDigest(
        record: PendingPermanentAccountDeletion,
        report: DeletionReport,
    ): String = sha256Hex(
        buildString {
            append(record.handle.value)
            append('\u0000')
            append(record.accountIdHash)
            append('\u0000')
            append(report.completedSteps.map(DeletionStep::name).sorted().joinToString(","))
            append('\u0000')
            append(report.errorCode.orEmpty())
        }
    )

    private fun randomOpaque(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also(random::nextBytes)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val IDEMPOTENCY_BYTES = 24
        const val CONFIRMATION_TOKEN_BYTES = 32
        const val REDACTED_IDEMPOTENCY_KEY = "lc22-local-cleared-idempotency"
        const val REDACTED_CONFIRMATION_TOKEN = "lc22-local-cleared-confirmation-token"
        val COLD_START_PRECONDITIONS = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
    }
}
