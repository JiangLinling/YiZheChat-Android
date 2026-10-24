/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** One authorised page lifetime. The port owns durable intent; this controller only owns the transient edit form. */
internal class DiscoveryPersonEditorController(
    private val target: PersonEditTarget,
    private val port: DiscoveryPersonEditorPort,
    private val today: () -> LocalDate,
) {
    private val mutable = MutableStateFlow(PersonEditorState(target))
    val state = mutable.asStateFlow()
    private val busy = Mutex()
    private var closed = false

    fun edit(change: (MemberPersonDraft) -> MemberPersonDraft) {
        if (closed || state.value.phase != PersonEditPhase.EDITING) return
        val draft = state.value.draft ?: return
        mutable.value = state.value.copy(draft = change(draft), notice = null)
    }

    suspend fun load() = exclusively {
        if (state.value.phase !in setOf(PersonEditPhase.LOADING, PersonEditPhase.FAILED)) return@exclusively
        mutable.value = PersonEditorState(target)
        try {
            when (val result = port.recover(target)) {
                is PersonEditOutcome.Saved -> saved(result.result)
                is PersonEditOutcome.SavedRefresh -> refreshSaved(result.locator)
                PersonEditOutcome.Pending -> uncertain()
                PersonEditOutcome.Clear, PersonEditOutcome.Rejected -> readForm(result == PersonEditOutcome.Rejected)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!closed) mutable.value = PersonEditorState(target, phase = PersonEditPhase.FAILED, notice = PersonEditNotice.LOAD_FAILED)
        }
    }

    suspend fun save(useAfterSave: Boolean) = exclusively {
        val current = state.value
        if (current.phase != PersonEditPhase.EDITING) return@exclusively
        val draft = current.draft?.normalized() ?: return@exclusively
        if (draft.issues(target, today()).isNotEmpty()) {
            mutable.value = current.copy(showIssues = true)
            return@exclusively
        }
        mutable.value = current.copy(phase = PersonEditPhase.SAVING, draft = draft, notice = null,
            useAfterSave = useAfterSave && target.kind != PersonEditKind.SELF)
        try {
            when (val result = port.save(target, draft)) {
                is PersonEditOutcome.Saved -> saved(result.result)
                is PersonEditOutcome.SavedRefresh -> refreshSaved(result.locator)
                PersonEditOutcome.Rejected -> rejected()
                PersonEditOutcome.Pending, PersonEditOutcome.Clear -> uncertain()
            }
        } catch (cancelled: CancellationException) { uncertain(); throw cancelled
        } catch (_: Exception) { uncertain() }
    }

    suspend fun recover() = exclusively {
        if (state.value.phase == PersonEditPhase.SAVED_REFRESH) {
            refreshSaved(requireNotNull(state.value.savedLocator))
            return@exclusively
        }
        if (state.value.phase != PersonEditPhase.UNCERTAIN) return@exclusively
        try {
            when (val result = port.recover(target)) {
                is PersonEditOutcome.Saved -> saved(result.result)
                is PersonEditOutcome.SavedRefresh -> refreshSaved(result.locator)
                PersonEditOutcome.Rejected -> {
                    if (state.value.draft != null) rejected() else readForm(rejected = true)
                }
                PersonEditOutcome.Pending, PersonEditOutcome.Clear -> uncertain()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { uncertain() }
    }

    fun close() { closed = true; mutable.value = PersonEditorState(target) }

    /** Only a user acknowledgement may retire a result; loading or recreating the page never does. */
    suspend fun finish(useAfterSave: Boolean = state.value.useAfterSave, onSaved: (PersonEditResult, Boolean) -> Unit) = exclusively {
        val result = state.value.result ?: return@exclusively
        try {
            port.acknowledge(result)
            currentCoroutineContext().ensureActive()
            if (!closed) onSaved(result, useAfterSave && target.kind != PersonEditKind.SELF)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!closed) mutable.value = state.value.copy(notice = PersonEditNotice.CHECK_ORIGINAL)
        }
    }

    private suspend fun refreshSaved(locator: io.element.android.features.discovery.impl.actions.DiscoveryPersonResultLocator) {
        require(locator.target == target)
        if (closed) return
        mutable.value = PersonEditorState(target, phase = PersonEditPhase.SAVED_REFRESH, savedLocator = locator,
            useAfterSave = state.value.useAfterSave)
        try { saved(port.refresh(locator))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!closed) mutable.value = state.value.copy(notice = PersonEditNotice.SAVED_REFRESH_FAILED)
        }
    }

    private suspend fun readForm(rejected: Boolean) {
        val snapshot = port.read(target)
        currentCoroutineContext().ensureActive()
        require(snapshot.target == target)
        if (!closed) mutable.value = PersonEditorState(target, phase = PersonEditPhase.EDITING,
            draft = snapshot.draft, maskedPhone = snapshot.maskedPhone,
            notice = if (rejected) PersonEditNotice.REJECTED else null)
    }
    private suspend fun saved(result: PersonEditResult) {
        currentCoroutineContext().ensureActive()
        require(result.target == target)
        if (!closed) mutable.value = PersonEditorState(target, phase = PersonEditPhase.SAVED,
            result = result, useAfterSave = state.value.useAfterSave)
    }
    private fun rejected() {
        if (!closed) mutable.value = state.value.copy(phase = PersonEditPhase.EDITING, notice = PersonEditNotice.REJECTED, useAfterSave = false)
    }
    private fun uncertain() {
        if (!closed) mutable.value = state.value.copy(phase = PersonEditPhase.UNCERTAIN, notice = PersonEditNotice.CHECK_ORIGINAL)
    }
    private suspend fun exclusively(block: suspend () -> Unit) {
        if (closed || !busy.tryLock()) return
        try { if (!closed) block() } finally { busy.unlock() }
    }
}
