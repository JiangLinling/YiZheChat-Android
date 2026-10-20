/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.push
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps a new verification out of the previous verification's exit transition. */
internal class IncomingVerificationNavigation<T : Any>(
    private val backstack: BackStack<T>,
    private val isVerification: (T) -> Boolean,
) {
    private val mutex = Mutex()
    private val handledRequestIds = linkedSetOf<String>()

    suspend fun show(requestId: String, target: T, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            mutex.withLock {
                if (!handledRequestIds.add(requestId)) return@withLock false
                while (handledRequestIds.size > 128) handledRequestIds.remove(handledRequestIds.first())
                // Appyx singleTop searches DESTROYED entries still animating out. Replacing one
                // after pop can append the already-active Home NavKey a second time. Wait until
                // the old verification leaves, then push a genuinely new navigation entry.
                backstack.elements.first { elements -> elements.none { isVerification(it.key.navTarget) } }
                backstack.push(target)
                true
            }
        } ?: false
}
