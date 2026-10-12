/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.KmsTransportPriority
import io.element.android.security.contentprotection.api.KmsTransportSchedulingContext
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SecureMatrixMediaLoaderTest {
    @Test
    fun `BUG043 unavailable original epoch wipes transport bytes and queues one original failure`() = runTest {
        val frame = "protected-original-frame".encodeToByteArray()
        val coordinator = FakeAppOperationalStateCoordinator()
        val delegate = ExpiringProtectedMediaService(NOW + 60_000)
        val missing = object : MatrixProtectedMediaService by delegate, MatrixProtectedMediaAuthorityService {
            override fun inspect(frame: ByteArray) = delegate.inspect(frame)
            override suspend fun decryptToEncryptedStaging(
                frame: ByteArray, vaultId: AccountVaultId, accountIdHash: String, plaintext: OutputStream,
            ): io.element.android.security.securestorage.api.SecureMediaReleaseAuthority {
                throw ContentProtectionException.RoomEpochMaterialUnavailable()
            }
        }
        val manager = RecordingAuthorizedLeaseManager()
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(frame), AccountVaultId("original-history"),
            InMemorySensitiveFileStore(), manager, this, SystemClock { NOW }, coordinator,
            protectedMediaService = missing, accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true })
        val request = io.element.android.security.dataprotection.api.lifecycleOperationRequest(
            FullAppLifecycleId.LC_16, "download", accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
        )
        val original = coordinator.start(request)
        val result = withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(original.operationId, FullAppLifecycleId.LC_16)) {
            loader.downloadMediaFile(MediaSource("mxc://example.org/original"), MimeTypes.Jpeg, "photo.jpg")
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.RoomEpochMaterialUnavailable::class.java)
        assertThat(frame.all { it == 0.toByte() }).isTrue()
        assertThat(manager.authorizedRequest).isNull()
        assertThat(manager.legacyIssueCalls).isEqualTo(0)
        assertThat(coordinator.startedRequests).hasSize(1)
        val failure = coordinator.clientReceipts.single()
        assertThat(failure.operationId).isEqualTo(original.operationId)
        assertThat(failure.resultCode).isEqualTo("MEDIA_DOWNLOAD_KEY_UNAVAILABLE_AND_CLEANED")
        assertThat(failure.receiptReference).isEqualTo("android-media-download-failure:v1:${original.operationId}")
    }

    @Test
    fun `cancelled progressive acquisition retries failed disposal and releases the original download permit`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var acquisitions = 0
        var closes = 0
        val late = mockk<io.element.android.libraries.matrix.api.media.ProgressiveMediaFile>(relaxed = true)
        io.mockk.every { late.close() } answers { if (++closes == 1) throw java.io.IOException("Disposal pending.") }
        io.mockk.every { late.isReadQuiescent } answers { closes >= 2 }
        val fresh = mockk<MediaFile>(relaxed = true)
        val coordinator = FakeAppOperationalStateCoordinator()
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(byteArrayOf(1)), AccountVaultId("cancelled-progressive"),
            InMemorySensitiveFileStore(), RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            progressiveVideoEnabled = { true }, progressiveVideoAcquirer = { _, _ ->
                if (++acquisitions == 1) { gate.await(); late } else fresh
            })
        val source = MediaSource("mxc://example.org/video", "{}",
            io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$video"))
        val waiting = launch { loader.openProgressiveVideo(source, 100_000) }
        runCurrent()
        assertThat(acquisitions).isEqualTo(1)
        waiting.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(closes).isEqualTo(1)
        assertThat(loader.openProgressiveVideo(source, 100_000).getOrThrow()).isSameInstanceAs(fresh)
        assertThat(closes).isEqualTo(2)
        assertThat(acquisitions).isEqualTo(2)
    }

    @Test
    fun `disabled progressive rollout leaves the old path and lifecycle untouched`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        var acquisitions = 0
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(byteArrayOf(1)), AccountVaultId("disabled-progressive"),
            InMemorySensitiveFileStore(), RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            progressiveVideoAcquirer = { _, _ -> acquisitions++; mockk<MediaFile>(relaxed = true) })
        val source = MediaSource("mxc://example.org/video", "{}",
            io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$video"))
        assertThat(loader.openProgressiveVideo(source, 100_000).getOrThrow()).isNull()
        assertThat(acquisitions).isEqualTo(0)
        assertThat(coordinator.startedRequests).isEmpty()
    }

    @Test
    fun `admitted progressive verification failure preserves original lifecycle and never downloads through legacy`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val delegate = RecordingMediaLoader(byteArrayOf(1))
        var acquisitions = 0
        val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("admitted-progressive"),
            InMemorySensitiveFileStore(), RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            progressiveVideoEnabled = { true },
            progressiveVideoAcquirer = { _, _ ->
                acquisitions++
                assertThat(io.element.android.security.dataprotection.api.currentLifecycleOperationContext()?.lifecycleId)
                    .isEqualTo(FullAppLifecycleId.LC_16)
                throw ContentProtectionException.AuthenticationFailed()
            })
        val source = MediaSource("mxc://example.org/video", "{}",
            io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$video"))
        assertThat(loader.openProgressiveVideo(source, 100_000).isFailure).isTrue()
        assertThat(acquisitions).isEqualTo(1)
        assertThat(delegate.observedPriority).isNull()
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.startedRequests.single().accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(coordinator.startedRequests.single().matrixDeviceIdHash).isEqualTo(DEVICE_HASH)
        assertThat(coordinator.startedRequests.single().eventIdHash).isNotNull()
    }

    @Test
    fun `single use downloaded object retains original lifecycle identity before encrypted write`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val store = InMemorySensitiveFileStore()
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(byteArrayOf(1, 2, 3)), AccountVaultId("durable-owner"),
            store, RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH)
        val source = MediaSource("mxc://example.org/shared", eventOrigin =
            io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$media"))
        val file = loader.downloadMediaFile(source, MimeTypes.Ogg, "fixture.ogg").getOrThrow()
        val owner = checkNotNull(store.transientMediaRequests.values.single().transientMediaOwner)
        val request = coordinator.startedRequests.single()
        assertThat(owner.operationId).isEqualTo(request.operationId)
        assertThat(owner.accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(owner.matrixDeviceIdHash).isEqualTo(DEVICE_HASH)
        assertThat(owner.roomIdHash).isEqualTo(request.roomIdHash)
        assertThat(owner.eventIdHash).isEqualTo(request.eventIdHash)
        file.close()
        advanceUntilIdle()
    }

    @Test
    fun `nested media refuses another account device expired or terminal original before acquisition`() = runTest {
        val fake = FakeAppOperationalStateCoordinator()
        val request = io.element.android.security.dataprotection.api.lifecycleOperationRequest(
            FullAppLifecycleId.LC_16, "download", ACCOUNT_HASH, DEVICE_HASH)
        val original = fake.start(request)
        val now = System.currentTimeMillis()
        val invalid = listOf(
            original.copy(subjects = original.subjects.copy(accountIdHash = "c".repeat(64))),
            original.copy(subjects = original.subjects.copy(matrixDeviceIdHash = "e".repeat(64))),
            original.copy(createdAtEpochMillis = now - 120_000, deadlineEpochMillis = now - 60_000),
            original.copy(status = io.element.android.security.dataprotection.api.LifecycleOperationStatus.SUCCEEDED,
                terminalReceiptId = "signed-terminal"),
        )
        invalid.forEach { response ->
            assertThat(response).isNotEqualTo(original)
            val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by fake {
                override suspend fun operation(operationId: String) = response
            }
            val delegate = RecordingMediaLoader(byteArrayOf(1, 2, 3))
            val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("parent-guard"), InMemorySensitiveFileStore(),
                RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
                accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH)
            withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(request.operationId, FullAppLifecycleId.LC_16)) {
                assertThat(loader.loadMediaContent(MediaSource("mxc://example.org/shared")).isFailure).isTrue()
            }
            assertThat(delegate.observedPriority).isNull()
        }
        assertThat(fake.startedRequests).hasSize(1)
        assertThat(fake.clientReceipts).isEmpty()
    }

    @Test
    fun `nested download inherits only the original event and submits no premature inner receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val origin = io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$media")
        fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val request = io.element.android.security.dataprotection.api.lifecycleOperationRequest(
            FullAppLifecycleId.LC_16, "download", ACCOUNT_HASH, DEVICE_HASH, hash(origin.roomId), hash(checkNotNull(origin.eventId)),
        )
        coordinator.start(request)
        val delegate = RecordingMediaLoader(byteArrayOf(1, 2, 3))
        val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("nested-media"), InMemorySensitiveFileStore(),
            RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH)
        withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(request.operationId, FullAppLifecycleId.LC_16)) {
            val source = MediaSource("mxc://example.org/shared", eventOrigin = origin)
            assertThat(loader.loadMediaContent(source).isSuccess).isTrue()
            assertThat(loader.loadMediaContent(source.copy(eventOrigin = origin.copy(eventId = "\$another"))).isFailure).isTrue()
            assertThat(loader.loadMediaContent(source.copy(eventOrigin = origin.copy(roomId = "!other:example.org"))).isFailure).isTrue()
            assertThat(loader.loadMediaContent(source.copy(eventOrigin = null)).isFailure).isTrue()
        }
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `standalone download persists canonical room and event in its original request`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(byteArrayOf(1, 2, 3)), AccountVaultId("scoped-media"),
            InMemorySensitiveFileStore(), RecordingLeaseManager(), this, SystemClock { NOW }, coordinator,
            accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH)
        val origin = io.element.android.libraries.matrix.api.media.MediaEventOrigin("!room:example.org", "\$media")
        loader.loadMediaContent(MediaSource("mxc://example.org/shared", eventOrigin = origin)).getOrThrow()
        fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertThat(coordinator.startedRequests.single().roomIdHash).isEqualTo(hash(origin.roomId))
        assertThat(coordinator.startedRequests.single().eventIdHash).isEqualTo(hash(checkNotNull(origin.eventId)))
    }

    @Test
    fun `generic file downloads used by Coil and notifications remain background unless caller declares user action`() = runTest {
        suspend fun fetch(userAction: Boolean): KmsTransportPriority? {
            val delegate = RecordingMediaLoader(byteArrayOf(1, 2, 3))
            val loader = SecureMatrixMediaLoader(
                delegate, AccountVaultId("role-test"), InMemorySensitiveFileStore(), RecordingLeaseManager(),
                this@runTest, SystemClock { NOW }, FakeAppOperationalStateCoordinator(),
                accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            )
            val download: suspend () -> Unit = {
                loader.downloadMediaFile(MediaSource("mxc://example.org/automatic-or-clicked"), MimeTypes.Ogg, "fixture.ogg").getOrThrow().close()
            }
            if (userAction) withContext(KmsTransportSchedulingContext(KmsTransportPriority.USER_MEDIA)) { download() } else download()
            return delegate.observedPriority
        }
        assertThat(fetch(false)).isEqualTo(KmsTransportPriority.BACKGROUND)
        assertThat(fetch(true)).isEqualTo(KmsTransportPriority.USER_MEDIA)
    }

    @Test
    fun `strict policy rejects media without an authenticated YiZhe frame`() = runTest {
        val plaintext = "downgrade-media-canary".encodeToByteArray()
        val leaseManager = RecordingLeaseManager()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader(plaintext),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        val result = loader.downloadMediaFile(
            source = MediaSource("mxc://example.org/plaintext-downgrade"),
            mimeType = MimeTypes.Ogg,
            filename = "renamed.yzm",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(leaseManager.request).isNull()
        assertThat(plaintext.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `strict policy keeps public profile thumbnails memory only without requiring a message envelope`() = runTest {
        val avatar = "public-avatar".encodeToByteArray()
        val coordinator = FakeAppOperationalStateCoordinator()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader(avatar),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = RecordingLeaseManager(),
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = coordinator,
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        val result = loader.loadPublicProfileThumbnail(MediaSource("mxc://example.org/avatar"), 128, 128)

        assertThat(result.getOrThrow()).isEqualTo("public-avatar".encodeToByteArray())
        assertThat(coordinator.startedRequests).isEmpty()
    }

    @Test
    fun `protected thumbnail opens LC16 download before decrypting`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader("protected-frame".encodeToByteArray()),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = RecordingLeaseManager(),
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = coordinator,
            protectedMediaService = ExpiringProtectedMediaService(NOW + 5.minutes.inWholeMilliseconds),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        val result = loader.loadMediaThumbnail(MediaSource("mxc://example.org/thumbnail"), 320, 240)

        assertThat(result.getOrThrow()).isEqualTo("decrypted".encodeToByteArray())
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
        assertThat(coordinator.startedRequests.single().trigger).isEqualTo("download")
        assertThat(coordinator.startedRequests.single().accountIdHash).isEqualTo(ACCOUNT_HASH)
        assertThat(coordinator.startedRequests.single().matrixDeviceIdHash).isEqualTo(DEVICE_HASH)
    }

    @Test
    fun `protected thumbnail preserves ephemeral cache policy at the delegate boundary`() = runTest {
        val delegate = RecordingMediaLoader("protected-frame".encodeToByteArray())
        val loader = SecureMatrixMediaLoader(
            delegate = delegate,
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = RecordingLeaseManager(),
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            protectedMediaService = ExpiringProtectedMediaService(NOW + 5.minutes.inWholeMilliseconds),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        loader.loadMediaThumbnail(
            source = MediaSource("mxc://example.org/one-time-thumbnail"),
            width = 320,
            height = 240,
            cachePolicy = MediaCachePolicy.EPHEMERAL,
        ).getOrThrow()

        assertThat(delegate.lastThumbnailCachePolicy).isEqualTo(MediaCachePolicy.EPHEMERAL)
    }

    @Test
    fun `one-time file download preserves ephemeral cache policy at the delegate boundary`() = runTest {
        val delegate = RecordingMediaLoader("media-canary".encodeToByteArray())
        val loader = SecureMatrixMediaLoader(
            delegate = delegate,
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = RecordingLeaseManager(),
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
        )

        loader.downloadMediaFile(
            source = MediaSource("mxc://example.org/one-time-original"),
            mimeType = MimeTypes.Jpeg,
            filename = "one-time.jpg",
            useCache = false,
        ).getOrThrow().close()

        assertThat(delegate.lastContentCachePolicy).isEqualTo(MediaCachePolicy.EPHEMERAL)
    }

    @Test
    fun `download stores encrypted object and returns revocable content handle`() = runTest {
        val plaintext = "media-canary".encodeToByteArray()
        val delegate = RecordingMediaLoader(plaintext)
        val store = InMemorySensitiveFileStore()
        val leaseManager = RecordingLeaseManager()
        val loader = SecureMatrixMediaLoader(
            delegate = delegate,
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = store,
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
        )

        val mediaFile = loader.downloadMediaFile(
            source = MediaSource("mxc://example.org/media"),
            mimeType = MimeTypes.Ogg,
            filename = "voice.ogg",
        ).getOrThrow()

        val request = checkNotNull(leaseManager.request)
        assertThat(request.reference.purpose).isEqualTo(SecureObjectPurpose.VOICE_MESSAGE)
        assertThat(request.requireSeekableDescriptor).isTrue()
        val stored = store.read(request.reference) { it.readBytes() }
        assertThat(stored).isEqualTo("media-canary".encodeToByteArray())
        assertThat(delegate.payload.all { it == 0.toByte() }).isTrue()
        assertThat(mediaFile.uri()).isSameInstanceAs(leaseManager.lease.uri)
        assertThat(mediaFile.persist("ignored")).isFalse()
        assertThat(runCatchingExceptions { mediaFile.path() }.isFailure).isTrue()

        mediaFile.close()
        advanceUntilIdle()

        assertThat(leaseManager.lease.closed).isTrue()
        assertThat(runCatchingExceptions { store.read(request.reference) { it.read() } }.isFailure).isTrue()
    }

    @Test
    fun `pdf download requests a seekable descriptor without creating a plaintext file`() = runTest {
        val leaseManager = RecordingLeaseManager()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader("pdf-canary".encodeToByteArray()),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
        )

        val mediaFile = loader.downloadMediaFile(
            source = MediaSource("mxc://example.org/report"),
            mimeType = MimeTypes.Pdf,
            filename = "report.pdf",
        ).getOrThrow()

        assertThat(leaseManager.request?.requireSeekableDescriptor).isTrue()
        assertThat(runCatchingExceptions { mediaFile.path() }.isFailure).isTrue()
        mediaFile.close()
        advanceUntilIdle()
    }

    @Test
    fun `download failure does not issue lease`() = runTest {
        val leaseManager = RecordingLeaseManager()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader(ByteArray(0), failure = IllegalStateException("network")),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
        )

        val result = loader.downloadMediaFile(MediaSource("mxc://example.org/missing"), null, null)

        assertThat(result.isFailure).isTrue()
        assertThat(leaseManager.request).isNull()
    }

    @Test
    fun `protected media passes original expiry authority to authorized lease without issuing a legacy lease`() = runTest {
        val leaseManager = RecordingAuthorizedLeaseManager()
        val authorityService = ExpiringProtectedMediaService(NOW + 5.minutes.inWholeMilliseconds)
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader("protected-frame".encodeToByteArray()),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            protectedMediaService = authorityService,
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        val file = loader.downloadMediaFile(MediaSource("mxc://example.org/protected"), MimeTypes.Jpeg, "photo.jpg").getOrThrow()

        assertThat(leaseManager.authorizedRequest?.releaseAuthority).isSameInstanceAs(authorityService.authority)
        assertThat(authorityService.authority.expiresAtEpochMillis).isEqualTo(NOW + 5.minutes.inWholeMilliseconds)
        assertThat(leaseManager.legacyIssueCalls).isEqualTo(0)
        file.close()
    }

    @Test
    fun `already expired protected media is removed without issuing a lease`() = runTest {
        val leaseManager = RecordingAuthorizedLeaseManager()
        val frame = "protected-frame".encodeToByteArray()
        val loader = SecureMatrixMediaLoader(
            delegate = RecordingMediaLoader(frame),
            vaultId = AccountVaultId("alice-device-session"),
            sensitiveFileStore = InMemorySensitiveFileStore(),
            secureMediaLeaseManager = leaseManager,
            sessionCoroutineScope = this,
            clock = SystemClock { NOW },
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
            protectedMediaService = ExpiringProtectedMediaService(NOW),
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true },
        )

        val result = loader.downloadMediaFile(MediaSource("mxc://example.org/expired"), MimeTypes.Jpeg, "photo.jpg")

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        assertThat(leaseManager.authorizedRequest).isNull()
        assertThat(leaseManager.legacyIssueCalls).isEqualTo(0)
        assertThat(frame.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `missing protected authority adapter rejects download and erases transport bytes`() = runTest {
        val frame = "protected-frame".encodeToByteArray()
        val manager = RecordingLeaseManager()
        val facts = FakeAppOperationalStateCoordinator()
        val legacyOnly = object : MatrixProtectedMediaService by ExpiringProtectedMediaService(NOW + 5.minutes.inWholeMilliseconds) {}
        val loader = SecureMatrixMediaLoader(RecordingMediaLoader(frame), AccountVaultId("missing-authority"),
            InMemorySensitiveFileStore(), manager, backgroundScope, SystemClock { NOW }, facts,
            protectedMediaService = legacyOnly, accountIdHash = ACCOUNT_HASH, matrixDeviceIdHash = DEVICE_HASH,
            requireInnerProtection = { true })
        val result = loader.downloadMediaFile(MediaSource("mxc://example.org/legacy-only"), MimeTypes.Jpeg, "photo.jpg")
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(frame.all { it == 0.toByte() }).isTrue()
        assertThat(manager.request).isNull()
        assertThat(facts.clientReceipts).isEmpty()
    }

    private companion object {
        const val NOW = 1_000_000L
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_HASH = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}

private class ExpiringProtectedMediaService(
    private val expiresAtEpochMillis: Long,
) : MatrixProtectedMediaService, MatrixProtectedMediaAuthorityService {
    val authority = ExpiryFixtureAuthority(expiresAtEpochMillis)

    override fun inspect(frame: ByteArray) = ProtectedMatrixMediaInspection(
        "expiry-fixture-content", "IMAGE", "policy", 1L, "1".repeat(64), "2".repeat(64), 9L,
    )

    override suspend fun decryptToEncryptedStaging(
        frame: ByteArray, vaultId: AccountVaultId, accountIdHash: String, plaintext: OutputStream,
    ): io.element.android.security.securestorage.api.SecureMediaReleaseAuthority {
        if (expiresAtEpochMillis <= 1_000_000L) throw ContentProtectionException.Expired()
        plaintext.write("decrypted".encodeToByteArray())
        return authority
    }
    override suspend fun protect(source: File, request: ProtectContentRequest): ProtectedMatrixMedia = error("Not used")

    override fun isProtected(frame: ByteArray): Boolean = true

    override suspend fun decrypt(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): ProtectedMatrixMediaDecryption {
        plaintext.write("decrypted".encodeToByteArray())
        return ProtectedMatrixMediaDecryption(expiresAtEpochMillis)
    }
}

private class ExpiryFixtureAuthority(val expiresAtEpochMillis: Long) : io.element.android.security.securestorage.api.SecureMediaReleaseAuthority {
    override fun close() = Unit
}

private class RecordingAuthorizedLeaseManager : SecureMediaLeaseManager,
    io.element.android.security.securestorage.api.AuthorizedSecureMediaLeaseManager,
    io.element.android.security.securestorage.api.SecureObjectStagingStore {
    var authorizedRequest: io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest? = null
    var legacyIssueCalls = 0
    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease { legacyIssueCalls++; error("Protected files must use authorized leases") }
    override fun revokeScope(scope: String) = Unit
    override fun cleanupFailures() = emptyList<io.element.android.security.securestorage.api.SecureObjectStagingCleanupReport>()
    override suspend fun issueAuthorized(request: io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest):
        io.element.android.security.securestorage.api.AuthorizedSecureMediaLease {
        authorizedRequest = request
        return mockk(relaxed = true)
    }
    override suspend fun stage(
        request: io.element.android.security.securestorage.api.SecureObjectWriteRequest,
        writePlaintext: suspend (OutputStream) -> Unit,
    ): io.element.android.security.securestorage.api.SecureObjectStagingTransaction {
        java.io.ByteArrayOutputStream().use { writePlaintext(it) }
        return mockk(relaxed = true)
    }
}

private class RecordingMediaLoader(
    val payload: ByteArray,
    private val failure: Exception? = null,
) : MatrixMediaLoader {
    var lastContentCachePolicy: MediaCachePolicy? = null
    var lastThumbnailCachePolicy: MediaCachePolicy? = null
    var observedPriority: KmsTransportPriority? = null

    override suspend fun loadMediaContent(source: MediaSource, cachePolicy: MediaCachePolicy): Result<ByteArray> {
        lastContentCachePolicy = cachePolicy
        observedPriority = currentCoroutineContext()[KmsTransportSchedulingContext]?.priority
        return failure?.let(Result.Companion::failure) ?: Result.success(payload)
    }

    override suspend fun loadMediaThumbnail(
        source: MediaSource,
        width: Long,
        height: Long,
        cachePolicy: MediaCachePolicy,
    ): Result<ByteArray> {
        lastThumbnailCachePolicy = cachePolicy
        return loadMediaContent(source, cachePolicy)
    }

    override suspend fun downloadMediaFile(
        source: MediaSource,
        mimeType: String?,
        filename: String?,
        useCache: Boolean,
    ): Result<MediaFile> = error("Secure adapter must not request an SDK plaintext file.")
}

private class RecordingLeaseManager : SecureMediaLeaseManager {
    var request: SecureMediaLeaseRequest? = null
    val lease = RecordingLease()

    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease {
        this.request = request
        return lease
    }

    override fun revokeScope(scope: String) {
        if (request?.revocationScope == scope) lease.close()
    }
}

private class RecordingLease : SecureMediaLease {
    override val uri: Uri = mockk(relaxed = true)
    var closed = false

    override fun openInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun close() {
        closed = true
    }
}
