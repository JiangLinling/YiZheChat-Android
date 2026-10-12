/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/** Records actual process departures for the selected Matrix session, independent of node visibility. */
internal class LoggedInBackgroundLifecycleObserver(
    private val sessionCoroutineScope: CoroutineScope,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val accountIdHash: String,
    private val matrixDeviceIdHash: String,
) : DefaultLifecycleObserver {
    private var foregroundObserved = false

    override fun onStart(owner: LifecycleOwner) {
        foregroundObserved = true
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!foregroundObserved) return
        foregroundObserved = false
        // Capture a new identity for this actual departure. Do not resume/coalesce a foreground
        // operation, or allow a later UI resume to cancel this background admission and its receipts.
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_02,
            trigger = "background",
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        sessionCoroutineScope.launch {
            try {
                appOperationalStateCoordinator.perform(request) {
                    // ProcessLifecycleOwner supplied the real stop; this action grants no entry,
                    // restores no destination and claims no new Matrix authorization.
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.w(failure, "Background lifecycle operation remains pending in the operational coordinator.")
            }
        }
    }
}
