/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class ExternalOpenSourceRejectionTest {
    @Test
    fun `confirmed source denial retires exact renderer projection without a handoff`() = runTest {
        val file = TrackingFile()
        val media = aLocalMedia(file.uri())
        val token = FilePreviewLeaseRegistry.register(media.filePreviewLeaseBinding(), file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, media.filePreviewLeaseBinding(), authority))
        val result = actions { SecureMediaAuthenticatedReadRejection.EXPIRED }.open(media.copy(filePreviewLeaseToken = token))

        val failure = result.exceptionOrNull() as ProtectedMediaReadUnavailableException
        assertThat(failure.reason).isEqualTo(SecureMediaAuthenticatedReadRejection.EXPIRED)
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Released(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE))
        assertThat(file.closes).isEqualTo(1)
    }

    @Test
    fun `denial cannot retire a token bound to a different attachment`() = runTest {
        val file = TrackingFile()
        val media = aLocalMedia(file.uri())
        val token = FilePreviewLeaseRegistry.register(media.filePreviewLeaseBinding(), file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, media.filePreviewLeaseBinding(), authority))
        val different = media.copy(info = media.info.copy(filename = "other.docx"), filePreviewLeaseToken = token)

        assertThat(actions { SecureMediaAuthenticatedReadRejection.REVOKED }.open(different).isFailure).isTrue()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
        assertThat(file.closes).isEqualTo(0)
        lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE)
    }

    @Test
    fun `source revoked at final main dispatcher check closes renderer and rejects launch`() = runTest {
        val file = TrackingFile()
        val media = aLocalMedia(file.uri())
        val token = FilePreviewLeaseRegistry.register(media.filePreviewLeaseBinding(), file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, media.filePreviewLeaseBinding(), authority))
        var checks = 0
        val result = actions {
            if (++checks == 1) null else SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH
        }.open(media.copy(filePreviewLeaseToken = token))

        assertThat(checks).isEqualTo(2)
        assertThat((result.exceptionOrNull() as ProtectedMediaReadUnavailableException).reason)
            .isEqualTo(SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH)
        assertThat(lease.state.value).isInstanceOf(FilePreviewLeaseState.Released::class.java)
        assertThat(file.closes).isEqualTo(1)
    }

    @Test
    fun `activity absence with authorized source does not invent authority rejection`() = runTest {
        val file = TrackingFile()
        val media = aLocalMedia(file.uri())
        val token = FilePreviewLeaseRegistry.register(media.filePreviewLeaseBinding(), file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, media.filePreviewLeaseBinding(), authority))

        val result = actions { null }.open(media.copy(filePreviewLeaseToken = token))
        assertThat(result.exceptionOrNull()).isNotInstanceOf(ProtectedMediaReadUnavailableException::class.java)
        assertThat(result.isFailure).isTrue()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
        lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE)
    }

    @Test
    fun `cleanup observer failure remains attached to original denial and cannot dispatch`() = runTest {
        val file = TrackingFile()
        val media = aLocalMedia(file.uri())
        val observerFailure = IllegalStateException("synthetic cleanup observer failure")
        var failObserver = true
        val token = FilePreviewLeaseRegistry.register(media.filePreviewLeaseBinding(), file, onRelease = {
            if (failObserver) throw observerFailure
        })
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, media.filePreviewLeaseBinding(), authority))

        val result = actions { SecureMediaAuthenticatedReadRejection.CLOSED }.open(media.copy(filePreviewLeaseToken = token))
        val failure = result.exceptionOrNull() as ProtectedMediaReadUnavailableException
        assertThat(failure.reason).isEqualTo(SecureMediaAuthenticatedReadRejection.CLOSED)
        assertThat(failure.suppressed.toList()).containsExactly(observerFailure)
        assertThat(lease.state.value).isInstanceOf(FilePreviewLeaseState.Released::class.java)
        assertThat(file.closes).isEqualTo(1)
        failObserver = false
        lease.release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
    }

    private fun TestScope.actions(check: () -> SecureMediaAuthenticatedReadRejection?) = AndroidLocalMediaActions(
        RuntimeEnvironment.getApplication(), testCoroutineDispatchers(), aBuildMeta(),
        FakeDataProtectionPolicyProvider(allowContentExport = true),
        object : SecureMediaLeaseManager {
            override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("No minting allowed")
            override fun readAuthorizationRejection(uri: Uri): SecureMediaAuthenticatedReadRejection? = check()
        },
    )

    private val authority get() = "${RuntimeEnvironment.getApplication().packageName}.securemedia"
    private inner class TrackingFile : MediaFile {
        var closes = 0
        override fun uri(): Uri = Uri.parse("content://$authority/v1/${"a".repeat(48)}")
        override fun uriString(): String = uri().toString()
        override fun path(): String = error("No plaintext path")
        override fun openInputStream() = ByteArrayInputStream(byteArrayOf(1))
        override fun persist(path: String): Boolean = false
        override fun close() { closes++ }
    }
}
