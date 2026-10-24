/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import io.element.android.features.discovery.impl.actions.DiscoveryEnrollmentResultLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Production implementation is DefaultDiscoveryEnrollmentPort under course-enrollment-v1.
 * Remote operations must use DiscoveryAccess.withSession and the protected operation journal:
 * persist exact operation + body before submit, recover the SAME operation after any unknown outcome,
 * and return Clear only when there is no unresolved operation. A payment callback cannot produce a receipt.
 */
internal interface DiscoveryEnrollmentPort {
    suspend fun options(courseId: Long, sessionId: Long): EnrollmentOptions
    suspend fun quote(request: EnrollmentRequest): EnrollmentQuote
    suspend fun submit(quote: EnrollmentQuote): EnrollmentOutcome
    suspend fun recover(courseId: Long, sessionId: Long): EnrollmentRecovery
    /** Read the current owned enrollment group after the operation journal has been retired. */
    suspend fun current(receipt: EnrollmentReceipt): EnrollmentReceipt
    /** Explicitly leave a confirmed result; expiry/background never acknowledges it. */
    suspend fun dismiss(receipt: EnrollmentReceipt)
}

/** Only for rejection before staging, or after a quote is durably terminal and its protected journal has been completed. */
internal class EnrollmentQuoteResolvedFailure(val latestOptions: EnrollmentOptions? = null, val reason: String? = null) : Exception()

internal sealed interface EnrollmentOutcome {
    data class Confirmed(val receipt: EnrollmentReceipt) : EnrollmentOutcome
    data object Pending : EnrollmentOutcome
    /** Definitive server rejection, persisted as terminal by the port before returning. */
    data class Rejected(val reason: String? = null) : EnrollmentOutcome
}

internal sealed interface EnrollmentRecovery {
    data object Clear : EnrollmentRecovery
    data object Pending : EnrollmentRecovery
    /** The original operation is durably terminal and was explicitly rejected by the server. */
    data class Rejected(val reason: String? = null) : EnrollmentRecovery
    data class Confirmed(val receipt: EnrollmentReceipt, val locator: DiscoveryEnrollmentResultLocator? = null) : EnrollmentRecovery
}

internal enum class EnrollmentPhase { LOADING, EDITING, QUOTING, QUOTED, SUBMITTING, UNCERTAIN, RECEIPT, FAILED }
internal enum class EnrollmentNotice { LOAD_FAILED, QUOTE_FAILED, QUOTE_EXPIRED, REJECTED, CHECK_RESULT }

internal data class DiscoveryEnrollmentState(
    val phase: EnrollmentPhase = EnrollmentPhase.LOADING,
    val options: EnrollmentOptions? = null,
    val draft: EnrollmentDraft? = null,
    val quote: EnrollmentQuote? = null,
    val receipt: EnrollmentReceipt? = null,
    val notice: EnrollmentNotice? = null,
    val showIssues: Boolean = false,
    val pendingQuote: Boolean = false,
    val businessReason: String? = null,
) {
    val editable: Boolean get() = phase == EnrollmentPhase.EDITING || phase == EnrollmentPhase.QUOTED
    val issues: Set<EnrollmentIssue> get() = options?.let { draft?.issues(it) }.orEmpty()
}

/** One page lifetime and one authorised account context. Close on leave/identity change; never retain in SavedState. */
internal class DiscoveryEnrollmentController(
    private val courseId: Long,
    private val sessionId: Long,
    private val port: DiscoveryEnrollmentPort,
    private val nowMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow(DiscoveryEnrollmentState())
    val state = mutableState.asStateFlow()
    private val busy = Mutex()
    private var closed = false

    fun edit(change: (EnrollmentDraft) -> EnrollmentDraft) {
        val current = state.value
        if (closed || !current.editable) return
        val draft = current.draft ?: return
        val updated = change(draft)
        if (updated != draft) mutableState.value = current.copy(
            phase = EnrollmentPhase.EDITING, draft = updated, quote = null, notice = null, businessReason = null,
        )
    }

    /** Invoked only after fresh registration options on return from the authorised editor. */
    fun useReturnedPerson(id: Long) {
        val options = state.value.options ?: return
        if (closed || !state.value.editable) return
        val person = options.people.firstOrNull { it.id == id } ?: return
        if (options.mode == EnrollmentMode.PARENT_CHILD && person.relation != EnrollmentRelation.CHILD) return
        edit { it.copy(personIds = if (options.mode == EnrollmentMode.SINGLE) setOf(id) else it.personIds + id) }
        mutableState.value = state.value.copy(showIssues = true)
    }

    suspend fun load() = exclusively {
        if (state.value.phase != EnrollmentPhase.LOADING && state.value.phase != EnrollmentPhase.FAILED) return@exclusively
        mutableState.value = DiscoveryEnrollmentState()
        try {
            when (val recovery = port.recover(courseId, sessionId)) {
                is EnrollmentRecovery.Confirmed -> showReceipt(recovery.receipt)
                EnrollmentRecovery.Pending -> pending()
                EnrollmentRecovery.Clear, is EnrollmentRecovery.Rejected -> {
                    val options = port.options(courseId, sessionId)
                    currentCoroutineContext().ensureActive()
                    require(options.courseId == courseId && options.sessionId == sessionId)
                    if (!closed) mutableState.value = DiscoveryEnrollmentState(
                        phase = EnrollmentPhase.EDITING, options = options, draft = options.initialDraft(),
                        notice = if (recovery is EnrollmentRecovery.Rejected) EnrollmentNotice.REJECTED else null,
                        businessReason = (recovery as? EnrollmentRecovery.Rejected)?.reason,
                    )
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!closed) mutableState.value = DiscoveryEnrollmentState(phase = EnrollmentPhase.FAILED, notice = EnrollmentNotice.LOAD_FAILED)
        }
    }

    suspend fun requestQuote() = exclusively {
        val current = state.value
        if (!current.editable) return@exclusively
        val options = current.options ?: return@exclusively
        val draft = current.draft ?: return@exclusively
        if (draft.issues(options).isNotEmpty()) {
            mutableState.value = current.copy(showIssues = true)
            return@exclusively
        }
        val request = draft.request(options)
        mutableState.value = current.copy(phase = EnrollmentPhase.QUOTING, quote = null, notice = null, businessReason = null, showIssues = true)
        try {
            val quote = port.quote(request)
            currentCoroutineContext().ensureActive()
            require(quote.request == request)
            if (!closed) mutableState.value = if (quote.expiresAtMillis > nowMillis()) {
                state.value.copy(phase = EnrollmentPhase.QUOTED, quote = quote)
            } else state.value.copy(phase = EnrollmentPhase.EDITING, quote = null, notice = EnrollmentNotice.QUOTE_EXPIRED)
        } catch (failure: EnrollmentQuoteResolvedFailure) {
            val latest = failure.latestOptions
            require(latest == null || (latest.courseId == courseId && latest.sessionId == sessionId))
            if (!closed) mutableState.value = state.value.copy(phase = EnrollmentPhase.EDITING, quote = null,
                options = latest ?: state.value.options, notice = EnrollmentNotice.QUOTE_FAILED, showIssues = true, businessReason = failure.reason)
        } catch (cancelled: CancellationException) {
            pending(quote = true)
            throw cancelled
        } catch (_: Exception) {
            pending(quote = true)
        }
    }

    /** Call after returning from child/profile editing. Removed authorisations never survive in the form. */
    suspend fun refreshOptions() = exclusively {
        val current = state.value
        if (!current.editable) return@exclusively
        val draft = current.draft ?: return@exclusively
        mutableState.value = DiscoveryEnrollmentState()
        try {
            val options = port.options(courseId, sessionId)
            currentCoroutineContext().ensureActive()
            require(options.courseId == courseId && options.sessionId == sessionId)
            val eligibleIds = options.people.filter { options.mode == EnrollmentMode.SINGLE || it.relation == EnrollmentRelation.CHILD }
                .map { it.id }.toSet()
            val selected = draft.personIds.intersect(eligibleIds)
            if (!closed) mutableState.value = DiscoveryEnrollmentState(
                phase = EnrollmentPhase.EDITING, options = options, showIssues = true,
                draft = draft.copy(personIds = if (options.mode == EnrollmentMode.SINGLE && selected.size > 1) emptySet() else selected,
                    guardian = draft.guardian && options.mode == EnrollmentMode.PARENT_CHILD),
            )
        } catch (cancelled: CancellationException) {
            if (!closed) mutableState.value = DiscoveryEnrollmentState(phase = EnrollmentPhase.FAILED, notice = EnrollmentNotice.LOAD_FAILED)
            throw cancelled
        } catch (_: Exception) {
            if (!closed) mutableState.value = DiscoveryEnrollmentState(phase = EnrollmentPhase.FAILED, notice = EnrollmentNotice.LOAD_FAILED)
        }
    }

    suspend fun submit() = exclusively {
        val current = state.value
        val quote = current.quote ?: return@exclusively
        if (current.phase != EnrollmentPhase.QUOTED) return@exclusively
        if (quote.expiresAtMillis <= nowMillis()) {
            mutableState.value = current.copy(phase = EnrollmentPhase.EDITING, quote = null, notice = EnrollmentNotice.QUOTE_EXPIRED)
            return@exclusively
        }
        mutableState.value = current.copy(phase = EnrollmentPhase.SUBMITTING, notice = null)
        try {
            when (val result = port.submit(quote)) {
                is EnrollmentOutcome.Confirmed -> showReceipt(result.receipt)
                EnrollmentOutcome.Pending -> pending()
                is EnrollmentOutcome.Rejected -> if (!closed) mutableState.value = current.copy(
                    phase = EnrollmentPhase.EDITING, quote = null, notice = EnrollmentNotice.REJECTED, businessReason = result.reason,
                )
            }
        } catch (cancelled: CancellationException) {
            pending()
            throw cancelled
        } catch (_: Exception) { pending() }
    }

    suspend fun checkResult() = exclusively {
        if (state.value.phase != EnrollmentPhase.UNCERTAIN && state.value.phase != EnrollmentPhase.RECEIPT) return@exclusively
        try {
            val previousReceipt = state.value.receipt
            if (previousReceipt != null) {
                val latest = port.current(previousReceipt)
                require(latest.enrollmentId == previousReceipt.enrollmentId && latest.orderId == previousReceipt.orderId)
                showReceipt(latest)
                return@exclusively
            }
            when (val result = port.recover(courseId, sessionId)) {
                is EnrollmentRecovery.Confirmed -> showReceipt(result.receipt)
                is EnrollmentRecovery.Rejected -> {
                    val current = state.value
                    if (!closed && current.receipt == null) {
                        mutableState.value = current.copy(
                            phase = if (current.draft != null && current.options != null) EnrollmentPhase.EDITING else EnrollmentPhase.FAILED,
                            quote = null, notice = EnrollmentNotice.REJECTED, pendingQuote = false, businessReason = result.reason,
                        )
                    }
                }
                // An uncertain submission is not safe to repeat merely because an operation query found nothing.
                EnrollmentRecovery.Clear -> {
                    val current = state.value
                    // A quote never enrolls anyone. Clear means its exact original operation is resolved;
                    // the user may now request a new quote. Enrollment uncertainty never uses this shortcut.
                    if (current.pendingQuote && current.draft != null && current.options != null) {
                        if (!closed) mutableState.value = current.copy(phase = EnrollmentPhase.EDITING, quote = null,
                            pendingQuote = false, notice = EnrollmentNotice.QUOTE_FAILED)
                    } else pending()
                }
                EnrollmentRecovery.Pending -> pending()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { pending() }
    }

    fun close() {
        closed = true
        mutableState.value = DiscoveryEnrollmentState()
    }

    suspend fun dismissResult(onDismissed: () -> Unit) = exclusively {
        val receipt = state.value.receipt ?: return@exclusively
        try {
            port.dismiss(receipt)
            currentCoroutineContext().ensureActive()
            if (!closed) onDismissed()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!closed) mutableState.value = state.value.copy(notice = EnrollmentNotice.CHECK_RESULT)
        }
    }

    private suspend fun showReceipt(receipt: EnrollmentReceipt) {
        currentCoroutineContext().ensureActive()
        require(receipt.courseId == courseId && receipt.sessionId == sessionId)
        if (!closed) mutableState.value = DiscoveryEnrollmentState(phase = EnrollmentPhase.RECEIPT, receipt = receipt)
    }

    private fun pending(quote: Boolean = state.value.pendingQuote) {
        if (!closed) mutableState.value = state.value.copy(phase = EnrollmentPhase.UNCERTAIN, quote = null,
            notice = EnrollmentNotice.CHECK_RESULT, pendingQuote = quote)
    }

    private suspend fun exclusively(block: suspend () -> Unit) {
        if (closed || !busy.tryLock()) return
        try { if (!closed) block() } finally { busy.unlock() }
    }
}
