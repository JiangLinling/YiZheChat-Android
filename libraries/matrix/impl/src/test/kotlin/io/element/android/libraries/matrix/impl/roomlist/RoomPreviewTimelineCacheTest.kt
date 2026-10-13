/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.IOException

class RoomPreviewTimelineCacheTest {
    private val first = RoomId("!one:qa")
    private val second = RoomId("!two:qa")
    private val third = RoomId("!three:qa")

    @Test fun `changing latest events reuse one live metadata timeline`() = runTest {
        val timeline = FakeFfiTimeline()
        var loads = 0
        val cache = RoomPreviewTimelineCache(backgroundScope) { loads++; timeline }
        repeat(100) { index ->
            timeline.latestEventIdentity = "\$event-$index"
            assertThat(cache.withTimeline(first) { it.latestEventId() }).isEqualTo("\$event-$index")
        }
        assertThat(loads).isEqualTo(1)
        assertThat(timeline.closeCount).isEqualTo(0)
        cache.close()
        assertThat(timeline.closeCount).isEqualTo(1)
    }

    @Test fun `concurrent list consumers share construction without sharing authorization results`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val timeline = FakeFfiTimeline()
        var loads = 0
        var reads = 0
        val cache = RoomPreviewTimelineCache(backgroundScope) { loads++; ready.await(); timeline }
        val readers = List(12) { async { cache.withTimeline(first) { ++reads } } }
        runCurrent()
        assertThat(loads).isEqualTo(1)
        ready.complete(Unit)
        readers.forEach { it.await() }
        assertThat(reads).isEqualTo(12)
        cache.close()
        assertThat(timeline.closeCount).isEqualTo(1)
    }

    @Test fun `preview timeout leaves one tracked construction for the next consumer`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val timeline = FakeFfiTimeline()
        var loads = 0
        val cache = RoomPreviewTimelineCache(backgroundScope) { loads++; ready.await(); timeline }
        assertThat(withTimeoutOrNull(10) { cache.withTimeline(first) { true } }).isNull()
        assertThat(loads).isEqualTo(1)
        ready.complete(Unit)
        assertThat(cache.withTimeline(first) { true }).isTrue()
        assertThat(loads).isEqualTo(1)
        cache.close()
        assertThat(timeline.closeCount).isEqualTo(1)
    }

    @Test fun `occupied capacity does not evict an active native reader`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val timelines = mutableListOf<FakeFfiTimeline>()
        val cache = RoomPreviewTimelineCache(backgroundScope) { FakeFfiTimeline().also(timelines::add) }
        val readers = listOf(first, second).map { id -> async { cache.withTimeline(id) { ready.await() } } }
        runCurrent()
        assertThat(cache.withTimeline(third) { true }).isNull()
        assertThat(timelines).hasSize(2)
        assertThat(timelines.map { it.closeCount }).containsExactly(0, 0)
        readers.forEach { it.cancelAndJoin() }
        cache.close()
        assertThat(timelines.map { it.closeCount }).containsExactly(1, 1)
    }

    @Test fun `unused least recent owner is released before another room is admitted`() = runTest {
        val timelines = mutableMapOf<RoomId, FakeFfiTimeline>()
        val cache = RoomPreviewTimelineCache(backgroundScope) { id -> FakeFfiTimeline().also { timelines[id] = it } }
        listOf(first, second, first, third).forEach { id -> cache.withTimeline(id) { true } }
        assertThat(timelines.getValue(first).closeCount).isEqualTo(0)
        assertThat(timelines.getValue(second).closeCount).isEqualTo(1)
        cache.close()
        assertThat(timelines.values.map { it.closeCount }).containsExactly(1, 1, 1)
    }

    @Test fun `session completion waits for outstanding native readers to release their handle`() = runTest {
        val owner = Job()
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val timeline = FakeFfiTimeline()
        val release = CompletableDeferred<Unit>()
        val cache = RoomPreviewTimelineCache(scope) { timeline }
        val reader = async { cache.withTimeline(first) { release.await() } }
        runCurrent()
        owner.cancelAndJoin()
        assertThat(timeline.closeCount).isEqualTo(0)
        assertThat(cache.withTimeline(second) { true }).isNull()
        release.complete(Unit)
        reader.await()
        assertThat(timeline.closeCount).isEqualTo(1)
    }

    @Test fun `metadata construction failure does not cancel the session and can recover`() = runTest {
        val owner = Job()
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val timeline = FakeFfiTimeline()
        var loads = 0
        val cache = RoomPreviewTimelineCache(scope) { if (++loads == 1) throw IOException("Unavailable"); timeline }
        assertThat(runCatching { cache.withTimeline(first) { true } }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(owner.isActive).isTrue()
        assertThat(cache.withTimeline(first) { true }).isTrue()
        owner.cancelAndJoin()
        assertThat(timeline.closeCount).isEqualTo(1)
    }

    @Test fun `late SDK construction after retirement releases its native result exactly once`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val timeline = FakeFfiTimeline()
        val cache = RoomPreviewTimelineCache(backgroundScope) {
            withContext(NonCancellable) { ready.await(); timeline }
        }
        val reader = async { runCatching { cache.withTimeline(first) { true } } }
        runCurrent()
        cache.close()
        ready.complete(Unit)
        reader.await()
        runCurrent()
        assertThat(timeline.closeCount).isEqualTo(1)
        assertThat(cache.withTimeline(first) { true }).isNull()
        cache.close()
        assertThat(timeline.closeCount).isEqualTo(1)
    }
}
