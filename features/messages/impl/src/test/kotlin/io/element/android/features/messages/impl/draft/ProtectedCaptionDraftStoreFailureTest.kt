package io.element.android.features.messages.impl.draft

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ProtectedCaptionDraftStoreFailureTest {
    @Test
    fun `write failure preserves the previously committed generation`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.files.write(any(), any()) } returns fixture.reference("old")
        fixture.store.updateDraft(ROOM_ID, null, DRAFT)
        val oldVersion = fixture.store.captureVersion(ROOM_ID, null)
        coEvery { fixture.files.write(any(), any()) } throws IllegalStateException("write failed")

        runCatching { fixture.store.updateDraft(ROOM_ID, null, DRAFT.copy(plainText = "new")) }

        assertThat(fixture.store.captureVersion(ROOM_ID, null)).isEqualTo(oldVersion)
    }

    @Test
    fun `reference commit failure deletes the uncommitted encrypted object`() = runTest {
        val fixture = Fixture(this)
        val uncommitted = fixture.reference("uncommitted")
        coEvery { fixture.files.write(any(), any()) } returns uncommitted
        fixture.failNextCommit = true

        runCatching { fixture.store.updateDraft(ROOM_ID, null, DRAFT) }

        coVerify(exactly = 1) { fixture.files.delete(uncommitted) }
        assertThat(fixture.store.captureVersion(ROOM_ID, null)).isNull()
    }

    @Test
    fun `delete failure is journaled and a later operation retries retirement`() = runTest {
        val fixture = Fixture(this)
        val old = fixture.reference("old")
        val replacement = fixture.reference("replacement")
        coEvery { fixture.files.write(any(), any()) } returnsMany listOf(old, replacement)
        coEvery { fixture.files.delete(old) } returns false andThen true
        coEvery { fixture.files.delete(replacement) } returns true

        fixture.store.updateDraft(ROOM_ID, null, DRAFT)
        fixture.store.updateDraft(ROOM_ID, null, DRAFT.copy(plainText = "replacement"))
        fixture.store.captureVersion(ROOM_ID, null)

        coVerify(exactly = 2) { fixture.files.delete(old) }
        assertThat(fixture.preferences.all.keys.none { it.startsWith("retired:") }).isTrue()
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val preferences = mockk<SharedPreferences>()
        val files = mockk<SensitiveFileStore>(relaxed = true)
        var failNextCommit = false
        private val values = linkedMapOf<String, String>()
        private val pending = linkedMapOf<String, String?>()
        private val editor = mockk<SharedPreferences.Editor>()

        init {
            every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.all } answers { values.toMap() }
            every { preferences.edit() } answers { pending.clear(); editor }
            every { editor.putString(any(), any()) } answers { pending[firstArg()] = secondArg(); editor }
            every { editor.remove(any()) } answers { pending[firstArg()] = null; editor }
            every { editor.commit() } answers {
                if (failNextCommit) {
                    failNextCommit = false
                    false
                } else {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    true
                }
            }
        }

        private val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        private val client = mockk<MatrixClient> {
            every { sessionId } returns SessionId("@alice:example.org")
            every { deviceId } returns DeviceId("DEVICE")
        }
        private val room = mockk<JoinedRoom> { every { roomId } returns ROOM_ID }
        val store = ProtectedCaptionDraftStore(context, files, client, room, scope, mockk(relaxed = true))

        private val vault = deriveAccountVaultId("@alice:example.org", "DEVICE")
        private val roomHash = sha256(ROOM_ID.value)

        fun reference(id: String) = SecureObjectReference(
            objectId = SecureObjectId(id),
            vaultId = vault,
            purpose = SecureObjectPurpose.EDITING_STAGING,
            keyEnvelopeId = KeyEnvelopeId("key-$id"),
            formatVersion = 1,
            plaintextLength = 32,
            ciphertextLength = 64,
            associatedDataSha256 = sha256("caption-draft-v1\u0000${vault.value}\u0000$roomHash"),
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val ROOM_ID = RoomId("!room:example.org")
        val DRAFT = ComposerDraft("caption", null, ComposerDraftType.EditCaption(EventId("\$event")))
    }
}
