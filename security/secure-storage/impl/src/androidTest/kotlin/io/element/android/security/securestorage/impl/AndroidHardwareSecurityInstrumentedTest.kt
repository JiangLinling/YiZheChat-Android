/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

class AndroidHardwareSecurityInstrumentedTest {
    private companion object {
        const val SECURITY_TEST_TAG = "YiZheSecurityTest"
        val GOOGLE_ATTESTATION_ROOTS = setOf(
            "cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc",
            "6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0",
        )
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val secureRandom = SecureRandom()

    @Test
    fun deviceRootKeyIsHardwareBackedAndPerformsAuthenticatedWrapping() {
        val backend = AndroidDeviceRootKeyBackend(context)
        val alias = "yizhe.test.device-root.${UUID.randomUUID()}"
        val plaintextKey = ByteArray(32).also(secureRandom::nextBytes)
        val associatedData = ByteArray(32).also(secureRandom::nextBytes)
        try {
            val descriptor = backend.create(alias)
            assertThat(descriptor.hardwareSecurityLevel).isAnyOf(
                HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                HardwareSecurityLevel.STRONGBOX,
            )

            val wrapped = backend.wrap(alias, plaintextKey, associatedData)
            assertThat(wrapped.ciphertext.contentEquals(plaintextKey)).isFalse()
            assertThat(backend.unwrap(alias, wrapped, associatedData)).isEqualTo(plaintextKey)
        } finally {
            plaintextKey.fill(0)
            associatedData.fill(0)
            assertThat(backend.destroy(alias)).isTrue()
        }
    }

    @Test
    fun deviceAttestationKeyHasHardwareCertificateChainAndVerifiableSignature() {
        val backend = AndroidDeviceAttestationBackend(context)
        val alias = "yizhe.test.device-attestation.${UUID.randomUUID()}"
        val challenge = ByteArray(32).also(secureRandom::nextBytes)
        val statement = ByteArray(64).also(secureRandom::nextBytes)
        try {
            val descriptor = backend.create(alias, challenge)
            assertThat(descriptor.hardwareSecurityLevel).isAnyOf(
                HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                HardwareSecurityLevel.STRONGBOX,
            )
            assertThat(descriptor.hasHardwareAttestation).isTrue()
            assertThat(descriptor.certificateChainDer.size).isGreaterThan(1)

            val certificates = descriptor.certificateChainDer.map {
                CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate
            }
            certificates.zipWithNext().forEach { (certificate, issuer) ->
                certificate.verify(issuer.publicKey)
            }
            val root = certificates.last()
            root.verify(root.publicKey)
            val rootSha256 = MessageDigest.getInstance("SHA-256").digest(root.encoded).toHexString()
            Log.i(
                SECURITY_TEST_TAG,
                "attestation_chain_length=${certificates.size} " +
                    "attestation_root_sha256=$rootSha256 " +
                    "google_trust_anchor=${rootSha256 in GOOGLE_ATTESTATION_ROOTS}",
            )

            val leaf = certificates.first()
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(leaf.publicKey.encoded))
            val signature = backend.sign(alias, statement)
            assertThat(
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(statement)
                    verify(signature)
                }
            ).isTrue()
            signature.fill(0)
        } finally {
            challenge.fill(0)
            statement.fill(0)
            assertThat(backend.destroy(alias)).isTrue()
        }
    }
}
