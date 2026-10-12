/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.draft

import android.content.Context
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
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleDraftApplied
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class SentTextDraftRecoveryTest {
    @Test fun `canonical storage verified registration consumes original sent draft after process restart`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        f.authority = f.confirmed().copy(steps = listOf(f.registeredStep()))
        assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNotNull()
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isNull()
    }

    @Test fun `registration commit requires storage authority verified event hash and canonical receipt`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        val valid = f.registeredStep()
        listOf(valid.copy(authorityRevision = "ui-success"), valid.copy(resultCode = "ENQUEUED"),
            valid.copy(resultReference = null), valid.copy(resultReference = "invalid"),
            valid.copy(receiptReference = "matrix-event:unverified"),
            valid.copy(status = LifecycleStepStatus.RETRY)).forEach { step ->
            f.authority = f.confirmed().copy(steps = listOf(step))
            assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
            assertThat(f.persisted).isEqualTo(TEXT)
        }
    }
    @Test fun `process dies before debounce then original Matrix confirmation consumes only sent draft`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        val version = f.store().textDraftVersion(ROOM)
        // New store instances share only real durable pointers/secure objects and Matrix persistence.
        assertThat(runCatching { f.store().updateComposerDraft(ROOM, null, null, f.matrix) }.isFailure).isTrue()
        assertThat(f.persisted).isEqualTo(TEXT)
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(TEXT)
        f.authority = f.confirmed()
        assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isEqualTo(ConsumedTextDraft(version!!, TEXT))
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isNull()
        assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
        assertThat(f.queried).contains(SEND)
    }

    @Test fun `enqueue success cached UI success and unknown authority retain original draft`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        // No signed Matrix step: no amount of optimistic success or retry acceptance owns a clear.
        f.authority = f.confirmed().copy(steps = emptyList())
        assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
        assertThat(f.persisted).isEqualTo(TEXT)
        f.authority = null
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(TEXT)
        f.authority = f.confirmed().copy(steps = listOf(f.step().copy(status = LifecycleStepStatus.RETRY, receiptReference = null)))
        assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
        assertThat(f.persisted).isEqualTo(TEXT)
    }

    @Test fun `later draft including identical text belongs to its new version`() = runTest {
        for (later in listOf(TEXT, TEXT.copy(plainText = "later edit"), TEXT.copy(draftType = ComposerDraftType.Reply(EventId("\$reply"))))) {
            val f = Fixture(this)
            f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
            val sentVersion = f.store().textDraftVersion(ROOM)
            f.store().updateComposerDraft(ROOM, null, later, f.matrix)
            assertThat(f.store().textDraftVersion(ROOM)).isNotEqualTo(sentVersion)
            f.authority = f.confirmed()
            assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
            assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(later)
        }
    }

    @Test fun `another operation account device room or unsigned Matrix result cannot consume`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        val good = f.confirmed()
        val candidates = listOf(good.copy(operationId = "lc13-send-" + "c".repeat(64)),
            good.copy(subjects = good.subjects.copy(accountIdHash = "c".repeat(64))),
            good.copy(subjects = good.subjects.copy(matrixDeviceIdHash = "c".repeat(64))),
            good.copy(subjects = good.subjects.copy(roomIdHash = "c".repeat(64))),
            good.copy(lifecycleId = FullAppLifecycleId.LC_14),
            good.copy(steps = listOf(f.step().copy(authorityRevision = "ui-success"))),
            good.copy(steps = listOf(f.step().copy(receiptReference = "enqueue-success"))))
        candidates.forEach {
            f.authority = it
            assertThat(f.store().reconcileSentTextDraft(ROOM, f.matrix)).isNull()
            assertThat(f.persisted).isEqualTo(TEXT)
        }
        assertThat(runCatching { f.store().reconcileSentTextDraft(RoomId("!other:test"), f.matrix) }.isFailure).isTrue()
        assertThat(f.persisted).isEqualTo(TEXT)
    }

    @Test fun `crash during confirmed clear resumes durable consumption before any new write`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        f.authority = f.confirmed()
        f.failClear = true
        assertThat(runCatching { f.store().reconcileSentTextDraft(ROOM, f.matrix) }.isFailure).isTrue()
        assertThat(f.persisted).isEqualTo(TEXT)
        f.failClear = false
        // The encrypted clear transition already owns the authority-confirmed fact.
        f.authority = null
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isNull()
        f.store().updateComposerDraft(ROOM, null, TEXT, f.matrix)
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(TEXT)
    }

    @Test fun `legacy unbound draft is retained and no body matching migration clears it`() = runTest {
        val f = Fixture(this)
        f.persisted = TEXT
        f.authority = f.confirmed()
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(TEXT)
        assertThat(f.queried).isEmpty()
        assertThat(f.preferences.all.toString()).doesNotContain(TEXT.plainText)
    }

    @Test fun `an unsaved newer editor revision fences consumption while authority query is suspended`() = runTest {
        val f = Fixture(this)
        f.store().bindTextSendDraft(ROOM, TEXT, SEND, f.matrix)
        f.authority = f.confirmed()
        val queried = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterQuery = { queried.complete(Unit); release.await() }
        val consuming = async { f.store().reconcileSentTextDraft(ROOM, f.matrix) { f.allowConsumption } }
        queried.await()
        f.allowConsumption = false
        release.complete(Unit)
        assertThat(consuming.await()).isNull()
        assertThat(f.persisted).isEqualTo(TEXT)
        f.store().updateComposerDraft(ROOM, null, TEXT, f.matrix)
        assertThat(f.store().loadComposerDraft(ROOM, null, f.matrix)).isEqualTo(TEXT)
    }

    private class Fixture(private val scope: CoroutineScope) {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("protected_caption_draft_references_v1", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val files = InMemorySensitiveFileStore()
        val client = mockk<MatrixClient> {
            every { sessionId } returns SessionId(ACCOUNT)
            every { deviceId } returns DeviceId(DEVICE)
        }
        val room = mockk<JoinedRoom> { every { roomId } returns ROOM }
        val matrix = mockk<MatrixComposerDraftStore>()
        var persisted: ComposerDraft? = null
        var failClear = false
        var authority: LifecycleOperation? = null
        var allowConsumption = true
        var afterQuery: suspend () -> Unit = {}
        val queried = mutableListOf<String>()
        private val fake = FakeAppOperationalStateCoordinator()
        val lifecycle = object : AppOperationalStateCoordinator by fake {
            override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit) {
                // Match the production PendingLifecycleDraftStart journal admission contract.
                require(request.lifecycleId == FullAppLifecycleId.LC_14 && request.trigger == "draft_change")
                fake.performLocalDraft(request, block)
            }
            override suspend fun operation(operationId: String): LifecycleOperation {
                queried += operationId
                afterQuery()
                return checkNotNull(authority) { "authority outcome unknown" }
            }
            override suspend fun validateLocalDraftRecovery(operationId: String, accountIdHash: String, matrixDeviceIdHash: String, roomIdHash: String) = Unit
            override suspend fun recoverLocalDraftApplied(fact: LifecycleDraftApplied): Boolean = true
        }
        init {
            coEvery { matrix.loadDraft(ROOM, null) } coAnswers { persisted }
            coEvery { matrix.updateDraft(ROOM, null, any()) } coAnswers {
                check(!(failClear && thirdArg<ComposerDraft?>() == null)) { "process death while clearing Matrix draft" }
                persisted = thirdArg()
            }
        }
        fun store() = ProtectedCaptionDraftStore(context, files, client, room, scope, lifecycle)
        fun step() = LifecycleStep("matrix-send-original", LifecycleComponent.MATRIX, true, LifecycleStepStatus.COMPLETE, 1,
            authorityRevision = "matrix-event-query:" + "a".repeat(64), receiptReference = "matrix-event-query:" + "b".repeat(64), updatedAtEpochMillis = 2)
        fun registeredStep() = step().copy(authorityRevision = "matrix-storage:v1", resultCode = "MATRIX_EVENT_VERIFIED",
            resultReference = "e".repeat(64), receiptReference = "matrix-event:" + "a".repeat(24))
        fun confirmed() = LifecycleOperation(operationId = SEND, lifecycleId = FullAppLifecycleId.LC_13, idempotencyKey = SEND,
            subjects = LifecycleSubjectHashes(accountIdHash = hash(ACCOUNT), matrixDeviceIdHash = hash(DEVICE), roomIdHash = hash(ROOM.value)),
            status = LifecycleOperationStatus.WAITING_EXTERNAL, currentPhase = LifecyclePhase.EXTERNAL_COMMITTED, steps = listOf(step()),
            deadlineEpochMillis = 100, createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
    }

    companion object {
        private const val ACCOUNT = "@bug041:test"
        private const val DEVICE = "BUG041_SYNTHETIC"
        private val ROOM = RoomId("!bug041-synthetic:test")
        private val TEXT = ComposerDraft("synthetic original draft", null, ComposerDraftType.NewMessage)
        private val SEND = "lc13-send-" + "d".repeat(64)
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
