/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PendingMediaSendTaskTest {
    @Test fun `cancel active preparation stops that item and preserves its sibling`() = runTest {
        val started = CompletableDeferred<Unit>()
        var released = false
        var cleared = 0
        val first = PendingMediaSendTask(this, { cleared++ }) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        }
        val second = PendingMediaSendTask(this, {}) { true }
        val result = async { first.send() }
        started.await()
        assertThat(first.cancel().getOrThrow()).isEqualTo(MediaSendCancellationResult.Cancelled)
        assertThat(result.await()).isFalse()
        assertThat(released).isTrue()
        assertThat(cleared).isEqualTo(1)
        assertThat(second.send()).isTrue()
    }

    @Test fun `queued cancellation awaits actual handler result before clearing files`() = runTest {
        val started = CompletableDeferred<Unit>()
        val acknowledged = CompletableDeferred<MediaSendCancellationResult>()
        var cleared = false
        val task = PendingMediaSendTask(this, { cleared = true }) { awaitCancellation() }
        task.owner.beforeEnqueue()
        task.owner.attach(object : MediaUploadHandler {
            override suspend fun await(): Result<Unit> = Result.success(Unit)
            override fun cancel() = error("A signal alone must not settle cancellation")
            override suspend fun cancelPending(): Result<MediaSendCancellationResult> {
                started.complete(Unit)
                return Result.success(acknowledged.await())
            }
        })
        val cancelling = async { task.cancel() }
        started.await()
        assertThat(cleared).isFalse()
        acknowledged.complete(MediaSendCancellationResult.Cancelled)
        assertThat(cancelling.await().getOrThrow()).isEqualTo(MediaSendCancellationResult.Cancelled)
        assertThat(cleared).isTrue()
    }

    @Test fun `unknown enqueue and cleanup failure retain original files`() = runTest {
        var cleared = false
        val task = PendingMediaSendTask(this, { cleared = true }) { true }
        task.owner.beforeEnqueue()
        assertThat(task.cancel().getOrThrow()).isEqualTo(MediaSendCancellationResult.Unknown)
        assertThat(cleared).isFalse()
        val failed = PendingMediaSendTask(this, { cleared = true }) { true }
        failed.owner.cleanupFailure = IllegalStateException("KMS receipt missing")
        assertThat(failed.cancel().isFailure).isTrue()
        assertThat(cleared).isFalse()
    }

    @Test fun `interrupted protection retains uncertainty even before SDK enqueue`() = runTest {
        var cleared = false
        val task = PendingMediaSendTask(this, { cleared = true }) { true }
        task.owner.beforeProtection()
        assertThat(task.cancel().getOrThrow()).isEqualTo(MediaSendCancellationResult.Unknown)
        assertThat(cleared).isFalse()
    }

    @Test fun `unused lazy siblings can be closed after another item fails`() = runTest {
        var admitted = false
        val task = PendingMediaSendTask(this, {}) { admitted = true; true }
        task.closeUnused()
        assertThat(admitted).isFalse()
    }

    @Test fun `cancel queued item before its turn prevents admission`() = runTest {
        var admitted = false
        val task = PendingMediaSendTask(this, {}) { admitted = true; true }
        assertThat(task.cancel().getOrThrow()).isEqualTo(MediaSendCancellationResult.Cancelled)
        assertThat(task.send()).isFalse()
        assertThat(admitted).isFalse()
    }
}
