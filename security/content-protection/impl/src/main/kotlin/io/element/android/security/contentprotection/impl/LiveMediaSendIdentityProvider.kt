/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.LiveMediaSendIdentity
import io.element.android.security.contentprotection.api.LiveMediaSendIdentityProvider
import io.element.android.security.securestorage.api.DeviceTrustService
import java.security.MessageDigest

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultLiveMediaSendIdentityProvider(
    private val sessionStore: SessionStore,
    private val deviceTrustService: DeviceTrustService,
    private val contentProtectionService: ContentProtectionService,
) : LiveMediaSendIdentityProvider {
    override suspend fun current(
        matrixUserId: String,
        roomId: String,
        expectedAccountIdHash: String,
    ): Result<LiveMediaSendIdentity> = runCatching {
        require(matrixUserId.isNotBlank() && roomId.startsWith("!") && ':' in roomId)
        val session = requireNotNull(sessionStore.getSession(matrixUserId))
        require(session.userId == matrixUserId && session.deviceId.isNotBlank() && session.isTokenValid)
        val accountHash = sha256Hex(session.userId)
        require(MessageDigest.isEqual(accountHash.encodeToByteArray(), expectedAccountIdHash.encodeToByteArray()))
        val trust = deviceTrustService.current()
        require(trust.installationId.isNotBlank() && trust.attestationVersion > 0)
        require(trust.allowsLocalVaultAccess && trust.remotelyTrusted)
        val lifecycle = contentProtectionService.resolveRoomLifecycle(roomId)
        val roomHash = sha256Hex(roomId)
        require(MessageDigest.isEqual(lifecycle.roomIdSha256, roomHash.hexToBytes()))
        LiveMediaSendIdentity(
            accountIdHash = accountHash,
            installationId = trust.installationId,
            matrixUserId = session.userId,
            matrixDeviceId = session.deviceId,
            attestationVersion = trust.attestationVersion,
            roomId = roomId,
            roomIdSha256 = roomHash,
            contentContractId = lifecycle.contractId,
            profileRevision = lifecycle.profileRevision,
            contractSha256 = lifecycle.contractSha256?.toHex(),
        )
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray()).toHex()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
