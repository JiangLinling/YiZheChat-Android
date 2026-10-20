/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LifecycleOperationProjectionsTest {
    @Test
    fun `unrelated history cannot wake one original operation observer`() = runTest {
        val projections = LifecycleOperationProjections()
        val original = operation("original", 1)
        projections.observe(original.operationId).test {
            assertThat(awaitItem()).isNull()
            repeat(2_000) { projections.put(operation("history-$it", it.toLong() + 2)) }
            runCurrent()
            expectNoEvents()
            projections.put(original)
            assertThat(awaitItem()).isEqualTo(original)
            projections.put(original)
            expectNoEvents()
            projections.put(original.copy(
                status = LifecycleOperationStatus.SUCCEEDED,
                updatedAtEpochMillis = 3_000,
                terminalReceiptId = "test-terminal-receipt-original"
            ))
            assertThat(awaitItem()?.status).isEqualTo(LifecycleOperationStatus.SUCCEEDED)
            assertThat(projections.state.value.size).isEqualTo(2_001)
        }
        runCurrent()
        assertThat(projections.observerCount).isEqualTo(0)
    }

    @Test
    fun `message updates cannot wake favourites observers`() = runTest {
        val projections = LifecycleOperationProjections()
        projections.observeLifecycle(FullAppLifecycleId.LC_18).test {
            assertThat(awaitItem()).isEmpty()
            repeat(1_000) { projections.put(operation("send-$it", it.toLong() + 1)) }
            runCurrent()
            expectNoEvents()
            val favourite = operation("favourite", 2_000).copy(lifecycleId = FullAppLifecycleId.LC_18)
            projections.put(favourite)
            assertThat(awaitItem()).containsExactly(favourite.operationId, favourite)
        }
    }

    @Test
    fun `snapshot removal reports unknown and later rediscovery restores the same original`() = runTest {
        val projections = LifecycleOperationProjections()
        val original = operation("original", 1)
        projections.put(original)
        projections.observe(original.operationId).test {
            assertThat(awaitItem()).isEqualTo(original)
            projections.replace(emptyMap())
            assertThat(awaitItem()).isNull()
            projections.put(original)
            assertThat(awaitItem()).isEqualTo(original)
        }
    }

    @Test
    fun `incremental lifecycle summary retains newer unrelated original and rebuilds after removal`() {
        val projections = LifecycleOperationProjections()
        val newer = operation("newer", 20)
        val older = operation("older", 10)
        projections.put(newer)
        projections.put(older)
        assertThat(projections.latestByLifecycle()[FullAppLifecycleId.LC_13]).isEqualTo(newer)
        projections.replace(mapOf(older.operationId to older))
        assertThat(projections.latestByLifecycle()[FullAppLifecycleId.LC_13]).isEqualTo(older)
    }

    private fun operation(id: String, updated: Long) = LifecycleOperation(
        operationId = "test-original-operation-$id",
        lifecycleId = FullAppLifecycleId.LC_13,
        idempotencyKey = "test-original-idempotency-$id",
        subjects = LifecycleSubjectHashes(),
        status = LifecycleOperationStatus.RUNNING,
        currentPhase = LifecyclePhase.INTENT_PERSISTED,
        deadlineEpochMillis = 10_000,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = updated,
    )
}
