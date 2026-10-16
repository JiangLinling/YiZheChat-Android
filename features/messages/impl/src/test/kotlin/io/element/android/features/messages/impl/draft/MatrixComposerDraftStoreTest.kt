package io.element.android.features.messages.impl.draft

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MatrixComposerDraftStoreTest {
    @Test
    fun `FC002 failed load is currently returned as an empty draft`() = runTest {
        val room = FakeBaseRoom(loadComposerDraftLambda = { Result.failure(IllegalStateException("unavailable")) })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val store = MatrixComposerDraftStore(client, DefaultComposerDraftPreviewService(client))

        assertThat(store.loadDraft(A_ROOM_ID, null)).isNull()
    }

    @Test
    fun `failed persistence is surfaced instead of acknowledging a durable draft`() = runTest {
        val error = IllegalStateException("storage unavailable")
        val room = FakeBaseRoom(saveComposerDraftLambda = { Result.failure(error) })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val result = runCatching { MatrixComposerDraftStore(client, DefaultComposerDraftPreviewService(client)).updateDraft(A_ROOM_ID, null, ComposerDraft("unsent", null, ComposerDraftType.NewMessage)) }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(error)
    }

    @Test
    fun `unavailable room cannot acknowledge draft persistence`() = runTest {
        val result = runCatching { MatrixComposerDraftStore(FakeMatrixClient(), DefaultComposerDraftPreviewService(FakeMatrixClient())).updateDraft(A_ROOM_ID, null, null) }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `reading a draft twice does not consume the durable copy`() = runTest {
        val draft = ComposerDraft("unsent", null, ComposerDraftType.NewMessage)
        var retained: ComposerDraft? = draft
        val room = FakeBaseRoom(loadComposerDraftLambda = { Result.success(retained) }, clearComposerDraftLambda = {
            retained = null
            Result.success(Unit)
        })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val store = MatrixComposerDraftStore(client, DefaultComposerDraftPreviewService(client))
        assertThat(store.loadDraft(A_ROOM_ID, null)).isEqualTo(draft)
        assertThat(store.loadDraft(A_ROOM_ID, null)).isEqualTo(draft)
        store.updateDraft(A_ROOM_ID, null, null)
        assertThat(store.loadDraft(A_ROOM_ID, null)).isNull()
    }

    @Test
    fun `committed edit and clear immediately replace room list preview`() = runTest {
        val room = FakeBaseRoom(saveComposerDraftLambda = { Result.success(Unit) }, clearComposerDraftLambda = { Result.success(Unit) })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        previews.update(A_ROOM_ID, ComposerDraft("old", null, ComposerDraftType.NewMessage))
        val store = MatrixComposerDraftStore(client, previews)
        store.updateDraft(A_ROOM_ID, null, ComposerDraft("new", null, ComposerDraftType.NewMessage))
        assertThat(previews.previews.value[A_ROOM_ID]).isEqualTo("new")
        store.updateDraft(A_ROOM_ID, null, null)
        assertThat(previews.previews.value).doesNotContainKey(A_ROOM_ID)
    }

    @Test
    fun `failed save does not publish an uncommitted room list preview`() = runTest {
        val error = IllegalStateException("storage unavailable")
        val room = FakeBaseRoom(saveComposerDraftLambda = { Result.failure(error) })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        previews.update(A_ROOM_ID, ComposerDraft("old", null, ComposerDraftType.NewMessage))
        val result = runCatching { MatrixComposerDraftStore(client, previews).updateDraft(A_ROOM_ID, null, ComposerDraft("new", null, ComposerDraftType.NewMessage)) }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(error)
        assertThat(previews.previews.value[A_ROOM_ID]).isEqualTo("old")
    }

    @Test
    fun `failed clear retains the committed room list preview`() = runTest {
        val error = IllegalStateException("storage unavailable")
        val room = FakeBaseRoom(clearComposerDraftLambda = { Result.failure(error) })
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val previews = DefaultComposerDraftPreviewService(client)
        previews.update(A_ROOM_ID, ComposerDraft("old", null, ComposerDraftType.NewMessage))
        val result = runCatching { MatrixComposerDraftStore(client, previews).updateDraft(A_ROOM_ID, null, null) }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(error)
        assertThat(previews.previews.value[A_ROOM_ID]).isEqualTo("old")
    }
}
