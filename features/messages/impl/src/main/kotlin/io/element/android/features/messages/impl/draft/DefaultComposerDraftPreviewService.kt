/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.draft

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.api.draft.ComposerDraftPreviewService
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultComposerDraftPreviewService(
    private val client: MatrixClient,
) : ComposerDraftPreviewService {
    private val mutablePreviews = MutableStateFlow<Map<RoomId, String>>(emptyMap())
    private val loadedRoomIds = mutableSetOf<RoomId>()
    private val stateLock = Any()
    private val revisions = mutableMapOf<RoomId, Long>()

    override val previews: StateFlow<Map<RoomId, String>> = mutablePreviews.asStateFlow()

    override suspend fun refresh(roomIds: Collection<RoomId>) {
        val idsToLoad = synchronized(stateLock) {
            roomIds.filter { loadedRoomIds.add(it) }.map { it to (revisions[it] ?: 0L) }
        }
        if (idsToLoad.isEmpty()) return
        val loaded = coroutineScope {
            idsToLoad.map { (roomId, revision) ->
                async {
                    val draft = client.getRoom(roomId)?.use { room ->
                        room.loadComposerDraft(threadRoot = null).getOrNull()
                    }
                    Triple(roomId, revision, draft.previewText())
                }
            }.awaitAll()
        }
        synchronized(stateLock) {
            mutablePreviews.update { current ->
                loaded.fold(current) { previews, (roomId, revision, preview) ->
                    if ((revisions[roomId] ?: 0L) != revision) previews
                    else if (preview == null) previews - roomId else previews + (roomId to preview)
                }
            }
        }
    }

    override fun update(roomId: RoomId, draft: ComposerDraft?) {
        val preview = draft.previewText()
        synchronized(stateLock) {
            // A committed edit/clear supersedes any earlier asynchronous room-list load.
            revisions[roomId] = (revisions[roomId] ?: 0L) + 1L
            loadedRoomIds.add(roomId)
            mutablePreviews.update { current ->
                if (preview == null) current - roomId else current + (roomId to preview)
            }
        }
    }

    private fun ComposerDraft?.previewText(): String? = this
        ?.plainText
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(120)
        ?.takeIf { it.isNotEmpty() }
}
