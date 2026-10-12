/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AttachmentDirectoryBrowserTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only immediate children appear and directory contents are not restricted to recent categories`() = runTest {
        val root = temporary.newFolder()
        File(root, MANAGED_FOLDER_MARKER).writeText("marker")
        File(root, "direct.txt").writeText("direct")
        File(root, "photo.jpg").writeText("photo")
        val child = File(root, "Nested").apply { mkdir() }
        File(child, "deep.xlsx").writeText("deep")
        val source = AttachmentSource(Uri.fromFile(root), true, 0, managed = true)
        val resolver = mockk<ContentResolver>()
        val listing = browseAttachmentDirectory(resolver, source, hasSharedAccess = { true })
        assertThat(listing.files.map { it.name }).containsExactly("direct.txt", "photo.jpg")
        assertThat(listing.directories.map { it.name }).containsExactly("Nested")
        val nested = browseAttachmentDirectory(resolver, source, listing.directories.single().uri, { true })
        assertThat(nested.files.map { it.name }).containsExactly("deep.xlsx")
        assertThat(nested.files.single().root).isEqualTo(source.uri.toString())
    }

    @Test
    fun `revoked shared access and paths outside the source are rejected`() = runTest {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        File(outside, "private.txt").writeText("untouched")
        val source = AttachmentSource(Uri.fromFile(root), true, 0, managed = true)
        val resolver = mockk<ContentResolver>()
        assertThat(runCatching { browseAttachmentDirectory(resolver, source, hasSharedAccess = { false }) }.isFailure).isTrue()
        assertThat(runCatching { browseAttachmentDirectory(resolver, source, Uri.fromFile(outside), { true }) }.isFailure).isTrue()
        assertThat(File(outside, "private.txt").readText()).isEqualTo("untouched")
    }

    @Test
    fun `SAF browsing queries one level and rejects a different tree`() = runTest {
        val resolver = mockk<ContentResolver>()
        val source = AttachmentSource(Uri.parse("content://qa.documents/tree/root"), true, 0)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            assertThat(firstArg<Uri>().toString()).endsWith("document/root/children")
            MatrixCursor(secondArg<Array<String>>()).apply {
                addRow(arrayOf<Any?>("nested", "Nested", DocumentsContract.Document.MIME_TYPE_DIR, null, 0L))
                addRow(arrayOf<Any?>("note", "note.txt", "text/plain", 4L, 100L))
            }
        }
        val listing = browseAttachmentDirectory(resolver, source)
        assertThat(listing.files.map { it.name }).containsExactly("note.txt")
        assertThat(listing.directories.map { it.name }).containsExactly("Nested")
        assertThat(runCatching {
            browseAttachmentDirectory(resolver, source, Uri.parse("content://qa.documents/tree/other/document/root"))
        }.isFailure).isTrue()
        verify(exactly = 1) { resolver.query(any(), any(), any(), any(), any()) }
    }
}
