/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Uses the existing installation DAK; never creates a key or substitutes an application signer.
 * This signs a verified challenge outcome, not a fabricated local application/cleanup observation.
 * The caller must durably store the returned envelope before sending and replay that original.
 */
internal class NextPolicyReceiptSigner(
    private val trust: DeviceTrustService,
    private val verifier: ManagementSignatureVerifier,
) {
    suspend fun sign(
        challenge: VerifiedNextPolicyChallenge,
        expectedContext: NextPolicyChallengeContext,
        currentContext: suspend () -> NextPolicyChallengeContext,
        appliedAtMonotonicMs: Long,
        trustedServerTimeMs: Long,
        observations: List<NextCapabilityObservation>,
    ): JsonObject {
        val expected = expectedContext.copy(
            targetSnapshot = Json.parseToJsonElement(verifier.canonicalJson(expectedContext.targetSnapshot).decodeToString()) as JsonObject,
            requiredCapabilities = expectedContext.requiredCapabilities.toSet(),
        )
        check(currentContext() == expected) { "Application authority changed before DAK signing." }
        val before = trust.current()
        requireCurrent(before, expected, trustedServerTimeMs)
        val payload = challenge.receiptPayload(expected, appliedAtMonotonicMs, trustedServerTimeMs, observations)
        val signature = trust.signControlStatement(verifier.canonicalJson(payload))
        val after = trust.current()
        requireCurrent(after, expected, trustedServerTimeMs)
        check(after.installationId == before.installationId && after.attestationVersion == before.attestationVersion)
        check(after.attestationPublicKeySha256 == before.attestationPublicKeySha256)
        check(after.trustState == before.trustState && after.managedLicense == before.managedLicense)
        check(signature.installationId == before.installationId && signature.attestationVersion == before.attestationVersion)
        check(currentContext() == expected) { "Application authority changed during DAK signing." }
        check(signature.signature.size in 1..8192)
        return buildJsonObject {
            put("contract", "next.policy-applied-receipt.v1")
            put("payload", payload)
            put("deviceKeyId", expected.devicePublicKeySha256.take(16))
            put("devicePublicKeySha256", expected.devicePublicKeySha256)
            put("deviceSignatureAlgorithm", "SHA256withECDSA")
            put("deviceSignatureB64", Base64.getEncoder().encodeToString(signature.signature.copyOf()))
        }
    }

    private fun requireCurrent(snapshot: DeviceTrustSnapshot, context: NextPolicyChallengeContext, trustedServerTimeMs: Long) {
        check(snapshot.installationId == context.installationId && snapshot.attestationVersion > 0)
        check(snapshot.attestationPublicKeySha256 == context.devicePublicKeySha256)
        check(snapshot.riskSignals.none { it.blocksLocalVaultAccess })
        when (snapshot.trustState) {
            DeviceTrustState.TRUSTED -> check(
                snapshot.hardwareSecurityLevel in setOf(HardwareSecurityLevel.STRONGBOX, HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT)
            )
            DeviceTrustState.MANAGED -> {
                // The trust service applies this only after verifying the management
                // signature and immutable license bindings. Pending is not admission.
                val license = checkNotNull(snapshot.managedLicense)
                check(license.isActive(trustedServerTimeMs) && license.issuedAtEpochMillis <= trustedServerTimeMs)
                check(license.installationId == context.installationId && license.devicePublicKeySha256 == context.devicePublicKeySha256)
                check(license.allowsAccount(context.accountIdHash))
            }
            else -> error("Current device authorization is unavailable.")
        }
    }
}
