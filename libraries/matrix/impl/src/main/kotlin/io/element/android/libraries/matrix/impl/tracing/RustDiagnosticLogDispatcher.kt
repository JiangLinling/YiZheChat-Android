/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.tracing

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import timber.log.Timber

/** Bounded best-effort diagnostics. Overflow runs at the caller instead of discarding an entry. */
internal object RustDiagnosticLogDispatcher {
    private val executor by lazy { createDiagnosticLogExecutor() }

    fun dispatch(task: () -> Unit) {
        executor.execute {
            runCatching(task).onFailure { failure ->
                // Do not let a diagnostic worker terminate the app, or recursively log
                // forever if the native logger itself is unavailable.
                runCatching { Timber.w("Queued diagnostic log failed: %s", failure.javaClass.simpleName) }
            }
        }
    }
}

internal fun createDiagnosticLogExecutor(capacity: Int = 128) = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
    { task -> Thread(task, "YiZheDiagnosticLogs").apply { isDaemon = true } },
    ThreadPoolExecutor.CallerRunsPolicy(),
)
