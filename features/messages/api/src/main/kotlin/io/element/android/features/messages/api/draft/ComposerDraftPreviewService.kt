/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.api.draft

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-scoped, in-memory projection of composer drafts for the conversation list.
 * The source of truth remains the encrypted Matrix room state store.
 */
interface ComposerDraftPreviewService {
    val previews: StateFlow<Map<RoomId, String>>

    suspend fun refresh(roomIds: Collection<RoomId>)

    fun update(roomId: RoomId, draft: ComposerDraft?)
}
