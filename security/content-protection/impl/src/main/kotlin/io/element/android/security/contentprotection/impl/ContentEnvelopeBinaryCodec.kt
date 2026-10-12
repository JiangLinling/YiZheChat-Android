/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.security.contentprotection.api.ContentCipherSuite
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionHeader
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.RoomEpochReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@ContributesBinding(AppScope::class)
class ContentEnvelopeBinaryCodec : ContentEnvelopeCodec {
    override fun encode(envelope: ProtectedContentEnvelope): ByteArray = try {
        val header = ContentEnvelopeBinaryFormat.encodeHeader(envelope.header)
        require(envelope.ciphertext.size <= MAX_CIPHERTEXT_BYTES)
        ByteArrayOutputStream(header.size + envelope.ciphertext.size + 32).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(ENVELOPE_MAGIC)
                output.writeInt(header.size)
                output.write(header)
                output.writeInt(envelope.ciphertext.size)
                output.write(envelope.ciphertext)
            }
            bytes.toByteArray()
        }
    } catch (failure: IllegalArgumentException) {
        throw ContentProtectionException.InvalidEnvelope(failure)
    }

    override fun decode(encoded: ByteArray): ProtectedContentEnvelope = try {
        require(encoded.size <= MAX_ENVELOPE_BYTES)
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readExact(ENVELOPE_MAGIC.size).contentEquals(ENVELOPE_MAGIC))
            val headerLength = input.readBoundedLength(MAX_HEADER_BYTES)
            val header = ContentEnvelopeBinaryFormat.decodeHeader(input.readExact(headerLength))
            val ciphertextLength = input.readBoundedLength(MAX_CIPHERTEXT_BYTES)
            val ciphertext = input.readExact(ciphertextLength)
            require(input.read() == -1)
            ProtectedContentEnvelope(header, ciphertext)
        }
    } catch (failure: ContentProtectionException.InvalidEnvelope) {
        throw failure
    } catch (failure: Exception) {
        throw ContentProtectionException.InvalidEnvelope(failure)
    }

    private companion object {
        val ENVELOPE_MAGIC = "YZCE0001".encodeToByteArray()
        const val MAX_HEADER_BYTES = 1024 * 1024
        const val MAX_CIPHERTEXT_BYTES = 256 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_HEADER_BYTES + MAX_CIPHERTEXT_BYTES + 32
    }
}

internal object ContentEnvelopeBinaryFormat {
    private val HEADER_MAGIC = "YZCH0001".encodeToByteArray()
    private val CONTEXT_MAGIC = "YZCX0001".encodeToByteArray()
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_BINARY_BYTES = 8192
    private const val MAX_RECIPIENTS = 512

    fun encodeHeader(header: ContentProtectionHeader): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(HEADER_MAGIC)
            output.writeInt(header.formatVersion)
            output.writeString(header.contentId.value)
            output.writeInt(header.kind.ordinal)
            output.writeInt(header.cipherSuite.ordinal)
            output.writeString(header.policy.policyId.value)
            output.writeLong(header.policy.policyVersion)
            output.writeLong(header.policy.expiresAtEpochMillis)
            output.writeString(header.senderInstallationId)
            if (header.formatVersion >= ROOM_EPOCH_FORMAT_VERSION) {
                val roomEpoch = requireNotNull(header.roomEpoch)
                output.writeString(roomEpoch.epochId)
                output.writeLong(roomEpoch.generation)
                output.writeBytes(roomEpoch.audienceDigestSha256)
            } else {
                output.writeStringSet(header.audienceAccountIdHashes, MAX_AUDIENCE_ACCOUNTS)
            }
            output.writeLong(header.createdAtEpochMillis)
            output.writeBytes(header.immutableContextSha256)
            output.writeBytes(header.nonce)
            output.writeInt(header.deviceShareEnvelopes.size)
            header.deviceShareEnvelopes.forEach { envelope ->
                output.writeString(envelope.recipientInstallationId)
                output.writeString(envelope.recipientKeyId)
                output.writeString(envelope.algorithm)
                output.writeBytes(envelope.ciphertext)
            }
            output.writeString(header.kmsShareReference.value)
            output.writeInt(header.offlineLeaseMode.ordinal)
            if (header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                output.writeByte(LIFECYCLE_EXTENSION_PRESENT)
                output.writeInt(header.lifecycle.mode.ordinal)
                output.writeInt(requireNotNull(header.lifecycle.conversationKind).ordinal)
            }
        }
        bytes.toByteArray()
    }

    fun decodeHeader(encoded: ByteArray): ContentProtectionHeader = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readExact(HEADER_MAGIC.size).contentEquals(HEADER_MAGIC))
        val formatVersion = input.readInt()
        val contentId = ContentId(input.readString())
        val kind = enumValue<ContentKind>(input.readInt())
        val cipherSuite = enumValue<ContentCipherSuite>(input.readInt())
        val policy = ContentPolicyReference(
            policyId = PolicyId(input.readString()),
            policyVersion = input.readLong(),
            expiresAtEpochMillis = input.readLong(),
        )
        val senderInstallationId = input.readString()
        val roomEpoch = if (formatVersion >= ROOM_EPOCH_FORMAT_VERSION) {
            RoomEpochReference(
                epochId = input.readString(),
                generation = input.readLong(),
                audienceDigestSha256 = input.readBytes(),
            )
        } else {
            null
        }
        val audienceAccountIdHashes = if (formatVersion in 2 until ROOM_EPOCH_FORMAT_VERSION) {
            input.readStringSet(MAX_AUDIENCE_ACCOUNTS)
        } else {
            emptySet()
        }
        val createdAtEpochMillis = input.readLong()
        val immutableContextSha256 = input.readBytes()
        val nonce = input.readBytes()
        val recipientCount = input.readBoundedLength(MAX_RECIPIENTS)
        val recipients = List(recipientCount) {
            DeviceShareEnvelope(
                recipientInstallationId = input.readString(),
                recipientKeyId = input.readString(),
                algorithm = input.readString(),
                ciphertext = input.readBytes(),
            )
        }
        val reference = KmsShareReference(input.readString())
        val leaseMode = enumValue<OfflineLeaseMode>(input.readInt())
        val lifecycle = if (input.available() == 0) {
            ContentLifecycle()
        } else {
            require(input.readUnsignedByte() == LIFECYCLE_EXTENSION_PRESENT)
            val mode = enumValue<MessageLifecycleMode>(input.readInt())
            require(mode == MessageLifecycleMode.BURN_AFTER_READ)
            ContentLifecycle(mode, enumValue<ConversationKind>(input.readInt()))
        }
        require(input.read() == -1)
        ContentProtectionHeader(
            formatVersion = formatVersion,
            contentId = contentId,
            kind = kind,
            cipherSuite = cipherSuite,
            policy = policy,
            senderInstallationId = senderInstallationId,
            audienceAccountIdHashes = audienceAccountIdHashes,
            createdAtEpochMillis = createdAtEpochMillis,
            immutableContextSha256 = immutableContextSha256,
            nonce = nonce,
            deviceShareEnvelopes = recipients,
            roomEpoch = roomEpoch,
            kmsShareReference = reference,
            offlineLeaseMode = leaseMode,
            lifecycle = lifecycle,
        )
    }

    fun encodeImmutableContext(
        roomId: String,
        formatVersion: Int,
        contentId: ContentId,
        kind: ContentKind,
        cipherSuite: ContentCipherSuite,
        policy: ContentPolicyReference,
        senderInstallationId: String,
        audienceAccountIdHashes: Set<String>,
        authorizedInstallationIds: Set<String>,
        roomEpoch: RoomEpochReference? = null,
        createdAtEpochMillis: Long,
        offlineLeaseMode: OfflineLeaseMode,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(CONTEXT_MAGIC)
            output.writeInt(formatVersion)
            output.writeString(roomId)
            output.writeString(contentId.value)
            output.writeInt(kind.ordinal)
            output.writeInt(cipherSuite.ordinal)
            output.writeString(policy.policyId.value)
            output.writeLong(policy.policyVersion)
            output.writeLong(policy.expiresAtEpochMillis)
            output.writeString(senderInstallationId)
            if (formatVersion >= ROOM_EPOCH_FORMAT_VERSION) {
                val epoch = requireNotNull(roomEpoch)
                output.writeString(epoch.epochId)
                output.writeLong(epoch.generation)
                output.writeBytes(epoch.audienceDigestSha256)
            } else {
                output.writeStringSet(audienceAccountIdHashes, MAX_AUDIENCE_ACCOUNTS)
                output.writeStringSet(authorizedInstallationIds, MAX_RECIPIENTS)
            }
            output.writeLong(createdAtEpochMillis)
            output.writeInt(offlineLeaseMode.ordinal)
            if (lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                output.writeByte(LIFECYCLE_EXTENSION_PRESENT)
                output.writeInt(lifecycle.mode.ordinal)
                output.writeInt(requireNotNull(lifecycle.conversationKind).ordinal)
            }
        }
        bytes.toByteArray()
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T = enumValues<T>().getOrNull(ordinal)
        ?: throw IllegalArgumentException("Unknown enum value.")

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String = readExact(readBoundedLength(MAX_STRING_BYTES)).decodeToString()

    private const val LIFECYCLE_EXTENSION_PRESENT = 1

    private fun DataOutputStream.writeStringSet(values: Set<String>, maximum: Int) {
        require(values.size <= maximum)
        writeInt(values.size)
        values.sorted().forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringSet(maximum: Int): Set<String> {
        val values = List(readBoundedLength(maximum)) { readString() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }

    private fun DataOutputStream.writeBytes(value: ByteArray) {
        require(value.size <= MAX_BINARY_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBytes(): ByteArray = readExact(readBoundedLength(MAX_BINARY_BYTES))

    private const val MAX_AUDIENCE_ACCOUNTS = 512
    private const val ROOM_EPOCH_FORMAT_VERSION = 3
}

internal fun DataInputStream.readBoundedLength(maximum: Int): Int = readInt().also { require(it in 0..maximum) }

internal fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)
