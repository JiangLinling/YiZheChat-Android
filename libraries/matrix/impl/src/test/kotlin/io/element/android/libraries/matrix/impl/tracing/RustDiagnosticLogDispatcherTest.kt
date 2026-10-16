/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.tracing

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class RustDiagnosticLogDispatcherTest {
    @Test fun `a full queue uses the caller and preserves every accepted diagnostic`() {
        val executor = createDiagnosticLogExecutor(capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val caller = Thread.currentThread()
        try {
            executor.execute {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                count.incrementAndGet()
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            executor.execute { count.incrementAndGet() }
            executor.execute {
                assertThat(Thread.currentThread()).isSameInstanceAs(caller)
                count.incrementAndGet()
            }
            assertThat(executor.queue.size).isEqualTo(1)
            assertThat(count.get()).isEqualTo(1)
        } finally {
            release.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
        assertThat(count.get()).isEqualTo(3)
    }
}
