package io.element.android.libraries.localofficeviewer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewBackingCleanupStatus
import io.element.android.libraries.localofficeviewer.api.FilePreviewLease
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.localofficeviewer.api.FilePreviewTransferProgress
import io.element.android.libraries.localofficeviewer.api.FilePreviewVerification
import io.element.android.libraries.localofficeviewer.api.FilePreviewVerificationAuthority
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCleanupResult
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCloseReason
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class LocalOfficeRuntimeGateTest {
    @Test
    fun `expiry and normal revocation are not presented as a security inspection failure`() {
        assertThat(FilePreviewLeaseReleaseReason.SESSION_EXPIRED.officeLeaseReleaseMessageResource())
            .isEqualTo(R.string.local_office_session_expired)
        assertThat(FilePreviewLeaseReleaseReason.ACCESS_REVOKED.officeLeaseReleaseMessageResource())
            .isEqualTo(R.string.local_office_session_ended)
        assertThat(FilePreviewLeaseReleaseReason.SECURITY_BLOCKED.officeLeaseReleaseMessageResource())
            .isEqualTo(R.string.local_office_security_blocked)
    }

    @Test
    fun `termination revokes bytes closes active stream and rejects late results`() {
        val token = "single-use-token"
        val bytes = byteArrayOf(1, 2, 3)
        val payload = LocalOfficeValidatedPayload(bytes)
        val handler = SessionContentPathHandler(token)
        val states = mutableListOf<LocalOfficePreviewState>()
        val closes = mutableListOf<Pair<LocalOfficeCloseReason, LocalOfficeCleanupResult>>()
        val gate = LocalOfficeRuntimeGate(
            sourceHandler = handler,
            onState = states::add,
            onClose = { reason, cleanup -> closes += reason to cleanup },
        )
        assertThat(handler.install(payload, "application/octet-stream")).isTrue()
        val activeStream = checkNotNull(handler.openPayloadStream(token)).stream
        assertThat(activeStream.read()).isEqualTo(1)

        assertThat(gate.beginTermination(LocalOfficeCloseReason.RENDER_TIMEOUT)).isTrue()

        assertThat(gate.isAcceptingResults()).isFalse()
        assertThat(gate.beginTermination(LocalOfficeCloseReason.RENDER_FAILED)).isFalse()
        assertThat(payload.isCleared()).isTrue()
        assertThrows(IOException::class.java) { activeStream.read() }

        gate.finishWithoutRuntime(LocalOfficeCloseReason.RENDER_TIMEOUT)
        assertThat(states).containsExactly(
            LocalOfficePreviewState.CLOSED,
            LocalOfficePreviewState.RESOURCES_RELEASED,
        ).inOrder()
        assertThat(closes).hasSize(1)
        assertThat(closes.single().first).isEqualTo(LocalOfficeCloseReason.RENDER_TIMEOUT)
        assertThat(closes.single().second.sourceClosed).isTrue()
    }

    @Test
    fun `app lock releases exact host lease and reports only observable cleanup`() {
        val lease = TrackingFilePreviewLease()
        val closes = mutableListOf<Pair<LocalOfficeCloseReason, LocalOfficeCleanupResult>>()
        val gate = LocalOfficeRuntimeGate(
            sourceHandler = SessionContentPathHandler("token"),
            hostLease = lease,
            onState = {},
            onClose = { reason, cleanup -> closes += reason to cleanup },
        )

        gate.finishWithoutRuntime(LocalOfficeCloseReason.APP_LOCKED)

        assertThat(lease.releaseReasons).containsExactly(FilePreviewLeaseReleaseReason.APP_LOCKED)
        assertThat(closes.single().second.hostLeaseReleased).isTrue()
        assertThat(closes.single().second.hostLeaseTokenMatched).isTrue()
        assertThat(closes.single().second.hostActiveStreamsClosed).isTrue()
        assertThat(closes.single().second.hostBackingCleanupStatus)
            .isEqualTo(FilePreviewBackingCleanupStatus.NOT_OBSERVABLE)
    }

    @Test
    fun `retryable render timeout keeps host lease available`() {
        val lease = TrackingFilePreviewLease()
        val closes = mutableListOf<Pair<LocalOfficeCloseReason, LocalOfficeCleanupResult>>()
        val gate = LocalOfficeRuntimeGate(
            sourceHandler = SessionContentPathHandler("token"),
            hostLease = lease,
            onState = {},
            onClose = { reason, cleanup -> closes += reason to cleanup },
        )

        gate.finishWithoutRuntime(LocalOfficeCloseReason.RENDER_TIMEOUT)

        assertThat(lease.releaseReasons).isEmpty()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
        assertThat(closes.single().second.hostLeaseReleased).isFalse()
        assertThat(closes.single().second.hostLeaseTokenMatched).isFalse()
    }

    @Test
    fun `attempt disposal releases host unless a retry explicitly preserves it`() {
        val releasedLease = TrackingFilePreviewLease()
        val releasedCloses = mutableListOf<Pair<LocalOfficeCloseReason, LocalOfficeCleanupResult>>()
        LocalOfficeRuntimeGate(
            sourceHandler = SessionContentPathHandler("released"),
            hostLease = releasedLease,
            onState = {},
            onClose = { reason, cleanup -> releasedCloses += reason to cleanup },
        ).disposeAttempt()

        val preservedLease = TrackingFilePreviewLease()
        val preservedCloses = mutableListOf<Pair<LocalOfficeCloseReason, LocalOfficeCleanupResult>>()
        LocalOfficeRuntimeGate(
            sourceHandler = SessionContentPathHandler("preserved"),
            hostLease = preservedLease,
            onState = {},
            onClose = { reason, cleanup -> preservedCloses += reason to cleanup },
        ).disposeAttempt(preserveHostLease = true)

        assertThat(releasedLease.releaseReasons).containsExactly(FilePreviewLeaseReleaseReason.HOST_DISPOSED)
        assertThat(releasedCloses.single().second.hostLeaseReleased).isTrue()
        assertThat(preservedLease.releaseReasons).isEmpty()
        assertThat(preservedCloses.single().second.hostLeaseReleased).isFalse()
    }

    private class TrackingFilePreviewLease : FilePreviewLease {
        private val mutableState = MutableStateFlow<FilePreviewLeaseState>(FilePreviewLeaseState.Available)
        val releaseReasons = mutableListOf<FilePreviewLeaseReleaseReason>()

        override val leaseToken = "lease-token"
        override val operationId = "operation"
        override val carrierUri = "content://securemedia/document"
        override val verification = FilePreviewVerification(
            authority = FilePreviewVerificationAuthority.SECURE_MATRIX_MEDIA_LOADER,
            carrierIntegrityVerified = true,
            requiredPlaintextAuthenticationSatisfied = true,
        )
        override val state: StateFlow<FilePreviewLeaseState> = mutableState
        override val progress: StateFlow<FilePreviewTransferProgress> =
            MutableStateFlow(FilePreviewTransferProgress.IndeterminateSecureReady)

        override fun openInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))

        override fun release(reason: FilePreviewLeaseReleaseReason): FilePreviewLeaseReleaseResult {
            releaseReasons += reason
            mutableState.value = FilePreviewLeaseState.Released(reason)
            return FilePreviewLeaseReleaseResult(
                leaseTokenMatched = true,
                activeStreamsClosed = true,
                hostHandleCloseCompleted = true,
                backingCleanupStatus = FilePreviewBackingCleanupStatus.NOT_OBSERVABLE,
            )
        }
    }
}
