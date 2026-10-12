package io.element.android.libraries.mediaviewer.impl.local.lease

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class DefaultLocalAttachmentPreviewLeaseServiceTest {
    @Test
    fun `longer browsing never extends the authority deadline or send confirmation TTL`() {
        val now = 100_000L
        assertThat(localPreviewTtl(now + 30.minutes.inWholeMilliseconds, true, now)).isEqualTo(30.minutes)
        assertThat(localPreviewTtl(now + 2.minutes.inWholeMilliseconds, true, now)).isEqualTo(2.minutes)
        assertThat(localPreviewTtl(now + 30.minutes.inWholeMilliseconds, false, now)).isEqualTo(5.minutes)
        assertThrows(IllegalArgumentException::class.java) { localPreviewTtl(now, true, now) }
    }

    @Test
    fun `browsing requests local preview authority and never requests upload`() = runTest {
        val client = FakeMatrixClient()
        val vault = mockk<VaultKeyService>()
        coEvery { vault.isUnlocked(deriveAccountVaultId(client.sessionId.value, client.deviceId.value)) } returns true
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val request = slot<LifecycleOperationRequest>()
        val unavailable = IllegalStateException("authority unavailable")
        coEvery { coordinator.start(capture(request)) } throws unavailable
        val source = LocalMedia(
            uri = Uri.parse("content://selected/fixture.pdf"),
            info = anImageMediaInfo().copy(mimeType = "application/pdf", filename = "fixture.pdf"),
            lifecycleOperationId = "browse-attachment-operation",
            lifecycleRoomIdHash = "a".repeat(64),
        )
        val service = DefaultLocalAttachmentPreviewLeaseService(
            context = mockk(),
            matrixClient = client,
            coordinator = coordinator,
            ownerAuthority = mockk(),
            vaultKeyService = vault,
            sensitiveFileStore = mockk(),
            secureMediaLeaseManager = mockk(),
            sessionCoroutineScope = backgroundScope,
        )
        assertThat(service.openForBrowse(source).exceptionOrNull()).isSameInstanceAs(unavailable)
        assertThat(request.captured.trigger).isEqualTo("local_preview")
        assertThat(request.captured.operationId).isEqualTo(source.lifecycleOperationId)
        assertThat(request.captured.accountIdHash).isEqualTo(hash(client.sessionId.value))
        assertThat(request.captured.matrixDeviceIdHash).isEqualTo(hash(client.deviceId.value))
    }

    @Test
    fun `local send preview starts the same upload intent and subjects used by sending`() = runTest {
        val client = FakeMatrixClient()
        val vault = mockk<VaultKeyService>()
        coEvery { vault.isUnlocked(deriveAccountVaultId(client.sessionId.value, client.deviceId.value)) } returns true
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val request = slot<LifecycleOperationRequest>()
        // Stop at the authority boundary: this test must not fabricate a successful authorization or lease.
        val unavailable = IllegalStateException("authority unavailable")
        coEvery { coordinator.start(capture(request)) } throws unavailable
        val source = LocalMedia(
            uri = Uri.parse("content://selected/fixture.pdf"),
            info = anImageMediaInfo().copy(mimeType = "application/pdf", filename = "fixture.pdf"),
            lifecycleOperationId = "selected-attachment-operation",
            lifecycleRoomIdHash = "a".repeat(64),
        )
        val service = DefaultLocalAttachmentPreviewLeaseService(
            context = mockk(),
            matrixClient = client,
            coordinator = coordinator,
            ownerAuthority = mockk(),
            vaultKeyService = vault,
            sensitiveFileStore = mockk(),
            secureMediaLeaseManager = mockk(),
            sessionCoroutineScope = backgroundScope,
        )

        assertThat(service.open(source).exceptionOrNull()).isSameInstanceAs(unavailable)
        val upload = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_16,
            trigger = "upload",
            operationId = source.lifecycleOperationId,
            accountIdHash = hash(client.sessionId.value),
            matrixDeviceIdHash = hash(client.deviceId.value),
            roomIdHash = source.lifecycleRoomIdHash,
            deadlineEpochMillis = request.captured.deadlineEpochMillis,
        )
        assertThat(request.captured).isEqualTo(upload)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
