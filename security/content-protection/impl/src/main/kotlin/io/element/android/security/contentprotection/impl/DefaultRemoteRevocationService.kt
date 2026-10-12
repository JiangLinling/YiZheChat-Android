/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RemoteRevocationService
import io.element.android.security.contentprotection.api.RevocationSyncResult
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyService
import java.security.SecureRandom

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultRemoteRevocationService(
    private val kmsShareClient: KmsShareClient,
    private val store: OfflineLicenseStore,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val deviceShareKeyService: DeviceShareKeyService,
) : RemoteRevocationService {
    private val secureRandom = SecureRandom()

    override suspend fun synchronize(
        vaultId: AccountVaultId,
        accountIdHash: String,
        installationId: String,
    ): RevocationSyncResult {
        require(accountIdHash.isNotBlank() && installationId.isNotBlank())
        val existing = store.readRevocations(vaultId)
        val snapshot = kmsShareClient.fetchRevocations(
            FetchRevocationsRequest(
                accountIdHash = accountIdHash,
                installationId = installationId,
                knownSequence = existing.sequence,
                requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES).also(secureRandom::nextBytes),
            )
        )
        val merged = store.applySnapshot(vaultId, snapshot)
        when (merged.deviceAccessState) {
            RemoteDeviceAccessState.ACTIVE -> Unit
            RemoteDeviceAccessState.STALE -> {
                deviceTrustService.applyRemoteRestriction(DeviceTrustState.STALE, snapshot.issuedAtEpochMillis)
                vaultKeyService.lock(vaultId)
            }
            RemoteDeviceAccessState.REVOKED -> {
                // Persist the non-Vault device tombstone before destroying all local account/content key access.
                deviceTrustService.applyRemoteRestriction(DeviceTrustState.REVOKED, snapshot.issuedAtEpochMillis)
                vaultKeyService.destroy(vaultId, VaultDestroyReason.DEVICE_REVOKED)
                deviceShareKeyService.destroy()
                store.clearVault(vaultId)
            }
        }
        return RevocationSyncResult(
            sequence = merged.sequence,
            deviceAccessState = merged.deviceAccessState,
            revokedContentCount = merged.revokedContentIds.size + merged.locallyRevokedContentIds.size,
            revokedLeaseCount = merged.revokedLeaseIds.size,
        )
    }
}
