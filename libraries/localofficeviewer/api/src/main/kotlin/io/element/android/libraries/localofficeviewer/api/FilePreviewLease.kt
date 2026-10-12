/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.api

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

enum class FilePreviewVerificationAuthority {
    SECURE_MATRIX_MEDIA_LOADER,
    LOCAL_SECURE_FILE_STORE,
}

/** Both layers must be satisfied; carrier verification never substitutes for required plaintext authentication. */
data class FilePreviewVerification(
    val authority: FilePreviewVerificationAuthority,
    val carrierIntegrityVerified: Boolean,
    val requiredPlaintextAuthenticationSatisfied: Boolean,
) {
    val isSatisfied: Boolean
        get() = carrierIntegrityVerified && requiredPlaintextAuthenticationSatisfied
}

enum class FilePreviewTransferPhase {
    SECURE_READY,
    TERMINAL,
}

/** Null byte counts mean that the host has no authoritative byte-progress projection. */
data class FilePreviewTransferProgress(
    val phase: FilePreviewTransferPhase,
    val verifiedBytes: Long?,
    val totalBytes: Long?,
) {
    init {
        require((verifiedBytes == null) == (totalBytes == null))
        require(verifiedBytes == null || verifiedBytes >= 0L)
        require(totalBytes == null || totalBytes >= checkNotNull(verifiedBytes))
    }

    companion object {
        val IndeterminateSecureReady = FilePreviewTransferProgress(
            phase = FilePreviewTransferPhase.SECURE_READY,
            verifiedBytes = null,
            totalBytes = null,
        )
    }
}

enum class FilePreviewLeaseReleaseReason {
    USER_CLOSE,
    CANCELLED,
    ATTACHMENT_SWITCHED,
    APP_LOCKED,
    ACCESS_REVOKED,
    ACCOUNT_EXITED,
    SECURITY_BLOCKED,
    HOST_DISPOSED,
    UNDERLYING_UNAVAILABLE,
    SESSION_EXPIRED,
}

/** Facts about this viewer instance, after its blocking work and owned buffers have stopped. */
data class FilePreviewRuntimeCleanup(
    val runtimeReleased: Boolean,
    val activeWorkFinished: Boolean,
    val ownedPayloadCleared: Boolean,
) {
    val isComplete: Boolean get() = runtimeReleased && activeWorkFinished && ownedPayloadCleared
}

/** Bound by the host to one runtime generation; completing it cannot release another generation. */
fun interface FilePreviewRuntime {
    fun complete(cleanup: FilePreviewRuntimeCleanup)
}

sealed interface FilePreviewLeaseState {
    data object Available : FilePreviewLeaseState
    data class Released(val reason: FilePreviewLeaseReleaseReason) : FilePreviewLeaseState
}

enum class FilePreviewBackingCleanupStatus {
    /** MediaFile.close returned, but its asynchronous backing-object cleanup has no receipt at this boundary. */
    NOT_OBSERVABLE,
}

data class FilePreviewLeaseReleaseResult(
    val leaseTokenMatched: Boolean,
    val activeStreamsClosed: Boolean,
    val hostHandleCloseCompleted: Boolean,
    val backingCleanupStatus: FilePreviewBackingCleanupStatus,
)

/**
 * A projection of one already-authorized host MediaFile. It never creates or renews authorization.
 *
 * [state] is a current-value stream. Consumers subscribe to it before opening the first stream;
 * [openInputStream] rechecks the same state atomically so a release between subscription and use
 * cannot be missed. Availability is authoritative even when no numeric expiry is exposed.
 */
interface FilePreviewLease : AutoCloseable {
    /** A released host cannot request fresh admission or become available again. */
    suspend fun maintainReadAuthorization(): Boolean = state.value == FilePreviewLeaseState.Available

    val leaseToken: String
    val operationId: String

    /** Carrier identity only. Consumers must still require [verification] and current [state]. */
    val carrierUri: String
    val verification: FilePreviewVerification
    val state: StateFlow<FilePreviewLeaseState>
    val progress: StateFlow<FilePreviewTransferProgress>
    val tracksRuntimeCleanup: Boolean get() = false

    fun openInputStream(): InputStream

    /** Register before opening a renderer. Existing received-media hosts can omit local receipt tracking. */
    fun beginRuntime(): FilePreviewRuntime? = null

    fun release(reason: FilePreviewLeaseReleaseReason): FilePreviewLeaseReleaseResult

    override fun close() {
        release(FilePreviewLeaseReleaseReason.HOST_DISPOSED)
    }
}
