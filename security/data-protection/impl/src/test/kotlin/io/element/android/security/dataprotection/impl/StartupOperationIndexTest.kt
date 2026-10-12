/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupOperationIndexTest {
    @Test
    fun `reads are bounded at four and retain signed index order`() = runTest {
        var active = 0
        var peak = 0
        val ids = (0 until 9).map(Int::toString)
        val started = testScheduler.currentTime
        val result = readStartupOperationIndex(ids) { id ->
            active++
            peak = maxOf(peak, active)
            try { delay(100); "verified-$id" } finally { active-- }
        }
        assertThat(peak).isEqualTo(4)
        assertThat(active).isEqualTo(0)
        assertThat(result).containsExactlyElementsIn(ids.map { "verified-$it" }).inOrder()
        assertThat(testScheduler.currentTime - started).isEqualTo(300L)
    }

    @Test
    fun `scope mismatch cancels sibling reads and never exposes a partial index`() = runTest {
        var active = 0
        val parked = CompletableDeferred<Unit>()
        val result = runCatching {
            readStartupOperationIndex(listOf("wrong-subject", "valid-one", "valid-two", "valid-three", "queued")) { id ->
                active++
                try {
                    if (id == "wrong-subject") { delay(10); error("installation mismatch") }
                    parked.await()
                    id
                } finally { active-- }
            }
        }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("installation mismatch")
        assertThat(active).isEqualTo(0)
    }

    @Test
    fun `caller cancellation closes all active and queued reads`() = runTest {
        var active = 0
        val parked = CompletableDeferred<Unit>()
        val reader = backgroundScope.async {
            readStartupOperationIndex((0 until 12).map(Int::toString)) {
                active++
                try { parked.await() } finally { active-- }
            }
        }
        runCurrent()
        assertThat(active).isEqualTo(4)
        reader.cancel()
        runCurrent()
        assertThat(active).isEqualTo(0)
        assertThat(reader.isCancelled).isTrue()
    }

    @Test
    fun `parallel readers inherit the exact original lifecycle context`() = runTest {
        val original = LifecycleOperationContext("original-index-operation", FullAppLifecycleId.LC_06)
        val contexts = withContext(original) {
            readStartupOperationIndex(listOf("one", "two", "three")) { currentLifecycleOperationContext() }
        }
        contexts.forEach { assertThat(it).isSameInstanceAs(original) }
    }

    @Test
    fun `invalid bounds and duplicate IDs fail before any transport starts`() = runTest {
        var reads = 0
        for (ids in listOf(listOf("duplicate", "duplicate"), (0 until 129).map(Int::toString))) {
            assertThat(runCatching { readStartupOperationIndex(ids) { reads++ } }.isFailure).isTrue()
        }
        assertThat(reads).isEqualTo(0)
        assertThat(readStartupOperationIndex(emptyList()) { reads++ }).isEmpty()
    }
}
