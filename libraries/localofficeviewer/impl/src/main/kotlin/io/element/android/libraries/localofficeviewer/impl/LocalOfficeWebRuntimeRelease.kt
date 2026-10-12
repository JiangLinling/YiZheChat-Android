/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Owned by one WebView. A script result is accepted only from that view's evaluateJavascript callback. */
internal class LocalOfficeWebRuntimeRelease(
    private val evaluate: (String, (String?) -> Unit) -> Unit,
    private val destroyNative: (rendererGone: Boolean) -> Unit,
    private val onComplete: () -> Unit,
    private val allowUninitialized: () -> Boolean,
    private val scope: CoroutineScope? = null,
) {
    private var started = false
    private var jsComplete = false
    private var nativeComplete = false
    private var rendererGone = false

    /** Only the OS onRenderProcessGone callback for this exact WebView supplies this fact. */
    fun rendererExited() {
        rendererGone = true
        jsComplete = true
    }

    // Calls and callbacks run on the WebView/main thread. Compose disposal does not cancel this owner.
    fun release() {
        if (started) return
        started = true
        (scope ?: CoroutineScope(Dispatchers.Main.immediate)).launch {
            while (!nativeComplete) {
                if (!jsComplete) {
                    val status = withTimeoutOrNull(5_000) {
                        suspendCancellableCoroutine { continuation ->
                            runCatching {
                                evaluate(CLEANUP_SCRIPT) { result ->
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            }.onFailure {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    }
                    jsComplete = jsComplete || status == "\"complete\"" || (status == "\"uninitialized\"" && allowUninitialized())
                }
                if (jsComplete) nativeComplete = runCatching { destroyNative(rendererGone) }.isSuccess
                if (!nativeComplete) delay(250)
            }
            onComplete()
        }
    }

    companion object {
        // Before the module is initialized, the fence prevents its later open() from creating resources.
        // Missing/malformed responses never substitute for a successful callback from this document.
        internal val CLEANUP_SCRIPT = """
            (() => {
              globalThis.__YiZheOfficeCloseRequested = true;
              if (typeof globalThis.YiZheOffice === 'undefined') return 'uninitialized';
              if (typeof globalThis.YiZheOffice.requestDestroy !== 'function') return 'unknown';
              return globalThis.YiZheOffice.requestDestroy();
            })()
        """.trimIndent()
    }
}
