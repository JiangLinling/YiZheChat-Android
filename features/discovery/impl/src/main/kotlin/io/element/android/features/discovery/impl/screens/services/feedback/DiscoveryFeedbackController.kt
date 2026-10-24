/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal class DiscoveryFeedbackController(private val target: FeedbackTarget, private val port: DiscoveryFeedbackPort) {
    private val mutable = MutableStateFlow(FeedbackState())
    val state = mutable.asStateFlow()
    private val busy = Mutex()
    private var closed = false

    fun edit(change: (FeedbackDraft) -> FeedbackDraft) {
        val current = state.value
        if (closed || current.phase != FeedbackPhase.EDITING) return
        current.draft?.let { mutable.value = current.copy(draft = change(it), receipt = null, rejected = false) }
    }

    suspend fun load() = exclusively {
        if (state.value.phase !in setOf(FeedbackPhase.LOADING, FeedbackPhase.FAILED, FeedbackPhase.SAVED)) return@exclusively
        val knownReceipt = state.value.receipt
        mutable.value = FeedbackState(receipt = knownReceipt)
        try {
            if (knownReceipt != null) readPage(knownReceipt) else when (val result = port.recover(target)) {
                is FeedbackOutcome.Saved -> saved(result.receipt)
                FeedbackOutcome.Pending -> uncertain()
                FeedbackOutcome.Clear -> readPage()
                is FeedbackOutcome.Rejected -> rejected(result.reason)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = FeedbackState(phase = FeedbackPhase.FAILED, receipt = knownReceipt) }
    }

    suspend fun submit() = exclusively {
        val current = state.value
        val page = current.page ?: return@exclusively
        val draft = current.draft?.normalized() ?: return@exclusively
        if (current.phase != FeedbackPhase.EDITING || !page.canSubmit) return@exclusively
        if (draft.issues().isNotEmpty()) { mutable.value = current.copy(showIssues = true); return@exclusively }
        mutable.value = current.copy(phase = FeedbackPhase.SAVING, draft = draft, rejected = false)
        try {
            when (val result = port.submit(target, page.feedbackId, page.revision, draft)) {
                is FeedbackOutcome.Saved -> saved(result.receipt)
                is FeedbackOutcome.Rejected -> rejected(result.reason)
                FeedbackOutcome.Clear, FeedbackOutcome.Pending -> uncertain()
            }
        } catch (cancelled: CancellationException) { uncertain(); throw cancelled
        } catch (_: Exception) { uncertain() }
    }

    suspend fun recover() = exclusively {
        if (state.value.phase != FeedbackPhase.UNCERTAIN) return@exclusively
        try {
            when (val result = port.recover(target)) {
                is FeedbackOutcome.Saved -> saved(result.receipt)
                is FeedbackOutcome.Rejected -> rejected(result.reason)
                FeedbackOutcome.Clear, FeedbackOutcome.Pending -> uncertain()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { uncertain() }
    }

    fun close() { closed = true; mutable.value = FeedbackState() }

    suspend fun reloadConflict() = exclusively { if (state.value.phase == FeedbackPhase.CONFLICT) readConflict() }

    fun resolveConflict(keepDraft: Boolean) {
        if (closed || state.value.phase != FeedbackPhase.CONFLICT || busy.isLocked) return
        val latest = state.value.conflict ?: return
        val draft = if (keepDraft) state.value.draft ?: latest.draft else latest.draft
        mutable.value = FeedbackState(if (latest.canSubmit) FeedbackPhase.EDITING else FeedbackPhase.READ_ONLY, latest, draft)
    }

    suspend fun continueEditing() = exclusively {
        val current = state.value
        val receipt = current.receipt ?: return@exclusively
        if (current.page?.canSubmit != true || current.phase != FeedbackPhase.READ_ONLY) return@exclusively
        try {
            port.acknowledge(receipt)
            currentCoroutineContext().ensureActive()
            if (!closed) mutable.value = current.copy(phase = FeedbackPhase.EDITING, receipt = null, rejected = false)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = current.copy(rejected = true) }
    }

    suspend fun finish(onFinished: () -> Unit) = exclusively {
        try {
            state.value.receipt?.let { port.acknowledge(it) }
            currentCoroutineContext().ensureActive()
            if (!closed) onFinished()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = state.value.copy(rejected = true) }
    }

    private suspend fun rejected(reason: String) {
        if (closed) return
        if (reason == "ENROLLMENT_UNAVAILABLE") {
            mutable.value = FeedbackState(FeedbackPhase.UNAVAILABLE)
        } else if (reason == "FEEDBACK_CHANGED") {
            mutable.value = state.value.copy(phase = FeedbackPhase.CONFLICT, conflict = null, rejected = true, receipt = null)
            readConflict()
        } else if (state.value.page != null && state.value.draft != null) {
            mutable.value = state.value.copy(phase = FeedbackPhase.EDITING, rejected = true)
        } else {
            try { readPage(rejected = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (!closed) mutable.value = FeedbackState(FeedbackPhase.FAILED, rejected = true) }
        }
    }

    private suspend fun readConflict() {
        if (closed) return
        mutable.value = state.value.copy(conflictReadFailed = false)
        try {
            val latest = port.load(target)
            currentCoroutineContext().ensureActive()
            require(latest.target == target)
            if (!closed) mutable.value = state.value.copy(phase = FeedbackPhase.CONFLICT, conflict = latest)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = state.value.copy(conflictReadFailed = true) }
    }
    private suspend fun readPage(receipt: FeedbackSaved? = null, rejected: Boolean = false) {
        val page = port.load(target)
        currentCoroutineContext().ensureActive()
        require(page.target == target && (receipt == null || receipt.feedbackId == page.feedbackId))
        if (!closed) mutable.value = FeedbackState(if (page.canSubmit && receipt == null) FeedbackPhase.EDITING else FeedbackPhase.READ_ONLY,
            page, page.draft, receipt, rejected = rejected)
    }
    private suspend fun saved(receipt: FeedbackSaved) {
        currentCoroutineContext().ensureActive()
        require(receipt.target == target)
        state.value.page?.feedbackId?.let { require(receipt.feedbackId == it) }
        if (!closed) mutable.value = FeedbackState(phase = FeedbackPhase.SAVED, receipt = receipt)
    }
    private fun uncertain() { if (!closed) mutable.value = state.value.copy(phase = FeedbackPhase.UNCERTAIN) }
    private suspend fun exclusively(block: suspend () -> Unit) {
        if (closed || !busy.tryLock()) return
        try { if (!closed) block() } finally { busy.unlock() }
    }
}
