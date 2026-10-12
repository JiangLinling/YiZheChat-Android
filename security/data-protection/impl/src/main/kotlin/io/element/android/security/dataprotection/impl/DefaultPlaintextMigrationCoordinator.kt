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
import io.element.android.security.dataprotection.api.PlaintextMigrationCoordinator
import io.element.android.security.dataprotection.api.PlaintextMigrationReport
import io.element.android.security.dataprotection.api.PlaintextMigrationStep
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable, fail-closed cutover from legacy plaintext scratch/cache paths to the encrypted media baseline. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultPlaintextMigrationCoordinator(
    private val cleaner: LegacyPlaintextCleaner,
    private val journal: PlaintextMigrationJournal,
    private val sensitiveFileStore: SensitiveFileStore,
) : PlaintextMigrationCoordinator {
    private val migrationMutex = Mutex()

    override suspend fun migrate(): PlaintextMigrationReport = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            val loaded = runCatchingExceptions(journal::load).getOrElse {
                return@withLock failedReport(errorCode = ERROR_JOURNAL_READ_FAILED)
            }
            if (loaded.complete) {
                val completedState = requireNotNull(loaded.state)
                LegacyPlaintextTarget.entries.forEach { target ->
                    val cleanup = runCatchingExceptions { cleaner.clean(target) }.getOrNull()
                    if (cleanup?.completed != true) {
                        return@withLock completedState.toReport(
                            retryRequired = true,
                            errorCode = ERROR_PLAINTEXT_CLEANUP_FAILED,
                            unreadableJournalEntries = loaded.unreadableEntries,
                        )
                    }
                }
                return@withLock completedState.toReport(
                    retryRequired = false,
                    unreadableJournalEntries = loaded.unreadableEntries,
                )
            }

            var state = runCatchingExceptions { loaded.state ?: journal.start(MIGRATION_VERSION) }.getOrElse {
                return@withLock failedReport(
                    errorCode = ERROR_JOURNAL_WRITE_FAILED,
                    unreadableJournalEntries = loaded.unreadableEntries,
                )
            }
            state = persist(
                state.copy(
                    completedSteps = state.completedSteps + PlaintextMigrationStep.SECURE_MEDIA_CUTOVER,
                    attemptCount = state.attemptCount + 1,
                    lastErrorCode = null,
                )
            ) ?: return@withLock state.toReport(
                retryRequired = true,
                errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
                unreadableJournalEntries = loaded.unreadableEntries,
            )

            for (target in LegacyPlaintextTarget.entries) {
                if (target.completedStep in state.completedSteps) continue
                val cleanup = runCatchingExceptions { cleaner.clean(target) }.getOrElse {
                    return@withLock fail(state, ERROR_PLAINTEXT_CLEANUP_FAILED, loaded.unreadableEntries)
                }
                if (!cleanup.completed) {
                    return@withLock fail(state, ERROR_PLAINTEXT_CLEANUP_FAILED, loaded.unreadableEntries)
                }
                state = persist(state.copy(completedSteps = state.completedSteps + target.completedStep))
                    ?: return@withLock state.toReport(
                        retryRequired = true,
                        errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
                        unreadableJournalEntries = loaded.unreadableEntries,
                    )
            }

            if (PlaintextMigrationStep.MAINTAINED_SECURE_STORE !in state.completedSteps) {
                val maintenance = runCatchingExceptions { sensitiveFileStore.runMaintenance() }.getOrElse {
                    return@withLock fail(state, ERROR_SECURE_STORE_MAINTENANCE_FAILED, loaded.unreadableEntries)
                }
                if (maintenance.partialFilesDeleted < 0 || maintenance.orphanDataFilesDeleted < 0 || maintenance.orphanKeyEnvelopesDeleted < 0) {
                    return@withLock fail(state, ERROR_SECURE_STORE_MAINTENANCE_FAILED, loaded.unreadableEntries)
                }
                state = persist(state.copy(completedSteps = state.completedSteps + PlaintextMigrationStep.MAINTAINED_SECURE_STORE))
                    ?: return@withLock state.toReport(
                        retryRequired = true,
                        errorCode = ERROR_JOURNAL_PROGRESS_WRITE_FAILED,
                        unreadableJournalEntries = loaded.unreadableEntries,
                    )
            }

            val missing = REQUIRED_STEPS - state.completedSteps
            if (missing.isNotEmpty()) return@withLock fail(state, ERROR_REQUIRED_STEP_MISSING, loaded.unreadableEntries)
            val completed = runCatchingExceptions { journal.complete(state) }.getOrElse {
                return@withLock state.toReport(
                    retryRequired = true,
                    errorCode = ERROR_COMPLETION_WRITE_FAILED,
                    unreadableJournalEntries = loaded.unreadableEntries,
                )
            }
            completed.toReport(retryRequired = false, unreadableJournalEntries = loaded.unreadableEntries)
        }
    }

    private fun persist(state: PlaintextMigrationState): PlaintextMigrationState? = runCatchingExceptions { journal.update(state) }.getOrNull()

    private fun fail(state: PlaintextMigrationState, errorCode: String, unreadableEntries: Int): PlaintextMigrationReport {
        val persisted = persist(state.copy(lastErrorCode = errorCode))
            ?: return state.toReport(true, ERROR_JOURNAL_PROGRESS_WRITE_FAILED, unreadableEntries)
        return persisted.toReport(true, errorCode, unreadableEntries)
    }

    private fun failedReport(
        errorCode: String,
        unreadableJournalEntries: Int = 0,
    ): PlaintextMigrationReport = PlaintextMigrationReport(
        migrationVersion = MIGRATION_VERSION,
        completedSteps = emptySet(),
        attemptCount = 0,
        unreadableJournalEntries = unreadableJournalEntries,
        retryRequired = true,
        errorCode = errorCode,
    )

    private fun PlaintextMigrationState.toReport(
        retryRequired: Boolean,
        errorCode: String? = lastErrorCode,
        unreadableJournalEntries: Int,
    ): PlaintextMigrationReport = PlaintextMigrationReport(
        migrationVersion = migrationVersion,
        completedSteps = completedSteps,
        attemptCount = attemptCount,
        unreadableJournalEntries = unreadableJournalEntries,
        retryRequired = retryRequired,
        errorCode = errorCode,
    )

    private companion object {
        const val MIGRATION_VERSION = 1
        const val ERROR_JOURNAL_READ_FAILED = "PLAINTEXT_MIGRATION_JOURNAL_READ_FAILED"
        const val ERROR_JOURNAL_WRITE_FAILED = "PLAINTEXT_MIGRATION_JOURNAL_WRITE_FAILED"
        const val ERROR_JOURNAL_PROGRESS_WRITE_FAILED = "PLAINTEXT_MIGRATION_PROGRESS_WRITE_FAILED"
        const val ERROR_PLAINTEXT_CLEANUP_FAILED = "PLAINTEXT_CLEANUP_FAILED"
        const val ERROR_SECURE_STORE_MAINTENANCE_FAILED = "SECURE_STORE_MAINTENANCE_FAILED"
        const val ERROR_REQUIRED_STEP_MISSING = "PLAINTEXT_MIGRATION_REQUIRED_STEP_MISSING"
        const val ERROR_COMPLETION_WRITE_FAILED = "PLAINTEXT_MIGRATION_COMPLETION_WRITE_FAILED"
        val REQUIRED_STEPS = buildSet {
            add(PlaintextMigrationStep.SECURE_MEDIA_CUTOVER)
            add(PlaintextMigrationStep.MAINTAINED_SECURE_STORE)
            LegacyPlaintextTarget.entries.forEach { add(it.completedStep) }
        }
    }
}
