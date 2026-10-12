package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AttachmentIncrementalIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `dirty directory reconciliation handles create rename delete and new nested folders`() = runTest {
        val root = temporary.newFolder()
        val documents = File(root, "Documents").apply { mkdir() }
        val old = File(documents, "old.pdf").apply { writeText("old") }
        val stamps = mutableMapOf<String, Long>()
        val initial = scanAttachmentSharedFiles(listOf(root), onDirectory = { directory, time -> stamps[directory.path] = time })
        val backend = AttachmentAndroidIndexBackend(RuntimeEnvironment.getApplication(), "qa", mockk(), { listOf(root) })
        var snapshot = AttachmentIndexSnapshot(disk = initial, media = initial, directories = stamps, baselineComplete = true, sharedAccess = true)
        val renamed = File(documents, "renamed.pdf")
        assertThat(old.renameTo(renamed)).isTrue()
        File(documents, "new.txt").writeText("new")
        File(documents, "nested").apply { mkdir() }.resolve("child.docx").writeText("child")
        snapshot = backend.reconcile(snapshot, setOf(documents.path), false)
        assertThat(snapshot.disk.files.map { it.name }).containsExactly("renamed.pdf", "new.txt", "child.docx")
        assertThat(snapshot.projection().files.map { it.name }).containsExactly("renamed.pdf", "new.txt", "child.docx")
        assertThat(renamed.delete()).isTrue()
        snapshot = backend.reconcile(snapshot, setOf(documents.path), false)
        assertThat(snapshot.disk.files.map { it.name }).containsExactly("new.txt", "child.docx")
        assertThat(snapshot.directories.keys).contains(File(documents, "nested").path)
        File(documents, "nested").deleteRecursively()
        snapshot = backend.reconcile(snapshot, setOf(documents.path), false)
        assertThat(snapshot.projection().files.map { it.name }).containsExactly("new.txt")
    }

    @Test
    fun `explicit refresh detects overwrite even when directory timestamp is unchanged`() = runTest {
        val root = temporary.newFolder()
        val documents = File(root, "Documents").apply { mkdir() }
        val file = File(documents, "a.txt").apply { writeText("a") }
        val stamps = mutableMapOf<String, Long>()
        val initial = scanAttachmentSharedFiles(listOf(root), onDirectory = { directory, time -> stamps[directory.path] = time })
        val originalStamp = documents.lastModified()
        file.writeText("updated contents")
        documents.setLastModified(originalStamp)
        val backend = AttachmentAndroidIndexBackend(RuntimeEnvironment.getApplication(), "qa", mockk(), { listOf(root) })
        val result = backend.reconcile(
            AttachmentIndexSnapshot(disk = initial, directories = stamps, baselineComplete = true, sharedAccess = true),
            emptySet(),
            true
        )
        assertThat(result.disk.files.single().size).isEqualTo(file.length())
    }
}
