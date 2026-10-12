/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalOfficeWebRuntimeReleaseTest {
    @Test
    fun `pending script cannot destroy native runtime or emit completion`() = runTest {
        var result = "\"pending\""
        var nativeClosed = 0
        var completed = 0
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { _, callback -> callback(result) },
            destroyNative = { nativeClosed++ },
            onComplete = { completed++ },
            allowUninitialized = { false },
            scope = backgroundScope,
        )
        owner.release()
        runCurrent()
        assertThat(nativeClosed).isEqualTo(0)
        assertThat(completed).isEqualTo(0)
        result = "\"complete\""
        advanceTimeBy(250)
        runCurrent()
        assertThat(nativeClosed).isEqualTo(1)
        assertThat(completed).isEqualTo(1)
        owner.release()
        runCurrent()
        assertThat(completed).isEqualTo(1)
    }

    @Test
    fun `native close retries without losing accepted script completion`() = runTest {
        var scripts = 0
        var nativeCloses = 0
        var completed = 0
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { _, callback -> scripts++; callback("\"complete\"") },
            destroyNative = { if (++nativeCloses == 1) error("native still owned") },
            onComplete = { completed++ },
            allowUninitialized = { false },
            scope = backgroundScope,
        )
        owner.release()
        runCurrent()
        assertThat(completed).isEqualTo(0)
        advanceTimeBy(250)
        runCurrent()
        assertThat(nativeCloses).isEqualTo(2)
        assertThat(scripts).isEqualTo(1)
        assertThat(completed).isEqualTo(1)
    }

    @Test
    fun `uninitialized module only closes when this document never acquired content`() = runTest {
        var neverOpened = false
        var completed = 0
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { script, callback ->
                assertThat(script).contains("globalThis.__YiZheOfficeCloseRequested = true")
                callback("\"uninitialized\"")
            },
            destroyNative = {},
            onComplete = { completed++ },
            allowUninitialized = { neverOpened },
            scope = backgroundScope,
        )
        owner.release()
        runCurrent()
        assertThat(completed).isEqualTo(0)
        neverOpened = true
        advanceTimeBy(250)
        runCurrent()
        assertThat(completed).isEqualTo(1)
    }

    @Test
    fun `null malformed and timed out responses stay pending and late callback is ignored`() = runTest {
        var calls = 0
        var completed = 0
        lateinit var late: (String?) -> Unit
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { _, callback ->
                when (++calls) {
                    1 -> callback(null)
                    2 -> callback("complete")
                    3 -> late = callback
                    else -> callback("\"pending\"")
                }
            },
            destroyNative = { error("no successful JS cleanup") },
            onComplete = { completed++ },
            allowUninitialized = { false },
            scope = backgroundScope,
        )
        owner.release()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        late("\"complete\"")
        advanceTimeBy(250)
        runCurrent()
        assertThat(completed).isEqualTo(0)
        assertThat(calls).isAtLeast(4)
    }

    @Test
    fun `OS renderer exit bypasses script but still requires native destruction`() = runTest {
        var completed = 0
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { _, _ -> error("cannot evaluate in a dead renderer") },
            destroyNative = { rendererGone -> assertThat(rendererGone).isTrue() },
            onComplete = { completed++ },
            allowUninitialized = { false },
            scope = backgroundScope,
        )
        owner.rendererExited()
        owner.release()
        runCurrent()
        assertThat(completed).isEqualTo(1)
    }

    @Test
    fun `pending script timeout cannot overwrite an observed renderer exit`() = runTest {
        var completed = 0
        val owner = LocalOfficeWebRuntimeRelease(
            evaluate = { _, _ -> },
            destroyNative = { rendererGone -> assertThat(rendererGone).isTrue() },
            onComplete = { completed++ },
            allowUninitialized = { false },
            scope = backgroundScope,
        )
        owner.release()
        runCurrent()
        owner.rendererExited()
        advanceTimeBy(5_001)
        runCurrent()
        assertThat(completed).isEqualTo(1)
    }
}
