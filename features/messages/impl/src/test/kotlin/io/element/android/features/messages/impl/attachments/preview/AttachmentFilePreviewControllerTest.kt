/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.attachments.preview

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCancelReason
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCleanup
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AttachmentFilePreviewControllerTest {
    private val source = aLocalMedia(Uri.parse("content://picker/document/1")).copy(
        lifecycleOperationId = "source-operation", lifecycleRoomIdHash = "c".repeat(64),
    )

    @Test
    fun `first state waits and only a matching projection becomes ready`() = runTest {
        val result = CompletableDeferred<Result<LocalAttachmentPreviewLease>>()
        val lease = Lease(source)
        val controller = AttachmentFilePreviewController(source, service { result.await() })
        assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Preparing)
        assertThat(controller.lease).isNull()
        controller.start(backgroundScope)
        runCurrent()
        assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Preparing)
        result.complete(Result.success(lease))
        runCurrent()
        assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Ready)
        assertThat(controller.lease).isSameInstanceAs(lease)
        controller.cancelPreparation() // An already prepared lease remains available for upload transfer.
        assertThat(lease.closeCalls).isEqualTo(0)
        controller.close()
        controller.close()
        assertThat(lease.closeCalls).isEqualTo(1)
    }

    @Test
    fun `returned and thrown preparation failures remain visible without a lease`() = runTest {
        for (throws in listOf(false, true)) {
            val controller = AttachmentFilePreviewController(source, service {
                if (throws) throw IOException("unavailable") else Result.failure(IOException("unavailable"))
            })
            controller.start(backgroundScope)
            runCurrent()
            assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Failed)
            assertThat(controller.lease).isNull()
            controller.close()
        }
    }

    @Test
    fun `a real denial is revoked rather than preparing or ready`() = runTest {
        val controller = AttachmentFilePreviewController(source, service { Result.failure(SecurityException("denied")) })
        controller.start(backgroundScope)
        runCurrent()
        assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Revoked)
        assertThat(controller.lease).isNull()
    }

    @Test
    fun `wrong operation room metadata or missing token fails and releases acquired handle`() = runTest {
        val valid = Lease(source).previewMedia
        for (invalid in listOf(valid.copy(lifecycleOperationId = "other"), valid.copy(lifecycleRoomIdHash = "d".repeat(64)),
            valid.copy(info = valid.info.copy(filename = "other.docx")), valid.copy(filePreviewLeaseToken = null))) {
            val lease = Lease(source, invalid)
            val controller = AttachmentFilePreviewController(source, service { Result.success(lease) })
            controller.start(backgroundScope)
            runCurrent()
            assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Failed)
            assertThat(controller.lease).isNull()
            assertThat(lease.closeCalls).isEqualTo(1)
        }
    }

    @Test
    fun `cancel and leave reject and close a noncooperative late callback`() = runTest {
        for (cancelPreparing in listOf(false, true)) {
            val result = CompletableDeferred<Result<LocalAttachmentPreviewLease>>()
            val lease = Lease(source)
            val controller = AttachmentFilePreviewController(source, service { withContext(NonCancellable) { result.await() } })
            controller.start(backgroundScope)
            runCurrent()
            if (cancelPreparing) controller.cancelPreparation() else controller.close()
            assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Closed)
            result.complete(Result.success(lease))
            runCurrent()
            assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Closed)
            assertThat(controller.lease).isNull()
            assertThat(lease.closeCalls).isEqualTo(1)
        }
    }

    @Test
    fun `cancellation interrupts an issuer still doing preparation`() = runTest {
        var stopped = false
        val controller = AttachmentFilePreviewController(source, service {
            try { awaitCancellation() } finally { stopped = true }
        })
        controller.start(backgroundScope)
        runCurrent()
        controller.close()
        runCurrent()
        assertThat(stopped).isTrue()
        assertThat(controller.state).isEqualTo(AttachmentFilePreviewState.Closed)
    }

    @Test
    fun `switch isolates late old completion from the current resource`() = runTest {
        val oldResult = CompletableDeferred<Result<LocalAttachmentPreviewLease>>()
        val oldLease = Lease(source)
        val old = AttachmentFilePreviewController(source, service { withContext(NonCancellable) { oldResult.await() } })
        old.start(backgroundScope)
        runCurrent()
        old.close()
        val nextSource = source.copy(uri = Uri.parse("content://picker/document/2"), lifecycleOperationId = "next-operation")
        val nextLease = Lease(nextSource)
        val next = AttachmentFilePreviewController(nextSource, service { Result.success(nextLease) })
        next.start(backgroundScope)
        runCurrent()
        oldResult.complete(Result.success(oldLease))
        runCurrent()
        assertThat(oldLease.closeCalls).isEqualTo(1)
        assertThat(next.state).isEqualTo(AttachmentFilePreviewState.Ready)
        assertThat(next.lease).isSameInstanceAs(nextLease)
        assertThat(nextLease.closeCalls).isEqualTo(0)
        next.close()
    }

    @Test
    fun `recreation starts preparing and never restores an old ready lease`() = runTest {
        val oldLease = Lease(source)
        val old = AttachmentFilePreviewController(source, service { Result.success(oldLease) })
        old.start(backgroundScope)
        runCurrent()
        old.close()
        val restored = AttachmentFilePreviewController(source, service { awaitCancellation() })
        assertThat(restored.state).isEqualTo(AttachmentFilePreviewState.Preparing)
        assertThat(restored.lease).isNull()
        assertThat(oldLease.closeCalls).isEqualTo(1)
    }

    private fun service(open: suspend () -> Result<LocalAttachmentPreviewLease>) = object : LocalAttachmentPreviewLeaseService {
        override fun requiresLease(source: LocalMedia) = true
        override suspend fun open(source: LocalMedia) = open()
    }

    private class Lease(source: LocalMedia, projection: LocalMedia? = null) : LocalAttachmentPreviewLease {
        override val previewMedia = projection ?: source.copy(
            uri = Uri.parse("content://com.yizhe.chat.next.debug.securemedia/v1/${"a".repeat(64)}"),
            filePreviewLeaseToken = "test-lease-token",
        )
        var closeCalls = 0
        override fun close() { closeCalls++ }
        override suspend fun transferToUpload() = Result.success(Unit)
        override suspend fun completeUpload() = Result.success(Unit)
        override suspend fun closeAndAwait() = Result.success(LocalAttachmentPreviewCleanup(true, true, true))
        override suspend fun cancel(reason: LocalAttachmentPreviewCancelReason) = closeAndAwait()
    }
}
