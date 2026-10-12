/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProtectedVoiceDraftLeasePolicyTest {
    @Test
    fun `local lease cap cannot extend an earlier policy expiry`() {
        val now = 10_000L

        val deadline = voiceDraftLeaseDeadline(
            nowEpochMillis = now,
            policy = policy(expiresAtEpochMillis = now + 1_000, contentRetentionMillis = 60_000),
            sourceAuthorizationDeadlineEpochMillis = now + 30_000,
        )

        assertThat(deadline).isEqualTo(now + 1_000)
    }

    @Test
    fun `local lease cap cannot extend an earlier retention deadline`() {
        val now = 10_000L

        val deadline = voiceDraftLeaseDeadline(
            nowEpochMillis = now,
            policy = policy(expiresAtEpochMillis = null, contentRetentionMillis = 2_000),
            sourceAuthorizationDeadlineEpochMillis = now + 30_000,
        )

        assertThat(deadline).isEqualTo(now + 2_000)
    }

    @Test
    fun `local lease is capped at thirty minutes when policy allows longer`() {
        val now = 10_000L

        val deadline = voiceDraftLeaseDeadline(
            nowEpochMillis = now,
            policy = policy(expiresAtEpochMillis = null, contentRetentionMillis = 3_600_000),
            sourceAuthorizationDeadlineEpochMillis = now + 3_600_000,
        )

        assertThat(deadline).isEqualTo(now + VOICE_DRAFT_LEASE_MAX_TTL.inWholeMilliseconds)
    }

    @Test
    fun `source authorization deadline cannot be extended by policy or local cap`() {
        val now = 10_000L

        val deadline = voiceDraftLeaseDeadline(
            nowEpochMillis = now,
            policy = policy(expiresAtEpochMillis = now + 60_000, contentRetentionMillis = 60_000),
            sourceAuthorizationDeadlineEpochMillis = now + 500,
        )

        assertThat(deadline).isEqualTo(now + 500)
    }

    @Test
    fun `abandoned recovery revokes operation scope before deleting object`() = runTest {
        val calls = mutableListOf<String>()
        val operationId = "voice-operation-0001"

        val deleted = cleanupAbandonedVoiceDraft(
            operationId = operationId,
            revokeScope = { calls += "revoke:$it" },
            deleteObject = {
                calls += "delete"
                true
            },
        )

        assertThat(deleted).isTrue()
        assertThat(calls).containsExactly("revoke:voice-draft:$operationId", "delete").inOrder()
    }

    private fun policy(expiresAtEpochMillis: Long?, contentRetentionMillis: Long) = DataProtectionPolicyV1(
        policyId = "voice-policy",
        version = 1,
        issuedAtEpochMillis = 0,
        effectiveAtEpochMillis = 0,
        expiresAtEpochMillis = expiresAtEpochMillis,
        contentRetentionMillis = contentRetentionMillis,
        maximumOfflineMillis = 0,
        offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
        notificationExposure = NotificationExposure.HIDDEN,
        hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
        allowContentExport = false,
        allowClipboard = false,
        allowExternalShare = false,
        screenCapturePolicy = ScreenCapturePolicy.BLOCK,
    )
}
