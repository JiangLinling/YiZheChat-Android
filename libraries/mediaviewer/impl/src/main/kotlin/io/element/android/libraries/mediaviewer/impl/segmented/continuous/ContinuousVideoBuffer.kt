/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Only an existing authenticated decoder lease may implement this boundary. No plaintext cache is introduced. */
internal interface ContinuousVideoFragment {
    suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int
    suspend fun probe()
    fun revoke()
    suspend fun closeAndAwait()
}

internal data class ContinuousVideoPart(val length: Long, val startUs: Long, val durationUs: Long)

/** Immutable byte/time index for init followed by authenticated fMP4 fragments in their original order. */
internal class ContinuousVideoLayout(val initLength: Long, val parts: List<ContinuousVideoPart>) {
    val starts: List<Long>
    val length: Long
    val durationUs: Long

    init {
        require(initLength in 1..1_048_576L && parts.isNotEmpty())
        var bytes = initLength
        var time = 0L
        starts = parts.map { part ->
            require(part.length in 1..2_097_152L && part.durationUs in 1..MAX_FORWARD_US && part.startUs == time)
            time = Math.addExact(time, part.durationUs)
            bytes.also { bytes = Math.addExact(bytes, part.length) }
        }
        length = bytes
        durationUs = time
    }

    fun partAt(position: Long): Int {
        require(position in 0 until length)
        if (position < initLength) return 0
        val match = starts.binarySearch(position)
        return if (match >= 0) match else -match - 2
    }

    fun partAtTime(timeUs: Long): Int {
        val target = timeUs.coerceIn(0, durationUs - 1)
        val match = parts.binarySearchBy(target) { it.startUs }
        return if (match >= 0) match else -match - 2
    }

    // Each local session contains its own init. Only the first init is exposed in the continuous byte stream.
    fun localPosition(index: Int, position: Long): Long =
        if (index == 0) position else Math.addExact(initLength, position - starts[index])

    fun end(index: Int): Long = if (index == parts.lastIndex) length else starts[index + 1]

    fun window(index: Int): List<Int> {
        val result = mutableListOf(index)
        var bytes = Math.addExact(initLength, parts[index].length)
        var forwardUs = 0L
        for (next in index + 1..minOf(index + 2, parts.lastIndex)) {
            val nextBytes = Math.addExact(bytes, Math.addExact(initLength, parts[next].length))
            val nextTime = Math.addExact(forwardUs, parts[next].durationUs)
            if (nextBytes > MAX_WINDOW_BYTES || nextTime > MAX_FORWARD_US) break
            result += next
            bytes = nextBytes
            forwardUs = nextTime
        }
        return result
    }

    companion object {
        const val MAX_WINDOW_BYTES = 8L * 1024 * 1024
        const val MAX_FORWARD_US = 6_000_000L
    }
}

/**
 * One Media3 reader generation. A bounded window overlaps fetch/authentication with consumption.
 * Closing first fences admission, then waits for physical acquisition/read termination and lease cleanup.
 */
internal class ContinuousVideoBuffer(
    val layout: ContinuousVideoLayout,
    dispatcher: CoroutineDispatcher,
    private val load: suspend (Int) -> ContinuousVideoFragment,
    private val isOwnerValid: () -> Boolean,
    private val playbackPositionUs: (() -> Long)? = null,
) {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val reader = Mutex()
    private val lock = Any()
    private val slots = mutableMapOf<Int, Slot>()
    private val retiring = mutableSetOf<Slot>()
    private val closeMutex = Mutex()
    private var cleanupComplete = false
    val isClosed: Boolean get() = closed.get()

    suspend fun prepare(position: Long = 0) = reader.withLock {
        checkOpen()
        updateWindow(layout.partAt(position))
        slot(layout.partAt(position)).ready.await().probe()
    }

    suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int = reader.withLock {
        require(offset >= 0 && count >= 0 && offset <= destination.size - count)
        checkOpen()
        if (count == 0) return@withLock 0
        if (position == layout.length) return@withLock -1
        val index = layout.partAt(position)
        val requested = minOf(count.toLong(), layout.end(index) - position).toInt()
        try {
            // Back-pressure Media3's default read-ahead. Keep authority for the playing fragment alive,
            // even when the extractor has already read its samples into the decoder's bounded window.
            var anchor = playbackPositionUs?.let { minOf(index, layout.partAtTime(it())) } ?: index
            while (index !in layout.window(anchor)) {
                checkOpen()
                probe()
                delay(50)
                anchor = playbackPositionUs?.let { minOf(index, layout.partAtTime(it())) } ?: index
            }
            updateWindow(anchor)
            val fragment = slot(index).ready.await()
            checkOpen()
            val copied = fragment.read(layout.localPosition(index, position), destination, offset, requested)
            if (copied != requested) throw IOException("Continuous authenticated video read was short.")
            checkOpen()
            copied
        } catch (failure: Throwable) {
            destination.fill(0, offset, offset + requested)
            beginClose()
            throw failure
        }
    }

    /** Also called while paused: already buffered Media3 samples must not outlive their authority. */
    suspend fun probe() {
        checkOpen()
        val ready = synchronized(lock) { slots.values.filter { it.fragment != null }.toList() }
        ready.forEach { entry ->
            try {
                entry.fragment?.probe()
            } catch (failure: Throwable) {
                if (synchronized(lock) { slots[entry.index] === entry } && !closed.get()) throw failure
            }
        }
        checkOpen()
    }

    fun beginClose() {
        closed.set(true)
        synchronized(lock) {
            (slots.values + retiring).forEach { entry ->
                runCatching { entry.fragment?.revoke() }.exceptionOrNull()?.let { entry.cleanupFailure = it }
                entry.job?.cancel()
            }
        }
    }

    suspend fun closeAndAwait() = withContext(NonCancellable) {
        closeMutex.withLock {
            if (cleanupComplete) return@withLock
            beginClose()
            reader.withLock {
                val entries = synchronized(lock) { (slots.values + retiring).distinct() }
                entries.mapNotNull { it.job }.joinAll()
                val failures = mutableListOf<Throwable>()
                entries.forEach { entry ->
                    entry.fragment?.let { fragment ->
                        runCatching { fragment.closeAndAwait() }.exceptionOrNull()?.let(failures::add)
                    }
                    entry.cleanupFailure?.let(failures::add)
                }
                if (failures.isNotEmpty()) {
                    throw IOException("Continuous video cleanup remains incomplete.", failures.first()).also { error ->
                        failures.drop(1).forEach(error::addSuppressed)
                    }
                }
                synchronized(lock) { slots.clear(); retiring.clear() }
                scope.cancel()
                cleanupComplete = true
            }
        }
    }

    private suspend fun updateWindow(index: Int) {
        val wanted = layout.window(index)
        val obsolete = synchronized(lock) {
            slots.filterKeys { it !in wanted }.values.toList().also { values ->
                values.forEach { entry -> slots.remove(entry.index); retiring += entry; entry.job?.cancel() }
            }
        }
        // Reclaim old capacity before scheduling another window, including in-flight acquisitions.
        withContext(NonCancellable) {
            obsolete.forEach { entry ->
                entry.job?.join()
                entry.fragment?.closeAndAwait()
                entry.cleanupFailure?.let { throw IOException("Retired video fragment cleanup failed.", it) }
                synchronized(lock) { retiring.remove(entry) }
            }
        }
        checkOpen()
        wanted.forEach { slot(it) }
    }

    private fun slot(index: Int): Slot = synchronized(lock) {
        checkOpen()
        slots[index] ?: Slot(index).also { entry ->
                slots[index] = entry
                entry.job = scope.launch {
                    var unowned: ContinuousVideoFragment? = null
                    try {
                        val fragment = load(index)
                        unowned = fragment
                        synchronized(lock) {
                            checkOpen()
                            check(slots[index] === entry) { "Continuous video acquisition became stale." }
                            entry.fragment = fragment
                            unowned = null
                            entry.ready.complete(fragment)
                        }
                    } catch (failure: Throwable) {
                        entry.ready.completeExceptionally(failure)
                    } finally {
                        withContext(NonCancellable) {
                            unowned?.let { fragment ->
                                runCatching { fragment.closeAndAwait() }.exceptionOrNull()?.let { entry.cleanupFailure = it }
                            }
                        }
                    }
                }.also { job ->
                    job.invokeOnCompletion { cause ->
                        if (!entry.ready.isCompleted) {
                            entry.ready.completeExceptionally(cause ?: CancellationException("Video acquisition cancelled."))
                        }
                    }
                }
        }
    }

    private fun checkOpen() {
        if (closed.get() || !isOwnerValid()) throw IOException("Continuous video owner is closed, expired or replaced.")
    }

    private class Slot(val index: Int) {
        val ready = CompletableDeferred<ContinuousVideoFragment>()
        var job: Job? = null
        var fragment: ContinuousVideoFragment? = null
        var cleanupFailure: Throwable? = null
    }
}
