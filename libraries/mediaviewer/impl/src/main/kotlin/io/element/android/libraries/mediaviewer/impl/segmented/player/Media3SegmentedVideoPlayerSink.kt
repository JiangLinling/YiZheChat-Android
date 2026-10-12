/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Media3 sink for one-shot authenticated D2 sources. The surrounding video view still owns player release. */
@OptIn(UnstableApi::class)
internal class Media3SegmentedVideoPlayerSink(
    private val player: ExoPlayer,
) : SegmentedVideoPlayerSink {
    private val stateLock = ReentrantLock()
    private var closed = false
    private var sourceInstalled = false

    override suspend fun install(source: SegmentedVideoPlayerSource) {
        check(isOnOwnerThread()) { "Segmented player installation must run on the player application thread." }
        stateLock.withLock {
            check(!closed) { "Segmented player sink is closed." }
            val resumePlayback = player.playWhenReady
            val mediaItem = MediaItem.Builder()
                .setMediaId(source.mediaId)
                .setUri(source.uri)
                .build()
            val factory = source.extractorsFactory?.let { ProgressiveMediaSource.Factory(source.dataSourceFactory, it) }
                ?: ProgressiveMediaSource.Factory(source.dataSourceFactory)
            val mediaSource = factory
                .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, source.startPositionUs / 1_000L)
            player.prepare()
            player.playWhenReady = resumePlayback
            sourceInstalled = true
        }
    }

    override suspend fun clearAndAwait() {
        stateLock.withLock {
            closed = true
            quiesceLocked()
        }
    }

    /**
     * Synchronously stops the current decoder input and waits for a playback-thread command barrier.
     * Source ownership is cleared only after the barrier is delivered, so failures remain retryable.
     */
    fun quiesceCurrentSourceAndAwait() {
        stateLock.withLock { quiesceLocked() }
    }

    /** Avoids a main-thread/runBlocking inversion when the close caller already owns the player thread. */
    fun quiesceIfOnOwnerThread(): Boolean {
        if (!isOnOwnerThread()) return false
        quiesceCurrentSourceAndAwait()
        return true
    }

    private fun quiesceLocked() {
        if (!sourceInstalled) return
        runOnOwnerThreadAndAwait {
            check(Looper.myLooper() != player.playbackLooper) {
                "Decoder quiescence cannot block the Media3 playback looper."
            }
            player.stop()
            player.clearMediaItems()
            player.createMessage(NOOP_TARGET).send().blockUntilDelivered()
        }
        sourceInstalled = false
    }

    private fun isOnOwnerThread(): Boolean = Looper.myLooper() == player.applicationLooper

    private fun runOnOwnerThreadAndAwait(block: () -> Unit) {
        if (isOnOwnerThread()) {
            block()
            return
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        check(Handler(player.applicationLooper).post {
            try {
                block()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                completed.countDown()
            }
        }) { "Player application thread rejected the decoder-quiescence barrier." }
        try {
            completed.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while awaiting decoder quiescence.", interrupted)
        }
        failure?.let { throw it }
    }

    private companion object {
        val NOOP_TARGET = PlayerMessage.Target { _, _ -> Unit }
    }
}
