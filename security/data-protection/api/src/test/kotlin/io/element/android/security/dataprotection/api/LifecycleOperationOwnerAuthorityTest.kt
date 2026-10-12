/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertThrows
import org.junit.Test

class LifecycleOperationOwnerAuthorityTest {
    @Test
    fun `descriptor enforces phase role and LC16 binding`() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(lifecycleId = FullAppLifecycleId.LC_13)
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(phase = LifecycleOperationOwnerPhase.OWNED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(role = LifecycleOperationOwnerRole.PREVIEW)
        }
    }

    @Test
    fun `transition receipt rejects malformed hashes and non advancing generation`() {
        val from = descriptor()
        val to = from.copy(
            role = LifecycleOperationOwnerRole.PREVIEW,
            phase = LifecycleOperationOwnerPhase.OWNED,
            generation = 1,
        )
        assertThrows(IllegalArgumentException::class.java) {
            receipt(from, to).copy(requestFingerprintSha256 = "invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            receipt(from, to).copy(to = from)
        }
    }

    @Test
    fun `existing coordinator fake needs no owner methods`() {
        val existingOnly: AppOperationalStateCoordinator = ExistingCoordinatorOnly()
        assertThat(existingOnly.operations.value).isEmpty()
        assertThat(existingOnly is LifecycleOperationOwnerAuthority).isFalse()
    }

    private fun descriptor() = LifecycleOperationOwnerDescriptor(
        operationId = "owner-operation-0001",
        lifecycleId = FullAppLifecycleId.LC_16,
        role = null,
        phase = LifecycleOperationOwnerPhase.AVAILABLE,
        generation = 0,
        authoritativeOperationUpdatedAtEpochMillis = 1,
    )

    private fun receipt(from: LifecycleOperationOwnerDescriptor, to: LifecycleOperationOwnerDescriptor) =
        LifecycleOperationOwnerTransitionReceipt(
            operationId = from.operationId,
            commandIdSha256 = "a".repeat(64),
            requestFingerprintSha256 = "b".repeat(64),
            processInstanceIdSha256 = "c".repeat(64),
            fromCapabilityIdSha256 = null,
            toCapabilityIdSha256 = "d".repeat(64),
            from = from,
            to = to,
            storeRevision = 1,
        )

    private class ExistingCoordinatorOnly : AppOperationalStateCoordinator {
        override val state: StateFlow<AppOperationalCoordinatorState> =
            MutableStateFlow(AppOperationalCoordinatorState.Discovering)
        override val operations: StateFlow<Map<String, LifecycleOperation>> = MutableStateFlow(emptyMap())
        override val uiStates: StateFlow<Map<FullAppLifecycleId, AppLifecycleUiState>> = MutableStateFlow(emptyMap())

        override suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot = error("unused")
        override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation = error("unused")
        override suspend fun operation(operationId: String): LifecycleOperation = error("unused")
        override suspend fun resume(operationId: String): LifecycleOperation = error("unused")
        override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation = error("unused")
        override suspend fun <T> performResuming(
            request: LifecycleOperationRequest,
            hasNewStartupIntent: Boolean,
            block: suspend (operationId: String) -> T,
        ): T = error("unused")
    }
}
