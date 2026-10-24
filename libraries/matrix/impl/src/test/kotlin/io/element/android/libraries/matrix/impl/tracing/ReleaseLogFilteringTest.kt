/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.tracing

import android.util.Log
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.rustcomponents.sdk.LogLevel
import timber.log.Timber

class ReleaseLogFilteringTest {
    private data class Entry(val location: LogEventLocation, val level: LogLevel, val text: String)
    private val entries = mutableListOf<Entry>()
    private val queued = mutableListOf<() -> Unit>()
    private var stackReads = 0
    private var formatted = 0
    private val argument = object { override fun toString(): String { formatted++; return "fixture" } }
    private fun tree(retrieve: Boolean = false, floor: Int? = Log.INFO) = RustTracingTree(
        target = "elementx", retrieveFromStackTrace = retrieve, minimumPriority = floor,
        locationProvider = { stackReads++; LogEventLocation("Fixture.kt", 1u) },
        diagnosticDispatcher = { queued += it }, isMainThread = { true },
        sink = { location, level, _, text -> entries += Entry(location, level, text) },
    )

    @Before fun prepare() = Timber.uprootAll()
    @After fun cleanup() = Timber.uprootAll()

    @Test fun `release d and v stop before formatter stack queue and native sink`() {
        Timber.plant(tree(retrieve = true))
        Timber.tag("YiZheMessageStage").d("discard %s", argument)
        Timber.tag("Lifecycle").v("discard %s", argument)
        Timber.tag("SecurityAudit").d(IllegalStateException("fixture"), "discard %s", argument)
        assertThat(formatted).isEqualTo(0)
        assertThat(stackReads).isEqualTo(0)
        assertThat(queued).isEmpty()
        assertThat(entries).isEmpty()
    }

    @Test fun `filtered tag is consumed and cannot contaminate next accepted log`() {
        Timber.plant(tree())
        Timber.tag("discarded-tag").d("discard %s", argument)
        Timber.i("untagged information")
        Timber.tag("accepted-tag").w("warning %s", argument)
        assertThat(formatted).isEqualTo(1)
        assertThat(entries.map { it.text }).containsExactly("untagged information", "[accepted-tag] warning fixture").inOrder()
        assertThat(entries.map { it.level }).containsExactly(LogLevel.INFO, LogLevel.WARN).inOrder()
    }

    @Test fun `release info warning and error keep payload tags and location policy`() {
        Timber.plant(tree())
        Timber.tag("information").i("value %s", argument)
        Timber.tag("warning").w("value %s", argument)
        Timber.tag("error").e("value %s", argument)
        assertThat(formatted).isEqualTo(3)
        assertThat(stackReads).isEqualTo(0)
        assertThat(queued).isEmpty()
        assertThat(entries.map { it.text }).containsExactly("[information] value fixture", "[warning] value fixture", "[error] value fixture").inOrder()
        assertThat(entries.map { it.level }).containsExactly(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR).inOrder()
    }

    @Test fun `direct low level log cannot bypass final no side effect guard`() {
        val tree = tree(retrieve = true)
        tree.log(Log.DEBUG, "Lifecycle", "already formatted", null)
        tree.log(Log.VERBOSE, "SecurityAudit", "already formatted", IllegalStateException("fixture"))
        assertThat(stackReads).isEqualTo(0)
        assertThat(queued).isEmpty()
        assertThat(entries).isEmpty()
    }

    @Test fun `release assert is forwarded as an error instead of being downgraded to debug`() {
        tree().log(Log.ASSERT, "critical", "important failure", null)
        assertThat(entries.single()).isEqualTo(Entry(LogEventLocation("", null), LogLevel.ERROR, "[critical] important failure"))
    }

    @Test fun `unfiltered diagnostic build keeps formatting and existing deferred dispatch`() {
        Timber.plant(tree(retrieve = true, floor = null))
        Timber.tag("Lifecycle").d("value %s", argument)
        assertThat(formatted).isEqualTo(1)
        assertThat(stackReads).isEqualTo(1)
        assertThat(entries).isEmpty()
        assertThat(queued).hasSize(1)
        queued.single().invoke()
        assertThat(entries.single().text).isEqualTo("[Lifecycle] value fixture")
        assertThat(entries.single().level).isEqualTo(LogLevel.DEBUG)
    }

    @Test fun `release debug throwable remains debug and is not silently promoted to error`() {
        val tree = tree(retrieve = true)
        tree.d(IllegalStateException("fixture"), "discard %s", argument)
        assertThat(formatted).isEqualTo(0)
        assertThat(stackReads).isEqualTo(0)
        assertThat(entries).isEmpty()
    }
}
