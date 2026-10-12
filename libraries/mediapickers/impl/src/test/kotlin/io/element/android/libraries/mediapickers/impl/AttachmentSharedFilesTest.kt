package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import android.os.Environment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AttachmentSharedFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `shared roots always include the primary directory when volume metadata is absent`() {
        assertThat(attachmentStorageRoots(RuntimeEnvironment.getApplication()).map { it.canonicalFile })
            .contains(Environment.getExternalStorageDirectory().canonicalFile)
    }

    @Test
    fun `shared recent scan includes different directories without adding sources and skips app private storage`() = runTest {
        val root = temporary.newFolder()
        val downloads = File(root, "Download").apply { mkdir() }
        val documents = File(root, "Documents").apply { mkdir() }
        val sharedMedia = File(root, "Android/media/chat").apply { mkdirs() }
        val privateFiles = File(root, "Android/data/chat").apply { mkdirs() }
        val old = File(documents, "old.pdf").apply {
            writeText("old")
            setLastModified(100_000)
        }
        val newest = File(downloads, "invoice.pdf").apply {
            writeText("new")
            setLastModified(300_000)
        }
        val media = File(sharedMedia, "saved.docx").apply {
            writeText("shared")
            setLastModified(200_000)
        }
        File(privateFiles, "private.txt").writeText("must not appear")
        val result = scanAttachmentSharedFiles(listOf(root))
        assertThat(result.files.map { it.uri }).containsExactly(Uri.fromFile(newest), Uri.fromFile(media), Uri.fromFile(old)).inOrder()
        assertThat(result.folders).isEmpty()
    }

    @Test
    fun `canonical path traversal outside shared root is rejected`() {
        val root = temporary.newFolder("shared")
        assertThat(isAttachmentSharedPath(File(root, "../secret.txt"), root)).isFalse()
        assertThat(isAttachmentSharedPath(File(root, "Android/obb/game.bin"), root)).isFalse()
        assertThat(isAttachmentSharedPath(File(root, "Android/media/chat/file.pdf"), root)).isTrue()
    }

    @Test
    fun `documents are published before the complete shared storage traversal finishes`() = runTest {
        val root = temporary.newFolder()
        File(root, "Other").apply { mkdir() }.resolve("older.txt").writeText("older")
        File(root, "Documents").apply { mkdir() }.resolve("latest.txt").writeText("latest")
        val updates = mutableListOf<AttachmentIndex>()
        val result = scanAttachmentSharedFiles(listOf(root), onUpdate = { updates.add(it) })
        assertThat(updates.first().files.map { it.name }).containsExactly("latest.txt")
        assertThat(result.files.map { it.name }).containsExactly("latest.txt", "older.txt")
    }

    @Test
    fun `global and common folder projections do not duplicate a file or lose its folder identity`() {
        val uri = Uri.fromFile(File(temporary.root, "file.txt"))
        val file = AttachmentFile(uri, "file.txt", "text/plain", 1, 100, 0, "Documents", "phone")
        val common = file.copy(source = "Contracts", root = "my-folder")
        val merged = mergeAttachmentIndexes(
            AttachmentIndex(listOf(file), emptyList(), 0, false),
            AttachmentIndex(listOf(common), listOf("my-folder" to "Contracts"), 0, false)
        )
        assertThat(merged.files).containsExactly(common)
        assertThat(merged.folders).containsExactly("my-folder" to "Contracts")
    }
}
