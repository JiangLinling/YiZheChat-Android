/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationDecisionPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaElapsedClockPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaLeaseAuthorizationValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaWallClockPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.aFixtureManifest
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.impl.DefaultSecureMediaLeaseManager
import io.element.android.security.securestorage.impl.DefaultSecureObjectStagingStore
import io.element.android.security.securestorage.impl.DefaultSensitiveFileStore
import io.element.android.security.securestorage.impl.ObjectKeyEnvelopeService
import io.element.android.security.securestorage.impl.WrappedObjectKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class LocalAuthenticatedSegmentBridgeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real encrypted store and C prepare exact segmented decoder leases`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val fixture = RealAuthorizedResources(temporaryFolder.root)
        try {
            val decoder = RecordingDecoderLifecycle()

            val session = bridge(worker, finalizer).prepare(
                validatedManifest = validatedFixtureManifest(),
                segmentIndex = 0,
                init = fixture.issue(
                    TEST_MANIFEST.init,
                    SegmentedMediaFmp4FixturePrototype.init,
                    setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
                ),
                segment = fixture.issue(
                    TEST_MANIFEST.segments.single(),
                    SegmentedMediaFmp4FixturePrototype.segment,
                    setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
                ),
                readContext = TEST_READ_CONTEXT,
                authorizationGate = alwaysAuthorizedGate(),
                decoderLifecycle = decoder,
            )

            assertThat(session.state()).isEqualTo(SegmentedPlaybackSessionState.Open(TEST_READ_CONTEXT.generation))
            val receipt = session.closeAndAwaitQuiescence()
            assertThat(receipt.succeeded).isTrue()
            assertThat(decoder.beginStopCalls.get()).isEqualTo(1)
            assertThat(decoder.flushCalls.get()).isEqualTo(1)
        } finally {
            fixture.close()
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `normal default lease stream purpose cannot prepare segmented decoder`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val fixture = RealAuthorizedResources(temporaryFolder.root)
        try {
            val failure = captureFailure {
                bridge(worker, finalizer).prepare(
                    validatedManifest = validatedFixtureManifest(),
                    segmentIndex = 0,
                    init = fixture.issue(
                        TEST_MANIFEST.init,
                        SegmentedMediaFmp4FixturePrototype.init,
                        null,
                    ),
                    segment = fixture.issue(
                        TEST_MANIFEST.segments.single(),
                        SegmentedMediaFmp4FixturePrototype.segment,
                        null,
                    ),
                    readContext = TEST_READ_CONTEXT,
                    authorizationGate = alwaysAuthorizedGate(),
                    decoderLifecycle = RecordingDecoderLifecycle(),
                )
            }

            assertThat(failure).isInstanceOf(SegmentedBridgeException::class.java)
            assertThat((failure as SegmentedBridgeException).reason)
                .isEqualTo(SegmentedBridgeFailureReason.AUTHORIZED_READ_REJECTED)
            assertThat(failure.message).contains("PURPOSE_MISMATCH")
        } finally {
            fixture.close()
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `prepare failure closes both acquired leases exactly once`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val initLease = FaultInjectingAuthorizedLease(IOException("init read failed"))
        val segmentLease = FaultInjectingAuthorizedLease(null)
        try {
            val failure = captureFailure {
                bridge(worker, finalizer).prepare(
                    validatedManifest = validatedFixtureManifest(),
                    segmentIndex = 0,
                    init = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, initLease),
                    segment = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.segments.single(), segmentLease),
                    readContext = TEST_READ_CONTEXT,
                    authorizationGate = alwaysAuthorizedGate(),
                    decoderLifecycle = RecordingDecoderLifecycle(),
                )
            }

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(initLease.closeCalls.get()).isEqualTo(1)
            assertThat(segmentLease.closeCalls.get()).isEqualTo(1)
        } finally {
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `prepare failure preserves primary failure and suppresses both lease cleanup failures`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val primaryFailure = IOException("init read failed")
        val initCloseFailure = IllegalStateException("init close failed")
        val segmentCloseFailure = IllegalStateException("segment close failed")
        val initLease = FaultInjectingAuthorizedLease(primaryFailure, initCloseFailure)
        val segmentLease = FaultInjectingAuthorizedLease(null, segmentCloseFailure)
        try {
            val failure = captureFailure {
                bridge(worker, finalizer).prepare(
                    validatedManifest = validatedFixtureManifest(),
                    segmentIndex = 0,
                    init = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, initLease),
                    segment = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.segments.single(), segmentLease),
                    readContext = TEST_READ_CONTEXT,
                    authorizationGate = alwaysAuthorizedGate(),
                    decoderLifecycle = RecordingDecoderLifecycle(),
                )
            }

            assertThat(failure).isSameInstanceAs(primaryFailure)
            assertThat(failure.suppressed.asList()).containsExactly(initCloseFailure, segmentCloseFailure).inOrder()
            assertThat(initLease.closeCalls.get()).isEqualTo(1)
            assertThat(segmentLease.closeCalls.get()).isEqualTo(1)
        } finally {
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `prepare failure suppresses a single lease cleanup failure and still closes the other lease`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val primaryFailure = IOException("init read failed")
        val initCloseFailure = IllegalStateException("init close failed")
        val initLease = FaultInjectingAuthorizedLease(primaryFailure, initCloseFailure)
        val segmentLease = FaultInjectingAuthorizedLease(null)
        try {
            val failure = captureFailure {
                bridge(worker, finalizer).prepare(
                    validatedManifest = validatedFixtureManifest(),
                    segmentIndex = 0,
                    init = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, initLease),
                    segment = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.segments.single(), segmentLease),
                    readContext = TEST_READ_CONTEXT,
                    authorizationGate = alwaysAuthorizedGate(),
                    decoderLifecycle = RecordingDecoderLifecycle(),
                )
            }

            assertThat(failure).isSameInstanceAs(primaryFailure)
            assertThat(failure.suppressed.asList()).containsExactly(initCloseFailure)
            assertThat(initLease.closeCalls.get()).isEqualTo(1)
            assertThat(segmentLease.closeCalls.get()).isEqualTo(1)
        } finally {
            worker.close()
            finalizer.close()
        }
    }

    private class FaultInjectingAuthorizedLease(
        private val readFailure: Throwable?,
        private val closeFailure: Throwable? = null,
    ) : AuthorizedSecureMediaLease {
        val closeCalls = AtomicInteger()
        override val uri: Uri = Uri.EMPTY

        override fun openInputStream(): InputStream = error("decoder stream is forbidden")

        override suspend fun copyAuthenticatedRangeInto(
            request: SecureMediaAuthenticatedReadRequest,
            destination: ByteArray,
            destinationOffset: Int,
        ): SecureMediaAuthenticatedReadResult {
            readFailure?.let { throw it }
            return SecureMediaAuthenticatedReadResult.Rejected(
                io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection.CLOSED
            )
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }

    private class RealAuthorizedResources(
        root: File,
    ) {
        private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val roots = object : SecureStorageRoots {
            private val persistent = File(root, "persistent").apply { mkdirs() }
            private val runtime = File(root, "runtime").apply { mkdirs() }
            override fun persistentRoot(): File = persistent
            override fun runtimeRoot(): File = runtime
        }
        private val store = DefaultSensitiveFileStore(roots, TestEnvelopeService())
        private val stagingStore = DefaultSecureObjectStagingStore(roots, store)
        private val manager = DefaultSecureMediaLeaseManager(
            context = ApplicationProvider.getApplicationContext<Context>(),
            appCoroutineScope = managerScope,
            sensitiveFileStore = store,
            authorizationValidator = TestAuthorizationValidator,
            stagingStore = stagingStore,
        )

        suspend fun issue(
            resource: SegmentedMediaResourcePrototype,
            plaintext: ByteArray,
            purposes: Set<SecureMediaReadPurpose>?,
        ): SegmentedAuthorizedResourceHandle {
            val objectId = SecureObjectId("d1-${resource.resourceId}-${UUID.randomUUID()}")
            val logical = secureDescriptor(resource, objectId)
            val staging = stagingStore.stage(
                SecureObjectWriteRequest(
                    objectId = objectId,
                    vaultId = TEST_VAULT,
                    purpose = SecureObjectPurpose.VIDEO,
                    associatedData = logical.canonicalBytes(),
                    expectedPlaintextLength = plaintext.size.toLong(),
                )
            ) { output -> output.write(plaintext) }
            val request = SecureMediaAuthorizedLeaseRequest(
                staging = staging,
                logicalResource = logical,
                releaseAuthority = TestReleaseAuthority(),
                mimeType = "video/mp4",
                displayName = null,
                timeToLive = 5.minutes,
                deleteObjectOnRevoke = false,
                allowedReadPurposes = purposes ?: setOf(SecureMediaReadPurpose.LEASE_STREAM),
            )
            return SegmentedAuthorizedResourceHandle(resource, manager.issueAuthorized(request))
        }

        fun close() {
            managerScope.cancel()
        }
    }

    private object TestAuthorizationValidator : SecureMediaAuthorizationValidator {
        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation {
            if (authority !is TestReleaseAuthority || expectedAuthorizationRevision?.let { it != 7L } == true) {
                return SecureMediaAuthorizationValidation.Rejected(
                    io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE
                )
            }
            return SecureMediaAuthorizationValidation.Verified(
                authorization = SecureMediaVerifiedAuthorization(
                    authorityId = authority.id,
                    contentId = expectedContentId,
                    immutableContextSha256Hex = expectedImmutableContextSha256Hex,
                    authorizationDeadlineEpochMillis = 1_800_000_000_000L,
                    authorizationRevision = 7L,
                    revocationScope = null,
                ),
                time = SecureMediaValidationTime(bootCount = 1L, elapsedRealtimeMillis = 100L),
            )
        }
    }

    private class TestReleaseAuthority : SecureMediaReleaseAuthority {
        val id = UUID.randomUUID().toString()
        override fun close() = Unit
    }

    private class TestEnvelopeService : ObjectKeyEnvelopeService {
        private val keys = mutableMapOf<AccountVaultId, SecretKey>()

        override suspend fun wrap(
            vaultId: AccountVaultId,
            objectKey: SecretKey,
            associatedData: ByteArray,
        ): WrappedObjectKey {
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
                init(
                    Cipher.DECRYPT_MODE,
                    checkNotNull(keys[vaultId]),
                    GCMParameterSpec(128, wrappedObjectKey.initializationVector),
                )
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
}

internal class RecordingDecoderLifecycle(
    private val flushFailure: Throwable? = null,
) : SegmentedDecoderLifecycle {
    val beginStopCalls = AtomicInteger()
    val flushCalls = AtomicInteger()

    override fun beginStop() {
        beginStopCalls.incrementAndGet()
    }

    override fun flushAndRelease() {
        flushCalls.incrementAndGet()
        flushFailure?.let { throw it }
    }
}

internal open class ImmediateAuthorizedLease(
    protected val plaintext: ByteArray,
) : AuthorizedSecureMediaLease {
    override val uri: Uri = Uri.parse("content://d1-test/${UUID.randomUUID()}")
    val closeCalls = AtomicInteger()

    override fun openInputStream(): InputStream = ByteArrayInputStream(plaintext)

    override open suspend fun copyAuthenticatedRangeInto(
        request: SecureMediaAuthenticatedReadRequest,
        destination: ByteArray,
        destinationOffset: Int,
    ): SecureMediaAuthenticatedReadResult {
        if (request.purpose != SecureMediaReadPurpose.SEGMENTED_DECODER) {
            return SecureMediaAuthenticatedReadResult.Rejected(
                io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection.PURPOSE_MISMATCH
            )
        }
        if (request.offset < 0L || request.byteCount < 0 || request.offset + request.byteCount > plaintext.size) {
            return SecureMediaAuthenticatedReadResult.Rejected(
                io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection.RANGE_INVALID
            )
        }
        plaintext.copyInto(
            destination,
            destinationOffset,
            request.offset.toInt(),
            request.offset.toInt() + request.byteCount,
        )
        return SecureMediaAuthenticatedReadResult.Copied(request.byteCount)
    }

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

internal suspend fun preparedFakeSession(
    workerDispatcher: CoroutineDispatcher,
    finalizerDispatcher: CoroutineDispatcher,
    decoderLifecycle: SegmentedDecoderLifecycle,
    initLease: ImmediateAuthorizedLease = ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
    segmentLease: ImmediateAuthorizedLease = ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
): SegmentedPlaybackSession = bridge(workerDispatcher, finalizerDispatcher).prepare(
    validatedManifest = validatedFixtureManifest(),
    segmentIndex = 0,
    init = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, initLease),
    segment = SegmentedAuthorizedResourceHandle(TEST_MANIFEST.segments.single(), segmentLease),
    readContext = TEST_READ_CONTEXT,
    authorizationGate = alwaysAuthorizedGate(),
    decoderLifecycle = decoderLifecycle,
)

internal fun bridge(
    workerDispatcher: CoroutineDispatcher,
    finalizerDispatcher: CoroutineDispatcher,
) = LocalAuthenticatedSegmentBridge(workerDispatcher, finalizerDispatcher)

internal val TEST_MANIFEST = aFixtureManifest()
internal val TEST_READ_CONTEXT = SegmentedMediaReadContextPrototype("d1-owner", "d1-lease", 1L)
private val TEST_VAULT = AccountVaultId("d1-vault")

internal fun validatedFixtureManifest() = SegmentedMediaManifestValidatorPrototype.validate(TEST_MANIFEST)

internal fun alwaysAuthorizedGate() = SegmentedMediaAuthorizationGatePrototype(
    validator = SegmentedMediaLeaseAuthorizationValidatorPrototype { _, _, _ ->
        SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
    },
    wallClock = SegmentedMediaWallClockPrototype { 1_000L },
    elapsedClock = SegmentedMediaElapsedClockPrototype { 1_000L },
)

private fun secureDescriptor(
    resource: SegmentedMediaResourcePrototype,
    objectId: SecureObjectId,
) = SecureMediaLogicalResourceDescriptor(
    assetId = TEST_MANIFEST.assetId,
    mediaVersion = TEST_MANIFEST.mediaVersion.toLong(),
    roomIdSha256Hex = TEST_MANIFEST.roomIdHash,
    contentContractId = TEST_MANIFEST.contentContractId,
    resourceId = resource.resourceId,
    contentId = resource.contentId,
    kind = resource.kind.name,
    index = resource.index,
    expectedPlaintextLength = resource.plainLength,
    immutableContextSha256Hex = resource.innerContextDigestSha256,
    matrixLocatorSha256Hex = sha256Hex(resource.locator.mxcUri.encodeToByteArray()),
    outerFrameSha256Hex = resource.locator.outerCipherSha256,
    outerFrameLength = resource.locator.cipherLength,
    vaultId = TEST_VAULT,
    objectId = objectId,
    purpose = SecureObjectPurpose.VIDEO,
)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
    block()
    error("Expected failure")
} catch (failure: Throwable) {
    failure
}
