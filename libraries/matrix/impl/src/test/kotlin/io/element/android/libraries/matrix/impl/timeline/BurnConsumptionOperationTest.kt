/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.BurnConsumptionKey
import io.element.android.security.dataprotection.api.BurnConsumptionState
import io.element.android.security.contentprotection.api.BurnAfterReadEndReason
import org.junit.Test

class BurnConsumptionOperationTest {
    @Test
    fun `first open creates LC-21 checkpoint before side effects`() {
        val checkpoint = pendingBurnConsumptionForOpen(
            existing = null,
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 1_000L,
            newOperationId = VIEW_OPERATION_ID,
            newSessionId = SESSION_ID,
            operationDeadlineEpochMillis = 50_000L,
        )

        assertThat(checkpoint.state).isEqualTo(BurnConsumptionState.OPENING)
        assertThat(checkpoint.viewOperationId).isEqualTo(VIEW_OPERATION_ID)
        assertThat(checkpoint.sessionId).isEqualTo(SESSION_ID)
        assertThat(checkpoint.installationId).isEqualTo(INSTALLATION_ID)
    }

    @Test
    fun `lost open response retries the same operation session and deadline`() {
        val original = pendingBurnConsumptionForOpen(
            existing = null,
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 1_000L,
            newOperationId = VIEW_OPERATION_ID,
            newSessionId = SESSION_ID,
            operationDeadlineEpochMillis = 50_000L,
        )

        val retry = pendingBurnConsumptionForOpen(
            existing = original.copy(failureCode = "KMS_UNAVAILABLE"),
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 2_000L,
            newOperationId = "discarded-operation-00000001",
            newSessionId = "discarded-session-0000000001",
            operationDeadlineEpochMillis = 90_000L,
        )

        assertThat(retry.viewOperationId).isEqualTo(VIEW_OPERATION_ID)
        assertThat(retry.sessionId).isEqualTo(SESSION_ID)
        assertThat(retry.operationDeadlineEpochMillis).isEqualTo(50_000L)
        assertThat(retry.lastAttemptAtEpochMillis).isEqualTo(2_000L)
        assertThat(retry.failureCode).isNull()
    }

    @Test
    fun `ending checkpoint and another installation cannot open again`() {
        val original = pendingBurnConsumptionForOpen(
            existing = null,
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 1_000L,
            newOperationId = VIEW_OPERATION_ID,
            newSessionId = SESSION_ID,
            operationDeadlineEpochMillis = 50_000L,
        )

        assertThat(runCatching {
            pendingBurnConsumptionForOpen(
                existing = original.copy(state = BurnConsumptionState.END_PENDING),
                key = KEY,
                installationId = INSTALLATION_ID,
                nowEpochMillis = 2_000L,
                newOperationId = "discarded-operation-00000001",
                newSessionId = "discarded-session-0000000001",
                operationDeadlineEpochMillis = 90_000L,
            )
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching {
            pendingBurnConsumptionForOpen(
                existing = original,
                key = KEY,
                installationId = "another-installation-00000001",
                nowEpochMillis = 2_000L,
                newOperationId = "discarded-operation-00000001",
                newSessionId = "discarded-session-0000000001",
                operationDeadlineEpochMillis = 90_000L,
            )
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `process recovery resumes only end pending or expired viewing checkpoints`() {
        val opening = pendingBurnConsumptionForOpen(
            existing = null,
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 1_000L,
            newOperationId = VIEW_OPERATION_ID,
            newSessionId = SESSION_ID,
            operationDeadlineEpochMillis = 50_000L,
        )
        val activeViewing = opening.copy(
            state = BurnConsumptionState.VIEWING,
            viewExpiresAtEpochMillis = 30_000L,
        )
        val expiredViewing = activeViewing.copy(
            key = KEY.copy(contentId = "burn-content-00000000000002"),
            viewOperationId = "view-operation-00000000000002",
            sessionId = "burn-session-00000000000002",
            viewExpiresAtEpochMillis = 10_000L,
        )
        val endPending = activeViewing.copy(
            key = KEY.copy(contentId = "burn-content-00000000000003"),
            viewOperationId = "view-operation-00000000000003",
            sessionId = "burn-session-00000000000003",
            state = BurnConsumptionState.END_PENDING,
        )

        val resumed = pendingBurnConsumptionsToResume(
            checkpoints = listOf(opening, activeViewing, expiredViewing, endPending),
            accountIdHash = KEY.accountIdHash,
            roomIdHash = KEY.roomIdHash,
            nowEpochMillis = 20_000L,
        )

        assertThat(resumed).containsExactly(expiredViewing, endPending).inOrder()
    }

    @Test
    fun `app lock persists end pending before remote terminal acknowledgement`() {
        val viewing = pendingBurnConsumptionForOpen(
            existing = null,
            key = KEY,
            installationId = INSTALLATION_ID,
            nowEpochMillis = 1_000L,
            newOperationId = VIEW_OPERATION_ID,
            newSessionId = SESSION_ID,
            operationDeadlineEpochMillis = 50_000L,
        ).copy(state = BurnConsumptionState.VIEWING, viewExpiresAtEpochMillis = 30_000L)

        val ending = pendingBurnConsumptionForEnd(viewing, BurnAfterReadEndReason.APP_LOCKED, 2_000L)
        val retry = pendingBurnConsumptionForEnd(
            ending.copy(failureCode = "KMS_UNAVAILABLE"),
            BurnAfterReadEndReason.WINDOW_EXPIRED,
            3_000L,
        )

        assertThat(ending.state).isEqualTo(BurnConsumptionState.END_PENDING)
        assertThat(ending.endReason).isEqualTo(BurnAfterReadEndReason.APP_LOCKED.name)
        assertThat(retry.viewOperationId).isEqualTo(VIEW_OPERATION_ID)
        assertThat(retry.sessionId).isEqualTo(SESSION_ID)
        assertThat(retry.endReason).isEqualTo(BurnAfterReadEndReason.APP_LOCKED.name)
        assertThat(retry.state).isEqualTo(BurnConsumptionState.END_PENDING)
    }

    private companion object {
        val KEY = BurnConsumptionKey(
            accountIdHash = "a".repeat(64),
            roomIdHash = "b".repeat(64),
            eventIdHash = "c".repeat(64),
            contentId = "burn-content-00000000000001",
        )
        const val INSTALLATION_ID = "installation-00000000000001"
        const val VIEW_OPERATION_ID = "view-operation-00000000000001"
        const val SESSION_ID = "burn-session-00000000000001"
    }
}
