/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.rageshake.impl.reporter

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.DiagnosticCollectionState
import io.element.android.security.securestorage.api.SecureDiagnosticLogStore
import io.element.android.security.securestorage.api.SecureDiagnosticSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** One immutable snapshot for both the in-memory viewer and the existing upload endpoint. */
@SingleIn(AppScope::class)
@Inject
class DiagnosticSnapshotProvider(private val store: SecureDiagnosticLogStore) {
    private val mutex = Mutex()
    @Volatile private var current: DiagnosticBundle? = null

    suspend fun get(refresh: Boolean = false): DiagnosticBundle = mutex.withLock {
        if (!refresh) current?.let { return@withLock it }
        val snapshot = store.snapshot()
        withContext(Dispatchers.Default) { encode(snapshot) }.also { current = it }
    }

    fun clear() {
        current = null
    }

    internal fun encode(snapshot: SecureDiagnosticSnapshot): DiagnosticBundle {
        val jsonLines = snapshot.events.joinToString(separator = "\n", postfix = if (snapshot.events.isEmpty()) "" else "\n") { event ->
            JSONObject().put("timestamp_ms", event.timestampEpochMillis).put("priority", event.priority)
                .put("source", event.source).put("details", event.details).put("stack", event.stack)
                .put("location", event.location).put("redacted_fields", event.redactedFields).toString()
        }
        val bytes = jsonLines.toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        val nativeCount = snapshot.events.count { it.source == "org.matrix.rust.sdk" }
        val nativeUnclassified = snapshot.events.count { it.details.contains("SDK_UNCLASSIFIED_RECORD") }
        val nativeUnavailable = snapshot.events.any { it.details.contains("SDK_CAPTURE_UNAVAILABLE") }
        val coverage = JSONObject().put("native_records", nativeCount).put("native_unclassified_records", nativeUnclassified)
            .put(
                "native_bridge",
                when {
                nativeUnavailable -> "UNAVAILABLE"
                nativeUnclassified > 0 -> "PARTIAL"
                nativeCount > 0 -> "RECORDS_CAPTURED"
                snapshot.events.any { it.details.contains("SDK_CAPTURE_STARTED") } -> "STARTED_NO_RECORDS"
                else -> "NOT_OBSERVED"
            }
            )
            .put("previous_exit_records", snapshot.events.count { it.source == "ApplicationExit" })
            .put("previous_exit_unsupported", snapshot.events.any { it.details.contains("SDK_EXIT_TRACE_UNSUPPORTED") })
        val manifest = JSONObject().put("schema_version", 1).put("snapshot_id", snapshot.id).put("state", snapshot.state.name)
            .put("record_count", snapshot.recordCount).put("uncompressed_bytes", bytes.size)
            .put("first_timestamp_ms", snapshot.firstTimestampEpochMillis ?: JSONObject.NULL)
            .put("last_timestamp_ms", snapshot.lastTimestampEpochMillis ?: JSONObject.NULL)
            .put("dropped_events", snapshot.droppedEvents).put("unreadable_segments", snapshot.unreadableSegments)
            .put("failure_code", snapshot.failureCode ?: JSONObject.NULL).put("source_coverage", coverage).toString()
        val byteCount = bytes.size
        bytes.fill(0)
        val status = when (snapshot.state) {
            DiagnosticCollectionState.AVAILABLE -> "已采集"
            DiagnosticCollectionState.PARTIAL -> "采集不完整，已保留可读取部分"
            DiagnosticCollectionState.EMPTY -> "尚无运行日志，请重现问题后刷新"
            DiagnosticCollectionState.KEY_UNAVAILABLE -> "日志密钥不可用，无法读取，不能提交空日志"
            DiagnosticCollectionState.STORAGE_ERROR -> "日志存储异常，无法读取，不能提交空日志"
        }
        val summary = buildString {
            append(status).append("\n记录：").append(snapshot.recordCount).append(" 条；原始字节：").append(byteCount)
            append("；压缩字节：").append(compressed.size)
            append("\n时间：").append(snapshot.firstTimestampEpochMillis?.let(Instant::ofEpochMilli) ?: "无")
            append(" 至 ").append(snapshot.lastTimestampEpochMillis?.let(Instant::ofEpochMilli) ?: "无")
            append("\n丢失：").append(snapshot.droppedEvents).append(" 条；损坏文件：").append(snapshot.unreadableSegments)
            snapshot.failureCode?.let { append("\n原因：").append(it) }
            append("\nSDK 原生日志：").append(nativeCount).append(" 条；无法分类：").append(nativeUnclassified).append(" 条")
            if (nativeUnavailable) append("；原生采集不可用")
            append("\n快照：").append(snapshot.id)
        }
        return DiagnosticBundle(snapshot, manifest, compressed, summary, jsonLines)
    }
}

class DiagnosticBundle(
    val snapshot: SecureDiagnosticSnapshot,
    val manifest: String,
    val compressed: ByteArray,
    val summary: String,
    val text: String,
) {
    val canUpload: Boolean get() = snapshot.recordCount > 0 &&
        snapshot.state in setOf(DiagnosticCollectionState.AVAILABLE, DiagnosticCollectionState.PARTIAL)
}
