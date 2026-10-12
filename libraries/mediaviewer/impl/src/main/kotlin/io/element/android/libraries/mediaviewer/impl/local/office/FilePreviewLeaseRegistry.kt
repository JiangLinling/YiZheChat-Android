/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import io.element.android.libraries.localofficeviewer.api.FilePreviewBackingCleanupStatus
import io.element.android.libraries.localofficeviewer.api.FilePreviewLease
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.localofficeviewer.api.FilePreviewTransferPhase
import io.element.android.libraries.localofficeviewer.api.FilePreviewTransferProgress
import io.element.android.libraries.localofficeviewer.api.FilePreviewVerification
import io.element.android.libraries.localofficeviewer.api.FilePreviewVerificationAuthority
import io.element.android.libraries.matrix.api.media.MediaFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.FilterInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** File-only ownership projection. The registered MediaFile remains the sole authorization authority. */
internal object FilePreviewLeaseRegistry {
    private val entries = ConcurrentHashMap<String, RegisteredFilePreviewLease>()

    fun register(operationId: String, mediaFile: MediaFile): String = register(
        binding = FilePreviewLeaseBinding(operationId, null, mediaFile.uriString()),
        mediaFile = mediaFile,
    )

    fun register(
        binding: FilePreviewLeaseBinding,
        mediaFile: MediaFile,
        verificationAuthority: FilePreviewVerificationAuthority = FilePreviewVerificationAuthority.SECURE_MATRIX_MEDIA_LOADER,
        runtimeFactory: (() -> FilePreviewRuntime?)? = null,
        onRelease: ((FilePreviewLeaseReleaseResult) -> Unit)? = null,
    ): String {
        val uri = mediaFile.uriString()
        if (!SECURE_MEDIA_URI_REGEX.matches(uri)) {
            throw SecurityException("Local file preview requires an authenticated secure-media lease.")
        }
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = randomToken()
            val entry = RegisteredFilePreviewLease(token, binding, uri, mediaFile, verificationAuthority, runtimeFactory, onRelease)
            if (entries.putIfAbsent(token, entry) == null) return token
        }
        error("Unable to allocate a unique file-preview lease token.")
    }

    fun acquire(
        leaseToken: String,
        binding: FilePreviewLeaseBinding,
        expectedSecureMediaAuthority: String,
    ): FilePreviewLease? {
        val entry = entries[leaseToken] ?: return null
        if (!entry.matches(binding, expectedSecureMediaAuthority)) return null
        return entry
    }

    fun acquire(
        leaseToken: String,
        operationId: String,
        expectedSecureMediaAuthority: String,
    ): FilePreviewLease? {
        val entry = entries[leaseToken] ?: return null
        if (!entry.matchesOperation(operationId, expectedSecureMediaAuthority)) return null
        return entry
    }

    fun release(
        leaseToken: String,
        reason: FilePreviewLeaseReleaseReason,
    ): FilePreviewLeaseReleaseResult {
        val entry = entries[leaseToken] ?: return unmatchedReleaseResult()
        return release(entry, reason)
    }

    /** Retry only already-revoked owners. Available leases are never touched. */
    fun retryPendingReleases() {
        entries.values.toList().forEach { entry ->
            entry.pendingReleaseReason()?.let { reason ->
                runCatching { release(entry, reason) }.onFailure {
                    Timber.w("File-preview release observation remains pending.")
                }
            }
        }
    }

    private fun release(
        entry: RegisteredFilePreviewLease,
        reason: FilePreviewLeaseReleaseReason,
    ): FilePreviewLeaseReleaseResult {
        val result = entry.releaseOwned(reason)
        if (entry.canRetire()) entries.remove(entry.leaseToken, entry)
        return result
    }

    private fun randomToken(): String = ByteArray(TOKEN_LENGTH_BYTES)
        .also(SECURE_RANDOM::nextBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun unmatchedReleaseResult() = FilePreviewLeaseReleaseResult(
        leaseTokenMatched = false,
        activeStreamsClosed = false,
        hostHandleCloseCompleted = false,
        backingCleanupStatus = FilePreviewBackingCleanupStatus.NOT_OBSERVABLE,
    )

    private class RegisteredFilePreviewLease(
        override val leaseToken: String,
        private val binding: FilePreviewLeaseBinding,
        override val carrierUri: String,
        private val mediaFile: MediaFile,
        verificationAuthority: FilePreviewVerificationAuthority,
        private val runtimeFactory: (() -> FilePreviewRuntime?)?,
        private val onRelease: ((FilePreviewLeaseReleaseResult) -> Unit)?,
    ) : FilePreviewLease {
        override val operationId: String = binding.operationId
        private val lock = Any()
        private val activeStreams = mutableSetOf<TrackedLeaseInputStream>()
        private var releaseResult: FilePreviewLeaseReleaseResult? = null
        private var deliveredResult: FilePreviewLeaseReleaseResult? = null
        private var hostHandleClosed = false
        private var releaseInProgress = false
        private var releaseReason: FilePreviewLeaseReleaseReason? = null
        private val mutableState = MutableStateFlow<FilePreviewLeaseState>(FilePreviewLeaseState.Available)
        private val mutableProgress = MutableStateFlow(FilePreviewTransferProgress.IndeterminateSecureReady)

        override val verification = FilePreviewVerification(
            authority = verificationAuthority,
            carrierIntegrityVerified = true,
            requiredPlaintextAuthenticationSatisfied = true,
        )
        override val state: StateFlow<FilePreviewLeaseState> = mutableState
        override val progress: StateFlow<FilePreviewTransferProgress> = mutableProgress
        override val tracksRuntimeCleanup: Boolean = runtimeFactory != null

        override suspend fun maintainReadAuthorization(): Boolean {
            if (mutableState.value != FilePreviewLeaseState.Available) return false
            val maintained = try {
                mediaFile.maintainReadAuthorization()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!maintained) release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
            return maintained && mutableState.value == FilePreviewLeaseState.Available
        }

        override fun beginRuntime(): FilePreviewRuntime? = synchronized(lock) {
            if (mutableState.value != FilePreviewLeaseState.Available) null else runtimeFactory?.invoke()
        }

        fun matches(candidateBinding: FilePreviewLeaseBinding, expectedAuthority: String): Boolean =
            binding == candidateBinding &&
                carrierUri.startsWith("content://$expectedAuthority/$SECURE_MEDIA_PATH_VERSION/") &&
                verification.isSatisfied &&
                mutableState.value == FilePreviewLeaseState.Available

        fun matchesOperation(candidateOperationId: String, expectedAuthority: String): Boolean =
            operationId == candidateOperationId &&
                carrierUri.startsWith("content://$expectedAuthority/$SECURE_MEDIA_PATH_VERSION/") &&
                verification.isSatisfied &&
                mutableState.value == FilePreviewLeaseState.Available

        override fun openInputStream(): InputStream = synchronized(lock) {
            check(mutableState.value == FilePreviewLeaseState.Available) { "File-preview lease is unavailable." }
            val raw = try {
                mediaFile.openInputStream()
            } catch (failure: Exception) {
                FilePreviewLeaseRegistry.release(this, FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
                throw failure
            }
            if (mutableState.value != FilePreviewLeaseState.Available) {
                raw.close()
                error("File-preview lease was released before stream use.")
            }
            TrackedLeaseInputStream(raw, this).also(activeStreams::add)
        }

        override fun release(reason: FilePreviewLeaseReleaseReason): FilePreviewLeaseReleaseResult =
            FilePreviewLeaseRegistry.release(this, reason)

        fun releaseOwned(reason: FilePreviewLeaseReleaseReason): FilePreviewLeaseReleaseResult {
            val streams = synchronized(lock) {
                if (canRetire()) return checkNotNull(releaseResult)
                if (releaseInProgress) return snapshot()
                releaseInProgress = true
                if (releaseReason == null) releaseReason = reason
                mutableState.value = FilePreviewLeaseState.Released(checkNotNull(releaseReason))
                mutableProgress.value = FilePreviewTransferProgress(
                    phase = FilePreviewTransferPhase.TERMINAL,
                    verifiedBytes = null,
                    totalBytes = null,
                )
                activeStreams.toList()
            }
            // Never invoke a stream, MediaFile or observer while holding the owner
            // lock. A closing stream may wait for a read whose finally reports here.
            val result = try {
                streams.forEach { stream -> runCatching { stream.closeFromLease() } }
                if (!synchronized(lock) { hostHandleClosed }) {
                    val closed = runCatching { mediaFile.close() }.isSuccess
                    synchronized(lock) { hostHandleClosed = closed }
                }
                val observed = synchronized(lock) { snapshot().also { releaseResult = it } }
                if (synchronized(lock) { deliveredResult != observed }) {
                    onRelease?.invoke(observed)
                    synchronized(lock) { deliveredResult = observed }
                }
                observed
            } finally {
                synchronized(lock) { releaseInProgress = false }
            }
            // A last read can finish between the snapshot and observer delivery.
            // Publish that genuine completion without requiring another UI action.
            return if (synchronized(lock) { snapshot() != result }) releaseOwned(reason) else result
        }

        fun streamClosed(stream: TrackedLeaseInputStream) {
            val retry = synchronized(lock) {
                activeStreams.remove(stream)
                releaseReason?.takeUnless { releaseInProgress }
            }
            if (retry != null) runCatching { FilePreviewLeaseRegistry.release(this, retry) }.onFailure {
                Timber.w("File-preview release observation remains pending after read exit.")
            }
        }

        fun pendingReleaseReason(): FilePreviewLeaseReleaseReason? = synchronized(lock) { releaseReason }

        fun canRetire(): Boolean = synchronized(lock) {
            releaseReason != null && activeStreams.isEmpty() && hostHandleClosed &&
                releaseResult?.activeStreamsClosed == true && deliveredResult == releaseResult
        }

        private fun snapshot() = FilePreviewLeaseReleaseResult(
            leaseTokenMatched = true,
            activeStreamsClosed = activeStreams.isEmpty(),
            hostHandleCloseCompleted = hostHandleClosed,
            backingCleanupStatus = FilePreviewBackingCleanupStatus.NOT_OBSERVABLE,
        )

        fun underlyingUnavailable(failure: Exception): Nothing {
            try {
                FilePreviewLeaseRegistry.release(this, FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private class TrackedLeaseInputStream(
        source: InputStream,
        private val owner: RegisteredFilePreviewLease,
    ) : FilterInputStream(source) {
        private val closeRequested = AtomicBoolean(false)
        private val stateLock = Any()
        private val closeLock = Any()
        private var rawCloseCompleted = false
        private var reads = 0

        override fun read(): Int = protect { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            return protect(discard = { buffer.fill(0, offset, offset + length) }) { super.read(buffer, offset, length) }
        }

        override fun skip(byteCount: Long): Long = protect { super.skip(byteCount) }

        override fun close() {
            closeRequested.set(true)
            synchronized(closeLock) {
                if (!synchronized(stateLock) { rawCloseCompleted }) {
                    super.close()
                    synchronized(stateLock) { rawCloseCompleted = true }
                }
            }
            if (synchronized(stateLock) { rawCloseCompleted && reads == 0 }) owner.streamClosed(this)
        }

        fun closeFromLease() = close()

        private inline fun <T> protect(discard: () -> Unit = {}, block: () -> T): T {
            synchronized(stateLock) {
                if (closeRequested.get()) throw java.io.IOException("File-preview stream is closed.")
                reads++
            }
            try {
                val value = block()
                if (closeRequested.get()) throw java.io.IOException("File-preview stream was revoked during read.")
                return value
            } catch (failure: Exception) {
                discard()
                owner.underlyingUnavailable(failure)
            } finally {
                val finished = synchronized(stateLock) {
                    reads--
                    rawCloseCompleted && reads == 0
                }
                if (finished) owner.streamClosed(this)
            }
        }
    }

    private const val TOKEN_LENGTH_BYTES = 24
    private const val MAX_TOKEN_ATTEMPTS = 4
    private const val SECURE_MEDIA_PATH_VERSION = "v1"
    private val SECURE_RANDOM = SecureRandom()
    private val SECURE_MEDIA_URI_REGEX = Regex("^content://[^/]+\\.securemedia/$SECURE_MEDIA_PATH_VERSION/[0-9a-f]{32,}$")
}
