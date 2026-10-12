/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy

class BuiltInDataProtectionPolicyProvider : DataProtectionPolicyProvider {
    override fun current(): ActiveDataProtectionPolicy = BASELINE_POLICY
}

internal val BASELINE_POLICY = ActiveDataProtectionPolicy(
    policy = DataProtectionPolicyV1(
        policyId = "yizhechat-strict-baseline",
        version = 2,
        issuedAtEpochMillis = 0,
        effectiveAtEpochMillis = 0,
        expiresAtEpochMillis = null,
        contentRetentionMillis = 24 * HOUR_MILLIS,
        maximumOfflineMillis = 24 * HOUR_MILLIS,
        offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
        notificationExposure = NotificationExposure.CONTENT_ALLOWED,
        hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
        allowContentExport = false,
        allowClipboard = false,
        allowExternalShare = false,
        allowGroupReadReceipts = false,
        screenCapturePolicy = ScreenCapturePolicy.BLOCK,
        requireContentEnvelope = true,
        allowRtc = false,
    ),
    source = DataProtectionPolicySource.BUILT_IN_BASELINE,
)

/**
 * Local deny-only projection used after signed policy authority has been accepted but is no longer usable.
 * Its source prevents it from being presented to KMS as verified remote authority.
 */
internal val FAIL_CLOSED_POLICY = ActiveDataProtectionPolicy(
    policy = DataProtectionPolicyV1(
        policyId = "yizhechat-local-fail-closed",
        version = 1,
        issuedAtEpochMillis = 0,
        effectiveAtEpochMillis = 0,
        expiresAtEpochMillis = null,
        contentRetentionMillis = 24 * HOUR_MILLIS,
        maximumOfflineMillis = 0,
        offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
        notificationExposure = NotificationExposure.HIDDEN,
        hardwareTrustRequirement = HardwareTrustRequirement.STRONGBOX,
        allowContentExport = false,
        allowClipboard = false,
        allowExternalShare = false,
        allowGroupReadReceipts = false,
        screenCapturePolicy = ScreenCapturePolicy.BLOCK,
        requireContentEnvelope = true,
        allowRtc = false,
    ),
    source = DataProtectionPolicySource.FAIL_CLOSED,
)

private const val HOUR_MILLIS = 60L * 60L * 1_000L
