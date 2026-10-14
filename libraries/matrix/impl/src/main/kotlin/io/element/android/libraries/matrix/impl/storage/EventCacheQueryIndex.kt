/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.storage

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import timber.log.Timber

/** An additive access index over existing encrypted SDK lookup keys, never a content or authority cache. */
internal object EventCacheQueryIndex {
    const val NAME = "yizhe_event_room_session_idx"

    fun ensure(cacheDirectory: File): Boolean {
        val database = File(cacheDirectory, "matrix-sdk-event-cache.sqlite3")
        // Never create an empty SDK database or follow an escaped cache path.
        return try {
            if (!database.isFile || database.canonicalFile.parentFile != cacheDirectory.canonicalFile) return false
            SQLiteDatabase.openDatabase(database.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            ).use { db ->
                val columns = db.rawQuery("PRAGMA table_info(events)", null).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2).uppercase())
                    }
                }
                if (listOf("room_id", "event_id", "event_type", "session_id", "content").any { columns[it] != "BLOB" }) {
                    return false
                }
                // The SDK's (room_id,event_type,session_id) index cannot directly serve
                // room/session security refreshes which intentionally omit event_type.
                db.execSQL("CREATE INDEX IF NOT EXISTS $NAME ON events(room_id,session_id)")
                true
            }
        } catch (_: SQLiteException) {
            // An optional performance index must not bypass SDK open/recovery or authorize data.
            Timber.tag("YiZheEventCache").w("Room/session query index unavailable; SDK validation remains required")
            false
        } catch (_: IOException) {
            false
        }
    }
}
