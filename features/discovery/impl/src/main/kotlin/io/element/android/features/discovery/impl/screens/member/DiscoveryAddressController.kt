/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal class DiscoveryAddressController(private val port: DiscoveryAddressPort) {
    private val mutable = MutableStateFlow(AddressState())
    val state = mutable.asStateFlow()
    private val busy = Mutex()
    private var closed = false

    suspend fun load() = exclusively {
        if (state.value.phase !in setOf(AddressPhase.LOADING, AddressPhase.READY, AddressPhase.SAVED, AddressPhase.FAILED)) return@exclusively
        val savedId = state.value.savedId
        mutable.value = AddressState(savedId = savedId)
        try {
            if (savedId != null) { readBook(rejected = false); return@exclusively }
            when (val recovered = port.recover()) {
                is AddressOutcome.Saved -> saved(recovered)
                AddressOutcome.Pending -> uncertain()
                AddressOutcome.Clear, AddressOutcome.Rejected -> readBook(recovered == AddressOutcome.Rejected)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = AddressState(phase = AddressPhase.FAILED, savedId = savedId) }
    }

    fun begin(id: Long? = null) {
        if (closed || state.value.phase != AddressPhase.READY) return
        val book = state.value.book ?: return
        val draft = if (id == null) MemberAddressDraft() else book.rows.firstOrNull { it.id == id }?.draft ?: return
        mutable.value = state.value.copy(phase = AddressPhase.EDITING, editingId = id, draft = draft, paste = "", savedId = null,
            showIssues = false, rejected = false)
    }
    fun edit(change: (MemberAddressDraft) -> MemberAddressDraft) {
        if (closed || state.value.phase != AddressPhase.EDITING) return
        val draft = state.value.draft ?: return
        mutable.value = state.value.copy(draft = change(draft), rejected = false)
    }
    fun paste(value: String) {
        if (!closed && state.value.phase == AddressPhase.EDITING) mutable.value = state.value.copy(paste = value.take(2000))
    }
    fun parse() { edit { parseAddressPaste(state.value.paste, it) } }
    fun useName(personId: Long) {
        val suggestion = state.value.book?.names?.firstOrNull { it.personId == personId } ?: return
        edit { it.copy(name = suggestion.name, phone = if (it.phone.isBlank()) suggestion.phone.orEmpty() else it.phone) }
    }
    fun cancelEdit() {
        if (!closed && state.value.phase == AddressPhase.EDITING) mutable.value = AddressState(phase = AddressPhase.READY, book = state.value.book)
    }
    fun canChoose(id: Long): Boolean = !closed && state.value.phase == AddressPhase.READY && state.value.book?.rows?.any { it.id == id } == true

    suspend fun save() = exclusively {
        val current = state.value
        if (current.phase != AddressPhase.EDITING) return@exclusively
        val draft = current.draft?.normalized() ?: return@exclusively
        if (draft.issues().isNotEmpty()) { mutable.value = current.copy(showIssues = true); return@exclusively }
        mutable.value = current.copy(phase = AddressPhase.SAVING, draft = draft, rejected = false)
        try {
            when (val result = port.save(current.editingId, draft)) {
                is AddressOutcome.Saved -> saved(result)
                AddressOutcome.Rejected -> reject()
                AddressOutcome.Pending, AddressOutcome.Clear -> uncertain()
            }
        } catch (cancelled: CancellationException) { uncertain(); throw cancelled
        } catch (_: Exception) { uncertain() }
    }
    suspend fun recover() = exclusively {
        if (state.value.phase != AddressPhase.UNCERTAIN) return@exclusively
        try {
            when (val result = port.recover()) {
                is AddressOutcome.Saved -> saved(result)
                AddressOutcome.Rejected -> if (state.value.draft != null) reject() else readBook(rejected = true)
                AddressOutcome.Pending, AddressOutcome.Clear -> uncertain()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { uncertain() }
    }
    fun close() { closed = true; mutable.value = AddressState() }
    private suspend fun readBook(rejected: Boolean) {
        val book = port.load()
        currentCoroutineContext().ensureActive()
        if (!closed) mutable.value = AddressState(phase = AddressPhase.READY, book = book, rejected = rejected, savedId = state.value.savedId)
    }
    private suspend fun saved(result: AddressOutcome.Saved) {
        currentCoroutineContext().ensureActive()
        state.value.book?.let { previous -> result.book?.let { require(it.ownerId == previous.ownerId) } }
        state.value.editingId?.let { require(result.addressId == it) }
        if (!closed) mutable.value = AddressState(phase = if (result.book != null) AddressPhase.READY else AddressPhase.SAVED,
            book = result.book, savedId = result.addressId)
    }
    private fun reject() { if (!closed) mutable.value = state.value.copy(phase = AddressPhase.EDITING, rejected = true) }
    private fun uncertain() { if (!closed) mutable.value = state.value.copy(phase = AddressPhase.UNCERTAIN) }
    private suspend fun exclusively(block: suspend () -> Unit) {
        if (closed || !busy.tryLock()) return
        try { if (!closed) block() } finally { busy.unlock() }
    }
}
