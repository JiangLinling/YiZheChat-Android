/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.androidutils.startup

import android.os.SystemClock
import android.util.Log

/** Startup tracing that works before Timber has been configured. */
object StartupTrace {
    private const val TAG = "YiZheStartup"
    private val processStartElapsed = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = SystemClock.elapsedRealtime() - processStartElapsed
        Log.i(TAG, "[Startup] +${elapsed}ms $stage thread=${Thread.currentThread().name}")
    }

    inline fun <T> measure(stage: String, block: () -> T): T {
        mark("BEGIN $stage")
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            mark("END $stage duration=${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    suspend inline fun <T> measureSuspend(stage: String, crossinline block: suspend () -> T): T {
        mark("BEGIN $stage")
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            mark("END $stage duration=${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }
}
