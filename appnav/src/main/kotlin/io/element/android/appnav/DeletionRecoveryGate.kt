/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.appnav

import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import kotlinx.coroutines.CancellationException

internal sealed interface DeletionRecoveryGate {
    data object Clear : DeletionRecoveryGate
    data class Pending(val handle: PermanentAccountDeletionHandle) : DeletionRecoveryGate
    data class RetryRequired(val failure: Exception) : DeletionRecoveryGate
}

internal suspend fun checkDeletionRecoveryGate(
    query: suspend () -> PermanentAccountDeletionHandle?,
): DeletionRecoveryGate = try {
    query()?.let(DeletionRecoveryGate::Pending) ?: DeletionRecoveryGate.Clear
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    DeletionRecoveryGate.RetryRequired(failure)
}
