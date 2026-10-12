/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import com.google.common.truth.Truth.assertThat
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
        val sessionData = aSessionData(isTokenValid = true)
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
                            FakeFfiClient(
                                homeserverLoginDetailsResult = {
                                    FakeFfiHomeserverLoginDetails()
                                }
                            )
                        }
                    )
                }
            ),
        )
        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
    }

    @Test
    fun `password login replaces an existing invalid session`() = runTest {
        val oldSession = aSessionData(isTokenValid = false)
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        var initialDeviceName: String? = null
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
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

        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(logoutCount).isEqualTo(0)
        assertThat(initialDeviceName).isEqualTo("乙者 Android")
    }

    @Test
    fun `password login uses the pre-login snapshot when the SDK session callback rewrites the old row`() = runTest {
        val oldSession = aSessionData(isTokenValid = false)
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
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

        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEqualTo("stale-database-key")
        assertThat(logoutCount).isEqualTo(0)
    }

    @Test
    fun `password login still rejects an existing valid session`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData(isTokenValid = true).copy(passphrase = "existing-database-key")),
        )
        var logoutCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
        )

        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(logoutCount).isEqualTo(1)
    }

    @Test
    fun `password login replaces a valid session after its local restore failed`() = runTest {
        val oldSession = aSessionData(isTokenValid = true).copy(passphrase = "existing-database-key")
        val sessionStore = InMemorySessionStore(initialList = listOf(oldSession))
        var logoutCount = 0
        val restoreClient = FakeFfiClient(
            restoreSessionResult = { error("Encrypted Matrix store cannot be restored") },
        )
        val loginClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
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
        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEqualTo("existing-database-key")
        assertThat(logoutCount).isEqualTo(0)
    }

    @Test
    fun `password login replaces an unrestorable legacy session marked valid`() = runTest {
        val sessionStore = InMemorySessionStore(initialList = listOf(aSessionData(isTokenValid = true)))
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = { FakeFfiClientBuilder(buildResult = { client }) },
            ),
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )

        assertThat(sut.setHomeserver("matrix.org").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(sessionStore.getAllSessions().single().isTokenValid).isTrue()
        assertThat(sessionStore.getAllSessions().single().passphrase).isNotEmpty()
    }

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
