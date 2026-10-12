/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.admitManagedDirectRoomOperations
import io.element.android.security.dataprotection.api.managedDirectRoomApplicationReceipts
import io.element.android.security.dataprotection.api.ManagementControlService

/** The authority owns durable intent identity. Android only acknowledges its actual local sync. */
internal class ManagedDirectRoomLifecycle(
    private val management: ManagementControlService,
    private val coordinator: AppOperationalStateCoordinator,
    private val accountHash: String,
    private val deviceHash: String,
    private val awaitLocalRoom: suspend (String) -> Unit,
) {
    suspend fun execute(peer: String, create: Boolean): String? {
        check(management.supportsManagedRoomCreation)
        val result = management.managedDirectRoom(accountHash, deviceHash, peer, create)
        if (result == null) {
            check(!create) { "Managed direct creation returned no room" }
            return null
        }
        awaitLocalRoom(result.roomId)
        val operations = coordinator.admitManagedDirectRoomOperations(result, accountHash, deviceHash)
        coordinator.enqueueClientReceipts(managedDirectRoomApplicationReceipts(operations, LifecycleComponent.ANDROID_SERVICE))
        return result.roomId
    }
}
