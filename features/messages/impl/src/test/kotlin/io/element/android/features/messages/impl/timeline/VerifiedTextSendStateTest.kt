/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.accountIdHash
import org.junit.Test

internal const val SEND_TEST_INSTALLATION = "original-installation-12345"
internal fun committedTextSend(request: LifecycleOperationRequest, eventId: EventId) = LifecycleOperation(
    operationId = request.operationId, lifecycleId = request.lifecycleId, idempotencyKey = accountIdHash(request.idempotencyKey),
    subjects = LifecycleSubjectHashes(request.accountIdHash, SEND_TEST_INSTALLATION, request.matrixDeviceIdHash, request.roomIdHash),
    status = LifecycleOperationStatus.WAITING_EXTERNAL, currentPhase = LifecyclePhase.CLIENT_APPLIED,
    deadlineEpochMillis = request.deadlineEpochMillis, createdAtEpochMillis = 1, updatedAtEpochMillis = 2,
    steps = listOf(LifecycleComponent.MATRIX, LifecycleComponent.KMS, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE).map {
        LifecycleStep("original-step-${it.name}", it, status = LifecycleStepStatus.COMPLETE, attemptCount = 1,
            authorityRevision = "authority:1", receiptReference = "original-receipt:${it.name}", updatedAtEpochMillis = 2,
            resultCode = if (it == LifecycleComponent.MATRIX) "MATRIX_EVENT_VERIFIED" else "COMMITTED",
            resultReference = if (it == LifecycleComponent.MATRIX) accountIdHash(eventId.value) else "committed")
    },
)

class VerifiedTextSendStateTest {
    private val event = EventId("\$original-event")
    private val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "send", "a".repeat(64), "b".repeat(64), "c".repeat(64),
        operationId = "original-send-operation-12345")
    private val binding = TextSendConfirmationBinding(request, TransactionId("original-sdk-transaction"), SEND_TEST_INSTALLATION)
    private val committed = committedTextSend(request, event)

    @Test fun `signed server commit confirms transport without fabricating client completion`() {
        assertThat(binding.confirms(event, committed)).isTrue()
        assertThat(committed.status).isEqualTo(LifecycleOperationStatus.WAITING_EXTERNAL)
        assertThat(committed.terminalReceiptId).isNull()
    }

    @Test fun `every original identity scope must match`() {
        val mismatches = listOf(
            committed.copy(operationId = "another-operation-12345"),
            committed.copy(idempotencyKey = "another-idempotency-12345"),
            committed.copy(subjects = committed.subjects.copy(accountIdHash = "d".repeat(64))),
            committed.copy(subjects = committed.subjects.copy(matrixDeviceIdHash = "d".repeat(64))),
            committed.copy(subjects = committed.subjects.copy(roomIdHash = "d".repeat(64))),
            committed.copy(subjects = committed.subjects.copy(eventIdHash = "d".repeat(64))),
            committed.copy(subjects = committed.subjects.copy(installationId = "another-installation-12345")),
        )
        mismatches.forEach { assertThat(binding.confirms(event, it)).isFalse() }
    }

    @Test fun `queue acceptance and partial or duplicate server steps do not confirm`() {
        committed.steps.indices.forEach { index ->
            assertThat(binding.confirms(event, committed.copy(steps = committed.steps.filterIndexed { i, _ -> i != index }))).isFalse()
            assertThat(binding.confirms(event, committed.copy(steps = committed.steps.mapIndexed { i, step ->
                if (i == index) step.copy(status = LifecycleStepStatus.PENDING) else step
            }))).isFalse()
        }
        assertThat(binding.confirms(event, committed.copy(steps = committed.steps + committed.steps.first().copy(stepId = "duplicate-step-id")))).isFalse()
    }

    @Test fun `missing authority revision or required commit cannot confirm`() {
        for (index in committed.steps.indices) {
            for (step in listOf(committed.steps[index].copy(authorityRevision = null), committed.steps[index].copy(required = false))) {
                assertThat(binding.confirms(event, committed.copy(steps = committed.steps.toMutableList().also { it[index] = step }))).isFalse()
            }
        }
    }

    @Test fun `event hash must match the exact SDK confirmation`() {
        assertThat(binding.confirms(EventId("\$different-event"), committed)).isFalse()
        listOf("transaction", "\$", "\$bad event", "\$" + "a".repeat(512)).forEach {
            // EventId itself rejects some malformed inputs before the confirmation validator.
            val parsed = runCatching { EventId(it) }.getOrNull()
            assertThat(parsed == null || !binding.confirms(parsed, committedTextSend(request, parsed))).isTrue()
        }
    }

    @Test fun `failed cancelled and blocked operations never confirm`() {
        listOf(LifecycleOperationStatus.FAILED_FINAL, LifecycleOperationStatus.CANCELLED, LifecycleOperationStatus.TERMINATED,
            LifecycleOperationStatus.BLOCKED, LifecycleOperationStatus.RETRYABLE_FAILURE).forEach { state ->
            assertThat(binding.confirms(event, committed.copy(status = state,
                terminalReceiptId = "terminal-failure-12345".takeIf { state.isTerminal }))).isFalse()
        }
    }

    @Test fun `a different lifecycle or reply cannot use ordinary text confirmation`() {
        assertThat(binding.confirms(event, committed.copy(lifecycleId = FullAppLifecycleId.LC_14))).isFalse()
        assertThat(binding.copy(request = request.copy(trigger = "reply")).confirms(event, committed)).isFalse()
    }
}
