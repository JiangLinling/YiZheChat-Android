/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest

class LiveMediaSendIdentityProviderTest {
    @Test fun `reads current session device and signed room lifecycle`() = runTest {
        val sessionStore = mockk<SessionStore>(); val trust = mockk<DeviceTrustService>(); val service = mockk<ContentProtectionService>()
        val session = session(valid = true); coEvery { sessionStore.getSession(session.userId) } returns session
        coEvery { trust.current() } returns trust(); coEvery { service.resolveRoomLifecycle(ROOM) } returns lifecycle()
        val provider = DefaultLiveMediaSendIdentityProvider(sessionStore, trust, service)

        val result = provider.current(session.userId, ROOM, digest(session.userId)).getOrThrow()

        assertThat(result.installationId).isEqualTo("install-1")
        assertThat(result.matrixDeviceId).isEqualTo("DEVICE_A")
        assertThat(result.contentContractId).isEqualTo("contract-1")
    }

    @Test fun `token device trust account or room mismatch fails closed`() = runTest {
        val sessionStore = mockk<SessionStore>(); val trust = mockk<DeviceTrustService>(); val service = mockk<ContentProtectionService>()
        coEvery { sessionStore.getSession(USER) } returns session(valid = false)
        coEvery { trust.current() } returns trust(); coEvery { service.resolveRoomLifecycle(ROOM) } returns lifecycle()
        assertThat(DefaultLiveMediaSendIdentityProvider(sessionStore, trust, service).current(USER, ROOM, digest(USER)).isFailure).isTrue()
    }

    private fun session(valid: Boolean) = SessionData(USER, "DEVICE_A", "token", null, "https://matrix.example", null, null,
        valid, LoginType.PASSWORD, null, "session", "cache", 0, 1, null, null)
    private fun trust() = DeviceTrustSnapshot("install-1", HardwareSecurityLevel.STRONGBOX, DeviceTrustState.TRUSTED, 7, 1)
    private fun lifecycle() = ResolvedRoomLifecycle(
        digestBytes(ROOM), "profile-1", RoomLifecycleProfileKind.STANDARD, 60_000, 0,
        RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY, 100_000, 1, 90_000,
        contractId = "contract-1", profileRevision = 4, contractSha256 = ByteArray(32) { 3 },
    )
    private fun digest(value: String) = digestBytes(value).joinToString("") { "%02x".format(it) }
    private fun digestBytes(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    private companion object { const val USER = "@alice:example.org"; const val ROOM = "!room:example.org" }
}
