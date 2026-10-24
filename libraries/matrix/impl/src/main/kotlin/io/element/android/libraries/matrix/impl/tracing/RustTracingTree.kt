/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.tracing

import android.os.Looper
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
    private val minimumPriority: Int? = null,
    private val locationProvider: (() -> LogEventLocation)? = null,
    private val diagnosticDispatcher: ((() -> Unit) -> Unit)? = null,
    private val isMainThread: () -> Boolean = { Thread.currentThread() === Looper.getMainLooper().thread },
    private val sink: (LogEventLocation, LogLevel, String, String) -> Unit = { location, level, logTarget, text ->
        logEvent(file = location.file, line = location.line, level = level, target = logTarget, message = text)
    },
) : Timber.Tree() {
    // Timber consumes the tag before this check and formats only accepted messages.
    public override fun isLoggable(tag: String?, priority: Int): Boolean = minimumPriority?.let { priority >= it } ?: true

    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Also defend direct callers that bypass Timber.prepareLog; this is not the formatting gate.
        if (!isLoggable(tag, priority)) return
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
        val text = if (tag != null) "[$tag] $message" else message
        // Only known debug diagnostics are queued. Capture the source and immutable
        // payload at the caller; warnings, errors, exceptions and audit tags stay synchronous.
        if (priority == Log.DEBUG && t == null && tag in DEFERRED_DIAGNOSTIC_TAGS && diagnosticDispatcher != null && isMainThread()) {
            diagnosticDispatcher.invoke { sink(location, logLevel, target, text) }
        } else {
            sink(location, logLevel, target, text)
        }
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
        val TIMING_TAGS = setOf("YiZheMessageStage", "YiZheTimelinePerf", "YiZheDeviceTrust")
        val DEFERRED_DIAGNOSTIC_TAGS = TIMING_TAGS + setOf("Lifecycle", "Analytics", "Sentry")
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
        Log.ERROR, Log.ASSERT -> LogLevel.ERROR
        else -> LogLevel.DEBUG
    }
}
