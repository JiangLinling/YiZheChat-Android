/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.lease

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewBackingCleanupStatus
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCleanup
import org.junit.Test
import org.junit.Assert.assertThrows

class LocalPreviewClosureTrackerTest {
    @Test
    fun `source cleanup cannot complete a still active renderer`() {
        var receipts = 0
        val tracker = LocalPreviewClosureTracker { receipts++ }
        val runtime = checkNotNull(tracker.beginRuntime())
        tracker.seal()
        tracker.hostReleased(HOST_CLEAN)
        tracker.sourceReleased(SOURCE_CLEAN)
        assertThat(receipts).isEqualTo(0)
        runtime.complete(RUNTIME_CLEAN)
        assertThat(receipts).isEqualTo(1)
        runtime.complete(RUNTIME_CLEAN)
        tracker.sourceReleased(SOURCE_CLEAN)
        assertThat(receipts).isEqualTo(1)
        assertThat(tracker.beginRuntime()).isNull()
    }

    @Test
    fun `each retry generation must release its own resources`() {
        var receipts = 0
        val tracker = LocalPreviewClosureTracker { receipts++ }
        val old = checkNotNull(tracker.beginRuntime())
        old.complete(RUNTIME_CLEAN)
        val current = checkNotNull(tracker.beginRuntime())
        tracker.seal()
        tracker.hostReleased(HOST_CLEAN)
        tracker.sourceReleased(SOURCE_CLEAN)
        old.complete(RUNTIME_CLEAN)
        assertThat(receipts).isEqualTo(0)
        current.complete(RUNTIME_CLEAN)
        assertThat(receipts).isEqualTo(1)
    }

    @Test
    fun `every incomplete runtime source or host fact retains pending state`() {
        for (mask in 0 until 512) {
            var receipts = 0
            val tracker = LocalPreviewClosureTracker { receipts++ }
            val runtime = checkNotNull(tracker.beginRuntime())
            tracker.seal()
            runtime.complete(FilePreviewRuntimeCleanup(bit(mask, 0), bit(mask, 1), bit(mask, 2)))
            tracker.hostReleased(HOST_CLEAN.copy(
                leaseTokenMatched = bit(mask, 3),
                activeStreamsClosed = bit(mask, 4),
                hostHandleCloseCompleted = bit(mask, 5),
            ))
            tracker.sourceReleased(LocalAttachmentPreviewCleanup(bit(mask, 6), bit(mask, 7), bit(mask, 8)))
            assertThat(receipts).isEqualTo(if (mask == 511) 1 else 0)
        }
    }

    @Test
    fun `all actual cleanup orders join without depending on callback timing`() {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        orders.forEach { order ->
            var receipts = 0
            val tracker = LocalPreviewClosureTracker { receipts++ }
            val runtime = checkNotNull(tracker.beginRuntime())
            tracker.seal()
            order.forEachIndexed { index, event ->
                when (event) {
                    0 -> runtime.complete(RUNTIME_CLEAN)
                    1 -> tracker.hostReleased(HOST_CLEAN)
                    2 -> tracker.sourceReleased(SOURCE_CLEAN)
                }
                assertThat(receipts).isEqualTo(if (index == 2) 1 else 0)
            }
        }
    }

    @Test
    fun `a host closed before rendering can release only its own observed local resources`() {
        var receipts = 0
        val tracker = LocalPreviewClosureTracker { receipts++ }
        tracker.seal()
        tracker.sourceReleased(SOURCE_CLEAN)
        assertThat(receipts).isEqualTo(0)
        tracker.hostReleased(HOST_CLEAN)
        assertThat(receipts).isEqualTo(1)
    }

    private fun bit(mask: Int, index: Int): Boolean = mask and (1 shl index) != 0

    @Test
    fun `sealing after all observed cleanup still emits the result`() {
        var receipts = 0
        val tracker = LocalPreviewClosureTracker { receipts++ }
        val runtime = checkNotNull(tracker.beginRuntime())
        runtime.complete(RUNTIME_CLEAN)
        tracker.hostReleased(HOST_CLEAN)
        tracker.sourceReleased(SOURCE_CLEAN)
        assertThat(receipts).isEqualTo(0)
        tracker.seal()
        tracker.seal()
        assertThat(receipts).isEqualTo(1)
    }

    @Test
    fun `failed cleanup observer can retry the same complete result`() {
        var attempts = 0
        val tracker = LocalPreviewClosureTracker {
            attempts++
            if (attempts == 1) throw IllegalStateException("observer unavailable")
        }
        tracker.hostReleased(HOST_CLEAN)
        tracker.sourceReleased(SOURCE_CLEAN)
        assertThrows(IllegalStateException::class.java) { tracker.seal() }
        tracker.seal()
        tracker.seal()
        assertThat(attempts).isEqualTo(2)
    }

    private companion object {
        val RUNTIME_CLEAN = FilePreviewRuntimeCleanup(true, true, true)
        val SOURCE_CLEAN = LocalAttachmentPreviewCleanup(true, true, true)
        val HOST_CLEAN = FilePreviewLeaseReleaseResult(true, true, true, FilePreviewBackingCleanupStatus.NOT_OBSERVABLE)
    }
}
