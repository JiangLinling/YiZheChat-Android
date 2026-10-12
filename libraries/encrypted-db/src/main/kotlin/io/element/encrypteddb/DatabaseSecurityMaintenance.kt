/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.encrypteddb

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

data class DatabaseMaintenanceReport(
    val openDatabases: Int,
    val checkpointedDatabases: Int,
    val failedDatabases: Int,
) {
    val completed: Boolean = failedDatabases == 0
}

interface DatabaseSecurityMaintenance {
    fun checkpointAndTruncate(): DatabaseMaintenanceReport
}

/**
 * Applies the common SQLCipher hardening policy and keeps only weak references to open databases.
 *
 * The registry intentionally stores no SQL, row values, account identifiers or passphrases. It exists so account
 * deletion can force every open application database to checkpoint and truncate its WAL after sensitive rows have
 * been removed.
 */
internal object OpenDatabaseSecurityRegistry {
    private val openDatabases = ConcurrentHashMap<String, WeakReference<SupportSQLiteDatabase>>()

    fun registerAndHarden(name: String, database: SupportSQLiteDatabase) {
        applyPragma(database, "PRAGMA secure_delete = ON")
        applyPragma(database, "PRAGMA cipher_memory_security = ON")
        applyPragma(database, "PRAGMA temp_store = MEMORY")
        applyPragma(database, "PRAGMA wal_autocheckpoint = 100")
        openDatabases[name] = WeakReference(database)
    }

    /**
     * SQLCipher reports assignment-style PRAGMAs through the query path. Using [SupportSQLiteDatabase.execSQL]
     * routes them through executeForChangedRowCount and crashes on SQLCipher 4.9 because PRAGMAs may return a row.
     * Advancing the cursor also guarantees that lazily evaluated drivers actually apply the setting.
     */
    private fun applyPragma(database: SupportSQLiteDatabase, statement: String) {
        database.query(statement).use { cursor ->
            cursor.moveToFirst()
        }
    }

    internal fun clearForTests() {
        openDatabases.clear()
    }

    fun checkpointAndTruncate(): DatabaseMaintenanceReport {
        var openCount = 0
        var checkpointedCount = 0
        var failedCount = 0
        openDatabases.entries.removeIf { (_, reference) -> reference.get() == null }
        openDatabases.values.forEach { reference ->
            val database = reference.get() ?: return@forEach
            openCount += 1
            try {
                database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "Database WAL checkpoint could not acquire the required lock."
                    }
                }
                database.query("PRAGMA optimize").close()
                checkpointedCount += 1
            } catch (_: Exception) {
                failedCount += 1
            }
        }
        return DatabaseMaintenanceReport(
            openDatabases = openCount,
            checkpointedDatabases = checkpointedCount,
            failedDatabases = failedCount,
        )
    }
}

@ContributesBinding(AppScope::class)
class DefaultDatabaseSecurityMaintenance : DatabaseSecurityMaintenance {
    override fun checkpointAndTruncate(): DatabaseMaintenanceReport = OpenDatabaseSecurityRegistry.checkpointAndTruncate()
}
