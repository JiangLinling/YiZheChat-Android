/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A bounded read of the original signed status, never another send or lifecycle operation. */
internal suspend fun refreshTextSendConfirmation(
    binding: TextSendConfirmationBinding,
    eventId: EventId,
    isPending: () -> Boolean,
    read: suspend () -> LifecycleOperation,
): Boolean = withTimeoutOrNull(2_500L) {
    // Native acceptance can precede the server's verified event binding. Do not wait for an
    // unrelated UI/client-receipt refresh when that first signed snapshot is merely early.
    for (waitMillis in listOf(0L, 100L, 200L, 400L, 800L)) {
        if (waitMillis != 0L) delay(waitMillis)
        if (!isPending()) return@withTimeoutOrNull false
        val operation = read()
        if (binding.confirms(eventId, operation)) return@withTimeoutOrNull true
        if (operation.status.isTerminal || operation.status in setOf(
                LifecycleOperationStatus.BLOCKED, LifecycleOperationStatus.RETRYABLE_FAILURE,
            )) return@withTimeoutOrNull false
    }
    false
} ?: false

/** Continue only this original confirmation after a delayed response; history is not its retry trigger. */
internal suspend fun awaitTextSendConfirmation(
    binding: TextSendConfirmationBinding,
    eventId: EventId,
    isPending: () -> Boolean,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    read: suspend () -> LifecycleOperation,
): Boolean = withTimeoutOrNull(30_000L) {
    val waits = listOf(0L, 100L, 200L, 400L, 800L) + List(12) { 2_000L }
    for (waitMillis in waits) {
        if (waitMillis > 0) delay(waitMillis)
        if (!isPending() || nowEpochMillis() >= binding.request.deadlineEpochMillis) return@withTimeoutOrNull false
        val operation = try {
            withTimeoutOrNull(2_500L) { read() }
        } catch (_: IOException) {
            null // A lost status response does not resend the original message.
        } ?: continue
        if (binding.confirms(eventId, operation)) return@withTimeoutOrNull true
        if (operation.operationId != binding.request.operationId || operation.status.isTerminal ||
            operation.status == LifecycleOperationStatus.BLOCKED || nowEpochMillis() >= operation.deadlineEpochMillis
        ) return@withTimeoutOrNull false
    }
    false
} ?: false
