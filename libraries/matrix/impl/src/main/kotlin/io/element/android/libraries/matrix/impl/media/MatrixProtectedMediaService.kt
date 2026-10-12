/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import kotlinx.coroutines.currentCoroutineContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID

data class ProtectedMatrixMedia(val file: File, val contentId: ContentId)

data class ProtectedMatrixMediaDecryption(
    val expiresAtEpochMillis: Long,
    val revocationScope: String? = null,
)

interface MatrixProtectedMediaService {
    suspend fun openChunkReader(
        prefix: ByteArray,
        expectedRoomId: String,
        vaultId: AccountVaultId,
        accountIdHash: String,
    ): io.element.android.security.contentprotection.api.ProtectedChunkReadSession =
        throw ContentProtectionException.KmsUnavailable()

    suspend fun protect(source: File, request: ProtectContentRequest): ProtectedMatrixMedia

    /** Encrypts an authorized stream without creating a plaintext staging file. */
    suspend fun protect(
        source: InputStream,
        workingDirectory: File,
        request: ProtectContentRequest,
    ): ProtectedMatrixMedia = error("Streaming media protection is unavailable.")

    suspend fun protect(source: ByteArray, workingDirectory: File, request: ProtectContentRequest): ProtectedMatrixMedia =
        error("In-memory media protection is unavailable.")

    fun isProtected(frame: ByteArray): Boolean

    suspend fun decrypt(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): ProtectedMatrixMediaDecryption
}

@ContributesBinding(AppScope::class, binding = binding<MatrixProtectedMediaService>())
class DefaultMatrixProtectedMediaService(
    private val contentProtectionService: ContentProtectionService,
    private val envelopeCodec: ContentEnvelopeCodec,
    private val deviceTrustService: DeviceTrustService,
    private val burnAfterReadSessionRegistry: BurnAfterReadMediaSessionRegistry,
    private val authorityService: MatrixProtectedMediaAuthorityService = DisabledMatrixProtectedMediaAuthorityService,
) : MatrixProtectedMediaService, MatrixProtectedMediaAuthorityService {
    private val secureRandom = SecureRandom()

    override suspend fun protect(source: File, request: ProtectContentRequest): ProtectedMatrixMedia {
        require(source.isFile)
        return FileInputStream(source).use { protect(it, requireNotNull(source.parentFile), request) }
    }

    override suspend fun protect(source: ByteArray, workingDirectory: File, request: ProtectContentRequest): ProtectedMatrixMedia {
        require(workingDirectory.isDirectory)
        return ByteArrayInputStream(source).use { protect(it, workingDirectory, request) }
    }

    override suspend fun protect(
        source: InputStream,
        workingDirectory: File,
        request: ProtectContentRequest,
    ): ProtectedMatrixMedia {
        val id = UUID.randomUUID().toString()
        val encryptedPayload = File(workingDirectory, ".yizhe-$id.payload")
        require(request.contentId.value.matches(PROTECTED_MEDIA_CONTENT_ID_REGEX))
        val lifecycleKind = if (request.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) "burn-" else ""
        val transportKind = if (request.kind == io.element.android.security.contentprotection.api.ContentKind.STICKER) "sticker-" else ""
        val protectedFrame = File(workingDirectory, ".yizhe-$lifecycleKind$transportKind${request.contentId.value}.yzm")
        try {
            val header = FileOutputStream(encryptedPayload).use { output ->
                val submission = currentCoroutineContext()[MediaSubmissionContext]
                if (submission == null) contentProtectionService.protectStream(request, source, output)
                else contentProtectionService.protectStream(request, source, output) { submission.recordKeyStore(request.contentId.value) }
            }
            val encodedHeader = envelopeCodec.encode(ProtectedContentEnvelope(header, ByteArray(0)))
            try {
                FileOutputStream(protectedFrame).use { raw ->
                    DataOutputStream(raw).use { output ->
                        val roomId = request.roomId.encodeToByteArray()
                        require(roomId.size <= MAX_ROOM_ID_BYTES)
                        require(encodedHeader.size <= MAX_ENCODED_HEADER_BYTES)
                        output.write(FRAME_MAGIC)
                        output.writeInt(roomId.size)
                        output.write(roomId)
                        output.writeInt(encodedHeader.size)
                        output.write(encodedHeader)
                        FileInputStream(encryptedPayload).use { it.copyTo(output, STREAM_COPY_BUFFER_BYTES) }
                    }
                }
            } finally {
                encodedHeader.fill(0)
            }
            return ProtectedMatrixMedia(protectedFrame, request.contentId)
        } catch (failure: Exception) {
            protectedFrame.delete()
            if (currentCoroutineContext()[MediaSubmissionContext]?.keyStoreStarted != true) runCatchingExceptions {
                contentProtectionService.destroyContentAccess(
                    request.vaultId,
                    request.contentId,
                    io.element.android.security.contentprotection.api.ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION,
                )
            }
            throw failure
        } finally {
            encryptedPayload.delete()
        }
    }

    override fun isProtected(frame: ByteArray): Boolean =
        frame.size >= FRAME_MAGIC.size && frame.copyOfRange(0, FRAME_MAGIC.size).contentEquals(FRAME_MAGIC)

    override suspend fun openChunkReader(
        prefix: ByteArray,
        expectedRoomId: String,
        vaultId: AccountVaultId,
        accountIdHash: String,
    ): io.element.android.security.contentprotection.api.ProtectedChunkReadSession {
        require(isProtected(prefix))
        return DataInputStream(ByteArrayInputStream(prefix)).use { input ->
            require(input.readExact(FRAME_MAGIC.size).contentEquals(FRAME_MAGIC))
            val roomId = input.readExact(input.readBoundedLength(MAX_ROOM_ID_BYTES)).decodeToString()
            require(roomId == expectedRoomId)
            val encoded = input.readExact(input.readBoundedLength(MAX_ENCODED_HEADER_BYTES))
            val envelope = try { envelopeCodec.decode(encoded) } finally { encoded.fill(0) }
            require(input.read() == -1 && envelope.ciphertext.isEmpty())
            val trust = deviceTrustService.current()
            val burn = if (envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                burnAfterReadSessionRegistry.get(accountIdHash, envelope.header.contentId)
                    ?: throw ContentProtectionException.AccessDenied()
            } else null
            contentProtectionService.openChunkReadSession(
                ReleaseContentRequest(
                    header = envelope.header,
                    vaultId = vaultId,
                    accountIdHash = accountIdHash,
                    roomId = roomId,
                    requestingInstallationId = trust.installationId,
                    requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                    burnSessionId = burn?.sessionId,
                    progressiveVideoPlayback = true,
                )
            )
        }
    }

    override suspend fun decrypt(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): ProtectedMatrixMediaDecryption {
        require(isProtected(frame))
        return DataInputStream(ByteArrayInputStream(frame)).use { input ->
            require(input.readExact(FRAME_MAGIC.size).contentEquals(FRAME_MAGIC))
            val roomId = input.readExact(input.readBoundedLength(MAX_ROOM_ID_BYTES)).decodeToString()
            val encodedHeader = input.readExact(input.readBoundedLength(MAX_ENCODED_HEADER_BYTES))
            val envelope = try { envelopeCodec.decode(encodedHeader) } finally { encodedHeader.fill(0) }
            require(envelope.ciphertext.isEmpty())
            val trust = deviceTrustService.current()
            val burnSession = if (envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                burnAfterReadSessionRegistry.get(accountIdHash, envelope.header.contentId)
                    ?: throw ContentProtectionException.AccessDenied()
            } else null
            contentProtectionService.decryptStream(
                ReleaseContentRequest(
                    header = envelope.header,
                    vaultId = vaultId,
                    accountIdHash = accountIdHash,
                    roomId = roomId,
                    requestingInstallationId = trust.installationId,
                    requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                    burnSessionId = burnSession?.sessionId,
                ),
                input,
                plaintext,
            )
            ProtectedMatrixMediaDecryption(
                expiresAtEpochMillis = burnSession?.viewExpiresAtEpochMillis
                    ?.let { minOf(it, envelope.header.policy.expiresAtEpochMillis) }
                    ?: envelope.header.policy.expiresAtEpochMillis,
                revocationScope = burnSession?.sessionId ?: "content:${envelope.header.contentId.value}",
            )
        }
    }

    override fun inspect(frame: ByteArray): ProtectedMatrixMediaInspection = authorityService.inspect(frame)

    override suspend fun decryptToEncryptedStaging(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): SecureMediaReleaseAuthority = authorityService.decryptToEncryptedStaging(frame, vaultId, accountIdHash, plaintext)

    private fun DataInputStream.readBoundedLength(maximum: Int): Int = readInt().also { require(it in 0..maximum) }
    private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

    private companion object {
        val PROTECTED_MEDIA_CONTENT_ID_REGEX = Regex("[A-Za-z0-9._-]{8,96}")
        val FRAME_MAGIC = "YZMF0001".encodeToByteArray()
        const val MAX_ROOM_ID_BYTES = 4096
        const val MAX_ENCODED_HEADER_BYTES = 1024 * 1024
        const val STREAM_COPY_BUFFER_BYTES = 64 * 1024
    }
}

object DisabledMatrixProtectedMediaService : MatrixProtectedMediaService {
    override suspend fun protect(source: File, request: ProtectContentRequest): ProtectedMatrixMedia = error("disabled")
    override fun isProtected(frame: ByteArray): Boolean = false
    override suspend fun decrypt(
        frame: ByteArray,
        vaultId: AccountVaultId,
        accountIdHash: String,
        plaintext: OutputStream,
    ): ProtectedMatrixMediaDecryption = error("disabled")
}
