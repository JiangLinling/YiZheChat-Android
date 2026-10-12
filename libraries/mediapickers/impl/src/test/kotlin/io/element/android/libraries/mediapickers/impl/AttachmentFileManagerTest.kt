package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AttachmentFileManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = mockk<AttachmentSourceStore>()
    private val operationId = UUID.randomUUID().toString()
    private var pending: JSONObject? = null
    private var sources = listOf<AttachmentSource>()

    private fun manager(canAccess: Boolean = true): Pair<AttachmentFileManager, File> {
        val root = temporary.newFolder()
        coEvery { store.managedRoot("alice") } returns root
        coEvery { store.pendingManagement("alice") } answers { pending }
        coEvery { store.load("alice") } answers { sources }
        coEvery { store.persistManagement("alice", any()) } answers {
            pending = secondArg()
            Unit
        }
        coEvery { store.completeManagement("alice", any(), any()) } answers {
            val folder = thirdArg<File?>()
            if (folder != null) sources = sources + AttachmentSource(Uri.fromFile(folder), true, 1, managed = true)
            pending = null
            Unit
        }
        return AttachmentFileManager(store, canAccess = { canAccess }, sharedRoot = { root }) to root
    }

    @Test
    fun `folder and UTF8 file are real on disk and existing content is never overwritten`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Invoices", operationId)
        val folder = sources.single()
        manager.createText("alice", folder, "note", "发票 123", UUID.randomUUID().toString())
        val file = File(root, "Invoices/note.txt")
        assertThat(file.readText()).isEqualTo("发票 123")
        assertThat(pending).isNull()
        assertThat(runCatching { manager.createText("alice", folder, "note", "replacement", UUID.randomUUID().toString()) }.isFailure).isTrue()
        assertThat(file.readText()).isEqualTo("发票 123")
        assertThat(runCatching { manager.createFolder("alice", "../escape", UUID.randomUUID().toString()) }.isFailure).isTrue()
    }

    @Test
    fun `revoked access leaves persisted intent and creates no file`() = runTest {
        val (manager, root) = manager(canAccess = false)
        assertThat(runCatching { manager.createFolder("alice", "Pending", operationId) }.isFailure).isTrue()
        assertThat(File(root, "Pending").exists()).isFalse()
        assertThat(pending?.getString("operationId")).isEqualTo(operationId)
    }

    @Test
    fun `recovery of already moved file verifies destination without duplicating or deleting anything`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Destination", UUID.randomUUID().toString())
        val target = File(root, "Destination/result.txt").apply { writeText("preserved bytes") }
        val absentSource = File(root, "YZ-QA-${UUID.randomUUID()}.txt")
        pending = JSONObject().put(
            "operationId",
            operationId
        ).put(
            "kind",
            "move"
        ).put(
            "entries",
            org.json.JSONArray().put(JSONObject().put("source", absentSource.path).put("target", target.path).put("sha256", attachmentDiskHash(target)))
        )
        manager.resume("alice")
        assertThat(pending).isNull()
        assertThat(target.readText()).isEqualTo("preserved bytes")
        assertThat(absentSource.exists()).isFalse()
    }

    @Test
    fun `changed destination keeps operation unresolved and stop preserves the file`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Destination", UUID.randomUUID().toString())
        val target = File(root, "Destination/result.txt").apply { writeText("changed") }
        pending = JSONObject().put(
            "operationId",
            operationId
        ).put("kind", "move").put("entries", org.json.JSONArray().put(JSONObject().put("source", File(root, "missing.txt").path)
                .put("target", target.path).put("sha256", sha256("original".encodeToByteArray()))))
        assertThat(runCatching { manager.resume("alice") }.isFailure).isTrue()
        assertThat(pending).isNotNull()
        assertThat(target.readText()).isEqualTo("changed")
        manager.cancel("alice")
        assertThat(pending).isNull()
        assertThat(target.readText()).isEqualTo("changed")
    }

    @Test
    fun `move preserves byte identity and collision leaves original files untouched`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Destination", UUID.randomUUID().toString())
        val incoming = File(root, "incoming").apply { mkdir() }
        val source = File(incoming, "file.txt").apply { writeText("exact bytes") }
        val selected = attachmentFileFromDisk(source, root)
        manager.move("alice", listOf(selected), sources.single(), operationId)
        val target = File(root, "Destination/file.txt")
        assertThat(source.exists()).isFalse()
        assertThat(target.readBytes()).isEqualTo("exact bytes".encodeToByteArray())
        source.writeText("new source")
        assertThat(runCatching { manager.move("alice", listOf(selected), sources.single(), UUID.randomUUID().toString()) }.isFailure).isTrue()
        assertThat(source.readText()).isEqualTo("new source")
        assertThat(target.readText()).isEqualTo("exact bytes")
    }

    @Test
    fun `creation and movement in a child directory remain within the registered account source`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Owned", UUID.randomUUID().toString())
        val top = sources.single()
        manager.createFolder("alice", "Nested", UUID.randomUUID().toString(), top.uri)
        assertThat(sources).hasSize(1)
        val nested = top.copy(uri = Uri.fromFile(File(root, "Owned/Nested")))
        manager.createText("alice", nested, "local", "nested content", UUID.randomUUID().toString())
        assertThat(File(root, "Owned/Nested/local.txt").readText()).isEqualTo("nested content")
        val source = File(root, "incoming.pdf").apply { writeText("exact bytes") }
        manager.move("alice", listOf(attachmentFileFromDisk(source, root)), nested, UUID.randomUUID().toString())
        assertThat(source.exists()).isFalse()
        assertThat(File(root, "Owned/Nested/incoming.pdf").readText()).isEqualTo("exact bytes")
        assertThat(pending).isNull()
    }

    @Test
    fun `rename after a collision preserves both existing content and moved bytes`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Destination", UUID.randomUUID().toString())
        val source = File(root, "same.txt").apply { writeText("new bytes") }
        File(root, "Destination/same.txt").writeText("old bytes")
        val item = attachmentFileFromDisk(source, root)
        assertThat(runCatching { manager.move("alice", listOf(item), sources.single(), operationId) }.exceptionOrNull())
            .isInstanceOf(AttachmentNameCollisionException::class.java)
        assertThat(pending).isNull()
        manager.move("alice", listOf(item), sources.single(), UUID.randomUUID().toString(), "renamed.txt")
        assertThat(source.exists()).isFalse()
        assertThat(File(root, "Destination/same.txt").readText()).isEqualTo("old bytes")
        assertThat(File(root, "Destination/renamed.txt").readText()).isEqualTo("new bytes")
    }

    @Test
    fun `a synthetic destination outside owned source cannot authorize writes`() = runTest {
        val (manager, root) = manager()
        manager.createFolder("alice", "Owned", UUID.randomUUID().toString())
        val outside = temporary.newFolder()
        val fabricated = sources.single().copy(uri = Uri.fromFile(outside))
        assertThat(runCatching { manager.createText("alice", fabricated, "escape", "no", UUID.randomUUID().toString()) }.isFailure).isTrue()
        assertThat(runCatching { manager.createFolder("alice", "escape", UUID.randomUUID().toString(), fabricated.uri) }.isFailure).isTrue()
        assertThat(outside.listFiles()).isEmpty()
        assertThat(File(root, "Owned").isDirectory).isTrue()
    }
}
