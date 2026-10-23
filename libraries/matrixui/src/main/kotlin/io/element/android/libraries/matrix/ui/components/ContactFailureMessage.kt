/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.ui.components

import io.element.android.libraries.ui.strings.CommonStrings

/** Only known authority codes select copy; never display a raw server response. */
fun Throwable.contactFailureMessageResource(): Int? {
    var failure: Throwable? = this
    repeat(8) {
        val codes = failure?.message.orEmpty().split(Regex("[^A-Z0-9_]+"))
        when {
            codes.any { it == "LC11_DIRECT_RESPONSE_EXPIRED_RETRY" } -> return CommonStrings.contact_request_expired_retry
            codes.any { it == "CONTACT_PROJECTION_RECONCILIATION_REQUIRED" } -> return CommonStrings.contact_projection_pending
            codes.any { it == "CONTACT_BLOCKED_BY_YOU" || it == "M_CONTACT_BLOCKED_BY_YOU" } -> return CommonStrings.contact_blocked_by_you
            codes.any { it == "CONTACT_UNAVAILABLE" || it == "M_CONTACT_UNAVAILABLE" } -> return CommonStrings.contact_unavailable
            codes.any {
                it in setOf(
                    "LC11_DIRECT_GENERATION_CHANGED_REVIEW_REQUIRED",
                    "LC11_DIRECT_GENERATION_REVOKED",
                "LC11_DIRECT_MEMBERSHIP_CHANGED",
                    "LC11_DIRECT_PREVIEW_REQUIRED",
                    "CONTACT_POLICY_CHANGED_REFRESH_REQUIRED"
                )
            } ->
                return CommonStrings.contact_request_changed
            codes.any {
                it in setOf(
                    "LC11_DIRECT_ORIGINAL_RECONCILIATION_REQUIRED",
                    "LC11_DIRECT_DECLINE_RECONCILIATION_REQUIRED",
                "LC11_DIRECT_ORIGINAL_DEVICE_REQUIRED",
                    "LC11_REMOVAL_RECONCILIATION_REQUIRED"
                )
            } -> return CommonStrings.contact_request_pending
            codes.any { it == "CONTACT_POLICY_UNAVAILABLE" || it == "M_CONTACT_POLICY_UNAVAILABLE" } -> return CommonStrings.contact_policy_unavailable
        }
        failure = failure?.cause
    }
    return null
}
