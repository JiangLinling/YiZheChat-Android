/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageRoots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

internal data class InstallationIdentity(
    val value: String,
    val sha256: ByteArray,
)

internal enum class DeviceRootKeyRecordState(val formatCode: Int) {
    ACTIVE(1),
    DESTROYED(2),
}

internal data class DeviceRootKeyRecord(
    val installationIdSha256: ByteArray,
    val keyVersion: Long,
    val state: DeviceRootKeyRecordState,
    val strongBoxRequested: Boolean,
)

@SingleIn(AppScope::class)
@Inject
class InstallationIdentityStore(
    private val roots: SecureStorageRoots,
) {
    private val secureRandom = SecureRandom()

    internal fun loadOrCreate(): InstallationIdentity {
        val file = identityFile()
        val existing = AtomicSecureRecordIO.read(file, MAX_RECORD_LENGTH_BYTES)
        if (existing != null) return decode(existing)
        val identityBytes = ByteArray(INSTALLATION_ID_LENGTH_BYTES).also(secureRandom::nextBytes)
        val encoded = encode(identityBytes)
        if (!AtomicSecureRecordIO.create(file, encoded)) return decode(requireNotNull(AtomicSecureRecordIO.read(file, MAX_RECORD_LENGTH_BYTES)))
        return identity(identityBytes)
    }

    private fun identityFile(): File = File(roots.persistentRoot(), "$DEVICE_ROOT_DIRECTORY/$IDENTITY_FILE_NAME")

    private fun encode(identityBytes: ByteArray): ByteArray = withChecksum(
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(INSTALLATION_MAGIC)
                output.writeInt(INSTALLATION_FORMAT_VERSION)
                output.writeInt(identityBytes.size)
                output.write(identityBytes)
            }
            bytes.toByteArray()
        }
    )

    private fun decode(bytes: ByteArray): InstallationIdentity = authenticateRecord {
        val payload = verifyAndStripChecksum(bytes)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(INSTALLATION_MAGIC.size).contentEquals(INSTALLATION_MAGIC))
            require(input.readInt() == INSTALLATION_FORMAT_VERSION)
            require(input.readInt() == INSTALLATION_ID_LENGTH_BYTES)
            val identityBytes = input.readExact(INSTALLATION_ID_LENGTH_BYTES)
            require(input.read() == -1)
            identity(identityBytes)
        }
    }

    private fun identity(bytes: ByteArray): InstallationIdentity = InstallationIdentity(
        value = bytes.toHex(),
        sha256 = sha256(bytes),
    )
}

@SingleIn(AppScope::class)
@Inject
class DeviceRootKeyRecordStore(
    private val roots: SecureStorageRoots,
) {
    internal fun read(): DeviceRootKeyRecord? = AtomicSecureRecordIO.read(recordFile(), MAX_RECORD_LENGTH_BYTES)?.let(::decode)

    internal fun create(record: DeviceRootKeyRecord): Boolean = AtomicSecureRecordIO.create(recordFile(), encode(record))

    internal fun markDestroyed(record: DeviceRootKeyRecord) {
        AtomicSecureRecordIO.replace(recordFile(), encode(record.copy(state = DeviceRootKeyRecordState.DESTROYED)))
    }

    private fun recordFile(): File = File(roots.persistentRoot(), "$DEVICE_ROOT_DIRECTORY/$ROOT_KEY_RECORD_FILE_NAME")

    private fun encode(record: DeviceRootKeyRecord): ByteArray = withChecksum(
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(ROOT_KEY_MAGIC)
                output.writeInt(ROOT_KEY_RECORD_FORMAT_VERSION)
                output.write(record.installationIdSha256.requireLength(SHA256_LENGTH_BYTES))
                output.writeLong(record.keyVersion)
                output.writeInt(record.state.formatCode)
                output.writeBoolean(record.strongBoxRequested)
            }
            bytes.toByteArray()
        }
    )

    private fun decode(bytes: ByteArray): DeviceRootKeyRecord = authenticateRecord {
        val payload = verifyAndStripChecksum(bytes)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(ROOT_KEY_MAGIC.size).contentEquals(ROOT_KEY_MAGIC))
            require(input.readInt() == ROOT_KEY_RECORD_FORMAT_VERSION)
            val installationIdSha256 = input.readExact(SHA256_LENGTH_BYTES)
            val keyVersion = input.readLong()
            val stateCode = input.readInt()
            val strongBoxRequested = input.readBoolean()
            require(input.read() == -1)
            require(keyVersion > 0)
            val state = requireNotNull(DeviceRootKeyRecordState.entries.firstOrNull { it.formatCode == stateCode })
            DeviceRootKeyRecord(installationIdSha256, keyVersion, state, strongBoxRequested)
        }
    }
}

internal const val DEVICE_ROOT_DIRECTORY = "device-root"
internal const val DEVICE_ROOT_KEY_VERSION = 1L
private const val INSTALLATION_FORMAT_VERSION = 1
private const val ROOT_KEY_RECORD_FORMAT_VERSION = 1
private const val INSTALLATION_ID_LENGTH_BYTES = 32
private const val SHA256_LENGTH_BYTES = 32
private const val CHECKSUM_LENGTH_BYTES = 32
private const val MAX_RECORD_LENGTH_BYTES = 1024L
private const val IDENTITY_FILE_NAME = "installation-id.yzid"
private const val ROOT_KEY_RECORD_FILE_NAME = "device-root.yzdrk"
private val INSTALLATION_MAGIC = "YZID0001".encodeToByteArray()
private val ROOT_KEY_MAGIC = "YZDR0001".encodeToByteArray()

private fun withChecksum(payload: ByteArray): ByteArray = payload + sha256(payload)

private fun verifyAndStripChecksum(bytes: ByteArray): ByteArray {
    require(bytes.size > CHECKSUM_LENGTH_BYTES)
    val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_LENGTH_BYTES)
    val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_LENGTH_BYTES, bytes.size)
    require(MessageDigest.isEqual(checksum, sha256(payload)))
    return payload
}

private inline fun <T> authenticateRecord(block: () -> T): T = try {
    block()
} catch (failure: SecureObjectAuthenticationException) {
    throw failure
} catch (failure: Exception) {
    throw SecureObjectAuthenticationException(failure)
}

private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

private fun ByteArray.requireLength(expectedLength: Int): ByteArray = apply { require(size == expectedLength) }
