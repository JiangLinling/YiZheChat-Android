/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.HistoricalMediaSendPrepared
import io.element.android.security.contentprotection.api.MediaSendJournal
import io.element.android.security.contentprotection.api.MediaSendJournalCas
import io.element.android.security.contentprotection.api.MediaSendJournalRecord
import io.element.android.security.contentprotection.api.MediaSendJournalState
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedMediaSendJournal(
    private val sensitiveFileStore: SensitiveFileStore,
    private val sessionStore: SessionStore,
    private val durableStore: DurableMediaSendStateStore,
) : MediaSendJournal {
    override suspend fun persistPrepared(prepared: HistoricalMediaSendPrepared): Result<MediaSendJournalRecord> = runCatching {
        validatePrepared(prepared)
        val session = requireSession(prepared.matrixUserId, prepared.matrixDeviceId, prepared.accountIdHash)
        val vault = deriveAccountVaultId(session.userId, session.deviceId)
        val namespace = namespace(vault)
        val key = operationKey(prepared.operationId, prepared.requestId, prepared.transactionId)
        durableStore.withLockedState(namespace, key) { current, commit ->
            require(current == null) { "PREPARED already exists" }
            publish(
                vault = vault,
                key = key,
                record = unsignedRecord(1, MediaSendJournalState.PREPARED, prepared),
                commit = commit,
            )
        }
    }

    override suspend fun load(operationId: String, requestId: String, transactionId: String): Result<MediaSendJournalRecord> = runCatching {
        val session = requireNotNull(sessionStore.getLatestSession()).also { require(it.isTokenValid) }
        val vault = deriveAccountVaultId(session.userId, session.deviceId)
        loadLocked(vault, operationKey(operationId, requestId, transactionId)).also { record ->
            require(record.prepared.matrixUserId == session.userId && record.prepared.matrixDeviceId == session.deviceId)
            require(record.prepared.operationId == operationId && record.prepared.requestId == requestId)
            require(record.prepared.transactionId == transactionId)
        }
    }

    override suspend fun compareAndSet(request: MediaSendJournalCas): Result<MediaSendJournalRecord> = runCatching {
        val session = requireNotNull(sessionStore.getLatestSession()).also { require(it.isTokenValid) }
        val vault = deriveAccountVaultId(session.userId, session.deviceId)
        val namespace = namespace(vault)
        val key = operationKey(request.operationId, request.requestId, request.transactionId)
        durableStore.withLockedState(namespace, key) { pointerState, commit ->
            val current = readRecord(requireNotNull(pointerState), vault)
            require(current.revision == request.expectedRevision)
            require(equalDigest(current.recordDigestSha256, request.expectedRecordDigestSha256))
            require(current.state == request.expectedState)
            require(isAllowedTransition(current.state, request.nextState))
            require(current.prepared.operationId == request.operationId)
            require(current.prepared.requestId == request.requestId)
            require(current.prepared.transactionId == request.transactionId)
            val nextRevision = Math.addExact(current.revision, 1)
            val next = unsignedRecord(
                revision = nextRevision,
                state = request.nextState,
                prepared = current.prepared,
                allowEnvelope = request.allowEnvelope ?: current.allowEnvelope,
                sendBoundEnvelope = request.sendBoundEnvelope ?: current.sendBoundEnvelope,
                matrixEventId = request.matrixEventId ?: current.matrixEventId,
            )
            validateTransitionPayload(current, next)
            publish(vault, key, next, commit).also {
                pointerState.payload.decodePointer().reference.let { old -> runCatching { sensitiveFileStore.delete(old) } }
            }
        }
    }

    override suspend fun pendingRecovery(limit: Int): Result<List<MediaSendJournalRecord>> = runCatching {
        require(limit in 1..128)
        val session = requireNotNull(sessionStore.getLatestSession()).also { require(it.isTokenValid) }
        val vault = deriveAccountVaultId(session.userId, session.deviceId)
        buildList {
            for (key in durableStore.keys(namespace(vault))) {
                val record = runCatching { loadLocked(vault, key) }.getOrNull() ?: continue
                if (record.state == MediaSendJournalState.MATRIX_IN_FLIGHT ||
                    record.state == MediaSendJournalState.MATRIX_UNCERTAIN
                ) add(record)
                if (size == limit) break
            }
        }
    }

    private suspend fun loadLocked(vault: AccountVaultId, key: String): MediaSendJournalRecord =
        durableStore.withLockedState(namespace(vault), key) { state, _ -> readRecord(requireNotNull(state), vault) }

    private suspend fun readRecord(
        pointerState: DurableMediaSendStateStore.StoredState,
        vault: AccountVaultId,
    ): MediaSendJournalRecord {
        val pointer = pointerState.payload.decodePointer()
        require(pointer.reference.vaultId == vault)
        val encoded = sensitiveFileStore.read(pointer.reference) { it.readBytes() }
        require(equalDigest(sha256Hex(encoded), pointer.recordDigestSha256))
        val record = decodeRecord(encoded, pointer.recordDigestSha256)
        require(record.revision == pointerState.revision)
        return record
    }

    private suspend fun publish(
        vault: AccountVaultId,
        key: String,
        record: MediaSendJournalRecord,
        commit: (DurableMediaSendStateStore.StoredState) -> Unit,
    ): MediaSendJournalRecord {
        val encoded = encodeRecord(record)
        val digest = sha256Hex(encoded)
        val finalized = record.copy(
            recordDigestSha256 = digest,
            allowEnvelopeSha256 = record.allowEnvelope?.let(::sha256Hex),
            sendBoundEnvelopeSha256 = record.sendBoundEnvelope?.let(::sha256Hex),
        )
        val finalizedBytes = encodeRecord(finalized)
        val finalDigest = sha256Hex(finalizedBytes)
        val identity = SecureObjectId("media-send-$key-${record.revision}-${UUID.randomUUID()}")
        val reference = sensitiveFileStore.write(
            SecureObjectWriteRequest(
                objectId = identity,
                vaultId = vault,
                purpose = SecureObjectPurpose.OFFLINE_OUTBOX,
                associatedData = "media-send-journal-v1\u0000$key\u0000${record.revision}".encodeToByteArray(),
                expectedPlaintextLength = finalizedBytes.size.toLong(),
            )
        ) { it.write(finalizedBytes) }
        val pointerPayload = Pointer(reference, finalDigest).encode()
        commit(DurableMediaSendStateStore.StoredState(record.revision, sha256Hex(pointerPayload), pointerPayload))
        return finalized.copy(recordDigestSha256 = finalDigest)
    }

    private suspend fun requireSession(userId: String, deviceId: String, accountHash: String): SessionData {
        val session = requireNotNull(sessionStore.getSession(userId))
        require(session.deviceId == deviceId && session.isTokenValid)
        require(equalDigest(sha256Hex(userId.encodeToByteArray()), accountHash))
        return session
    }

    private fun validatePrepared(value: HistoricalMediaSendPrepared) {
        require(listOf(value.operationId, value.requestId, value.transactionId, value.assetId, value.contentContractId)
            .all { it.matches(INTERNAL_ID) })
        require(value.roomId.startsWith("!") && ':' in value.roomId)
        require(value.mediaVersion > 0 && value.deviceTrustGeneration > 0 && value.roomEpoch > 0)
        require(value.policyVersion > 0 && value.kmsFenceGeneration > 0 && value.resourceRootGeneration > 0)
        require(listOf(value.resourceSetDigestSha256, value.manifestDigestSha256, value.pagesDigestSha256,
            value.accountIdHash, value.policyDigestSha256, value.eventContentDigestSha256).all { it.matches(SHA256_HEX) })
        require(value.canonicalEventContentJson.encodeToByteArray().size in 1..MAX_EVENT_BYTES)
        require(sha256Hex(value.canonicalEventContentJson.encodeToByteArray()) == value.eventContentDigestSha256)
    }

    private fun validateTransitionPayload(current: MediaSendJournalRecord, next: MediaSendJournalRecord) {
        require(next.prepared == current.prepared)
        if (next.state.ordinal >= MediaSendJournalState.ALLOW_VERIFIED.ordinal) requireNotNull(next.allowEnvelope)
        if (next.state.ordinal >= MediaSendJournalState.SEND_BOUND_VERIFIED.ordinal) requireNotNull(next.sendBoundEnvelope)
        if (next.state == MediaSendJournalState.MATRIX_IN_FLIGHT) {
            require(next.matrixEventId == null)
            require(next.sendBoundEnvelope.contentEquals(current.sendBoundEnvelope ?: next.sendBoundEnvelope))
        }
        if (next.state == MediaSendJournalState.MATRIX_ACCEPTED ||
            next.state == MediaSendJournalState.ACTIVATION_PENDING ||
            next.state == MediaSendJournalState.ACTIVE
        ) require(!next.matrixEventId.isNullOrBlank())
    }

    private fun unsignedRecord(
        revision: Long,
        state: MediaSendJournalState,
        prepared: HistoricalMediaSendPrepared,
        allowEnvelope: ByteArray? = null,
        sendBoundEnvelope: ByteArray? = null,
        matrixEventId: String? = null,
    ) = MediaSendJournalRecord(
        revision, ZERO_DIGEST, state, prepared,
        allowEnvelope, allowEnvelope?.let(::sha256Hex),
        sendBoundEnvelope, sendBoundEnvelope?.let(::sha256Hex), matrixEventId,
    )

    private fun namespace(vault: AccountVaultId) = "journal-${sha256Hex(vault.value.encodeToByteArray()).take(32)}"
    private fun operationKey(operationId: String, requestId: String, transactionId: String): String {
        require(listOf(operationId, requestId, transactionId).all { it.matches(INTERNAL_ID) })
        return sha256Hex("$operationId\u0000$requestId\u0000$transactionId".encodeToByteArray())
    }

    private data class Pointer(val reference: SecureObjectReference, val recordDigestSha256: String)

    private fun Pointer.encode(): ByteArray = binaryOutput { out ->
        out.writeInt(POINTER_VERSION)
        out.writeUTF(reference.objectId.value); out.writeUTF(reference.vaultId.value)
        out.writeInt(reference.purpose.formatCode); out.writeUTF(reference.keyEnvelopeId.value)
        out.writeInt(reference.formatVersion); out.writeLong(reference.plaintextLength); out.writeLong(reference.ciphertextLength)
        out.writeUTF(reference.associatedDataSha256); out.writeUTF(recordDigestSha256)
    }

    private fun ByteArray.decodePointer(): Pointer = DataInputStream(ByteArrayInputStream(this)).use { input ->
        require(input.readInt() == POINTER_VERSION)
        val reference = SecureObjectReference(
            SecureObjectId(input.readUTF()), AccountVaultId(input.readUTF()),
            requireNotNull(SecureObjectPurpose.fromFormatCode(input.readInt())), KeyEnvelopeId(input.readUTF()),
            input.readInt(), input.readLong(), input.readLong(), input.readUTF(),
        )
        val digest = input.readUTF().also { require(it.matches(SHA256_HEX)) }
        require(input.read() == -1)
        Pointer(reference, digest)
    }

    private fun encodeRecord(record: MediaSendJournalRecord): ByteArray = binaryOutput { out ->
        out.writeInt(RECORD_VERSION); out.writeLong(record.revision); out.writeInt(record.state.ordinal)
        writePrepared(out, record.prepared)
        out.writeNullableBytes(record.allowEnvelope); out.writeNullableBytes(record.sendBoundEnvelope)
        out.writeNullableUtf(record.matrixEventId)
    }

    private fun decodeRecord(bytes: ByteArray, digest: String): MediaSendJournalRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == RECORD_VERSION)
            val revision = input.readLong().also { require(it > 0) }
            val state = MediaSendJournalState.entries[input.readInt()]
            val prepared = readPrepared(input)
            val allow = input.readNullableBytes(); val bound = input.readNullableBytes(); val eventId = input.readNullableUtf()
            require(input.read() == -1)
            MediaSendJournalRecord(revision, digest, state, prepared, allow, allow?.let(::sha256Hex), bound, bound?.let(::sha256Hex), eventId)
        }

    private fun writePrepared(out: DataOutputStream, p: HistoricalMediaSendPrepared) = with(out) {
        listOf(p.operationId,p.requestId,p.transactionId,p.roomId,p.assetId).forEach(::writeUTF); writeLong(p.mediaVersion)
        listOf(p.resourceSetDigestSha256,p.manifestDigestSha256,p.pagesDigestSha256,p.accountIdHash,p.installationId,
            p.matrixUserId,p.matrixDeviceId).forEach(::writeUTF)
        writeLong(p.deviceTrustGeneration); writeLong(p.roomEpoch); writeUTF(p.contentContractId)
        writeUTF(p.policyDomain); writeUTF(p.policyRevisionId); writeLong(p.policyVersion); writeUTF(p.policyDigestSha256)
        writeLong(p.kmsFenceGeneration); writeLong(p.resourceRootGeneration); writeUTF(p.eventContentDigestSha256)
        writeBoundedUtf8(p.canonicalEventContentJson)
    }

    private fun readPrepared(input: DataInputStream) = HistoricalMediaSendPrepared(
        input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),
        input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),
        input.readLong(),input.readLong(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readUTF(),
        input.readLong(),input.readLong(),input.readUTF(),input.readBoundedUtf8(),
    ).also(::validatePrepared)

    private fun binaryOutput(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(block); bytes.toByteArray()
    }

    private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
        writeInt(value?.size ?: -1); if (value != null) write(value)
    }
    private fun DataInputStream.readNullableBytes(): ByteArray? = readInt().let { size ->
        if (size == -1) null else ByteArray(size.also { require(it in 0..MAX_EVENT_BYTES) }).also(::readFully)
    }
    private fun DataOutputStream.writeNullableUtf(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.writeBoundedUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size in 1..MAX_EVENT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }
    private fun DataInputStream.readBoundedUtf8(): String {
        val size = readInt().also { require(it in 1..MAX_EVENT_BYTES) }
        return ByteArray(size).also(::readFully).decodeToString()
    }

    private companion object {
        const val POINTER_VERSION = 1
        const val RECORD_VERSION = 1
        const val MAX_EVENT_BYTES = 1_048_576
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        val INTERNAL_ID = Regex("^[A-Za-z0-9._:-]{1,160}$")
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

private fun equalDigest(left: String, right: String): Boolean =
    left.matches(Regex("^[0-9a-f]{64}$")) && right.matches(Regex("^[0-9a-f]{64}$")) &&
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

private fun isAllowedTransition(from: MediaSendJournalState, to: MediaSendJournalState): Boolean = when (from) {
    MediaSendJournalState.PREPARED -> to == MediaSendJournalState.ALLOW_VERIFIED
    MediaSendJournalState.ALLOW_VERIFIED -> to == MediaSendJournalState.SEND_BOUND_VERIFIED
    MediaSendJournalState.SEND_BOUND_VERIFIED -> to == MediaSendJournalState.MATRIX_IN_FLIGHT
    MediaSendJournalState.MATRIX_IN_FLIGHT -> to == MediaSendJournalState.MATRIX_UNCERTAIN || to == MediaSendJournalState.MATRIX_ACCEPTED
    MediaSendJournalState.MATRIX_UNCERTAIN -> to == MediaSendJournalState.MATRIX_ACCEPTED || to == MediaSendJournalState.CLEANUP_REQUIRED
    MediaSendJournalState.MATRIX_ACCEPTED -> to == MediaSendJournalState.ACTIVATION_PENDING
    MediaSendJournalState.ACTIVATION_PENDING -> to == MediaSendJournalState.ACTIVE || to == MediaSendJournalState.CLEANUP_REQUIRED
    MediaSendJournalState.ACTIVE, MediaSendJournalState.CLEANUP_REQUIRED -> false
}
