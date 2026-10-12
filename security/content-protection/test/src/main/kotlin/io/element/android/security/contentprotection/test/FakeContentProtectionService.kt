/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.test

import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentCipherSuite
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionHeader
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ProtectedContentEnvelope
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Test-only implementation. It intentionally performs no cryptography. */
class FakeContentProtectionService(
    private val roomLifecycleProfileKind: RoomLifecycleProfileKind = RoomLifecycleProfileKind.STANDARD,
) : ContentProtectionService {
    private val destroyedContent = mutableSetOf<ContentId>()
    val assignedRoomLifecycles = mutableListOf<Pair<String, RoomLifecycleProfileKind>>()
    val registeredCallRecords = mutableListOf<Pair<String, String>>()

    override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle = ResolvedRoomLifecycle(
        roomIdSha256 = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()),
        profileId = if (roomLifecycleProfileKind == RoomLifecycleProfileKind.STANDARD) "STANDARD_30D" else "CONFIDENTIAL_7D",
        profileKind = roomLifecycleProfileKind,
        retentionMillis = if (roomLifecycleProfileKind == RoomLifecycleProfileKind.STANDARD) {
            30L * 24 * 60 * 60_000
        } else {
            7L * 24 * 60 * 60_000
        },
        maximumOfflineMillis = if (roomLifecycleProfileKind == RoomLifecycleProfileKind.STANDARD) {
            24L * 60 * 60_000
        } else {
            60L * 60_000
        },
        recoveryPolicy = if (roomLifecycleProfileKind == RoomLifecycleProfileKind.STANDARD) {
            RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY
        } else {
            RoomLifecycleRecoveryPolicy.OLD_DEVICE_AND_ADMIN_APPROVAL
        },
        messageExpiresAtEpochMillis = Long.MAX_VALUE,
        issuedAtEpochMillis = 1,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )

    override suspend fun assignCreatedRoomLifecycle(roomId: String, profileKind: RoomLifecycleProfileKind): Boolean =
        assignedRoomLifecycles.add(roomId to profileKind)

    override suspend fun registerCallRecord(roomId: String, eventId: String): Boolean =
        registeredCallRecords.add(roomId to eventId)

    override suspend fun protectBytes(request: ProtectContentRequest, plaintext: ByteArray): ProtectedContentEnvelope =
        ProtectedContentEnvelope(
            header = headerFor(request),
            ciphertext = plaintext.copyOf(),
        )

    override suspend fun protectBytes(
        request: ProtectContentRequest, plaintext: ByteArray, beforeKmsStore: suspend () -> Unit,
    ): ProtectedContentEnvelope {
        beforeKmsStore()
        return protectBytes(request, plaintext)
    }

    override suspend fun decryptBytes(request: ReleaseContentRequest, ciphertext: ByteArray): ByteArray {
        check(request.header.contentId !in destroyedContent) { "Content access was destroyed." }
        return ciphertext.copyOf()
    }

    override suspend fun protectStream(
        request: ProtectContentRequest,
        plaintext: InputStream,
        ciphertext: OutputStream,
    ): ContentProtectionHeader = headerFor(request).also { plaintext.copyTo(ciphertext) }

    override suspend fun decryptStream(
        request: ReleaseContentRequest,
        ciphertext: InputStream,
        plaintext: OutputStream,
    ) {
        check(request.header.contentId !in destroyedContent) { "Content access was destroyed." }
        ciphertext.copyTo(plaintext)
    }

    override suspend fun destroyContentAccess(
        vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        contentId: ContentId,
        reason: ContentAccessDestroyReason,
    ): Boolean =
        destroyedContent.add(contentId)

    override suspend fun revokeLocalContent(
        vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        contentId: ContentId,
    ): Boolean = destroyedContent.add(contentId) || contentId in destroyedContent

    private fun headerFor(request: ProtectContentRequest) = ContentProtectionHeader(
        formatVersion = ContentProtectionFormat.LEGACY_DEVICE_ENVELOPE_VERSION,
        contentId = request.contentId,
        kind = request.kind,
        cipherSuite = ContentCipherSuite.AES_256_GCM_HKDF_SHA256,
        policy = request.policy,
        senderInstallationId = request.senderInstallationId,
        createdAtEpochMillis = request.createdAtEpochMillis,
        immutableContextSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 1 },
        nonce = ByteArray(ContentProtectionFormat.GCM_NONCE_SIZE_BYTES) { 1 },
        deviceShareEnvelopes = request.recipients.map {
            DeviceShareEnvelope(
                recipientInstallationId = it.installationId,
                recipientKeyId = "fake-key",
                algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
                ciphertext = byteArrayOf(1),
            )
        },
        kmsShareReference = KmsShareReference("fake-kms-share"),
        offlineLeaseMode = request.offlineLeaseMode,
        lifecycle = request.lifecycle,
    )
}
