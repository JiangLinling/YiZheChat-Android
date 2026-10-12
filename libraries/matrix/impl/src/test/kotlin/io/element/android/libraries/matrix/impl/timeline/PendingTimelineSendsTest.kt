/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.PendingSendLifetime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingTimelineSendsTest {
    @Test
    fun `navigation close retains in-flight send and rejects new sends`() = runTest {
        var closeCount = 0
        val lifetime = PendingSendLifetime { closeCount++ }
        val protectionReady = CompletableDeferred<Unit>()
        val send = async {
            lifetime.withSend {
                protectionReady.await()
                assertThat(closeCount).isEqualTo(0)
                "accepted"
            }
        }
        runCurrent()
        lifetime.close()
        lifetime.close()
        assertThat(closeCount).isEqualTo(0)
        assertThat(runCatching { lifetime.withSend { error("must not run") } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        protectionReady.complete(Unit)
        assertThat(send.await()).isEqualTo("accepted")
        lifetime.close()
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `session cancellation releases retained native resource`() = runTest {
        var closed = false
        val lifetime = PendingSendLifetime { closed = true }
        val send = async { lifetime.withSend { awaitCancellation() } }
        runCurrent()
        lifetime.close()
        send.cancel()
        send.join()
        assertThat(closed).isTrue()
    }

    @Test
    fun `all overlapping sends must finish before native close`() = runTest {
        var closeCount = 0
        val lifetime = PendingSendLifetime { closeCount++ }
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val first = async { lifetime.withSend { firstReady.await() } }
        val second = async { lifetime.withSend { secondReady.await() } }
        runCurrent()
        lifetime.close()
        firstReady.complete(Unit)
        first.await()
        assertThat(closeCount).isEqualTo(0)
        secondReady.complete(Unit)
        second.await()
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `idle close releases exactly once`() = runTest {
        var closeCount = 0
        val lifetime = PendingSendLifetime { closeCount++ }
        lifetime.close()
        lifetime.close()
        assertThat(closeCount).isEqualTo(1)
    }
}
