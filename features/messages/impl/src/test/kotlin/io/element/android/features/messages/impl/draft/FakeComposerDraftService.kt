/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.draft

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft

class FakeComposerDraftService : ComposerDraftService {
    override suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String) = updateDraft(roomId, null, draft, false)
    var textDraftVersionLambda: suspend (RoomId) -> String? = { null }
    override suspend fun textDraftVersion(roomId: RoomId): String? = textDraftVersionLambda(roomId)
    override suspend fun hasPendingTextSendDraft(roomId: RoomId): Boolean = false
    var reconcileSentTextDraftLambda: suspend (RoomId) -> ConsumedTextDraft? = { null }
    override suspend fun reconcileSentTextDraft(roomId: RoomId, canConsume: () -> Boolean): ConsumedTextDraft? =
        reconcileSentTextDraftLambda(roomId).takeIf { canConsume() }

    var preparePersistentDraftWriteLambda: suspend (RoomId, ThreadId?) -> Unit = { _, _ -> }
    override suspend fun preparePersistentDraftWrite(roomId: RoomId, threadRoot: ThreadId?) =
        preparePersistentDraftWriteLambda(roomId, threadRoot)

    var captureProtectedCaptionDraftVersionLambda: (RoomId, ThreadId?) -> String? = { _, _ -> null }
    override suspend fun captureProtectedCaptionDraftVersion(roomId: RoomId, threadRoot: ThreadId?): String? =
        captureProtectedCaptionDraftVersionLambda(roomId, threadRoot)

    var clearProtectedCaptionDraftIfVersionLambda: suspend (RoomId, ThreadId?, String?) -> Boolean = { _, _, _ -> true }
    override suspend fun clearProtectedCaptionDraftIfVersion(roomId: RoomId, threadRoot: ThreadId?, version: String?): Boolean =
        clearProtectedCaptionDraftIfVersionLambda(roomId, threadRoot, version)

    var loadDraftLambda: (RoomId, ThreadId?, Boolean) -> ComposerDraft? = { _, _, _ -> null }
    override suspend fun loadDraft(
        roomId: RoomId,
        threadRoot: ThreadId?,
        isVolatile: Boolean
    ): ComposerDraft? = loadDraftLambda(roomId, threadRoot, isVolatile)

    var saveDraftLambda: (RoomId, ThreadId?, ComposerDraft?, Boolean) -> Unit = { _, _, _, _ -> }
    override suspend fun updateDraft(
        roomId: RoomId,
        threadRoot: ThreadId?,
        draft: ComposerDraft?,
        isVolatile: Boolean
    ) = saveDraftLambda(roomId, threadRoot, draft, isVolatile)
}
