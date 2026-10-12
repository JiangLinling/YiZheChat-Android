/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLifecycleContractsTest {
    @Test
    fun `operational snapshot accepts bounded future issue skew without extending expiry`() {
        val snapshot = snapshot(REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component() })

        assertThat(snapshot.isUsableAt(nowEpochMillis = 95, maximumFutureIssueSkewMillis = 5)).isTrue()
        assertThat(snapshot.isUsableAt(nowEpochMillis = 94, maximumFutureIssueSkewMillis = 5)).isFalse()
        assertThat(snapshot.isUsableAt(nowEpochMillis = 199, maximumFutureIssueSkewMillis = 5)).isTrue()
        assertThat(snapshot.isUsableAt(nowEpochMillis = 200, maximumFutureIssueSkewMillis = 5)).isFalse()
    }

    @Test
    fun `operation wire model accepts the existing Office Preview authority`() {
        assertThat(LifecycleComponent.valueOf("OFFICE_PREVIEW"))
            .isEqualTo(LifecycleComponent.OFFICE_PREVIEW)
    }

    @Test
    fun `catalog exposes exactly the 25 whole-app lifecycles`() {
        assertThat(FullAppLifecycleId.entries.map { it.wireValue })
            .containsExactlyElementsIn((1..25).map { "LC-${it.toString().padStart(2, '0')}" })
            .inOrder()
    }

    @Test
    fun `ready snapshot rejects an unknown component`() {
        val components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component() }.toMutableMap()
        components["kms"] = component(AppOperationalStatus.UNKNOWN)

        val failure = runCatching { snapshot(components) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("non-ready component")
    }

    @Test
    fun `snapshot rejects a missing component`() {
        val components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component() }.toMutableMap()
        components.remove("push")

        val failure = runCatching { snapshot(components) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("components are incomplete")
    }

    @Test
    fun `successful operation requires every required step receipt`() {
        val failure = runCatching {
            LifecycleOperation(
                operationId = "operation-1234567890",
                lifecycleId = FullAppLifecycleId.LC_22,
                idempotencyKey = "idempotency-123456",
                subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64)),
                status = LifecycleOperationStatus.SUCCEEDED,
                currentPhase = LifecyclePhase.RECEIPTED,
                steps = listOf(
                    LifecycleStep(
                        stepId = "push-delete",
                        component = LifecycleComponent.PUSH,
                        status = LifecycleStepStatus.WAITING_EXTERNAL,
                        attemptCount = 1,
                        updatedAtEpochMillis = 150,
                    )
                ),
                deadlineEpochMillis = 500,
                terminalReceiptId = "terminal-receipt",
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 200,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("incomplete required steps")
    }

    private fun snapshot(components: Map<String, ComponentOperationalState>) = AppOperationalSnapshot(
        accountIdHash = "a".repeat(64),
        installationId = "installation-1234",
        matrixDeviceIdHash = "b".repeat(64),
        issuedAtEpochMillis = 100,
        expiresAtEpochMillis = 200,
        overallStatus = AppOperationalStatus.READY,
        components = components,
        signingKeyId = "signing-key-1",
        signatureBase64 = "signed-snapshot-value",
    )

    private fun component(status: AppOperationalStatus = AppOperationalStatus.READY) = ComponentOperationalState(
        status = status,
        authority = "CONTROL_PLANE",
        authorityRevision = "revision-1",
        observedAtEpochMillis = 100,
        expiresAtEpochMillis = 200,
    )
}
