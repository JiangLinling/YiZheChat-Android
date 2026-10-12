/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import android.net.Uri
import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.ExtractorsFactory
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoCandidate
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.CandidateResourceRole
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.LocalAuthenticatedSegmentBridge
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAuthorizedResourceHandle
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAuthorizedResourceRequest
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedDecoderLifecycle
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedManifestIdentityFactory
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedPlaybackSession
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedPlaybackCloseCause
import io.element.android.libraries.mediaviewer.impl.segmented.player.Media3SegmentedVideoPlayerSink
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoAdvanceStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoPlayerSource
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoSeekOutcome
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoSeekReceipt
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoSeekStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoTimelineSnapshot
import io.element.android.libraries.mediaviewer.impl.segmented.product.ProtectedSegmentedVideoOwner
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoAttachment
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoPlaybackRegistry
import io.element.android.libraries.mediaviewer.impl.segmented.product.authorizationGate
import io.element.android.libraries.mediaviewer.impl.segmented.product.resourceProvider
import io.element.android.libraries.mediaviewer.impl.segmented.product.toPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaResourceAcquirer
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Reuses Matrix/YZMF acquisition, C's authenticated leases and D1's fMP4 verification. Rollout is opt-in. */
internal class ContinuousVideoPlaybackOwner private constructor(
    private val candidate: SegmentedVideoCandidate,
    private val displayName: String?,
    private val acquirer: SegmentedProtectedMediaResourceAcquirer,
    private val dispatchers: CoroutineDispatchers,
    private val clock: SystemClock,
) : ProtectedSegmentedVideoOwner {
    override val continuous: Boolean = true
    private val validated = SegmentedMediaManifestValidatorPrototype.validate(candidate.toPrototype())
    private val layout = ContinuousVideoLayout(validated.manifest.init.plainLength, validated.manifest.segments.map {
        ContinuousVideoPart(it.plainLength, it.startUs, it.durationUs)
    })
    private val closing = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val buffers = ConcurrentHashMap.newKeySet<ContinuousVideoBuffer>()
    private val initial = AtomicReference<ContinuousVideoBuffer?>()
    private val playPositionUs = AtomicLong()
    private val generation = AtomicLong()
    private val fragmentTimings = ConcurrentHashMap<Int, ContinuousFragmentTiming>()
    private val closeMutex = Mutex()
    private val failure = MutableStateFlow<Throwable?>(null)
    private var player: ExoPlayer? = null
    private var sink: Media3SegmentedVideoPlayerSink? = null
    private var cleanupComplete = false
    override val uri: Uri = SegmentedVideoPlaybackRegistry.register(this)

    private fun valid(): Boolean = !closing.get() && clock.epochMillis() < candidate.expiresAtEpochMillis

    private fun newBuffer(): ContinuousVideoBuffer {
        check(valid()) { "Continuous video owner is closed or expired." }
        val ownerToken = UUID.randomUUID().toString()
        val readGeneration = generation.incrementAndGet().also { check(it > 0) }
        val provider = candidate.resourceProvider(acquirer, displayName, clock)
        val bridge = LocalAuthenticatedSegmentBridge(dispatchers.io, dispatchers.computation)
        val gate = candidate.authorizationGate(ownerToken, clock)
        val identity = SegmentedManifestIdentityFactory.create(validated)
        return ContinuousVideoBuffer(layout, dispatchers.io, load = { index ->
            val context = SegmentedMediaReadContextPrototype(ownerToken, UUID.randomUUID().toString(), readGeneration)
            val initHandle = provider.acquire(SegmentedAuthorizedResourceRequest(CandidateResourceRole.INIT, identity, validated.manifest.init, context))
            val segment = try {
                provider.acquire(SegmentedAuthorizedResourceRequest(CandidateResourceRole.SEGMENT, identity, validated.manifest.segments[index], context))
            } catch (failure: Throwable) {
                initHandle.lease.close()
                throw failure
            }
            // The shared continuous owner owns the decoder. Local sessions own only their reader borrows/leases;
            // their close receipts are never published as a whole-video decoder cleanup receipt.
            var relocator: ContinuousFragmentRelocator? = null
            val session = bridge.prepare(validated, index, initHandle, segment, context, gate, READER_LIFECYCLE) { init, fragment ->
                relocator = ContinuousFragmentRelocator.plan(layout.initLength, layout.starts[index], fragment)
                val timing = ContinuousFragmentTimeline.inspect(init, fragment, layout.parts[index])
                val prior = fragmentTimings.putIfAbsent(index, timing)
                check(prior == null || prior == timing) { "Authenticated fragment timeline changed." }
            }
            SessionFragment(session, context.generation, initHandle, segment, dispatchers, requireNotNull(relocator))
        }, isOwnerValid = ::valid, playbackPositionUs = playPositionUs::get).also {
            buffers += it
            if (!valid()) it.beginClose()
        }
    }

    override suspend fun attach(player: ExoPlayer): SegmentedVideoAttachment {
        check(Looper.myLooper() == player.applicationLooper && this.player == null && valid())
        this.player = player
        val installedSink = Media3SegmentedVideoPlayerSink(player)
        sink = installedSink
        val factory = DataSource.Factory {
            ContinuousVideoDataSource(uri, { initial.getAndSet(null) ?: newBuffer() },
                { failClosed() }, { buffers.remove(it) }, retainAuthorityAtEnd = true)
        }
        val firstCursor = validated.manifest.segments.first().let {
            io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedFragmentCursor(
                manifestIdentity = SegmentedManifestIdentityFactory.create(validated),
                segmentIndex = 0, publicationEpoch = 0, readGeneration = 1,
                segmentStartUs = 0, segmentEndUsExclusive = it.durationUs, assetDurationUs = layout.durationUs,
            )
        }
        installedSink.install(SegmentedVideoPlayerSource(firstCursor, uri, factory, 0, ExtractorsFactory {
            arrayOf(ContinuousVideoExtractor(layout) { index -> requireNotNull(fragmentTimings[index]) { "Fragment timeline is not authenticated." } })
        }))
        // Playback position comes from the player thread; blocked reads and IO authority probes cannot stall its updates.
        scope.launch(dispatchers.main) {
            while (valid()) {
                playPositionUs.set(Math.multiplyExact(player.currentPosition, 1_000L))
                delay(100)
            }
        }
        scope.launch {
            try {
                while (valid()) {
                    buffers.filterNot { it.isClosed }.forEach { it.probe() }
                    delay(250)
                }
                if (!closing.get()) failClosed()
            } catch (error: Throwable) {
                if (!closing.get()) failClosed(error)
            }
        }
        return object : SegmentedVideoAttachment {
            override fun seek(targetTimeUs: Long): SegmentedVideoSeekStart {
                if (!valid() || targetTimeUs !in 0..layout.durationUs) return SegmentedVideoSeekStart.Rejected(ownerExiting = closing.get())
                check(Looper.myLooper() == player.applicationLooper)
                playPositionUs.set(targetTimeUs)
                player.seekTo(targetTimeUs / 1_000L)
                return SegmentedVideoSeekStart.Accepted(generation.get(), CompletableDeferred(SegmentedVideoSeekReceipt(SegmentedVideoSeekOutcome.INSTALLED, null)))
            }
            override fun onFragmentEnded(mediaId: String?): SegmentedVideoAdvanceStart = SegmentedVideoAdvanceStart.FinalFragment
            override fun timelineSnapshot() = SegmentedVideoTimelineSnapshot(layout.durationUs, 0, layout.durationUs)
            override fun failureFlow(): StateFlow<Throwable?> = failure
            override suspend fun closeAndAwait() = this@ContinuousVideoPlaybackOwner.closeAndAwait()
        }
    }

    private fun failClosed(error: Throwable = IOException("Continuous video authority expired or read failed.")) {
        if (closing.compareAndSet(false, true)) {
            failure.value = error
            buffers.forEach { it.beginClose() }
            scope.launch(dispatchers.main) {
                runCatching { closeAndAwait() }.exceptionOrNull()?.let { cleanupFailure ->
                    // Keep the owner registered as incomplete; background cleanup failure must not crash the UI.
                    failure.value = IOException("Continuous video stopped with incomplete cleanup.", cleanupFailure)
                }
            }
        }
    }

    override suspend fun closeAndAwait() {
        closing.set(true)
        buffers.forEach { it.beginClose() }
        // Main-thread callers may be inside runBlocking; cross its decoder barrier before suspending.
        val eagerFailure = runCatching { sink?.quiesceIfOnOwnerThread() }.exceptionOrNull()
        withContext(NonCancellable) {
            closeMutex.withLock {
                if (cleanupComplete) return@withLock
                val failures = mutableListOf<Throwable>()
                eagerFailure?.let(failures::add)
                runCatching { sink?.clearAndAwait() }.exceptionOrNull()?.let(failures::add)
                buffers.toList().forEach { buffer ->
                    runCatching { buffer.closeAndAwait(); buffers.remove(buffer) }.exceptionOrNull()?.let(failures::add)
                }
                if (failures.isNotEmpty()) throw IOException("Continuous video cleanup remains incomplete.", failures.first()).also { error ->
                    failures.drop(1).forEach(error::addSuppressed)
                }
                initial.set(null)
                SegmentedVideoPlaybackRegistry.remove(uri, this@ContinuousVideoPlaybackOwner)
                cleanupComplete = true
                scope.cancel()
            }
        }
    }

    companion object {
        suspend fun open(candidate: SegmentedVideoCandidate, displayName: String?, acquirer: SegmentedProtectedMediaResourceAcquirer,
                         dispatchers: CoroutineDispatchers, clock: SystemClock): ContinuousVideoPlaybackOwner {
            val owner = ContinuousVideoPlaybackOwner(candidate, displayName, acquirer, dispatchers, clock)
            try {
                val buffer = owner.newBuffer()
                owner.initial.set(buffer)
                buffer.prepare()
                return owner
            } catch (failure: Throwable) {
                runCatching { owner.closeAndAwait() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        private val READER_LIFECYCLE = object : SegmentedDecoderLifecycle {
            override fun beginStop() = Unit
            override fun flushAndRelease() = Unit
        }
    }
}

private class SessionFragment(
    private val session: SegmentedPlaybackSession,
    private val generation: Long,
    private val init: SegmentedAuthorizedResourceHandle,
    private val segment: SegmentedAuthorizedResourceHandle,
    private val dispatchers: CoroutineDispatchers,
    private val relocator: ContinuousFragmentRelocator,
) : ContinuousVideoFragment {
    override suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int = withContext(dispatchers.io) {
        session.read(generation, position, destination, offset, count).also { copied ->
            relocator.apply(position, destination, offset, copied)
        }
    }
    override suspend fun probe() {
        for (handle in listOf(init, segment)) {
            val result = handle.lease.copyAuthenticatedRangeInto(SecureMediaAuthenticatedReadRequest(0, 0, SecureMediaReadPurpose.SEGMENTED_DECODER), ByteArray(0))
            if (result !is SecureMediaAuthenticatedReadResult.Copied || result.byteCount != 0) throw IOException("Continuous video lease is no longer valid.")
        }
    }
    override fun revoke() = session.requestClose(SegmentedPlaybackCloseCause.OWNER_EXIT)
    override suspend fun closeAndAwait() {
        val receipt = session.closeAndAwaitQuiescence()
        if (!receipt.succeeded) throw IOException("Authenticated video session cleanup failed.").also { error -> receipt.failures.forEach(error::addSuppressed) }
    }
}
