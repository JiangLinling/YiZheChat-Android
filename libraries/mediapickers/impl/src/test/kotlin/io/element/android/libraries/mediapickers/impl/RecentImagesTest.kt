/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentImagesTest {
    private var granted: String? = null
    private var queries = 0
    private var selection: String? = null
    private var deniedDuringQuery = false
    private var cursor = MatrixCursor(arrayOf("_id", "date_added"))
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private fun permission(granted: String? = null) {
        this.granted = granted
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
                queries++
                this@RecentImagesTest.selection = selection
                if (deniedDuringQuery) throw SecurityException()
                return cursor
            }
            override fun getType(uri: Uri) = "image/png"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
    }

    @Test fun `no photo access performs no query`() = runTest {
        permission()
        assertThat(readRecentImages(context, 300_000L)).isEmpty()
        assertThat(queries).isEqualTo(0)
    }

    @Test fun `partial photo access stays inside authorized query and rejects old or future dates`() = runTest {
        permission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        cursor = MatrixCursor(arrayOf("_id", "date_added")).apply {
            addRow(arrayOf(9, 301)); addRow(arrayOf(8, 300)); addRow(arrayOf(7, 180)); addRow(arrayOf(6, 179))
        }
        val result = readRecentImages(context, 300_000L)
        assertThat(result.map { it.uri.lastPathSegment }).containsExactly("8", "7").inOrder()
        assertThat(selection).contains("is_pending = 0")
        assertThat(selection).contains("is_trashed = 0")
        assertThat(cursor.isClosed).isTrue()
    }

    @Test fun `permission revoked during query degrades to normal attachments`() = runTest {
        permission(Manifest.permission.READ_MEDIA_IMAGES)
        deniedDuringQuery = true
        assertThat(readRecentImages(context, 300_000L)).isEmpty()
    }

    @Test fun `candidate count is bounded without loading the entire library`() = runTest {
        permission(Manifest.permission.READ_MEDIA_IMAGES)
        cursor = MatrixCursor(arrayOf("_id", "date_added")).apply { (1..100).forEach { addRow(arrayOf(it, 300)) } }
        assertThat(readRecentImages(context, 300_000L)).hasSize(10)
        assertThat(cursor.isClosed).isTrue()
    }

    @Test fun `gallery browsing without permission never queries photos`() = runTest {
        permission()
        assertThat(readGalleryImages(context, 300_000L)).isEmpty()
        assertThat(queries).isEqualTo(0)
    }

    @Test fun `gallery includes older authorized photos and paginates equal dates by id`() = runTest {
        permission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        fun rows() = MatrixCursor(arrayOf("_id", "date_added")).apply {
            addRow(arrayOf(9, 301)); addRow(arrayOf(8, 300)); addRow(arrayOf(7, 100));
            addRow(arrayOf(6, 100)); addRow(arrayOf(5, 50))
        }
        cursor = rows()
        val first = readGalleryImages(context, 300_000L, pageSize = 2)
        assertThat(first.map { it.uri.lastPathSegment }).containsExactly("8", "7").inOrder()
        assertThat(cursor.isClosed).isTrue()
        cursor = rows()
        val second = readGalleryImages(context, 300_000L, first.last(), pageSize = 2)
        assertThat(second.map { it.uri.lastPathSegment }).containsExactly("6", "5").inOrder()
        assertThat(selection).contains("_id < ?")
        assertThat(selection).contains("is_pending = 0")
        assertThat(selection).contains("is_trashed = 0")
        assertThat(cursor.isClosed).isTrue()
    }

    @Test fun `gallery page is bounded and permission revocation returns no images`() = runTest {
        permission(Manifest.permission.READ_MEDIA_IMAGES)
        cursor = MatrixCursor(arrayOf("_id", "date_added")).apply { (100 downTo 1).forEach { addRow(arrayOf(it, 100)) } }
        assertThat(readGalleryImages(context, 300_000L, pageSize = 1000)).hasSize(40)
        deniedDuringQuery = true
        assertThat(readGalleryImages(context, 300_000L)).isEmpty()
    }
}
