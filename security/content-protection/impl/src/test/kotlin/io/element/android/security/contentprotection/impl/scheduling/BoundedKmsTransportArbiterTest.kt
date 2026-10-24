/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl.scheduling

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.KmsTransportPriority
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BoundedKmsTransportArbiterTest {
    @Test
    fun `initial render borrows only two slots and never consumes the reserved current message slot`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        arbiter.tryEnqueue(entry("history", KmsTransportPriority.BACKGROUND))
        val history = checkNotNull(arbiter.takeBatch())
        repeat(5) { arbiter.tryEnqueue(entry("bootstrap-$it", KmsTransportPriority.FOREGROUND_BOOTSTRAP)) }
        val first = checkNotNull(arbiter.takeBatch())
        val second = checkNotNull(arbiter.takeBatch())
        assertThat(first.values).containsExactly("bootstrap-0")
        assertThat(second.values).containsExactly("bootstrap-1")
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.tryEnqueue(entry("current", KmsTransportPriority.CURRENT_MESSAGE))
        val current = checkNotNull(arbiter.takeBatch())
        assertThat(current.values).containsExactly("current")
        assertThat(arbiter.activeCount()).isEqualTo(4)
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.complete(first)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("bootstrap-2")
        arbiter.complete(history)
        arbiter.complete(second)
        arbiter.complete(current)
    }

    @Test
    fun `bootstrap admission preserves current message pending reserve and same operation order`() {
        val bounded = BoundedKmsTransportArbiter<String>()
        repeat(60) { assertThat(bounded.tryEnqueue(entry("boot-$it", KmsTransportPriority.FOREGROUND_BOOTSTRAP))).isTrue() }
        assertThat(bounded.tryEnqueue(entry("boot-overflow", KmsTransportPriority.FOREGROUND_BOOTSTRAP))).isFalse()
        repeat(4) { assertThat(bounded.tryEnqueue(entry("current-$it", KmsTransportPriority.CURRENT_MESSAGE))).isTrue() }
        val ordered = BoundedKmsTransportArbiter<String>()
        ordered.tryEnqueue(entry("one", KmsTransportPriority.FOREGROUND_BOOTSTRAP, "first"))
        ordered.tryEnqueue(entry("one", KmsTransportPriority.CURRENT_MESSAGE, "second"))
        val first = checkNotNull(ordered.takeBatch())
        assertThat(first.values).containsExactly("first")
        assertThat(ordered.takeBatch()).isNull()
        ordered.complete(first)
        assertThat(checkNotNull(ordered.takeBatch()).values).containsExactly("second")
    }

    @Test
    fun `legacy without operation keeps one active transport while queued current message overtakes history`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        arbiter.tryEnqueue(BoundedKmsTransportArbiter.Entry(LegacyKmsTransportOperation.CURRENT_MESSAGE, KmsTransportPriority.CURRENT_MESSAGE, "active"))
        val active = checkNotNull(arbiter.takeBatch())
        arbiter.tryEnqueue(BoundedKmsTransportArbiter.Entry(LegacyKmsTransportOperation.BACKGROUND, KmsTransportPriority.BACKGROUND, "history"))
        arbiter.tryEnqueue(BoundedKmsTransportArbiter.Entry(LegacyKmsTransportOperation.CURRENT_MESSAGE, KmsTransportPriority.CURRENT_MESSAGE, "current"))
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.complete(active)
        val current = checkNotNull(arbiter.takeBatch())
        assertThat(current.values).containsExactly("current")
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.complete(current)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("history")
    }

    private fun entry(key: String, priority: KmsTransportPriority, value: String = key) = BoundedKmsTransportArbiter.Entry(key, priority, value)

    @Test
    fun `many historical media operations cannot occupy the foreground slot or block its admission`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        repeat(8) { assertThat(arbiter.tryEnqueue(entry("history-$it", KmsTransportPriority.BACKGROUND))).isTrue() }
        val history = checkNotNull(arbiter.takeBatch())
        assertThat(arbiter.takeBatch()).isNull()
        assertThat(arbiter.tryEnqueue(entry("current", KmsTransportPriority.CURRENT_MESSAGE))).isTrue()
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("current")
        assertThat(arbiter.activeCount()).isEqualTo(2)
        arbiter.complete(history)
    }

    @Test
    fun `waiting same operation head consumes no active slot and cannot be reordered by priority`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        arbiter.tryEnqueue(entry("one", KmsTransportPriority.USER_MEDIA, "first"))
        val first = checkNotNull(arbiter.takeBatch())
        arbiter.tryEnqueue(entry("one", KmsTransportPriority.BACKGROUND, "second"))
        arbiter.tryEnqueue(entry("one", KmsTransportPriority.CURRENT_MESSAGE, "third"))
        arbiter.tryEnqueue(entry("other", KmsTransportPriority.CURRENT_MESSAGE))
        val other = checkNotNull(arbiter.takeBatch())
        assertThat(other.values).containsExactly("other")
        assertThat(arbiter.activeCount()).isEqualTo(2)
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.complete(first)
        val second = checkNotNull(arbiter.takeBatch())
        assertThat(second.values).containsExactly("second")
        arbiter.complete(second)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("third")
    }

    @Test
    fun `three clicked media operations still reserve one of four slots for current text`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        repeat(4) { arbiter.tryEnqueue(entry("voice-$it", KmsTransportPriority.USER_MEDIA)) }
        repeat(3) { assertThat(arbiter.takeBatch()).isNotNull() }
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.tryEnqueue(entry("text", KmsTransportPriority.CURRENT_MESSAGE))
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("text")
        assertThat(arbiter.activeCount()).isEqualTo(4)
        assertThat(arbiter.takeBatch()).isNull()
    }

    @Test
    fun `one queue has sixty background positions and four foreground reserved positions`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        repeat(60) { assertThat(arbiter.tryEnqueue(entry("old-$it", KmsTransportPriority.BACKGROUND))).isTrue() }
        assertThat(arbiter.tryEnqueue(entry("overflow", KmsTransportPriority.BACKGROUND))).isFalse()
        repeat(4) { assertThat(arbiter.tryEnqueue(entry("text-$it", KmsTransportPriority.CURRENT_MESSAGE))).isTrue() }
        assertThat(arbiter.pendingCount()).isEqualTo(64)
        assertThat(arbiter.tryEnqueue(entry("too-many", KmsTransportPriority.CURRENT_MESSAGE))).isFalse()
    }

    @Test
    fun `batch cap and operation boundary are retained`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        repeat(40) { arbiter.tryEnqueue(entry("same", KmsTransportPriority.CURRENT_MESSAGE, "$it")) }
        arbiter.tryEnqueue(entry("different", KmsTransportPriority.CURRENT_MESSAGE))
        val first = checkNotNull(arbiter.takeBatch())
        assertThat(first.values).hasSize(32)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("different")
        assertThat(arbiter.takeBatch()).isNull()
        arbiter.complete(first)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactlyElementsIn((32..39).map(Int::toString)).inOrder()
    }

    @Test
    fun `removing obsolete queued owners neither removes active resources nor releases their permits`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        arbiter.tryEnqueue(entry("old-active", KmsTransportPriority.BACKGROUND))
        val active = checkNotNull(arbiter.takeBatch())
        arbiter.tryEnqueue(entry("old-queued", KmsTransportPriority.BACKGROUND))
        arbiter.tryEnqueue(entry("new", KmsTransportPriority.CURRENT_MESSAGE))
        assertThat(arbiter.removeQueued { it.startsWith("old") }).containsExactly("old-queued")
        assertThat(arbiter.activeCount()).isEqualTo(1)
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("new")
        arbiter.complete(active)
        assertThat(runCatching { arbiter.complete(active) }.isFailure).isTrue()
    }

    @Test
    fun `foreground traffic has bounded fairness for an eligible historical head`() {
        val arbiter = BoundedKmsTransportArbiter<String>()
        arbiter.tryEnqueue(entry("history", KmsTransportPriority.BACKGROUND))
        repeat(8) {
            arbiter.tryEnqueue(entry("current-$it", KmsTransportPriority.CURRENT_MESSAGE))
            val batch = checkNotNull(arbiter.takeBatch())
            assertThat(batch.values).containsExactly("current-$it")
            arbiter.complete(batch)
        }
        arbiter.tryEnqueue(entry("current-last", KmsTransportPriority.CURRENT_MESSAGE))
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("history")
        assertThat(checkNotNull(arbiter.takeBatch()).values).containsExactly("current-last")
    }

    @Test
    fun `cancelled producer waiting for queue capacity does not leave an admitted item`() = runTest {
        val arbiter = BoundedKmsTransportArbiter<String>()
        repeat(60) { arbiter.tryEnqueue(entry("old-$it", KmsTransportPriority.BACKGROUND)) }
        val waiter = async { arbiter.enqueue(entry("cancelled", KmsTransportPriority.BACKGROUND)) }
        runCurrent()
        waiter.cancel()
        arbiter.removeQueued { it == "old-0" }
        runCurrent()
        assertThat(arbiter.pendingCount()).isEqualTo(59)
        assertThat(arbiter.removeQueued { it == "cancelled" }).isEmpty()
    }
}
