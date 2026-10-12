/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

/**
 * LC-14/17 local voice-draft source view of an already verified snapshot. The full-app contract
 * reserves overallStatus for navigation; it is not an action grant. These source authorities must
 * positively be READY. LC-08 authorizes recovery of historical content, not a newly captured draft.
 * Push, remote Office preview and RTC session availability do not own a draft.
 * Policy, local vault/lock/audio ownership and the later room/item Matrix/KMS admission remain
 * separate mandatory checks. This view neither changes the snapshot nor grants content access.
 */
fun AppOperationalSnapshot.isLocalVoiceDraftSourceAuthorizedAt(
    nowEpochMillis: Long,
    accountIdHash: String,
    matrixDeviceIdHash: String,
): Boolean = this.accountIdHash == accountIdHash &&
    this.matrixDeviceIdHash == matrixDeviceIdHash &&
    isUsableAt(nowEpochMillis, APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS) &&
    LOCAL_VOICE_DRAFT_SOURCE_COMPONENTS.all { name ->
        components[name]?.let { component ->
            component.status == AppOperationalStatus.READY &&
                // Both timestamps are signed server observations. Reapplying a zero-skew handset
                // clock here would reject the freshly verified snapshot on every refresh.
                component.observedAtEpochMillis <= issuedAtEpochMillis &&
                nowEpochMillis < component.expiresAtEpochMillis
        } == true
    }

/** An explicit source denial is never replaced by a refresh from the draft boundary. */
fun AppOperationalSnapshot.hasLocalVoiceDraftSourceDenial(): Boolean =
    LOCAL_VOICE_DRAFT_SOURCE_COMPONENTS.any { name ->
        components[name]?.status in setOf(
            AppOperationalStatus.BLOCKED,
            AppOperationalStatus.TERMINATING,
            AppOperationalStatus.TERMINATED,
        )
    }

/** Bounds lease issuance and refresh without extending any individual authority observation. */
val AppOperationalSnapshot.localVoiceDraftSourceExpiresAtEpochMillis: Long
    get() = minOf(
        expiresAtEpochMillis,
        LOCAL_VOICE_DRAFT_SOURCE_COMPONENTS.minOf { name -> components[name]?.expiresAtEpochMillis ?: Long.MIN_VALUE },
    )

private val LOCAL_VOICE_DRAFT_SOURCE_COMPONENTS = setOf(
    "access_session", "device_trust", "cleanup", "matrix", "kms",
)
