/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.element.android.security.dataprotection.api.isLocalVoiceDraftSourceAuthorizedAt
import io.element.android.security.dataprotection.api.localVoiceDraftSourceExpiresAtEpochMillis
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class ProtectedVoiceDraftSourceAuthorizationTest {
    @Test
    fun `fresh server observation uses the verified snapshot skew but never extends expiry`() {
        val now = 10_000L
        for (ahead in listOf(1_000L, 300_000L)) {
            val fresh = snapshot(issuedAt = now + ahead, expiresAt = now + ahead + 60_000)
            assertThat(fresh.isLocalVoiceDraftSourceAuthorizedAt(now, ACCOUNT_HASH, DEVICE_HASH)).isTrue()
            assertThat(fresh.isLocalVoiceDraftSourceAuthorizedAt(fresh.expiresAtEpochMillis, ACCOUNT_HASH, DEVICE_HASH)).isFalse()
            for (name in SOURCE_COMPONENTS) {
                val component = fresh.components.getValue(name)
                val inconsistent = fresh.copy(components = fresh.components + (name to component.copy(
                    observedAtEpochMillis = fresh.issuedAtEpochMillis + 1,
                )))
                assertThat(inconsistent.isLocalVoiceDraftSourceAuthorizedAt(now, ACCOUNT_HASH, DEVICE_HASH)).isFalse()
            }
        }
        assertThat(snapshot(issuedAt = now + 300_001, expiresAt = now + 360_001)
            .isLocalVoiceDraftSourceAuthorizedAt(now, ACCOUNT_HASH, DEVICE_HASH)).isFalse()
    }

    @Test
    fun `every source authority must be ready and within its own observation window`() {
        val base = snapshot(issuedAt = 1_000, expiresAt = 3_000)
        for (name in SOURCE_COMPONENTS) {
            val original = base.components.getValue(name)
            val rejected = AppOperationalStatus.entries.filter { it != AppOperationalStatus.READY }
                .map { original.copy(status = it) } + listOf(
                original.copy(expiresAtEpochMillis = 2_000),
                original.copy(observedAtEpochMillis = 2_001),
            )
            for (component in rejected) {
                val candidate = base.copy(
                    overallStatus = AppOperationalStatus.BLOCKED,
                    components = base.components + (name to component),
                )
                assertThat(candidate.isLocalVoiceDraftSourceAuthorizedAt(2_000, ACCOUNT_HASH, DEVICE_HASH)).isFalse()
            }
        }
    }

    @Test
    fun `expired unrelated blocked overview refreshes without reviving a denied source component`() = runTest {
        val base = snapshot(issuedAt = 500, expiresAt = 1_000)
        val expired = base.copy(
            overallStatus = AppOperationalStatus.BLOCKED,
            components = base.components + ("rtc" to base.components.getValue("rtc").copy(status = AppOperationalStatus.BLOCKED)),
        )
        val current = snapshot(issuedAt = 1_500, expiresAt = 3_000)
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(expired), current)
        assertThat(resolveVoiceDraftSourceAuthorization(coordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 }))
            .isSameInstanceAs(current)
        assertThat(coordinator.refreshRequests).hasSize(1)

        for (name in SOURCE_COMPONENTS) {
            val denied = expired.copy(components = expired.components + (name to base.components.getValue(name).copy(status = AppOperationalStatus.BLOCKED)))
            val blockedCoordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(denied), current)
            assertThat(runCatching {
                resolveVoiceDraftSourceAuthorization(blockedCoordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })
            }.isFailure).isTrue()
            assertThat(blockedCoordinator.refreshRequests).isEmpty()
        }
    }

    @Test
    fun `unrelated unavailable services do not deny a voice draft with current source authorities`() = runTest {
        val base = snapshot(issuedAt = 1_000, expiresAt = 3_000)
        val cached = base.copy(
            overallStatus = AppOperationalStatus.BLOCKED,
            components = base.components.mapValues { (name, component) ->
                if (name in setOf("release", "push", "rtc", "preview", "recovery")) {
                    component.copy(status = AppOperationalStatus.BLOCKED)
                } else {
                    component
                }
            },
        )
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Available(cached),
            refreshedSnapshot = null,
        )

        val result = resolveVoiceDraftSourceAuthorization(coordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })

        assertThat(result).isSameInstanceAs(cached)
        assertThat(coordinator.refreshRequests).isEmpty()
    }

    @Test
    fun `historical recovery denial does not authorize history or block a newly captured draft`() = runTest {
        val base = snapshot(issuedAt = 1_000, expiresAt = 3_000)
        for (status in listOf(AppOperationalStatus.BLOCKED, AppOperationalStatus.TERMINATED)) {
            val recovery = base.components.getValue("recovery").copy(status = status, expiresAtEpochMillis = 1_500)
            val current = base.copy(
                overallStatus = AppOperationalStatus.TERMINATED,
                components = base.components + ("recovery" to recovery),
            )
            val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(current), null)

            val result = resolveVoiceDraftSourceAuthorization(coordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })

            assertThat(result).isSameInstanceAs(current)
            assertThat(result.components.getValue("recovery")).isEqualTo(recovery)
            assertThat(result.localVoiceDraftSourceExpiresAtEpochMillis).isEqualTo(3_000)
            assertThat(coordinator.refreshRequests).isEmpty()
        }
    }

    @Test
    fun `expired new capture authority refreshes despite a historical recovery denial`() = runTest {
        val expired = snapshot(issuedAt = 500, expiresAt = 1_000).let { source ->
            source.copy(overallStatus = AppOperationalStatus.BLOCKED, components = source.components + ("recovery" to source.components.getValue("recovery").copy(
                status = AppOperationalStatus.BLOCKED,
            )))
        }
        val renewed = snapshot(issuedAt = 1_500, expiresAt = 3_000).let { source ->
            source.copy(overallStatus = AppOperationalStatus.BLOCKED, components = source.components + ("recovery" to source.components.getValue("recovery").copy(
                status = AppOperationalStatus.BLOCKED,
            )))
        }
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(expired), renewed)

        val result = resolveVoiceDraftSourceAuthorization(coordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })

        assertThat(result).isSameInstanceAs(renewed)
        assertThat(coordinator.refreshRequests).containsExactly(ACCOUNT_HASH to DEVICE_HASH)
    }

    @Test
    fun `discovering state refreshes once for the current source identity`() = runTest {
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Discovering,
            refreshedSnapshot = snapshot(issuedAt = 1_000, expiresAt = 3_000),
        )

        val result = resolveVoiceDraftSourceAuthorization(
            appOperationalStateCoordinator = coordinator,
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            nowEpochMillis = { 2_000 },
        )

        assertThat(result.signatureBase64).isEqualTo("source-signature-value")
        assertThat(coordinator.refreshRequests).containsExactly(ACCOUNT_HASH to DEVICE_HASH)
    }

    @Test
    fun `expired access session denial rechecks signed authority after ticket renewal`() = runTest {
        val base = snapshot(issuedAt = 500, expiresAt = 1_000)
        val expired = base.copy(overallStatus = AppOperationalStatus.BLOCKED,
            components = base.components + ("access_session" to base.components.getValue("access_session").copy(
            status = AppOperationalStatus.BLOCKED, reasonCode = "ACCESS_SESSION_EXPIRED",
        )))
        val renewed = snapshot(issuedAt = 1_500, expiresAt = 3_000)
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(expired), renewed)
        assertThat(resolveVoiceDraftSourceAuthorization(coordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 }))
            .isSameInstanceAs(renewed)
        assertThat(coordinator.refreshRequests).containsExactly(ACCOUNT_HASH to DEVICE_HASH)

        // The request itself is not authorization. A still-denied signed reply
        // must not issue a lease or accept the expired original snapshot.
        val stillDenied = renewed.copy(overallStatus = AppOperationalStatus.BLOCKED,
            components = renewed.components + ("access_session" to
            renewed.components.getValue("access_session").copy(status = AppOperationalStatus.BLOCKED, reasonCode = "ACCESS_SESSION_EXPIRED")))
        val denied = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(expired), stillDenied)
        assertThat(runCatching {
            resolveVoiceDraftSourceAuthorization(denied, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })
        }.isFailure).isTrue()
        assertThat(denied.refreshRequests).hasSize(1)
    }

    @Test
    fun `fresh access denial and expired revoked device are not refreshed by a voice draft`() = runTest {
        val base = snapshot(issuedAt = 500, expiresAt = 3_000)
        val denied = base.copy(overallStatus = AppOperationalStatus.BLOCKED,
            components = base.components + ("access_session" to base.components.getValue("access_session").copy(
            status = AppOperationalStatus.BLOCKED, reasonCode = "ACCESS_SESSION_EXPIRED",
        )))
        val fresh = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(denied), base)
        assertThat(runCatching {
            resolveVoiceDraftSourceAuthorization(fresh, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })
        }.isFailure).isTrue()
        assertThat(fresh.refreshRequests).isEmpty()
        val revoked = denied.copy(expiresAtEpochMillis = 1_000,
            components = denied.components + ("device_trust" to base.components.getValue("device_trust").copy(status = AppOperationalStatus.BLOCKED)))
        val revokedCoordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(revoked), base)
        assertThat(runCatching {
            resolveVoiceDraftSourceAuthorization(revokedCoordinator, ACCOUNT_HASH, DEVICE_HASH, nowEpochMillis = { 2_000 })
        }.isFailure).isTrue()
        assertThat(revokedCoordinator.refreshRequests).isEmpty()
    }

    @Test
    fun `usable cached authorization does not refresh`() = runTest {
        val cached = snapshot(issuedAt = 1_000, expiresAt = 3_000)
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Available(cached),
            refreshedSnapshot = snapshot(issuedAt = 2_000, expiresAt = 4_000),
        )

        val result = resolveVoiceDraftSourceAuthorization(
            appOperationalStateCoordinator = coordinator,
            accountIdHash = ACCOUNT_HASH,
            matrixDeviceIdHash = DEVICE_HASH,
            nowEpochMillis = { 2_000 },
        )

        assertThat(result).isSameInstanceAs(cached)
        assertThat(coordinator.refreshRequests).isEmpty()
    }

    @Test
    fun `expired authorization refreshes once and uses post refresh time`() = runTest {
        var now = 2_000L
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Available(snapshot(issuedAt = 500, expiresAt = 1_000)),
            refreshedSnapshot = snapshot(issuedAt = 2_000, expiresAt = 2_500),
            beforeRefreshReturn = { now = 2_600 },
        )

        val failure = runCatching {
            resolveVoiceDraftSourceAuthorization(
                appOperationalStateCoordinator = coordinator,
                accountIdHash = ACCOUNT_HASH,
                matrixDeviceIdHash = DEVICE_HASH,
                nowEpochMillis = { now },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("Voice draft source authorization is unavailable or expired.")
        assertThat(coordinator.refreshRequests).hasSize(1)
    }

    @Test
    fun `snapshot for another source identity refreshes and cannot authorize the current source`() = runTest {
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Available(
                snapshot(
                    issuedAt = 1_000,
                    expiresAt = 3_000,
                    accountIdHash = OTHER_ACCOUNT_HASH,
                )
            ),
            refreshedSnapshot = snapshot(
                issuedAt = 2_000,
                expiresAt = 4_000,
                accountIdHash = OTHER_ACCOUNT_HASH,
            ),
        )

        val failure = runCatching {
            resolveVoiceDraftSourceAuthorization(
                appOperationalStateCoordinator = coordinator,
                accountIdHash = ACCOUNT_HASH,
                matrixDeviceIdHash = DEVICE_HASH,
                nowEpochMillis = { 2_000 },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(coordinator.refreshRequests).containsExactly(ACCOUNT_HASH to DEVICE_HASH)
    }

    @Test
    fun `failed refresh remains fail closed`() = runTest {
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Discovering,
            refreshedSnapshot = null,
            refreshFailure = IllegalStateException("offline"),
        )

        val failure = runCatching {
            resolveVoiceDraftSourceAuthorization(
                appOperationalStateCoordinator = coordinator,
                accountIdHash = ACCOUNT_HASH,
                matrixDeviceIdHash = DEVICE_HASH,
                nowEpochMillis = { 2_000 },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("Voice draft source authorization is unavailable or expired.")
        assertThat(coordinator.refreshRequests).hasSize(1)
    }

    @Test
    fun `authorization revision or source identity change during refresh is rejected`() = runTest {
        var contextIsCurrent = true
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Discovering,
            refreshedSnapshot = snapshot(issuedAt = 1_000, expiresAt = 3_000),
            beforeRefreshReturn = { contextIsCurrent = false },
        )

        val failure = runCatching {
            resolveVoiceDraftSourceAuthorization(
                appOperationalStateCoordinator = coordinator,
                accountIdHash = ACCOUNT_HASH,
                matrixDeviceIdHash = DEVICE_HASH,
                contextStillCurrent = { contextIsCurrent },
                nowEpochMillis = { 2_000 },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("Voice draft authorization changed during source refresh.")
    }

    @Test
    fun `refresh timeout cancellation propagates`() = runTest {
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Discovering,
            refreshedSnapshot = null,
            beforeRefreshReturn = { awaitCancellation() },
        )

        val failure = runCatching {
            withTimeout(100) {
                resolveVoiceDraftSourceAuthorization(
                    appOperationalStateCoordinator = coordinator,
                    accountIdHash = ACCOUNT_HASH,
                    matrixDeviceIdHash = DEVICE_HASH,
                )
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
    }

    @Test
    fun `terminal source authorization is not refreshed`() = runTest {
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Available(
                snapshot(
                    issuedAt = 1_000,
                    expiresAt = 3_000,
                    status = AppOperationalStatus.TERMINATED,
                )
            ),
            refreshedSnapshot = snapshot(issuedAt = 2_000, expiresAt = 4_000),
        )

        val failure = runCatching {
            resolveVoiceDraftSourceAuthorization(
                appOperationalStateCoordinator = coordinator,
                accountIdHash = ACCOUNT_HASH,
                matrixDeviceIdHash = DEVICE_HASH,
                nowEpochMillis = { 2_000 },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(
            "Voice draft source authorization is blocked or outside its refreshable state."
        )
        assertThat(coordinator.refreshRequests).isEmpty()
    }

    private fun snapshot(
        issuedAt: Long,
        expiresAt: Long,
        status: AppOperationalStatus = AppOperationalStatus.READY,
        accountIdHash: String = ACCOUNT_HASH,
        matrixDeviceIdHash: String = DEVICE_HASH,
    ): AppOperationalSnapshot = AppOperationalSnapshot(
        accountIdHash = accountIdHash,
        installationId = "voice-test-installation",
        matrixDeviceIdHash = matrixDeviceIdHash,
        issuedAtEpochMillis = issuedAt,
        expiresAtEpochMillis = expiresAt,
        overallStatus = status,
        components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
            ComponentOperationalState(
                status = status,
                authority = "voice-test-$component",
                authorityRevision = "voice-test-revision",
                observedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = expiresAt,
            )
        },
        signingKeyId = "voice-test-signing-key",
        signatureBase64 = "source-signature-value",
    )

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_ACCOUNT_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}

class ProtectedVoiceDraftStoreAuthorizationIntegrationTest {
    @Test
    fun `new capture lease remains usable while historical recovery is revoked`() = runTest {
        val now = System.currentTimeMillis()
        val fresh = integrationSnapshot(sourceIdentity(), now, AppOperationalStatus.READY)
        val historicalRecovery = fresh.components.getValue("recovery").copy(
            status = AppOperationalStatus.BLOCKED,
            expiresAtEpochMillis = now - 1,
        )
        val source = fresh.copy(
            overallStatus = AppOperationalStatus.TERMINATED,
            components = fresh.components + ("recovery" to historicalRecovery),
        )
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(source), null)
        val fixture = fixture(coordinator)

        val issued = fixture.issueSuccess()

        assertThat(issued.authorizationDeadlineEpochMillis).isEqualTo(fresh.expiresAtEpochMillis)
        assertThat(fixture.leaseManager.lastRequest?.isReadAuthorized?.invoke()).isTrue()
        assertThat(coordinator.state.value).isEqualTo(AppOperationalCoordinatorState.Available(source))
        assertThat(coordinator.refreshRequests).isEmpty()
    }

    @Test
    fun `fresh future snapshot survives refresh lease issue and per read checks`() = runTest {
        val now = System.currentTimeMillis()
        val source = integrationSnapshot(sourceIdentity(), now + 1_000, AppOperationalStatus.READY)
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Discovering, source)
        val fixture = fixture(coordinator)
        val issued = fixture.issueSuccess()
        assertThat(coordinator.refreshRequests).hasSize(1)
        assertThat(issued.authorizationDeadlineEpochMillis).isEqualTo(source.expiresAtEpochMillis)
        assertThat(fixture.leaseManager.lastRequest?.isReadAuthorized?.invoke()).isTrue()
    }

    @Test
    fun `failed one shot refresh does not issue lease`() = runTest {
        val fixture = fixture(
            coordinator = RecordingRefreshCoordinator(
                initialState = AppOperationalCoordinatorState.Discovering,
                refreshedSnapshot = null,
                refreshFailure = IllegalStateException("offline"),
            ),
        )

        val failure = fixture.issueFailure()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.leaseManager.issueCount).isEqualTo(0)
    }

    @Test
    fun `refresh return cannot override newer blocked coordinator state`() = runTest {
        val now = System.currentTimeMillis()
        val identity = sourceIdentity()
        val returned = integrationSnapshot(identity, now, AppOperationalStatus.READY, signature = "returned-signature-value")
        val blocked = integrationSnapshot(identity, now, AppOperationalStatus.BLOCKED, signature = "blocked-signature-value")
        val fixture = fixture(
            coordinator = RecordingRefreshCoordinator(
                initialState = AppOperationalCoordinatorState.Discovering,
                refreshedSnapshot = returned,
                publishedSnapshot = blocked,
            ),
        )

        val failure = fixture.issueFailure()

        assertThat(failure).hasMessageThat().isEqualTo("Voice draft source authorization changed before lease issue.")
        assertThat(fixture.leaseManager.issueCount).isEqualTo(0)
    }

    @Test
    fun `cached fast path rejects authorization revision change before lease issue`() = runTest {
        val now = System.currentTimeMillis()
        val gate = FakeProtectedPreviewAuthorizationGate()
        val policy = MutableVoicePolicyProvider(
            onCurrent = { call -> if (call == 1) gate.requestAuthorizationRefresh() },
        )
        val fixture = fixture(
            coordinator = RecordingRefreshCoordinator(
                initialState = AppOperationalCoordinatorState.Available(
                    integrationSnapshot(sourceIdentity(), now, AppOperationalStatus.READY)
                ),
                refreshedSnapshot = null,
            ),
            gate = gate,
            policyProvider = policy,
        )

        val failure = fixture.issueFailure()

        assertThat(failure).hasMessageThat().isEqualTo("Voice draft authorization changed during source refresh.")
        assertThat(fixture.leaseManager.issueCount).isEqualTo(0)
    }

    @Test
    fun `policy becoming fail closed during refresh prevents lease issue`() = runTest {
        val now = System.currentTimeMillis()
        val policy = MutableVoicePolicyProvider()
        val coordinator = RecordingRefreshCoordinator(
            initialState = AppOperationalCoordinatorState.Discovering,
            refreshedSnapshot = integrationSnapshot(sourceIdentity(), now, AppOperationalStatus.READY),
            beforeRefreshReturn = { policy.failClosed() },
        )
        val fixture = fixture(coordinator = coordinator, policyProvider = policy)

        val failure = fixture.issueFailure()

        assertThat(failure).hasMessageThat().isEqualTo("Voice draft access is blocked by policy.")
        assertThat(fixture.leaseManager.issueCount).isEqualTo(0)
    }

    @Test
    fun `issued lease read authorization is revoked when gate revision changes`() = runTest {
        val now = System.currentTimeMillis()
        val gate = FakeProtectedPreviewAuthorizationGate()
        val fixture = fixture(
            coordinator = RecordingRefreshCoordinator(
                initialState = AppOperationalCoordinatorState.Discovering,
                refreshedSnapshot = integrationSnapshot(sourceIdentity(), now, AppOperationalStatus.READY),
            ),
            gate = gate,
        )

        fixture.issueSuccess()
        gate.requestAuthorizationRefresh()

        assertThat(fixture.leaseManager.issueCount).isEqualTo(1)
        assertThat(fixture.leaseManager.lastRequest?.isReadAuthorized?.invoke()).isFalse()
    }

    @Test
    fun `shortest component bounds the real lease and source revocation denies subsequent reads`() = runTest {
        val now = System.currentTimeMillis()
        val base = integrationSnapshot(sourceIdentity(), now, AppOperationalStatus.READY)
        val deadline = now + 20_000
        val source = base.copy(components = base.components + ("access_session" to
            base.components.getValue("access_session").copy(expiresAtEpochMillis = deadline)))
        val coordinator = RecordingRefreshCoordinator(AppOperationalCoordinatorState.Available(source), null)
        val fixture = fixture(coordinator)

        val issued = fixture.issueSuccess()
        assertThat(issued.authorizationDeadlineEpochMillis).isEqualTo(deadline)
        assertThat(fixture.leaseManager.lastRequest?.timeToLive?.inWholeMilliseconds).isAtMost(20_000)
        assertThat(fixture.leaseManager.lastRequest?.isReadAuthorized?.invoke()).isTrue()

        coordinator.publish(source.copy(
            overallStatus = AppOperationalStatus.BLOCKED,
            components = source.components + ("cleanup" to source.components.getValue("cleanup").copy(status = AppOperationalStatus.TERMINATING)),
            signatureBase64 = "new-source-denial-signature",
        ))
        assertThat(fixture.leaseManager.lastRequest?.isReadAuthorized?.invoke()).isFalse()
    }

    private fun TestScope.fixture(
        coordinator: AppOperationalStateCoordinator,
        gate: FakeProtectedPreviewAuthorizationGate = FakeProtectedPreviewAuthorizationGate(),
        policyProvider: DataProtectionPolicyProvider = MutableVoicePolicyProvider(),
    ): StoreFixture {
        val leaseManager = RecordingSecureMediaLeaseManager()
        val matrixClient = FakeMatrixClient(INTEGRATION_SESSION_ID, INTEGRATION_DEVICE_ID)
        val store = DefaultProtectedVoiceDraftStore(
            context = fakeContext(),
            sensitiveFileStore = mockk<SensitiveFileStore>(),
            secureMediaLeaseManager = leaseManager,
            dataProtectionPolicyProvider = policyProvider,
            protectedPreviewAuthorizationGate = gate,
            appOperationalStateCoordinator = coordinator,
            roomCoroutineScope = backgroundScope,
            matrixClient = matrixClient,
            room = FakeJoinedRoom(),
        )
        return StoreFixture(store, leaseManager)
    }

    private fun fakeContext(): Context {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        return context
    }

    private suspend fun StoreFixture.issueFailure(): Throwable? {
        return runCatching {
            store.issueLease(TEST_REFERENCE, "audio/ogg", "voice-operation-integration-0001")
        }.exceptionOrNull()
    }

    private suspend fun StoreFixture.issueSuccess(): DefaultProtectedVoiceDraftStore.IssuedVoiceDraftLease =
        store.issueLease(TEST_REFERENCE, "audio/ogg", "voice-operation-integration-0002")

    private fun sourceIdentity(): Pair<String, String> =
        INTEGRATION_SESSION_ID.value.sha256HexForTest() to INTEGRATION_DEVICE_ID.value.sha256HexForTest()

    private companion object {
        val INTEGRATION_SESSION_ID = SessionId("@voice-source:test")
        val INTEGRATION_DEVICE_ID = DeviceId("VOICE-SOURCE-DEVICE")
        val TEST_REFERENCE = SecureObjectReference(
            objectId = io.element.android.security.securestorage.api.SecureObjectId("voice-source-object"),
            vaultId = AccountVaultId("voice-source-vault"),
            purpose = io.element.android.security.securestorage.api.SecureObjectPurpose.VOICE_MESSAGE,
            keyEnvelopeId = KeyEnvelopeId("voice-source-envelope"),
            formatVersion = 1,
            plaintextLength = 3,
            ciphertextLength = 3,
            associatedDataSha256 = "a".repeat(64),
        )
    }
}

private data class StoreFixture(
    val store: DefaultProtectedVoiceDraftStore,
    val leaseManager: RecordingSecureMediaLeaseManager,
)

private class RecordingSecureMediaLeaseManager : SecureMediaLeaseManager {
    var issueCount = 0
    var lastRequest: SecureMediaLeaseRequest? = null

    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease {
        issueCount++
        lastRequest = request
        return object : SecureMediaLease {
            override val uri: Uri = mockk()
            override fun openInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }
    }
}

private class MutableVoicePolicyProvider(
    private val onCurrent: (Int) -> Unit = {},
) : DataProtectionPolicyProvider {
    private val baseline = FakeDataProtectionPolicyProvider().current()
    private var active = baseline
    private var currentCalls = 0

    override fun current(): ActiveDataProtectionPolicy {
        currentCalls++
        onCurrent(currentCalls)
        return active
    }

    fun failClosed() {
        active = baseline.copy(source = DataProtectionPolicySource.FAIL_CLOSED)
    }
}

private fun integrationSnapshot(
    identity: Pair<String, String>,
    now: Long,
    status: AppOperationalStatus,
    signature: String = "integration-signature-value",
): AppOperationalSnapshot = AppOperationalSnapshot(
    accountIdHash = identity.first,
    installationId = "voice-integration-installation",
    matrixDeviceIdHash = identity.second,
    issuedAtEpochMillis = now - 1_000,
    expiresAtEpochMillis = now + 60_000,
    overallStatus = status,
    components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
        ComponentOperationalState(
            status = status,
            authority = "voice-integration-$component",
            authorityRevision = "voice-integration-revision",
            observedAtEpochMillis = now - 1_000,
            expiresAtEpochMillis = now + 60_000,
        )
    },
    signingKeyId = "voice-integration-signing-key",
    signatureBase64 = signature,
)

private fun String.sha256HexForTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private class RecordingRefreshCoordinator(
    initialState: AppOperationalCoordinatorState,
    private val refreshedSnapshot: AppOperationalSnapshot?,
    private val publishedSnapshot: AppOperationalSnapshot? = refreshedSnapshot,
    private val refreshFailure: Throwable? = null,
    private val beforeRefreshReturn: suspend () -> Unit = {},
    delegate: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
) : AppOperationalStateCoordinator by delegate {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<AppOperationalCoordinatorState> = mutableState
    val refreshRequests = mutableListOf<Pair<String, String>>()

    fun publish(snapshot: AppOperationalSnapshot) {
        mutableState.value = AppOperationalCoordinatorState.Available(snapshot)
    }

    override suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot {
        refreshRequests += accountIdHash to matrixDeviceIdHash
        beforeRefreshReturn()
        refreshFailure?.let { throw it }
        return checkNotNull(refreshedSnapshot).also {
            publishedSnapshot?.let { snapshot -> mutableState.value = AppOperationalCoordinatorState.Available(snapshot) }
        }
    }
}

private val SOURCE_COMPONENTS = setOf("access_session", "device_trust", "cleanup", "matrix", "kms")
