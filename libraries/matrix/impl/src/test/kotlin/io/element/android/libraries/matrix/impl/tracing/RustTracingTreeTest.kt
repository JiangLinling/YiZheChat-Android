/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.tracing

import android.util.Log
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rustcomponents.sdk.LogLevel

class RustTracingTreeTest {
    private data class Entry(val location: LogEventLocation, val level: LogLevel, val target: String, val text: String)
    private val entries = mutableListOf<Entry>()
    private var stackReads = 0
    private val source = LogEventLocation("OriginalCallSite.kt", 42u)
    private fun tree(debug: Boolean = true) = RustTracingTree(
        target = "elementx",
        retrieveFromStackTrace = debug,
        locationProvider = { stackReads++; source },
        sink = { location, level, target, text -> entries += Entry(location, level, target, text) },
    )

    @Test fun `debug timing spans preserve exact log payload and level without capturing stacks`() {
        val tree = tree()
        listOf("YiZheMessageStage", "YiZheTimelinePerf", "YiZheDeviceTrust").forEach { tag ->
            tree.log(Log.DEBUG, tag, "phase=finish stage=send span=7 duration_us=55", null)
        }
        assertThat(stackReads).isEqualTo(0)
        assertThat(entries).hasSize(3)
        entries.forEach {
            assertThat(it.location).isEqualTo(LogEventLocation("", null))
            assertThat(it.level).isEqualTo(LogLevel.DEBUG)
            assertThat(it.target).isEqualTo("elementx")
            assertThat(it.text).endsWith("phase=finish stage=send span=7 duration_us=55")
        }
        assertThat(entries[0].text).startsWith("[YiZheMessageStage] ")
        assertThat(entries[1].text).startsWith("[YiZheTimelinePerf] ")
        assertThat(entries[2].text).startsWith("[YiZheDeviceTrust] ")
    }

    @Test fun `warnings and errors retain their call site even with a timing tag`() {
        val tree = tree()
        tree.log(Log.WARN, "YiZheMessageStage", "warning", null)
        tree.log(Log.ERROR, "YiZheTimelinePerf", "error", null)
        assertThat(stackReads).isEqualTo(2)
        assertThat(entries.map { it.location }).containsExactly(source, source)
        assertThat(entries.map { it.level }).containsExactly(LogLevel.WARN, LogLevel.ERROR).inOrder()
    }

    @Test fun `debug exceptions retain their call site`() {
        tree().log(Log.DEBUG, "YiZheMessageStage", "exception detail", IllegalStateException("fixture"))
        assertThat(stackReads).isEqualTo(1)
        assertThat(entries.single().location).isEqualTo(source)
    }

    @Test fun `ordinary and untagged debug logs retain source locations`() {
        val tree = tree()
        tree.log(Log.DEBUG, "SecurityAudit", "opaque audit event", null)
        tree.log(Log.DEBUG, null as String?, "ordinary event", null)
        tree.log(Log.INFO, "YiZheMessageStage", "information", null)
        assertThat(stackReads).isEqualTo(3)
        assertThat(entries.map { it.location }).containsExactly(source, source, source)
        assertThat(entries[1].text).isEqualTo("ordinary event")
    }

    @Test fun `nondebug logging keeps the existing location-free behavior`() {
        tree(debug = false).log(Log.ERROR, "SecurityAudit", "opaque failure", IllegalStateException("fixture"))
        assertThat(stackReads).isEqualTo(0)
        assertThat(entries.single()).isEqualTo(Entry(LogEventLocation("", null), LogLevel.ERROR, "elementx", "[SecurityAudit] opaque failure"))
    }

    @Test fun `main thread diagnostic forwarding preserves the original source and payload`() {
        val queued = mutableListOf<() -> Unit>()
        val tree = RustTracingTree(
            target = "elementx", retrieveFromStackTrace = true,
            locationProvider = { source }, diagnosticDispatcher = { queued += it }, isMainThread = { true },
            sink = { location, level, target, text -> entries += Entry(location, level, target, text) },
        )
        tree.log(Log.DEBUG, "Lifecycle", "onCreate OriginalHome", null)
        tree.log(Log.DEBUG, "Analytics", "User consent updated to false", null)
        tree.log(Log.DEBUG, "Sentry", "Stopping Sentry", null)
        tree.log(Log.DEBUG, "YiZheMessageStage", "epoch_ms=123 stage=start span=7", null)
        assertThat(entries).isEmpty()
        queued.forEach { it() }
        assertThat(entries).containsExactly(
            Entry(source, LogLevel.DEBUG, "elementx", "[Lifecycle] onCreate OriginalHome"),
            Entry(source, LogLevel.DEBUG, "elementx", "[Analytics] User consent updated to false"),
            Entry(source, LogLevel.DEBUG, "elementx", "[Sentry] Stopping Sentry"),
            Entry(LogEventLocation("", null), LogLevel.DEBUG, "elementx", "[YiZheMessageStage] epoch_ms=123 stage=start span=7"),
        ).inOrder()
    }

    @Test fun `audit messages warnings and exceptions never use the diagnostic queue`() {
        val queued = mutableListOf<() -> Unit>()
        val tree = RustTracingTree(
            target = "elementx", retrieveFromStackTrace = true,
            locationProvider = { source }, diagnosticDispatcher = { queued += it }, isMainThread = { true },
            sink = { location, level, target, text -> entries += Entry(location, level, target, text) },
        )
        tree.log(Log.DEBUG, "SecurityAudit", "original operation", null)
        tree.log(Log.WARN, "Lifecycle", "warning", null)
        tree.log(Log.ERROR, "YiZheMessageStage", "error", null)
        tree.log(Log.DEBUG, "YiZheMessageStage", "exception", IllegalStateException("fixture"))
        tree.log(Log.WARN, "Sentry", "warning", null)
        tree.log(Log.DEBUG, "Sentry", "exception", IllegalStateException("fixture"))
        assertThat(queued).isEmpty()
        assertThat(entries).hasSize(6)
        assertThat(entries.map { it.location }).containsExactly(source, source, source, source, source, source)
    }

    @Test fun `background diagnostics keep synchronous forwarding`() {
        val queued = mutableListOf<() -> Unit>()
        val tree = RustTracingTree(
            target = "elementx", retrieveFromStackTrace = false,
            diagnosticDispatcher = { queued += it }, isMainThread = { false },
            sink = { location, level, target, text -> entries += Entry(location, level, target, text) },
        )
        tree.log(Log.DEBUG, "YiZheMessageStage", "epoch_ms=123", null)
        assertThat(queued).isEmpty()
        assertThat(entries).hasSize(1)
    }
}
