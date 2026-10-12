/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.draft

import android.content.Context
import android.content.ContextWrapper
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
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleDraftApplied
import io.element.android.security.dataprotection.api.LifecycleDraftWriteContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ComposerDraftTransitionRecoveryTest {
    @Test fun `crash between stores recovers original caption before a newer ordinary write`() = runTest {
        val f = Fixture(this)
        f.matrixDraft = ordinary("previous")
        f.failMatrixWrite = true
        val owner = LifecycleDraftWriteContext(ORIGINAL)
        assertThat(runCatching { withContext(owner) { f.store().updateComposerDraft(ROOM, null, CAPTION, f.matrix) } }.isFailure).isTrue()
        assertThat(owner.hasDurableResultOwner).isTrue()
        assertThat(f.facts).isEmpty()
        assertThat(f.preferences.all.keys.any { it.startsWith("transition:") }).isTrue()
        f.failMatrixWrite = false
        val reopened = f.store()
        reopened.prepareComposerDraftWrite(ROOM, null, f.matrix)
        assertThat(f.facts.map { it.operationId }).containsExactly(ORIGINAL)
        withContext(LifecycleDraftWriteContext(NEWER)) { reopened.updateComposerDraft(ROOM, null, ordinary("newer"), f.matrix) }
        assertThat(f.writes.map { it.first }).containsExactly(ORIGINAL, ORIGINAL, NEWER).inOrder()
        assertThat(f.facts.map { it.operationId }).containsExactly(ORIGINAL, NEWER).inOrder()
        assertThat(reopened.loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(ordinary("newer"))
        assertThat(f.preferences.all.keys.none { it.startsWith("transition:") || it.startsWith("applied:") }).isTrue()
        assertThat(f.preferences.all.toString()).doesNotContain("secret caption")
        assertThat(f.preferences.all.toString()).doesNotContain("newer")
    }

    @Test fun `crash after stores but before result commit resumes only original transition`() = runTest {
        val f = Fixture(this)
        f.failResultCommit = true
        assertThat(runCatching { withContext(LifecycleDraftWriteContext(ORIGINAL)) {
            f.store().updateComposerDraft(ROOM, null, ordinary("original"), f.matrix)
        } }.isFailure).isTrue()
        assertThat(f.matrixDraft).isEqualTo(ordinary("original"))
        assertThat(f.facts).isEmpty()
        f.failResultCommit = false
        val reopened = f.store()
        assertThat(reopened.loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(ordinary("original"))
        assertThat(f.facts.single().operationId).isEqualTo(ORIGINAL)
        assertThat(f.writes.map { it.first }).containsExactly(ORIGINAL, ORIGINAL)
    }

    @Test fun `late result handoff never rewrites a newer committed draft`() = runTest {
        val f = Fixture(this)
        f.acceptFacts = false
        withContext(LifecycleDraftWriteContext(ORIGINAL)) { f.store().updateComposerDraft(ROOM, null, CAPTION, f.matrix) }
        val retained = f.preferences.all.filterKeys { it.startsWith("applied:") }.values.single()
        withContext(LifecycleDraftWriteContext(NEWER)) { f.store().updateComposerDraft(ROOM, null, ordinary("newer"), f.matrix) }
        assertThat(f.preferences.all.filterKeys { it.startsWith("applied:") }.values).contains(retained)
        val writes = f.writes.toList()
        f.acceptFacts = true
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(ordinary("newer"))
        assertThat(f.writes).isEqualTo(writes)
        assertThat(f.facts.map { it.operationId }).containsExactly(ORIGINAL, NEWER)
        assertThat(f.facts.map { it.storageVersionHash }.distinct()).hasSize(2)
    }

    @Test fun `ordinary write failure retains caption and blocks later writes until recovery`() = runTest {
        val f = Fixture(this)
        f.store().updateDraft(ROOM, null, CAPTION)
        val previous = f.store().captureVersion(ROOM, null)
        f.failMatrixWrite = true
        assertThat(runCatching { withContext(LifecycleDraftWriteContext(ORIGINAL)) {
            f.store().updateComposerDraft(ROOM, null, ordinary("replacement"), f.matrix)
        } }.isFailure).isTrue()
        assertThat(f.store().captureVersion(ROOM, null)).isEqualTo(previous)
        assertThat(runCatching { withContext(LifecycleDraftWriteContext(NEWER)) {
            f.store().updateComposerDraft(ROOM, null, ordinary("must wait"), f.matrix)
        } }.isFailure).isTrue()
        assertThat(f.writes.map { it.first }.distinct()).containsExactly(ORIGINAL)
        assertThat(f.facts).isEmpty()
    }

    @Test fun `changed installation rejects retained transition before another storage write`() = runTest {
        val f = Fixture(this)
        f.failMatrixWrite = true
        runCatching { withContext(LifecycleDraftWriteContext(ORIGINAL)) {
            f.store().updateComposerDraft(ROOM, null, CAPTION, f.matrix)
        } }
        val writes = f.writes.toList()
        f.failMatrixWrite = false
        coEvery { f.lifecycle.validateLocalDraftRecovery(any(), any(), any(), any()) } throws IllegalStateException("installation changed")
        assertThat(runCatching { f.store().loadComposerDraft(ROOM, null, f.matrix) }.isFailure).isTrue()
        assertThat(f.writes).isEqualTo(writes)
        assertThat(f.preferences.all.keys.any { it.startsWith("transition:") }).isTrue()
    }

    @Test fun `clear transition and caption CAS preserve exact storage ownership`() = runTest {
        val f = Fixture(this)
        val store = f.store()
        withContext(LifecycleDraftWriteContext(ORIGINAL)) { store.updateComposerDraft(ROOM, null, CAPTION, f.matrix) }
        val captionVersion = store.captureComposerDraftVersion(ROOM, null, f.matrix)
        withContext(LifecycleDraftWriteContext(NEWER)) { store.updateComposerDraft(ROOM, null, ordinary("current"), f.matrix) }
        assertThat(store.clearComposerDraftIfVersion(ROOM, null, captionVersion, f.matrix)).isFalse()
        assertThat(store.loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(ordinary("current"))
        store.updateComposerDraft(ROOM, null, null, f.matrix)
        assertThat(store.loadComposerDraft(ROOM, null, f.matrix)).isNull()
    }

    private class Fixture(private val scope: CoroutineScope) {
        var failResultCommit = false
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        var resultCommit = false
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                if (key?.startsWith("applied:") == true) resultCommit = true
                                editor.putString(key, value)
                                return this
                            }
                            override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                            override fun commit(): Boolean {
                                if (failResultCommit && resultCommit) error("simulated process exit before result commit")
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val preferences = context.getSharedPreferences("protected_caption_draft_references_v1", Context.MODE_PRIVATE)
        val files = InMemorySensitiveFileStore()
        val client = mockk<MatrixClient> {
            every { sessionId } returns SessionId("@alice:test")
            every { deviceId } returns DeviceId("DEVICE")
        }
        val room = mockk<JoinedRoom> { every { roomId } returns ROOM }
        val lifecycle = mockk<AppOperationalStateCoordinator>()
        val matrix = mockk<MatrixComposerDraftStore>()
        var matrixDraft: ComposerDraft? = null
        var failMatrixWrite = false
        var acceptFacts = true
        val facts = mutableListOf<LifecycleDraftApplied>()
        val writes = mutableListOf<Pair<String?, ComposerDraft?>>()
        init {
            coEvery { lifecycle.validateLocalDraftRecovery(any(), any(), any(), any()) } just Runs
            coEvery { lifecycle.recoverLocalDraftApplied(any()) } coAnswers {
                if (acceptFacts) facts += firstArg<LifecycleDraftApplied>()
                acceptFacts
            }
            coEvery { matrix.loadDraft(ROOM, null) } coAnswers { matrixDraft }
            coEvery { matrix.updateDraft(ROOM, null, any()) } coAnswers {
                writes += currentLifecycleOperationContext()?.operationId to thirdArg<ComposerDraft?>()
                check(!failMatrixWrite) { "matrix write interrupted" }
                matrixDraft = thirdArg()
            }
        }
        fun store() = ProtectedCaptionDraftStore(context, files, client, room, scope, lifecycle)
    }

    companion object {
        private val ROOM = RoomId("!room:test")
        private const val ORIGINAL = "original-draft-operation-0001"
        private const val NEWER = "newer-draft-operation-000002"
        private val CAPTION = ComposerDraft("secret caption", "<b>secret caption</b>", ComposerDraftType.EditCaption(EventId("\$event")))
        private fun ordinary(text: String) = ComposerDraft(text, null, ComposerDraftType.NewMessage)
    }
}
