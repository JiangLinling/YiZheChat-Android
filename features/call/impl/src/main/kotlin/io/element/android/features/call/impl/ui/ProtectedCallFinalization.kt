/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.impl.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Local media is already stopped and LC-20 started before this protected write is attempted. */
internal suspend fun awaitProtectedCallFinalization(access: Flow<Boolean>, complete: suspend () -> Unit) {
    access.first { it }
    // Never automatically repeat a failed write: its acknowledgement may have been lost.
    complete()
}
