/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleStepStatus

/** A signed operation outcome is not acknowledgement of every queued Android fact. */
internal fun requireTerminalClientFactAcknowledged(receipt: LifecycleClientReceipt, operation: LifecycleOperation) {
    check(operation.operationId == receipt.operationId) { "Lifecycle receipt acknowledgement has a different owner." }
    if (!operation.status.isTerminal) return // The normal receipt endpoint owns its successful write response.
    val matching = operation.steps.filter { it.component == receipt.component }
    // A non-participating observer (for example heartbeat's frontend) cannot be
    // appended after terminal signing. Retiring it does not assert an applied step.
    if (matching.isEmpty()) return
    val step = matching.singleOrNull()
    check(step != null && step.status in setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED) &&
        step.receiptReference == receipt.receiptReference && step.resultCode == receipt.resultCode) {
        "Terminal lifecycle operation has not acknowledged this original Android fact."
    }
}
