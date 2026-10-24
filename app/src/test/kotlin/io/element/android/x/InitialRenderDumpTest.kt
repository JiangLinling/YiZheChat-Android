/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnosticSnapshot
import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnostics
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Test

class InitialRenderDumpTest {
    @Test fun `unrelated arguments leave the original dump path untouched`() {
        val output = StringWriter()
        val handled = InitialRenderDump.writeIfRequested(arrayOf("--other"), true, true, PrintWriter(output)) {
            error("The diagnostic provider must not run")
        }
        assertThat(handled).isFalse()
        assertThat(output.toString()).isEmpty()
    }

    @Test fun `recognized selector with extra input fails closed without echo or a read`() {
        val output = StringWriter()
        val handled = InitialRenderDump.writeIfRequested(
            arrayOf(InitialRenderDump.SELECTOR, "private-untrusted-extra"), true, true, PrintWriter(output),
        ) { error("The diagnostic provider must not run") }
        assertThat(handled).isTrue()
        assertThat(output.toString()).contains("cause=INVALID_SELECTOR")
        assertThat(output.toString()).doesNotContain("private-untrusted-extra")
    }

    @Test fun `foreign package or protected foreground never reads the snapshot`() {
        listOf(false to true, true to false).forEach { (isInternal, visible) ->
            val output = StringWriter()
            assertThat(InitialRenderDump.writeIfRequested(arrayOf(InitialRenderDump.SELECTOR), isInternal, visible, PrintWriter(output)) {
                error("Locked or unsupported callers must not read the report")
            }).isTrue()
            assertThat(output.toString()).contains("status=UNAVAILABLE cause=PROTECTED_FOREGROUND")
        }
    }

    @Test fun `allowed explicit request only renders the immutable scalar report once`() {
        val owner = InitialRenderDiagnostics.newOwner()
        try {
            InitialRenderDiagnostics.recordViewport(owner, InitialRenderDiagnosticSnapshot(rawCount = 3))
            val before = InitialRenderDiagnostics.read()
            val output = StringWriter()
            var reads = 0
            assertThat(InitialRenderDump.writeIfRequested(arrayOf(InitialRenderDump.SELECTOR), true, true, PrintWriter(output)) {
                reads++
                InitialRenderDiagnostics.read()
            }).isTrue()
            assertThat(reads).isEqualTo(1)
            assertThat(output.toString()).contains("layer=MATRIX")
            assertThat(output.toString()).contains("raw=3")
            assertThat(InitialRenderDiagnostics.read()).isSameInstanceAs(before)
        } finally {
            InitialRenderDiagnostics.close(owner)
        }
    }
}
