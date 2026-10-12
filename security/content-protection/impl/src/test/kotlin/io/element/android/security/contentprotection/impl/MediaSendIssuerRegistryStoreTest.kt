/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfiguration
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.MediaSendTrustedTime
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class MediaSendIssuerRegistryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `signed revision persists and second instance rejects rollback and equal conflict`() = runTest {
        val root = keyPair(); val issuer = keyPair(); val config = config(root); val roots = roots()
        val first = MediaSendIssuerRegistryStore(config, durable(roots))
        val second = MediaSendIssuerRegistryStore(config, durable(roots))
        val now = 50_000L
        val revision2 = envelope(root, issuer, 2, now - 10, now + 10_000)
        assertThat(first.installSignedRegistry(revision2, time(now)).getOrThrow().revision).isEqualTo(2)
        assertThat(second.installSignedRegistry(envelope(root, issuer, 1, now - 10, now + 10_000), time(now)).isFailure).isTrue()
        assertThat(second.installSignedRegistry(envelope(root, issuer, 2, now - 20, now + 10_000), time(now)).isFailure).isTrue()
        assertThat(second.currentVerifiedRegistry(time(now)).isFailure).isTrue()
        assertThat(second.installSignedRegistry(revision2, time(now)).getOrThrow().revision).isEqualTo(2)
        assertThat(second.currentVerifiedRegistry(time(now)).getOrThrow().revision).isEqualTo(2)
    }

    @Test fun `revoked only registry and bad root signature fail closed`() = runTest {
        val root = keyPair(); val issuer = keyPair(); val store = MediaSendIssuerRegistryStore(config(root), durable(roots()))
        val now = 50_000L
        val wrongRoot = envelope(keyPair(), issuer, 1, now - 1, now + 1000)
        assertThat(store.installSignedRegistry(wrongRoot, time(now)).isFailure).isTrue()
        val exactExpiry = envelope(root, issuer, 1, now - 1, now + 20)
        assertThat(store.installSignedRegistry(exactExpiry, MediaSendTrustedTime(now, now + 20)).isFailure).isTrue()
    }

    @Test fun `signed envelope is reverified and disk restore requires fresh authority`() = runTest {
        val root = keyPair(); val issuer = keyPair(); val roots = roots(); val durable = durable(roots)
        val store = MediaSendIssuerRegistryStore(config(root), durable); val now = 50_000L
        val revision1 = envelope(root, issuer, 1, now - 10, now + 10_000)
        val revision2 = envelope(root, issuer, 2, now - 10, now + 10_000)
        store.installSignedRegistry(revision1, time(now)).getOrThrow()
        val stateFile = java.io.File(temporary.root, "media-send-state/issuer-registry/current.state")
        val revision1State = stateFile.readBytes()
        store.installSignedRegistry(revision2, time(now)).getOrThrow()

        stateFile.writeBytes(revision1State)
        assertThat(store.currentVerifiedRegistry(time(now)).isFailure).isTrue()

        store.installSignedRegistry(revision2, time(now)).getOrThrow()
        val restarted = MediaSendIssuerRegistryStore(config(root), durable(roots))
        assertThat(restarted.currentVerifiedRegistry(time(now)).isFailure).isTrue()
        restarted.installSignedRegistry(revision2, time(now)).getOrThrow()
        assertThat(restarted.currentVerifiedRegistry(time(now)).getOrThrow().revision).isEqualTo(2)

        val tampered = revision2.decodeToString().replaceFirst("\"revision\":2", "\"revision\":3").encodeToByteArray()
        durable.withLockedState("issuer-registry", "current") { _, commit ->
            commit(DurableMediaSendStateStore.StoredState(2, sha256Hex(tampered), tampered))
        }
        assertThat(store.currentVerifiedRegistry(time(now)).isFailure).isTrue()
    }

    private fun envelope(root: KeyPair, issuer: KeyPair, revision: Long, issued: Long, expires: Long): ByteArray {
        val keyId = sha256Hex(issuer.public.encoded).take(16)
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(issuer.public.encoded)}\n-----END PUBLIC KEY-----\n"
        val payload = "{\"contract\":\"next.media-send-issuer-key-registry.v1\",\"expiresAtMs\":$expires,\"issuedAtMs\":$issued,\"keys\":[{\"algorithm\":\"SHA256withECDSA\",\"keyId\":\"$keyId\",\"notAfterMs\":${expires + 1000},\"notBeforeMs\":${issued - 1000},\"publicKeyPem\":${pem.quote()},\"status\":\"ACTIVE\"}],\"revision\":$revision}"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(root.private); update("YZC-NEXT-WP08-SEND-ISSUER-REGISTRY-v1\n$payload".encodeToByteArray()); Base64.getEncoder().encodeToString(sign())
        }
        val rootId = sha256Hex(root.public.encoded).take(16)
        return "{\"contract\":\"next.media-send-issuer-key-registry-envelope.v1\",\"payload\":$payload,\"signature\":{\"algorithm\":\"SHA256withECDSA\",\"keyId\":\"$rootId\",\"signatureBase64\":\"$signature\"}}".encodeToByteArray()
    }

    private fun config(root: KeyPair) = MediaSendAuthorityConfigurationProvider {
        MediaSendAuthorityConfiguration("https://cp.example", setOf("sha256/${"A".repeat(43)}="), root.public.encoded, 100)
    }
    private fun roots() = object : SecureStorageRoots { override fun persistentRoot() = temporary.root; override fun runtimeRoot() = temporary.root }
    private fun durable(roots: SecureStorageRoots) = DurableMediaSendStateStore(roots) { }
    private fun time(value: Long) = MediaSendTrustedTime(value, value)
    private fun keyPair() = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
}

private fun String.quote(): String = buildString { append('"'); this@quote.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) } }; append('"') }
