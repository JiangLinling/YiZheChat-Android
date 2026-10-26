/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.api

enum class DiagnosticCollectionState { AVAILABLE, EMPTY, KEY_UNAVAILABLE, STORAGE_ERROR, PARTIAL }

/** Redacted diagnostic facts only. This is not a content, authorization or lifecycle receipt. */
data class SecureDiagnosticEvent(
    val timestampEpochMillis: Long,
    val priority: Int,
    val source: String,
    val details: String,
    val stack: String,
    val location: String = "",
    val redactedFields: Int = 0,
)

data class SecureDiagnosticSnapshot(
    val id: String,
    val state: DiagnosticCollectionState,
    val events: List<SecureDiagnosticEvent>,
    val droppedEvents: Long,
    val unreadableSegments: Int,
    val failureCode: String?,
) {
    val recordCount: Int get() = events.size
    val firstTimestampEpochMillis: Long? get() = events.minOfOrNull { it.timestampEpochMillis }
    val lastTimestampEpochMillis: Long? get() = events.maxOfOrNull { it.timestampEpochMillis }
}

data class DiagnosticMigrationResult(val filesMigrated: Int, val filesFailed: Int, val recordsMigrated: Int)

/** Installation-scoped LOG storage; deliberately independent of account-vault readiness. */
interface SecureDiagnosticLogStore {
    /** Implementations redact at ingress before queueing, encrypt at rest and expose overflow. */
    fun append(priority: Int, source: String?, message: String, throwable: Throwable? = null, location: String? = null)

    /** Bounded crash-path flush. False is an actual failure, never permission to write plaintext. */
    fun flushBlocking(timeoutMillis: Long = 1500): Boolean

    /** Typed collection/cleanup failure. Never accepts raw exception messages or content. */
    fun reportFailure(code: String)

    suspend fun snapshot(): SecureDiagnosticSnapshot

    /** Caller owns old-file cleanup. Only authenticated, fully committed migration is counted as success. */
    suspend fun migrateLegacy(files: List<java.io.File>): DiagnosticMigrationResult
}
