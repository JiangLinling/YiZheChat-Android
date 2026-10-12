/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import io.element.android.features.messages.impl.R
import io.element.android.security.dataprotection.api.RelayEditException

internal fun relayEditErrorResource(error: Throwable?): Int = when ((error as? RelayEditException)?.reason) {
    RelayEditException.Reason.CLOSED -> R.string.screen_room_relay_closed
    RelayEditException.Reason.CONFLICT -> R.string.screen_room_relay_edit_conflict
    RelayEditException.Reason.EXPIRED -> R.string.error_relay_edit_expired
    RelayEditException.Reason.TERMINAL_FAILURE -> R.string.error_relay_edit_terminal
    else -> R.string.error_relay_edit_authority_unavailable
}
