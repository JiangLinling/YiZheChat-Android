/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Values must come from the current session, encrypted reactivation scope and actual DAK/share-key services. */
internal data class NextCurrentAccountScope(
    val accountIdHash: String,
    val installationId: String,
    val matrixDeviceIdHash: String,
    val clientScopeId: String,
    val clientGeneration: Long,
    val currentAttestationVersion: Long,
    val currentPolicyVersion: Long,
    val matrixCrossSigningKeyHash: String,
    val recipientKeyId: String,
)

/** Local signed activation provenance, never a claim that an online account head is still current. */
internal class VerifiedNextCurrentAccountAuthority private constructor(
    val accountMobileGeneration: Long,
    val activationId: String,
    val activationReceiptEnvelopeSha256: String,
) {
    companion object {
        fun select(
            receipts: List<SignedJsonEnvelopeWire>,
            expected: NextCurrentAccountScope,
            trustedNowMs: Long,
            accountIsTombstoned: Boolean,
            verifier: ManagementSignatureVerifier,
        ): VerifiedNextCurrentAccountAuthority? {
            check(trustedNowMs > 0 && !accountIsTombstoned)
            check(expected.accountIdHash.matches(HASH) && expected.matrixDeviceIdHash.matches(HASH))
            check(expected.matrixCrossSigningKeyHash.matches(HASH) && expected.recipientKeyId.matches(HASH))
            check(expected.installationId.matches(ID) && expected.clientScopeId.matches(SCOPE_ID))
            check(expected.clientGeneration > 0 && expected.currentAttestationVersion > 0 && expected.currentPolicyVersion > 0)
            check(receipts.size <= 128)
            val currentAccount = receipts.map { envelope ->
                val payload = checkNotNull(envelope.receipt)
                // Reconstruct exactly this signed receipt payload; ambiguous envelope selectors are never authority.
                check(envelope.payload() === payload)
                check(listOf(
                    envelope.policy,
                    envelope.command,
                    envelope.decision,
                    envelope.license,
                    envelope.release,
                    envelope.ticket,
                    envelope.grant,
                    envelope.milestone,
                    envelope.tombstones
                ).all { it == null })
                val ownedPayload = Json.decodeFromString(JsonObject.serializer(), payload.toString())
                val owned = SignedJsonEnvelopeWire(receipt = ownedPayload, keyId = envelope.keyId, signatureBase64 = envelope.signatureBase64)
                check(verifier.verify(owned)) { "Persisted activation signature is invalid." }
                val version = exactLong(ownedPayload, "schema_version")
                check(version in 1..3)
                val required = FIELDS - if (version < 3) setOf("account_mobile_generation") else emptySet()
                // Older persisted schema receipts may omit their default FIRST_ACTIVATION identity field.
                check(ownedPayload.keys.all { it in FIELDS })
                check(ownedPayload.keys.containsAll(required - setOf("identity_continuity")))
                listOf("client_generation", "attestation_version", "policy_version", "committed_at_epoch_millis")
                    .forEach { check(exactLong(ownedPayload, it) > 0) }
                if (version == 3L) check(exactLong(ownedPayload, "account_mobile_generation") > 0)
                val wire = Json.decodeFromJsonElement(AccountActivationReceiptWire.serializer(), ownedPayload)
                check(wire.purpose == "ACCOUNT_CORE_ACTIVATION" && wire.state == "CORE_READY")
                check(wire.accountIdHash.matches(HASH) && wire.matrixDeviceIdHash.matches(HASH))
                check(wire.matrixCrossSigningKeyHash.matches(HASH) && wire.recipientKeyId.matches(HASH))
                check(wire.installationId.matches(ID) && wire.activationId.matches(SCOPE_ID) && wire.clientScopeId.matches(SCOPE_ID))
                check(wire.committedAtEpochMillis <= trustedNowMs)
                check(wire.identityContinuity in setOf("FIRST_ACTIVATION", "CONTINUOUS", "RESET_CONFIRMED"))
                if (version == 1L) check(wire.identityContinuity == "FIRST_ACTIVATION")
                if (version < 3) check(wire.accountMobileGeneration == null)
                wire to owned
            }.filter { it.first.accountIdHash == expected.accountIdHash && it.first.schemaVersion == 3 }
            val generation = currentAccount.maxOfOrNull { checkNotNull(it.first.accountMobileGeneration) } ?: return null
            val atHead = currentAccount.filter { it.first.accountMobileGeneration == generation }
            check(atHead.map { it.first.ownerIdentity() }.toSet().size == 1) { "Conflicting retained mobile generation owners." }
            val selected = atHead.maxBy { it.first.committedAtEpochMillis }
            val wire = selected.first
            check(wire.installationId == expected.installationId && wire.matrixDeviceIdHash == expected.matrixDeviceIdHash)
            check(wire.clientScopeId == expected.clientScopeId && wire.clientGeneration == expected.clientGeneration)
            check(wire.matrixCrossSigningKeyHash == expected.matrixCrossSigningKeyHash && wire.recipientKeyId == expected.recipientKeyId)
            // Renewed attestation/policy may advance without changing an already committed account head.
            check(wire.attestationVersion <= expected.currentAttestationVersion && wire.policyVersion <= expected.currentPolicyVersion)
            val envelope = buildJsonObject {
                put("receipt", checkNotNull(selected.second.receipt))
                put("key_id", selected.second.keyId)
                put("signature_b64", selected.second.signatureBase64)
            }
            return VerifiedNextCurrentAccountAuthority(
                generation,
                wire.activationId,
                MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(envelope)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            )
        }
        private fun AccountActivationReceiptWire.ownerIdentity() = listOf(
            installationId,
            matrixDeviceIdHash,
            clientScopeId,
            clientGeneration.toString(),
            matrixCrossSigningKeyHash,
            recipientKeyId
        )
        private fun exactLong(payload: JsonObject, key: String): Long {
            val value = payload[key] as? JsonPrimitive ?: error("Missing activation number.")
            check(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")))
            return checkNotNull(value.content.toLongOrNull())
        }
        private val HASH = Regex("[0-9a-f]{64}")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val SCOPE_ID = Regex("[A-Za-z0-9_-]{20,128}")
        private val FIELDS = setOf(
            "schema_version",
            "purpose",
            "activation_id",
            "account_id_hash",
            "installation_id",
            "matrix_device_id_hash",
            "client_scope_id",
            "client_generation",
            "attestation_version",
            "policy_version",
            "matrix_cross_signing_key_hash",
            "recipient_key_id",
            "identity_continuity",
            "account_mobile_generation",
            "state",
            "committed_at_epoch_millis"
        )
    }
}
