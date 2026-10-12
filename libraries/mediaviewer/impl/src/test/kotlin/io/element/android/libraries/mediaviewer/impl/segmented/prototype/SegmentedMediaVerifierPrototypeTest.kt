/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

class SegmentedMediaVerifierPrototypeTest {
    private val context = SegmentedMediaReadContextPrototype("owner", "lease", 2L)

    @Test
    fun `outer digest mismatch rejects before decrypt and authenticate`() {
        var decryptCalls = 0
        var authenticateCalls = 0
        val verifier = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype {
                decryptCalls++
                byteArrayOf(1)
            },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ ->
                authenticateCalls++
                byteArrayOf(1)
            },
            authorizationGate = alwaysAuthorizedGate(context),
        )

        val result = verifier.verify(
            descriptor = aVerifierDescriptor(byteArrayOf(9), plainLength = 1L),
            ciphertext = byteArrayOf(8),
            context = context,
        )

        assertThat(result).isEqualTo(
            SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.OUTER_DIGEST_MISMATCH
            )
        )
        assertThat(decryptCalls).isEqualTo(0)
        assertThat(authenticateCalls).isEqualTo(0)
    }

    @Test
    fun `inner authentication failure releases no decoder bytes and wipes container`() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val decryptedContainer = byteArrayOf(7, 7, 7)
        val verifier = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { decryptedContainer },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> null },
            authorizationGate = alwaysAuthorizedGate(context),
        )

        val result = verifier.verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context)

        assertThat(result).isEqualTo(
            SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.INNER_AUTHENTICATION_FAILED
            )
        )
        assertThat(decryptedContainer.asList()).containsExactly(0.toByte(), 0.toByte(), 0.toByte())
    }

    @Test
    fun `fully authenticated bytes remain bound to owner lease and generation`() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val verifier = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> byteArrayOf(10, 11, 12) },
            authorizationGate = alwaysAuthorizedGate(context),
        )

        val result = verifier.verify(aVerifierDescriptor(ciphertext, 3L), ciphertext, context)
            as SegmentedMediaVerificationResultPrototype.Verified

        assertThat(result.segment.read(context, 0, 3)?.asList())
            .containsExactly(10.toByte(), 11.toByte(), 12.toByte()).inOrder()
        assertThat(result.segment.read(context.copy(generation = 3L), 0, 3)).isNull()
        assertThat(result.segment.read(context.copy(ownerToken = "other"), 0, 3)).isNull()
        result.segment.close()
        assertThat(result.segment.read(context, 0, 3)).isNull()
        assertThat(result.segment.isClearedForTesting()).isTrue()
    }

    @Test
    fun `authenticated plaintext with wrong declared length is wiped and rejected`() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val plaintext = byteArrayOf(10, 11, 12)
        val verifier = SegmentedMediaVerifierPrototype(
            outerDecryptor = SegmentedMediaOuterDecryptorPrototype { byteArrayOf(4, 5, 6) },
            innerAuthenticator = SegmentedMediaInnerAuthenticatorPrototype { _, _ -> plaintext },
            authorizationGate = alwaysAuthorizedGate(context),
        )

        val result = verifier.verify(aVerifierDescriptor(ciphertext, 2L), ciphertext, context)

        assertThat(result).isEqualTo(
            SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.PLAIN_LENGTH_MISMATCH
            )
        )
        assertThat(plaintext.asList()).containsExactly(0.toByte(), 0.toByte(), 0.toByte())
    }

    private fun aVerifierDescriptor(ciphertext: ByteArray, plainLength: Long): SegmentedMediaResourcePrototype {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) }
        return aSegment(index = 0, startUs = 0L, plainLength = plainLength).let { segment ->
            segment.copy(locator = segment.locator.copy(outerCipherSha256 = digest, cipherLength = ciphertext.size.toLong()))
        }
    }
}
