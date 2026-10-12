/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupItem
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupReport
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStoreMaintenanceReport
import io.element.android.security.securestorage.api.SecureVaultDeletionReport
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecureMediaAuthenticatedRangeHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `idle metadata authority expiry retires readers before cleanup without extending process ttl`() {
        val validator = MutableValidator()
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), validator, processDeadline = 3_600_000)
        val stops = AtomicInteger()
        val reader = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token) { stops.incrementAndGet() })
        validator.rejection = SecureMediaAuthorizationRejection.EXPIRED
        var reason: SecureMediaAuthenticatedReadRejection? = null
        assertThat(SecureMediaLeaseRegistry.acquire(fixture.token) { reason = it }).isNull()
        assertThat(reason).isEqualTo(SecureMediaAuthenticatedReadRejection.EXPIRED)
        assertThat(stops.get()).isEqualTo(1)
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        assertThat(SecureMediaLeaseRegistry.acquire(fixture.token)).isNull()
        reader.close()
        reader.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
        assertThat(stops.get()).isEqualTo(1)
        assertThat(fixture.entry.authorizedState!!.processTtlDeadlineElapsedRealtimeMillis).isEqualTo(3_600_000)
    }

    @Test
    fun `idle expiry races suspended authenticated read and never publishes plaintext`() = runTest {
        val store = BarrierRangeStore(PLAINTEXT)
        val validator = MutableValidator()
        val fixture = registryFixture(store, validator, processDeadline = 3_600_000)
        val reader = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token))
        val destination = ByteArray(4) { 0x66 }
        val pending = async { read(fixture.token, 0, destination) }
        runCurrent()
        assertThat(store.entered.isCompleted).isTrue()
        validator.rejection = SecureMediaAuthorizationRejection.EXPIRED
        var reason: SecureMediaAuthenticatedReadRejection? = null
        assertThat(SecureMediaLeaseRegistry.acquire(fixture.token) { reason = it }).isNull()
        assertThat(reason).isEqualTo(SecureMediaAuthenticatedReadRejection.EXPIRED)
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        store.proceed.complete(Unit)
        assertThat(pending.await()).isEqualTo(SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED))
        assertThat(destination).isEqualTo(ByteArray(4) { 0x66 })
        assertThat(checkNotNull(store.capturedDestination).all { it == 0.toByte() }).isTrue()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        reader.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CT monotonic process ttl only shortens issuer authorization`() = runTest {
        val validator = MutableValidator(elapsedRealtimeMillis = 100)
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), validator, processDeadline = 110)
        val first = ByteArray(5)

        validator.elapsedRealtimeMillis = 109
        assertThat(read(fixture.token, 0, first)).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(5))
        assertThat(first).isEqualTo(PLAINTEXT.copyOfRange(0, 5))

        validator.elapsedRealtimeMillis = 110
        val expiredDestination = ByteArray(5) { 0x55 }
        assertThat(read(fixture.token, 0, expiredDestination)).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.EXPIRED)
        )
        assertThat(expiredDestination).isEqualTo(ByteArray(5) { 0x55 })
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CE issuer rejection at final recheck prevents delivery and revokes`() = runTest {
        val validator = MutableValidator(elapsedRealtimeMillis = 100, rejectOnCall = 2)
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), validator)
        val destination = ByteArray(6) { 0x33 }

        val result = read(fixture.token, 0, destination)

        assertThat(result).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.EXPIRED)
        )
        assertThat(destination).isEqualTo(ByteArray(6) { 0x33 })
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CR second concurrent read is immediately busy and does not enter storage`() = runTest {
        val store = BarrierRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())
        val firstDestination = ByteArray(4)
        val first = async { read(fixture.token, 0, firstDestination) }
        runCurrent()
        assertThat(store.entered.isCompleted).isTrue()

        val second = read(fixture.token, 0, ByteArray(4))

        assertThat(second).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.BUSY)
        )
        assertThat(store.readCalls.get()).isEqualTo(1)
        store.proceed.complete(Unit)
        assertThat(first.await()).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(4))
        assertThat(firstDestination).isEqualTo(PLAINTEXT.copyOfRange(0, 4))
    }

    @Test
    fun `CR cancellation clears C source buffer and leaves destination untouched`() = runTest {
        val store = BarrierRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())
        val destination = ByteArray(4) { 0x44 }
        val read = async { read(fixture.token, 0, destination) }
        runCurrent()
        assertThat(store.entered.isCompleted).isTrue()

        read.cancelAndJoin()

        assertThat(destination).isEqualTo(ByteArray(4) { 0x44 })
        assertThat(checkNotNull(store.capturedDestination).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `CR revoke while storage is suspended wins before final copy`() = runTest {
        val store = BarrierRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())
        val destination = ByteArray(4) { 0x66 }
        val read = async { read(fixture.token, 0, destination) }
        runCurrent()
        assertThat(store.entered.isCompleted).isTrue()

        SecureMediaLeaseRegistry.revoke(fixture.token, SecurityException("test revoke"))
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        assertThat(checkNotNull(store.capturedDestination).any { it != 0.toByte() }).isTrue()
        store.proceed.complete(Unit)

        assertThat(read.await()).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
        )
        assertThat(destination).isEqualTo(ByteArray(4) { 0x66 })
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
        assertThat(checkNotNull(store.capturedDestination).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `provider cancellation signals run once and cleanup waits for every active reader`() {
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), MutableValidator())
        val signals = AtomicInteger()
        val first = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token) { signals.incrementAndGet() })
        val second = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token) { signals.incrementAndGet() })
        val originalCause = SecurityException("Original revoke")
        SecureMediaLeaseRegistry.revoke(fixture.token, originalCause)
        SecureMediaLeaseRegistry.revoke(fixture.token, SecurityException("Duplicate revoke"))
        assertThat(signals.get()).isEqualTo(2)
        assertThat(SecureMediaLeaseRegistry.beginProviderRead(fixture.token)).isNull()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        first.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        second.close()
        second.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
        assertThat(fixture.cleanupCause.get()).isSameInstanceAs(originalCause)
        assertThat(signals.get()).isEqualTo(2)
    }

    @Test
    fun `quiescence waits for both authenticated buffer and provider reader`() = runTest {
        val store = BarrierRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())
        val provider = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token))
        val destination = ByteArray(4)
        val pending = async { read(fixture.token, 0, destination) }
        runCurrent()
        SecureMediaLeaseRegistry.revoke(fixture.token)
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        store.proceed.complete(Unit)
        assertThat(pending.await()).isEqualTo(SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED))
        assertThat(checkNotNull(store.capturedDestination).all { it == 0.toByte() }).isTrue()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        provider.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a failed provider stop does not skip others or claim quiescence`() {
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), MutableValidator())
        val first = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token) { error("Injected stop failure") })
        var secondStopped = false
        val second = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(fixture.token) { secondStopped = true })
        val cause = SecurityException("Original revoke")
        SecureMediaLeaseRegistry.revoke(fixture.token, cause)
        assertThat(secondStopped).isTrue()
        assertThat(cause.suppressed).hasLength(1)
        assertThat(fixture.cleanupCalls.get()).isEqualTo(0)
        first.close()
        second.close()
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
        assertThat(fixture.cleanupCause.get()).isSameInstanceAs(cause)
    }

    @Test
    fun `CR03 through CR07 slot release partial failure and late cancellation are deterministic`() = runTest {
        val barrierStore = BarrierRangeStore(PLAINTEXT)
        val barrierFixture = registryFixture(barrierStore, MutableValidator())
        val first = async { read(barrierFixture.token, 0, ByteArray(3)) }
        runCurrent()
        assertThat(barrierFixture.entry.readInFlight).isTrue()
        assertThat(checkNotNull(barrierStore.capturedDestination).any { it != 0.toByte() }).isTrue()
        barrierStore.proceed.complete(Unit)
        assertThat(first.await()).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(3))
        assertThat(barrierFixture.entry.readInFlight).isFalse()
        assertThat(read(barrierFixture.token, 0, ByteArray(3)))
            .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(3))
        assertThat(barrierStore.readCalls.get()).isEqualTo(2)

        val partialFailure = SecureObjectAuthenticationException()
        val failingStore = FailingRangeStore(partialFailure, fillBeforeFailure = true)
        val failingFixture = registryFixture(failingStore, MutableValidator())
        val unchanged = ByteArray(4) { 0x2d }
        assertThat(read(failingFixture.token, 0, unchanged)).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(
                SecureMediaAuthenticatedReadRejection.STORAGE_AUTHENTICATION_FAILED
            )
        )
        assertThat(unchanged).isEqualTo(ByteArray(4) { 0x2d })
        assertThat(checkNotNull(failingStore.capturedDestination).all { it == 0.toByte() }).isTrue()
        assertThat(failingFixture.entry.readInFlight).isFalse()

        val ignoringStore = IgnoreCancellationRangeStore(PLAINTEXT)
        val ignoringFixture = registryFixture(ignoringStore, MutableValidator())
        val cancelledDestination = ByteArray(4) { 0x19 }
        val cancelled = async { read(ignoringFixture.token, 0, cancelledDestination) }
        runCurrent()
        assertThat(ignoringFixture.entry.readInFlight).isTrue()
        cancelled.cancel()
        ignoringStore.proceed.complete(Unit)
        cancelled.cancelAndJoin()
        assertThat(cancelledDestination).isEqualTo(ByteArray(4) { 0x19 })
        assertThat(checkNotNull(ignoringStore.capturedDestination).all { it == 0.toByte() }).isTrue()
        assertThat(ignoringFixture.entry.readInFlight).isFalse()
    }

    @Test
    fun `CA04 delivery linearizes before revoke and every later copy fails`() = runTest {
        val store = ImmediateRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())
        val delivered = ByteArray(4)

        assertThat(read(fixture.token, 0, delivered)).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(4))
        SecureMediaLeaseRegistry.revoke(fixture.token, SecurityException("after delivery"))
        val later = ByteArray(4) { 0x71 }

        assertThat(delivered).isEqualTo(PLAINTEXT.copyOfRange(0, 4))
        assertThat(read(fixture.token, 0, later)).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
        )
        assertThat(later).isEqualTo(ByteArray(4) { 0x71 })
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CA05 preview and authorization generation changes during read never deliver`() = runTest {
        val previewValidator = MutableValidator()
        val previewStore = BarrierRangeStore(PLAINTEXT)
        val previewFixture = registryFixture(previewStore, previewValidator)
        val previewDestination = ByteArray(3) { 0x11 }
        val previewRead = async { read(previewFixture.token, 0, previewDestination) }
        runCurrent()
        previewValidator.rejection = SecureMediaAuthorizationRejection.PREVIEW_DENIED
        previewStore.proceed.complete(Unit)
        assertThat(previewRead.await()).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.PREVIEW_DENIED)
        )
        assertThat(previewDestination).isEqualTo(ByteArray(3) { 0x11 })

        val revisionValidator = MutableValidator()
        val revisionStore = BarrierRangeStore(PLAINTEXT)
        val revisionFixture = registryFixture(revisionStore, revisionValidator)
        val revisionDestination = ByteArray(3) { 0x12 }
        val revisionRead = async { read(revisionFixture.token, 0, revisionDestination) }
        runCurrent()
        revisionValidator.authorization = revisionValidator.authorization.copy(authorizationRevision = 8)
        revisionStore.proceed.complete(Unit)
        assertThat(revisionRead.await()).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH)
        )
        assertThat(revisionDestination).isEqualTo(ByteArray(3) { 0x12 })
    }

    @Test
    fun `CA07 callback is reentrant and Long MAX revision never wraps`() {
        val token = UUID.randomUUID().toString()
        val callbackCalls = AtomicInteger()
        val entry = authorizedEntry(
            token = token,
            store = ImmediateRangeStore(PLAINTEXT),
            validator = MutableValidator(),
            cleanup = {
                callbackCalls.incrementAndGet()
                assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
                SecureMediaLeaseRegistry.revoke(token)
            },
        )
        entry.stateLock.withLock { entry.revision = Long.MAX_VALUE }

        SecureMediaLeaseRegistry.revoke(token, SecurityException("first"))
        SecureMediaLeaseRegistry.revoke(token, SecurityException("duplicate"))

        assertThat(entry.revision).isEqualTo(Long.MAX_VALUE)
        assertThat(entry.revoked).isTrue()
        assertThat(callbackCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CA07A validator runs under entry lock and throwable releases before cleanup`() = runTest {
        val validator = MutableValidator()
        val fixture = registryFixture(ImmediateRangeStore(PLAINTEXT), validator)
        val observedCalls = AtomicInteger()
        val finalFailure = IllegalStateException("final validator failure")
        validator.onValidate = { call ->
            assertThat(fixture.entry.stateLock.isHeldByCurrentThread).isTrue()
            observedCalls.incrementAndGet()
            if (call == 2) throw finalFailure
        }
        val destination = ByteArray(3) { 0x24 }

        val result = read(fixture.token, 0, destination)

        assertThat(result).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(
                SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE
            )
        )
        assertThat(destination).isEqualTo(ByteArray(3) { 0x24 })
        assertThat(observedCalls.get()).isEqualTo(2)
        assertThat(fixture.cleanupCause.get()).isSameInstanceAs(finalFailure)
        assertThat(fixture.entry.stateLock.tryLock()).isTrue()
        fixture.entry.stateLock.unlock()
    }

    @Test
    fun `C29 repeated bounded reads cover two MiB with one source slot`() = runTest {
        val plaintext = ByteArray(2 * 1024 * 1024) { index -> (index % 239).toByte() }
        val store = ImmediateRangeStore(plaintext)
        val fixture = registryFixture(
            store,
            MutableValidator(),
            reference = reference(plaintext.size.toLong()),
        )
        val restored = ByteArray(plaintext.size)
        var offset = 0
        while (offset < restored.size) {
            val length = minOf(64 * 1024, restored.size - offset)
            val target = ByteArray(length)
            assertThat(read(fixture.token, offset.toLong(), target))
                .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(length))
            target.copyInto(restored, offset)
            offset += length
        }

        assertThat(restored).isEqualTo(plaintext)
        assertThat(store.readCalls.get()).isEqualTo(32)
        assertThat(store.capturedDestinations).hasSize(32)
        assertThat(store.capturedDestinations.all { source ->
            source.size <= 64 * 1024 && source.all { it == 0.toByte() }
        }).isTrue()
        assertThat(fixture.entry.readInFlight).isFalse()
    }

    @Test
    fun `C31 logical resource mismatch rejects before storage`() = runTest {
        val store = ImmediateRangeStore(PLAINTEXT)
        val fixture = registryFixture(
            store,
            MutableValidator(),
            logicalResourceDigestSha256Hex = "b".repeat(64),
        )
        val destination = ByteArray(2) { 0x4c }

        assertThat(read(fixture.token, 0, destination)).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RESOURCE_MISMATCH)
        )
        assertThat(destination).isEqualTo(ByteArray(2) { 0x4c })
        assertThat(store.readCalls.get()).isEqualTo(0)
        assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CR purpose and 64 KiB bounds reject before storage`() = runTest {
        val store = ImmediateRangeStore(PLAINTEXT)
        val fixture = registryFixture(store, MutableValidator())

        val wrongPurpose = SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
            fixture.token,
            SecureMediaAuthenticatedReadRequest(0, 1, SecureMediaReadPurpose.LEASE_STREAM),
            ByteArray(1),
            0,
        )
        val oversized = SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
            fixture.token,
            SecureMediaAuthenticatedReadRequest(0, 64 * 1024 + 1, SecureMediaReadPurpose.SEGMENTED_DECODER),
            ByteArray(64 * 1024 + 1),
            0,
        )

        assertThat(wrongPurpose).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.PURPOSE_MISMATCH)
        )
        assertThat(oversized).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RANGE_INVALID)
        )
        assertThat(store.readCalls.get()).isEqualTo(0)
    }

    @Test
    fun `C01 through C07 range purpose zero and short read contract`() = runTest {
        val store = ImmediateRangeStore(PLAINTEXT, returnedCount = 2)
        val fixture = registryFixture(store, MutableValidator())
        val shortDestination = ByteArray(5) { 0x5a }

        assertThat(read(fixture.token, 0, ByteArray(0)))
            .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(0))
        assertThat(store.readCalls.get()).isEqualTo(0)
        assertThat(read(fixture.token, 0, shortDestination))
            .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(2))
        assertThat(shortDestination.copyOfRange(0, 2)).isEqualTo(PLAINTEXT.copyOfRange(0, 2))
        assertThat(shortDestination.copyOfRange(2, 5)).isEqualTo(ByteArray(3) { 0x5a })
        assertThat(store.capturedDestinations.single().all { it == 0.toByte() }).isTrue()

        val callsAfterValidRead = store.readCalls.get()
        listOf(
            SecureMediaAuthenticatedReadRequest(-1, 1, SecureMediaReadPurpose.SEGMENTED_DECODER),
            SecureMediaAuthenticatedReadRequest(PLAINTEXT.size.toLong(), 1, SecureMediaReadPurpose.SEGMENTED_DECODER),
            SecureMediaAuthenticatedReadRequest(Long.MAX_VALUE, 1, SecureMediaReadPurpose.SEGMENTED_DECODER),
        ).forEach { request ->
            assertThat(SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
                fixture.token,
                request,
                ByteArray(1) { 0x22 },
                0,
            )).isEqualTo(SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RANGE_INVALID))
        }
        assertThat(SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
            fixture.token,
            SecureMediaAuthenticatedReadRequest(0, 2, SecureMediaReadPurpose.SEGMENTED_DECODER),
            ByteArray(2),
            1,
        )).isEqualTo(SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RANGE_INVALID))
        assertThat(store.readCalls.get()).isEqualTo(callsAfterValidRead)
    }

    @Test
    fun `CT04 ttl overflow fails before commit or registration`() = runTest {
        val descriptor = descriptor()
        val staging = FixedStaging(
            descriptor,
            reference(descriptor.expectedPlaintextLength, descriptor.digestSha256Hex()),
        )
        val authority = CountingAuthority()
        val issuedTokens = mutableListOf<String>()
        val manager = DefaultSecureMediaLeaseManager(
            context = testContext(),
            appCoroutineScope = this,
            sensitiveFileStore = ImmediateRangeStore(PLAINTEXT),
            authorizationValidator = MutableValidator(elapsedRealtimeMillis = Long.MAX_VALUE - 10),
        ).also { manager ->
            manager.leaseUriFactory = { token ->
                issuedTokens += token
                testUri(token)
            }
        }

        val failure = captureFailure {
            manager.issueAuthorized(
                SecureMediaAuthorizedLeaseRequest(
                    staging = staging,
                    logicalResource = descriptor,
                    releaseAuthority = authority,
                    mimeType = "video/mp4",
                    displayName = null,
                    timeToLive = 5.minutes,
                    allowedReadPurposes = setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
                )
            )
        }

        assertThat(failure).isInstanceOf(ArithmeticException::class.java)
        assertThat(staging.commitCalls.get()).isEqualTo(0)
        assertThat(staging.abortCalls.get()).isEqualTo(1)
        assertThat(issuedTokens).isEmpty()
        assertThat(authority.closeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CT07 through CT10 elapsed boot clock and hostile wall states fail closed`() = runTest {
        val rollbackValidator = MutableValidator(elapsedRealtimeMillis = 99)
        val rollback = registryFixture(
            ImmediateRangeStore(PLAINTEXT),
            rollbackValidator,
            processStart = 100,
        )
        assertThat(read(rollback.token, 0, ByteArray(1))).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
        )

        val bootValidator = MutableValidator(bootCount = 2)
        val boot = registryFixture(ImmediateRangeStore(PLAINTEXT), bootValidator)
        assertThat(read(boot.token, 0, ByteArray(1))).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
        )

        val clockFailure = IllegalStateException("clock unavailable")
        val throwingValidator = MutableValidator(throwable = clockFailure)
        val clock = registryFixture(ImmediateRangeStore(PLAINTEXT), throwingValidator)
        assertThat(read(clock.token, 0, ByteArray(1))).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE)
        )
        assertThat(clock.cleanupCause.get()).isSameInstanceAs(clockFailure)

        val hostileWall = registryFixture(
            ImmediateRangeStore(PLAINTEXT),
            MutableValidator(),
            legacyExpiresAtMillis = 0,
        )
        SecureMediaLeaseRegistry.pruneExpired()
        assertThat(read(hostileWall.token, 0, ByteArray(1)))
            .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(1))
    }

    @Test
    fun `CE01 maps every issuer rejection one to one`() = runTest {
        val expected = mapOf(
            SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE to SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE,
            SecureMediaAuthorizationRejection.OWNER_MISMATCH to SecureMediaAuthenticatedReadRejection.OWNER_MISMATCH,
            SecureMediaAuthorizationRejection.RELEASE_MISMATCH to SecureMediaAuthenticatedReadRejection.RELEASE_MISMATCH,
            SecureMediaAuthorizationRejection.EXPIRED to SecureMediaAuthenticatedReadRejection.EXPIRED,
            SecureMediaAuthorizationRejection.BINDING_INVALID to SecureMediaAuthenticatedReadRejection.BINDING_INVALID,
            SecureMediaAuthorizationRejection.CLOSED to SecureMediaAuthenticatedReadRejection.CLOSED,
            SecureMediaAuthorizationRejection.PREVIEW_DENIED to SecureMediaAuthenticatedReadRejection.PREVIEW_DENIED,
            SecureMediaAuthorizationRejection.REVISION_MISMATCH to SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH,
        )
        expected.forEach { (issuerReason, publicReason) ->
            val fixture = registryFixture(
                ImmediateRangeStore(PLAINTEXT),
                MutableValidator(rejection = issuerReason),
            )
            assertThat(read(fixture.token, 0, ByteArray(1))).isEqualTo(
                SecureMediaAuthenticatedReadResult.Rejected(publicReason)
            )
            assertThat(fixture.cleanupCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `CE03 maps storage subclasses and fallback without copying`() = runTest {
        val cases = listOf(
            SecureObjectNotFoundException() to SecureMediaAuthenticatedReadRejection.STORAGE_NOT_FOUND,
            SecureObjectAuthenticationException() to SecureMediaAuthenticatedReadRejection.STORAGE_AUTHENTICATION_FAILED,
            SecureStorageKeyUnavailableException() to SecureMediaAuthenticatedReadRejection.STORAGE_KEY_UNAVAILABLE,
            SecureVaultDestroyedException() to SecureMediaAuthenticatedReadRejection.VAULT_DESTROYED,
            SecureStorageException("generic") to SecureMediaAuthenticatedReadRejection.STORAGE_UNAVAILABLE,
        )
        cases.forEach { (failure, expected) ->
            val store = FailingRangeStore(failure)
            val fixture = registryFixture(store, MutableValidator())
            val destination = ByteArray(4) { 0x6b }
            assertThat(read(fixture.token, 0, destination)).isEqualTo(
                SecureMediaAuthenticatedReadResult.Rejected(expected)
            )
            assertThat(destination).isEqualTo(ByteArray(4) { 0x6b })
            assertThat(checkNotNull(store.capturedDestination).all { it == 0.toByte() }).isTrue()
            assertThat(fixture.entry.readInFlight).isFalse()
        }
    }

    @Test
    fun `CE04 preserves the original cancellation exception`() = runTest {
        val cancellation = CancellationException("store cancellation")
        val fixture = registryFixture(FailingRangeStore(cancellation), MutableValidator())

        val failure = captureFailure { read(fixture.token, 0, ByteArray(2)) }

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(fixture.entry.readInFlight).isFalse()
    }

    @Test
    fun `CA cleanup is claimed once and retains primary cause receipt`() = runTest {
        val descriptor = descriptor()
        val reference = reference(
            length = descriptor.expectedPlaintextLength,
            associatedDataSha256 = descriptor.digestSha256Hex(),
        )
        val authority = CountingAuthority()
        val store = DeleteFailureStore(reference)
        val manager = DefaultSecureMediaLeaseManager(
            context = testContext(),
            appCoroutineScope = CoroutineScope(Dispatchers.Unconfined),
            sensitiveFileStore = store,
            authorizationValidator = MutableValidator(),
        ).also { it.leaseUriFactory = ::testUri }
        val staging = FixedStaging(descriptor, reference)
        val lease = manager.issueAuthorized(
            SecureMediaAuthorizedLeaseRequest(
                staging = staging,
                logicalResource = descriptor,
                releaseAuthority = authority,
                mimeType = "video/mp4",
                displayName = null,
                timeToLive = 5.minutes,
                allowedReadPurposes = setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
            )
        )

        lease.close()
        lease.close()
        manager.revokeScope("content:$CONTENT_ID")
        manager.revokeVault(descriptor.vaultId)

        assertThat(authority.closeCalls.get()).isEqualTo(1)
        assertThat(store.deleteCalls.get()).isEqualTo(1)
        assertThat(manager.cleanupFailures()).hasSize(1)
        assertThat(manager.cleanupFailureExceptions()).hasSize(1)
        assertThat(manager.cleanupFailures().single().items)
            .containsExactly(SecureObjectStagingCleanupItem("revoked-object", false))
        assertThat(manager.cleanupFailureExceptions().single().cause?.message)
            .isEqualTo("Secure media authorized lease was closed.")
        assertThat(manager.cleanupFailureExceptions().single().suppressed).isEmpty()
    }

    @Test
    fun `CA10 thrown delete failure is retained once as suppressed`() = runTest {
        val descriptor = descriptor()
        val reference = reference(descriptor.expectedPlaintextLength, descriptor.digestSha256Hex())
        val deletionFailure = IllegalStateException("delete failed")
        val authority = CountingAuthority()
        val store = DeleteFailureStore(reference, deletionFailure)
        val manager = DefaultSecureMediaLeaseManager(
            context = testContext(),
            appCoroutineScope = CoroutineScope(Dispatchers.Unconfined),
            sensitiveFileStore = store,
            authorizationValidator = MutableValidator(),
        ).also { it.leaseUriFactory = ::testUri }
        val lease = manager.issueAuthorized(
            SecureMediaAuthorizedLeaseRequest(
                staging = FixedStaging(descriptor, reference),
                logicalResource = descriptor,
                releaseAuthority = authority,
                mimeType = "video/mp4",
                displayName = null,
                timeToLive = 5.minutes,
                allowedReadPurposes = setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
            )
        )

        lease.close()

        val cleanup = manager.cleanupFailureExceptions().single()
        assertThat(cleanup.cause?.message).isEqualTo("Secure media authorized lease was closed.")
        assertThat(cleanup.suppressed.toList()).containsExactly(deletionFailure)
        assertThat(authority.closeCalls.get()).isEqualTo(1)
        assertThat(store.deleteCalls.get()).isEqualTo(1)
    }

    @Test
    fun `CA real AEAD store authenticates cross chunk range into C owned source`() = runTest {
        val roots = object : SecureStorageRoots {
            override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
        }
        val store = DefaultSensitiveFileStore(roots, TestEnvelopeService())
        val plaintext = ByteArray(SECURE_FILE_CHUNK_SIZE + 128) { index -> (index % 251).toByte() }
        val writeRequest = SecureObjectWriteRequest(
            objectId = SecureObjectId("aead-object"),
            vaultId = AccountVaultId("aead-vault"),
            purpose = SecureObjectPurpose.VIDEO,
            associatedData = "authenticated-range-aad".encodeToByteArray(),
            expectedPlaintextLength = plaintext.size.toLong(),
        )
        val reference = store.write(writeRequest) { it.write(plaintext) }
        val fixture = registryFixture(store, MutableValidator(), reference = reference)
        val offset = SECURE_FILE_CHUNK_SIZE - 31L
        val destination = ByteArray(97)

        val result = read(fixture.token, offset, destination)

        assertThat(result).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(destination.size))
        assertThat(destination).isEqualTo(
            plaintext.copyOfRange(offset.toInt(), offset.toInt() + destination.size)
        )
        SecureMediaLeaseRegistry.revoke(fixture.token)

        val encryptedData = roots.persistentRoot().walkTopDown()
            .first { it.isFile && it.name.endsWith(".yzsf") }
        val encryptedBytes = encryptedData.readBytes()
        encryptedBytes[encryptedBytes.lastIndex] = (encryptedBytes.last().toInt() xor 1).toByte()
        encryptedData.writeBytes(encryptedBytes)
        val capturingStore = CapturingDelegatingStore(store)
        val tampered = registryFixture(capturingStore, MutableValidator(), reference = reference)
        val tamperedDestination = ByteArray(32) { 0x7d }

        assertThat(read(tampered.token, 0, tamperedDestination)).isEqualTo(
            SecureMediaAuthenticatedReadResult.Rejected(
                SecureMediaAuthenticatedReadRejection.STORAGE_AUTHENTICATION_FAILED
            )
        )
        assertThat(tamperedDestination).isEqualTo(ByteArray(32) { 0x7d })
        assertThat(checkNotNull(capturingStore.capturedDestination).all { it == 0.toByte() }).isTrue()
    }

    private suspend fun read(token: String, offset: Long, destination: ByteArray) =
        SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
            token = token,
            request = SecureMediaAuthenticatedReadRequest(
                offset = offset,
                byteCount = destination.size,
                purpose = SecureMediaReadPurpose.SEGMENTED_DECODER,
            ),
            destination = destination,
            destinationOffset = 0,
        )

    private fun registryFixture(
        store: SensitiveFileStore,
        validator: MutableValidator,
        processDeadline: Long = 10_000,
        processStart: Long = 0,
        legacyExpiresAtMillis: Long = Long.MAX_VALUE,
        reference: SecureObjectReference = reference(PLAINTEXT.size.toLong()),
        logicalResourceDigestSha256Hex: String = reference.associatedDataSha256,
    ): RegistryFixture {
        val token = UUID.randomUUID().toString()
        val cleanupCalls = AtomicInteger()
        val cleanupCause = AtomicReference<Throwable>()
        val entry = authorizedEntry(
            token = token,
            store = store,
            validator = validator,
            reference = reference,
            processDeadline = processDeadline,
            processStart = processStart,
            legacyExpiresAtMillis = legacyExpiresAtMillis,
            logicalResourceDigestSha256Hex = logicalResourceDigestSha256Hex,
            cleanup = { cause ->
                cleanupCause.set(cause)
                cleanupCalls.incrementAndGet()
            },
        )
        return RegistryFixture(token, cleanupCalls, cleanupCause, entry)
    }

    private fun authorizedEntry(
        token: String,
        store: SensitiveFileStore,
        validator: MutableValidator,
        reference: SecureObjectReference = reference(PLAINTEXT.size.toLong()),
        processDeadline: Long = 10_000,
        processStart: Long = 0,
        legacyExpiresAtMillis: Long = Long.MAX_VALUE,
        logicalResourceDigestSha256Hex: String = reference.associatedDataSha256,
        cleanup: (Throwable) -> Unit,
    ): SecureMediaLeaseRegistry.Entry {
        val entry = SecureMediaLeaseRegistry.Entry(
            reference = reference,
            mimeType = "video/mp4",
            displayName = null,
            expiresAtMillis = legacyExpiresAtMillis,
            requireSeekableDescriptor = false,
            sensitiveFileStore = store,
            onRevoked = {},
            authorizedState = SecureMediaLeaseRegistry.AuthorizedState(
                authority = CountingAuthority(),
                validator = validator,
                expectedContentId = CONTENT_ID,
                expectedImmutableContextSha256Hex = CONTEXT_SHA256,
                authorization = validator.authorization,
                bootCount = 1,
                processTtlStartElapsedRealtimeMillis = processStart,
                processTtlDeadlineElapsedRealtimeMillis = processDeadline,
                logicalResourceDigestSha256Hex = logicalResourceDigestSha256Hex,
                allowedPurpose = SecureMediaReadPurpose.SEGMENTED_DECODER,
            ),
            onRevokedWithCause = cleanup,
        )
        SecureMediaLeaseRegistry.register(token, entry)
        return entry
    }

    private fun descriptor() = SecureMediaLogicalResourceDescriptor(
        assetId = "asset",
        mediaVersion = 1,
        roomIdSha256Hex = "1".repeat(64),
        contentContractId = "contract",
        resourceId = "resource",
        contentId = CONTENT_ID,
        kind = "VIDEO",
        index = 0,
        expectedPlaintextLength = PLAINTEXT.size.toLong(),
        immutableContextSha256Hex = CONTEXT_SHA256,
        matrixLocatorSha256Hex = "3".repeat(64),
        outerFrameSha256Hex = "4".repeat(64),
        outerFrameLength = 100,
        vaultId = AccountVaultId("vault"),
        objectId = SecureObjectId("object"),
        purpose = SecureObjectPurpose.VIDEO,
    )

    private class FixedStaging(
        descriptor: SecureMediaLogicalResourceDescriptor,
        private val reference: SecureObjectReference,
    ) : SecureObjectStagingTransaction {
        val commitCalls = AtomicInteger()
        val abortCalls = AtomicInteger()
        override val transactionId: String = "slice-c-cleanup"
        override val request = SecureObjectWriteRequest(
            objectId = descriptor.objectId,
            vaultId = descriptor.vaultId,
            purpose = descriptor.purpose,
            associatedData = descriptor.canonicalBytes(),
            expectedPlaintextLength = descriptor.expectedPlaintextLength,
        )
        override val plaintextLength: Long = descriptor.expectedPlaintextLength
        override suspend fun commit(): SecureObjectReference {
            commitCalls.incrementAndGet()
            return reference
        }
        override suspend fun abort(): SecureObjectStagingCleanupReport {
            abortCalls.incrementAndGet()
            return SecureObjectStagingCleanupReport(
                transactionId,
                listOf(SecureObjectStagingCleanupItem("encrypted-staging", true)),
            )
        }
    }

    private class MutableValidator(
        var elapsedRealtimeMillis: Long = 100,
        var bootCount: Long = 1,
        var rejection: SecureMediaAuthorizationRejection? = null,
        var throwable: Throwable? = null,
        var onValidate: (Int) -> Unit = {},
        private val rejectOnCall: Int? = null,
    ) : SecureMediaAuthorizationValidator {
        val calls = AtomicInteger()
        var authorization = SecureMediaVerifiedAuthorization(
            authorityId = "authority",
            contentId = CONTENT_ID,
            immutableContextSha256Hex = CONTEXT_SHA256,
            authorizationDeadlineEpochMillis = Long.MAX_VALUE,
            authorizationRevision = 7,
            revocationScope = "content:$CONTENT_ID",
        )

        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation {
            val call = calls.incrementAndGet()
            onValidate(call)
            throwable?.let { throw it }
            rejection?.let { return SecureMediaAuthorizationValidation.Rejected(it) }
            if (call == rejectOnCall) {
                return SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.EXPIRED)
            }
            return SecureMediaAuthorizationValidation.Verified(
                authorization = authorization,
                time = SecureMediaValidationTime(bootCount = bootCount, elapsedRealtimeMillis = elapsedRealtimeMillis),
            )
        }
    }

    private open class ImmediateRangeStore(
        private val plaintext: ByteArray,
        private val returnedCount: Int? = null,
    ) : SensitiveFileStore {
        val readCalls = AtomicInteger()
        val capturedDestinations = mutableListOf<ByteArray>()

        override suspend fun readRangeInto(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
            destination: ByteArray,
            destinationOffset: Int,
        ): Int {
            readCalls.incrementAndGet()
            capturedDestinations += destination
            val copied = returnedCount ?: byteCount
            plaintext.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + copied)
            return copied
        }

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = unsupported()
        override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T = unsupported()
        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray = unsupported()
        override suspend fun delete(reference: SecureObjectReference): Boolean = true
        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = true
        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = unsupported()
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = unsupported()

        protected fun <T> unsupported(): T = error("Not used by this test.")
    }

    private class BarrierRangeStore(private val plaintext: ByteArray) : SensitiveFileStore {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val readCalls = AtomicInteger()
        var capturedDestination: ByteArray? = null

        override suspend fun readRangeInto(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
            destination: ByteArray,
            destinationOffset: Int,
        ): Int {
            readCalls.incrementAndGet()
            capturedDestination = destination
            plaintext.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + byteCount)
            entered.complete(Unit)
            proceed.await()
            return byteCount
        }

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = unsupported()
        override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T = unsupported()
        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray = unsupported()
        override suspend fun delete(reference: SecureObjectReference): Boolean = true
        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = true
        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = unsupported()
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = unsupported()
        private fun <T> unsupported(): T = error("Not used by this test.")
    }

    private class IgnoreCancellationRangeStore(private val plaintext: ByteArray) : SensitiveFileStore {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var capturedDestination: ByteArray? = null

        override suspend fun readRangeInto(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
            destination: ByteArray,
            destinationOffset: Int,
        ): Int = withContext(NonCancellable) {
            capturedDestination = destination
            entered.complete(Unit)
            proceed.await()
            plaintext.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + byteCount)
            byteCount
        }

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = unsupported()
        override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T = unsupported()
        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray = unsupported()
        override suspend fun delete(reference: SecureObjectReference): Boolean = true
        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = true
        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = unsupported()
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = unsupported()
        private fun <T> unsupported(): T = error("Not used by this test.")
    }

    private class FailingRangeStore(
        private val failure: Throwable,
        private val fillBeforeFailure: Boolean = false,
    ) : SensitiveFileStore {
        var capturedDestination: ByteArray? = null

        override suspend fun readRangeInto(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
            destination: ByteArray,
            destinationOffset: Int,
        ): Int {
            capturedDestination = destination
            if (fillBeforeFailure) destination.fill(0x6e, destinationOffset, destinationOffset + byteCount)
            throw failure
        }

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = unsupported()
        override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T = unsupported()
        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray = unsupported()
        override suspend fun delete(reference: SecureObjectReference): Boolean = true
        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = true
        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = unsupported()
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = unsupported()
        private fun <T> unsupported(): T = error("Not used by this test.")
    }

    private class CapturingDelegatingStore(
        private val delegate: SensitiveFileStore,
    ) : SensitiveFileStore by delegate {
        var capturedDestination: ByteArray? = null

        override suspend fun readRangeInto(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
            destination: ByteArray,
            destinationOffset: Int,
        ): Int {
            capturedDestination = destination
            return delegate.readRangeInto(reference, offset, byteCount, destination, destinationOffset)
        }
    }

    private class DeleteFailureStore(
        @Suppress("unused") private val reference: SecureObjectReference,
        private val deletionFailure: Throwable? = null,
    ) : SensitiveFileStore {
        val deleteCalls = AtomicInteger()
        override suspend fun delete(reference: SecureObjectReference): Boolean {
            deleteCalls.incrementAndGet()
            deletionFailure?.let { throw it }
            return false
        }
        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = delete(reference)
        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = unsupported()
        override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T = unsupported()
        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray = unsupported()
        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = unsupported()
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = unsupported()
        private fun <T> unsupported(): T = error("Not used by this test.")
    }

    private class CountingAuthority : SecureMediaReleaseAuthority {
        val closeCalls = AtomicInteger()
        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class TestEnvelopeService : ObjectKeyEnvelopeService {
        private val keys = mutableMapOf<AccountVaultId, SecretKey>()

        override suspend fun wrap(vaultId: AccountVaultId, objectKey: SecretKey, associatedData: ByteArray): WrappedObjectKey {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, vaultKey(vaultId))
                updateAAD(associatedData)
            }
            return WrappedObjectKey(cipher.iv, cipher.doFinal(objectKey.encoded))
        }

        override suspend fun unwrap(
            vaultId: AccountVaultId,
            wrappedObjectKey: WrappedObjectKey,
            associatedData: ByteArray,
        ): SecretKey {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, checkNotNull(keys[vaultId]), GCMParameterSpec(128, wrappedObjectKey.initializationVector))
                updateAAD(associatedData)
            }
            return SecretKeySpec(cipher.doFinal(wrappedObjectKey.ciphertext), "AES")
        }

        override suspend fun destroyVaultKey(vaultId: AccountVaultId): Boolean = keys.remove(vaultId) != null
        override suspend fun retireLegacyVaultKey(vaultId: AccountVaultId): Boolean = true

        private fun vaultKey(vaultId: AccountVaultId): SecretKey = keys.getOrPut(vaultId) {
            KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }
        }
    }

    private fun testContext(): Context = mockk<Context>().also {
        every { it.packageName } returns "io.element.android.test"
    }

    private fun testUri(token: String): Uri = mockk<Uri>().also {
        every { it.lastPathSegment } returns token
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected failure")
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        val PLAINTEXT = "authenticated protected media".encodeToByteArray()
        const val CONTENT_ID = "content-123"
        val CONTEXT_SHA256 = "2".repeat(64)

        fun reference(
            length: Long,
            associatedDataSha256: String = "a".repeat(64),
        ) = SecureObjectReference(
            objectId = SecureObjectId("object"),
            vaultId = AccountVaultId("vault"),
            purpose = SecureObjectPurpose.VIDEO,
            keyEnvelopeId = KeyEnvelopeId("key"),
            formatVersion = 1,
            plaintextLength = length,
            ciphertextLength = length + 64,
            associatedDataSha256 = associatedDataSha256,
        )
    }

    private data class RegistryFixture(
        val token: String,
        val cleanupCalls: AtomicInteger,
        val cleanupCause: AtomicReference<Throwable>,
        val entry: SecureMediaLeaseRegistry.Entry,
    )
}
