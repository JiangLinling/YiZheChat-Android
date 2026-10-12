/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.search

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.datasource.aRoomListRoomSummaryFactory
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest

class RoomListSearchDataSourceTest {
    @Test
    fun `search excludes deleted peer DM and keeps shared group and unrelated result`() = runTest {
        val deletedUserId = UserId("@deleted:server.org")
        val deletedDmId = RoomId("!deleted-dm:server.org")
        val sharedGroupId = RoomId("!shared-group:server.org")
        val unrelatedDmId = RoomId("!unrelated-dm:server.org")
        val summaries = MutableStateFlow(
            listOf(
                aRoomSummary(info = aRoomInfo(
                    id = deletedDmId,
                    isDm = true,
                    activeMembersCount = 2,
                    joinedMembersCount = 2,
                    invitedMembersCount = 0,
                    heroes = listOf(MatrixUser(deletedUserId)),
                )),
                aRoomSummary(info = aRoomInfo(
                    id = sharedGroupId,
                    isDm = false,
                    heroes = listOf(MatrixUser(deletedUserId)),
                )),
                aRoomSummary(info = aRoomInfo(
                    id = unrelatedDmId,
                    isDm = true,
                    activeMembersCount = 2,
                    joinedMembersCount = 2,
                    invitedMembersCount = 0,
                    heroes = listOf(MatrixUser(UserId("@other:server.org"))),
                )),
            )
        )
        val roomList = FakeDynamicRoomList(summaries = summaries)
        val dataSource = RoomListSearchDataSource(
            coroutineScope = backgroundScope,
            roomListService = FakeRoomListService(createRoomListLambda = { roomList }),
            coroutineDispatchers = testCoroutineDispatchers(),
            roomSummaryFactory = aRoomListRoomSummaryFactory(),
            deletedIdentityProjection = FakeDeletedIdentityProjection(setOf(hash(deletedUserId.value))),
        )

        dataSource.roomSummaries.test {
            assertThat(awaitItem().map { it.roomId })
                .containsExactly(sharedGroupId, unrelatedDmId)
                .inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
