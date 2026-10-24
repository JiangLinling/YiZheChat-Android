/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import io.element.android.features.discovery.impl.network.DiscoveryConfiguration
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.sessionstorage.api.LoggedInState
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class AndroidDiscoveryIdentityGateTest {
    private class Fixture(test: TestScope, configured: Boolean = true) {
        val account = "@discovery-test:example.invalid"
        val deviceId = "DEVICE-A"
        val installation = "installation-discovery-test-a"
        var now = System.currentTimeMillis()
        var tokenValid = true
        var loginAt = Date(100)
        val client = mockk<MatrixClient>()
        val sessions = mockk<SessionStore>()
        val trust = mockk<DeviceTrustService>()
        val vault = mockk<VaultKeyService>()
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val configuration = mockk<DiscoveryConfiguration>()
        val clock = mockk<AndroidDiscoveryClock>()
        val stored = mockk<SessionData>()
        var latest = stored
        val login = MutableStateFlow<LoggedInState>(LoggedInState.LoggedIn(account, true))
        val device = MutableStateFlow(DeviceTrustSnapshot(installation, HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            DeviceTrustState.TRUSTED, 1, now))
        var currentDevice: DeviceTrustSnapshot? = null
        val vaultState = MutableStateFlow(VaultState.UNLOCKED)
        val operational = MutableStateFlow<AppOperationalCoordinatorState>(AppOperationalCoordinatorState.Available(snapshot()))
        val gate: AndroidDiscoveryIdentityGate

        init {
            every { configuration.endpoints } returns if (configured) mockk() else null
            every { client.sessionId } returns UserId(account)
            every { client.deviceId } returns DeviceId(deviceId)
            every { stored.userId } returns account
            every { stored.deviceId } returns deviceId
            every { stored.isTokenValid } answers { tokenValid }
            every { stored.loginTimestamp } answers { loginAt }
            every { clock.wallMillis() } answers { now }
            every { sessions.loggedInStateFlow() } returns login
            coEvery { sessions.getSession(account) } returns stored
            coEvery { sessions.getLatestSession() } answers { latest }
            every { trust.trust() } returns device
            coEvery { trust.current() } answers { currentDevice ?: device.value }
            every { vault.state(any()) } returns vaultState
            coEvery { vault.isUnlocked(any()) } answers { vaultState.value == VaultState.UNLOCKED }
            every { coordinator.state } returns operational
            gate = AndroidDiscoveryIdentityGate(configuration, client, sessions, trust, vault, coordinator, clock, test.backgroundScope)
        }

        fun snapshot(issued: Long = now - 1_000, expires: Long = now + 60_000) = AppOperationalSnapshot(
            accountIdHash = sha256(account), installationId = installation, matrixDeviceIdHash = sha256(deviceId),
            issuedAtEpochMillis = issued, expiresAtEpochMillis = expires, overallStatus = AppOperationalStatus.READY,
            components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith {
                ComponentOperationalState(AppOperationalStatus.READY, it, "test-revision", issued, expires)
            }, signingKeyId = "test-key", signatureBase64 = "test-verified-snapshot",
        )
    }

    @Test
    fun `disabled discovery does not subscribe to host state or access its token`() = runTest {
        val f = Fixture(this, configured = false)
        runCurrent()
        assertNull(f.gate.scope.value)
        assertNull(f.gate.current())
        verify(exactly = 0) { f.sessions.loggedInStateFlow(); f.trust.trust(); f.vault.state(any()); f.coordinator.state }
        coVerify(exactly = 0) { f.sessions.getSession(any()); f.client.recoverySessionAccessToken() }
    }

    @Test
    fun `vault lock logout and unavailable authority discard observed identity`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertNotNull(f.gate.current())
        f.vaultState.value = VaultState.LOCKED
        runCurrent()
        assertNull(f.gate.scope.value)
        f.vaultState.value = VaultState.UNLOCKED
        runCurrent()
        assertNotNull(f.gate.current())
        f.login.value = LoggedInState.NotLoggedIn
        runCurrent()
        assertNull(f.gate.scope.value)
        f.login.value = LoggedInState.LoggedIn(f.account, true)
        runCurrent()
        f.operational.value = AppOperationalCoordinatorState.RetryableFailure("TEST_UNAVAILABLE", f.snapshot())
        runCurrent()
        assertNull(f.gate.scope.value)
        coVerify(exactly = 0) { f.client.recoverySessionAccessToken(); f.coordinator.refresh(any(), any()) }
    }

    @Test
    fun `current rechecks token login generation device and deadline even without flow emissions`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertNotNull(f.gate.current())
        f.tokenValid = false
        assertNull(f.gate.current())
        f.tokenValid = true
        f.latest = mockk { every { userId } returns "@another:example.invalid" }
        assertNull(f.gate.current())
        f.latest = f.stored
        f.loginAt = Date(200)
        assertNull(f.gate.current())
        f.loginAt = Date(100)
        f.currentDevice = f.device.value.copy(trustState = DeviceTrustState.REVOKED)
        assertNull(f.gate.current())
        f.currentDevice = f.device.value.copy(attestationVersion = 2)
        assertNull(f.gate.current())
        f.currentDevice = f.device.value.copy(riskSignals = setOf(DeviceRiskSignal.ROOT_ARTIFACT_DETECTED))
        assertNull(f.gate.current())
        f.currentDevice = null
        assertNotNull(f.gate.current())
        f.now = (f.operational.value as AppOperationalCoordinatorState.Available).snapshot.expiresAtEpochMillis
        assertNull(f.gate.current())
    }

    @Test
    fun `host accepted future issue skew is respected but expiry and excessive skew are strict`() = runTest {
        val f = Fixture(this)
        val snapshot = f.snapshot(issued = f.now + 2_000, expires = f.now + 62_000)
        f.operational.value = AppOperationalCoordinatorState.Available(snapshot)
        runCurrent()
        assertNotNull(f.gate.scope.value)
        assertNotNull(f.gate.current())
        f.now = snapshot.expiresAtEpochMillis
        assertNull(f.gate.current())
        val tooFar = f.now + APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS + 1
        f.operational.value = AppOperationalCoordinatorState.Available(f.snapshot(tooFar, tooFar + 60_000))
        runCurrent()
        assertNull(f.gate.scope.value)
    }

    @Test
    fun `managed license requires this account and cannot outlive its signed deadline`() = runTest {
        val f = Fixture(this)
        val license = ManagedDeviceLicense("license", f.installation, "a".repeat(64), "share-key", "test.package", 1, 1,
            setOf("test-scope"), false, f.now - 1_000, f.now + 30_000, setOf(sha256(f.account)))
        f.device.value = f.device.value.copy(trustState = DeviceTrustState.MANAGED, managedLicense = license)
        runCurrent()
        assertNotNull(f.gate.current())
        f.currentDevice = f.device.value.copy(managedLicense = license.copy(accountIdHashes = setOf("b".repeat(64))))
        assertNull(f.gate.current())
        f.currentDevice = null
        f.now = license.expiresAtEpochMillis
        assertNull(f.gate.current())
    }
}
