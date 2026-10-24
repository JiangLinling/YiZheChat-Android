/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Run only while the host activity is foreground. Cancellation clears only guest state. */
suspend fun DiscoveryAccess.observeForeground() {
    if (!configured) return
    try {
        while (currentCoroutineContext().isActive) {
            try {
                refreshAccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Guest failure must never cancel the host's composition or session scope.
                leaveSafely()
            }
            delay(20_000)
        }
    } finally {
        withContext(NonCancellable) { leaveSafely() }
    }
}

private suspend fun DiscoveryAccess.leaveSafely() {
    try {
        leave()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // No host logout or navigation repair is owned here.
    }
}
