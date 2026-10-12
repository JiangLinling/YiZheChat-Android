/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** Load signed, server-owned operations before the durable receipt queue can admit their facts.
 * A late acknowledgement does not dispatch effects or change the original deadline.
 */
suspend fun AppOperationalStateCoordinator.admitManagedDirectRoomOperations(
    result: ManagedDirectRoomResult,
    accountHash: String,
    deviceHash: String,
): List<LifecycleOperation> = result.clientOperationIds.map { operationId ->
    val original = operation(operationId)
    val child = operationId.startsWith("dmroom-")
    val parentId = "direct-" + operationId.substringAfter('-')
    require(original.operationId == operationId && original.lifecycleId == if (child) FullAppLifecycleId.LC_12 else FullAppLifecycleId.LC_11)
    require(original.subjects.accountIdHash == accountHash && original.subjects.matrixDeviceIdHash == deviceHash)
    require(if (child) original.authorityRevisions["room_create_protocol"] == "managed-v1" &&
        original.authorityRevisions["direct_invitation_operation"] == parentId
        else !original.authorityRevisions["direct_invitation_v1"].isNullOrBlank())
    require(!original.status.isTerminal || original.status == LifecycleOperationStatus.SUCCEEDED)
    val required = original.steps.filter { it.required }.map { it.component }.toSet()
    require(required == setOf(LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE,
        LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND) + if (child) setOf(LifecycleComponent.KMS) else emptySet())
    original
}

fun managedDirectRoomApplicationReceipts(
    operations: List<LifecycleOperation>,
    component: LifecycleComponent,
): List<LifecycleClientReceipt> {
    require(component in setOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND))
    return operations.filter { original ->
        original.steps.single { it.component == component && it.required }.status != LifecycleStepStatus.COMPLETE
    }.map { original ->
        val frontend = component == LifecycleComponent.ANDROID_FRONTEND
        LifecycleClientReceipt(original.operationId, component, LifecyclePhase.CLIENT_APPLIED,
            if (frontend) "MANAGED_DIRECT_ROOM_APPLIED" else "MANAGED_DIRECT_ROOM_SYNCED",
            "android-${if (frontend) "frontend" else "service"}:${original.operationId}", System.currentTimeMillis())
    }
}
