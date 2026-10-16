/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.LocalChatListPermission
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.OfflineEntryAccountBinding
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.accountIdHash
import io.mockk.coEvery
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetainedSessionPreparationTest {
    private val sessionId = SessionId("@retained:test")
    private val sessions = mockk<SessionStore>()
    private val provider = mockk<MatrixClientProvider>()
    private val control = mockk<ManagementControlService>()
    private val trust = mockk<DeviceTrustService>()
    private val vault = mockk<VaultKeyService>()
    private val lock = mockk<LockScreenService>()
    private val deletion = mockk<PermanentAccountDeletionCoordinator>()
    private val client = mockk<MatrixClient>()
    private val vaultState = MutableStateFlow(VaultState.UNLOCKED)
    private val lockState = MutableStateFlow<LockScreenLockState>(LockScreenLockState.Unlocked)
    private val session = mockk<SessionData> {
        every { userId } returns "@retained:test"
        every { deviceId } returns "device"
        every { isTokenValid } returns true
        every { passphrase } returns "test-only-store-key"
    }
    private val grant = LocalChatListPermission(1, 1, 60_000, listOf(
        OfflineEntryAccountBinding(accountIdHash(session.userId), accountIdHash(session.deviceId), 1, "test-activation"),
    ))
    private val preparation = RetainedSessionPreparation(sessions, lazy { provider }, control, trust, vault, lock, deletion)

    init {
        coEvery { sessions.getLatestSession() } returns session
        every { vault.state(any()) } returns vaultState
        coEvery { vault.isUnlocked(any()) } returns true
        every { lock.lockState } returns lockState
        coEvery { trust.current() } returns mockk { every { allowsLocalVaultAccess } returns true }
        coEvery { control.localListEntryStatus() } returns grant
        coEvery { deletion.pendingRecovery(any()) } returns null
        coEvery { provider.getOrRestore(sessionId) } returns Result.success(client)
    }

    @Test
    fun `authorized preparation uses shared cache without touching client or unlocking keys`() = runTest {
        preparation.prepare()
        coVerify(exactly = 1) { provider.getOrRestore(sessionId) }
        coVerify(exactly = 0) { vault.unlock(any()) }
        verify { client wasNot Called }
    }

    @Test
    fun `missing signed grant never restores`() = runTest {
        coEvery { control.localListEntryStatus() } returns null
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `expired grant never restores`() = runTest {
        coEvery { control.localListEntryStatus() } returns grant.copy(remainingMillis = 0)
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `grant for another device never restores`() = runTest {
        coEvery { control.localListEntryStatus() } returns grant.copy(accountBindings = listOf(
            grant.accountBindings.single().copy(matrixDeviceIdHash = accountIdHash("another-device")),
        ))
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `vault that remains locked times out without unlocking or restoring`() = runTest {
        vaultState.value = VaultState.LOCKED
        preparation.prepare()
        coVerify(exactly = 0) { vault.unlock(any()) }
        verify { provider wasNot Called }
    }

    @Test
    fun `lock or revocation after vault notification prevents restoration`() = runTest {
        lockState.value = LockScreenLockState.Locked
        preparation.prepare()
        lockState.value = LockScreenLockState.Unlocked
        coEvery { trust.current() } returns mockk { every { allowsLocalVaultAccess } returns false }
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `logout while waiting for vault prevents restoration`() = runTest {
        coEvery { sessions.getLatestSession() } returnsMany listOf(session, null)
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `invalid token never restores`() = runTest {
        every { session.isTokenValid } returns false
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `pending account deletion never restores`() = runTest {
        coEvery { deletion.pendingRecovery(any()) } returns PermanentAccountDeletionHandle("pending-delete")
        preparation.prepare()
        verify { provider wasNot Called }
    }

    @Test
    fun `optional restore failure does not fail application initialization`() = runTest {
        coEvery { provider.getOrRestore(sessionId) } throws IllegalStateException("test failure")
        preparation.prepare()
    }
}
