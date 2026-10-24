/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Non-persistent LC-23 observation only; never participates in authorization, admission or release. */
object InitialRenderDiagnostics {
    class Owner internal constructor(internal val order: Long) {
        internal val closed = AtomicBoolean()
        internal val report = AtomicReference(InitialRenderDiagnosticReport())
    }
    private data class Selection(val owner: Owner?, val unavailable: InitialRenderUnavailable)
    private val sequence = AtomicLong()
    private val selected = AtomicReference(Selection(null, InitialRenderUnavailable.NEVER_MEASURED))

    fun newOwner(): Owner = Owner(sequence.incrementAndGet())

    private fun update(owner: Owner, transform: (InitialRenderDiagnosticReport) -> InitialRenderDiagnosticReport) {
        if (owner.closed.get()) return
        owner.report.updateAndGet { if (owner.closed.get()) InitialRenderDiagnosticReport() else transform(it) }
    }

    fun recordReset(owner: Owner) = update(owner) { it.changed(resetsSeen = increment(it.resetsSeen)) }

    fun recordCapture(owner: Owner, empty: Boolean) = update(owner) {
        it.changed(captureAttempts = increment(it.captureAttempts), emptyCaptures = if (empty) increment(it.emptyCaptures) else it.emptyCaptures)
    }

    fun recordDispatch(owner: Owner, lane: InitialRenderMatrixLane, unmeasured: Boolean) = update(owner) {
        when (lane) {
            InitialRenderMatrixLane.VISIBLE -> it.changed(matrixVisible = increment(it.matrixVisible))
            InitialRenderMatrixLane.BOOTSTRAP -> it.changed(matrixBootstrap = increment(it.matrixBootstrap))
            InitialRenderMatrixLane.HISTORY -> it.changed(matrixHistory = increment(it.matrixHistory),
                unmeasuredHistory = if (unmeasured) increment(it.unmeasuredHistory) else it.unmeasuredHistory)
        }
    }

    /** Only a nonempty measured UI viewport may select its existing scope for observation. */
    fun recordViewport(owner: Owner, snapshot: InitialRenderDiagnosticSnapshot) {
        if (owner.closed.get()) return
        update(owner) { it.sample(snapshot) }
        while (!owner.closed.get()) {
            val previous = selected.get()
            if (previous.owner != null && !previous.owner.closed.get() && previous.owner.order > owner.order) return
            if (selected.compareAndSet(previous, Selection(owner, InitialRenderUnavailable.NEVER_MEASURED))) return
        }
    }

    fun hide(owner: Owner) {
        if (owner.report.get().samples.isNotEmpty()) owner.report.set(InitialRenderDiagnosticReport())
        unselect(owner, InitialRenderUnavailable.HIDDEN)
    }

    fun close(owner: Owner, cause: InitialRenderUnavailable = InitialRenderUnavailable.OWNER_CLOSED) {
        owner.closed.set(true)
        owner.report.set(InitialRenderDiagnosticReport())
        unselect(owner, cause)
    }

    private fun unselect(owner: Owner, cause: InitialRenderUnavailable) {
        while (true) {
            val previous = selected.get()
            if (previous.owner !== owner) return
            if (selected.compareAndSet(previous, Selection(null, cause))) return
        }
    }

    /** No callback, queue lock, clock, storage, parsing, network or state mutation occurs here. */
    fun read(): InitialRenderDiagnosticReport {
        val selection = selected.get()
        val owner = selection.owner ?: return InitialRenderDiagnosticReport(unavailable = selection.unavailable)
        val report = owner.report.get()
        return if (!owner.closed.get() && selected.get() === selection) report
        else InitialRenderDiagnosticReport(unavailable = InitialRenderUnavailable.TRANSITION)
    }

    fun lines(report: InitialRenderDiagnosticReport): List<String> = buildList {
        report.unavailable?.let { add(unavailableLine(it)); return@buildList }
        add("YIZHE_INITIAL_RENDER_V1 layer=MATRIX resets=${report.resetsSeen} capture_attempts=${report.captureAttempts} " +
            "empty_captures=${report.emptyCaptures} dispatched_visible=${report.matrixVisible} " +
            "dispatched_bootstrap=${report.matrixBootstrap} dispatched_history=${report.matrixHistory} " +
            "dispatched_unmeasured_history=${report.unmeasuredHistory}")
        report.samples.forEachIndexed { index, sample ->
            add("sample=$index state=${sample.state.name} cause=${sample.cause.name} tail_block=${sample.tailBlock.name} " +
                "allow=${sample.allowInitialRender} " +
                "measured=${sample.measured} anchor=${sample.anchorDisplayable} tail=${sample.tailOnScreen} " +
                "raw=${sample.rawCount} display=${sample.displayCount} visible=${sample.visibleItemCount} " +
                "visible_raw_matches=${sample.matchingVisibleRawSlots} shells=${sample.shellCount} initial_window=${sample.initialWindowSize} " +
                "capture_empty_shells=${sample.lastCaptureEmptyShells} capture_metadata_eligible=${sample.lastCaptureMetadataEligible} " +
                "candidates=${sample.candidateCount} raw_matches=${sample.matchingRawCandidates} " +
                "selected_bootstrap=${sample.selectedBootstrap} raw_revision_changes=${sample.rawRevisionChanges}")
        }
    }

    fun unavailableLine(cause: InitialRenderUnavailable): String = "YIZHE_INITIAL_RENDER_V1 status=UNAVAILABLE cause=${cause.name}"
    private fun increment(value: Int) = (value + 1).coerceAtMost(65535)
}
