/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.impl.ClientBuilderProvider
import io.element.android.libraries.matrix.impl.FakeClientBuilderProvider
import io.element.android.libraries.matrix.impl.createRustMatrixClientFactory
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiHomeserverLoginDetails
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.libraries.matrix.test.auth.FakeOAuthRedirectUrlProvider
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.libraries.workmanager.test.FakeWorkManagerScheduler
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class RustMatrixAuthenticationServiceTest {
    @Test
    fun `Next homeserver route accepts only the configured canonical HTTPS route`() {
        val configured = "https://matrix-next.yizhesy.cn"

        assertThat(
            enforceNextHomeserverRoute(
                candidate = "https://MATRIX-NEXT.YIZHESY.CN:443/",
                configuredHomeserver = configured,
                isNextDeployment = true,
            )
        ).isEqualTo(configured)
        assertThat(
            enforceNextHomeserverRoute(
                candidate = "matrix-next.yizhesy.cn",
                configuredHomeserver = configured,
                isNextDeployment = true,
                allowServerName = true,
            )
        ).isEqualTo(configured)
    }

    @Test
    fun `Next homeserver route rejects Stable and alternate routes`() {
        val configured = "https://matrix-next.yizhesy.cn"
        val rejected = listOf(
            "https://matrix.yizhesy.cn",
            "http://matrix-next.yizhesy.cn",
            "https://matrix-next.yizhesy.cn:8448",
            "https://matrix-next.yizhesy.cn/other",
            "https://user@matrix-next.yizhesy.cn",
            "https://matrix-next.yizhesy.cn?redirect=stable",
            "https://matrix-next.yizhesy.cn#stable",
            "matrix-next.yizhesy.cn",
        )

        rejected.forEach { candidate ->
            assertThat(
                runCatching {
                    enforceNextHomeserverRoute(
                        candidate = candidate,
                        configuredHomeserver = configured,
                        isNextDeployment = true,
                    )
                }.isFailure
            ).isTrue()
        }
    }

    @Test
    fun `Next QR server name rejects a different host`() {
        assertThat(
            runCatching {
                enforceNextHomeserverRoute(
                    candidate = "matrix.yizhesy.cn",
                    configuredHomeserver = "https://matrix-next.yizhesy.cn",
                    isNextDeployment = true,
                    allowServerName = true,
                )
            }.isFailure
        ).isTrue()
    }

    @Test
    fun `Stable homeserver route remains user selectable`() {
        assertThat(
            enforceNextHomeserverRoute(
                candidate = "matrix.org",
                configuredHomeserver = "https://matrix.yizhesy.cn",
                isNextDeployment = false,
            )
        ).isEqualTo("matrix.org")
    }

    @Test
    fun `restoreSession refuses a legacy session without a database key`() = runTest {
        val sessionData = configuredSessionData(isTokenValid = true)
        val sessionStore = InMemorySessionStore(initialList = listOf(sessionData))
        var clientBuilderRequested = false
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    clientBuilderRequested = true
                    FakeFfiClientBuilder()
                }
            ),
        )

        val result = sut.restoreSession(SessionId(sessionData.userId))

        assertThat(result.isFailure).isTrue()
        assertThat(clientBuilderRequested).isFalse()
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isFalse()
    }

    @Test
    fun `setHomeserver is successful`() = runTest {
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = {
                            configuredClient(
                                homeserverLoginDetailsResult = {
                                    FakeFfiHomeserverLoginDetails(url = testHomeserver)
                                }
                            )
                        }
                    )
                }
            ),
        )
        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
    }

    @Test
    fun `password login replaces an existing invalid session`() = runTest {
        val oldSession = configuredSessionData(isTokenValid = false)
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        var initialDeviceName: String? = null
        val client = configuredClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
            withUtdHook = {},
            loginResult = { _, _, deviceName, _ -> initialDeviceName = deviceName },
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )

        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(logoutCount).isEqualTo(0)
        assertThat(initialDeviceName).isEqualTo("乙者 Android")
    }

    @Test
    fun `password login uses the pre-login snapshot when the SDK session callback rewrites the old row`() = runTest {
        val oldSession = configuredSessionData(isTokenValid = false)
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        val client = configuredClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
            withUtdHook = {},
            loginResult = { _, _, _, _ ->
                // Mirrors saveSessionInKeychain racing ahead of duplicate-account
                // detection after the server has accepted the new login.
                sessionStore.updateData(
                    oldSession.copy(
                        isTokenValid = true,
                        passphrase = "stale-database-key",
                    )
                )
            },
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )

        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEqualTo("stale-database-key")
        assertThat(logoutCount).isEqualTo(0)
    }

    @Test
    fun `password login still rejects an existing valid session`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(configuredSessionData(isTokenValid = true).copy(passphrase = "existing-database-key")),
        )
        var logoutCount = 0
        val client = configuredClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
        )

        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(logoutCount).isEqualTo(1)
    }

    @Test
    fun `password login replaces a valid session after its local restore failed`() = runTest {
        val oldSession = configuredSessionData(isTokenValid = true).copy(passphrase = "existing-database-key")
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        val restoreClient = configuredClient(
            restoreSessionResult = { error("Encrypted Matrix store cannot be restored") },
        )
        val loginClient = configuredClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
        )
        val clients = ArrayDeque(listOf(restoreClient, loginClient))
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { clients.removeFirst() }) },
            ),
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )

        assertThat(sut.restoreSession(SessionId(oldSession.userId)).isFailure).isTrue()
        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEqualTo("existing-database-key")
        assertThat(logoutCount).isEqualTo(0)
    }

    @Test
    fun `password login replaces an unrestorable legacy session marked valid`() = runTest {
        val sessionStore = InMemorySessionStore(initialList = listOf(configuredSessionData(isTokenValid = true)))
        val client = configuredClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
            withUtdHook = {},
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )

        assertThat(sut.setHomeserver(testHomeserver).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEmpty()
    }

    private val testHomeserver: String
        get() = if (NextDeploymentConfig.isNextDeployment) NextDeploymentConfig.matrixHomeserverUrl else "https://matrix.org"

    private fun configuredSessionData(isTokenValid: Boolean) =
        aSessionData(isTokenValid = isTokenValid).copy(homeserverUrl = testHomeserver)

    private fun configuredClient(
        homeserverLoginDetailsResult: () -> org.matrix.rustcomponents.sdk.HomeserverLoginDetails =
            { FakeFfiHomeserverLoginDetails(url = testHomeserver) },
        withUtdHook: (org.matrix.rustcomponents.sdk.UnableToDecryptDelegate) -> Unit = {},
        loginResult: suspend (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
        logoutResult: suspend () -> Unit = {},
        restoreSessionResult: suspend (org.matrix.rustcomponents.sdk.Session) -> Unit = {},
    ) = FakeFfiClient(
        session = aRustSession().copy(homeserverUrl = testHomeserver),
        homeserverLoginDetailsResult = homeserverLoginDetailsResult,
        withUtdHook = withUtdHook,
        loginResult = loginResult,
        logoutResult = logoutResult,
        restoreSessionResult = restoreSessionResult,
    )

    private fun TestScope.createRustMatrixAuthenticationService(
        sessionStore: SessionStore = InMemorySessionStore(),
        clientBuilderProvider: ClientBuilderProvider = FakeClientBuilderProvider(),
        enterpriseService: EnterpriseService = FakeEnterpriseService(),
        workManagerScheduler: FakeWorkManagerScheduler = FakeWorkManagerScheduler(),
    ): RustMatrixAuthenticationService {
        val baseDirectory = File("/base")
        val cacheDirectory = File("/cache")
        val rustMatrixClientFactory = createRustMatrixClientFactory(
            cacheDirectory = cacheDirectory,
            sessionStore = sessionStore,
            clientBuilderProvider = clientBuilderProvider,
            workManagerScheduler = workManagerScheduler,
        )
        return RustMatrixAuthenticationService(
            sessionPathsFactory = SessionPathsFactory(baseDirectory, cacheDirectory),
            coroutineDispatchers = testCoroutineDispatchers(),
            sessionStore = sessionStore,
            rustMatrixClientFactory = rustMatrixClientFactory,
            secretGenerator = FakeSecretGenerator(),
            oAuthConfigurationProvider = OAuthConfigurationProvider(
                buildMeta = aBuildMeta(),
                oAuthRedirectUrlProvider = FakeOAuthRedirectUrlProvider(),
            ),
            enterpriseService = enterpriseService,
        )
    }
}
