/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x.initializer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import io.element.android.features.rageshake.impl.crash.CrashDataStore
import io.element.android.security.securestorage.api.SecureDiagnosticLogStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Same pre-login collector for Debug and the internal release. No raw files or other-app log access. */
internal object SecureDiagnostics {
    private val started = AtomicBoolean()
    private val timingTags = setOf("YiZheTimelinePerf", "YiZheMessageStage", "YiZheDeviceTrust", "RTCSetup", "RTCWidgetSetup")

    fun start(context: Context, store: SecureDiagnosticLogStore, crashes: CrashDataStore) {
        if (!started.compareAndSet(false, true)) return
        Timber.plant(object : Timber.Tree() {
            override fun isLoggable(tag: String?, priority: Int) = true
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                val isTiming = t == null && priority < 5 && tag in timingTags
                val frame = if (isTiming) {
                    null
                } else {
                    Throwable().stackTrace.firstOrNull {
                    !it.className.startsWith("timber.") && !it.className.startsWith(SecureDiagnostics::class.java.name)
                }
                }
                store.append(
                    priority,
                    tag ?: frame?.className?.substringAfterLast('.'),
                    message,
                    t,
                    frame?.let { "${it.className}.${it.methodName}:${it.lineNumber.coerceAtLeast(0)}" } ?: tag
                )
            }
        })
        store.append(4, "YiZheDiagnostics", "stage=app_collector_started SDK_APP_CAPTURE_STARTED")
        Thread({
            try {
                runBlocking {
                val oldDirectory = File(context.cacheDir, "logs")
                val oldFiles = oldDirectory.walkTopDown().filter { it.isFile }.take(25).toList()
                val migration = store.migrateLegacy(oldFiles)
                val crash = crashes.crashInfo().first()
                if (crash.isNotBlank() && !crash.startsWith("崩溃详情已") && !crash.startsWith("历史崩溃日志迁移失败") &&
                    !crash.startsWith("发生崩溃，请查看")) {
                    crash.lineSequence().take(1024).forEach { store.append(6, "LegacyCrash", it, location = "legacy-crash") }
                    val persisted = store.flushBlocking(5000)
                    // Old preferences may contain messages and navigation state. Never retain that plaintext.
                    crashes.setCrashData(if (persisted) "崩溃详情已保存到加密诊断日志" else "历史崩溃日志迁移失败，部分诊断已丢失")
                    if (!persisted) store.reportFailure("LEGACY_CRASH_MIGRATION_FAILED")
                }
                var cleanupFailures = 0
                oldFiles.forEach { if (it.exists() && !it.delete()) cleanupFailures++ }
                if (oldDirectory.walkTopDown().any { it.isFile }) store.reportFailure("LEGACY_PLAINTEXT_CLEANUP_INCOMPLETE")
                if (migration.filesFailed > 0) store.reportFailure("LEGACY_MIGRATION_FAILED_PLAINTEXT_REMOVED")
                if (cleanupFailures > 0) store.reportFailure("LEGACY_PLAINTEXT_CLEANUP_FAILED")
                store.append(
                    4,
                    "YiZheDiagnostics",
                    "stage=legacy_migration count=${migration.recordsMigrated} item_count=${migration.filesFailed} retry_count=$cleanupFailures"
                )
            }
            } catch (_: Exception) {
                store.reportFailure("LEGACY_MIGRATION_FAILED")
            } finally {
                collectPreviousExit(context, store)
            }
        }, "YiZheDiagnosticMigration").apply { isDaemon = true }.start()
        Thread({ captureNative(store) }, "YiZheNativeDiagnostics").apply { isDaemon = true }.start()
    }

    private fun captureNative(store: SecureDiagnosticLogStore) {
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(NativeDiagnosticLine.command(Process.myPid(), Process.myUid())).redirectErrorStream(false).start()
            store.append(4, "YiZheDiagnostics", "SDK_CAPTURE_STARTED")
            process.inputStream.bufferedReader().use { reader ->
                val parser = NativeDiagnosticLine()
                // Filter before ingress; raw data is only a bounded line in process memory, never a temp file.
                val line = StringBuilder()
                var oversized = false
                while (true) {
                    val next = reader.read()
                    if (next < 0) break
                    if (next == 10) {
                        if (line.isBlank() || line.startsWith("--------- beginning of ")) {
                            line.setLength(0)
                            oversized = false
                            continue
                        }
                        if (!oversized) {
                            val parsed = parser.parse(line.toString())
                            if (parsed != null) {
                                store.append(
                                    parsed.priority,
                                    parsed.source,
                                    parsed.message,
                                    location = if (parsed.source == "org.matrix.rust.sdk") "native-sdk" else "android-logcat",
                                )
                            } else if (!parser.lastWasForwardedApp) {
                                store.append(5, "YiZheDiagnostics", "SDK_UNCLASSIFIED_RECORD")
                                store.reportFailure("SDK_SOURCE_PARTIAL")
                            }
                        } else {
                            store.reportFailure("SDK_RECORD_TOO_LARGE")
                        }
                        line.setLength(0)
                        oversized = false
                    } else if (line.length < 128 * 1024) {
                        line.append(next.toChar())
                    } else {
                        oversized = true
                    }
                }
            }
            store.append(5, "YiZheDiagnostics", "SDK_CAPTURE_UNAVAILABLE status_code=${process.waitFor()}")
            store.reportFailure("SDK_CAPTURE_UNAVAILABLE")
        } catch (_: Exception) {
            store.append(6, "YiZheDiagnostics", "SDK_CAPTURE_UNAVAILABLE")
            store.reportFailure("SDK_CAPTURE_UNAVAILABLE")
        } finally {
            process?.destroy()
        }
    }

    private fun collectPreviousExit(context: Context, store: SecureDiagnosticLogStore) {
        if (Build.VERSION.SDK_INT < 30) {
            store.append(4, "YiZheDiagnostics", "SDK_EXIT_TRACE_UNSUPPORTED")
            return
        }
        try {
            val preferences = context.getSharedPreferences("diagnostic-exit-watermark", Context.MODE_PRIVATE)
            val watermark = preferences.getLong("timestamp", 0)
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .filter { it.timestamp > watermark && (it.processName == context.packageName || it.processName.startsWith(context.packageName + ":")) }
            exits.sortedBy { it.timestamp }.forEach { exit ->
                store.append(
                    6,
                    "ApplicationExit",
                    "SDK_PROCESS_EXIT reason=${exit.reason} status_code=${exit.status} pss=${exit.pss} rss=${exit.rss} epoch_ms=${exit.timestamp}"
                )
                if (exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) {
                    val trace = exit.traceInputStream
                    if (trace == null) {
                        store.append(5, "ApplicationExit", "SDK_EXIT_TRACE_UNAVAILABLE")
                    } else {
                        trace.bufferedReader().useLines { lines ->
                        lines.take(1024).forEach { store.append(6, "ApplicationExit", it.take(4096), location = "previous-exit") }
                    }
                    }
                }
            }
            if (exits.isNotEmpty() && store.flushBlocking(5000)) {
                preferences.edit().putLong("timestamp", exits.maxOf { it.timestamp }).apply()
            }
        } catch (_: Exception) {
            store.append(5, "ApplicationExit", "SDK_EXIT_TRACE_UNAVAILABLE")
        }
    }
}
