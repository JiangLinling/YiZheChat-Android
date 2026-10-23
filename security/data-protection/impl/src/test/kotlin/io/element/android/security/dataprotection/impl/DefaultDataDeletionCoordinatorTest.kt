/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.test.FakeContentProtectionService
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.encrypteddb.DatabaseMaintenanceReport
import io.element.encrypteddb.DatabaseSecurityMaintenance
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultDataDeletionCoordinatorTest {
    @Test
    fun `prepared deletion performs no destructive work and cold start resumes it`() = runTest {
        val journal = InMemoryDeletionJournal()
        val notifications = FakeDeletionNotificationCleaner()
        val sessions = FakeDeletionSessionCleaner()
        val coordinator = aCoordinator(journal = journal, notifications = notifications, sessions = sessions)
        val request = aRequest(confirmedPreconditions = emptySet())

        val prepared = coordinator.prepare(request)
        val recovered = coordinator.resumePending(coldStart = true)

        assertThat(prepared.completedSteps).containsExactly(DeletionStep.REQUESTED)
        assertThat(prepared.errorCode).isNull()
        assertThat(prepared.accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(recovered.operationReports.single().isComplete).isTrue()
        assertThat(recovered.operationReports.single().accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(notifications.calls).isEqualTo(1)
        assertThat(notifications.scopes.single().localSessionId).isEqualTo("@alice:example.org")
        assertThat(sessions.calls).isEqualTo(1)
    }

    @Test
    fun `account deletion durably completes every local step`() = runTest {
        val journal = InMemoryDeletionJournal()
        val coordinator = aCoordinator(journal = journal)

        val report = coordinator.delete(aRequest())

        assertThat(report.isComplete).isTrue()
        assertThat(report.completedSteps).containsAtLeastElementsIn(
            setOf(
                DeletionStep.REQUESTED,
                DeletionStep.STOPPED_SYNC_AND_UI,
                DeletionStep.PURGED_MEMORY,
                DeletionStep.DESTROYED_KEY_ACCESS,
                DeletionStep.CLOSED_DATABASES,
                DeletionStep.SANITIZED_DATABASES,
                DeletionStep.DELETED_FILES,
                DeletionStep.CANCELLED_NOTIFICATIONS,
                DeletionStep.WROTE_NON_CONTENT_AUDIT,
                DeletionStep.COMPLETE,
            )
        )
        assertThat(journal.completed).isTrue()
    }

    @Test
    fun `database checkpoint failure remains pending and resumes after restart`() = runTest {
        val database = FakeDatabaseSecurityMaintenance(completed = false)
        val journal = InMemoryDeletionJournal()
        val firstCoordinator = aCoordinator(journal = journal, database = database)

        val failed = firstCoordinator.delete(aRequest())
        database.completed = true
        val recovery = aCoordinator(journal = journal, database = database).resumePending()

        assertThat(failed.errorCode).isEqualTo("DATABASE_CHECKPOINT_FAILED")
        assertThat(failed.retryRequired).isTrue()
        assertThat(failed.accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(recovery.operationReports).hasSize(1)
        assertThat(recovery.operationReports.single().isComplete).isTrue()
        assertThat(recovery.operationReports.single().accountIdHash).isEqualTo(ACCOUNT_HASH)
    }

    @Test
    fun `notification cancellation failure is retried`() = runTest {
        val notifications = FakeDeletionNotificationCleaner(completed = false)
        val journal = InMemoryDeletionJournal()
        val coordinator = aCoordinator(journal = journal, notifications = notifications)

        val failed = coordinator.delete(aRequest())
        notifications.completed = true
        val recovered = coordinator.resumePending()

        assertThat(failed.errorCode).isEqualTo("NOTIFICATION_CANCELLATION_FAILED")
        assertThat(recovered.operationReports.single().isComplete).isTrue()
        assertThat(notifications.calls).isEqualTo(2)
    }

    @Test
    fun `residual local state failure remains pending before vault destruction`() = runTest {
        val residualState = FakeAccountLocalStateCleaner(completed = false)
        val coordinator = aCoordinator(residualState = residualState)

        val report = coordinator.delete(aRequest())

        assertThat(report.isComplete).isFalse()
        assertThat(report.errorCode).isEqualTo("LOCAL_RESIDUAL_STATE_DELETION_FAILED")
        assertThat(residualState.calls).isEqualTo(1)
    }

    @Test
    fun `missing live session preconditions fail closed`() = runTest {
        val notifications = FakeDeletionNotificationCleaner()
        val sessions = FakeDeletionSessionCleaner()
        val coordinator = aCoordinator(notifications = notifications, sessions = sessions)

        val report = coordinator.delete(aRequest(confirmedPreconditions = emptySet()))

        assertThat(report.isComplete).isFalse()
        assertThat(report.errorCode).isEqualTo("LOCAL_PRECONDITIONS_NOT_CONFIRMED")
        assertThat(notifications.calls).isEqualTo(0)
        assertThat(sessions.calls).isEqualTo(0)
    }

    @Test
    fun `completed operation id is idempotent`() = runTest {
        val journal = InMemoryDeletionJournal()
        val notifications = FakeDeletionNotificationCleaner()
        val coordinator = aCoordinator(journal = journal, notifications = notifications)
        val request = aRequest()

        val first = coordinator.delete(request)
        val second = coordinator.delete(request)

        assertThat(first.isComplete).isTrue()
        assertThat(second.isComplete).isTrue()
        assertThat(notifications.calls).isEqualTo(1)
    }

    @Test
    fun `content deletion durably destroys local and remote key access`() = runTest {
        val journal = InMemoryDeletionJournal()
        val coordinator = aCoordinator(journal = journal)

        val report = coordinator.delete(
            aRequest(
                scope = DeletionScope.Content(
                    accountIdHash = ACCOUNT_HASH,
                    vaultId = "account-vault",
                    contentId = "event",
                )
            )
        )

        assertThat(report.isComplete).isTrue()
        assertThat(report.completedSteps).containsAtLeast(
            DeletionStep.MARKED_INACCESSIBLE,
            DeletionStep.DESTROYED_KEY_ACCESS,
            DeletionStep.REVOKED_REMOTE_ACCESS,
        )
        assertThat(journal.beginCalls).isEqualTo(1)
    }

    private fun aCoordinator(
        journal: InMemoryDeletionJournal = InMemoryDeletionJournal(),
        database: FakeDatabaseSecurityMaintenance = FakeDatabaseSecurityMaintenance(completed = true),
        notifications: FakeDeletionNotificationCleaner = FakeDeletionNotificationCleaner(),
        sessions: FakeDeletionSessionCleaner = FakeDeletionSessionCleaner(),
        residualState: FakeAccountLocalStateCleaner = FakeAccountLocalStateCleaner(),
    ) = DefaultDataDeletionCoordinator(
        sensitiveFileStore = InMemorySensitiveFileStore(),
        databaseSecurityMaintenance = database,
        deletionJournal = journal,
        deletionNotificationCleaner = notifications,
        deletionSessionCleaner = sessions,
        accountLocalStateCleaner = residualState,
        contentProtectionService = FakeContentProtectionService(),
    )

    private fun aRequest(
        scope: DeletionScope = DeletionScope.Account(
            accountIdHash = ACCOUNT_HASH,
            vaultId = "account-vault",
            localSessionId = "@alice:example.org",
            sessionFilePath = "C:/app/sessions/random",
            sessionCachePath = "C:/app/cache/random",
        ),
        confirmedPreconditions: Set<DeletionStep> = CONFIRMED_PRECONDITIONS,
    ) = DeletionRequest(
        operationId = DeletionOperationId("operation"),
        scope = scope,
        reason = DeletionReason.ACCOUNT_LOGOUT,
        policyVersion = 1,
        requestedAtEpochMillis = 1,
        confirmedPreconditions = confirmedPreconditions,
    )

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val CONFIRMED_PRECONDITIONS = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
    }
}

private class FakeDatabaseSecurityMaintenance(
    var completed: Boolean,
) : DatabaseSecurityMaintenance {
    override fun checkpointAndTruncate(): DatabaseMaintenanceReport = DatabaseMaintenanceReport(
        openDatabases = 1,
        checkpointedDatabases = if (completed) 1 else 0,
        failedDatabases = if (completed) 0 else 1,
    )
}

private class FakeDeletionNotificationCleaner(
    var completed: Boolean = true,
) : DeletionNotificationCleaner {
    var calls = 0
    val scopes = mutableListOf<DeletionScope.Account>()

    override fun cancel(scope: DeletionScope.Account): Boolean {
        calls += 1
        scopes += scope
        return completed
    }
}

private class FakeDeletionSessionCleaner : DeletionSessionCleaner {
    var calls = 0

    override suspend fun delete(scope: DeletionScope.Account): LocalSessionDeletionReport {
        calls += 1
        return LocalSessionDeletionReport(
            sessionRecordRemoved = true,
            sessionFilesDeleted = true,
        )
    }
}

private class FakeAccountLocalStateCleaner(
    var completed: Boolean = true,
) : AccountLocalStateCleaner {
    var calls = 0

    override fun clear(scope: DeletionScope.Account, preserveAccountState: Boolean): Boolean {
        calls += 1
        return completed
    }
}

private class InMemoryDeletionJournal : DeletionJournal {
    var entry: DeletionJournalEntry? = null
    var completed = false
    var beginCalls = 0

    override fun begin(request: DeletionRequest): DeletionJournalBeginResult {
        beginCalls += 1
        val current = entry ?: DeletionJournalEntry(
            request = request,
            completedSteps = setOf(DeletionStep.REQUESTED) + request.confirmedPreconditions,
            attemptCount = 0,
            lastErrorCode = null,
            revision = 1,
        ).also { entry = it }
        check(current.request == request)
        return DeletionJournalBeginResult(current, alreadyComplete = completed)
    }

    override fun update(entry: DeletionJournalEntry): DeletionJournalEntry = entry.copy(
        revision = (this.entry?.revision ?: 0) + 1,
    ).also { this.entry = it }

    override fun complete(entry: DeletionJournalEntry): DeletionJournalEntry = entry.copy(
        completedSteps = entry.completedSteps + DeletionStep.WROTE_NON_CONTENT_AUDIT + DeletionStep.COMPLETE,
        lastErrorCode = null,
        revision = entry.revision + 1,
    ).also {
        this.entry = it
        completed = true
    }

    override fun loadPending(): PendingDeletionEntries = PendingDeletionEntries(
        entries = if (completed) emptyList() else listOfNotNull(entry),
        unreadableEntries = 0,
    )
}
