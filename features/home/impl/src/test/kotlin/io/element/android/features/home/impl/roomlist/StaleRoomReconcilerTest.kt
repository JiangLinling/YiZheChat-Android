/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.features.preferences.api.ClearCacheUseCase
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.push.test.notifications.FakeNotificationCleaner
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StaleRoomReconcilerTest {
    @Test
    fun `stale local room clears notifications and rebuilds cache`() = runTest {
        val staleRoomId = RoomId("!stale:example.org")
        val retainedRoomId = RoomId("!retained:example.org")
        val clearedMessageRooms = mutableListOf<RoomId>()
        val clearedMembershipRooms = mutableListOf<RoomId>()
        var cacheCleared = false
        val sut = StaleRoomReconciler(
            client = FakeMatrixClient(
                getJoinedRoomIdsResult = { Result.success(setOf(staleRoomId, retainedRoomId)) },
                getRemoteJoinedRoomIdsResult = { Result.success(setOf(retainedRoomId)) },
            ),
            notificationCleaner = FakeNotificationCleaner(
                clearMessagesForRoomLambda = { _, roomId -> clearedMessageRooms += roomId },
                clearMembershipNotificationForRoomLambda = { _, roomId -> clearedMembershipRooms += roomId },
            ),
            clearCacheUseCase = ClearCacheUseCase { cacheCleared = true },
        )

        assertThat(sut()).isTrue()
        assertThat(cacheCleared).isTrue()
        assertThat(clearedMessageRooms).containsExactly(staleRoomId)
        assertThat(clearedMembershipRooms).containsExactly(staleRoomId)
    }

    @Test
    fun `remote failure preserves local cache`() = runTest {
        var cacheCleared = false
        val sut = StaleRoomReconciler(
            client = FakeMatrixClient(
                getJoinedRoomIdsResult = { Result.success(setOf(RoomId("!local:example.org"))) },
                getRemoteJoinedRoomIdsResult = { Result.failure(IllegalStateException("offline")) },
            ),
            notificationCleaner = FakeNotificationCleaner(),
            clearCacheUseCase = ClearCacheUseCase { cacheCleared = true },
        )

        assertThat(sut()).isFalse()
        assertThat(cacheCleared).isFalse()
    }
}
