/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** Product semantics only; the original authority failure remains the cause. */
class RelayEditException(val reason: Reason, cause: Throwable? = null) : IllegalStateException(reason.name, cause) {
    enum class Reason { CLOSED, CONFLICT, EXPIRED, TERMINAL_FAILURE, AUTHORITY_UNAVAILABLE }
}
