/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.draft

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft

@ContributesBinding(RoomScope::class)
class DefaultComposerDraftService(
    private val volatileComposerDraftStore: VolatileComposerDraftStore,
    private val matrixComposerDraftStore: MatrixComposerDraftStore,
    private val protectedCaptionDraftStore: ProtectedCaptionDraftStore,
) : ComposerDraftService {
    override suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String) =
        protectedCaptionDraftStore.bindTextSendDraft(roomId, draft, operationId, matrixComposerDraftStore)

    override suspend fun textDraftVersion(roomId: RoomId): String? = protectedCaptionDraftStore.textDraftVersion(roomId)
    override suspend fun hasPendingTextSendDraft(roomId: RoomId): Boolean = protectedCaptionDraftStore.hasPendingTextSendDraft(roomId)

    override suspend fun reconcileSentTextDraft(roomId: RoomId, canConsume: () -> Boolean): ConsumedTextDraft? =
        protectedCaptionDraftStore.reconcileSentTextDraft(roomId, matrixComposerDraftStore, canConsume)

    override suspend fun preparePersistentDraftWrite(roomId: RoomId, threadRoot: ThreadId?) =
        protectedCaptionDraftStore.prepareComposerDraftWrite(roomId, threadRoot, matrixComposerDraftStore)

    override suspend fun captureProtectedCaptionDraftVersion(roomId: RoomId, threadRoot: ThreadId?): String? =
        protectedCaptionDraftStore.captureComposerDraftVersion(roomId, threadRoot, matrixComposerDraftStore)

    override suspend fun clearProtectedCaptionDraftIfVersion(roomId: RoomId, threadRoot: ThreadId?, version: String?): Boolean =
        protectedCaptionDraftStore.clearComposerDraftIfVersion(roomId, threadRoot, version, matrixComposerDraftStore)

    override suspend fun loadDraft(roomId: RoomId, threadRoot: ThreadId?, isVolatile: Boolean): ComposerDraft? {
        if (isVolatile) return volatileComposerDraftStore.loadDraft(roomId, threadRoot)
        return protectedCaptionDraftStore.loadComposerDraft(roomId, threadRoot, matrixComposerDraftStore)
    }

    override suspend fun updateDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?, isVolatile: Boolean) {
        if (isVolatile) {
            volatileComposerDraftStore.updateDraft(roomId, threadRoot, draft)
        } else {
            protectedCaptionDraftStore.updateComposerDraft(roomId, threadRoot, draft, matrixComposerDraftStore)
        }
    }
}
