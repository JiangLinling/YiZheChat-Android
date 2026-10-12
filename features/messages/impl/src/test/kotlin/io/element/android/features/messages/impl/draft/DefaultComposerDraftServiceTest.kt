/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.draft

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultComposerDraftServiceTest {
    private val roomId = RoomId("!room:example.org")
    private val captionDraft = ComposerDraft(
        plainText = "caption",
        htmlText = "<strong>caption</strong>",
        draftType = ComposerDraftType.EditCaption(EventId("\$event")),
    )
    private val volatileStore = mockk<VolatileComposerDraftStore>(relaxed = true)
    private val matrixStore = mockk<MatrixComposerDraftStore>(relaxed = true)
    private val captionStore = mockk<ProtectedCaptionDraftStore>(relaxed = true)
    private val sut = DefaultComposerDraftService(volatileStore, matrixStore, captionStore)

    @Test
    fun `persistent caption draft never crosses the Matrix SDK draft store`() = runTest {
        sut.updateDraft(roomId, null, captionDraft, isVolatile = false)

        coVerify(exactly = 1) { captionStore.updateComposerDraft(roomId, null, captionDraft, matrixStore) }
        coVerify(exactly = 0) { matrixStore.updateDraft(roomId, null, captionDraft) }
    }

    @Test
    fun `protected caption draft is restored before ordinary Matrix draft`() = runTest {
        coEvery { captionStore.loadComposerDraft(roomId, null, matrixStore) } returns captionDraft

        assertThat(sut.loadDraft(roomId, null, isVolatile = false)).isEqualTo(captionDraft)

        coVerify(exactly = 0) { matrixStore.loadDraft(roomId, null) }
    }

    @Test
    fun `ordinary draft enters the same recoverable transition as caption draft`() = runTest {
        val ordinary = ComposerDraft("message", null, ComposerDraftType.NewMessage)

        sut.updateDraft(roomId, null, ordinary, isVolatile = false)

        coVerify(exactly = 1) { captionStore.updateComposerDraft(roomId, null, ordinary, matrixStore) }
        coVerify(exactly = 0) { matrixStore.updateDraft(roomId, null, any()) }
    }
}
