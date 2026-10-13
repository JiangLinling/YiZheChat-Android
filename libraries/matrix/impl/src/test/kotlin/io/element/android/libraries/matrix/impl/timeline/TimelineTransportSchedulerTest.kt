/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class TimelineTransportSchedulerTest {
    @Test
    fun `visible join promotes original background receive and leaves one slot for foreground`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupied = List(2) { launch { work.withPermit { release.await() } } }
        runCurrent()
        var activeHistory = 0
        var maximumHistory = 0
        val backlog = List(100) { index -> launch { work.withPermit(receiveIdentity = "old-$index") {
            activeHistory++
            maximumHistory = maxOf(maximumHistory, activeHistory)
            order += "old-$index"
            delay(10)
            activeHistory--
        } } }
        val original = async { work.withPermit(receiveIdentity = "original-receive") { order += "original-receive" } }
        runCurrent()
        work.promoteReceive("original-receive")
        release.complete(Unit)
        runCurrent()
        assertThat(original.isCompleted).isTrue()
        assertThat(order.first()).isEqualTo("original-receive")
        assertThat(maximumHistory).isEqualTo(1)
        val foreground = async { work.withPermit(isForegroundRead = true) { "current-read" } }
        runCurrent()
        assertThat(foreground.isCompleted).isTrue()
        assertThat(foreground.await()).isEqualTo("current-read")
        occupied.forEach { it.join() }
        backlog.forEach { it.join() }
        assertThat(maximumHistory).isEqualTo(1)
        assertThat(order).hasSize(101)
    }

    @Test
    fun `fresh receive precedes restored foreground backlog while send and old work progress`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupied = List(2) { launch { work.withPermit { release.await() } } }
        runCurrent()
        val old = List(100) { index -> launch { work.withPermit(isForegroundRead = true) {
            order += "old-visible-$index"
            delay(10)
        } } }
        val history = launch { work.withPermit { order += "history" } }
        runCurrent()
        val fresh = async { work.withPermit(isForegroundRead = true) { order += "fresh-original-receive" } }
        val send = async { work.withPermit(isLiveSend = true) { order += "original-send" } }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertThat(fresh.isCompleted).isTrue()
        assertThat(send.isCompleted).isTrue()
        assertThat(order.take(2)).containsExactly("original-send", "fresh-original-receive").inOrder()
        advanceTimeBy(100)
        runCurrent()
        assertThat(order.indexOf("old-visible-0")).isAtMost(10)
        assertThat(history.isCompleted).isTrue()
        assertThat(old.any { !it.isCompleted }).isTrue()
        occupied.forEach { it.join() }
        old.forEach { it.join() }
        assertThat(order).hasSize(103)
    }

    @Test
    fun `cancelled foreground admission cannot occupy or leak either transport slot`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val occupied = List(2) { launch { work.withPermit { release.await() } } }
        runCurrent()
        val queued = async { work.withPermit(isForegroundRead = true) { error("cancelled receive must not run") } }
        runCurrent()
        queued.cancel()
        val entered = CompletableDeferred<Unit>()
        val granted = async { work.withPermit(isForegroundRead = true) { entered.complete(Unit); awaitCancellation() } }
        runCurrent()
        occupied[0].cancel()
        occupied[0].join()
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        granted.cancel()
        runCurrent()
        val next = async { work.withPermit(isForegroundRead = true) { "same-next-original" } }
        runCurrent()
        assertThat(next.await()).isEqualTo("same-next-original")
        assertThat(queued.isCancelled).isTrue()
        assertThat(granted.isCancelled).isTrue()
        release.complete(Unit)
        occupied[1].join()
    }

    @Test
    fun `new send mapping precedes a hundred queued origin lookups while keeping two slots`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var active = 0
        var maximum = 0
        val occupied = List(2) { launch { work.withPermit {
            active++
            maximum = maxOf(maximum, active)
            release.await()
            active--
        } } }
        runCurrent()
        val lookups = List(100) { index -> launch { work.withPermit {
            active++
            maximum = maxOf(maximum, active)
            order += "lookup-$index"
            active--
        } } }
        runCurrent()
        val send = async { work.withPermit(isLiveSend = true) {
            active++
            maximum = maxOf(maximum, active)
            order += "original-send"
            active--
            "same-original-mapping"
        } }
        runCurrent()
        assertThat(send.isCompleted).isFalse()
        release.complete(Unit)
        runCurrent()
        assertThat(send.await()).isEqualTo("same-original-mapping")
        assertThat(order.first()).isEqualTo("original-send")
        assertThat(maximum).isEqualTo(2)
        occupied.forEach { it.join() }
        lookups.forEach { it.join() }
        assertThat(order.drop(1)).containsExactlyElementsIn(List(100) { "lookup-$it" }).inOrder()
    }

    @Test
    fun `cancelled queued or granted mapping releases capacity and propagates cancellation`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val occupied = List(2) { launch { work.withPermit { release.await() } } }
        runCurrent()
        val cancelledQueued = async { work.withPermit(isLiveSend = true) { error("cancelled mapping must not run") } }
        runCurrent()
        cancelledQueued.cancel()
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val cancelledGranted = async { work.withPermit(isLiveSend = true) { entered.complete(Unit); awaitCancellation() } }
        runCurrent()
        occupied[0].cancel()
        occupied[0].join()
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        cancelledGranted.cancel()
        runCurrent()
        assertThat(cancelledQueued.isCancelled).isTrue()
        assertThat(cancelledGranted.isCancelled).isTrue()
        val next = async { work.withPermit(isLiveSend = true) { "next-original" } }
        runCurrent()
        assertThat(next.isCompleted).isTrue()
        assertThat(next.await()).isEqualTo("next-original")
        release.complete(Unit)
        occupied[1].join()
    }

    @Test
    fun `failed mapping keeps original failure and releases capacity for later work`() = runTest {
        val work = TimelineTransportScheduler()
        val failure = IOException("original mapping failed")
        val observed = runCatching { work.withPermit(isLiveSend = true) { throw failure } }
        assertThat(observed.exceptionOrNull()).isSameInstanceAs(failure)
        val release = CompletableDeferred<Unit>()
        val first = async { work.withPermit { release.await(); "first" } }
        val second = async { work.withPermit(isLiveSend = true) { release.await(); "second" } }
        runCurrent()
        release.complete(Unit)
        assertThat(first.await()).isEqualTo("first")
        assertThat(second.await()).isEqualTo("second")
    }

    @Test
    fun `ordinary reads progress during a sustained current send burst`() = runTest {
        val work = TimelineTransportScheduler()
        val release = CompletableDeferred<Unit>()
        val occupied = List(2) { launch { work.withPermit { release.await() } } }
        runCurrent()
        val read = async { work.withPermit { "original-read-progress" } }
        val sends = List(100) { launch { work.withPermit(isLiveSend = true) { delay(10) } } }
        runCurrent()
        release.complete(Unit)
        advanceTimeBy(100)
        runCurrent()
        assertThat(read.isCompleted).isTrue()
        assertThat(read.await()).isEqualTo("original-read-progress")
        assertThat(sends.any { !it.isCompleted }).isTrue()
        occupied.forEach { it.join() }
        sends.forEach { it.join() }
    }
}
