/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.securestorage.api.SecureStorageRoots
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDeletionJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pending request and progress survive a new journal instance`() {
        val roots = testRoots()
        val journal = FileDeletionJournal(roots)
        val started = journal.begin(aRequest()).entry
        journal.update(started.copy(completedSteps = started.completedSteps + DeletionStep.MARKED_INACCESSIBLE))

        val recovered = FileDeletionJournal(roots).loadPending()

        assertThat(recovered.unreadableEntries).isEqualTo(0)
        assertThat(recovered.entries).hasSize(1)
        assertThat(recovered.entries.single().completedSteps).contains(DeletionStep.MARKED_INACCESSIBLE)
    }

    @Test
    fun `confirmed preconditions can be appended to a prepared request`() {
        val roots = testRoots()
        val journal = FileDeletionJournal(roots)
        val preparedRequest = aRequest().copy(confirmedPreconditions = emptySet())
        journal.begin(preparedRequest)

        val confirmed = journal.begin(aRequest()).entry

        assertThat(confirmed.request.confirmedPreconditions).containsExactlyElementsIn(aRequest().confirmedPreconditions)
        assertThat(confirmed.completedSteps).containsAtLeastElementsIn(aRequest().confirmedPreconditions)
    }

    @Test
    fun `corrupt latest revision is reported and previous valid revision is recoverable`() {
        val roots = testRoots()
        val journal = FileDeletionJournal(roots)
        val started = journal.begin(aRequest()).entry
        journal.update(started.copy(completedSteps = started.completedSteps + DeletionStep.MARKED_INACCESSIBLE))
        val latest = journalFiles(roots).filter { it.name.startsWith("pending-") }.maxBy { it.name }
        latest.writeBytes(byteArrayOf(1, 2, 3))

        val recovered = FileDeletionJournal(roots).loadPending()

        assertThat(recovered.unreadableEntries).isEqualTo(1)
        assertThat(recovered.entries).hasSize(1)
        assertThat(recovered.entries.single().completedSteps).doesNotContain(DeletionStep.MARKED_INACCESSIBLE)
    }

    @Test
    fun `completion publishes bounded non-content audit and removes pending records`() {
        val roots = testRoots()
        val journal = FileDeletionJournal(roots)
        val request = aRequest()
        val started = journal.begin(request).entry

        val completed = journal.complete(started)
        val duplicate = FileDeletionJournal(roots).begin(request)

        assertThat(completed.completedSteps).containsAtLeast(DeletionStep.WROTE_NON_CONTENT_AUDIT, DeletionStep.COMPLETE)
        assertThat(journalFiles(roots).none { it.name.startsWith("pending-") }).isTrue()
        assertThat(journalFiles(roots).count { it.name.startsWith("audit-") }).isEqualTo(1)
        assertThat(duplicate.alreadyComplete).isTrue()
    }

    @Test
    fun `journal durably records content scoped identifiers`() {
        val roots = testRoots()
        val journal = FileDeletionJournal(roots)
        val request = aRequest().copy(
            scope = DeletionScope.Content(
                accountIdHash = ACCOUNT_HASH,
                vaultId = "vault-id",
                contentId = "secret-event-id",
            )
        )

        journal.begin(request)
        val recovered = FileDeletionJournal(roots).loadPending()

        assertThat(recovered.entries.single().request).isEqualTo(request)
    }

    @Test
    fun `record checksum detects offline modification`() {
        val roots = testRoots()
        FileDeletionJournal(roots).begin(aRequest())
        val record = journalFiles(roots).single { it.name.startsWith("pending-") }
        val bytes = record.readBytes().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }
        record.writeBytes(bytes)

        val recovered = FileDeletionJournal(roots).loadPending()

        assertThat(recovered.entries).isEmpty()
        assertThat(recovered.unreadableEntries).isEqualTo(1)
    }

    private fun testRoots(): SecureStorageRoots {
        val persistentRoot = temporaryFolder.root.resolve("persistent")
        val runtimeRoot = temporaryFolder.root.resolve("runtime")
        return object : SecureStorageRoots {
            override fun persistentRoot(): File = persistentRoot
            override fun runtimeRoot(): File = runtimeRoot
        }
    }

    private fun journalFiles(roots: SecureStorageRoots): List<File> = roots.persistentRoot()
        .resolve("deletion-journal")
        .listFiles()
        .orEmpty()
        .toList()

    private fun aRequest() = DeletionRequest(
        operationId = DeletionOperationId("operation-1"),
        scope = DeletionScope.Account(
            accountIdHash = ACCOUNT_HASH,
            vaultId = "matrix-device-v1:opaque-vault",
            localSessionId = "@alice:example.org",
            sessionFilePath = "C:/app/sessions/random",
            sessionCachePath = "C:/app/cache/random",
        ),
        reason = DeletionReason.ACCOUNT_LOGOUT,
        policyVersion = 1,
        requestedAtEpochMillis = 1,
        confirmedPreconditions = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        ),
    )

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
