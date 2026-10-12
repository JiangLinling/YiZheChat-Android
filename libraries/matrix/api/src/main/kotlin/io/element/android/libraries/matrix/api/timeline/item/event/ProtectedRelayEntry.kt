/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline.item.event

import io.element.android.libraries.matrix.api.core.UserId

/** Projection of authenticated root/operation carriers. Never inferred from display names. */
data class ProtectedRelayEntry(
    val id: String,
    val author: UserId?,
    val text: String,
    val revision: Long = 0,
    /** Canonical Matrix event evidence; pending local echo must not be shown as synchronized. */
    val isConfirmed: Boolean = true,
)
