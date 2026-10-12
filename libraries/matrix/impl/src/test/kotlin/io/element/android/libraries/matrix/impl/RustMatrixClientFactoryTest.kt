/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.impl.auth.FakeProxyProvider
import io.element.android.libraries.matrix.impl.auth.FakeUserCertificatesProvider
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.paths.SessionPaths
import io.element.android.libraries.matrix.impl.room.FakeTimelineEventFilterFactory
import io.element.android.libraries.matrix.impl.storage.FakeSqliteStoreBuilderProvider
import io.element.android.libraries.network.useragent.SimpleUserAgentProvider
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.libraries.workmanager.api.WorkManagerRequestBuilder
import io.element.android.libraries.workmanager.test.FakeWorkManagerScheduler
import io.element.android.security.dataprotection.test.FakeDataDeletionCoordinator
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.test.FakePermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.FakeVaultKeyService
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import java.io.File

class RustMatrixClientFactoryTest {
    @Test
    fun `each built client receives an isolated session delegate`() = runTest {
        val delegates = mutableListOf<ClientSessionDelegate>()
        val sut = createRustMatrixClientFactory(
            clientBuilderProvider = FakeClientBuilderProvider {
                FakeFfiClientBuilder(sessionDelegateResult = { delegates.add(it) })
            }
        )
        val paths = SessionPaths(File("/session"), File("/cache"))

        val firstClient = sut.buildClient(paths, clientSecret = null, ClientBuilderSlidingSync.Discovered)
        val secondClient = sut.buildClient(paths, clientSecret = null, ClientBuilderSlidingSync.Discovered)

        assertThat(delegates).hasSize(2)
        assertThat(delegates[0]).isNotSameInstanceAs(delegates[1])
        sut.discardPendingClient(firstClient)
        sut.discardPendingClient(secondClient)
        firstClient.close()
        secondClient.close()
    }

    @Test
    fun test() = runTest {
        val scheduleVacuumLambda = lambdaRecorder<WorkManagerRequestBuilder, Unit> {}
        val workManagerScheduler = FakeWorkManagerScheduler(submitLambda = scheduleVacuumLambda)
        val vaultKeyService = FakeVaultKeyService()
        val sut = createRustMatrixClientFactory(workManagerScheduler = workManagerScheduler, vaultKeyService = vaultKeyService)

        val result = sut.create(aSessionData().copy(passphrase = "database-key"))

        assertThat(result.sessionId).isEqualTo(SessionId("@alice:server.org"))
        assertThat(vaultKeyService.state(deriveAccountVaultId(result.sessionId.value, result.deviceId.value)).first())
            .isEqualTo(VaultState.UNLOCKED)
        scheduleVacuumLambda.assertions().isCalledOnce()
        result.destroy()
    }

    @Test
    fun `create refuses a session without a Matrix database key`() = runTest {
        val sut = createRustMatrixClientFactory()

        val result = runCatching { sut.create(aSessionData().copy(isTokenValid = true)) }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}

fun TestScope.createRustMatrixClientFactory(
    cacheDirectory: File = File("/cache"),
    sessionStore: SessionStore = InMemorySessionStore(
        updateUserProfileResult = { _, _, _ -> },
    ),
    clientBuilderProvider: ClientBuilderProvider = FakeClientBuilderProvider(),
    workManagerScheduler: FakeWorkManagerScheduler = FakeWorkManagerScheduler(),
    vaultKeyService: VaultKeyService = FakeVaultKeyService(),
) = RustMatrixClientFactory(
    cacheDirectory = cacheDirectory,
    appCoroutineScope = backgroundScope,
    coroutineDispatchers = testCoroutineDispatchers(),
    sessionStore = sessionStore,
    userAgentProvider = SimpleUserAgentProvider(),
    userCertificatesProvider = FakeUserCertificatesProvider(),
    proxyProvider = FakeProxyProvider(),
    clock = FakeSystemClock(),
    analyticsService = FakeAnalyticsService(),
    featureFlagService = FakeFeatureFlagService(),
    timelineEventFilterFactory = FakeTimelineEventFilterFactory(),
    clientBuilderProvider = clientBuilderProvider,
    sqliteStoreBuilderProvider = FakeSqliteStoreBuilderProvider(),
    workManagerScheduler = workManagerScheduler,
    sensitiveFileStore = InMemorySensitiveFileStore(),
    secureMediaLeaseManager = DisabledSecureMediaLeaseManager,
    dataDeletionCoordinator = FakeDataDeletionCoordinator(),
    permanentAccountDeletionCoordinator = FakePermanentAccountDeletionCoordinator(),
    accountReactivationScopeStore = mockk<AccountReactivationScopeStore>(relaxed = true),
    dataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(),
    deviceRootKeyService = FakeDeviceRootKeyService("matrix-factory-installation-test"),
    managementControlService = mockk(),
    appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    vaultKeyService = vaultKeyService,
    accountSessionSecretVault = AccountSessionSecretVault(vaultKeyService, FakeFeatureFlagService()),
)
