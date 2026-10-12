/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry

/** Called only for inner authenticated operations with the SDK's Matrix sender. */
internal object ProtectedRelayReducer {
    data class State(val entries: List<ProtectedRelayEntry>, val closed: Boolean = false)

    fun append(state: State, itemId: String, actor: UserId, text: String, isConfirmed: Boolean = true): State {
        if (state.closed || !validIdentity(itemId) || !validText(text)) return state
        // Repeated transport/operation replay cannot add another item with the same identity.
        if (state.entries.any { it.id == itemId }) return state
        return state.copy(entries = state.entries + ProtectedRelayEntry(itemId, actor, text, isConfirmed = isConfirmed))
    }

    fun edit(state: State, itemId: String, actor: UserId, expectedRevision: Long, text: String, isConfirmed: Boolean = true): State {
        if (state.closed || !validIdentity(itemId) || !validText(text) || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) return state
        val index = state.entries.indexOfFirst { it.id == itemId }
        if (index < 0) return state
        val original = state.entries[index]
        if (original.author != actor || original.revision != expectedRevision || !original.isConfirmed) return state
        val entries = state.entries.toMutableList()
        entries[index] = original.copy(text = text, revision = expectedRevision+1, isConfirmed = isConfirmed)
        return state.copy(entries = entries)
    }

    fun close(state: State, actor: UserId, initiator: UserId): State =
        if (actor == initiator) state.copy(closed = true) else state

    private fun validIdentity(value: String): Boolean = value.length in 1..512 && value.none(Char::isWhitespace)
    private fun validText(value: String): Boolean = value.isNotBlank() && value.length <= 2_000
}
