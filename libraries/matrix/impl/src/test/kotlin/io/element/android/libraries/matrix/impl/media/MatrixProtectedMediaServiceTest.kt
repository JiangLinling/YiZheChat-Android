/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.contentprotection.api.BurnAfterReadSession
import io.element.android.security.contentprotection.api.BurnAfterReadWindowMode
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.test.FakeContentProtectionService
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MatrixProtectedMediaServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matrix media frame preserves protected header and streams payload back through content protection`() = runTest {
        val plaintext = ByteArray(150_000) { (it % 251).toByte() }
        val source = temporaryFolder.newFile("voice.ogg").apply { writeBytes(plaintext) }
        val codec = RetainingEnvelopeCodec()
        val service = DefaultMatrixProtectedMediaService(
            contentProtectionService = FakeContentProtectionService(),
            envelopeCodec = codec,
            deviceTrustService = FakeDeviceTrustService(),
            burnAfterReadSessionRegistry = BurnAfterReadMediaSessionRegistry(NoOpLeaseManager),
        )
        val request = request()

        val protected = service.protect(source, request)
        val frame = protected.file.readBytes()
        val decrypted = ByteArrayOutputStream()
        val result = service.decrypt(frame, request.vaultId, request.accountIdHash, decrypted)

        assertThat(service.isProtected(frame)).isTrue()
        assertThat(protected.contentId).isEqualTo(request.contentId)
        assertThat(decrypted.toByteArray()).isEqualTo(plaintext)
        assertThat(result.revocationScope).isEqualTo("content:${request.contentId.value}")
        assertThat(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".payload") }).isTrue()
    }

    @Test
    fun `authorized in-memory media is re-encrypted without a plaintext staging file`() = runTest {
        val plaintext = "forward-without-plaintext-file".repeat(1_000).encodeToByteArray()
        val service = DefaultMatrixProtectedMediaService(
            contentProtectionService = FakeContentProtectionService(),
            envelopeCodec = RetainingEnvelopeCodec(),
            deviceTrustService = FakeDeviceTrustService(),
            burnAfterReadSessionRegistry = BurnAfterReadMediaSessionRegistry(NoOpLeaseManager),
        )

        val protected = service.protect(plaintext, temporaryFolder.root, request())

        assertThat(temporaryFolder.root.listFiles().orEmpty().toList()).containsExactly(protected.file)
        assertThat(protected.file.name).endsWith(".yzm")
    }

    @Test
    fun `authorized voice stream creates only encrypted transport artifacts`() = runTest {
        val plaintext = ByteArray(96_000) { (it % 239).toByte() }
        val service = DefaultMatrixProtectedMediaService(
            contentProtectionService = FakeContentProtectionService(),
            envelopeCodec = RetainingEnvelopeCodec(),
            deviceTrustService = FakeDeviceTrustService(),
            burnAfterReadSessionRegistry = BurnAfterReadMediaSessionRegistry(NoOpLeaseManager),
        )

        val protected = ByteArrayInputStream(plaintext).use { source ->
            service.protect(source, temporaryFolder.root, request())
        }

        assertThat(temporaryFolder.root.listFiles().orEmpty().toList()).containsExactly(protected.file)
        assertThat(protected.file.name).endsWith(".yzm")
        assertThat(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".payload") }).isTrue()
        val decrypted = ByteArrayOutputStream()
        service.decrypt(protected.file.readBytes(), request().vaultId, request().accountIdHash, decrypted)
        assertThat(decrypted.toByteArray()).isEqualTo(plaintext)
    }

    @Test
    fun `truncated matrix media frame is rejected`() = runTest {
        val source = temporaryFolder.newFile("image.jpg").apply { writeBytes(ByteArray(32) { 7 }) }
        val service = DefaultMatrixProtectedMediaService(
            contentProtectionService = FakeContentProtectionService(),
            envelopeCodec = RetainingEnvelopeCodec(),
            deviceTrustService = FakeDeviceTrustService(),
            burnAfterReadSessionRegistry = BurnAfterReadMediaSessionRegistry(NoOpLeaseManager),
        )
        val protected = service.protect(source, request())
        val truncated = protected.file.readBytes().copyOf(12)

        val failure = runCatchingExceptions {
            service.decrypt(truncated, AccountVaultId("vault-alice"), "a".repeat(64), ByteArrayOutputStream())
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun `burn-after-read media requires active process session and returns its revocation scope`() = runTest {
        val source = temporaryFolder.newFile("one-time.jpg").apply { writeBytes(ByteArray(64) { 9 }) }
        val registry = BurnAfterReadMediaSessionRegistry(NoOpLeaseManager)
        val service = DefaultMatrixProtectedMediaService(
            contentProtectionService = FakeContentProtectionService(),
            envelopeCodec = RetainingEnvelopeCodec(),
            deviceTrustService = FakeDeviceTrustService(),
            burnAfterReadSessionRegistry = registry,
        )
        val request = request().copy(
            lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
        )
        val protected = service.protect(source, request)
        val frame = protected.file.readBytes()

        val rejected = runCatchingExceptions {
            service.decrypt(frame, request.vaultId, request.accountIdHash, ByteArrayOutputStream())
        }.exceptionOrNull()
        assertThat(rejected).isInstanceOf(ContentProtectionException.AccessDenied::class.java)

        val session = BurnAfterReadSession(
            contentId = request.contentId,
            accountIdHash = request.accountIdHash,
            installationId = request.senderInstallationId,
            sessionId = "burn-session-1",
            contentKind = ContentKind.IMAGE,
            windowMode = BurnAfterReadWindowMode.FIXED_WINDOW,
            openedAtEpochMillis = 100,
            viewExpiresAtEpochMillis = 500,
            globalExpiresAtEpochMillis = 5_000,
            serverTimeEpochMillis = 100,
        )
        registry.register(session)
        val plaintext = ByteArrayOutputStream()
        val result = service.decrypt(frame, request.vaultId, request.accountIdHash, plaintext)

        assertThat(plaintext.toByteArray()).isEqualTo(source.readBytes())
        assertThat(result.expiresAtEpochMillis).isEqualTo(session.viewExpiresAtEpochMillis)
        assertThat(result.revocationScope).isEqualTo(session.sessionId)
        assertThat(protected.file.name).contains(".yizhe-burn-")
    }

    @Test
    fun `removing burn-after-read session revokes every secure media lease in the session scope`() {
        val leaseManager = ScopeRecordingLeaseManager()
        val registry = BurnAfterReadMediaSessionRegistry(leaseManager)
        val session = BurnAfterReadSession(
            contentId = ContentId("content-media-revoke"),
            accountIdHash = "a".repeat(64),
            installationId = "installation-alice",
            sessionId = "burn-session-revoke",
            contentKind = ContentKind.IMAGE,
            windowMode = BurnAfterReadWindowMode.FIXED_WINDOW,
            openedAtEpochMillis = 100,
            viewExpiresAtEpochMillis = 500,
            globalExpiresAtEpochMillis = 5_000,
            serverTimeEpochMillis = 100,
        )
        registry.register(session)

        assertThat(registry.remove(session.accountIdHash, session.contentId, "wrong-session")).isNull()
        assertThat(leaseManager.revokedScopes).isEmpty()
        assertThat(registry.remove(session.accountIdHash, session.contentId, session.sessionId)).isEqualTo(session)
        assertThat(leaseManager.revokedScopes).containsExactly(session.sessionId)
        assertThat(registry.get(session.accountIdHash, session.contentId)).isNull()
    }

    private fun request() = ProtectContentRequest(
        contentId = ContentId("content-media-roundtrip"),
        vaultId = AccountVaultId("vault-alice"),
        kind = ContentKind.VOICE_MESSAGE,
        policy = ContentPolicyReference(PolicyId("strict"), 1, Long.MAX_VALUE),
        accountIdHash = "a".repeat(64),
        roomId = "!security:example.org",
        senderInstallationId = "installation-alice",
        createdAtEpochMillis = 1,
        recipients = emptyList(),
        offlineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
    )
}

private object NoOpLeaseManager : SecureMediaLeaseManager {
    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Not used")
}

private class ScopeRecordingLeaseManager : SecureMediaLeaseManager {
    val revokedScopes = mutableListOf<String>()

    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Not used")

    override fun revokeScope(scope: String) {
        revokedScopes += scope
    }
}

private class RetainingEnvelopeCodec : ContentEnvelopeCodec {
    private var envelope: ProtectedContentEnvelope? = null

    override fun encode(envelope: ProtectedContentEnvelope): ByteArray {
        this.envelope = envelope
        return "test-protected-header".encodeToByteArray()
    }

    override fun decode(encoded: ByteArray): ProtectedContentEnvelope {
        require(encoded.contentEquals("test-protected-header".encodeToByteArray()))
        return requireNotNull(envelope)
    }
}
