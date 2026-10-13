/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.tracing

import android.util.Log
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.logEvent
import timber.log.Timber

/**
 * List of fully qualified class names to ignore when looking for the first stack trace element.
 */
private val fqcnIgnore = listOf(
    Timber::class.java.name,
    Timber.Forest::class.java.name,
    Timber.Tree::class.java.name,
    RustTracingTree::class.java.name,
)

/**
 * A Timber tree that passes logs to the Rust SDK.
 */
internal class RustTracingTree(
    private val target: String,
    private val retrieveFromStackTrace: Boolean,
    private val locationProvider: (() -> LogEventLocation)? = null,
    private val sink: (LogEventLocation, LogLevel, String, String) -> Unit = { location, level, logTarget, text ->
        logEvent(file = location.file, line = location.line, level = level, target = logTarget, message = text)
    },
) : Timber.Tree() {
    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // These typed timing events already carry stage/span/operation identities. Capturing
        // a Throwable stack for every start/finish/queue marker makes the measured path slower,
        // especially on older phones. Preserve every log and all exception/warning locations.
        val isTimingPoint = priority == Log.DEBUG && t == null && tag in TIMING_TAGS
        val location = if (retrieveFromStackTrace && !isTimingPoint) {
            locationProvider?.invoke() ?: getLogEventLocationFromStackTrace()
        } else {
            LogEventLocation("", null)
        }
        val logLevel = priority.toLogLevel()
        sink(location, logLevel, target, if (tag != null) "[$tag] $message" else message)
    }

    /**
     * Extract the [LogEventLocation] from the stack trace.
     */
    private fun getLogEventLocationFromStackTrace(): LogEventLocation {
        return Throwable(null, null).stackTrace
            .first { it.className !in fqcnIgnore }
            .let(LogEventLocation::from)
    }

    private companion object {
        val TIMING_TAGS = setOf("YiZheMessageStage", "YiZheTimelinePerf")
    }
}

/**
 * Convert a log priority to a Rust SDK log level.
 */
private fun Int.toLogLevel(): LogLevel {
    return when (this) {
        Log.VERBOSE -> LogLevel.TRACE
        Log.DEBUG -> LogLevel.DEBUG
        Log.INFO -> LogLevel.INFO
        Log.WARN -> LogLevel.WARN
        Log.ERROR -> LogLevel.ERROR
        else -> LogLevel.DEBUG
    }
}
