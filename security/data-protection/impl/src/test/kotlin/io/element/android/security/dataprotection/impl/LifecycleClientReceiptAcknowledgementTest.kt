/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import org.junit.Assert.assertThrows
import org.junit.Test

class LifecycleClientReceiptAcknowledgementTest {
    private val fact = LifecycleClientReceipt(
        "original-operation-12345",
        LifecycleComponent.ANDROID_SERVICE,
        LifecyclePhase.CLIENT_APPLIED,
        "ANDROID_SERVICE_APPLIED",
        "android-service:original-operation-12345",
        150L
    )
    private val applied = LifecycleStep(
        "original-android-service-step",
        fact.component,
        true,
        LifecycleStepStatus.COMPLETE,
        1,
        resultCode = fact.resultCode,
        receiptReference = fact.receiptReference,
        updatedAtEpochMillis = 160L
    )
    private fun operation(status: LifecycleOperationStatus, steps: List<LifecycleStep>) = LifecycleOperation(
        operationId = fact.operationId,
        lifecycleId = FullAppLifecycleId.LC_16,
        idempotencyKey = "original-idempotency-12345",
        subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)),
        status = status,
        currentPhase = LifecyclePhase.RECEIPTED,
        steps = steps,
        deadlineEpochMillis = 1000L,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 200L,
        terminalReceiptId = "original-terminal-receipt-12345",
    )

    @Test
    fun `terminal success cannot acknowledge another result or another receipt reference`() {
        for (step in listOf(applied.copy(resultCode = "OTHER_RESULT"), applied.copy(receiptReference = "another:receipt"))) {
            assertThrows(IllegalStateException::class.java) {
                requireTerminalClientFactAcknowledged(fact, operation(LifecycleOperationStatus.SUCCEEDED, listOf(step)))
            }
        }
    }

    @Test
    fun `every terminal status still requires the registered Android step to match`() {
        for (status in LifecycleOperationStatus.entries.filter { it.isTerminal }) {
            requireTerminalClientFactAcknowledged(fact, operation(status, listOf(applied)))
            assertThrows(IllegalStateException::class.java) {
                requireTerminalClientFactAcknowledged(fact, operation(status, listOf(applied.copy(receiptReference = "other:receipt"))))
            }
        }
    }

    @Test
    fun `pending or retrying step remains unacknowledged after coordination failure`() {
        for (status in listOf(LifecycleStepStatus.PENDING, LifecycleStepStatus.RUNNING, LifecycleStepStatus.WAITING_EXTERNAL, LifecycleStepStatus.RETRY)) {
            assertThrows(IllegalStateException::class.java) {
                requireTerminalClientFactAcknowledged(fact, operation(LifecycleOperationStatus.FAILED_FINAL, listOf(applied.copy(status = status))))
            }
        }
    }

    @Test
    fun `exact failed-action fact can be acknowledged by its terminated Android step`() {
        val rejected = fact.copy(resultCode = "MATRIX_AUTHENTICATION_REJECTED", receiptReference = "android-login-rejected:${fact.operationId}")
        val step = applied.copy(status = LifecycleStepStatus.TERMINATED, resultCode = rejected.resultCode, receiptReference = rejected.receiptReference)
        requireTerminalClientFactAcknowledged(rejected, operation(LifecycleOperationStatus.FAILED_FINAL, listOf(step)))
    }

    @Test
    fun `ambiguous duplicate component cannot retire a local fact`() {
        assertThrows(IllegalStateException::class.java) {
            requireTerminalClientFactAcknowledged(fact, operation(
                LifecycleOperationStatus.SUCCEEDED,
                listOf(applied, applied.copy(stepId = "another-android-step"))
            ))
        }
    }

    @Test
    fun `another operation cannot acknowledge the retained fact`() {
        assertThrows(IllegalStateException::class.java) {
            requireTerminalClientFactAcknowledged(
                fact,
                operation(LifecycleOperationStatus.SUCCEEDED, listOf(applied))
                .copy(operationId = "other-operation-123456")
            )
        }
    }

    @Test
    fun `nonparticipating observer retires without claiming an applied authority step`() {
        val observer = fact.copy(component = LifecycleComponent.ANDROID_FRONTEND)
        requireTerminalClientFactAcknowledged(observer, operation(LifecycleOperationStatus.SUCCEEDED, listOf(applied)))
    }
}
