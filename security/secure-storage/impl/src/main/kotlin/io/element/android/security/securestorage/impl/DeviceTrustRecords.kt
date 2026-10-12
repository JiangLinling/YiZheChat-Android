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
import io.element.android.security.securestorage.api.DeviceRiskSignal
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageRoots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

internal data class DeviceTrustRecord(
    val installationIdSha256: ByteArray,
    val attestationPublicKeySha256: ByteArray,
    val appSigningCertificateSha256: List<ByteArray>,
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val trustState: DeviceTrustState,
    val attestationVersion: Long,
    val lastEvidenceAtEpochMillis: Long?,
    val lastVerifiedAtEpochMillis: Long?,
    val riskSignals: Set<DeviceRiskSignal>,
    val managedLicense: ManagedDeviceLicense? = null,
)

@SingleIn(AppScope::class)
@Inject
class DeviceTrustRecordStore(
    private val roots: SecureStorageRoots,
) {
    internal fun read(): DeviceTrustRecord? = AtomicSecureRecordIO.read(recordFile(), MAX_RECORD_LENGTH_BYTES)?.let(::decode)

    internal fun write(record: DeviceTrustRecord) {
        val encoded = encode(record)
        if (!AtomicSecureRecordIO.create(recordFile(), encoded)) AtomicSecureRecordIO.replace(recordFile(), encoded)
    }

    private fun recordFile(): File = File(roots.persistentRoot(), "$DEVICE_ROOT_DIRECTORY/$TRUST_RECORD_FILE_NAME")

    private fun encode(record: DeviceTrustRecord): ByteArray = withChecksum(
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(TRUST_RECORD_MAGIC)
                output.writeInt(TRUST_RECORD_FORMAT_VERSION)
                output.write(record.installationIdSha256.requireLength(SHA256_LENGTH_BYTES))
                output.write(record.attestationPublicKeySha256.requireLength(SHA256_LENGTH_BYTES))
                output.writeInt(record.appSigningCertificateSha256.size)
                record.appSigningCertificateSha256.sortedBy(ByteArray::toHex).forEach {
                    output.write(it.requireLength(SHA256_LENGTH_BYTES))
                }
                output.writeInt(record.hardwareSecurityLevel.ordinal)
                output.writeInt(record.trustState.ordinal)
                output.writeLong(record.attestationVersion)
                output.writeLong(record.lastEvidenceAtEpochMillis ?: NO_TIMESTAMP)
                output.writeLong(record.lastVerifiedAtEpochMillis ?: NO_TIMESTAMP)
                output.writeInt(record.riskSignals.size)
                record.riskSignals.sortedBy(DeviceRiskSignal::ordinal).forEach { output.writeInt(it.ordinal) }
                output.writeBoolean(record.managedLicense != null)
                record.managedLicense?.let { license ->
                    output.writeUTF(license.licenseId)
                    output.writeUTF(license.installationId)
                    output.writeUTF(license.devicePublicKeySha256)
                    output.writeUTF(license.contentShareKeyId)
                    output.writeUTF(license.appPackageName)
                    output.writeLong(license.appVersionCode)
                    output.writeLong(license.revision)
                    output.writeInt(license.allowedScopes.size)
                    license.allowedScopes.sorted().forEach(output::writeUTF)
                    output.writeInt(license.accountIdHashes.size)
                    license.accountIdHashes.sorted().forEach(output::writeUTF)
                    output.writeBoolean(license.offlineAllowed)
                    output.writeLong(license.issuedAtEpochMillis)
                    output.writeLong(license.expiresAtEpochMillis)
                }
            }
            bytes.toByteArray()
        }
    )

    private fun decode(bytes: ByteArray): DeviceTrustRecord = authenticateTrustRecord {
        val payload = verifyAndStripChecksum(bytes)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(TRUST_RECORD_MAGIC.size).contentEquals(TRUST_RECORD_MAGIC))
            val formatVersion = input.readInt().also { require(it in MIN_TRUST_RECORD_FORMAT_VERSION..TRUST_RECORD_FORMAT_VERSION) }
            val installationIdSha256 = input.readExact(SHA256_LENGTH_BYTES)
            val publicKeySha256 = input.readExact(SHA256_LENGTH_BYTES)
            val signingCount = input.readInt().also { require(it in 0..MAX_SIGNING_CERTIFICATES) }
            val signers = List(signingCount) { input.readExact(SHA256_LENGTH_BYTES) }
            val hardwareLevel = HardwareSecurityLevel.entries[input.readInt().also { require(it in HardwareSecurityLevel.entries.indices) }]
            val trustState = DeviceTrustState.entries[input.readInt().also { require(it in DeviceTrustState.entries.indices) }]
            val attestationVersion = input.readLong().also { require(it > 0) }
            val lastEvidence = input.readLong().toNullableTimestamp()
            val lastVerified = input.readLong().toNullableTimestamp()
            val riskCount = input.readInt().also { require(it in 0..DeviceRiskSignal.entries.size) }
            val risks = buildSet {
                repeat(riskCount) {
                    val ordinal = input.readInt().also { require(it in DeviceRiskSignal.entries.indices) }
                    add(DeviceRiskSignal.entries[ordinal])
                }
            }
            val managedLicense = if (formatVersion >= 2 && input.readBoolean()) {
                val licenseId = input.readUTF()
                val installationId = input.readUTF()
                val devicePublicKeySha256 = input.readUTF()
                val contentShareKeyId = input.readUTF()
                val appPackageName = input.readUTF()
                val appVersionCode = input.readLong()
                val revision = input.readLong()
                val allowedScopes = buildSet {
                    val scopeCount = input.readInt().also { require(it in 1..MAX_MANAGED_SCOPES) }
                    repeat(scopeCount) { add(input.readUTF()) }
                }
                val accountIdHashes = if (formatVersion >= 3) {
                    buildSet {
                        val accountCount = input.readInt().also { require(it in 0..MAX_MANAGED_ACCOUNTS) }
                        repeat(accountCount) { add(input.readUTF()) }
                    }
                } else {
                    emptySet()
                }
                ManagedDeviceLicense(
                    licenseId = licenseId,
                    installationId = installationId,
                    devicePublicKeySha256 = devicePublicKeySha256,
                    contentShareKeyId = contentShareKeyId,
                    appPackageName = appPackageName,
                    appVersionCode = appVersionCode,
                    revision = revision,
                    allowedScopes = allowedScopes,
                    offlineAllowed = input.readBoolean(),
                    issuedAtEpochMillis = input.readLong(),
                    expiresAtEpochMillis = input.readLong(),
                    accountIdHashes = accountIdHashes,
                )
            } else {
                null
            }
            require(input.read() == -1)
            DeviceTrustRecord(
                installationIdSha256,
                publicKeySha256,
                signers,
                hardwareLevel,
                trustState,
                attestationVersion,
                lastEvidence,
                lastVerified,
                risks,
                managedLicense,
            )
        }
    }

    private fun withChecksum(payload: ByteArray): ByteArray = payload + sha256(payload)

    private fun verifyAndStripChecksum(bytes: ByteArray): ByteArray {
        require(bytes.size > SHA256_LENGTH_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - SHA256_LENGTH_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - SHA256_LENGTH_BYTES, bytes.size)
        require(MessageDigest.isEqual(checksum, sha256(payload)))
        return payload
    }

    private fun Long.toNullableTimestamp(): Long? = takeUnless { it == NO_TIMESTAMP }?.also { require(it >= 0) }

    private fun ByteArray.requireLength(expected: Int): ByteArray = apply { require(size == expected) }

    private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

    private inline fun <T> authenticateTrustRecord(block: () -> T): T = try {
        block()
    } catch (failure: SecureObjectAuthenticationException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureObjectAuthenticationException(failure)
    }

    private companion object {
        const val MIN_TRUST_RECORD_FORMAT_VERSION = 1
        const val TRUST_RECORD_FORMAT_VERSION = 3
        const val SHA256_LENGTH_BYTES = 32
        const val MAX_SIGNING_CERTIFICATES = 16
        const val MAX_MANAGED_SCOPES = 8
        const val MAX_MANAGED_ACCOUNTS = 32
        const val MAX_RECORD_LENGTH_BYTES = 16_384L
        const val NO_TIMESTAMP = -1L
        const val TRUST_RECORD_FILE_NAME = "device-trust.yzdt"
        val TRUST_RECORD_MAGIC = "YZDT0001".encodeToByteArray()
    }
}
