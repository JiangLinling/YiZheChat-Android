/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.auth.OpenIdToken
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushNewGatewayHandler
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushStore
import io.element.android.libraries.pushproviders.unifiedpush.UnregisterUnifiedPushUseCase
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EmbeddedNtfyManagerTest {
    @Test
    fun `minimum coordinated strategy refreshes only Next registrations`() {
        assertThat(minimumCoordinatedStrategyVersion(isNextDeployment = true)).isEqualTo(4)
        assertThat(minimumCoordinatedStrategyVersion(isNextDeployment = false)).isEqualTo(2)
    }

    @Test
    fun `strategy refresh preserves cursor when ntfy identity is reused`() = runTest {
        val existing = EmbeddedNtfyRegistration(
            clientSecret = CLIENT_SECRET,
            server = "https://push.example.test",
            topic = "topic",
            username = "user",
            password = "old-password",
            endpoint = "https://push.example.test/topic",
            gateway = "https://old-gateway.example.test/_matrix/push/v1/notify",
            managementToken = "old-management-token",
            expiresAt = Long.MAX_VALUE,
            lastMessageId = "cursor-42",
            strategyVersion = 1,
        )
        val refreshed = existing.copy(
            password = "new-password",
            gateway = "http://next-push-registrar:8080/_matrix/push/v1/notify?provider=coordinator",
            managementToken = MANAGEMENT_TOKEN,
            lastMessageId = null,
            strategyVersion = 4,
        )
        val persisted = refreshed.copy(lastMessageId = existing.lastMessageId, deploymentBinding = embeddedPushDeploymentBinding())
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val gatewayHandler = mockk<UnifiedPushNewGatewayHandler>()
        coEvery { registrationStore.get(CLIENT_SECRET) } returns existing
        coEvery { registrationStore.upsert(persisted) } returns Unit
        coEvery { registrar.register(any(), CLIENT_SECRET, any()) } returns Result.success(refreshed)
        coEvery { registrar.confirm(MANAGEMENT_TOKEN) } returns Result.success(Unit)
        coEvery {
            gatewayHandler.handle(persisted.endpoint, persisted.gateway, CLIENT_SECRET)
        } returns Result.success(Unit)
        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true),
            newGatewayHandler = gatewayHandler,
            unregisterUnifiedPushUseCase = mockk(),
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        )
        val matrixClient = FakeMatrixClient(
            requestOpenIdTokenLambda = {
                Result.success(OpenIdToken("open-id", "Bearer", "example.test", 60))
            }
        )

        manager.register(matrixClient, CLIENT_SECRET).getOrThrow()

        coVerify(exactly = 1) { registrationStore.upsert(persisted) }
        coVerify(exactly = 1) { gatewayHandler.handle(persisted.endpoint, persisted.gateway, CLIENT_SECRET) }
        coVerify(exactly = 1) { registrar.confirm(MANAGEMENT_TOKEN) }
        coVerify(exactly = 0) { registrar.unregister(any()) }
    }

    @Test
    fun `deployment refresh replaces obsolete origin without revoking the reused credential lease`() = runTest {
        val binding = embeddedPushDeploymentBinding()
        assertThat(binding).isNotNull()
        val previous = EmbeddedNtfyRegistration(
            clientSecret = CLIENT_SECRET,
            server = "https://matrix-next.yizhesy.cn:18443/ntfy",
            topic = "retained-topic",
            username = "retained-user",
            password = "test-password",
            endpoint = "https://matrix-next.yizhesy.cn:18443/ntfy/retained-topic?up=1",
            gateway = "https://matrix-next.yizhesy.cn:18443/ntfy/_matrix/push/v1/notify?provider=coordinator",
            managementToken = MANAGEMENT_TOKEN,
            expiresAt = Long.MAX_VALUE,
            lastMessageId = "old-origin-cursor",
            strategyVersion = 4,
        )
        val refreshed = previous.copy(
            server = "https://push.yizhesy.cn",
            endpoint = "https://push.yizhesy.cn/retained-topic?up=1",
            gateway = "https://push.yizhesy.cn/_matrix/push/v1/notify?provider=coordinator",
            lastMessageId = null,
        )
        val persisted = refreshed.copy(deploymentBinding = binding)
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val gateway = mockk<UnifiedPushNewGatewayHandler>()
        coEvery { registrationStore.get(CLIENT_SECRET) } returns previous
        coEvery { registrationStore.upsert(persisted) } returns Unit
        coEvery { registrar.register(any(), CLIENT_SECRET, any()) } returns Result.success(refreshed)
        coEvery { registrar.confirm(MANAGEMENT_TOKEN) } returns Result.success(Unit)
        coEvery { gateway.handle(persisted.endpoint, persisted.gateway, CLIENT_SECRET) } returns Result.success(Unit)
        val operations = FakeAppOperationalStateCoordinator()
        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true),
            newGatewayHandler = gateway,
            unregisterUnifiedPushUseCase = mockk(),
            appOperationalStateCoordinator = operations,
        )
        val client = FakeMatrixClient(requestOpenIdTokenLambda = {
            Result.success(OpenIdToken("test-open-id", "Bearer", "yizhesy.cn", 60))
        })

        manager.register(client, CLIENT_SECRET).getOrThrow()

        coVerify(exactly = 1) { registrar.register(any(), CLIENT_SECRET, any()) }
        coVerify(exactly = 1) { registrationStore.upsert(persisted) }
        coVerify(exactly = 1) { registrar.confirm(MANAGEMENT_TOKEN) }
        coVerify(exactly = 0) { registrar.unregister(any()) }
        assertThat(persisted.lastMessageId).isNull()
        assertThat(operations.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_19)
    }

    @Test
    fun `LC-22 revokes every account registration with the parent operation`() = runTest {
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val unregisterUseCase = mockk<UnregisterUnifiedPushUseCase>()
        val coordinator = FakeAppOperationalStateCoordinator()
        coEvery { registrationStore.get(CLIENT_SECRET) } returns null
        coEvery { registrationStore.remove(CLIENT_SECRET) } returns Unit
        coEvery { registrationStore.getAll() } returns emptyList()
        coEvery {
            unregisterUseCase.unregister(any(), CLIENT_SECRET, unregisterUnifiedPush = false)
        } returns Result.success(Unit)
        coEvery { unregisterUseCase.cleanup(CLIENT_SECRET, unregisterUnifiedPush = false) } returns Unit
        coEvery { registrar.unregisterAccount(any()) } returns Result.success(Unit)
        val matrixClient = FakeMatrixClient(
            requestOpenIdTokenLambda = {
                Result.success(OpenIdToken("open-id", "Bearer", "example.test", 60))
            }
        )
        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true),
            newGatewayHandler = mockk<UnifiedPushNewGatewayHandler>(),
            unregisterUnifiedPushUseCase = unregisterUseCase,
            appOperationalStateCoordinator = coordinator,
        )

        coordinator.perform(
            lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_22,
                trigger = "permanent_delete",
                accountIdHash = "a".repeat(64),
                matrixDeviceIdHash = "b".repeat(64),
            )
        ) {
            manager.unregisterAccount(matrixClient, CLIENT_SECRET).getOrThrow()
        }

        coVerify(exactly = 1) { registrar.unregisterAccount(match { it.accessToken == "open-id" }) }
        coVerify(exactly = 0) { registrar.unregister(any()) }
        coVerify(exactly = 1) { registrationStore.remove(CLIENT_SECRET) }
    }

    @Test
    fun `device unregister nested in LC-22 uses a distinct LC-19 operation`() = runTest {
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val unregisterUseCase = mockk<UnregisterUnifiedPushUseCase>()
        val coordinator = FakeAppOperationalStateCoordinator()
        val registration = EmbeddedNtfyRegistration(
            clientSecret = CLIENT_SECRET,
            server = "https://push.example.test",
            topic = "topic",
            username = "user",
            password = "password",
            endpoint = "https://push.example.test/topic",
            gateway = "https://push.example.test/gateway",
            managementToken = MANAGEMENT_TOKEN,
            expiresAt = Long.MAX_VALUE,
        )
        coEvery { registrationStore.get(CLIENT_SECRET) } returns registration
        coEvery { registrationStore.remove(CLIENT_SECRET) } returns Unit
        coEvery { registrationStore.getAll() } returns emptyList()
        coEvery {
            unregisterUseCase.unregister(any(), CLIENT_SECRET, unregisterUnifiedPush = false)
        } returns Result.success(Unit)
        coEvery { unregisterUseCase.cleanup(CLIENT_SECRET, unregisterUnifiedPush = false) } returns Unit
        coEvery { registrar.unregister(MANAGEMENT_TOKEN) } returns Result.success(Unit)
        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true),
            newGatewayHandler = mockk<UnifiedPushNewGatewayHandler>(),
            unregisterUnifiedPushUseCase = unregisterUseCase,
            appOperationalStateCoordinator = coordinator,
        )

        coordinator.perform(
            lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_22,
                trigger = "remove_local_account",
                accountIdHash = "a".repeat(64),
                matrixDeviceIdHash = "b".repeat(64),
            )
        ) {
            manager.unregister(FakeMatrixClient(), CLIENT_SECRET).getOrThrow()
        }

        assertThat(coordinator.startedRequests.map { it.lifecycleId }).containsExactly(
            FullAppLifecycleId.LC_22,
            FullAppLifecycleId.LC_19,
        ).inOrder()
        assertThat(coordinator.startedRequests.map { it.trigger }).containsExactly(
            "remove_local_account",
            "device_revoke",
        ).inOrder()
        coVerify(exactly = 1) { registrar.unregister(MANAGEMENT_TOKEN) }
        coVerify(exactly = 0) { registrar.unregisterAccount(any()) }
        coVerify(exactly = 1) { registrationStore.remove(CLIENT_SECRET) }
    }

    @Test
    fun `explicit unregister fails closed when registrar does not confirm revocation`() = runTest {
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val unregisterUseCase = mockk<UnregisterUnifiedPushUseCase>()
        val registration = EmbeddedNtfyRegistration(
            clientSecret = CLIENT_SECRET,
            server = "https://push.example.test",
            topic = "topic",
            username = "user",
            password = "password",
            endpoint = "https://push.example.test/topic",
            gateway = "https://push.example.test/gateway",
            managementToken = MANAGEMENT_TOKEN,
            expiresAt = Long.MAX_VALUE,
        )
        coEvery { registrationStore.get(CLIENT_SECRET) } returns registration
        coEvery {
            unregisterUseCase.unregister(any(), CLIENT_SECRET, unregisterUnifiedPush = false)
        } returns Result.success(Unit)
        coEvery { registrar.unregister(MANAGEMENT_TOKEN) } returns Result.failure(REVOCATION_FAILURE)

        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true),
            newGatewayHandler = mockk<UnifiedPushNewGatewayHandler>(),
            unregisterUnifiedPushUseCase = unregisterUseCase,
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        )

        val result = manager.unregister(FakeMatrixClient(), CLIENT_SECRET)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).isEqualTo(REVOCATION_FAILURE.message)
        coVerify(exactly = 1) { registrar.unregister(MANAGEMENT_TOKEN) }
        coVerify(exactly = 0) { registrationStore.remove(any()) }
        coVerify(exactly = 0) { unregisterUseCase.cleanup(any(), any()) }
    }

    @Test
    fun `failed registration rollback retains management token when registrar cannot revoke`() = runTest {
        val registrationStore = mockk<EmbeddedNtfyRegistrationStore>()
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val registration = EmbeddedNtfyRegistration(
            clientSecret = CLIENT_SECRET,
            server = "https://push.example.test",
            topic = "topic",
            username = "user",
            password = "password",
            endpoint = "https://push.example.test/topic",
            gateway = "https://push.example.test/gateway",
            managementToken = MANAGEMENT_TOKEN,
            expiresAt = Long.MAX_VALUE,
            deploymentBinding = embeddedPushDeploymentBinding(),
        )
        val unifiedPushStore = mockk<UnifiedPushStore>(relaxed = true)
        val newGatewayHandler = mockk<UnifiedPushNewGatewayHandler>()
        coEvery { registrationStore.get(CLIENT_SECRET) } returns null
        coEvery { registrationStore.upsert(registration) } returns Unit
        coEvery { registrar.register(any(), CLIENT_SECRET, any()) } returns Result.success(registration)
        coEvery {
            newGatewayHandler.handle(registration.endpoint, registration.gateway, CLIENT_SECRET)
        } returns Result.failure(GATEWAY_FAILURE)
        coEvery { registrar.unregister(MANAGEMENT_TOKEN) } returns Result.failure(REVOCATION_FAILURE)
        val matrixClient = FakeMatrixClient(
            requestOpenIdTokenLambda = {
                Result.success(OpenIdToken("open-id", "Bearer", "example.test", 60))
            }
        )
        val manager = DefaultEmbeddedNtfyManager(
            context = mockk<Context>(relaxed = true),
            registrationStore = registrationStore,
            registrar = registrar,
            unifiedPushStore = unifiedPushStore,
            newGatewayHandler = newGatewayHandler,
            unregisterUnifiedPushUseCase = mockk(),
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        )

        val result = manager.register(matrixClient, CLIENT_SECRET)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).isEqualTo(GATEWAY_FAILURE.message)
        coVerify(exactly = 1) { registrationStore.upsert(registration) }
        coVerify(exactly = 1) { registrar.unregister(MANAGEMENT_TOKEN) }
        coVerify(exactly = 0) { registrationStore.remove(any()) }
        coVerify(exactly = 0) { unifiedPushStore.storeUpEndpoint(CLIENT_SECRET, null) }
        coVerify(exactly = 0) { unifiedPushStore.storePushGateway(CLIENT_SECRET, null) }
    }

    private companion object {
        const val CLIENT_SECRET = "client-secret"
        const val MANAGEMENT_TOKEN = "management-token"
        val REVOCATION_FAILURE = IllegalStateException("registrar did not confirm revocation")
        val GATEWAY_FAILURE = IllegalStateException("Matrix pusher registration failed")
    }
}
