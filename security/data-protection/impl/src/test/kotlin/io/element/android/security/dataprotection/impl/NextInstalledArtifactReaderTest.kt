/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** Stream fixtures test identity rejection. They are not real Android APK or hardware acceptance. */
class NextInstalledArtifactReaderTest {
    @Test
    fun `installed stream matches the signed same version manifest and produces exact claim`() {
        val f = Fixture()
        val claim = f.inspect().claim("operation-reader", "installation-reader", "nonce-reader")
        assertThat(claim.appArtifactSha256).isEqualTo(hash(f.bytes))
        assertThat(claim.appVersionCode).isEqualTo(123L)
        assertThat(claim.buildId).isEqualTo("next.current.123")
        assertThat(f.snapshots).isEqualTo(2)
        assertThat(f.streamClosed).isTrue()
    }

    @Test
    fun `tampered release another signing key and ambiguous envelope are rejected`() {
        val f = Fixture()
        reject { f.inspect(JsonObject(f.envelope() + ("release" to JsonObject(f.payload() + ("build_id" to JsonPrimitive("tampered")))))) }
        reject { f.inspect(Fixture().envelope()) }
        reject { f.inspect(JsonObject(f.envelope() + ("policy" to f.payload()))) }
        reject { f.inspect(f.envelope(JsonObject(f.payload() + ("extra" to JsonPrimitive(true))))) }
        reject { f.inspect(JsonObject(f.envelope() + ("key_id" to JsonPrimitive(123)))) }
    }

    @Test
    fun `package version signer actual bytes and malformed build identity cannot be substituted`() {
        val f = Fixture()
        val changes = mapOf(
            "package_name" to JsonPrimitive("com.other.app"),
            "version_code" to JsonPrimitive(124),
            "signing_certificate_sha256" to JsonPrimitive("9".repeat(64)),
            "apk_sha256" to JsonPrimitive("8".repeat(64)),
            "build_id" to JsonPrimitive("invalid build"),
            "schema_version" to JsonPrimitive(1)
        )
        changes.forEach { (key, value) -> reject { f.inspect(f.envelope(JsonObject(f.payload() + (key to value)))) } }
    }

    @Test
    fun `split multisigner missing signer and incomplete stream fail closed`() {
        val changes: List<(NextInstalledPackageSnapshot) -> NextInstalledPackageSnapshot> = listOf(
            { it.copy(splitApkPaths = listOf("split.apk")) },
            { it.copy(currentSignerSha256 = it.currentSignerSha256 + "9".repeat(64)) },
            { it.copy(currentSignerSha256 = emptyList()) },
            { it.copy(byteLength = 0) },
            { it.copy(byteLength = it.byteLength + 1) },
            { it.copy(byteLength = it.byteLength - 1) },
            { it.copy(canonicalApkPath = "") },
        )
        changes.forEach { change ->
            val f = Fixture()
            f.snapshotTransform = change
            reject { f.inspect() }
        }
    }

    @Test
    fun `package metadata changes during streaming invalidate an otherwise exact digest`() {
        val changes: List<(NextInstalledPackageSnapshot) -> NextInstalledPackageSnapshot> = listOf(
            { it.copy(versionCode = 124) },
            { it.copy(packageName = "com.other.app") },
            { it.copy(canonicalApkPath = "/another/base.apk") },
            { it.copy(lastUpdateTime = 11) },
            { it.copy(lastModifiedTime = 21) },
            { it.copy(byteLength = it.byteLength + 1) },
            { it.copy(currentSignerSha256 = listOf("9".repeat(64))) },
            { it.copy(splitApkPaths = listOf("split.apk")) },
        )
        changes.forEach { change ->
            val f = Fixture()
            f.afterReadTransform = change
            reject { f.inspect() }
            assertThat(f.streamClosed).isTrue()
        }
    }

    @Test
    fun `expired future issued string numbers and nonpositive trusted time are rejected`() {
        val f = Fixture()
        val changes = mapOf(
            "expires_at_epoch_millis" to JsonPrimitive(NOW),
            "issued_at_epoch_millis" to JsonPrimitive(NOW + 1),
            "version_code" to JsonPrimitive("123"),
            "issued_at_epoch_millis" to JsonPrimitive("1000"),
            "schema_version" to JsonPrimitive("2"),
            "expires_at_epoch_millis" to JsonPrimitive("2000")
        )
        changes.forEach { (key, value) -> reject { f.inspect(f.envelope(JsonObject(f.payload() + (key to value)))) } }
        reject { f.inspect(now = 0) }
    }

    private class Fixture : NextInstalledPackageSource {
        val bytes = "synthetic-installed-base-apk-bytes".encodeToByteArray()
        private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyId = hash(key.public.encoded).take(16)
        private val config = mockk<ManagementControlConfiguration>().also {
            every { it.isConfigured } returns true
            every { it.signingKeyId } returns keyId
            every { it.signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(key.public.encoded)
        }
        private val verifier = ManagementSignatureVerifier(config)
        var snapshots = 0
        var streamClosed = false
        var snapshotTransform: (NextInstalledPackageSnapshot) -> NextInstalledPackageSnapshot = { it }
        var afterReadTransform: (NextInstalledPackageSnapshot) -> NextInstalledPackageSnapshot = { it }
        override fun snapshot(): NextInstalledPackageSnapshot {
            snapshots++
            val value = NextInstalledPackageSnapshot(
                "com.yizhe.chat.next.debug",
                123,
                "/installed/base.apk",
                emptyList(),
                listOf("7".repeat(64)),
                10,
                bytes.size.toLong(),
                20
            )
            return snapshotTransform(if (streamClosed) afterReadTransform(value) else value)
        }
        override fun openBaseApk(snapshot: NextInstalledPackageSnapshot) = object : ByteArrayInputStream(bytes) {
            override fun close() {
                streamClosed = true
                super.close()
            }
        }
        fun payload() = buildJsonObject {
            put("schema_version", 2)
            put("version_code", 123)
            put("version_name", "123")
            put("package_name", "com.yizhe.chat.next.debug")
            put("apk_url", "https://example.invalid/base.apk")
            put("apk_fallback_url", "https://example.invalid/fallback.apk")
            put("apk_sha256", hash(bytes))
            put("build_id", "next.current.123")
            put("signing_certificate_sha256", "7".repeat(64))
            put("release_notes", "")
            put("mandatory", false)
            put("issued_at_epoch_millis", NOW - 100)
            put("expires_at_epoch_millis", NOW + 1000)
        }
        fun envelope(payload: JsonObject = payload()): JsonObject {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(verifier.canonicalJson(payload))
                sign()
            }
            return buildJsonObject {
                put("release", payload)
                put("key_id", keyId)
                put("signature_b64", Base64.getEncoder().encodeToString(signature))
            }
        }
        fun inspect(envelope: JsonObject = envelope(), now: Long = NOW) = VerifiedNextInstalledArtifact.inspect(this, envelope, now, verifier)
    }
    private fun reject(block: () -> Unit) {
        assertThrows(IllegalStateException::class.java, block)
    }
    companion object {
        private const val NOW = 1_000_000L
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
