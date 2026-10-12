/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickCancellation
import io.element.android.libraries.mediapickers.api.MediaPickSource

enum class MediaPickJournalWriteResult {
    STORED,
    ACTIVE_OPERATION_EXISTS,
    FAILED,
}

/**
 * Persists only external-activity correlation metadata. LC-14 remains the lifecycle authority;
 * selected URIs, filenames, MIME types, and lifecycle status never enter this journal.
 * A native cancellation fact survives correlation removal until the existing lifecycle outbox accepts it.
 */
interface MediaPickOperationJournal {
    fun persist(operation: MediaPickOperation, nowEpochMillis: Long = System.currentTimeMillis()): MediaPickJournalWriteResult

    fun read(source: MediaPickSource): MediaPickOperation?

    fun clear(operationId: String): Boolean

    fun completeCancellation(operation: MediaPickOperation, completedAtEpochMillis: Long): MediaPickCancellation?

    fun pendingCancellations(): List<MediaPickCancellation>

    fun acknowledgeCancellation(cancellation: MediaPickCancellation): Boolean
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SharedPreferencesMediaPickOperationJournal internal constructor(
    @ApplicationContext context: Context,
) : MediaPickOperationJournal {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun persist(operation: MediaPickOperation, nowEpochMillis: Long): MediaPickJournalWriteResult {
        val existing = read(operation.source)
        if (existing != null && existing.operationId != operation.operationId && existing.deadlineEpochMillis > nowEpochMillis) {
            return MediaPickJournalWriteResult.ACTIVE_OPERATION_EXISTS
        }
        return if (preferences.edit().putString(operation.source.preferenceKey(), operation.encode()).commit()) {
            MediaPickJournalWriteResult.STORED
        } else {
            MediaPickJournalWriteResult.FAILED
        }
    }

    @Synchronized
    override fun read(source: MediaPickSource): MediaPickOperation? {
        val encoded = preferences.getString(source.preferenceKey(), null) ?: return null
        return decode(encoded)?.takeIf { it.source == source } ?: run {
            preferences.edit().remove(source.preferenceKey()).commit()
            null
        }
    }

    @Synchronized
    override fun clear(operationId: String): Boolean {
        val matchingSources = MediaPickSource.entries.filter { source ->
            read(source)?.operationId == operationId
        }
        if (matchingSources.isEmpty()) return true
        val editor = preferences.edit()
        matchingSources.forEach { source -> editor.remove(source.preferenceKey()) }
        return editor.commit()
    }

    @Synchronized
    override fun completeCancellation(operation: MediaPickOperation, completedAtEpochMillis: Long): MediaPickCancellation? {
        val key = cancellationKey(operation.operationId)
        preferences.getString(key, null)?.let { encoded ->
            return decodeCancellation(encoded)?.takeIf { it.operation == operation }
        }
        if (read(operation.source) != operation || completedAtEpochMillis < operation.createdAtEpochMillis) return null
        if (preferences.all.keys.count { it.startsWith(CANCELLATION_PREFIX) } >= MAX_PENDING_CANCELLATIONS) return null
        val cancellation = MediaPickCancellation(operation, completedAtEpochMillis)
        val committed = preferences.edit()
            .remove(operation.source.preferenceKey())
            .putString(key, operation.encode() + SEPARATOR + completedAtEpochMillis)
            .commit()
        return cancellation.takeIf { committed }
    }

    @Synchronized
    override fun pendingCancellations(): List<MediaPickCancellation> = preferences.all.entries
        .filter { it.key.startsWith(CANCELLATION_PREFIX) }
        .mapNotNull { (key, value) -> (value as? String)?.let(::decodeCancellation)?.takeIf { cancellationKey(it.operation.operationId) == key } }
        .sortedBy { it.completedAtEpochMillis }

    @Synchronized
    override fun acknowledgeCancellation(cancellation: MediaPickCancellation): Boolean {
        val key = cancellationKey(cancellation.operation.operationId)
        val encoded = preferences.getString(key, null) ?: return true
        if (decodeCancellation(encoded) != cancellation) return false
        return preferences.edit().remove(key).commit()
    }

    private fun cancellationKey(operationId: String): String = CANCELLATION_PREFIX + operationId

    private fun decodeCancellation(value: String): MediaPickCancellation? = runCatching {
        val parts = value.split(SEPARATOR)
        require(parts.size == FIELD_COUNT + 1)
        MediaPickCancellation(checkNotNull(decode(parts.take(FIELD_COUNT).joinToString(SEPARATOR))), parts.last().toLong())
    }.getOrNull()

    private fun MediaPickSource.preferenceKey(): String = "pending_${name.lowercase()}"

    private fun MediaPickOperation.encode(): String = listOf(
        operationId,
        source.name,
        roomIdHash,
        createdAtEpochMillis.toString(),
        deadlineEpochMillis.toString(),
    ).joinToString(SEPARATOR)

    private fun decode(value: String): MediaPickOperation? = runCatching {
        val parts = value.split(SEPARATOR)
        require(parts.size == FIELD_COUNT)
        MediaPickOperation(
            operationId = parts[0],
            source = MediaPickSource.valueOf(parts[1]),
            roomIdHash = parts[2],
            createdAtEpochMillis = parts[3].toLong(),
            deadlineEpochMillis = parts[4].toLong(),
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "media_pick_operation_bridge_v1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 5
        const val CANCELLATION_PREFIX = "cancelled_"
        const val MAX_PENDING_CANCELLATIONS = 32
    }
}
