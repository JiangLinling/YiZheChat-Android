/* Copyright (c) 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OperationalRefreshRecoveryTest {
    @Test
    fun `135 active operations are all verified with at most four concurrent reads`() = runTest {
        val ids = List(135) { "refresh-operation-$it" }
        val fresh = snapshot(System.currentTimeMillis(), ids)
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns fresh
        val reads = mutableListOf<String>()
        var active = 0
        var peak = 0
        coEvery { service.lifecycleOperation(any()) } coAnswers {
            val id = firstArg<String>()
            reads += id
            active++
            peak = maxOf(peak, active)
            try {
                delay(100)
                operation(id, fresh.issuedAtEpochMillis)
            } finally {
                active--
            }
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        val started = testScheduler.currentTime
        assertThat(coordinator.refresh("a".repeat(64), "b".repeat(64))).isSameInstanceAs(fresh)
        assertThat(peak).isEqualTo(4)
        assertThat(active).isEqualTo(0)
        assertThat(reads).containsExactlyElementsIn(ids)
        assertThat(coordinator.operations.value.keys).containsExactlyElementsIn(ids).inOrder()
        assertThat(testScheduler.currentTime - started).isEqualTo(3400L)
    }

    @Test
    fun `second block failure cannot publish any partially recovered fresh state`() = runTest {
        val now = System.currentTimeMillis()
        val previous = snapshot(now, emptyList())
        val ids = List(135) { "refresh-operation-$it" }
        val fresh = snapshot(now + 1, ids)
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returnsMany listOf(previous, fresh)
        coEvery { service.lifecycleOperation(any()) } coAnswers {
            val id = firstArg<String>()
            delay(100)
            if (id == ids[128]) throw SecurityException("scope mismatch")
            operation(id, now)
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        coordinator.refresh("a".repeat(64), "b".repeat(64))
        val result = runCatching { coordinator.refresh("a".repeat(64), "b".repeat(64)) }
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        val state = coordinator.state.value as AppOperationalCoordinatorState.RetryableFailure
        assertThat(state.lastVerifiedSnapshot).isSameInstanceAs(previous)
        assertThat(coordinator.operations.value).isEmpty()
    }

    @Test
    fun `complete batch avoids individual reads and publishes all original operations`() = runTest {
        val ids = List(128) { "refresh-operation-$it" }
        val fresh = snapshot(System.currentTimeMillis(), ids).copy(verifiedSignedJson = "{}")
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns fresh
        coEvery { service.lifecycleOperationsForSnapshot(fresh, ids) } returns ids.map { operation(it, fresh.issuedAtEpochMillis) }
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        coordinator.refresh("a".repeat(64), "b".repeat(64))
        assertThat(coordinator.operations.value.keys).containsExactlyElementsIn(ids)
        io.mockk.coVerify(exactly = 0) { service.lifecycleOperation(any()) }
    }

    @Test
    fun `unavailable legacy endpoint falls back but an invalid batch never does`() = runTest {
        val ids = listOf("refresh-operation-legacy")
        val fresh = snapshot(System.currentTimeMillis(), ids).copy(verifiedSignedJson = "{}")
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns fresh
        coEvery { service.lifecycleOperationsForSnapshot(any(), any()) } returns null
        coEvery { service.lifecycleOperation(ids.single()) } returns operation(ids.single(), fresh.issuedAtEpochMillis)
        DefaultAppOperationalStateCoordinator(service).refresh("a".repeat(64), "b".repeat(64))
        io.mockk.coVerify(exactly = 1) { service.lifecycleOperation(ids.single()) }
        coEvery { service.lifecycleOperationsForSnapshot(any(), any()) } throws SecurityException("invalid batch")
        val rejected = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { rejected.refresh("a".repeat(64), "b".repeat(64)) }.isFailure).isTrue()
        assertThat(rejected.operations.value).isEmpty()
        io.mockk.coVerify(exactly = 1) { service.lifecycleOperation(ids.single()) }
    }

    @Test
    fun `later batch failure cannot publish an earlier verified batch`() = runTest {
        val ids = List(135) { "refresh-operation-$it" }
        val fresh = snapshot(System.currentTimeMillis(), ids).copy(verifiedSignedJson = "{}")
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns fresh
        coEvery { service.lifecycleOperationsForSnapshot(any(), ids.take(128)) } returns ids.take(128).map {
            operation(it, fresh.issuedAtEpochMillis)
        }
        coEvery { service.lifecycleOperationsForSnapshot(any(), ids.drop(128)) } throws SecurityException("later batch rejected")
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { coordinator.refresh("a".repeat(64), "b".repeat(64)) }.isFailure).isTrue()
        assertThat(coordinator.operations.value).isEmpty()
        io.mockk.coVerify(exactly = 0) { service.lifecycleOperation(any()) }
    }

    private fun operation(id: String, now: Long) = LifecycleOperation(
        operationId = id,
        lifecycleId = FullAppLifecycleId.LC_13,
        idempotencyKey = id,
        subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)),
        status = LifecycleOperationStatus.RUNNING,
        currentPhase = LifecyclePhase.INTENT_PERSISTED,
        deadlineEpochMillis = now + 60_000,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private fun snapshot(now: Long, ids: List<String>) = AppOperationalSnapshot(
        accountIdHash = "a".repeat(64),
        installationId = "installation-test-0001",
        matrixDeviceIdHash = "b".repeat(64),
        issuedAtEpochMillis = now,
        expiresAtEpochMillis = now + 60_000,
        overallStatus = AppOperationalStatus.READY,
        components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
            ComponentOperationalState(
                status = AppOperationalStatus.READY,
                authority = component,
                authorityRevision = "test-revision",
                observedAtEpochMillis = now,
                expiresAtEpochMillis = now + 60_000,
                receiptReference = "test-receipt:$component",
            )
        },
        activeOperationIds = ids,
        signingKeyId = "test-signing-key",
        signatureBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 1 }),
    )
}
