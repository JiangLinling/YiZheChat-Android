/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LifecycleDenialReceiptTest {
    private fun receipt(status: LifecycleOperationStatus, references: Map<String, String> = emptyMap()) = LifecycleReceipt(
        receiptId = "fixture-denial-receipt", operationId = "fixture-original-operation",
        lifecycleId = FullAppLifecycleId.LC_19, subjects = LifecycleSubjectHashes(installationId = "fixture-original-installation"),
        terminalStatus = status, authorityRevisions = emptyMap(), requiredStepReceipts = references,
        policyVersion = 1, startedAtEpochMillis = 1, completedAtEpochMillis = 2, signatureBase64 = "fixture-signature-at-least-sixteen",
    )

    @Test fun `denied or cancelled intent can report no applied authority facts`() {
        listOf(LifecycleOperationStatus.FAILED_FINAL, LifecycleOperationStatus.CANCELLED).forEach {
            assertThat(receipt(it).requiredStepReceipts).isEmpty()
        }
    }

    @Test fun `success or termination still requires actual authority references`() {
        listOf(LifecycleOperationStatus.SUCCEEDED, LifecycleOperationStatus.TERMINATED).forEach {
            assertThrows(IllegalArgumentException::class.java) { receipt(it) }
        }
    }

    @Test fun `denial does not permit blank component or receipt references`() {
        listOf(mapOf(" " to "original-receipt"), mapOf("original-step" to " ")).forEach {
            assertThrows(IllegalArgumentException::class.java) { receipt(LifecycleOperationStatus.FAILED_FINAL, it) }
        }
    }

    @Test fun `original applied reference remains unchanged in failed receipt`() {
        val original = mapOf("original-android-service" to "original-service-receipt")
        assertThat(receipt(LifecycleOperationStatus.FAILED_FINAL, original).requiredStepReceipts).isEqualTo(original)
    }
}
