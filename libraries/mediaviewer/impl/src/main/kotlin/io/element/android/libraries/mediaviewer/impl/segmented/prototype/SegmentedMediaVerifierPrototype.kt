/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import java.security.MessageDigest

internal fun interface SegmentedMediaOuterDecryptorPrototype {
    /** Returns the still-authenticated YZMF container, or null on outer-decryption failure. */
    fun decrypt(ciphertext: ByteArray): ByteArray?
}

internal fun interface SegmentedMediaInnerAuthenticatorPrototype {
    /** Returns plaintext only after full YZMF authentication, or null. */
    fun authenticate(
        yzmfContainer: ByteArray,
        expectedContextDigestSha256: String,
    ): ByteArray?
}

internal enum class SegmentedMediaVerificationRejectionPrototype {
    INVALID_CONTEXT,
    AUTHORIZATION_DENIED,
    WRONG_RESOURCE_KIND,
    OUTER_LENGTH_MISMATCH,
    OUTER_DIGEST_MISMATCH,
    OUTER_DECRYPTION_FAILED,
    INNER_AUTHENTICATION_FAILED,
    PLAIN_LENGTH_MISMATCH,
}

internal sealed interface SegmentedMediaVerificationResultPrototype {
    data class Verified(val segment: VerifiedSegmentBytesPrototype) : SegmentedMediaVerificationResultPrototype
    data class Rejected(val reason: SegmentedMediaVerificationRejectionPrototype) : SegmentedMediaVerificationResultPrototype
}

/** In-memory, lease-bound bytes. This prototype has no file sink and cannot expose bytes across owner/generation. */
internal class VerifiedSegmentBytesPrototype internal constructor(
    val resourceId: String,
    private val context: SegmentedMediaReadContextPrototype,
    private val authorizationGate: SegmentedMediaAuthorizationGatePrototype,
    bytes: ByteArray,
) : AutoCloseable {
    private val bytes = bytes.copyOf()
    private var closed = false

    @Synchronized
    fun read(
        requestContext: SegmentedMediaReadContextPrototype,
        offset: Int,
        length: Int,
    ): ByteArray? {
        if (closed) return null
        if (
            authorizationGate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_ADMISSION) !=
            SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        ) {
            close()
            return null
        }
        if (requestContext != context || offset < 0 || length < 0 || offset > bytes.size - length) return null
        val result = bytes.copyOfRange(offset, offset + length)
        if (
            authorizationGate.check(context, SegmentedMediaAuthorizationPurposePrototype.READ_DELIVERY) !=
            SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        ) {
            result.fill(0)
            close()
            return null
        }
        return result
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            bytes.fill(0)
            closed = true
        }
    }

    @Synchronized
    internal fun isClearedForTesting(): Boolean = closed && bytes.all { it == 0.toByte() }
}

/**
 * Prototype verification gate. Ciphertext hash, outer decrypt, and inner authentication must all finish before a
 * decoder-readable object can be constructed.
 */
internal class SegmentedMediaVerifierPrototype(
    private val outerDecryptor: SegmentedMediaOuterDecryptorPrototype,
    private val innerAuthenticator: SegmentedMediaInnerAuthenticatorPrototype,
    private val authorizationGate: SegmentedMediaAuthorizationGatePrototype,
) {
    fun verify(
        descriptor: SegmentedMediaResourcePrototype,
        ciphertext: ByteArray,
        context: SegmentedMediaReadContextPrototype,
    ): SegmentedMediaVerificationResultPrototype {
        if (context.ownerToken.isBlank() || context.leaseId.isBlank() || context.generation <= 0L) {
            return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.INVALID_CONTEXT
            )
        }
        if (!isAuthorized(context, SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_ADMISSION)) {
            return authorizationRejected()
        }
        if (descriptor.kind != SegmentedMediaResourceKindPrototype.MEDIA_SEGMENT) {
            return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.WRONG_RESOURCE_KIND
            )
        }
        if (ciphertext.size.toLong() != descriptor.locator.cipherLength) {
            return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.OUTER_LENGTH_MISMATCH
            )
        }
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        val expectedDigest = descriptor.locator.outerCipherSha256.hexToBytesOrNull()
        if (expectedDigest == null || !MessageDigest.isEqual(actualDigest, expectedDigest)) {
            return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.OUTER_DIGEST_MISMATCH
            )
        }
        if (!isAuthorized(context, SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_PROGRESS)) {
            return authorizationRejected()
        }
        val yzmfContainer = outerDecryptor.decrypt(ciphertext)
            ?: return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.OUTER_DECRYPTION_FAILED
            )
        if (!isAuthorized(context, SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_PROGRESS)) {
            yzmfContainer.fill(0)
            return authorizationRejected()
        }
        var authenticatedBytes: ByteArray? = null
        val plaintext = try {
            authenticatedBytes = innerAuthenticator.authenticate(yzmfContainer, descriptor.innerContextDigestSha256)
            authenticatedBytes?.copyOf()
        } finally {
            authenticatedBytes?.fill(0)
            yzmfContainer.fill(0)
        } ?: return SegmentedMediaVerificationResultPrototype.Rejected(
            SegmentedMediaVerificationRejectionPrototype.INNER_AUTHENTICATION_FAILED
        )
        if (!isAuthorized(context, SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_PROGRESS)) {
            plaintext.fill(0)
            return authorizationRejected()
        }
        if (plaintext.size.toLong() != descriptor.plainLength) {
            plaintext.fill(0)
            return SegmentedMediaVerificationResultPrototype.Rejected(
                SegmentedMediaVerificationRejectionPrototype.PLAIN_LENGTH_MISMATCH
            )
        }
        if (!isAuthorized(context, SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_DELIVERY)) {
            plaintext.fill(0)
            return authorizationRejected()
        }
        val verified = VerifiedSegmentBytesPrototype(descriptor.resourceId, context, authorizationGate, plaintext)
        plaintext.fill(0)
        return SegmentedMediaVerificationResultPrototype.Verified(verified)
    }

    private fun isAuthorized(
        context: SegmentedMediaReadContextPrototype,
        purpose: SegmentedMediaAuthorizationPurposePrototype,
    ): Boolean = authorizationGate.check(context, purpose) == SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED

    private fun authorizationRejected(): SegmentedMediaVerificationResultPrototype.Rejected =
        SegmentedMediaVerificationResultPrototype.Rejected(
            SegmentedMediaVerificationRejectionPrototype.AUTHORIZATION_DENIED
        )

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length != 64 || any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
