/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaRandomAccessProofPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaSeekLimitsPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaSeekMapperPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaSeekResultPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.ValidatedSegmentedMediaManifestPrototype
import io.element.android.security.securestorage.api.SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface SegmentedDecoderLifecycle {
    fun beginStop()
    fun flushAndRelease()
}

internal enum class SegmentedPlaybackCloseCause {
    DATA_SOURCE_CLOSED,
    OWNER_EXIT,
    SEEK_REPLACED,
    READ_FAILURE,
}

internal enum class SegmentedPlaybackCloseWait {
    C_REQUEST,
    READER_BORROW,
    DECODER_FLUSH,
}

internal data class SegmentedPlaybackCloseReceipt(
    val cause: SegmentedPlaybackCloseCause,
    val failures: List<Throwable>,
) {
    val succeeded: Boolean get() = failures.isEmpty()
}

internal sealed interface SegmentedPlaybackSessionState {
    data class Open(val generation: Long) : SegmentedPlaybackSessionState
    data class Closing(
        val generation: Long,
        val waitingFor: Set<SegmentedPlaybackCloseWait>,
    ) : SegmentedPlaybackSessionState
    data class Closed(val receipt: SegmentedPlaybackCloseReceipt) : SegmentedPlaybackSessionState
}

/**
 * Owns one D1 init+fragment source. Cancellation is never treated as physical worker termination.
 */
internal class SegmentedPlaybackSession internal constructor(
    private val validatedManifest: ValidatedSegmentedMediaManifestPrototype,
    private val init: SegmentedAuthorizedResourceHandle,
    private val segment: SegmentedAuthorizedResourceHandle,
    private val readContext: SegmentedMediaReadContextPrototype,
    private val proof: SegmentedMediaRandomAccessProofPrototype,
    private val workerDispatcher: CoroutineDispatcher,
    private val finalizerDispatcher: CoroutineDispatcher,
    private val decoderLifecycle: SegmentedDecoderLifecycle,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob)
    private val closeResult = CompletableDeferred<SegmentedPlaybackCloseReceipt>()
    private val sessionId = UUID.randomUUID().toString()
    private val requestIds = AtomicLong()
    private val closeFailures = mutableListOf<Throwable>()
    private val initialGeneration = readContext.generation
    private val virtualLength = Math.addExact(init.descriptor.plainLength, segment.descriptor.plainLength)

    private var generation = initialGeneration
    private var status = Status.OPEN
    private var closeCause: SegmentedPlaybackCloseCause? = null
    private var closeSideEffectsComplete = false
    private var finalizationStarted = false
    private var flushInProgress = false
    private var sourceIssued = false
    private var activeRead: ActiveRead? = null
    private var closedReceipt: SegmentedPlaybackCloseReceipt? = null

    init {
        require(workerDispatcher !== finalizerDispatcher) {
            "Worker and close-finalizer dispatchers must be distinct objects."
        }
        require(proof.matches(init.descriptor, segment.descriptor, readContext)) {
            "Random-access proof is not bound to this exact resource/session generation."
        }
    }

    val uri: Uri = Uri.Builder()
        .scheme("yizhe-segmented")
        .authority("local")
        .appendPath(sessionId)
        .build()

    fun dataSourceFactory(): DataSource.Factory = SecureSegmentDataSource.Factory(this)

    fun state(): SegmentedPlaybackSessionState = lock.withLock { stateLocked() }

    internal fun cursor(publicationEpoch: Long): SegmentedFragmentCursor = lock.withLock {
        check(status == Status.OPEN) { "Segmented playback session is not open." }
        val descriptor = segment.descriptor
        SegmentedFragmentCursor(
            manifestIdentity = SegmentedManifestIdentityFactory.create(validatedManifest),
            segmentIndex = descriptor.index,
            publicationEpoch = publicationEpoch,
            readGeneration = generation,
            segmentStartUs = descriptor.startUs,
            segmentEndUsExclusive = Math.addExact(descriptor.startUs, descriptor.durationUs),
            assetDurationUs = validatedManifest.manifest.durationUs,
        )
    }

    internal fun planOneFragmentSeek(
        targetTimeUs: Long,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
    ): SegmentedSeekIntent = lock.withLock {
        planExactFragmentLocked(targetTimeUs, authorizationGate, SegmentedReplacementKind.SEEK)
    }

    internal fun planNextFragment(
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
    ): SegmentedSeekIntent? = lock.withLock {
        val next = validatedManifest.manifest.segments.getOrNull(segment.descriptor.index + 1) ?: return@withLock null
        planExactFragmentLocked(next.startUs, authorizationGate, SegmentedReplacementKind.ADVANCE).also { intent ->
            check(intent.segment == next) { "Advance did not select the exact next manifest fragment." }
        }
    }

    private fun planExactFragmentLocked(
        targetTimeUs: Long,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        kind: SegmentedReplacementKind,
    ): SegmentedSeekIntent {
        check(status == Status.OPEN) { "Segmented playback session is not open for replacement planning." }
        check(proof.matches(init.descriptor, segment.descriptor, readContext)) {
            "The constructor-bound random-access proof no longer matches this session."
        }
        return when (val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validatedManifest,
            targetTimeUs = targetTimeUs,
            context = readContext,
            authorizationGate = authorizationGate,
            sourceSegment = segment.descriptor,
            randomAccessProofs = listOf(proof),
            limits = SegmentedMediaSeekLimitsPrototype(maximumSegments = 1),
        )) {
            is SegmentedMediaSeekResultPrototype.Rejected -> throw SegmentedSeekPlanningException(result.reason.name)
            is SegmentedMediaSeekResultPrototype.Planned -> {
                val planned = result.plan
                val plannedSegment = planned.segments.singleOrNull()
                    ?: throw SegmentedSeekPlanningException("ONE_FRAGMENT_PLAN_REQUIRED")
                if (planned.init != init.descriptor || plannedSegment !in validatedManifest.manifest.segments) {
                    throw SegmentedSeekPlanningException("VALIDATED_RESOURCE_MISMATCH")
                }
                SegmentedSeekIntent(
                    manifestIdentity = SegmentedManifestIdentityFactory.create(validatedManifest),
                    requestedTimeUs = planned.requestedTimeUs,
                    randomAccessStartUs = planned.randomAccessStartUs,
                    init = planned.init,
                    segment = plannedSegment,
                    validatedManifest = validatedManifest,
                    sourceContext = readContext,
                    kind = kind,
                )
            }
        }
    }

    suspend fun closeAndAwaitQuiescence(
        cause: SegmentedPlaybackCloseCause = SegmentedPlaybackCloseCause.OWNER_EXIT,
    ): SegmentedPlaybackCloseReceipt {
        requestClose(cause)
        return closeResult.await()
    }

    fun requestClose(cause: SegmentedPlaybackCloseCause) {
        var firstClose = false
        var worker: Job? = null
        lock.withLock {
            if (status == Status.OPEN) {
                status = Status.CLOSING
                // CLOSED/CLOSING status is the admission barrier. A wrapping diagnostic generation keeps close
                // total so the replacement coordinator can report generation overflow only after real quiescence.
                generation = if (generation == Long.MAX_VALUE) Long.MIN_VALUE else generation + 1L
                closeCause = cause
                worker = activeRead?.job
                firstClose = true
                changed.signalAll()
            }
        }
        if (!firstClose) return

        recordCloseFailure { decoderLifecycle.beginStop() }
        // Revocation plus coroutine cancellation both race C's final admission. Neither is treated as physical stop.
        recordCloseFailure { init.lease.close() }
        recordCloseFailure { segment.lease.close() }
        worker?.cancel(CancellationException("Segmented playback session is closing."))

        lock.withLock {
            closeSideEffectsComplete = true
            changed.signalAll()
        }
        maybeScheduleFinalization()
    }

    internal fun issueDataSource(): SecureSegmentDataSource {
        val expectedGeneration = lock.withLock {
            check(status == Status.OPEN) { "Segmented playback session is not open." }
            check(!sourceIssued) { "D1 prepared source is one-shot." }
            sourceIssued = true
            generation
        }
        return SecureSegmentDataSource(this, expectedGeneration)
    }

    internal fun lengthFor(expectedGeneration: Long): Long = lock.withLock {
        checkOpenGenerationLocked(expectedGeneration)
        virtualLength
    }

    internal fun read(
        expectedGeneration: Long,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        requestedLength: Int,
    ): Int {
        require(requestedLength > 0)
        require(destinationOffset >= 0 && destinationOffset <= destination.size - requestedLength)

        val request: ActiveRead
        lock.withLock {
            checkOpenGenerationLocked(expectedGeneration)
            if (activeRead != null) throw IOException("A segmented authenticated read is already in flight.")
            val mapped = mapRange(position, requestedLength)
            request = ActiveRead(
                id = requestIds.incrementAndGet(),
                generation = expectedGeneration,
                destination = destination,
                destinationOffset = destinationOffset,
                resource = mapped.resource,
                resourceOffset = mapped.resourceOffset,
                byteCount = mapped.byteCount,
            )
            activeRead = request
            val job = scope.launch(workerDispatcher) { executeRead(request) }
            request.job = job
            job.invokeOnCompletion { failure -> workerCompleted(request, failure) }
        }

        var interrupted = false
        val terminal: ReadTerminal
        var scheduleFinalization = false
        lock.lock()
        try {
            while (request.terminal == null || !request.workerCompleted) {
                try {
                    changed.await()
                } catch (_: InterruptedException) {
                    // The destination remains borrowed. Remember interruption but wait for physical worker exit.
                    interrupted = true
                }
            }
            terminal = requireNotNull(request.terminal)
            request.destination = null
            request.readerAcknowledged = true
            check(activeRead === request)
            activeRead = null
            changed.signalAll()
            scheduleFinalization = shouldStartFinalizationLocked()
        } finally {
            lock.unlock()
        }
        if (scheduleFinalization) startFinalization()
        if (interrupted) Thread.currentThread().interrupt()

        return when (terminal) {
            is ReadTerminal.Copied -> terminal.byteCount
            is ReadTerminal.Failed -> {
                if (interrupted) {
                    throw InterruptedIOException("Interrupted while awaiting authenticated read quiescence.").also {
                        it.initCause(terminal.failure)
                    }
                }
                throw terminal.failure
            }
        }
    }

    private suspend fun executeRead(request: ActiveRead) {
        val destination = requireNotNull(request.destination)
        val terminal = try {
            when (val result = request.resource.lease.copyAuthenticatedRangeInto(
                request = SecureMediaAuthenticatedReadRequest(
                    offset = request.resourceOffset,
                    byteCount = request.byteCount,
                    purpose = SecureMediaReadPurpose.SEGMENTED_DECODER,
                ),
                destination = destination,
                destinationOffset = request.destinationOffset,
            )) {
                is SecureMediaAuthenticatedReadResult.Copied -> {
                    if (result.byteCount != request.byteCount) {
                        ReadTerminal.Failed(IOException("Authenticated segmented read was short."))
                    } else {
                        ReadTerminal.Copied(result.byteCount, request.generation)
                    }
                }
                is SecureMediaAuthenticatedReadResult.Rejected -> {
                    ReadTerminal.Failed(IOException("Authenticated segmented read rejected: ${result.reason}."))
                }
            }
        } catch (failure: CancellationException) {
            ReadTerminal.Failed(IOException("Authenticated segmented read was cancelled.", failure))
        } catch (failure: IOException) {
            ReadTerminal.Failed(failure)
        } catch (failure: Throwable) {
            ReadTerminal.Failed(IOException("Authenticated segmented read failed.", failure))
        }
        publishTerminal(request, terminal)
    }

    private fun publishTerminal(request: ActiveRead, terminal: ReadTerminal) {
        lock.withLock {
            if (request.terminal == null) request.terminal = terminal
            changed.signalAll()
        }
    }

    private fun workerCompleted(request: ActiveRead, failure: Throwable?) {
        lock.withLock {
            if (request.terminal == null) {
                request.terminal = ReadTerminal.Failed(
                    IOException("Authenticated segmented worker terminated before publishing a result.", failure)
                )
            }
            request.workerCompleted = true
            changed.signalAll()
        }
    }

    private fun mapRange(position: Long, requestedLength: Int): MappedRange {
        if (position < 0L || position >= virtualLength) throw IOException("Segmented DataSource position is invalid.")
        val capped = minOf(requestedLength, SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES)
        return if (position < init.descriptor.plainLength) {
            val count = minOf(capped.toLong(), init.descriptor.plainLength - position).toInt()
            MappedRange(init, position, count)
        } else {
            val segmentOffset = position - init.descriptor.plainLength
            val count = minOf(capped.toLong(), segment.descriptor.plainLength - segmentOffset).toInt()
            MappedRange(segment, segmentOffset, count)
        }
    }

    private fun checkOpenGenerationLocked(expectedGeneration: Long) {
        if (status != Status.OPEN || generation != expectedGeneration) {
            throw IOException("Segmented playback source is closed or stale.")
        }
    }

    private fun recordCloseFailure(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            lock.withLock { closeFailures += failure }
        }
    }

    private fun maybeScheduleFinalization() {
        val start = lock.withLock { shouldStartFinalizationLocked() }
        if (start) startFinalization()
    }

    private fun shouldStartFinalizationLocked(): Boolean {
        if (
            status == Status.CLOSING &&
            closeSideEffectsComplete &&
            activeRead == null &&
            !finalizationStarted
        ) {
            finalizationStarted = true
            flushInProgress = true
            return true
        }
        return false
    }

    private fun startFinalization() {
        scope.launch(finalizerDispatcher) {
            recordCloseFailure { decoderLifecycle.flushAndRelease() }
            val receipt = lock.withLock {
                flushInProgress = false
                val value = SegmentedPlaybackCloseReceipt(
                    cause = requireNotNull(closeCause),
                    failures = closeFailures.toList(),
                )
                closedReceipt = value
                status = Status.CLOSED
                changed.signalAll()
                value
            }
            closeResult.complete(receipt)
            sessionJob.complete()
        }
    }

    private fun stateLocked(): SegmentedPlaybackSessionState = when (status) {
        Status.OPEN -> SegmentedPlaybackSessionState.Open(generation)
        Status.CLOSING -> SegmentedPlaybackSessionState.Closing(
            generation = generation,
            waitingFor = buildSet {
                if (activeRead != null) {
                    add(SegmentedPlaybackCloseWait.C_REQUEST)
                    add(SegmentedPlaybackCloseWait.READER_BORROW)
                }
                if (flushInProgress) add(SegmentedPlaybackCloseWait.DECODER_FLUSH)
            },
        )
        Status.CLOSED -> SegmentedPlaybackSessionState.Closed(requireNotNull(closedReceipt))
    }

    private data class MappedRange(
        val resource: SegmentedAuthorizedResourceHandle,
        val resourceOffset: Long,
        val byteCount: Int,
    )

    private class ActiveRead(
        val id: Long,
        val generation: Long,
        var destination: ByteArray?,
        val destinationOffset: Int,
        val resource: SegmentedAuthorizedResourceHandle,
        val resourceOffset: Long,
        val byteCount: Int,
    ) {
        var job: Job? = null
        var terminal: ReadTerminal? = null
        var workerCompleted = false
        var readerAcknowledged = false
    }

    private sealed interface ReadTerminal {
        data class Copied(val byteCount: Int, val generation: Long) : ReadTerminal
        data class Failed(val failure: IOException) : ReadTerminal
    }

    private enum class Status {
        OPEN,
        CLOSING,
        CLOSED,
    }
}
