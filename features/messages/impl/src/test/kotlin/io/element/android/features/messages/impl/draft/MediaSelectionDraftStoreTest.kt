/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.draft

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.preview.imageeditor.ImageAnnotation
import io.element.android.features.messages.impl.attachments.preview.imageeditor.ImagePoint
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSelectionDraftStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = InMemorySensitiveFileStore()
    private val client = FakeMatrixClient()
    private val leases = object : SecureMediaLeaseManager {
        override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("No lease expected in this test")
    }
    private val prefs get() = context.getSharedPreferences("encrypted_media_editor_drafts_v1", Context.MODE_PRIVATE)
    private val media = Attachment.Media(
        localMedia = LocalMedia(Uri.parse("content://private/photos/123"), anImageMediaInfo(), "operation-1", "room-hash"),
        sendAsFile = false,
        videoStartMs = 1250,
        videoEndMs = 5500,
        muteAudio = true,
    )
    private val draft = MediaSelectionDraft(
        attachments = listOf(media),
        selected = listOf(0),
        focused = 0,
        edits = mapOf(0 to AttachmentImageEdits(annotations = listOf(ImageAnnotation.Label(ImagePoint(.3f, .4f), "Private label", -1, .04f)))),
        pending = null,
        originalPhotos = true,
        videoQuality = VideoCompressionPreset.HIGH,
        resources = emptyMap(),
        burnAfterRead = true,
        inFlight = listOf(0),
    )

    @Test fun `disposed preview completion uses real durable checkpoint time and respects original deadline`() = runTest {
        for (appliedAt in listOf(1400L, 2100L)) {
            val uploadId = "original-upload-$appliedAt"
            val draftId = "original-draft-$appliedAt"
            val roomHash = "c".repeat(64)
            val originalMedia = media.copy(localMedia = media.localMedia.copy(lifecycleOperationId = uploadId, lifecycleRoomIdHash = roomHash))
            val store = MediaSelectionDraftStore(context, client, files, leases, backgroundScope)
            store.save(draftId, draft.copy(attachments = listOf(originalMedia), sources = listOf(originalMedia), burnAfterRead = false))
            val fact = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn",
                localCleanupAtEpochMillis = 1100, uploadOperationId = uploadId)
            val original = io.element.android.security.dataprotection.api.LifecycleOperation(
                operationId = uploadId, lifecycleId = io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_16, idempotencyKey = uploadId,
                subjects = io.element.android.security.dataprotection.api.LifecycleSubjectHashes(
                    accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64), roomIdHash = roomHash),
                status = io.element.android.security.dataprotection.api.LifecycleOperationStatus.RUNNING,
                currentPhase = io.element.android.security.dataprotection.api.LifecyclePhase.PROJECTED,
                steps = listOf(io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE,
                    io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_FRONTEND).map {
                    io.element.android.security.dataprotection.api.LifecycleStep("$uploadId:${it.name}", it,
                        status = io.element.android.security.dataprotection.api.LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = 1000)
                }, deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 1500,
            )
            val coordinator = io.mockk.mockk<io.element.android.security.dataprotection.api.AppOperationalStateCoordinator>(relaxed = true)
            io.mockk.coEvery { coordinator.operation(uploadId) } returns original
            val oldPreview = kotlinx.coroutines.Job()
            oldPreview.cancel()
            val reopened = MediaSelectionDraftStore(context, client, files, leases, backgroundScope)
            val retained = reopened.recordOriginalSubmission(draftId, 0, fact)
            val completed = io.element.android.features.messages.impl.attachments.preview.recoverOriginalMediaUploadCompletion(
                coordinator, uploadId, "a".repeat(64), "b".repeat(64), roomHash, retained,
                applyCheckpoint = { reopened.applyOriginalSubmission(draftId, 0, retained, now = { appliedAt }) }, now = { 1900 })
            assertThat(oldPreview.isCancelled).isTrue()
            assertThat(completed).isEqualTo(appliedAt <= 2000)
            assertThat(store.load(draftId)!!.submissions[0]?.clientAppliedAtEpochMillis).isEqualTo(appliedAt)
            io.mockk.coVerify(exactly = if (appliedAt <= 2000) 1 else 0) { coordinator.persistClientReceipts(any()) }
            io.mockk.coVerify(exactly = 0) { coordinator.start(any()) }
        }
    }

    @Test fun `reopened original store applies exact accepted checkpoint independently of old preview`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, backgroundScope)
        store.save("operation-1", draft.copy(burnAfterRead = false))
        val fact = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn",
            localCleanupAtEpochMillis = 1100, uploadOperationId = "operation-1")
        val reopened = MediaSelectionDraftStore(context, client, files, leases, backgroundScope)
        val retained = reopened.recordOriginalSubmission("operation-1", 0, fact)
        assertThat(retained.clientAppliedAtEpochMillis).isNull()
        val applied = reopened.applyOriginalSubmission("operation-1", 0, retained, now = { 1400 })
        assertThat(applied.clientAppliedAtEpochMillis).isEqualTo(1400L)
        val retry = reopened.applyOriginalSubmission("operation-1", 0, retained, now = { error("Must retain actual first time") })
        assertThat(retry).isEqualTo(applied)
        val durable = store.load("operation-1")!!
        assertThat(durable.committed).containsExactly(0)
        assertThat(durable.inFlight).isEmpty()
        assertThat(durable.submissions[0]).isEqualTo(applied)
        assertThat(prefs.all.toString()).doesNotContain("original-txn")
    }

    @Test fun `original cancellation and foreign producer are rejected by independent checkpoint`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, backgroundScope)
        store.save("operation-1", draft.copy(burnAfterRead = false, cancellationRequested = listOf(0)))
        val fact = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn",
            localCleanupAtEpochMillis = 1100, uploadOperationId = "operation-1")
        assertThat(runCatching { store.applyOriginalSubmission("operation-1", 0, fact) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { store.recordOriginalSubmission("operation-1", 0, fact.copy(uploadOperationId = "foreign")) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.load("operation-1")!!.submissions[0]?.clientAppliedAtEpochMillis).isNull()
    }

    @Test fun `original commit cleanup and client applied times survive reopen and stale snapshots`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val accepted = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn",
            localCleanupAtEpochMillis = 1100, clientAppliedAtEpochMillis = 1400, uploadOperationId = "operation-1")
        store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted)))
        store.save("operation-1", draft)
        val reopened = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(reopened.submissions[0]).isEqualTo(accepted)
        assertThat(reopened.committed).containsExactly(0)
        assertThat(reopened.inFlight).isEmpty()
        val index = prefs.all.toString()
        assertThat(index).doesNotContain("original-txn")
        assertThat(index).doesNotContain("operation-1")
    }

    @Test fun `late original cleanup metadata merges without downgrading accepted phase`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val accepted = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn")
        store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted)))
        store.save("operation-1", draft.copy(submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN,
            ".yizhe-original-content.yzm", transactionId = "original-txn", localCleanupAtEpochMillis = 1100,
            uploadOperationId = "operation-1"))))
        val reopened = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(reopened.submissions[0]).isEqualTo(accepted.copy(localCleanupAtEpochMillis = 1100, uploadOperationId = "operation-1"))
    }


    @Test
    fun `stale autosave cannot restore a retired editor resource`() = runTest {
        val vault = io.element.android.security.securestorage.api.deriveAccountVaultId(client.sessionId.value, client.deviceId.value)
        val reference = files.write(io.element.android.security.securestorage.api.SecureObjectWriteRequest(
            objectId = io.element.android.security.securestorage.api.SecureObjectId("cancelled-editor-object"),
            vaultId = vault,
            purpose = io.element.android.security.securestorage.api.SecureObjectPurpose.EDITING_STAGING,
            associatedData = "operation-1:room-hash".encodeToByteArray(),
            expectedPlaintextLength = 3,
        )) { it.write(byteArrayOf(1, 2, 3)) }
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val original = draft.copy(inFlight = emptyList(), resources = mapOf(0 to reference))
        store.save("operation-1", original)
        assertThat(files.delete(reference)).isTrue()
        store.save("operation-1", original.copy(discarded = listOf(0), cancellationRequested = listOf(0)))
        store.save("operation-1", original)
        val restored = store.load("operation-1")!!
        assertThat(restored.resources).isEmpty()
        assertThat(restored.discarded).containsExactly(0)
        assertThat(restored.committed).isEmpty()
        assertThat(restored.sources).isEqualTo(draft.sources)
    }

    @Test
    fun `cancel intent survives stale autosave without becoming sent or retryable`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(cancellationRequested = listOf(0),
            submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN))))
        store.save("operation-1", draft)
        val restored = store.load("operation-1")!!
        assertThat(restored.cancellationRequested).containsExactly(0)
        assertThat(restored.committed).isEmpty()
        assertThat(restored.selected).isEmpty()
        assertThat(restored.inFlight).containsExactly(0)
        assertThat(restored.sources).isEqualTo(draft.sources)
        assertThat(runCatching { store.clear("operation-1") }.isFailure).isTrue()
        assertThat(store.load("operation-1")).isNotNull()
    }

    @Test
    fun `acknowledged discard is durable and separate from committed`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(inFlight = emptyList(), discarded = listOf(0), cancellationRequested = listOf(0)))
        val restored = store.load("operation-1")!!
        assertThat(restored.discarded).containsExactly(0)
        assertThat(restored.committed).isEmpty()
        assertThat(restored.selected).isEmpty()
    }

    @Test
    fun `pre SDK failure restores original selection and operation after reopening`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.PRE_SDK))))
        val recovered = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(recovered.inFlight).isEmpty()
        assertThat(recovered.selected).containsExactly(0)
        assertThat(recovered.sources).isEqualTo(draft.sources)
        assertThat(recovered.attachments).isEqualTo(draft.attachments)
    }

    @Test
    fun `unknown SDK outcome stays locked and a stale autosave cannot downgrade it`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val unknown = MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, ".yizhe-original-content.yzm")
        store.save("operation-1", draft.copy(submissions = mapOf(0 to unknown)))
        store.save("operation-1", draft.copy(submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.PRE_SDK))))
        val recovered = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(recovered.inFlight).containsExactly(0)
        assertThat(recovered.submissions[0]).isEqualTo(unknown)
        assertThat(recovered.sources).isEqualTo(draft.sources)
    }

    @Test
    fun `accepted Matrix event survives missing presenter checkpoint and does not select again`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val accepted = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn")
        store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted)))
        store.save("operation-1", draft) // Delayed Compose snapshot before accepted callback.
        val recovered = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(recovered.committed).containsExactly(0)
        assertThat(recovered.inFlight).isEmpty()
        assertThat(recovered.selected).isEmpty()
        assertThat(recovered.submissions[0]).isEqualTo(accepted)
    }

    @Test
    fun `conflicting event or transport never replaces the original submission`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val accepted = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$accepted", "original-txn")
        store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted)))
        assertThat(runCatching { store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted.copy(eventId = "\$other")))) }.isFailure).isTrue()
        assertThat(runCatching { store.save("operation-1", draft.copy(submissions = mapOf(0 to accepted.copy(transportName = ".yizhe-other.yzm")))) }.isFailure).isTrue()
        assertThat(store.load("operation-1")!!.submissions[0]).isEqualTo(accepted)
    }

    @Test
    fun `legacy inFlight without submission evidence remains unknown`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        val recovered = store.load("operation-1")!!
        assertThat(recovered.inFlight).containsExactly(0)
        assertThat(recovered.committed).isEmpty()
        assertThat(recovered.submissions).isEmpty()
    }

    @Test
    fun `prepared source operation cannot be replaced by a later gallery snapshot`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN))))
        val replacement = media.copy(localMedia = media.localMedia.copy(lifecycleOperationId = "other-operation"))
        assertThat(runCatching { store.save("operation-1", draft.copy(attachments = listOf(replacement), sources = listOf(replacement))) }.isFailure).isTrue()
        assertThat(store.load("operation-1")!!.sources).isEqualTo(draft.sources)
    }

    @Before
    fun clearIndex() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `restores original selection edit choices and uncertain sends from the vault`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        val recovered = MediaSelectionDraftStore(context, client, files, leases, this).load("operation-1")!!
        assertThat(recovered).isEqualTo(draft)
        assertThat((recovered.attachments.single() as Attachment.Media).muteAudio).isTrue()
    }

    @Test
    fun `legacy cross-item corruption is discarded before it can restore or send`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val second = media.copy(localMedia = media.localMedia.copy(lifecycleOperationId = "operation-2"))
        val invalid = draft.copy(attachments = listOf(second, second), sources = listOf(media, second), selected = listOf(0, 1))
        store.save("operation-1", invalid)
        assertThat(store.latestSources("room-hash", true)).isNull()
        assertThat(store.load("operation-1")).isNull()
        assertThat(prefs.all).isEmpty()
    }

    @Test
    fun `preferences contain only an opaque reference without filenames uris or annotation text`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        val index = prefs.all.toString()
        assertThat(index).doesNotContain("Private label")
        assertThat(index).doesNotContain("content://private")
        assertThat(index).doesNotContain(media.localMedia.info.filename)
        assertThat(index).doesNotContain("room-hash")
    }

    @Test
    fun `resume cannot change the room or downgrade the burn after read mode`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        assertThat(store.latestSources("other-room", true)).isNull()
        assertThat(store.latestSources("room-hash", false)).isNull()
        assertThat(store.latestSources("room-hash", true)).containsExactly(media)
    }

    @Test
    fun `another account cannot discover or restore the draft`() = runTest {
        MediaSelectionDraftStore(context, client, files, leases, this).save("operation-1", draft)
        val other = MediaSelectionDraftStore(context, FakeMatrixClient(sessionId = SessionId("@other:example.org")), files, leases, this)
        assertThat(other.load("operation-1")).isNull()
        assertThat(other.latestSources("room-hash", true)).isNull()
    }

    @Test
    fun `tampered associated data fails closed`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        val entry = prefs.all.entries.single()
        val modified = JSONObject(entry.value as String).put("aad", "0".repeat(64))
        prefs.edit().putString(entry.key, modified.toString()).commit()
        assertThat(runCatching { store.load("operation-1") }.isFailure).isTrue()
    }

    @Test
    fun `opening gallery during a live transfer cannot resume the same outgoing batch`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft)
        store.beginTransfer("operation-1")
        assertThat(runCatching { store.beginTransfer("operation-1") }.isFailure).isTrue()
        assertThat(store.latestSources("room-hash", true)).isNull()
        store.finishTransfer("operation-1")
        assertThat(store.latestSources("room-hash", true)).isNotNull()
    }

    @Test
    fun `edited but unselected image remains recoverable without becoming selected`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(selected = emptyList(), inFlight = emptyList()))
        assertThat(store.latestSources("room-hash", true)).isNotNull()
        assertThat(store.load("operation-1")!!.selected).isEmpty()
    }

    @Test
    fun `unselected unknown draft remains discoverable until original event acceptance`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        val unselected = draft.copy(selected = emptyList(), edits = emptyMap(), pending = null)
        store.save("operation-1", unselected)
        assertThat(store.latestSources("room-hash", true)).containsExactly(media)
        val unknown = MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, ".yizhe-original-content.yzm")
        store.save("operation-1", unselected.copy(submissions = mapOf(0 to unknown)))
        val reopened = MediaSelectionDraftStore(context, client, files, leases, this)
        assertThat(reopened.latestSources("room-hash", true)).containsExactly(media)
        assertThat(reopened.load("operation-1")!!.selected).isEmpty()
        assertThat(reopened.load("operation-1")!!.inFlight).containsExactly(0)
        store.save("operation-1", unselected.copy(submissions = mapOf(0 to unknown.copy(
            phase = MediaSubmissionPhase.ACCEPTED, eventId = "\$original-event"))))
        assertThat(reopened.latestSources("room-hash", true)).isNull()
    }

    @Test
    fun `legacy committed marker without acceptance remains discoverable and locked`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(selected = emptyList(), edits = emptyMap(), inFlight = emptyList(), committed = listOf(0)))
        assertThat(store.latestSources("room-hash", true)).containsExactly(media)
        val restored = store.load("operation-1")!!
        assertThat(restored.committed).containsExactly(0)
        assertThat(restored.selected).isEmpty()
        assertThat(restored.hasUnknownMediaSubmission()).isTrue()
        assertThat(restored.submissions).isEmpty()
        assertThat(runCatching { store.clear("operation-1") }.isFailure).isTrue()
        val replaced = media.copy(localMedia = media.localMedia.copy(uri = android.net.Uri.parse("file:///another-original.jpg")))
        assertThat(runCatching { store.save("operation-1", restored.copy(sources = listOf(replaced), attachments = listOf(replaced))) }.isFailure).isTrue()
        assertThat(store.load("operation-1")!!.sources).containsExactly(media)
    }

    @Test
    fun `clear removes the discoverable draft`() = runTest {
        val store = MediaSelectionDraftStore(context, client, files, leases, this)
        store.save("operation-1", draft.copy(inFlight = emptyList()))
        store.clear("operation-1")
        assertThat(store.load("operation-1")).isNull()
        assertThat(prefs.all).isEmpty()
    }
    @Test
    fun `expired draft cleanup occurs after the authenticated read callback`() = runTest {
        var reading = false
        val guardedFiles = object : io.element.android.security.securestorage.api.SensitiveFileStore by files {
            override suspend fun <T> read(
                reference: io.element.android.security.securestorage.api.SecureObjectReference,
                readPlaintext: suspend (java.io.InputStream) -> T,
            ): T = files.read(reference) { input ->
                val expired = JSONObject(input.readBytes().decodeToString()).put("expires", 0).toString().toByteArray()
                reading = true
                try {
                    readPlaintext(java.io.ByteArrayInputStream(expired))
                } finally {
                    reading = false
                    expired.fill(0)
                }
            }
            override suspend fun delete(reference: io.element.android.security.securestorage.api.SecureObjectReference): Boolean {
                check(!reading) { "Encrypted file store operations are not reentrant" }
                return files.delete(reference)
            }
        }
        val store = MediaSelectionDraftStore(context, client, guardedFiles, leases, this)
        store.save("operation-1", draft.copy(inFlight = emptyList()))
        assertThat(store.latestSources("room-hash", true)).isNull()
        assertThat(prefs.all).isEmpty()
    }

    @Test
    fun `expired uncertain original draft is preserved instead of erased during recovery`() = runTest {
        val expiredFiles = object : io.element.android.security.securestorage.api.SensitiveFileStore by files {
            override suspend fun <T> read(
                reference: io.element.android.security.securestorage.api.SecureObjectReference,
                readPlaintext: suspend (java.io.InputStream) -> T,
            ): T = files.read(reference) { input ->
                val expired = JSONObject(input.readBytes().decodeToString()).put("expires", 0).toString().toByteArray()
                try { readPlaintext(java.io.ByteArrayInputStream(expired)) } finally { expired.fill(0) }
            }
        }
        val store = MediaSelectionDraftStore(context, client, expiredFiles, leases, this)
        store.save("operation-1", draft)
        assertThat(store.load("operation-1")!!.inFlight).containsExactly(0)
        assertThat(prefs.all).isNotEmpty()
    }
}
