/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.tracing

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.meta.BuildType
import io.element.android.libraries.matrix.test.core.aBuildMeta
import org.junit.Test
import timber.log.Timber

class ReleaseLoggingGateTest {
    private fun tree(buildType: BuildType, debuggable: Boolean = false) = RustTracingService(
        aBuildMeta(buildType = buildType, isDebuggable = debuggable),
    ).createTimberTree("release-gate-fixture")

    private fun accepts(tree: Timber.Tree, priority: Int): Boolean {
        val method = Timber.Tree::class.java.getDeclaredMethod("isLoggable", String::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(tree, "fixture", priority) as Boolean
    }

    @Test
    fun `release debug is rejected before format arguments or native forwarding`() {
        var formatted = 0
        val argument = object { override fun toString(): String { formatted++; return "fixture" } }
        val attempt = runCatching { tree(BuildType.RELEASE).d("discard %s", argument) }
        assertThat(formatted).isEqualTo(0)
        assertThat(attempt.isSuccess).isTrue()
    }

    @Test
    fun `release trace throwable is rejected by its original low level before formatting`() {
        var formatted = 0
        val argument = object { override fun toString(): String { formatted++; return "fixture" } }
        val attempt = runCatching { tree(BuildType.RELEASE).v(IllegalStateException("fixture"), "discard %s", argument) }
        assertThat(formatted).isEqualTo(0)
        assertThat(attempt.isSuccess).isTrue()
    }

    @Test
    fun `normal release has an app Timber info floor`() {
        val tree = tree(BuildType.RELEASE)
        assertThat(accepts(tree, Log.VERBOSE)).isFalse()
        assertThat(accepts(tree, Log.DEBUG)).isFalse()
    }

    @Test
    fun `debug build keeps low level diagnostics enabled`() {
        val tree = tree(BuildType.DEBUG, debuggable = true)
        assertThat(accepts(tree, Log.VERBOSE)).isTrue()
        assertThat(accepts(tree, Log.DEBUG)).isTrue()
    }

    @Test
    fun `nondebuggable nightly keeps its existing diagnostics enabled`() {
        val tree = tree(BuildType.NIGHTLY)
        assertThat(accepts(tree, Log.VERBOSE)).isTrue()
        assertThat(accepts(tree, Log.DEBUG)).isTrue()
    }

    @Test
    fun `normal release keeps info warnings errors and assert eligible`() {
        val tree = tree(BuildType.RELEASE)
        listOf(Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT).forEach { priority ->
            assertThat(accepts(tree, priority)).isTrue()
        }
    }
}
