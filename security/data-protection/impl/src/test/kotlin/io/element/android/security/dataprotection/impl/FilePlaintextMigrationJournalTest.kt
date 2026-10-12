/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.PlaintextMigrationStep
import io.element.android.security.securestorage.api.SecureStorageRoots
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilePlaintextMigrationJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `progress survives a new journal instance`() {
        val roots = roots()
        val journal = FilePlaintextMigrationJournal(roots)
        val started = journal.start(1)
        journal.update(started.copy(completedSteps = setOf(PlaintextMigrationStep.SECURE_MEDIA_CUTOVER)))

        val loaded = FilePlaintextMigrationJournal(roots).load()

        assertThat(loaded.complete).isFalse()
        assertThat(loaded.unreadableEntries).isEqualTo(0)
        assertThat(loaded.state?.completedSteps).contains(PlaintextMigrationStep.SECURE_MEDIA_CUTOVER)
    }

    @Test
    fun `corrupt latest revision is reported and previous valid revision is recovered`() {
        val roots = roots()
        val journal = FilePlaintextMigrationJournal(roots)
        val started = journal.start(1)
        journal.update(started.copy(completedSteps = setOf(PlaintextMigrationStep.SECURE_MEDIA_CUTOVER)))
        journalFiles(roots).filter { it.name.startsWith("state-") }.maxBy { it.name }.writeBytes(byteArrayOf(1, 2, 3))

        val loaded = FilePlaintextMigrationJournal(roots).load()

        assertThat(loaded.unreadableEntries).isEqualTo(1)
        assertThat(loaded.state?.completedSteps).doesNotContain(PlaintextMigrationStep.SECURE_MEDIA_CUTOVER)
    }

    @Test
    fun `completion is durable and removes progress revisions`() {
        val roots = roots()
        val journal = FilePlaintextMigrationJournal(roots)
        val started = journal.start(1)

        journal.complete(started)
        val loaded = FilePlaintextMigrationJournal(roots).load()

        assertThat(loaded.complete).isTrue()
        assertThat(loaded.state?.completedSteps).contains(PlaintextMigrationStep.COMPLETE)
        assertThat(journalFiles(roots).none { it.name.startsWith("state-") }).isTrue()
    }

    @Test
    fun `offline modification of completion marker is never accepted`() {
        val roots = roots()
        val journal = FilePlaintextMigrationJournal(roots)
        journal.complete(journal.start(1))
        val completed = journalFiles(roots).single { it.name == "complete.yzpm" }
        val bytes = completed.readBytes().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }
        completed.writeBytes(bytes)

        val loaded = FilePlaintextMigrationJournal(roots).load()

        assertThat(loaded.complete).isFalse()
        assertThat(loaded.unreadableEntries).isEqualTo(1)
    }

    private fun roots(): SecureStorageRoots = object : SecureStorageRoots {
        override fun persistentRoot(): File = temporaryFolder.root.resolve("secure")
        override fun runtimeRoot(): File = temporaryFolder.root.resolve("runtime")
    }

    private fun journalFiles(roots: SecureStorageRoots): List<File> = roots.persistentRoot()
        .resolve("plaintext-migration")
        .listFiles()
        .orEmpty()
        .toList()
}
