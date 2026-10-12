/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.BurnConsumptionKey
import io.element.android.security.dataprotection.api.BurnConsumptionState
import io.element.android.security.dataprotection.api.PendingBurnConsumption
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SharedPreferencesBurnConsumptionStoreTest {
    @Test
    fun `records are isolated by account room event and content`() = runTest {
        val store = SharedPreferencesBurnConsumptionStore(mutableMapOf())
        val first = record(key = key(contentId = CONTENT_ID), operationId = VIEW_OPERATION)
        val second = record(key = key(contentId = OTHER_CONTENT_ID), operationId = OTHER_VIEW_OPERATION)

        store.persist(first)
        store.persist(second)

        assertThat(store.get(first.key)).isEqualTo(first)
        assertThat(store.get(second.key)).isEqualTo(second)
        assertThat(store.pending()).containsExactly(first, second)
    }

    @Test
    fun `operation session device and deadline are immutable and states only move forward`() = runTest {
        val store = SharedPreferencesBurnConsumptionStore(mutableMapOf())
        val opening = record()
        store.persist(opening)
        val viewing = opening.copy(
            state = BurnConsumptionState.VIEWING,
            viewExpiresAtEpochMillis = 20_000L,
            lastAttemptAtEpochMillis = 2L,
        )
        store.persist(viewing)

        assertThat(runCatching {
            store.persist(viewing.copy(viewOperationId = OTHER_VIEW_OPERATION, lastAttemptAtEpochMillis = 3L))
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching {
            store.persist(viewing.copy(state = BurnConsumptionState.OPENING, lastAttemptAtEpochMillis = 3L))
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching {
            store.persist(viewing.copy(viewExpiresAtEpochMillis = 30_000L, lastAttemptAtEpochMillis = 3L))
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unconfirmed state cannot be erased and confirmed state is pruned`() = runTest {
        val store = SharedPreferencesBurnConsumptionStore(mutableMapOf())
        val opening = record()
        store.persist(opening)

        assertThat(runCatching { store.removeConfirmed(opening.key) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)

        store.persist(opening.copy(state = BurnConsumptionState.END_PENDING, lastAttemptAtEpochMillis = 2L))
        store.persist(opening.copy(state = BurnConsumptionState.TERMINAL_CONFIRMED, lastAttemptAtEpochMillis = 3L))
        assertThat(store.removeConfirmed(opening.key)).isTrue()
        assertThat(store.get(opening.key)).isNull()
    }

    private fun record(
        key: BurnConsumptionKey = key(),
        operationId: String = VIEW_OPERATION,
    ) = PendingBurnConsumption(
        key = key,
        rootOperationId = null,
        viewOperationId = operationId,
        sessionId = SESSION_ID,
        installationId = INSTALLATION_ID,
        policySnapshotSha256 = POLICY_HASH,
        operationDeadlineEpochMillis = 50_000L,
        state = BurnConsumptionState.OPENING,
        viewExpiresAtEpochMillis = null,
        endReason = null,
        lastAttemptAtEpochMillis = 1L,
        failureCode = null,
    )

    private fun key(contentId: String = CONTENT_ID) = BurnConsumptionKey(
        accountIdHash = "a".repeat(64),
        roomIdHash = "b".repeat(64),
        eventIdHash = "c".repeat(64),
        contentId = contentId,
    )

    private companion object {
        const val CONTENT_ID = "burn-content-00000000000001"
        const val OTHER_CONTENT_ID = "burn-content-00000000000002"
        const val VIEW_OPERATION = "view-operation-00000000000001"
        const val OTHER_VIEW_OPERATION = "view-operation-00000000000002"
        const val SESSION_ID = "burn-session-00000000000001"
        const val INSTALLATION_ID = "installation-00000000000001"
        const val POLICY_HASH = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
