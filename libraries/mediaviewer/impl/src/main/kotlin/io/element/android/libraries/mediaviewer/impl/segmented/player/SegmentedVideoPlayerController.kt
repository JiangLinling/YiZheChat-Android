/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.ExtractorsFactory
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedCoordinatorTerminationReceipt
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAdvanceStart
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedFragmentCursor
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementFailure
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementFailurePhase
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementPublication
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementStart
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementStartRejection
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementTerminalReceipt
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedSeekReplacementCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A D2 publication translated into the timeline local to its single random-access fragment. */
internal data class SegmentedVideoPlayerSource(
    val cursor: SegmentedFragmentCursor,
    val uri: Uri,
    val dataSourceFactory: DataSource.Factory,
    val startPositionUs: Long,
    val extractorsFactory: ExtractorsFactory? = null,
) {
    val epoch: Long get() = cursor.publicationEpoch
    val generation: Long get() = cursor.readGeneration
    val mediaId: String = listOf(
        "yizhe-segmented",
        cursor.manifestIdentity.canonicalSha256,
        cursor.publicationEpoch,
        cursor.readGeneration,
        cursor.segmentIndex,
    ).joinToString(":")
}

/**
 * The narrow product-side sink. It owns installing and clearing Media3 sources, not the ExoPlayer instance itself.
 * Its methods must run on the dispatcher supplied to [SegmentedVideoPlayerController].
 */
internal interface SegmentedVideoPlayerSink {
    suspend fun install(source: SegmentedVideoPlayerSource)
    suspend fun clearAndAwait()
}

internal enum class SegmentedVideoSeekOutcome {
    INSTALLED,
    REJECTED,
    OWNER_EXIT,
    PLAYER_INSTALL_FAILED,
}

internal data class SegmentedVideoSeekReceipt(
    val outcome: SegmentedVideoSeekOutcome,
    val replacement: SegmentedReplacementTerminalReceipt?,
    val failure: Throwable? = null,
) {
    val succeeded: Boolean get() = outcome == SegmentedVideoSeekOutcome.INSTALLED && failure == null
}

internal sealed interface SegmentedVideoSeekStart {
    data class Accepted(
        val epoch: Long,
        val terminal: Deferred<SegmentedVideoSeekReceipt>,
    ) : SegmentedVideoSeekStart

    data class Rejected(
        val replacement: SegmentedReplacementStart.Rejected? = null,
        val ownerExiting: Boolean = false,
    ) : SegmentedVideoSeekStart
}

internal sealed interface SegmentedVideoAdvanceStart {
    data class Accepted(
        val epoch: Long,
        val terminal: Deferred<SegmentedVideoSeekReceipt>,
    ) : SegmentedVideoAdvanceStart

    data object FinalFragment : SegmentedVideoAdvanceStart
    data object IgnoredStaleOrDuplicate : SegmentedVideoAdvanceStart
    data class Rejected(
        val ownerExiting: Boolean = false,
        val failedClosed: Boolean = false,
        val failure: Throwable? = null,
    ) : SegmentedVideoAdvanceStart
}

internal data class SegmentedVideoTimelineSnapshot(
    val assetDurationUs: Long,
    val segmentStartUs: Long,
    val segmentEndUsExclusive: Long,
) {
    fun globalPositionUs(localPositionUs: Long): Long =
        Math.addExact(segmentStartUs, localPositionUs.coerceIn(0L, segmentEndUsExclusive - segmentStartUs))
}

internal data class SegmentedVideoPlayerCloseReceipt(
    val coordinator: SegmentedCoordinatorTerminationReceipt,
    val sinkFailure: Throwable?,
) {
    val succeeded: Boolean get() = coordinator.succeeded && sinkFailure == null
}

/** Adapter seam kept private to video playback so Image, Voice and File retain their existing owners. */
internal interface SegmentedVideoReplacementOwner {
    fun replace(targetTimeUs: Long): SegmentedReplacementStart
    fun advance(expectedCursor: SegmentedFragmentCursor): SegmentedAdvanceStart
    suspend fun terminateAndAwait(): SegmentedCoordinatorTerminationReceipt
}

internal class CoordinatorSegmentedVideoReplacementOwner(
    private val coordinator: SegmentedSeekReplacementCoordinator,
) : SegmentedVideoReplacementOwner {
    override fun replace(targetTimeUs: Long): SegmentedReplacementStart = coordinator.replace(targetTimeUs)

    override fun advance(expectedCursor: SegmentedFragmentCursor): SegmentedAdvanceStart =
        coordinator.advance(expectedCursor)

    override suspend fun terminateAndAwait(): SegmentedCoordinatorTerminationReceipt =
        coordinator.terminateAndAwait()
}

/**
 * Owns the product hand-off from a D2 replacement receipt to a video-only player sink.
 *
 * UI cancellation only cancels the returned waiter. Publication, failure cleanup and owner exit remain in the
 * controller scopes. D2 remains the sole owner of sessions, leases, authenticated reads and decoder teardown.
 */
internal class SegmentedVideoPlayerController(
    private val replacementOwner: SegmentedVideoReplacementOwner,
    private val sink: SegmentedVideoPlayerSink,
    initialSource: SegmentedVideoPlayerSource,
    operationDispatcher: CoroutineDispatcher,
    cleanupDispatcher: CoroutineDispatcher,
) {
    private val lock = ReentrantLock()
    private val sinkMutex = Mutex()
    private val operationScope = CoroutineScope(SupervisorJob() + operationDispatcher)
    private val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)

    private var ownerExiting = false
    private var closeSignal: CompletableDeferred<SegmentedVideoPlayerCloseReceipt>? = null
    private var currentSource: SegmentedVideoPlayerSource? = initialSource
    private var activeHandoffEpoch: Long? = null
    private var finalEndedMediaId: String? = null

    fun seek(targetTimeUs: Long): SegmentedVideoSeekStart {
        require(targetTimeUs >= 0L) { "Video seek target must be non-negative." }
        if (lock.withLock { ownerExiting }) {
            return SegmentedVideoSeekStart.Rejected(ownerExiting = true)
        }
        return when (val replacement = replacementOwner.replace(targetTimeUs)) {
            is SegmentedReplacementStart.Rejected -> SegmentedVideoSeekStart.Rejected(replacement = replacement)
            is SegmentedReplacementStart.Accepted -> {
                lock.withLock { activeHandoffEpoch = replacement.epoch }
                SegmentedVideoSeekStart.Accepted(
                    replacement.epoch,
                    launchInstall(replacement.epoch, targetTimeUs, replacement.terminal),
                )
            }
        }
    }

    fun onFragmentEnded(mediaId: String?): SegmentedVideoAdvanceStart {
        val source = lock.withLock {
            if (ownerExiting) return SegmentedVideoAdvanceStart.Rejected(ownerExiting = true)
            val current = currentSource ?: return SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
            if (mediaId == null || mediaId != current.mediaId || activeHandoffEpoch != null) {
                return SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
            }
            if (current.cursor.segmentEndUsExclusive == current.cursor.assetDurationUs) {
                if (finalEndedMediaId == mediaId) return SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
                finalEndedMediaId = mediaId
                return SegmentedVideoAdvanceStart.FinalFragment
            }
            current
        }
        return when (val advance = replacementOwner.advance(source.cursor)) {
            is SegmentedAdvanceStart.Accepted -> {
                lock.withLock { activeHandoffEpoch = advance.epoch }
                SegmentedVideoAdvanceStart.Accepted(
                    advance.epoch,
                    launchInstall(advance.epoch, source.cursor.segmentEndUsExclusive, advance.terminal),
                )
            }
            SegmentedAdvanceStart.FinalFragment -> lock.withLock {
                if (currentSource?.mediaId != source.mediaId || finalEndedMediaId == source.mediaId) {
                    SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
                } else {
                    finalEndedMediaId = source.mediaId
                    SegmentedVideoAdvanceStart.FinalFragment
                }
            }
            is SegmentedAdvanceStart.Rejected -> when (advance.reason) {
                SegmentedReplacementStartRejection.BUSY,
                SegmentedReplacementStartRejection.STALE_SOURCE,
                -> SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
                SegmentedReplacementStartRejection.SEEK_REJECTED -> {
                    beginClose()
                    SegmentedVideoAdvanceStart.Rejected(
                        ownerExiting = true,
                        failedClosed = true,
                        failure = advance.failure,
                    )
                }
                SegmentedReplacementStartRejection.TERMINATED,
                SegmentedReplacementStartRejection.NO_PUBLISHED_SESSION,
                -> SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate
            }
        }
    }

    fun timelineSnapshot(): SegmentedVideoTimelineSnapshot? = lock.withLock {
        currentSource?.cursor?.let { cursor ->
            SegmentedVideoTimelineSnapshot(
                assetDurationUs = cursor.assetDurationUs,
                segmentStartUs = cursor.segmentStartUs,
                segmentEndUsExclusive = cursor.segmentEndUsExclusive,
            )
        }
    }

    private fun launchInstall(
        epoch: Long,
        targetTimeUs: Long,
        replacementTerminal: Deferred<SegmentedReplacementTerminalReceipt>,
    ): Deferred<SegmentedVideoSeekReceipt> {
        val terminal = CompletableDeferred<SegmentedVideoSeekReceipt>()
        operationScope.launch {
            val result = try {
                installPublishedReplacement(epoch, targetTimeUs, replacementTerminal)
            } catch (failure: Throwable) {
                failClosedAfterUnexpectedHandoffFailure(failure)
            }
            terminal.complete(result)
        }
        // This is intentionally a child of the supervisor rather than the owner operation.
        return operationScope.async { terminal.await() }
    }

    suspend fun closeAndAwait(): SegmentedVideoPlayerCloseReceipt = beginClose().await()

    private fun beginClose(): CompletableDeferred<SegmentedVideoPlayerCloseReceipt> = lock.withLock {
        closeSignal?.let { return@withLock it }
        ownerExiting = true
        CompletableDeferred<SegmentedVideoPlayerCloseReceipt>().also { created ->
            closeSignal = created
            cleanupScope.launch {
                val coordinatorReceipt = try {
                    replacementOwner.terminateAndAwait()
                } catch (failure: Throwable) {
                    SegmentedCoordinatorTerminationReceipt(
                        activeReplacementReceipt = null,
                        publishedSessionCloseReceipt = null,
                        failures = listOf(
                            SegmentedReplacementFailure(
                                SegmentedReplacementFailurePhase.OWNER_CLOSE,
                                failure,
                            )
                        ),
                    )
                }
                val sinkFailure = sinkMutex.withLock {
                    runCatching { sink.clearAndAwait() }.exceptionOrNull()
                }
                lock.withLock {
                    currentSource = null
                    activeHandoffEpoch = null
                }
                created.complete(SegmentedVideoPlayerCloseReceipt(coordinatorReceipt, sinkFailure))
            }
        }
    }

    private suspend fun installPublishedReplacement(
        epoch: Long,
        targetTimeUs: Long,
        replacementTerminal: Deferred<SegmentedReplacementTerminalReceipt>,
    ): SegmentedVideoSeekReceipt {
        val receipt = replacementTerminal.await()
        val publication = receipt.publication
        if (!receipt.succeeded || publication == null) {
            lock.withLock { if (activeHandoffEpoch == epoch) activeHandoffEpoch = null }
            closeAndAwait()
            return SegmentedVideoSeekReceipt(SegmentedVideoSeekOutcome.REJECTED, receipt)
        }
        val source = publication.toPlayerSource(targetTimeUs)
        val installFailure = sinkMutex.withLock sinkLock@{
            if (lock.withLock { ownerExiting || activeHandoffEpoch != epoch }) {
                return SegmentedVideoSeekReceipt(SegmentedVideoSeekOutcome.OWNER_EXIT, receipt)
            } else {
                runCatching { sink.install(source) }.exceptionOrNull()?.also { return@sinkLock it }
                val accepted = lock.withLock {
                    if (ownerExiting || activeHandoffEpoch != epoch) {
                        false
                    } else {
                        currentSource = source
                        activeHandoffEpoch = null
                        finalEndedMediaId = null
                        true
                    }
                }
                if (accepted) null else IllegalStateException("Segmented source lost controller ownership during install.")
            }
        }
        if (installFailure == null) {
            return SegmentedVideoSeekReceipt(SegmentedVideoSeekOutcome.INSTALLED, receipt)
        }
        // A published D2 session without a player owner must not remain readable.
        closeAndAwait()
        return SegmentedVideoSeekReceipt(
            outcome = SegmentedVideoSeekOutcome.PLAYER_INSTALL_FAILED,
            replacement = receipt,
            failure = installFailure,
        )
    }

    private suspend fun failClosedAfterUnexpectedHandoffFailure(failure: Throwable): SegmentedVideoSeekReceipt {
        closeAndAwait()
        return SegmentedVideoSeekReceipt(
            outcome = SegmentedVideoSeekOutcome.PLAYER_INSTALL_FAILED,
            replacement = null,
            failure = failure,
        )
    }

    private fun SegmentedReplacementPublication.toPlayerSource(
        targetTimeUs: Long,
    ): SegmentedVideoPlayerSource {
        val localStartUs = Math.subtractExact(targetTimeUs, randomAccessStartUs)
        check(localStartUs >= 0L) { "D2 publication begins after the requested video position." }
        check(localStartUs < cursor.segmentEndUsExclusive - cursor.segmentStartUs) {
            "D2 publication target lies outside its exact fragment."
        }
        return SegmentedVideoPlayerSource(
            cursor = cursor,
            uri = uri,
            dataSourceFactory = dataSourceFactory,
            startPositionUs = localStartUs,
        )
    }
}
