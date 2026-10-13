/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.securestorage.api.accountIdHash

internal data class TextSendConfirmationBinding(
    val request: LifecycleOperationRequest,
    val sdkTransactionId: TransactionId,
    val installationId: String,
)

/**
 * Combines the original SDK SentEvent with the coordinator's signed server commit facts.
 * This confirms transport only: it neither authorizes plaintext nor completes LC-13's client
 * projection/receipt work. Missing, mismatched or failed authority always leaves the echo pending.
 */
internal fun TextSendConfirmationBinding.confirms(eventId: EventId, operation: LifecycleOperation): Boolean {
    if (!eventId.value.startsWith('$') || eventId.value.length !in 2..512 || eventId.value.any(Char::isWhitespace) ||
        operation.operationId != request.operationId || operation.lifecycleId != FullAppLifecycleId.LC_13 ||
        request.lifecycleId != FullAppLifecycleId.LC_13 || request.trigger != "send" ||
        installationId.isBlank() || operation.subjects.installationId != installationId ||
        operation.idempotencyKey !in setOf(request.idempotencyKey, accountIdHash(request.idempotencyKey)) ||
        operation.subjects.accountIdHash != request.accountIdHash ||
        operation.subjects.matrixDeviceIdHash != request.matrixDeviceIdHash ||
        operation.subjects.roomIdHash != request.roomIdHash ||
        operation.subjects.eventIdHash != request.eventIdHash ||
        request.accountIdHash == null || request.matrixDeviceIdHash == null || request.roomIdHash == null ||
        operation.status !in setOf(LifecycleOperationStatus.RUNNING, LifecycleOperationStatus.WAITING_EXTERNAL,
            LifecycleOperationStatus.SUCCEEDED)
    ) return false
    val serverComponents = setOf(LifecycleComponent.MATRIX, LifecycleComponent.KMS,
        LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE)
    val commits = operation.steps.filter { it.component in serverComponents }
    if (commits.size != serverComponents.size || commits.map { it.component }.toSet() != serverComponents ||
        commits.any { !it.required || it.status != LifecycleStepStatus.COMPLETE ||
            it.authorityRevision.isNullOrBlank() || it.receiptReference.isNullOrBlank() }
    ) return false
    val matrix = commits.single { it.component == LifecycleComponent.MATRIX }
    return matrix.resultCode == "MATRIX_EVENT_VERIFIED" && matrix.resultReference == accountIdHash(eventId.value)
}
