/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContinuousVideoBufferTest {
    @Test
    fun `startup waits only for first fragment and starts bounded forward fetch`() = runTest {
        val first = CompletableDeferred<Unit>()
        val future = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val layout = layout(6)
        val loaded = mutableListOf<FakeFragment>()
        val buffer = ContinuousVideoBuffer(layout, StandardTestDispatcher(testScheduler), load = { index ->
            started += index
            if (index == 0) first.await() else future.await()
            FakeFragment(bytes(index)).also(loaded::add)
        }, isOwnerValid = { true })
        val preparing = async { buffer.prepare() }
        runCurrent()
        assertThat(started).containsExactly(0, 1, 2)
        assertThat(preparing.isCompleted).isFalse()
        first.complete(Unit)
        runCurrent()
        assertThat(preparing.isCompleted).isTrue()
        assertThat(future.isCompleted).isFalse()
        buffer.closeAndAwait()
        assertThat(loaded.single().closed).isTrue()
    }

    @Test
    fun `sequential reads expose one init and ordered fragments without waiting at each end`() = runTest {
        val loaded = mutableMapOf<Int, FakeFragment>()
        val buffer = ContinuousVideoBuffer(layout(5), StandardTestDispatcher(testScheduler), load = { index ->
            FakeFragment(bytes(index)).also { loaded[index] = it }
        }, isOwnerValid = { true })
        buffer.prepare()
        val actual = ByteArray(buffer.layout.length.toInt())
        var position = 0
        while (position < actual.size) position += buffer.read(position.toLong(), actual, position, minOf(5, actual.size - position))
        assertThat(actual.toList()).containsExactlyElementsIn(listOf<Byte>(9, 9) + (0..4).flatMap { index -> List(4) { index.toByte() } }).inOrder()
        assertThat(buffer.read(position.toLong(), ByteArray(1), 0, 1)).isEqualTo(-1)
        buffer.closeAndAwait()
        assertThat(loaded.values.all { it.closed }).isTrue()
    }

    @Test
    fun `window respects both time and encrypted resource byte budgets`() {
        val short = ContinuousVideoLayout(1_048_576, List(10) { ContinuousVideoPart(2_097_152, it * 2_000_000L, 2_000_000) })
        assertThat(short.window(0)).containsExactly(0, 1).inOrder()
        val long = ContinuousVideoLayout(2, List(4) { ContinuousVideoPart(4, it * 4_000_000L, 4_000_000) })
        assertThat(long.window(0)).containsExactly(0, 1).inOrder()
        assertThat(runCatching {
            ContinuousVideoLayout(2, listOf(ContinuousVideoPart(4, 0, 7_000_000)))
        }.isFailure).isTrue()
    }

    @Test
    fun `revocation during read clears borrowed output and cleanup waits for physical reader exit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fragment = object : FakeFragment(bytes(0)) {
            override suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                data.copyInto(destination, offset, position.toInt(), position.toInt() + count)
                return count
            }
        }
        val buffer = ContinuousVideoBuffer(layout(1), StandardTestDispatcher(testScheduler), { fragment }, { true })
        buffer.prepare()
        val destination = ByteArray(4) { 7 }
        val reading = async { runCatching { buffer.read(0, destination, 0, 4) } }
        entered.await()
        val closing = launch { buffer.closeAndAwait() }
        runCurrent()
        assertThat(closing.isCompleted).isFalse()
        release.complete(Unit)
        closing.join()
        assertThat(reading.await().isFailure).isTrue()
        assertThat(destination).isEqualTo(ByteArray(4))
        assertThat(fragment.closed).isTrue()
    }

    @Test
    fun `close owns and disposes a late acquisition that ignores cancellation`() = runTest {
        var resumeLoader: (() -> Unit)? = null
        val fragment = FakeFragment(bytes(0))
        val buffer = ContinuousVideoBuffer(layout(1), StandardTestDispatcher(testScheduler), load = {
            suspendCoroutine { continuation -> resumeLoader = { continuation.resume(fragment) } }
        }, isOwnerValid = { true })
        val preparing = async { runCatching { buffer.prepare() } }
        runCurrent()
        val closing = launch { buffer.closeAndAwait() }
        runCurrent()
        assertThat(closing.isCompleted).isFalse()
        requireNotNull(resumeLoader).invoke()
        closing.join()
        assertThat(preparing.await().isFailure).isTrue()
        assertThat(fragment.closed).isTrue()
    }

    @Test
    fun `failed future fragment is never exposed and read failure fences further output`() = runTest {
        val buffer = ContinuousVideoBuffer(layout(2), StandardTestDispatcher(testScheduler), load = { index ->
            if (index == 1) throw IOException("authentication failure")
            FakeFragment(bytes(index))
        }, isOwnerValid = { true })
        buffer.prepare()
        val first = ByteArray(6)
        assertThat(buffer.read(0, first, 0, 6)).isEqualTo(6)
        val future = ByteArray(4) { 7 }
        assertThat(runCatching { buffer.read(6, future, 0, 4) }.isFailure).isTrue()
        buffer.closeAndAwait()
        assertThat(future).isEqualTo(ByteArray(4))
        assertThat(buffer.isClosed).isTrue()
    }

    @Test
    fun `expired owner cannot read already prepared data`() = runTest {
        var valid = true
        val buffer = ContinuousVideoBuffer(layout(1), StandardTestDispatcher(testScheduler), { FakeFragment(bytes(0)) }, { valid })
        buffer.prepare()
        valid = false
        assertThat(runCatching { buffer.read(0, ByteArray(1), 0, 1) }.isFailure).isTrue()
        buffer.closeAndAwait()
    }

    @Test
    fun `paused playback bounds decoder read ahead until playback advances`() = runTest {
        var playbackTime = 0L
        val buffer = ContinuousVideoBuffer(layout(5), StandardTestDispatcher(testScheduler), { FakeFragment(bytes(it)) }, { true }, { playbackTime })
        buffer.prepare()
        val reading = async { buffer.read(buffer.layout.starts[3], ByteArray(4), 0, 4) }
        runCurrent()
        assertThat(reading.isCompleted).isFalse()
        playbackTime = 2_000_000
        testScheduler.advanceTimeBy(50)
        runCurrent()
        assertThat(reading.await()).isEqualTo(4)
        buffer.closeAndAwait()
    }

    private open class FakeFragment(val data: ByteArray) : ContinuousVideoFragment {
        var closed = false
        var revoked = false
        override suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int {
            if (revoked) throw IOException("revoked")
            data.copyInto(destination, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override suspend fun probe() { if (revoked) throw IOException("revoked") }
        override fun revoke() { revoked = true }
        override suspend fun closeAndAwait() { revoke(); closed = true }
    }

    private fun bytes(index: Int): ByteArray = byteArrayOf(9, 9) + ByteArray(4) { index.toByte() }
    private fun layout(count: Int) = ContinuousVideoLayout(2, List(count) { ContinuousVideoPart(4, it * 2_000_000L, 2_000_000) })
}
