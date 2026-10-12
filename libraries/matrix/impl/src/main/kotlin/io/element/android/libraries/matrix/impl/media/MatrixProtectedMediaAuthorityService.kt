/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

data class ProtectedMatrixMediaInspection(
    val contentId: String,
    val kind: String,
    val policyId: String,
    val policyVersion: Long,
    val roomIdSha256Hex: String,
    val immutableContextSha256Hex: String,
    val expectedPlaintextLength: Long,
)

/** Matrix-internal bridge; it is not a decoder delivery API. */
interface MatrixProtectedMediaAuthorityService {
    fun inspect(frame: ByteArray): ProtectedMatrixMediaInspection

    suspend fun decryptToEncryptedStaging(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): SecureMediaReleaseAuthority
}

@ContributesBinding(AppScope::class)
class DefaultMatrixProtectedMediaAuthorityService(
    private val contentProtectionService: ContentProtectionService,
    private val envelopeCodec: ContentEnvelopeCodec,
    private val deviceTrustService: DeviceTrustService,
    private val burnAfterReadSessionRegistry: BurnAfterReadMediaSessionRegistry,
) : MatrixProtectedMediaAuthorityService {
    private val secureRandom = SecureRandom()

    override fun inspect(frame: ByteArray): ProtectedMatrixMediaInspection {
        val parsed = parse(frame)
        return ProtectedMatrixMediaInspection(
            contentId = parsed.envelope.header.contentId.value,
            kind = parsed.envelope.header.kind.name,
            policyId = parsed.envelope.header.policy.policyId.value,
            policyVersion = parsed.envelope.header.policy.policyVersion,
            roomIdSha256Hex = sha256Hex(parsed.roomId.encodeToByteArray()),
            immutableContextSha256Hex = parsed.envelope.header.immutableContextSha256.toHex(),
            expectedPlaintextLength = inspectChunkedPlaintextLength(parsed.ciphertext),
        )
    }

    override suspend fun decryptToEncryptedStaging(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): SecureMediaReleaseAuthority {
        val parsed = parse(frame)
        val trust = deviceTrustService.current()
        val burnSession = if (parsed.envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
            burnAfterReadSessionRegistry.get(accountIdHash, parsed.envelope.header.contentId)
                ?: throw ContentProtectionException.AccessDenied()
        } else {
            null
        }
        return contentProtectionService.decryptMediaStreamWithAuthority(
            request = ReleaseContentRequest(
                header = parsed.envelope.header,
                vaultId = vaultId,
                accountIdHash = accountIdHash,
                roomId = parsed.roomId,
                requestingInstallationId = trust.installationId,
                requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                burnSessionId = burnSession?.sessionId,
            ),
            ciphertext = ByteArrayInputStream(parsed.ciphertext),
            plaintext = plaintext,
        )
    }

    private fun parse(frame: ByteArray): Parsed = DataInputStream(ByteArrayInputStream(frame)).use { input ->
        require(input.readExact(FRAME_MAGIC.size).contentEquals(FRAME_MAGIC))
        val roomId = input.readExact(input.readBoundedLength(MAX_ROOM_ID_BYTES)).decodeToString()
        val encodedHeader = input.readExact(input.readBoundedLength(MAX_ENCODED_HEADER_BYTES))
        val envelope = try {
            envelopeCodec.decode(encodedHeader)
        } finally {
            encodedHeader.fill(0)
        }
        require(envelope.ciphertext.isEmpty())
        Parsed(roomId, envelope, input.readBytes())
    }

    private fun inspectChunkedPlaintextLength(ciphertext: ByteArray): Long =
        DataInputStream(ByteArrayInputStream(ciphertext)).use { input ->
            var total = 0L
            var finalSeen = false
            while (!finalSeen) {
                val flags = input.readUnsignedByte()
                require(flags == 0 || flags == FINAL_CHUNK_FLAG)
                val encryptedLength = input.readInt()
                require(encryptedLength in GCM_TAG_LENGTH_BYTES..MAX_ENCRYPTED_CHUNK_BYTES)
                input.readExact(encryptedLength).fill(0)
                total = Math.addExact(total, (encryptedLength - GCM_TAG_LENGTH_BYTES).toLong())
                finalSeen = flags == FINAL_CHUNK_FLAG
            }
            require(input.read() == -1)
            total
        }

    private fun DataInputStream.readBoundedLength(maximum: Int): Int = readInt().also { require(it in 0..maximum) }
    private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class Parsed(
        val roomId: String,
        val envelope: ProtectedContentEnvelope,
        val ciphertext: ByteArray,
    )

    private companion object {
        val FRAME_MAGIC = "YZMF0001".encodeToByteArray()
        const val MAX_ROOM_ID_BYTES = 4096
        const val MAX_ENCODED_HEADER_BYTES = 1024 * 1024
        const val GCM_TAG_LENGTH_BYTES = 16
        const val MAX_ENCRYPTED_CHUNK_BYTES = 64 * 1024 + GCM_TAG_LENGTH_BYTES
        const val FINAL_CHUNK_FLAG = 1
    }
}

object DisabledMatrixProtectedMediaAuthorityService : MatrixProtectedMediaAuthorityService {
    override fun inspect(frame: ByteArray): ProtectedMatrixMediaInspection = error("disabled")

    override suspend fun decryptToEncryptedStaging(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): SecureMediaReleaseAuthority = error("disabled")
}
