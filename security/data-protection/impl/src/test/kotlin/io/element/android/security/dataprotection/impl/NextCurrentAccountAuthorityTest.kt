/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class NextCurrentAccountAuthorityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test fun `exact signed schema3 identity survives reader recreation without inventing generation`() {
        val f = Fixture()
        val envelope = f.sign(f.wire())
        val first = f.select(listOf(envelope))
        val second = f.select(listOf(envelope))
        assertThat(first?.accountMobileGeneration).isEqualTo(7)
        assertThat(first?.activationId).isEqualTo(ACTIVATION)
        assertThat(first?.activationReceiptEnvelopeSha256).isEqualTo(second?.activationReceiptEnvelopeSha256)
        assertThat(first?.activationReceiptEnvelopeSha256).matches("[0-9a-f]{64}")
    }
    @Test fun `older schema receipt is retained but cannot become modern generation proof`() {
        val f = Fixture()
        val schema1 = f.sign(f.wire().copy(schemaVersion = 1, accountMobileGeneration = null))
        val schema2 = f.sign(f.wire().copy(schemaVersion = 2, accountMobileGeneration = null, identityContinuity = "CONTINUOUS"))
        assertThat(f.select(listOf(schema1, schema2))).isNull()
        assertThat(f.select(listOf(schema1, schema2, f.sign(f.wire())))?.accountMobileGeneration).isEqualTo(7)
    }
    @Test fun `newer mobile owner on another installation blocks old installation authority`() {
        val f = Fixture()
        val old = f.sign(f.wire())
        val moved = f.sign(f.wire().copy(installationId = "another-install", accountMobileGeneration = 8))
        reject { f.select(listOf(old, moved)) }
    }
    @Test fun `other account high generation cannot supersede this account head`() {
        val f = Fixture()
        val other = f.sign(f.wire().copy(accountIdHash = "f".repeat(64), accountMobileGeneration = 99))
        assertThat(f.select(listOf(other, f.sign(f.wire())))?.accountMobileGeneration).isEqualTo(7)
    }
    @Test fun `current session device scope client generation cross signing and recipient must all match`() {
        val f = Fixture()
        val original = f.sign(f.wire())
        val scopes = listOf(
            f.scope.copy(installationId = "another-install"),
            f.scope.copy(matrixDeviceIdHash = "e".repeat(64)),
            f.scope.copy(clientScopeId = "changed_scope_00000000001"),
            f.scope.copy(clientGeneration = 2),
            f.scope.copy(matrixCrossSigningKeyHash = "e".repeat(64)),
            f.scope.copy(recipientKeyId = "e".repeat(64))
        )
        scopes.forEach { scope -> reject { f.select(listOf(original), scope) } }
    }
    @Test fun `signature ambiguity unknown field tampering and untrusted key fail closed`() {
        val f = Fixture()
        val original = f.sign(f.wire())
        reject { f.select(listOf(original.copy(signatureBase64 = "invalid"))) }
        reject { f.select(listOf(original.copy(keyId = "0".repeat(16)))) }
        reject { f.select(listOf(original.copy(grant = checkNotNull(original.receipt)))) }
        val altered = JsonObject(checkNotNull(original.receipt) + ("account_mobile_generation" to JsonPrimitive(8)))
        reject { f.select(listOf(original.copy(receipt = altered))) }
        val unknown = JsonObject(checkNotNull(original.receipt) + ("extra" to JsonPrimitive(true)))
        reject { f.select(listOf(f.sign(unknown))) }
    }
    @Test fun `future nonpositive string overflow and missing numbers cannot supply authority`() {
        val f = Fixture()
        val wire = f.wire()
        val valid = f.payload(wire)
        reject { f.select(listOf(f.sign(wire.copy(committedAtEpochMillis = NOW + 1)))) }
        for (field in listOf("client_generation", "attestation_version", "policy_version", "committed_at_epoch_millis", "account_mobile_generation")) {
            for (value in listOf(JsonPrimitive(0), JsonPrimitive("7"), JsonPrimitive(-1), JsonPrimitive("9223372036854775808"))) {
                reject { f.select(listOf(f.sign(JsonObject(valid + (field to value))))) }
            }
            reject { f.select(listOf(f.sign(JsonObject(valid - field)))) }
        }
    }
    @Test fun `current attestation and policy rollback reject while normal renewal keeps provenance`() {
        val f = Fixture()
        val original = f.sign(f.wire())
        reject { f.select(listOf(original), f.scope.copy(currentAttestationVersion = 3)) }
        reject { f.select(listOf(original), f.scope.copy(currentPolicyVersion = 2)) }
        assertThat(f.select(listOf(original), f.scope.copy(currentAttestationVersion = 9, currentPolicyVersion = 10))?.accountMobileGeneration).isEqualTo(7)
    }
    @Test fun `tombstone invalid expected scope time and excessive history fail closed`() {
        val f = Fixture()
        val original = f.sign(f.wire())
        reject { f.select(listOf(original), tombstoned = true) }
        reject { f.select(listOf(original), now = 0) }
        reject { f.select(listOf(original), f.scope.copy(accountIdHash = "invalid")) }
        reject { f.select(List(129) { original }) }
        assertThat(f.select(emptyList())).isNull()
    }
    @Test fun `two owners at same mobile generation are rejected instead of choosing a convenient receipt`() {
        val f = Fixture()
        val original = f.sign(f.wire())
        val conflict = f.sign(f.wire().copy(installationId = "another-install", committedAtEpochMillis = NOW - 1))
        reject { f.select(listOf(original, conflict)) }
        assertThat(f.select(listOf(original, original))?.accountMobileGeneration).isEqualTo(7)
    }
    @Test fun `repository getter revalidates signed policy and durable activation after repository restart`() {
        val f = Fixture()
        val repository = f.repository(temporaryFolder.root)
        repository.acceptPolicy(f.policy(), NOW)
        repository.acceptActivationReceipt(f.sign(f.wire()))
        assertThat(repository.currentNextAccountAuthority(f.scope, NOW)?.accountMobileGeneration).isEqualTo(7)
        assertThat(f.repository(temporaryFolder.root).currentNextAccountAuthority(f.scope, NOW)?.accountMobileGeneration).isEqualTo(7)
        reject { repository.currentNextAccountAuthority(f.scope, NOW - 1) }
        reject { repository.currentNextAccountAuthority(f.scope.copy(currentPolicyVersion = 4), NOW) }
    }
    @Test fun `missing expired or tampered policy cannot open retained current account provenance`() {
        val f = Fixture()
        val repository = f.repository(temporaryFolder.root)
        repository.acceptActivationReceipt(f.sign(f.wire()))
        reject { repository.currentNextAccountAuthority(f.scope, NOW) }
        repository.acceptPolicy(f.policy(), NOW)
        reject { repository.currentNextAccountAuthority(f.scope, NOW + 60_000) }
        val state = temporaryFolder.root.resolve("security/management/control-state-v1.json")
        state.writeText(state.readText().replace("\"signature_b64\":\"", "\"signature_b64\":\"invalid"))
        reject { repository.currentNextAccountAuthority(f.scope, NOW) }
    }
    @Test fun `raw repository release retains current version signed envelope but rejects expired or future manifest`() {
        val f = Fixture()
        val repository = f.repository(temporaryFolder.root)
        val envelope = f.release()
        repository.acceptRelease(envelope, NOW)
        val raw = checkNotNull(repository.currentNextReleaseEnvelope(NOW))
        assertThat(raw.keys).containsExactly("release", "key_id", "signature_b64")
        assertThat(raw["release"]).isEqualTo(envelope.release)
        assertThat(f.repository(temporaryFolder.root).currentNextReleaseEnvelope(NOW)).isEqualTo(raw)
        reject { repository.currentNextReleaseEnvelope(NOW + 60_000) }
        // Legacy acceptance permits small issue-time skew; modern installed identity requires an issued manifest.
        val future = f.repository(temporaryFolder.newFolder())
        future.acceptRelease(f.release(issuedAt = NOW + 1), NOW)
        reject { future.currentNextReleaseEnvelope(NOW) }
    }
    @Test fun `raw release tampering corrupt state and rolled back version metadata are rejected`() {
        val f = Fixture()
        val repository = f.repository(temporaryFolder.root)
        assertThat(repository.currentNextReleaseEnvelope(NOW)).isNull()
        repository.acceptRelease(f.release(), NOW)
        val state = temporaryFolder.root.resolve("security/management/control-state-v1.json")
        val original = state.readText()
        state.writeText(original.replace("\"signature_b64\":\"", "\"signature_b64\":\"invalid"))
        reject { repository.currentNextReleaseEnvelope(NOW) }
        state.writeText(original.replace("\"highest_release_version\":123", "\"highest_release_version\":124"))
        reject { repository.currentNextReleaseEnvelope(NOW) }
        state.writeText("{truncated")
        reject { repository.currentNextReleaseEnvelope(NOW) }
    }
    private fun reject(block: () -> Unit) = assertThrows(IllegalStateException::class.java, block)
    private class Fixture {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val keyId = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val configuration = mockk<ManagementControlConfiguration> {
            every { isConfigured } returns true
            every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(pair.public.encoded)
            every { signingKeyId } returns keyId
        }
        val verifier = ManagementSignatureVerifier(configuration)
        val scope = NextCurrentAccountScope(ACCOUNT, "installation-1", DEVICE, "client_scope_00000000001", 1, 4, 3, CROSS, RECIPIENT)
        fun wire() = AccountActivationReceiptWire(
            schemaVersion = 3,
            purpose = "ACCOUNT_CORE_ACTIVATION",
            activationId = ACTIVATION,
            accountIdHash = ACCOUNT,
            installationId = scope.installationId,
            matrixDeviceIdHash = DEVICE,
            clientScopeId = scope.clientScopeId,
            clientGeneration = 1,
            attestationVersion = 4,
            policyVersion = 3,
            matrixCrossSigningKeyHash = CROSS,
            recipientKeyId = RECIPIENT,
            identityContinuity = "FIRST_ACTIVATION",
            accountMobileGeneration = 7,
            state = "CORE_READY",
            committedAtEpochMillis = NOW
        )
        fun payload(wire: AccountActivationReceiptWire) = Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToJsonElement(wire).jsonObject
        fun sign(wire: AccountActivationReceiptWire) = sign(payload(wire))
        fun sign(payload: JsonObject): SignedJsonEnvelopeWire {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(verifier.canonicalJson(payload))
                sign()
            }
            return SignedJsonEnvelopeWire(receipt = payload, keyId = keyId, signatureBase64 = Base64.getEncoder().encodeToString(signature))
        }
        fun repository(root: java.io.File): SignedManagementStateRepository {
            val context = object : ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
                override fun getNoBackupFilesDir(): java.io.File = root
            }
            return SignedManagementStateRepository(context, verifier)
        }
        fun policy(): SignedJsonEnvelopeWire {
            val wire = PolicyWire(
                policyId = "policy-next-1",
                version = 3,
                issuedAtEpochMillis = NOW - 100,
                effectiveAtEpochMillis = NOW - 100,
                expiresAtEpochMillis = NOW + 60_000,
                contentRetentionMillis = 120_000,
                maximumOfflineMillis = 0,
                offlineAccessMode = "ONLINE_REQUIRED",
                notificationExposure = "HIDDEN",
                hardwareTrustRequirement = "TEE_OR_STRONGBOX",
                allowContentExport = false,
                allowClipboard = false,
                allowExternalShare = false,
                screenCapturePolicy = "BLOCK",
                requireContentEnvelope = true,
                allowRtc = false,
                schemaVersion = 2
            )
            val payload = Json.encodeToJsonElement(wire).jsonObject
            val signed = sign(payload)
            return signed.copy(receipt = null, policy = payload)
        }
        fun release(issuedAt: Long = NOW - 100): SignedJsonEnvelopeWire {
            val wire = AppReleaseWire(
                schemaVersion = 2,
                versionCode = 123,
                versionName = "test-only",
                packageName = "com.yizhe.test.fixture",
                apkUrl = "https://fixture.invalid/current.apk",
                apkFallbackUrl = "https://fixture.invalid/fallback.apk",
                apkSha256 = ACCOUNT,
                buildId = "build-fixture-1",
                signingCertificateSha256 = DEVICE,
                releaseNotes = "Component fixture",
                mandatory = false,
                issuedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = NOW + 60_000
            )
            val payload = Json.encodeToJsonElement(wire).jsonObject
            val signed = sign(payload)
            return signed.copy(receipt = null, release = payload)
        }
        fun select(receipts: List<SignedJsonEnvelopeWire>, scope: NextCurrentAccountScope = this.scope, now: Long = NOW, tombstoned: Boolean = false) =
            VerifiedNextCurrentAccountAuthority.select(receipts, scope, now, tombstoned, verifier)
    }
    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ACTIVATION = "activation_000000000001"
        val ACCOUNT = "a".repeat(64)
        val DEVICE = "b".repeat(64)
        val CROSS = "c".repeat(64)
        val RECIPIENT = "d".repeat(64)
    }
}
