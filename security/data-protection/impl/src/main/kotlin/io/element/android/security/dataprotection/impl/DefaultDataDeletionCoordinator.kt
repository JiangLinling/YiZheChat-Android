/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRecoveryReport
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.PermanentAccountLocalResourceCleaner
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.encrypteddb.DatabaseSecurityMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durable, fail-closed coordinator for destructive local account cleanup.
 *
 * The request is fsynced before the first destructive operation and every completed step is committed to an
 * append-only revision. Re-running a step is safe: vault tombstones, Keystore deletion, database maintenance and
 * notification cancellation are all idempotent. Scoped content and asset requests are journalled and complete only
 * after their protected key access is destroyed and remote access is revoked. Server-side storage deletion remains
 * the responsibility of the lifecycle deletion job and its signed receipts.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDataDeletionCoordinator(
    private val sensitiveFileStore: SensitiveFileStore,
    private val databaseSecurityMaintenance: DatabaseSecurityMaintenance,
    private val deletionJournal: DeletionJournal,
    private val deletionNotificationCleaner: DeletionNotificationCleaner,
    private val deletionSessionCleaner: DeletionSessionCleaner,
    private val accountLocalStateCleaner: AccountLocalStateCleaner,
    private val contentProtectionService: ContentProtectionService,
    private val permanentAccountLocalResourceCleaners: Set<@JvmSuppressWildcards PermanentAccountLocalResourceCleaner> = emptySet(),
) : DataDeletionCoordinator {
    private val operationMutex = Mutex()

    override suspend fun prepare(request: DeletionRequest): DeletionReport = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val begin = runCatchingExceptions { deletionJournal.begin(request) }.getOrElse {
                return@withLock DeletionReport(
                    operationId = request.operationId,
                    completedSteps = emptySet(),
                    retryRequired = true,
                    errorCode = ERROR_JOURNAL_WRITE_FAILED,
                )
            }
            begin.entry.toReport(retryRequired = !begin.alreadyComplete)
        }
    }

    override suspend fun delete(request: DeletionRequest): DeletionReport = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            beginAndExecute(request)
        }
    }

    override suspend fun resumePending(coldStart: Boolean): DeletionRecoveryReport = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val pending = runCatchingExceptions(deletionJournal::loadPending).getOrElse {
                return@withLock DeletionRecoveryReport(
                    operationReports = emptyList(),
                    unreadableJournalEntries = 1,
                )
            }
            DeletionRecoveryReport(
                operationReports = pending.entries.map { pendingEntry ->
                    val entry = if (coldStart) {
                        confirmPreconditions(pendingEntry, COLD_START_PRECONDITIONS)
                            ?: return@map pendingEntry.toReport(
                                retryRequired = true,
                                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
                            )
                    } else {
                        pendingEntry
                    }
                    execute(entry)
                },
                unreadableJournalEntries = pending.unreadableEntries,
            )
        }
    }

    private suspend fun beginAndExecute(request: DeletionRequest): DeletionReport {
        val begin = runCatchingExceptions { deletionJournal.begin(request) }.getOrElse {
            return DeletionReport(
                operationId = request.operationId,
                completedSteps = emptySet(),
                retryRequired = true,
                errorCode = ERROR_JOURNAL_WRITE_FAILED,
            )
        }
        if (begin.alreadyComplete) return begin.entry.toReport(retryRequired = false)
        return execute(begin.entry)
    }

    private suspend fun execute(pendingEntry: DeletionJournalEntry): DeletionReport {
        var entry = runCatchingExceptions {
            deletionJournal.update(
                pendingEntry.copy(
                    attemptCount = pendingEntry.attemptCount + 1,
                    lastErrorCode = null,
                )
            )
        }.getOrElse {
            return pendingEntry.toReport(retryRequired = true, errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED)
        }

        if (entry.request.scope is DeletionScope.Account && !entry.completedSteps.containsAll(REQUIRED_CALLER_PRECONDITIONS)) {
            return persistFailure(entry, ERROR_LOCAL_PRECONDITIONS_NOT_CONFIRMED)
        }

        if (entry.request.scope !is DeletionScope.Account) return executeScoped(entry)

        if (!entry.hasCompleted(DeletionStep.DESTROYED_KEY_ACCESS, DeletionStep.DELETED_FILES)) {
            val scope = entry.request.scope as DeletionScope.Account
            if (entry.request.reason == DeletionReason.USER_REQUEST) {
                val featureResourcesCleared = permanentAccountLocalResourceCleaners.all { cleaner ->
                    runCatchingExceptions { cleaner.clear(scope) }.getOrDefault(false)
                }
                if (!featureResourcesCleared) return persistFailure(entry, ERROR_LOCAL_RESIDUAL_STATE_DELETION_FAILED)
            }
            val residualStateCleared = runCatchingExceptions {
                accountLocalStateCleaner.clear(scope, preserveAccountState = deletionSessionCleaner.hasReplacement(scope))
            }.getOrElse {
                return persistFailure(entry, ERROR_LOCAL_RESIDUAL_STATE_DELETION_FAILED)
            }
            if (!residualStateCleared) return persistFailure(entry, ERROR_LOCAL_RESIDUAL_STATE_DELETION_FAILED)
            val vaultReport = runCatchingExceptions { sensitiveFileStore.deleteVault(AccountVaultId(scope.vaultId)) }.getOrElse {
                return persistFailure(entry, ERROR_VAULT_ERASURE_FAILED)
            }
            val vaultSteps = buildSet {
                if (vaultReport.tombstoneWritten) add(DeletionStep.MARKED_INACCESSIBLE)
                if (vaultReport.keyDestroyed) add(DeletionStep.DESTROYED_KEY_ACCESS)
            }
            entry = persistProgress(entry, vaultSteps) ?: return entry.toReport(
                retryRequired = true,
                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
            )
            if (!vaultReport.cryptographicEraseCompleted) return persistFailure(entry, ERROR_VAULT_KEY_DESTRUCTION_FAILED)
            if (!vaultReport.filesDeleted) return persistFailure(entry, ERROR_VAULT_FILE_DELETION_FAILED)

            val sessionReport = runCatchingExceptions { deletionSessionCleaner.delete(scope) }.getOrElse {
                return persistFailure(entry, ERROR_SESSION_STATE_DELETION_FAILED)
            }
            if (!sessionReport.sessionRecordRemoved) return persistFailure(entry, ERROR_SESSION_STATE_DELETION_FAILED)
            if (!sessionReport.sessionFilesDeleted) return persistFailure(entry, ERROR_SESSION_FILE_DELETION_FAILED)
            entry = persistProgress(entry, setOf(DeletionStep.DELETED_FILES)) ?: return entry.toReport(
                retryRequired = true,
                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
            )
        }

        if (DeletionStep.SANITIZED_DATABASES !in entry.completedSteps) {
            val databaseReport = runCatchingExceptions { databaseSecurityMaintenance.checkpointAndTruncate() }.getOrElse {
                return persistFailure(entry, ERROR_DATABASE_CHECKPOINT_FAILED)
            }
            if (!databaseReport.completed) return persistFailure(entry, ERROR_DATABASE_CHECKPOINT_FAILED)
            entry = persistProgress(entry, setOf(DeletionStep.SANITIZED_DATABASES)) ?: return entry.toReport(
                retryRequired = true,
                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
            )
        }

        if (DeletionStep.CANCELLED_NOTIFICATIONS !in entry.completedSteps) {
            val scope = entry.request.scope as DeletionScope.Account
            if (!deletionSessionCleaner.hasReplacement(scope) && !deletionNotificationCleaner.cancel(scope)) {
                return persistFailure(entry, ERROR_NOTIFICATION_CANCELLATION_FAILED)
            }
            entry = persistProgress(entry, setOf(DeletionStep.CANCELLED_NOTIFICATIONS)) ?: return entry.toReport(
                retryRequired = true,
                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
            )
        }

        val missingSteps = REQUIRED_LOCAL_COMPLETION_STEPS - entry.completedSteps
        if (missingSteps.isNotEmpty()) return persistFailure(entry, ERROR_LOCAL_PRECONDITIONS_NOT_CONFIRMED)

        val completed = runCatchingExceptions { deletionJournal.complete(entry) }.getOrElse {
            return entry.toReport(retryRequired = true, errorCode = ERROR_AUDIT_WRITE_FAILED)
        }
        return completed.toReport(retryRequired = false)
    }

    private suspend fun executeScoped(pendingEntry: DeletionJournalEntry): DeletionReport {
        var entry = pendingEntry
        val scope = entry.request.scope
        val accountIdHash: String
        val vaultId: String
        val contentId: String
        when (scope) {
            is DeletionScope.Content -> {
                accountIdHash = scope.accountIdHash
                vaultId = scope.vaultId
                contentId = scope.contentId
            }
            is DeletionScope.Asset -> {
                accountIdHash = scope.accountIdHash
                vaultId = scope.vaultId
                contentId = scope.contentId
            }
            is DeletionScope.Account -> error("Account deletion must use the account execution path.")
        }
        if (accountIdHash.isBlank()) return persistFailure(entry, ERROR_SCOPED_IDENTIFIER_INVALID)
        if (!entry.hasCompleted(DeletionStep.MARKED_INACCESSIBLE, DeletionStep.DESTROYED_KEY_ACCESS, DeletionStep.REVOKED_REMOTE_ACCESS)) {
            val destroyed = runCatchingExceptions {
                contentProtectionService.destroyContentAccess(
                    AccountVaultId(vaultId),
                    ContentId(contentId),
                    entry.request.reason.toContentDestroyReason(),
                )
            }.getOrElse {
                return persistFailure(entry, ERROR_SCOPED_KEY_DESTRUCTION_FAILED)
            }
            if (!destroyed) return persistFailure(entry, ERROR_SCOPED_KEY_DESTRUCTION_FAILED)
            entry = persistProgress(
                entry,
                setOf(
                    DeletionStep.MARKED_INACCESSIBLE,
                    DeletionStep.DESTROYED_KEY_ACCESS,
                    DeletionStep.REVOKED_REMOTE_ACCESS,
                )
            ) ?: return entry.toReport(retryRequired = true, errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED)
        }
        val completed = runCatchingExceptions { deletionJournal.complete(entry) }.getOrElse {
            return entry.toReport(retryRequired = true, errorCode = ERROR_AUDIT_WRITE_FAILED)
        }
        return completed.toReport(retryRequired = false)
    }

    private fun io.element.android.security.dataprotection.api.DeletionReason.toContentDestroyReason(): ContentAccessDestroyReason =
        when (this) {
            io.element.android.security.dataprotection.api.DeletionReason.RETENTION_EXPIRED -> ContentAccessDestroyReason.RETENTION_EXPIRED
            io.element.android.security.dataprotection.api.DeletionReason.ACCOUNT_LOGOUT -> ContentAccessDestroyReason.ACCOUNT_LOGOUT
            io.element.android.security.dataprotection.api.DeletionReason.DEVICE_REVOKED -> ContentAccessDestroyReason.DEVICE_REVOKED
            io.element.android.security.dataprotection.api.DeletionReason.ADMINISTRATIVE_REVOCATION,
            io.element.android.security.dataprotection.api.DeletionReason.USER_REQUEST,
            io.element.android.security.dataprotection.api.DeletionReason.MIGRATION_CLEANUP ->
                ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION
        }

    private fun persistProgress(entry: DeletionJournalEntry, steps: Set<DeletionStep>): DeletionJournalEntry? {
        if (steps.isEmpty() || entry.completedSteps.containsAll(steps)) return entry
        return runCatchingExceptions {
            deletionJournal.update(entry.copy(completedSteps = entry.completedSteps + steps))
        }.getOrNull()
    }

    private fun confirmPreconditions(entry: DeletionJournalEntry, steps: Set<DeletionStep>): DeletionJournalEntry? {
        if (entry.completedSteps.containsAll(steps)) return entry
        val confirmedPreconditions = entry.request.confirmedPreconditions + steps
        return runCatchingExceptions {
            deletionJournal.update(
                entry.copy(
                    request = entry.request.copy(confirmedPreconditions = confirmedPreconditions),
                    completedSteps = entry.completedSteps + steps,
                )
            )
        }.getOrNull()
    }

    private fun persistFailure(entry: DeletionJournalEntry, errorCode: String): DeletionReport {
        val persisted = runCatchingExceptions { deletionJournal.update(entry.copy(lastErrorCode = errorCode)) }.getOrNull()
            ?: return entry.toReport(retryRequired = true, errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED)
        return persisted.toReport(retryRequired = true, errorCode = errorCode)
    }

    private fun DeletionJournalEntry.hasCompleted(vararg steps: DeletionStep): Boolean = completedSteps.containsAll(steps.toSet())

    private fun DeletionJournalEntry.toReport(
        retryRequired: Boolean,
        errorCode: String? = lastErrorCode,
    ): DeletionReport = DeletionReport(
        operationId = request.operationId,
        completedSteps = completedSteps,
        retryRequired = retryRequired,
        errorCode = errorCode,
    )

    private companion object {
        const val ERROR_SCOPED_IDENTIFIER_INVALID = "SCOPED_IDENTIFIER_INVALID"
        const val ERROR_SCOPED_KEY_DESTRUCTION_FAILED = "SCOPED_KEY_DESTRUCTION_FAILED"
        const val ERROR_VAULT_ERASURE_FAILED = "VAULT_ERASURE_FAILED"
        const val ERROR_VAULT_KEY_DESTRUCTION_FAILED = "VAULT_KEY_DESTRUCTION_FAILED"
        const val ERROR_VAULT_FILE_DELETION_FAILED = "VAULT_FILE_DELETION_FAILED"
        const val ERROR_SESSION_STATE_DELETION_FAILED = "SESSION_STATE_DELETION_FAILED"
        const val ERROR_SESSION_FILE_DELETION_FAILED = "SESSION_FILE_DELETION_FAILED"
        const val ERROR_LOCAL_RESIDUAL_STATE_DELETION_FAILED = "LOCAL_RESIDUAL_STATE_DELETION_FAILED"
        const val ERROR_DATABASE_CHECKPOINT_FAILED = "DATABASE_CHECKPOINT_FAILED"
        const val ERROR_NOTIFICATION_CANCELLATION_FAILED = "NOTIFICATION_CANCELLATION_FAILED"
        const val ERROR_LOCAL_PRECONDITIONS_NOT_CONFIRMED = "LOCAL_PRECONDITIONS_NOT_CONFIRMED"
        const val ERROR_JOURNAL_WRITE_FAILED = "DELETION_JOURNAL_WRITE_FAILED"
        const val ERROR_JOURNAL_PROGRESS_WRITE_FAILED = "DELETION_JOURNAL_PROGRESS_WRITE_FAILED"
        const val ERROR_AUDIT_WRITE_FAILED = "DELETION_AUDIT_WRITE_FAILED"
        val REQUIRED_LOCAL_COMPLETION_STEPS = setOf(
            DeletionStep.REQUESTED,
            DeletionStep.MARKED_INACCESSIBLE,
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.DESTROYED_KEY_ACCESS,
            DeletionStep.CLOSED_DATABASES,
            DeletionStep.SANITIZED_DATABASES,
            DeletionStep.DELETED_FILES,
            DeletionStep.CANCELLED_NOTIFICATIONS,
        )
        val COLD_START_PRECONDITIONS = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
        val REQUIRED_CALLER_PRECONDITIONS = COLD_START_PRECONDITIONS
    }
}
