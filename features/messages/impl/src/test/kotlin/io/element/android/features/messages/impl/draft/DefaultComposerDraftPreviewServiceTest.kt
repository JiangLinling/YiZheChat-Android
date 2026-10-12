package io.element.android.features.messages.impl.draft

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultComposerDraftPreviewServiceTest {
    @Test
    fun `late initial refresh cannot restore a cleared preview`() = runTest {
        val result = CompletableDeferred<ComposerDraft?>()
        val room = object : BaseRoom by FakeBaseRoom() {
            override suspend fun loadComposerDraft(threadRoot: ThreadId?): Result<ComposerDraft?> = Result.success(result.await())
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        val refresh = launch { previews.refresh(listOf(A_ROOM_ID)) }
        runCurrent()
        previews.update(A_ROOM_ID, null)
        result.complete(ComposerDraft("old", null, ComposerDraftType.NewMessage))
        refresh.join()
        assertThat(previews.previews.value).doesNotContainKey(A_ROOM_ID)
    }

    @Test
    fun `late initial refresh cannot replace a newly committed preview`() = runTest {
        val result = CompletableDeferred<ComposerDraft?>()
        val room = object : BaseRoom by FakeBaseRoom() {
            override suspend fun loadComposerDraft(threadRoot: ThreadId?): Result<ComposerDraft?> = Result.success(result.await())
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        val refresh = launch { previews.refresh(listOf(A_ROOM_ID)) }
        runCurrent()
        previews.update(A_ROOM_ID, ComposerDraft("new", null, ComposerDraftType.NewMessage))
        result.complete(ComposerDraft("old", null, ComposerDraftType.NewMessage))
        refresh.join()
        assertThat(previews.previews.value[A_ROOM_ID]).isEqualTo("new")
    }

    @Test
    fun `committed update before first refresh remains authoritative`() = runTest {
        val room = FakeBaseRoom(loadComposerDraftLambda = { error("Committed preview should not be reloaded") })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        previews.update(A_ROOM_ID, ComposerDraft("new", null, ComposerDraftType.NewMessage))
        previews.refresh(listOf(A_ROOM_ID))
        assertThat(previews.previews.value[A_ROOM_ID]).isEqualTo("new")
    }
}
