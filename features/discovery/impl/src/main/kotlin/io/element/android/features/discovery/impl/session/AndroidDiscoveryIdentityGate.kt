/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.DiscoveryConfiguration
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.sessionstorage.api.LoggedInState
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.retryWhen
import java.security.MessageDigest

/**
 * Reads existing signed state; never starts host operations, unlocks or changes account state.
 * Uses the host issue-time skew bound only; signed expiry remains strict.
 */
@Inject
internal class AndroidDiscoveryIdentityGate(
    configuration: DiscoveryConfiguration,
    private val client: MatrixClient,
    private val sessions: SessionStore,
    private val trust: DeviceTrustService,
    private val vault: VaultKeyService,
    private val operational: AppOperationalStateCoordinator,
    private val clock: AndroidDiscoveryClock,
    @SessionCoroutineScope coroutineScope: CoroutineScope,
) : DiscoveryIdentityGate {
    private val vaultId = deriveAccountVaultId(client.sessionId.value, client.deviceId.value)
    override val scope = if (configuration.endpoints == null) MutableStateFlow(null) else combine(
        sessions.loggedInStateFlow(), trust.trust(), vault.state(vaultId), operational.state,
    ) { login, device, vaultState, state ->
        if (login !is LoggedInState.LoggedIn || login.sessionId != client.sessionId.value || !login.isTokenValid ||
            vaultState != VaultState.UNLOCKED || !admitted(device) || state !is AppOperationalCoordinatorState.Available
        ) null else {
            val snapshot = state.snapshot
            val stored = sessions.getSession(client.sessionId.value)
            if (stored?.isTokenValid != true || stored.deviceId != client.deviceId.value ||
                snapshot.accountIdHash != sha256(client.sessionId.value) || snapshot.matrixDeviceIdHash != sha256(client.deviceId.value) ||
                snapshot.installationId != device.installationId || snapshot.overallStatus != AppOperationalStatus.READY ||
                !snapshot.isUsableAt(clock.wallMillis(), APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS)
            ) null else DiscoveryAccountScope(snapshot.accountIdHash, snapshot.matrixDeviceIdHash, device.installationId,
                device.attestationVersion, stored.loginTimestamp?.time ?: 0)
        }
    }.retryWhen { cause, _ ->
        if (cause is CancellationException) throw cause
        emit(null)
        delay(1_000)
        true
    }.stateIn(coroutineScope, SharingStarted.Eagerly, null)

    override suspend fun current(): DiscoveryAccountScope? {
        val observed = scope.value ?: return null
        val snapshot = (operational.state.value as? AppOperationalCoordinatorState.Available)?.snapshot ?: return null
        val device = trust.current()
        val stored = sessions.getSession(client.sessionId.value) ?: return null
        val latest = sessions.getLatestSession() ?: return null
        return observed.takeIf {
            latest.userId == stored.userId && stored.isTokenValid && stored.deviceId == client.deviceId.value &&
                observed.loginTimestamp == (stored.loginTimestamp?.time ?: 0) && vault.isUnlocked(vaultId) &&
                admitted(device) && device.installationId == observed.installationId &&
                device.attestationVersion == observed.attestationVersion && snapshot.overallStatus == AppOperationalStatus.READY &&
                snapshot.accountIdHash == observed.accountHash && snapshot.matrixDeviceIdHash == observed.deviceHash &&
                snapshot.installationId == observed.installationId && snapshot.isUsableAt(clock.wallMillis(), APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS)
        }
    }

    private fun admitted(device: DeviceTrustSnapshot): Boolean = device.allowsLocalVaultAccess &&
        device.riskSignals.none { it.blocksLocalVaultAccess } &&
        (device.remotelyTrusted || (device.trustState == DeviceTrustState.MANAGED &&
            device.managedLicense?.let { it.isActive(clock.wallMillis()) && it.allowsAccount(sha256(client.sessionId.value)) } == true))
}

internal fun sha256(value: String): String = sha256(value.encodeToByteArray())
internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
