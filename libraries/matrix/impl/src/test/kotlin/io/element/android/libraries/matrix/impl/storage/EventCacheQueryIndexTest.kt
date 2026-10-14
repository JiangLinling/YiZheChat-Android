/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.storage

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EventCacheQueryIndexTest {
    @get:Rule val folder = TemporaryFolder()
    private fun database() = File(folder.root, "matrix-sdk-event-cache.sqlite3")
    private fun create() = SQLiteDatabase.openOrCreateDatabase(database(), null).apply {
        execSQL("CREATE TABLE events(room_id BLOB NOT NULL,event_id BLOB PRIMARY KEY,event_type BLOB,session_id BLOB,content BLOB) WITHOUT ROWID")
        execSQL("CREATE INDEX event_type_index ON events(room_id,event_type,session_id)")
        execSQL("PRAGMA user_version=14")
    }

    @Test fun `index selects exact session without changing ciphertext or SDK schema version`() {
        create().use { db ->
            repeat(100) { i ->
                db.execSQL("INSERT INTO events VALUES(?,?,?,?,?)", arrayOf(byteArrayOf(1), byteArrayOf(i.toByte()),
                    byteArrayOf(2), byteArrayOf(i.toByte()), byteArrayOf(7, 8, 9)))
            }
        }
        assertThat(EventCacheQueryIndex.ensure(folder.root)).isTrue()
        SQLiteDatabase.openDatabase(database().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("EXPLAIN QUERY PLAN SELECT content FROM events WHERE room_id=x'01' AND session_id=x'05'", null).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(3)).contains(EventCacheQueryIndex.NAME)
                assertThat(c.getString(3)).contains("session_id=?")
            }
            db.rawQuery("SELECT content FROM events WHERE room_id=x'01' AND session_id=x'05'", null).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getBlob(0)).isEqualTo(byteArrayOf(7, 8, 9))
                assertThat(c.moveToNext()).isFalse()
            }
            assertThat(db.version).isEqualTo(14)
        }
    }

    @Test fun `reopening reuses index and preserves inserted updated and removed records`() {
        create().close()
        assertThat(EventCacheQueryIndex.ensure(folder.root)).isTrue()
        SQLiteDatabase.openDatabase(database().absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO events VALUES(x'01',x'02',x'03',x'04',x'05')")
            db.execSQL("UPDATE events SET session_id=x'06' WHERE event_id=x'02'")
            db.execSQL("DELETE FROM events WHERE session_id=x'06'")
        }
        assertThat(EventCacheQueryIndex.ensure(folder.root)).isTrue()
        SQLiteDatabase.openDatabase(database().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM events", null).use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
        }
    }

    @Test fun `missing SDK cache is not created`() {
        assertThat(EventCacheQueryIndex.ensure(folder.root)).isFalse()
        assertThat(database().exists()).isFalse()
    }

    @Test fun `unknown future schema is not modified`() {
        SQLiteDatabase.openOrCreateDatabase(database(), null).use { db -> db.execSQL("CREATE TABLE events(content TEXT)") }
        assertThat(EventCacheQueryIndex.ensure(folder.root)).isFalse()
        SQLiteDatabase.openDatabase(database().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index'", null).use { c ->
                c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0)
            }
        }
    }
}
