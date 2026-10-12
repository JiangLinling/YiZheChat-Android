/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.AccountDeletionReceipt
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionRecoveryReport

class FakePermanentAccountDeletionCoordinator : PermanentAccountDeletionCoordinator {
    val preparedRequests = mutableListOf<Pair<DeletionRequest, Boolean>>()
    var pendingHandle: PermanentAccountDeletionHandle? = null

    override suspend fun prepare(
        localDeletionRequest: DeletionRequest,
        eraseData: Boolean,
    ): Result<PermanentAccountDeletionHandle> {
        preparedRequests += localDeletionRequest to eraseData
        return Result.success(PermanentAccountDeletionHandle(localDeletionRequest.operationId.value).also { pendingHandle = it })
    }

    override suspend fun pending(accountIdHash: String): PermanentAccountDeletionHandle? = pendingHandle

    override suspend fun commitRemote(handle: PermanentAccountDeletionHandle): Result<AccountDeletionReceipt> = Result.success(
        AccountDeletionReceipt(
            deletionIntentId = "deletion-intent-00000000000001",
            accountIdHash = "0".repeat(64),
            installationId = "installation-000000000000001",
            globalDestroyJobId = "global-destroy-000000000000001",
            eraseData = true,
            committedAtEpochMillis = 1,
        )
    )

    override suspend fun completeLocal(
        handle: PermanentAccountDeletionHandle,
        confirmedPreconditions: Set<DeletionStep>,
    ): DeletionReport = DeletionReport(
        operationId = io.element.android.security.dataprotection.api.DeletionOperationId(handle.value),
        completedSteps = DeletionStep.entries.toSet(),
        retryRequired = false,
    ).also { pendingHandle = null }

    override suspend fun abandon(handle: PermanentAccountDeletionHandle): Boolean = true.also { pendingHandle = null }

    override suspend fun resumePending(coldStart: Boolean): PermanentAccountDeletionRecoveryReport =
        PermanentAccountDeletionRecoveryReport(0, 0, 0, 0)
}
