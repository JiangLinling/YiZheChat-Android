/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.office

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.withContext

/** Online conversion owns a new preview intent, separate from downloading or selecting its source. */
internal suspend fun <T> AppOperationalStateCoordinator.performOfficePreview(
    request: LifecycleOperationRequest,
    block: suspend (operationId: String) -> T,
): T {
    require(request.lifecycleId == FullAppLifecycleId.LC_16 && request.trigger == "preview")
    require(request.accountIdHash != null && request.matrixDeviceIdHash != null)
    val operation = start(request)
    check(operation.operationId == request.operationId && operation.lifecycleId == request.lifecycleId)
    check(operation.subjects.accountIdHash == request.accountIdHash &&
        operation.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash &&
        operation.subjects.roomIdHash == request.roomIdHash && operation.subjects.eventIdHash == request.eventIdHash)
    check(operation.status in NON_TERMINAL_RESUMABLE_STATUSES && operation.terminalReceiptId == null)
    check(operation.deadlineEpochMillis > System.currentTimeMillis() &&
        operation.deadlineEpochMillis == request.deadlineEpochMillis)
    val required = operation.steps.filter { it.required }
    check(required.size == 7 && required.map { it.component }.toSet() == setOf(
        LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.MATRIX,
        LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE, LifecycleComponent.KMS, LifecycleComponent.OFFICE_PREVIEW,
    ))
    // Override a same-LC download parent only after the new signed preview admission succeeds.
    // The existing receipt journal retains the preview ID even if transport fails.
    return withContext(LifecycleOperationContext(operation.operationId, operation.lifecycleId)) {
        perform(request, block)
    }
}
