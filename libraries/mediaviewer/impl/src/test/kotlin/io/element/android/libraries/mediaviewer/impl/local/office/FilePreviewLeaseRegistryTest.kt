@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewBackingCleanupStatus
import io.element.android.libraries.localofficeviewer.api.FilePreviewLease
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class FilePreviewLeaseRegistryTest {
    @Test
    fun `stale token cannot release a replacement lease for the same operation`() {
        val operationId = "operation"
        val oldFile = TrackingMediaFile()
        val replacementFile = TrackingMediaFile()
        val oldToken = FilePreviewLeaseRegistry.register(operationId, oldFile)
        val replacementToken = FilePreviewLeaseRegistry.register(operationId, replacementFile)
        val replacement = checkNotNull(FilePreviewLeaseRegistry.acquire(replacementToken, operationId, AUTHORITY))

        assertThat(oldToken).isNotEqualTo(replacementToken)
        assertThat(FilePreviewLeaseRegistry.release(oldToken, FilePreviewLeaseReleaseReason.ATTACHMENT_SWITCHED).leaseTokenMatched)
            .isTrue()
        assertThat(FilePreviewLeaseRegistry.release(oldToken, FilePreviewLeaseReleaseReason.CANCELLED).leaseTokenMatched)
            .isFalse()
        assertThat(replacement.state.value).isEqualTo(FilePreviewLeaseState.Available)
        assertThat(replacement.openInputStream().use { it.read() }).isEqualTo(1)
        assertThat(oldFile.closeCount).isEqualTo(1)
        assertThat(replacementFile.closeCount).isEqualTo(0)

        replacement.release(FilePreviewLeaseReleaseReason.CANCELLED)
        assertThat(replacementFile.closeCount).isEqualTo(1)
    }

    @Test
    fun `current state is available before use and release closes streams exactly once`() {
        val mediaFile = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("operation", mediaFile)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "operation", AUTHORITY))
        val activeStream = lease.openInputStream()

        assertThat(lease.carrierUri).isEqualTo("content://$AUTHORITY/v1/${"a".repeat(64)}")
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
        assertThat(lease.progress.value.verifiedBytes).isNull()
        assertThat(lease.progress.value.totalBytes).isNull()

        val firstRelease = lease.release(FilePreviewLeaseReleaseReason.ACCESS_REVOKED)
        val secondRelease = lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE)

        assertThat(lease.state.value).isEqualTo(
            FilePreviewLeaseState.Released(FilePreviewLeaseReleaseReason.ACCESS_REVOKED)
        )
        assertThat(firstRelease).isEqualTo(secondRelease)
        assertThat(firstRelease.leaseTokenMatched).isTrue()
        assertThat(firstRelease.activeStreamsClosed).isTrue()
        assertThat(firstRelease.hostHandleCloseCompleted).isTrue()
        assertThat(firstRelease.backingCleanupStatus).isEqualTo(FilePreviewBackingCleanupStatus.NOT_OBSERVABLE)
        assertThat(mediaFile.closeCount).isEqualTo(1)
        assertThrows(IOException::class.java) { activeStream.read() }
    }

    @Test
    fun `wrong operation authority and unsupported carrier fail closed`() {
        val mediaFile = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("operation", mediaFile)

        assertThat(FilePreviewLeaseRegistry.acquire(token, "other-operation", AUTHORITY)).isNull()
        assertThat(FilePreviewLeaseRegistry.acquire(token, "operation", "other.securemedia")).isNull()
        FilePreviewLeaseRegistry.release(token, FilePreviewLeaseReleaseReason.CANCELLED)

        val unsupported = TrackingMediaFile(uri = "file:///tmp/plaintext.pdf")
        assertThrows(SecurityException::class.java) {
            FilePreviewLeaseRegistry.register("operation", unsupported)
        }
        assertThat(unsupported.closeCount).isEqualTo(0)
    }

    @Test
    fun `strict binding rejects room and attachment replay`() {
        val mediaFile = TrackingMediaFile()
        val binding = FilePreviewLeaseBinding(
            operationId = "operation",
            roomIdHash = "room-a",
            resourceFingerprint = "attachment-a",
        )
        val token = FilePreviewLeaseRegistry.register(binding, mediaFile)

        assertThat(
            FilePreviewLeaseRegistry.acquire(
                token,
                binding.copy(roomIdHash = "room-b"),
                AUTHORITY,
            )
        ).isNull()
        assertThat(
            FilePreviewLeaseRegistry.acquire(
                token,
                binding.copy(resourceFingerprint = "attachment-b"),
                AUTHORITY,
            )
        ).isNull()
        assertThat(FilePreviewLeaseRegistry.acquire(token, binding, AUTHORITY)).isNotNull()

        FilePreviewLeaseRegistry.release(token, FilePreviewLeaseReleaseReason.CANCELLED)
    }

    @Test
    fun `remembered lease is reacquired when room or attachment binding changes`() = runComposeUiTest {
        val uri = Uri.parse("content://$AUTHORITY/v1/${"a".repeat(64)}")
        val original = aLocalMedia(uri).copy(
            lifecycleOperationId = "operation",
            lifecycleRoomIdHash = "room-a",
        )
        val token = FilePreviewLeaseRegistry.register(original.filePreviewLeaseBinding(), TrackingMediaFile())
        var media by mutableStateOf(original.copy(filePreviewLeaseToken = token))
        var observedLease: FilePreviewLease? = null
        setContent {
            observedLease = rememberBoundFilePreviewLease(media, AUTHORITY)
        }

        waitForIdle()
        assertThat(observedLease).isNotNull()

        runOnIdle { media = media.copy(lifecycleRoomIdHash = "room-b") }
        waitForIdle()
        assertThat(observedLease).isNull()

        runOnIdle { media = original.copy(filePreviewLeaseToken = token) }
        waitForIdle()
        assertThat(observedLease).isNotNull()

        runOnIdle { media = media.copy(info = media.info.copy(filename = "other-file")) }
        waitForIdle()
        assertThat(observedLease).isNull()

        FilePreviewLeaseRegistry.release(token, FilePreviewLeaseReleaseReason.CANCELLED)
    }

    @Test fun `fresh admission forwards only for current live host and keeps original identity`() = kotlinx.coroutines.test.runTest {
        val file = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("fresh-operation", file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "fresh-operation", AUTHORITY))
        repeat(9) { assertThat(lease.maintainReadAuthorization()).isTrue() }
        assertThat(file.maintenanceCalls).isEqualTo(9)
        assertThat(lease.leaseToken).isEqualTo(token)
        assertThat(lease.operationId).isEqualTo("fresh-operation")
        lease.release(FilePreviewLeaseReleaseReason.APP_LOCKED)
        assertThat(lease.maintainReadAuthorization()).isFalse()
        assertThat(file.maintenanceCalls).isEqualTo(9)
        assertThat(file.closeCount).isEqualTo(1)
    }

    @Test fun `failed fresh admission closes active stream once and never reopens host`() = kotlinx.coroutines.test.runTest {
        val file = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("failed-operation", file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "failed-operation", AUTHORITY))
        val stream = lease.openInputStream()
        file.maintenance = { false }
        assertThat(lease.maintainReadAuthorization()).isFalse()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Released(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE))
        assertThrows(IOException::class.java) { stream.read() }
        file.maintenance = { true }
        assertThat(lease.maintainReadAuthorization()).isFalse()
        assertThat(file.closeCount).isEqualTo(1)
    }

    @Test fun `host release during suspended fresh admission cannot become available again`() = kotlinx.coroutines.test.runTest {
        val file = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("race-operation", file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "race-operation", AUTHORITY))
        file.maintenance = { lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE); true }
        assertThat(lease.maintainReadAuthorization()).isFalse()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Released(FilePreviewLeaseReleaseReason.USER_CLOSE))
        assertThat(file.closeCount).isEqualTo(1)
    }

    @Test fun `fresh admission exception fails closed with pending backing cleanup unchanged`() = kotlinx.coroutines.test.runTest {
        val file = TrackingMediaFile()
        val token = FilePreviewLeaseRegistry.register("exception-operation", file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "exception-operation", AUTHORITY))
        file.maintenance = { throw IOException("unavailable") }
        assertThat(lease.maintainReadAuthorization()).isFalse()
        assertThat(lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE).backingCleanupStatus)
            .isEqualTo(FilePreviewBackingCleanupStatus.NOT_OBSERVABLE)
        assertThat(file.closeCount).isEqualTo(1)
    }

    private class TrackingMediaFile(
        private val uri: String = "content://$AUTHORITY/v1/${"a".repeat(64)}",
    ) : MediaFile {
        var closeCount = 0
            private set

        var maintenanceCalls = 0
        var maintenance: suspend () -> Boolean = { true }
        override suspend fun maintainReadAuthorization(): Boolean {
            maintenanceCalls++
            return maintenance()
        }

        override fun path(): String = ""

        override fun uriString(): String = uri

        override fun openInputStream(): InputStream = CloseAwareInputStream(byteArrayOf(1, 2, 3))

        override fun persist(path: String): Boolean = false

        override fun close() {
            closeCount++
        }
    }

    private class CloseAwareInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var closed = false

        override fun read(): Int {
            if (closed) throw IOException("closed")
            return super.read()
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val AUTHORITY = "com.yizhe.chat.debug.securemedia"
    }
}
