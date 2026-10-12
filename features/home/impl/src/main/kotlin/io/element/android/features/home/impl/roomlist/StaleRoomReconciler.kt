/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.roomlist

import dev.zacsweers.metro.Inject
import io.element.android.features.preferences.api.ClearCacheUseCase
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import timber.log.Timber

/** Reconciles stale local joined-room snapshots after a server-side room purge. */
@Inject
class StaleRoomReconciler(
    private val client: MatrixClient,
    private val notificationCleaner: NotificationCleaner,
    private val clearCacheUseCase: ClearCacheUseCase,
) {
    /** Returns true when stale rooms were found and the local non-cryptographic cache was rebuilt. */
    suspend operator fun invoke(): Boolean {
        val localRoomIds = client.getJoinedRoomIds()
            .onFailure { Timber.w(it, "Unable to read locally joined rooms during reconciliation") }
            .getOrNull()
            ?: return false
        val remoteRoomIds = client.getRemoteJoinedRoomIds()
            .onFailure { Timber.w(it, "Unable to read remotely joined rooms during reconciliation") }
            .getOrNull()
            ?: return false
        val staleRoomIds = localRoomIds - remoteRoomIds
        if (staleRoomIds.isEmpty()) return false

        Timber.w("Rebuilding the local room cache after detecting %d stale joined rooms", staleRoomIds.size)
        staleRoomIds.forEach { roomId ->
            notificationCleaner.clearMessagesForRoom(client.sessionId, roomId)
            notificationCleaner.clearMembershipNotificationForRoom(client.sessionId, roomId)
        }
        clearCacheUseCase()
        return true
    }
}
