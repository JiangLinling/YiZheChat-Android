/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

internal fun membershipHttpCode(status: Int, detail: String?): String {
    val known = setOf("PROTOCOL_UNAVAILABLE", "REQUEST_INVALID", "SESSION_REJECTED", "SESSION_UNAVAILABLE", "ACCOUNT_FROZEN",
        "ACTOR_TARGET_INVALID", "EXPIRED_RECONCILIATION_REQUIRED", "ALREADY_CHANGED", "INTENT_ALREADY_TERMINAL")
        .map { "LC12_MEMBERSHIP_$it" }
    return "HTTP_${status.coerceIn(100, 599)}" + if (detail in known) "_$detail" else ""
}
