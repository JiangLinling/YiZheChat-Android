/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.PolicyApplicationEvidence
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class PolicyApplicationProtocolTest {
    @Test
    fun `two signed envelopes and exact payload digest stage application without claiming applied`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK", "CONTENT_ENVELOPE"))

        val verified = verifyPolicyPublication(
            policyEnvelope = pair.policy,
            publicationEnvelope = pair.publication,
            serverTimeEpochMillis = NOW,
            highestAcceptedVersion = 6,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK", "CONTENT_ENVELOPE"),
            verifier = fixture.verifier,
        )

        assertThat(verified.localState).isEqualTo(LocalPolicyApplicationState.PENDING_APPLY)
        assertThat(verified.mayStagePolicy).isTrue()
        assertThat(verified.refusedCapabilities).isEmpty()
    }

    @Test
    fun `future publication stays pending effective and expired publication fails closed`() {
        val fixture = Fixture()
        val future = fixture.publication(version = 7, effectiveAt = NOW + 1)
        assertThat(
            fixture.verify(future, highestVersion = 6).localState
        ).isEqualTo(LocalPolicyApplicationState.PENDING_EFFECTIVE)

        val expired = fixture.publication(version = 8, effectiveAt = NOW - 2, expiresAt = NOW - 1)
        val rejected = fixture.verify(expired, highestVersion = 7)
        assertThat(rejected.localState).isEqualTo(LocalPolicyApplicationState.FAILED)
        assertThat(rejected.mayStagePolicy).isFalse()
    }

    @Test
    fun `newer security epoch and unknown mandatory capability stay blocked version`() {
        val fixture = Fixture()
        val newerEpoch = fixture.publication(version = 7, minSecurityEpoch = 4, capabilities = listOf("CAPTURE_BLOCK"))
        assertThat(fixture.verify(newerEpoch, highestVersion = 6).localState)
            .isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION)

        val unknown = fixture.publication(version = 8, capabilities = listOf("CAPTURE_BLOCK", "FUTURE_CAPABILITY"))
        val rejected = fixture.verify(unknown, highestVersion = 7)
        assertThat(rejected.localState).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION)
        assertThat(rejected.refusedCapabilities).containsExactly("CAPTURE_BLOCK", "FUTURE_CAPABILITY").inOrder()
    }

    @Test
    fun `rollback tamper and unsorted capabilities are rejected before local staging`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7)
        assertThrows(IllegalStateException::class.java) {
            fixture.verify(pair, highestVersion = 8)
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.verify(
                pair.copy(publication = pair.publication.copy(signatureBase64 = "dGFtcGVyZWQ=")),
                highestVersion = 6,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.verify(
                fixture.publication(version = 9, capabilities = listOf("CONTENT_ENVELOPE", "CAPTURE_BLOCK")),
                highestVersion = 8,
            )
        }
    }

    @Test
    fun `empty extra capabilities never bypass complete global policy validation`() {
        val fixture = Fixture()
        val invalidBaseline = fixture.replacePolicyField(
            fixture.publication(version = 7, capabilities = emptyList()),
            "offline_access_mode",
            "UNKNOWN_MODE",
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.verify(invalidBaseline, highestVersion = 6)
        }
    }

    @Test
    fun `exact full sync revision envelope binds outer fields policy projection and both signatures`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val envelope = fixture.revisionEnvelope(pair)

        assertThat(
            verifyPolicyRevisionEnvelope(
                envelope = envelope,
                serverTimeEpochMillis = NOW,
                highestAcceptedVersion = 6,
                localSecurityEpoch = 3,
                supportedCapabilities = setOf("CAPTURE_BLOCK"),
                verifier = fixture.verifier,
            ).localState
        ).isEqualTo(LocalPolicyApplicationState.PENDING_APPLY)
        assertThrows(IllegalStateException::class.java) {
            verifyPolicyRevisionEnvelope(
                envelope = envelope.copy(version = 8),
                serverTimeEpochMillis = NOW,
                highestAcceptedVersion = 6,
                localSecurityEpoch = 3,
                supportedCapabilities = setOf("CAPTURE_BLOCK"),
                verifier = fixture.verifier,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            verifyPolicyRevisionEnvelope(
                envelope = envelope.copy(policy = JsonObject(envelope.policy + ("allow_rtc" to JsonPrimitive(false)))),
                serverTimeEpochMillis = NOW,
                highestAcceptedVersion = 6,
                localSecurityEpoch = 3,
                supportedCapabilities = setOf("CAPTURE_BLOCK"),
                verifier = fixture.verifier,
            )
        }
    }

    @Test
    fun `receipt canonical statement excludes signature and preserves nullable applied time`() {
        val fixture = Fixture()
        val receipt = receipt(
            result = "BLOCKED_VERSION",
            appliedAt = null,
            refused = listOf("CAPTURE_BLOCK"),
        )

        val canonical = canonicalPolicyApplicationReceiptPayload(receipt, fixture.verifier).decodeToString()

        assertThat(canonical).contains("\"appliedAt\":null")
        assertThat(canonical).contains("\"nonce\":\"nonce-policy-receipt-0001\"")
        assertThat(canonical).doesNotContain("deviceSignature")
        assertThat(canonical).doesNotContain(receipt.deviceSignature)
    }

    @Test
    fun `receipt must account for exact required capabilities`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val application = PersistedPolicyApplicationWire(
            publication = pair.publication,
            state = LocalPolicyApplicationState.BLOCKED_VERSION.name,
        )
        val statement = JSON.decodeFromJsonElement(PolicyPublicationWire.serializer(), pair.publication.publication)

        assertThat(
            validatePolicyApplicationReceipt(
                receipt(
                    result = "BLOCKED_VERSION",
                    appliedAt = null,
                    refused = listOf("CAPTURE_BLOCK"),
                    digest = statement.payloadDigest,
                ),
                application,
            )
        ).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION)
        assertThrows(IllegalStateException::class.java) {
            validatePolicyApplicationReceipt(
                receipt(
                    result = "BLOCKED_VERSION",
                    appliedAt = null,
                    refused = emptyList(),
                    digest = statement.payloadDigest,
                ),
                application,
            )
        }
    }

    @Test
    fun `cleanup pending receipt binds the durable pending operation identities`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val statement = JSON.decodeFromJsonElement(PolicyPublicationWire.serializer(), pair.publication.publication)
        val application = PersistedPolicyApplicationWire(
            publication = pair.publication,
            state = LocalPolicyApplicationState.PENDING_APPLY.name,
            cleanupOperations = listOf(
                PersistedPolicyCleanupOperationWire(
                    operationId = "r05-policy-7-capture-state",
                    kind = "CAPTURE_STATE_RECONCILE",
                    state = "PENDING",
                )
            ),
            applyHandshakeAtEpochMillis = NOW,
        )
        val valid = PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = "installation-policy-0001",
            bootId = "android-boot-7",
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            appliedAt = NOW,
            enforcedCapabilities = statement.requiredCapabilities,
            refusedCapabilities = emptyList(),
            cleanupOperationIds = listOf("r05-policy-7-capture-state"),
            result = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name,
            nonce = "nonce-policy-receipt-cleanup-0001",
            deviceSignature = "device-signature",
        )

        assertThat(validatePolicyApplicationReceipt(valid, application))
            .isEqualTo(LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING)
        assertThrows(IllegalStateException::class.java) {
            validatePolicyApplicationReceipt(
                valid.copy(cleanupOperationIds = listOf("r05-policy-7-other-cleanup")),
                application,
            )
        }
    }

    @Test
    fun `unsigned receipt construction binds exact policy installation boot and durable cleanup`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val application = PersistedPolicyApplicationWire(
            publication = pair.publication,
            state = LocalPolicyApplicationState.PENDING_APPLY.name,
            cleanupOperations = listOf(
                PersistedPolicyCleanupOperationWire(
                    operationId = "r05-policy-7-capture-state",
                    kind = "CAPTURE_STATE_RECONCILE",
                    state = "PENDING",
                )
            ),
            applyHandshakeAtEpochMillis = NOW,
        )

        val receipt = buildUnsignedPolicyApplicationReceipt(
            application = application,
            evidence = PolicyApplicationEvidence(
                version = 7,
                appliedAtEpochMillis = NOW,
                enforcedCapabilities = listOf("CAPTURE_BLOCK"),
                refusedCapabilities = emptyList(),
                result = ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
            ),
            installationId = "installation-policy-0001",
            bootCount = 9,
            nonce = "nonce-policy-receipt-build-0001",
        )

        assertThat(receipt.bootId).isEqualTo("android-boot-9")
        assertThat(receipt.installationId).isEqualTo("installation-policy-0001")
        assertThat(receipt.cleanupOperationIds).containsExactly("r05-policy-7-capture-state")
        assertThat(receipt.deviceSignature).isEmpty()
    }

    @Test
    fun `persisted receipt is reused only for the same successful evidence state`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val application = PersistedPolicyApplicationWire(
            publication = pair.publication,
            state = LocalPolicyApplicationState.PENDING_APPLY.name,
            cleanupOperations = listOf(
                PersistedPolicyCleanupOperationWire(
                    operationId = "r05-policy-7-capture-state",
                    kind = "CAPTURE_STATE_RECONCILE",
                    state = "PENDING",
                )
            ),
            applyHandshakeAtEpochMillis = NOW,
        )
        val pendingEvidence = PolicyApplicationEvidence(
            version = 7,
            appliedAtEpochMillis = NOW,
            enforcedCapabilities = listOf("CAPTURE_BLOCK"),
            refusedCapabilities = emptyList(),
            result = ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
        )
        val receipt = buildUnsignedPolicyApplicationReceipt(
            application = application,
            evidence = pendingEvidence,
            installationId = "installation-policy-0001",
            bootCount = 9,
            nonce = "nonce-policy-receipt-reuse-0001",
        ).copy(deviceSignature = "device-signature")

        assertThat(
            policyApplicationReceiptMatchesEvidence(
                receipt,
                application,
                pendingEvidence.copy(appliedAtEpochMillis = NOW + 1),
            )
        ).isTrue()
        assertThat(
            policyApplicationReceiptMatchesEvidence(
                receipt,
                application.copy(cleanupOperations = application.cleanupOperations.map { it.copy(state = "COMPLETE") }),
                pendingEvidence.copy(result = ManagementPolicyApplicationState.APPLIED),
            )
        ).isFalse()
    }

    @Test
    fun `server acknowledgement must bind the exact durable signed receipt`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val statement = JSON.decodeFromJsonElement(PolicyPublicationWire.serializer(), pair.publication.publication)
        val application = PersistedPolicyApplicationWire(
            publication = pair.publication,
            state = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name,
        )
        val receipt = PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = "installation-policy-0001",
            bootId = "android-boot-9",
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            appliedAt = NOW,
            enforcedCapabilities = listOf("CAPTURE_BLOCK"),
            refusedCapabilities = emptyList(),
            cleanupOperationIds = listOf("r05-policy-7-capture-state"),
            result = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name,
            nonce = "nonce-policy-receipt-ack-0001",
            deviceSignature = "device-signature",
        )
        val acknowledgement = PolicyApplicationReceiptAcknowledgementWire(
            revisionId = statement.revisionId,
            domain = receipt.policyDomain,
            version = receipt.version,
            installationId = receipt.installationId,
            state = receipt.result,
            bootId = receipt.bootId,
            digest = receipt.digest,
            appliedAt = receipt.appliedAt,
            enforcedCapabilities = receipt.enforcedCapabilities,
            refusedCapabilities = receipt.refusedCapabilities,
            cleanupOperationIds = receipt.cleanupOperationIds,
            lastEvidenceDigest = policyApplicationReceiptEvidenceDigest(receipt, fixture.verifier),
        )

        assertThat(
            validatePolicyApplicationReceiptAcknowledgement(receipt, application, acknowledgement, fixture.verifier)
        ).isEqualTo(acknowledgement)

        listOf(
            acknowledgement.copy(revisionId = "revision-policy-other"),
            acknowledgement.copy(installationId = "installation-policy-other"),
            acknowledgement.copy(state = LocalPolicyApplicationState.APPLIED.name),
            acknowledgement.copy(enforcedCapabilities = emptyList()),
            acknowledgement.copy(cleanupOperationIds = emptyList()),
            acknowledgement.copy(lastEvidenceDigest = "f".repeat(64)),
        ).forEach { mismatchedAcknowledgement ->
            assertThrows(IllegalStateException::class.java) {
                validatePolicyApplicationReceiptAcknowledgement(
                    receipt,
                    application,
                    mismatchedAcknowledgement,
                    fixture.verifier,
                )
            }
        }
    }

    @Test
    fun `blocked version receipt can be constructed without an apply handshake`() {
        val fixture = Fixture()
        val pair = fixture.publication(version = 7, capabilities = listOf("CAPTURE_BLOCK"))
        val receipt = buildUnsignedPolicyApplicationReceipt(
            application = PersistedPolicyApplicationWire(
                publication = pair.publication,
                state = LocalPolicyApplicationState.BLOCKED_VERSION.name,
                refusedCapabilities = listOf("CAPTURE_BLOCK"),
            ),
            evidence = PolicyApplicationEvidence(
                version = 7,
                appliedAtEpochMillis = null,
                enforcedCapabilities = emptyList(),
                refusedCapabilities = listOf("CAPTURE_BLOCK"),
                result = ManagementPolicyApplicationState.BLOCKED_VERSION,
            ),
            installationId = "installation-policy-0001",
            bootCount = 9,
            nonce = "nonce-policy-receipt-blocked-0001",
        )

        assertThat(receipt.result).isEqualTo("BLOCKED_VERSION")
        assertThat(receipt.appliedAt).isNull()
        assertThat(receipt.refusedCapabilities).containsExactly("CAPTURE_BLOCK")
    }

    private data class PublicationPair(
        val policy: SignedJsonEnvelopeWire,
        val publication: SignedPolicyPublicationWire,
    )

    private class Fixture {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyId = MessageDigest.getInstance("SHA-256")
            .digest(keyPair.public.encoded)
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        private val configuration = mockk<ManagementControlConfiguration> {
            every { isConfigured } returns true
            every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(keyPair.public.encoded)
            every { signingKeyId } returns keyId
        }
        val verifier = ManagementSignatureVerifier(configuration)

        fun publication(
            version: Long,
            effectiveAt: Long = NOW - 1,
            expiresAt: Long? = NOW + 60_000,
            minSecurityEpoch: Int = 3,
            capabilities: List<String> = emptyList(),
        ): PublicationPair {
            val policy = PolicyWire(
                policyId = "global-policy",
                version = version,
                issuedAtEpochMillis = NOW - 10,
                effectiveAtEpochMillis = effectiveAt,
                expiresAtEpochMillis = expiresAt,
                contentRetentionMillis = 60_000,
                maximumOfflineMillis = 30_000,
                offlineAccessMode = "BOUNDED_OFFLINE",
                notificationExposure = "HIDDEN",
                hardwareTrustRequirement = "TEE_OR_STRONGBOX",
                allowContentExport = false,
                allowClipboard = false,
                allowExternalShare = false,
                allowGroupReadReceipts = false,
                screenCapturePolicy = "BLOCK",
                requireContentEnvelope = true,
                allowRtc = true,
                schemaVersion = 2,
            )
            val policyJson = JSON.encodeToJsonElement(PolicyWire.serializer(), policy).jsonObject
            val content = JsonObject(policyJson.filterKeys { it !in setOf("version", "issued_at_epoch_millis", "schema_version") })
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(content)).toHex()
            val publication = PolicyPublicationWire(
                contract = "CP-POLICY",
                signatureSchemaVersion = 1,
                operationId = "operation-policy-$version",
                revisionId = "revision-policy-$version",
                domain = "GLOBAL_POLICY",
                version = version,
                scope = "GLOBAL",
                issuedAt = policy.issuedAtEpochMillis,
                effectiveAt = effectiveAt,
                expiresAt = expiresAt,
                payloadDigest = digest,
                minSecurityEpoch = minSecurityEpoch,
                requiredCapabilities = capabilities,
                targetSnapshotDigest = "b".repeat(64),
            )
            val publicationJson = JSON.encodeToJsonElement(PolicyPublicationWire.serializer(), publication).jsonObject
            return PublicationPair(
                policy = SignedJsonEnvelopeWire(
                    policy = policyJson,
                    keyId = keyId,
                    signatureBase64 = sign(verifier.canonicalJson(policyJson)),
                ),
                publication = SignedPolicyPublicationWire(
                    publication = publicationJson,
                    keyId = keyId,
                    signatureBase64 = sign(verifier.canonicalJson(publicationJson)),
                ),
            )
        }

        fun verify(pair: PublicationPair, highestVersion: Long): VerifiedPolicyPublication = verifyPolicyPublication(
            policyEnvelope = pair.policy,
            publicationEnvelope = pair.publication,
            serverTimeEpochMillis = NOW,
            highestAcceptedVersion = highestVersion,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK", "CONTENT_ENVELOPE"),
            verifier = verifier,
        )

        fun revisionEnvelope(pair: PublicationPair): SignedPolicyRevisionEnvelopeWire {
            val statement = JSON.decodeFromJsonElement(
                PolicyPublicationWire.serializer(),
                pair.publication.publication,
            )
            val legacyPolicy = checkNotNull(pair.policy.policy)
            return SignedPolicyRevisionEnvelopeWire(
                contract = statement.contract,
                signatureSchemaVersion = statement.signatureSchemaVersion,
                revisionId = statement.revisionId,
                operationId = statement.operationId,
                domain = statement.domain,
                version = statement.version,
                payloadDigest = statement.payloadDigest,
                policy = JsonObject(legacyPolicy.filterKeys { it !in setOf("version", "issued_at_epoch_millis", "schema_version") }),
                publicationCanonical = pair.publication.publication,
                publicationSignatureB64 = pair.publication.signatureBase64,
                legacyPolicy = legacyPolicy,
                legacyPolicySignatureB64 = pair.policy.signatureBase64,
                keyId = pair.publication.keyId,
                effectiveAt = statement.effectiveAt,
                expiresAt = statement.expiresAt,
                serverFenceEvidenceDigest = "c".repeat(64),
            )
        }

        fun replacePolicyField(pair: PublicationPair, name: String, value: String): PublicationPair {
            val payload = JsonObject(checkNotNull(pair.policy.policy) + (name to JsonPrimitive(value)))
            return pair.copy(
                policy = pair.policy.copy(
                    policy = payload,
                    signatureBase64 = sign(verifier.canonicalJson(payload)),
                )
            )
        }

        private fun sign(value: ByteArray): String = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(value)
                sign()
            }
        )
    }

    private companion object {
        const val NOW = 2_000_000L
        val JSON = Json { explicitNulls = true; encodeDefaults = true }

        fun receipt(
            result: String,
            appliedAt: Long?,
            refused: List<String>,
            digest: String = "a".repeat(64),
        ) = PolicyApplicationReceiptWire(
            operationId = "operation-policy-7",
            installationId = "installation-policy-0001",
            bootId = "android-boot-7",
            policyDomain = "GLOBAL_POLICY",
            version = 7,
            digest = digest,
            appliedAt = appliedAt,
            enforcedCapabilities = emptyList(),
            refusedCapabilities = refused,
            cleanupOperationIds = emptyList(),
            result = result,
            nonce = "nonce-policy-receipt-0001",
            deviceSignature = "device-signature",
        )

        fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
