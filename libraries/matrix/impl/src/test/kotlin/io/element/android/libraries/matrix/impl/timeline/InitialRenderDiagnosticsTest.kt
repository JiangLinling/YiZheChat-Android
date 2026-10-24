/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnosticSnapshot
import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnostics
import io.element.android.libraries.matrix.api.timeline.InitialRenderMatrixLane
import io.element.android.libraries.matrix.api.timeline.InitialRenderUnavailable
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class InitialRenderDiagnosticsTest {
    private val owners = mutableListOf<InitialRenderDiagnostics.Owner>()
    private fun owner() = InitialRenderDiagnostics.newOwner().also(owners::add)

    @Before fun isolate() {
        val initial = owner()
        InitialRenderDiagnostics.recordViewport(initial, InitialRenderDiagnosticSnapshot())
        InitialRenderDiagnostics.close(initial)
    }

    @After fun close() = owners.forEach { InitialRenderDiagnostics.close(it) }

    @Test fun `background dispatches do not select a visible diagnostic owner`() {
        val background = owner()
        InitialRenderDiagnostics.recordReset(background)
        InitialRenderDiagnostics.recordDispatch(background, InitialRenderMatrixLane.HISTORY, true)
        assertThat(InitialRenderDiagnostics.read().unavailable).isNotNull()
        InitialRenderDiagnostics.recordViewport(background, InitialRenderDiagnosticSnapshot(rawCount = 1))
        assertThat(InitialRenderDiagnostics.read().resetsSeen).isEqualTo(1)
        assertThat(InitialRenderDiagnostics.read().unmeasuredHistory).isEqualTo(1)
    }

    @Test fun `old owner close and late work cannot alter the new visible owner`() {
        val old = owner()
        InitialRenderDiagnostics.recordViewport(old, InitialRenderDiagnosticSnapshot(rawCount = 1))
        val current = owner()
        InitialRenderDiagnostics.recordViewport(current, InitialRenderDiagnosticSnapshot(rawCount = 2))
        InitialRenderDiagnostics.close(old)
        InitialRenderDiagnostics.recordDispatch(old, InitialRenderMatrixLane.BOOTSTRAP, false)
        InitialRenderDiagnostics.recordViewport(old, InitialRenderDiagnosticSnapshot(rawCount = 99))
        assertThat(InitialRenderDiagnostics.read().samples.single().rawCount).isEqualTo(2)
        assertThat(InitialRenderDiagnostics.read().matrixBootstrap).isEqualTo(0)
    }

    @Test fun `hidden and hard invalidated owners expose no prior counters`() {
        val current = owner()
        InitialRenderDiagnostics.recordDispatch(current, InitialRenderMatrixLane.HISTORY, true)
        InitialRenderDiagnostics.recordViewport(current, InitialRenderDiagnosticSnapshot(rawCount = 10))
        InitialRenderDiagnostics.hide(current)
        assertThat(InitialRenderDiagnostics.read().unavailable).isEqualTo(InitialRenderUnavailable.HIDDEN)
        assertThat(InitialRenderDiagnostics.read().samples).isEmpty()
        InitialRenderDiagnostics.recordViewport(current, InitialRenderDiagnosticSnapshot(rawCount = 2))
        assertThat(InitialRenderDiagnostics.read().matrixHistory).isEqualTo(0)
        InitialRenderDiagnostics.close(current, InitialRenderUnavailable.HARD_INVALIDATION)
        InitialRenderDiagnostics.recordDispatch(current, InitialRenderMatrixLane.VISIBLE, false)
        assertThat(InitialRenderDiagnostics.read().unavailable).isEqualTo(InitialRenderUnavailable.HARD_INVALIDATION)
        assertThat(InitialRenderDiagnostics.read().matrixVisible).isEqualTo(0)
    }

    @Test fun `samples stay immutable bounded and preserve beginning plus latest evidence`() {
        val current = owner()
        repeat(64) { index ->
            InitialRenderDiagnostics.recordViewport(current, InitialRenderDiagnosticSnapshot(
                rawCount = index + 1, candidateCount = Int.MAX_VALUE, selectedBootstrap = -1, rawRevisionChanges = Int.MAX_VALUE,
            ))
        }
        val report = InitialRenderDiagnostics.read()
        assertThat(report.samples).hasSize(16)
        assertThat(report.samples.take(4).map { it.rawCount }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(report.samples.last().rawCount).isEqualTo(64)
        assertThat(report.samples.last().candidateCount).isEqualTo(30)
        assertThat(report.samples.last().selectedBootstrap).isEqualTo(0)
        assertThat(report.samples.last().rawRevisionChanges).isEqualTo(65535)
        assertThrows(UnsupportedOperationException::class.java) { (report.samples as MutableList<*>).clear() }
    }

    @Test fun `typed fields and read only rendering expose matrix counts without identifiers or callbacks`() {
        assertThat(InitialRenderDiagnosticSnapshot::class.java.declaredFields.all {
            it.type.isEnum || it.type == Int::class.javaPrimitiveType || it.type == Boolean::class.javaPrimitiveType
        }).isTrue()
        val current = owner()
        InitialRenderDiagnostics.recordCapture(current, empty = true)
        InitialRenderDiagnostics.recordDispatch(current, InitialRenderMatrixLane.VISIBLE, false)
        repeat(2) { InitialRenderDiagnostics.recordDispatch(current, InitialRenderMatrixLane.BOOTSTRAP, false) }
        InitialRenderDiagnostics.recordDispatch(current, InitialRenderMatrixLane.HISTORY, true)
        InitialRenderDiagnostics.recordViewport(current, InitialRenderDiagnosticSnapshot(rawCount = 4))
        val before = InitialRenderDiagnostics.read()
        val lines = InitialRenderDiagnostics.lines(before)
        assertThat(lines.first()).contains("layer=MATRIX")
        assertThat(lines.first()).contains("dispatched_bootstrap=2")
        assertThat(lines.first()).contains("dispatched_unmeasured_history=1")
        assertThat(lines.first()).contains("empty_captures=1")
        assertThat(InitialRenderDiagnostics.read()).isSameInstanceAs(before)
        assertThat(InitialRenderDiagnostics.lines(before)).containsExactlyElementsIn(lines).inOrder()
    }
}
