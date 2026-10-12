package io.element.android.libraries.mediapickers.impl

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AttachmentSourceStoreTest {
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getContentResolver() = resolver
    }
    private val sessions = mockk<SessionStore>()
    private val files = InMemorySensitiveFileStore()

    init {
        context.getSharedPreferences(AttachmentSourceStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        every { resolver.persistedUriPermissions } returns emptyList()
        listOf("alice", "bob").forEach { user ->
            val session = mockk<SessionData>()
            every { session.userId } returns user
            every { session.deviceId } returns "QA-DEVICE"
            coEvery { sessions.getSession(user) } returns session
        }
    }

    @Test
    fun `encrypted index reopens separately per account and never exposes names or directory paths in preferences`() = runTest {
        val uri = Uri.parse("file:///Documents/private-contract.pdf")
        val snapshot = AttachmentIndexSnapshot(
            media = AttachmentIndex(listOf(AttachmentFile(
                uri,
                "private-contract.pdf",
                "application/pdf",
            123L,
                456,
                100,
                "Documents",
                "root"
            )), emptyList(), 0, false),
            directories = mapOf("/Documents/Secret" to 100),
            mediaStamp = "version:1",
            baselineComplete = true,
            sharedAccess = true
        )
        AttachmentSourceStore(context, sessions, files).saveIndex("alice", snapshot)
        val reopened = AttachmentSourceStore(context, sessions, files)
        assertThat(reopened.loadIndex("alice")).isEqualTo(snapshot)
        assertThat(reopened.loadIndex("bob")).isNull()
        val persisted = context.getSharedPreferences(AttachmentSourceStore.PREFERENCES, Context.MODE_PRIVATE).all.toString()
        assertThat(persisted).doesNotContain("private-contract")
        assertThat(persisted).doesNotContain("Documents")
        files.deleteVault(deriveAccountVaultId("alice", "QA-DEVICE"))
        assertThat(runCatching { reopened.loadIndex("alice") }.isFailure).isTrue()
        assertThat(runCatching { reopened.saveIndex("alice", snapshot) }.isFailure).isTrue()
    }

    @Test
    fun `source registry survives reopening and keeps accounts separate without plaintext URIs`() = runTest {
        val uri = Uri.parse("content://qa.documents/document/private-contract")
        AttachmentSourceStore(context, sessions, files).add("alice", listOf(uri), false)
        val reopened = AttachmentSourceStore(context, sessions, files)
        assertThat(reopened.load("alice").single().uri).isEqualTo(uri)
        assertThat(reopened.load("bob")).isEmpty()
        val persisted = context.getSharedPreferences(AttachmentSourceStore.PREFERENCES, Context.MODE_PRIVATE).all.toString()
        assertThat(persisted).doesNotContain(uri.toString())
        assertThat(persisted).doesNotContain("private-contract")
    }

    @Test
    fun `destroyed vault refuses persistence and rolls back new URI grants`() = runTest {
        val uri = Uri.parse("content://qa.documents/document/new")
        files.deleteVault(deriveAccountVaultId("alice", "QA-DEVICE"))
        val failure = runCatching { AttachmentSourceStore(context, sessions, files).add("alice", listOf(uri), false) }
        assertThat(failure.isFailure).isTrue()
        verify { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        assertThat(context.getSharedPreferences(AttachmentSourceStore.PREFERENCES, Context.MODE_PRIVATE).all).isEmpty()
    }

    @Test
    fun `application-owned secure content cannot be imported as an external source`() = runTest {
        val uri = Uri.parse("content://${context.packageName}.securemedia/token")
        assertThat(runCatching { AttachmentSourceStore(context, sessions, files).add("alice", listOf(uri), false) }.isFailure).isTrue()
        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
    }

    @Test
    fun `organization intent survives process recreation and remains encrypted and account scoped`() = runTest {
        val store = AttachmentSourceStore(context, sessions, files)
        val operationId = java.util.UUID.randomUUID().toString()
        store.persistManagement("alice", JSONObject().put("operationId", operationId).put("kind", "text")
            .put("target", "/Documents/private-contract.txt").put("content", "private draft contents"))
        val reopened = AttachmentSourceStore(context, sessions, files)
        assertThat(reopened.pendingManagement("alice")?.getString("content")).isEqualTo("private draft contents")
        assertThat(reopened.pendingManagement("bob")).isNull()
        val persisted = context.getSharedPreferences(AttachmentSourceStore.PREFERENCES, Context.MODE_PRIVATE).all.toString()
        assertThat(persisted).doesNotContain("private-contract")
        assertThat(persisted).doesNotContain("private draft contents")
        reopened.completeManagement("alice", operationId)
        assertThat(reopened.pendingManagement("alice")).isNull()
    }
}
