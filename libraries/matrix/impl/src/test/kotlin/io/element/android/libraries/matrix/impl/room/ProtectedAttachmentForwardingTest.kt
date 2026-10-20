/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedAttachmentMetadata
import io.element.android.libraries.matrix.impl.timeline.RustTimeline
import io.element.android.libraries.matrix.test.media.aMediaSource
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.accountIdHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListService
import java.io.File
import java.io.IOException

class ProtectedAttachmentForwardingTest {
    @Test
    fun `audio forward binds metadata to a scoped caption and original pair upload`() = runTest {
        checkForward(unknown = false)
    }

    @Test
    fun `unknown media commit does not destroy shares or report applied forwarding`() = runTest {
        checkForward(unknown = true)
    }

    private suspend fun checkForward(unknown: Boolean) {
        val roomId = RoomId("!forward:test")
        val session = SessionId("@sender:test")
        val device = DeviceId("sender-device")
        val pair = "persisted-pair-operation-0001"
        val uploadId = "forward-upload-" + accountIdHash("$pair:${roomId.value}")
        val captionId = "media-caption-" + accountIdHash("$uploadId:caption")
        val coordinator = FakeAppOperationalStateCoordinator()
        val parent = coordinator.start(lifecycleOperationRequest(FullAppLifecycleId.LC_15, "forward"))
        val nativeRoom = mockk<Room>(relaxed = true)
        val rooms = mockk<RoomListService>()
        every { rooms.room(roomId.value) } returns nativeRoom
        val adapter = mockk<MatrixProtectedContentAdapter>()
        coEvery { adapter.enabled() } returns true
        val factory = mockk<MatrixProtectedContentAdapterFactory>()
        every { factory.create(session, device, roomId, any(), any()) } returns adapter
        val loader = mockk<MatrixMediaLoader>()
        val bytes = "audio".encodeToByteArray()
        coEvery { loader.loadMediaContent(any()) } returns Result.success(bytes)
        val timeline = mockk<RustTimeline>()
        val handler = mockk<MediaUploadHandler>()
        coEvery { handler.await() } returns if (unknown) Result.failure(IOException("unconfirmed native result")) else Result.success(Unit)
        coEvery { timeline.sendForwardedAttachment(any(), any(), any(), any(), any(), any()) } coAnswers {
            val operation = requireNotNull(currentLifecycleOperationContext())
            val caption = requireNotNull(currentCoroutineContext()[MediaCaptionOperationContext])
            assertThat(operation.operationId).isEqualTo(uploadId)
            assertThat(operation.lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
            assertThat(caption.captionOperationId).isEqualTo(captionId)
            assertThat(caption.accountIdHash).isEqualTo(accountIdHash(session.value))
            assertThat(caption.matrixDeviceIdHash).isEqualTo(accountIdHash(device.value))
            assertThat(caption.roomIdHash).isEqualTo(accountIdHash(roomId.value))
            assertThat(requireNotNull(currentCoroutineContext()[MediaSubmissionContext]).originalDeadline()).isEqualTo(parent.deadlineEpochMillis)
            assertThat(arg<ProtectedAttachmentMetadata>(4)).isEqualTo(ProtectedAttachmentMetadata("song.mp3", "audio/mpeg", 5))
            assertThat(firstArg<List<Pair<ByteArray, ContentKind>>>().single().second).isEqualTo(ContentKind.VOICE_MESSAGE)
            Result.success(handler)
        }
        val forwarder = RoomContentForwarder(rooms, session, device, factory, { loader }, File("encrypted-forward-output"),
            coordinator, { timeline })
        val content = aMessageContent(messageType = AudioMessageType("song.mp3", null, null, aMediaSource(),
            AudioInfo(duration = null, size = 5, mimetype = "audio/mpeg"))).copy(isContentProtectionAuthorized = true)
        val result = runCatching {
            withContext(LifecycleOperationContext(parent.operationId, FullAppLifecycleId.LC_15)) {
                forwarder.forwardProtected(content, listOf(roomId), operationId = pair)
            }
        }
        assertThat(result.isSuccess).isEqualTo(!unknown)
        assertThat(coordinator.startedRequests.drop(1).map { it.trigger }).containsExactly("upload", "media_caption").inOrder()
        assertThat(coordinator.startedRequests.all { it.deadlineEpochMillis == parent.deadlineEpochMillis }).isTrue()
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
        if (unknown) assertThat(coordinator.clientReceipts).isEmpty()
        coVerify(exactly = 1) { timeline.sendForwardedAttachment(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.destroyMedia(any(), any()) }
        coVerify(exactly = 0) { adapter.destroyPrepared(any(), any()) }
    }
}
