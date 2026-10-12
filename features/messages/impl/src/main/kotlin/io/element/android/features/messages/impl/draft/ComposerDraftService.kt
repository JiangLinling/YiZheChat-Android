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

data class ConsumedTextDraft(val version: String, val draft: ComposerDraft)

interface ComposerDraftService {
    /** Bind the exact persisted composer version before clearing the editor or starting transport. */
    suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String)
    suspend fun textDraftVersion(roomId: RoomId): String?
    suspend fun hasPendingTextSendDraft(roomId: RoomId): Boolean
    /** Query the original signed Matrix authority fact, then consume only its current storage version. */
    suspend fun reconcileSentTextDraft(roomId: RoomId, canConsume: () -> Boolean = { true }): ConsumedTextDraft?

    /** Finish an older retained transition before admitting a new persistent draft intent. */
    suspend fun preparePersistentDraftWrite(roomId: RoomId, threadRoot: ThreadId?)
    suspend fun loadDraft(roomId: RoomId, threadRoot: ThreadId?, isVolatile: Boolean): ComposerDraft?
    suspend fun updateDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?, isVolatile: Boolean)
    suspend fun captureProtectedCaptionDraftVersion(roomId: RoomId, threadRoot: ThreadId?): String?
    suspend fun clearProtectedCaptionDraftIfVersion(roomId: RoomId, threadRoot: ThreadId?, version: String?): Boolean
}
