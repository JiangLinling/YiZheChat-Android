/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MediaAdmissionOwnershipTest {
    @Test
    fun `LC13 parent cannot replace the independent LC16 download operation`() = runTest {
        val facts = FakeAppOperationalStateCoordinator()
        val bytes = byteArrayOf(41, 42, 43)
        val delegate = mockk<MatrixMediaLoader>()
        coEvery { delegate.loadMediaContent(any(), any()) } returns Result.success(bytes)
        val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("distinct-lifecycle"), InMemorySensitiveFileStore(),
            mockk<SecureMediaLeaseManager>(), backgroundScope, SystemClock { 1L }, facts,
            accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64))
        val parent = LifecycleOperationContext("real-parent-message-operation", FullAppLifecycleId.LC_13)
        withContext(parent) {
            loader.loadMediaContent(MediaSource("mxc://example.org/independent-media")).getOrThrow().fill(0)
        }
        assertThat(facts.startedRequests).hasSize(1)
        val download = facts.startedRequests.single()
        assertThat(download.lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
        assertThat(download.operationId).isNotEqualTo(parent.operationId)
        assertThat(facts.clientReceipts).hasSize(2)
        assertThat(facts.clientReceipts.map { it.operationId }.toSet()).containsExactly(download.operationId)
    }

    @Test
    fun `nested download never receipts before the real outer factory finishes`() = runTest {
        for (outerFails in listOf(true, false)) {
            val facts = FakeAppOperationalStateCoordinator()
            val bytes = byteArrayOf(31, 32, 33)
            val delegate = mockk<MatrixMediaLoader>()
            coEvery { delegate.loadMediaContent(any(), any()) } returns Result.success(bytes)
            val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("nested-owner"), InMemorySensitiveFileStore(),
                mockk<SecureMediaLeaseManager>(), backgroundScope, SystemClock { 1L }, facts,
                accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64))
            val request = lifecycleOperationRequest(FullAppLifecycleId.LC_16, "download")
            val outcome = runCatching {
                facts.perform(request) {
                    val acquired = loader.loadMediaContent(MediaSource("mxc://example.org/nested-resource")).getOrThrow()
                    try {
                        assertThat(facts.startedRequests).hasSize(1)
                        assertThat(facts.clientReceipts).isEmpty()
                        if (outerFails) error("Actual outer factory failure")
                    } finally {
                        acquired.fill(0)
                    }
                }
            }
            assertThat(outcome.isFailure).isEqualTo(outerFails)
            assertThat(facts.clientReceipts).hasSize(if (outerFails) 0 else 2)
            assertThat(facts.clientReceipts.all { it.operationId == request.operationId }).isTrue()
            assertThat(bytes.all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `server accepted start survives navigation response cancellation and records only actual acquisition`() = runTest {
        val facts = FakeAppOperationalStateCoordinator()
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val accepted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { coordinator.start(any()) } coAnswers {
            val operation = facts.start(firstArg())
            accepted.complete(Unit)
            response.await()
            operation
        }
        coEvery { coordinator.recordClientReceipt(any()) } coAnswers { facts.recordClientReceipt(firstArg()) }
        val bytes = byteArrayOf(11, 12, 13)
        val delegate = mockk<MatrixMediaLoader>()
        coEvery { delegate.loadMediaContent(any(), any()) } returns Result.success(bytes)
        val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("admission-test"), InMemorySensitiveFileStore(),
            mockk<SecureMediaLeaseManager>(), backgroundScope, SystemClock { 1L }, coordinator,
            accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64))
        val waiter = async { loader.loadMediaContent(MediaSource("mxc://example.org/original-resource")) }
        accepted.await()
        waiter.cancel()
        runCurrent()
        assertThat(facts.clientReceipts).isEmpty()
        response.complete(Unit)
        runCurrent()
        assertThat(facts.startedRequests).hasSize(1)
        assertThat(facts.clientReceipts).hasSize(2)
        assertThat(facts.clientReceipts.map { it.operationId }.toSet()).containsExactly(facts.startedRequests.single().operationId)
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `actual acquisition followed by receipt failure does not leak result or claim success`() = runTest {
        val facts = FakeAppOperationalStateCoordinator()
        val coordinator = mockk<AppOperationalStateCoordinator>()
        coEvery { coordinator.start(any()) } coAnswers { facts.start(firstArg()) }
        coEvery { coordinator.recordClientReceipt(any()) } throws IllegalStateException("fixture receipt unavailable")
        val bytes = byteArrayOf(21, 22, 23)
        val delegate = mockk<MatrixMediaLoader>()
        coEvery { delegate.loadMediaContent(any(), any()) } returns Result.success(bytes)
        val loader = SecureMatrixMediaLoader(delegate, AccountVaultId("failure-test"), InMemorySensitiveFileStore(),
            mockk<SecureMediaLeaseManager>(), backgroundScope, SystemClock { 1L }, coordinator,
            accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64))
        val result = loader.loadMediaContent(MediaSource("mxc://example.org/receipt-failure"))
        assertThat(result.isFailure).isTrue()
        assertThat(facts.clientReceipts).isEmpty()
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
    }
}
