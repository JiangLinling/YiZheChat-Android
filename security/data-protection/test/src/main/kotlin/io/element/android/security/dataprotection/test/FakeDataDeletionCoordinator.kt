/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.DeletionRecoveryReport
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy

class FakeDataDeletionCoordinator(
    private val deleteResult: (DeletionRequest) -> DeletionReport = { request ->
        DeletionReport(
            operationId = request.operationId,
            completedSteps = setOf(DeletionStep.COMPLETE),
            retryRequired = false,
        )
    },
) : DataDeletionCoordinator {
    val preparedRequests = mutableListOf<DeletionRequest>()
    val requests = mutableListOf<DeletionRequest>()

    override suspend fun prepare(request: DeletionRequest): DeletionReport {
        preparedRequests += request
        return DeletionReport(
            operationId = request.operationId,
            completedSteps = setOf(DeletionStep.REQUESTED),
            retryRequired = true,
        )
    }

    override suspend fun delete(request: DeletionRequest): DeletionReport {
        requests += request
        return deleteResult(request)
    }

    override suspend fun resumePending(coldStart: Boolean): DeletionRecoveryReport = DeletionRecoveryReport(
        operationReports = emptyList(),
        unreadableJournalEntries = 0,
    )
}

class FakeDataProtectionPolicyProvider(
    private val notificationExposure: NotificationExposure = NotificationExposure.HIDDEN,
    var allowContentExport: Boolean = false,
    private val allowClipboard: Boolean = false,
    var allowExternalShare: Boolean = false,
    var allowGroupReadReceipts: Boolean = false,
    private val requireContentEnvelope: Boolean = true,
    var allowRtc: Boolean = true,
    var hardwareTrustRequirement: HardwareTrustRequirement = HardwareTrustRequirement.NONE,
) : DataProtectionPolicyProvider {
    override fun current(): ActiveDataProtectionPolicy = ActiveDataProtectionPolicy(
        policy = DataProtectionPolicyV1(
            policyId = "fake-policy",
            version = 1,
            issuedAtEpochMillis = 0,
            effectiveAtEpochMillis = 0,
            expiresAtEpochMillis = null,
            contentRetentionMillis = 24 * 60 * 60 * 1_000L,
            maximumOfflineMillis = 24 * 60 * 60 * 1_000L,
            offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
            notificationExposure = notificationExposure,
            hardwareTrustRequirement = hardwareTrustRequirement,
            allowContentExport = allowContentExport,
            allowClipboard = allowClipboard,
            allowExternalShare = allowExternalShare,
            allowGroupReadReceipts = allowGroupReadReceipts,
            screenCapturePolicy = ScreenCapturePolicy.BLOCK,
            requireContentEnvelope = requireContentEnvelope,
            allowRtc = allowRtc,
        ),
        source = DataProtectionPolicySource.BUILT_IN_BASELINE,
    )
}
