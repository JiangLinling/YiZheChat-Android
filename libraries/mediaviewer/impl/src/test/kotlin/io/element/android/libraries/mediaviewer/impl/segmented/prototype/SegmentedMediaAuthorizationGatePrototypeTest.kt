/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SegmentedMediaAuthorizationGatePrototypeTest {
    private val context = SegmentedMediaReadContextPrototype("owner", "lease", 2L)

    @Test
    fun `exact lease owner and generation are checked against current authority`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context, expiresAtMs = 2_000L)
        val gate = authority.gate()

        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED)
        authority.currentContext = context.copy(leaseId = "replacement")
        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.LEASE_MISMATCH)
        assertThat(gate.check(context.copy(leaseId = "replacement"), SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED)
        authority.currentContext = context.copy(generation = 3L)
        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.GENERATION_MISMATCH)
    }

    @Test
    fun `deadline boundary expires and host wall rollback cannot extend it`() {
        val wall = MutableWallClockPrototype(1_000L)
        val elapsed = MutableElapsedClockPrototype(100L)
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context, expiresAtMs = 1_010L)
        val gate = authority.gate(wall, elapsed)

        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED)
        wall.nowMs = 900L
        elapsed.nowMs = 109L
        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED)
        elapsed.nowMs = 110L
        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.EXPIRED)
        wall.nowMs = 800L
        elapsed.nowMs = 105L
        assertThat(gate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION))
            .isEqualTo(SegmentedMediaAuthorizationDecisionPrototype.EXPIRED)
    }

    @Test
    fun `dynamic revoke denies a later read and clears the old buffer`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        val ciphertext = byteArrayOf(1, 2, 3)
        val verified = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> byteArrayOf(10, 11, 12) },
            authorizationGate = authority.gate(),
        ).verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context) as SegmentedMediaVerificationResultPrototype.Verified

        assertThat(verified.segment.read(context, 0, 3)).isNotNull()
        authority.revoked = true
        assertThat(verified.segment.read(context, 0, 3)).isNull()
        assertThat(verified.segment.isClearedForTesting()).isTrue()
    }

    @Test
    fun `generation rollover cannot be disguised as a new context for an old buffer`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        val ciphertext = byteArrayOf(1, 2, 3)
        val verified = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> byteArrayOf(10, 11, 12) },
            authorizationGate = authority.gate(),
        ).verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context) as SegmentedMediaVerificationResultPrototype.Verified

        authority.currentContext = context.copy(generation = 3L)
        assertThat(verified.segment.read(context.copy(generation = 3L), 0, 3)).isNull()
        assertThat(verified.segment.isClearedForTesting()).isTrue()
    }

    @Test
    fun `lease replacement clears old bytes even when caller presents the replacement context`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        val ciphertext = byteArrayOf(1, 2, 3)
        val verified = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> byteArrayOf(10, 11, 12) },
            authorizationGate = authority.gate(),
        ).verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context) as SegmentedMediaVerificationResultPrototype.Verified

        val replacement = context.copy(leaseId = "replacement")
        authority.currentContext = replacement
        assertThat(verified.segment.read(replacement, 0, 3)).isNull()
        assertThat(verified.segment.isClearedForTesting()).isTrue()
    }

    @Test
    fun `authorization lost during authentication wipes the result before delivery`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        val plaintext = byteArrayOf(10, 11, 12)
        val ciphertext = byteArrayOf(1, 2, 3)
        val verifier = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ ->
                authority.revoked = true
                plaintext
            },
            authorizationGate = authority.gate(),
        )

        val result = verifier.verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context)

        assertThat(result).isEqualTo(
            SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.AUTHORIZATION_DENIED
            )
        )
        assertThat(plaintext.asList()).containsExactly(0.toByte(), 0.toByte(), 0.toByte())
    }

    @Test
    fun `authorization lost between seek admission and delivery rejects the plan`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        authority.afterValidation = { purpose, decision ->
            if (purpose == SegmentedMediaAuthorizationPurposePrototype.SEEK_ADMISSION &&
                decision == SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
            ) {
                authority.revoked = true
            }
        }
        val validated = SegmentedMediaManifestValidatorPrototype.validate(aFixtureManifest())
        val proof = aRandomAccessProof(validated, 0, context)
        val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 0L,
            context = context,
            authorizationGate = authority.gate(),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = listOf(proof),
        )

        assertThat(result).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.AUTHORIZATION_DENIED)
        )
    }
}

internal class MutableWallClockPrototype(var nowMs: Long) : SegmentedMediaWallClockPrototype {
    override fun nowEpochMs(): Long = nowMs
}

internal class MutableElapsedClockPrototype(var nowMs: Long) : SegmentedMediaElapsedClockPrototype {
    override fun elapsedRealtimeMs(): Long = nowMs
}

internal class FakeSegmentedMediaLeaseAuthorityPrototype(
    var currentContext: SegmentedMediaReadContextPrototype?,
    var expiresAtMs: Long = Long.MAX_VALUE,
    var revoked: Boolean = false,
) : SegmentedMediaLeaseAuthorizationValidatorPrototype {
    var afterValidation: ((SegmentedMediaAuthorizationPurposePrototype, SegmentedMediaAuthorizationDecisionPrototype) -> Unit)? = null

    override fun validate(
        context: SegmentedMediaReadContextPrototype,
        effectiveNowEpochMs: Long,
        purpose: SegmentedMediaAuthorizationPurposePrototype,
    ): SegmentedMediaAuthorizationDecisionPrototype {
        val current = currentContext
        val decision = when {
            current == null -> SegmentedMediaAuthorizationDecisionPrototype.AUTHORITY_UNAVAILABLE
            current.ownerToken != context.ownerToken -> SegmentedMediaAuthorizationDecisionPrototype.OWNER_MISMATCH
            current.leaseId != context.leaseId -> SegmentedMediaAuthorizationDecisionPrototype.LEASE_MISMATCH
            current.generation != context.generation -> SegmentedMediaAuthorizationDecisionPrototype.GENERATION_MISMATCH
            revoked -> SegmentedMediaAuthorizationDecisionPrototype.REVOKED
            effectiveNowEpochMs >= expiresAtMs -> SegmentedMediaAuthorizationDecisionPrototype.EXPIRED
            else -> SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        }
        afterValidation?.invoke(purpose, decision)
        return decision
    }

    fun gate(
        wallClock: MutableWallClockPrototype = MutableWallClockPrototype(1_000L),
        elapsedClock: MutableElapsedClockPrototype = MutableElapsedClockPrototype(100L),
    ): SegmentedMediaAuthorizationGatePrototype = SegmentedMediaAuthorizationGatePrototype(
        validator = this,
        wallClock = wallClock,
        elapsedClock = elapsedClock,
    )
}

internal fun alwaysAuthorizedGate(context: SegmentedMediaReadContextPrototype): SegmentedMediaAuthorizationGatePrototype =
    FakeSegmentedMediaLeaseAuthorityPrototype(context).gate()

private fun aVerifierDescriptor(
    ciphertext: ByteArray,
    plainLength: Long,
): SegmentedMediaResourcePrototype {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(ciphertext)
        .joinToString("") { "%02x".format(it) }
    return aSegment(index = 0, startUs = 0L, plainLength = plainLength).let { segment ->
        segment.copy(locator = segment.locator.copy(outerCipherSha256 = digest, cipherLength = ciphertext.size.toLong()))
    }
}
