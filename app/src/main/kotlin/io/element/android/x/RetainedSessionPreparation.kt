/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.MessageDigest

/**
 * One optional local preparation per process, after ordered application initialization.
 * Reuses the navigation/push Matrix cache. Does not unlock keys, start sync, admit an
 * operation, or grant UI access: foreground navigation still revalidates every gate.
 */
@Inject
@SingleIn(AppScope::class)
class RetainedSessionPreparation(
    private val sessionStore: SessionStore,
    private val matrixClientProvider: Lazy<MatrixClientProvider>,
    private val managementControlService: ManagementControlService,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val lockScreenService: LockScreenService,
    private val permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator,
) {
    suspend fun prepare() {
        try {
            val session = sessionStore.getLatestSession() ?: return
            if (!session.isTokenValid || session.passphrase.isNullOrBlank()) return
            val vaultId = deriveAccountVaultId(session.userId, session.deviceId)
            // Observe the existing owner's unlock; no polling, wake lock or authorization refresh.
            val unlocked = withTimeoutOrNull(5_000) {
                vaultKeyService.state(vaultId).first { it == VaultState.UNLOCKED }
            } ?: return
            check(unlocked == VaultState.UNLOCKED)
            val current = sessionStore.getLatestSession() ?: return
            if (current.userId != session.userId || current.deviceId != session.deviceId || !current.isTokenValid) return
            if (lockScreenService.lockState.value != LockScreenLockState.Unlocked) return
            if (!deviceTrustService.current().allowsLocalVaultAccess || !vaultKeyService.isUnlocked(vaultId)) return
            val entry = managementControlService.localListEntryStatus() ?: return
            if (entry.remainingMillis <= 0) return
            if (permanentAccountDeletionCoordinator.pendingRecovery(accountIdHash(session.userId)) != null) return
            val deviceHash = MessageDigest.getInstance("SHA-256").digest(session.deviceId.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            if (entry.accountBindings.singleOrNull {
                it.accountIdHash == accountIdHash(session.userId) && it.matrixDeviceIdHash == deviceHash
            } == null) return
            val result = StartupTrace.measureSuspend("Background.session.prepare") {
                matrixClientProvider.value.getOrRestore(SessionId(session.userId))
            }
            StartupTrace.mark(if (result.isSuccess) "Background.session.prepared" else "Background.session.deferred")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Opportunistic preparation must never turn a working notification service into a
            // startup failure. Normal foreground recovery remains the authoritative retry path.
            Timber.w("Background session preparation deferred: %s", failure.javaClass.simpleName)
        }
    }
}
