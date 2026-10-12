/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.element.android.libraries.mediapickers.api.RecentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun readRecentImages(context: Context, now: Long): List<RecentImage> = withContext(Dispatchers.IO) {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val authorized = when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (!authorized) return@withContext emptyList()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val selection = buildString {
        append("date_added >= ? AND date_added <= ? AND _size > 0")
        if (Build.VERSION.SDK_INT >= 29) append(" AND is_pending = 0")
        if (Build.VERSION.SDK_INT >= 30) append(" AND is_trashed = 0")
    }
    try {
        buildList {
            context.contentResolver.query(
                collection, arrayOf("_id", "date_added"), selection,
                arrayOf(((now - 120_000L) / 1000L).toString(), (now / 1000L).toString()), "date_added DESC, _id DESC",
            )?.use { cursor ->
                while (size < 10 && cursor.moveToNext()) {
                    val added = cursor.getLong(1) * 1000L
                    if (now - added in 0..120_000L) add(RecentImage(ContentUris.withAppendedId(collection, cursor.getLong(0)), added))
                }
            }
        }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

/** Keyset pagination keeps older images reachable without loading the whole library into the chat. */
internal suspend fun readGalleryImages(context: Context, now: Long, before: RecentImage? = null, pageSize: Int = 40): List<RecentImage> =
    withContext(Dispatchers.IO) {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val authorized = when {
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (!authorized) return@withContext emptyList()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val beforeId = before?.uri?.lastPathSegment?.toLongOrNull()
        if (before != null && beforeId == null) return@withContext emptyList()
        val args = mutableListOf((now / 1000L).toString())
        val selection = buildString {
            append("date_added <= ? AND _size > 0")
            if (Build.VERSION.SDK_INT >= 29) append(" AND is_pending = 0")
            if (Build.VERSION.SDK_INT >= 30) append(" AND is_trashed = 0")
            if (before != null) {
                append(" AND (date_added < ? OR (date_added = ? AND _id < ?))")
                args += (before.addedAtEpochMillis / 1000L).toString()
                args += (before.addedAtEpochMillis / 1000L).toString()
                args += beforeId.toString()
            }
        }
        try {
            buildList {
                context.contentResolver.query(collection, arrayOf("_id", "date_added"), selection,
                    args.toTypedArray(), "date_added DESC, _id DESC")?.use { cursor ->
                    while (size < pageSize.coerceIn(1, 40) && cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val added = cursor.getLong(1) * 1000L
                        val precedesCursor = before == null || added < before.addedAtEpochMillis ||
                            (added == before.addedAtEpochMillis && id < requireNotNull(beforeId))
                        if (added <= now && precedesCursor) add(RecentImage(ContentUris.withAppendedId(collection, id), added))
                    }
                }
            }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
