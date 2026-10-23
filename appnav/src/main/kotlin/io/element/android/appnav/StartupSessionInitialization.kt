/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import io.element.android.security.dataprotection.api.LifecycleOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/** Joins actual startup actions under the existing LC-06 identity; it grants no admission. */
internal class StartupSessionInitialization(
    private val originalStillActive: suspend (LifecycleOperationContext) -> Boolean,
) {
    private val localReady = CompletableDeferred<LifecycleOperationContext?>()
    private val activationApplied = CompletableDeferred<Unit>()
    private val receiptsAcknowledged = CompletableDeferred<Unit>()

    suspend fun initialize(context: LifecycleOperationContext?, action: suspend () -> Unit) {
        action()
        localReady.complete(context)
        // The original perform owns the only pair of Android receipts. Do not claim
        // client completion until both real session setup and signed activation finish.
        if (context != null) activationApplied.await()
    }

    suspend fun activate(sharedAction: suspend () -> Unit, standaloneAction: suspend () -> Unit) {
        val context = localReady.await()
        if (context != null && !receiptsAcknowledged.isCompleted) {
            // Verification can take longer than this startup's authority deadline. Unknown
            // transport still retries the original; a verified terminal/expired original must
            // release its waiters and use the existing explicit startup retry UI.
            val active = try {
                originalStillActive(context)
            } catch (failure: StartupSessionEndedException) {
                fail(failure)
                throw failure
            }
            if (!active) {
                val failure = StartupSessionEndedException(deadlineExpired = true)
                fail(failure)
                throw failure
            }
            withContext(context) { sharedAction() }
            activationApplied.complete(Unit)
            // CORE alone must not expose HOME before the original receipt transport returns.
            receiptsAcknowledged.await()
        } else {
            // Offline restoration and later policy changes keep their fresh admission path.
            standaloneAction()
        }
    }

    fun complete() { receiptsAcknowledged.complete(Unit) }

    fun fail(failure: Throwable) {
        localReady.completeExceptionally(failure)
        activationApplied.completeExceptionally(failure)
        receiptsAcknowledged.completeExceptionally(failure)
    }
}

internal class StartupSessionEndedException(val deadlineExpired: Boolean) : IllegalStateException("Original startup is no longer active")
