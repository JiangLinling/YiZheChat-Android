/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.impl.ui

import kotlinx.coroutines.CancellationException

/** Storage authorization may change after the access check but before the encrypted write. */
internal suspend fun protectCallStateUpdate(onFailure: (Exception) -> Unit, update: suspend () -> Unit) {
    try {
        update()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
