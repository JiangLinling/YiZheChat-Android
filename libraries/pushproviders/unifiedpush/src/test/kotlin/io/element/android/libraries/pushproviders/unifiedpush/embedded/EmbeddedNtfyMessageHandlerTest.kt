/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.push.test.push.FakeFetchPushForegroundServiceManager
import io.element.android.libraries.push.test.test.FakePushHandler
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushParserTest
import io.element.android.libraries.pushproviders.unifiedpush.createUnifiedPushParser
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EmbeddedNtfyMessageHandlerTest {
    @Test
    fun `suppressed delivery never reaches the notification handler`() = runTest {
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        coEvery { registrar.claimDelivery(any(), any(), any()) } returns Result.success(DeliveryClaimDecision.SUPPRESS)
        var handled = false
        var stopped = false
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        val messageHandler = EmbeddedNtfyMessageHandler(
            pushParser = createUnifiedPushParser(),
            pushHandler = FakePushHandler(handleResult = { _, _ ->
                handled = true
                true
            }),
            fetchPushForegroundServiceManager = FakeFetchPushForegroundServiceManager(unlock = {
                stopped = true
                true
            }),
            registrar = registrar,
            appOperationalStateCoordinator = lifecycleCoordinator,
        )

        val result = messageHandler.handle(
            content = UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(),
            clientSecret = CLIENT_SECRET,
            managementToken = MANAGEMENT_TOKEN,
        )

        assertThat(result).isFalse()
        assertThat(handled).isFalse()
        assertThat(stopped).isTrue()
        assertThat(lifecycleCoordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_19)
        assertThat(lifecycleCoordinator.startedRequests.single().trigger).isEqualTo("delivery_claim")
        assertThat(lifecycleCoordinator.clientReceipts.map { it.component })
            .containsExactly(LifecycleComponent.ANDROID_SERVICE)
        coVerify(exactly = 0) { registrar.completeDelivery(any(), any(), any()) }
        coVerify(exactly = 0) { registrar.releaseDelivery(any(), any(), any()) }
    }

    @Test
    fun `claimed and handled delivery is completed`() = runTest {
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        coEvery { registrar.claimDelivery(any(), any(), any()) } returns Result.success(DeliveryClaimDecision.CLAIMED)
        coEvery { registrar.completeDelivery(any(), any(), any()) } returns Result.success(Unit)
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        val messageHandler = EmbeddedNtfyMessageHandler(
            pushParser = createUnifiedPushParser(),
            pushHandler = FakePushHandler(handleResult = { _, _ -> true }),
            fetchPushForegroundServiceManager = FakeFetchPushForegroundServiceManager(),
            registrar = registrar,
            appOperationalStateCoordinator = lifecycleCoordinator,
        )

        val result = messageHandler.handle(
            content = UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(),
            clientSecret = CLIENT_SECRET,
            managementToken = MANAGEMENT_TOKEN,
        )

        assertThat(result).isTrue()
        assertThat(lifecycleCoordinator.clientReceipts.map { it.component })
            .containsExactly(LifecycleComponent.ANDROID_SERVICE)
        coVerify(exactly = 1) {
            registrar.completeDelivery(MANAGEMENT_TOKEN, AN_EVENT_ID.value, A_ROOM_ID.value)
        }
        coVerify(exactly = 0) { registrar.releaseDelivery(any(), any(), any()) }
    }

    @Test
    fun `claimed but unfinished delivery is released for fallback`() = runTest {
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        coEvery { registrar.claimDelivery(any(), any(), any()) } returns Result.success(DeliveryClaimDecision.CLAIMED)
        coEvery { registrar.releaseDelivery(any(), any(), any()) } returns Result.success(Unit)
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        val messageHandler = EmbeddedNtfyMessageHandler(
            pushParser = createUnifiedPushParser(),
            pushHandler = FakePushHandler(handleResult = { _, _ -> false }),
            fetchPushForegroundServiceManager = FakeFetchPushForegroundServiceManager(),
            registrar = registrar,
            appOperationalStateCoordinator = lifecycleCoordinator,
        )

        val result = messageHandler.handle(
            content = UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(),
            clientSecret = CLIENT_SECRET,
            managementToken = MANAGEMENT_TOKEN,
        )

        assertThat(result).isFalse()
        assertThat(lifecycleCoordinator.clientReceipts.map { it.component })
            .containsExactly(LifecycleComponent.ANDROID_SERVICE)
        coVerify(exactly = 1) {
            registrar.releaseDelivery(MANAGEMENT_TOKEN, AN_EVENT_ID.value, A_ROOM_ID.value)
        }
        coVerify(exactly = 0) { registrar.completeDelivery(any(), any(), any()) }
    }

    @Test
    fun `response lost completion retries the original operation instead of creating another`() = runTest {
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        coEvery { registrar.claimDelivery(any(), any(), any()) } returnsMany listOf(
            Result.success(DeliveryClaimDecision.CLAIMED), Result.success(DeliveryClaimDecision.SUPPRESS),
        )
        coEvery { registrar.completeDelivery(any(), any(), any()) } returns Result.failure(java.io.IOException("response lost"))
        val coordinator = FakeAppOperationalStateCoordinator()
        var handled = 0
        val handler = EmbeddedNtfyMessageHandler(createUnifiedPushParser(),
            FakePushHandler(handleResult = { _, _ -> handled += 1; true }),
            FakeFetchPushForegroundServiceManager(), registrar, coordinator)
        val first = runCatching {
            handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), CLIENT_SECRET, managementToken = MANAGEMENT_TOKEN)
        }
        assertThat(first.isFailure).isTrue()
        assertThat(coordinator.clientReceipts).isEmpty()
        assertThat(handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), CLIENT_SECRET,
            managementToken = MANAGEMENT_TOKEN)).isFalse()
        assertThat(coordinator.startedRequests.map { it.operationId }.distinct()).hasSize(1)
        assertThat(coordinator.clientReceipts.single().operationId).isEqualTo(coordinator.startedRequests.first().operationId)
        assertThat(handled).isEqualTo(1)
    }

    @Test
    fun `registration and event scope keeps distinct deliveries separate`() = runTest {
        val registrar = mockk<EmbeddedNtfyRegistrar>()
        val coordinator = FakeAppOperationalStateCoordinator()
        val handler = EmbeddedNtfyMessageHandler(createUnifiedPushParser(), FakePushHandler(handleResult = { _, _ -> false }),
            FakeFetchPushForegroundServiceManager(), registrar, coordinator)
        handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), CLIENT_SECRET)
        handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), "other-client-secret")
        handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.replace(AN_EVENT_ID.value, "\$other-event").toByteArray(), CLIENT_SECRET)
        assertThat(coordinator.startedRequests.map { it.operationId }.distinct()).hasSize(3)
    }

    @Test
    fun `signed terminal delivery is retired without replaying product work or inventing receipts`() = runTest {
        for (status in listOf(
            io.element.android.security.dataprotection.api.LifecycleOperationStatus.SUCCEEDED,
            io.element.android.security.dataprotection.api.LifecycleOperationStatus.FAILED_FINAL,
        )) {
            val delegate = FakeAppOperationalStateCoordinator()
            val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by delegate {
                override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest) =
                    delegate.start(request).copy(status = status, terminalReceiptId = "original-signed-terminal",
                        createdAtEpochMillis = 1, deadlineEpochMillis = 2)
            }
            val registrar = mockk<EmbeddedNtfyRegistrar>()
            val handler = EmbeddedNtfyMessageHandler(createUnifiedPushParser(), FakePushHandler(),
                FakeFetchPushForegroundServiceManager(), registrar, coordinator)
            assertThat(handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), CLIENT_SECRET,
                managementToken = MANAGEMENT_TOKEN)).isFalse()
            assertThat(delegate.clientReceipts).isEmpty()
            coVerify(exactly = 0) { registrar.claimDelivery(any(), any(), any()) }
        }
    }

    @Test
    fun `terminal projection without receipt fails closed`() = runTest {
        val delegate = FakeAppOperationalStateCoordinator()
        val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by delegate {
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest) =
                delegate.start(request).copy(status = io.element.android.security.dataprotection.api.LifecycleOperationStatus.SUCCEEDED)
        }
        val handler = EmbeddedNtfyMessageHandler(createUnifiedPushParser(), FakePushHandler(),
            FakeFetchPushForegroundServiceManager(), mockk(), coordinator)
        val result = runCatching { handler.handle(UnifiedPushParserTest.UNIFIED_PUSH_DATA.toByteArray(), CLIENT_SECRET) }
        assertThat(result.isFailure).isTrue()
        assertThat(delegate.clientReceipts).isEmpty()
    }

    private companion object {
        const val CLIENT_SECRET = "client-secret"
        const val MANAGEMENT_TOKEN = "management-token"
    }
}
