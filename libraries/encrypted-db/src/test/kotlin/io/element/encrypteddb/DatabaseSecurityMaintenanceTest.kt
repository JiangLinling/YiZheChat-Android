/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.encrypteddb

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class DatabaseSecurityMaintenanceTest {
    @Test
    fun `register hardens database and maintenance truncates WAL`() {
        OpenDatabaseSecurityRegistry.clearForTests()
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)
        val checkpointCursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getInt(0) } returns 0
        }
        every { database.query("PRAGMA wal_checkpoint(TRUNCATE)") } returns checkpointCursor
        every { database.query("PRAGMA optimize") } returns mockk(relaxed = true)
        every { database.query(any<SupportSQLiteQuery>()) } returns mockk(relaxed = true)

        OpenDatabaseSecurityRegistry.registerAndHarden("test-${System.nanoTime()}", database)
        val report = DefaultDatabaseSecurityMaintenance().checkpointAndTruncate()

        verify { database.query("PRAGMA secure_delete = ON") }
        verify { database.query("PRAGMA cipher_memory_security = ON") }
        verify { database.query("PRAGMA temp_store = MEMORY") }
        verify { database.query("PRAGMA wal_autocheckpoint = 100") }
        verify(exactly = 0) { database.execSQL(any<String>()) }
        verify { database.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        assertThat(report.completed).isTrue()
        assertThat(report.checkpointedDatabases).isAtLeast(1)
    }

    @Test
    fun `busy WAL checkpoint is reported as a maintenance failure`() {
        OpenDatabaseSecurityRegistry.clearForTests()
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)
        val busyCursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getInt(0) } returns 1
        }
        every { database.query("PRAGMA wal_checkpoint(TRUNCATE)") } returns busyCursor

        OpenDatabaseSecurityRegistry.registerAndHarden("busy-${System.nanoTime()}", database)
        val report = DefaultDatabaseSecurityMaintenance().checkpointAndTruncate()

        assertThat(report.completed).isFalse()
        assertThat(report.failedDatabases).isAtLeast(1)
    }
}
