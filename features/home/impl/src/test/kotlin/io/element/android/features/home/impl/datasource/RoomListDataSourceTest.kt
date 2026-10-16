/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.FakeDateTimeObserver
import io.element.android.libraries.androidutils.system.DateTimeObserver
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID_3
import io.element.android.libraries.matrix.test.notificationsettings.FakeNotificationSettingsService
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleaner
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleanupResult
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleanupTarget
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.security.MessageDigest

class RoomListDataSourceTest {
    @Test
    fun `account tombstone removes only deleted peer direct rooms and keeps shared group and unrelated DM`() = runTest {
        val deletedUserId = UserId("@deleted:server.org")
        val otherUserId = UserId("@other:server.org")
        val deletedDmId = RoomId("!deleted-dm:server.org")
        val deletedInviteId = RoomId("!deleted-invite:server.org")
        val sharedGroupId = RoomId("!shared-group:server.org")
        val unrelatedDmId = RoomId("!unrelated-dm:server.org")
        val source = MutableStateFlow(
            listOf(
                aRoomSummary(info = aRoomInfo(
                    id = deletedDmId,
                    isDm = true,
                    heroes = listOf(MatrixUser(deletedUserId)),
                    numUnreadMessages = 7,
                )),
                aRoomSummary(info = aRoomInfo(
                    id = deletedInviteId,
                    isDm = true,
                    currentUserMembership = CurrentUserMembership.INVITED,
                    inviter = aRoomMember(userId = deletedUserId),
                    heroes = listOf(MatrixUser(deletedUserId)),
                    numUnreadMentions = 2,
                )),
                aRoomSummary(info = aRoomInfo(
                    id = sharedGroupId,
                    name = "Shared group",
                    isDm = false,
                    heroes = listOf(MatrixUser(deletedUserId)),
                    numUnreadMessages = 3,
                )),
                aRoomSummary(info = aRoomInfo(
                    id = unrelatedDmId,
                    isDm = true,
                    heroes = listOf(MatrixUser(otherUserId)),
                    numUnreadMessages = 5,
                )),
            )
        )
        val roomList = FakeDynamicRoomList(summaries = source)
        val projection = FakeDeletedIdentityProjection()
        val cleanedTargets = mutableListOf<DeletedIdentityProjectionCleanupTarget>()
        val dataSource = createRoomListDataSource(
            roomListService = FakeRoomListService(createRoomListLambda = { roomList }),
            deletedIdentityProjection = projection,
            deletedIdentityProjectionCleaner = object : DeletedIdentityProjectionCleaner {
                override suspend fun clear(target: DeletedIdentityProjectionCleanupTarget): DeletedIdentityProjectionCleanupResult {
                    cleanedTargets += target
                    return DeletedIdentityProjectionCleanupResult(target.roomIds, emptySet())
                }
            },
        )

        dataSource.roomSummariesFlow.test {
            dataSource.launchIn(backgroundScope)
            val before = awaitItem()
            assertThat(before.map { it.roomId }).containsExactly(
                deletedDmId,
                deletedInviteId,
                sharedGroupId,
                unrelatedDmId,
            ).inOrder()
            projection.markDeleted(hash(deletedUserId.value))
            val after = awaitItem()
            assertThat(after.map { it.roomId }).containsExactly(sharedGroupId, unrelatedDmId).inOrder()
            assertThat(after.first { it.roomId == sharedGroupId }.numberOfUnreadMessages).isEqualTo(3)
            assertThat(after.first { it.roomId == unrelatedDmId }.numberOfUnreadMessages).isEqualTo(5)
            assertThat(cleanedTargets.flatMap { it.roomIds }).containsExactly(
                deletedDmId.value,
                deletedInviteId.value,
            )
            assertThat(cleanedTargets.single().deletedIdentity.generation).isEqualTo(1)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `authorized protected preview is retried after the initial SDK projection`() = runTest {
        var rebuildCount = 0
        var previewRequests = 0
        val roomList = FakeDynamicRoomList(
            summaries = MutableStateFlow(listOf(aRoomSummary())),
            rebuildSummariesLambda = { rebuildCount += 1 },
            updatePreviewRoomsLambda = { previewRequests += 1 },
        )
        val roomListDataSource = createRoomListDataSource(
            roomListService = FakeRoomListService(createRoomListLambda = { roomList }),
            protectedPreviewAuthorizationGate = FakeProtectedPreviewAuthorizationGate(allowsPreview = true),
        )
        roomListDataSource.launchIn(backgroundScope)
        runCurrent()
        roomListDataSource.updatePreviewRooms(listOf(A_ROOM_ID))
        val previewRequestsBeforeRetry = previewRequests

        advanceTimeBy(501L)
        runCurrent()

        assertThat(previewRequests).isEqualTo(previewRequestsBeforeRetry + 1)
    }

    @Test
    fun `when protected authorization revision changes room summaries are rebuilt`() = runTest {
        var rebuildCount = 0
        val roomList = FakeDynamicRoomList(
            rebuildSummariesLambda = { rebuildCount += 1 },
        )
        val roomListService = FakeRoomListService(createRoomListLambda = { roomList })
        val authorizationGate = FakeProtectedPreviewAuthorizationGate(allowsPreview = true)

        createRoomListDataSource(
            roomListService = roomListService,
            protectedPreviewAuthorizationGate = authorizationGate,
        )
        runCurrent()
        val rebuildCountBeforeRevision = rebuildCount

        authorizationGate.requestAuthorizationRefresh()
        runCurrent()

        assertThat(rebuildCount).isGreaterThan(rebuildCountBeforeRevision)
    }

    @Test
    fun `when DateTimeObserver gets a date change, the room summaries are refreshed`() = runTest {
        val roomList = FakeDynamicRoomList().apply {
            summaries.emit(listOf(aRoomSummary()))
        }
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        ).apply {
            postState(RoomListService.State.Running)
        }
        val dateTimeObserver = FakeDateTimeObserver()
        var dateFormatterResult = "Today"
        val dateFormatter = FakeDateFormatter({ _, _, _ -> dateFormatterResult })
        val roomListDataSource = createRoomListDataSource(
            roomListService = roomListService,
            roomListRoomSummaryFactory = aRoomListRoomSummaryFactory(
                dateFormatter = dateFormatter,
            ),
            dateTimeObserver = dateTimeObserver,
        )

        roomListDataSource.roomSummariesFlow.test {
            // Observe room list items changes
            roomListDataSource.launchIn(backgroundScope)
            // Get the initial room list
            val initialRoomList = awaitItem()
            assertThat(initialRoomList).isNotEmpty()
            assertThat(initialRoomList.first().timestamp).isEqualTo("Today")
            dateFormatterResult = "Yesterday"
            // Trigger a date change
            dateTimeObserver.given(DateTimeObserver.Event.DateChanged(Instant.MIN, Instant.now()))
            // Check there is a new list and it's not the same as the previous one
            val newRoomList = awaitItem()
            assertThat(newRoomList).isNotSameInstanceAs(initialRoomList)
            assertThat(newRoomList.first().timestamp).isEqualTo("Yesterday")
        }
    }

    @Test
    fun `when DateTimeObserver gets a time zone change, the room summaries are refreshed`() = runTest {
        val roomList = FakeDynamicRoomList(summaries = MutableStateFlow(listOf(aRoomSummary())))
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        ).apply {
            postState(RoomListService.State.Running)
        }
        val dateTimeObserver = FakeDateTimeObserver()
        var dateFormatterResult = "Today"
        val dateFormatter = FakeDateFormatter({ _, _, _ -> dateFormatterResult })
        val roomListDataSource = createRoomListDataSource(
            roomListService = roomListService,
            roomListRoomSummaryFactory = aRoomListRoomSummaryFactory(
                dateFormatter = dateFormatter,
            ),
            dateTimeObserver = dateTimeObserver,
        )
        roomListDataSource.roomSummariesFlow.test {
            // Observe room list items changes
            roomListDataSource.launchIn(backgroundScope)
            // Get the initial room list
            val initialRoomList = awaitItem()
            assertThat(initialRoomList).isNotEmpty()
            assertThat(initialRoomList.first().timestamp).isEqualTo("Today")
            dateFormatterResult = "Yesterday"
            // Trigger a timezone change
            dateTimeObserver.given(DateTimeObserver.Event.TimeZoneChanged)
            // Check there is a new list and it's not the same as the previous one
            val newRoomList = awaitItem()
            assertThat(newRoomList).isNotSameInstanceAs(initialRoomList)
            assertThat(newRoomList.first().timestamp).isEqualTo("Yesterday")
        }
    }

    /**
     * Tracking issue #4182: rooms duplicated in the room list around midnight.
     *
     * If the SDK ever leaks a list containing the same roomId twice (the suspected cause of #4182),
     * the UI mapper's `distinctBy` safety net in [RoomListDataSource.buildAndEmitAllRooms] must
     * remove the duplicate AND `analyticsService.trackError` must fire so the team can root-cause
     * it via Sentry.
     */
    @Test
    fun `when SDK summaries source contains duplicate roomIds, UI layer dedupes and reports trackError`() = runTest {
        val analyticsService = FakeAnalyticsService()
        val duplicatedSummaries = listOf(
            aRoomSummary(roomId = A_ROOM_ID),
            aRoomSummary(roomId = A_ROOM_ID),
            aRoomSummary(roomId = A_ROOM_ID_2),
        )
        val roomList = FakeDynamicRoomList(summaries = MutableStateFlow(duplicatedSummaries))
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        ).apply {
            postState(RoomListService.State.Running)
        }
        val roomListDataSource = createRoomListDataSource(
            roomListService = roomListService,
            analyticsService = analyticsService,
        )

        roomListDataSource.roomSummariesFlow.test {
            roomListDataSource.launchIn(backgroundScope)
            val list = awaitItem()
            assertThat(list.map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()
            assertThat(analyticsService.trackedErrors).hasSize(1)
        }
    }

    /**
     * Tracking issue #4182.
     *
     * Targeted scenario: a `DateChanged` tick fires after an initial SDK emit, then a follow-up
     * SDK emit lands (mimicking "midnight, then a new message arrives"). Even though the diffCache
     * is bypassed during the rebuild (`useCache = false`), the final state must contain each
     * roomId exactly once and trackError must not fire on a happy path.
     */
    @Test
    fun `interleaved date change and SDK update with overlapping content does not produce duplicates`() = runTest {
        val analyticsService = FakeAnalyticsService()
        val summariesFlow = MutableStateFlow(
            listOf(
                aRoomSummary(roomId = A_ROOM_ID),
                aRoomSummary(roomId = A_ROOM_ID_2),
            )
        )
        val roomList = FakeDynamicRoomList(summaries = summariesFlow)
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        ).apply {
            postState(RoomListService.State.Running)
        }
        val dateTimeObserver = FakeDateTimeObserver()
        val roomListDataSource = createRoomListDataSource(
            roomListService = roomListService,
            dateTimeObserver = dateTimeObserver,
            analyticsService = analyticsService,
        )

        roomListDataSource.roomSummariesFlow.test {
            roomListDataSource.launchIn(backgroundScope)
            val initial = awaitItem()
            assertThat(initial.map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()

            // Midnight ticks while the cache holds [A_ROOM_ID, A_ROOM_ID_2]
            dateTimeObserver.given(DateTimeObserver.Event.DateChanged(Instant.MIN, Instant.now()))
            val afterMidnight = awaitItem()
            assertThat(afterMidnight.map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()

            // A new message bumps A_ROOM_ID — different unread count makes the StateFlow see this
            // as a new value
            summariesFlow.value = listOf(
                aRoomSummary(roomId = A_ROOM_ID, numUnreadMessages = 1),
                aRoomSummary(roomId = A_ROOM_ID_2),
            )
            val afterMessage = awaitItem()
            assertThat(afterMessage.map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()
            assertThat(afterMessage.map { it.roomId }.toSet()).hasSize(afterMessage.size)

            // Second midnight rebuild after the new message
            dateTimeObserver.given(DateTimeObserver.Event.DateChanged(Instant.MIN, Instant.now()))
            val afterSecondMidnight = awaitItem()
            assertThat(afterSecondMidnight.map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()
            assertThat(afterSecondMidnight.map { it.roomId }.toSet()).hasSize(afterSecondMidnight.size)

            assertThat(analyticsService.trackedErrors).isEmpty()
        }
    }

    @Test
    fun `regression test for race with DateTimeObserver and new items`() = runTest {
        val roomList = FakeDynamicRoomList(summaries = MutableStateFlow(listOf(aRoomSummary(), aRoomSummary(A_ROOM_ID_2))))
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        ).apply {
            postState(RoomListService.State.Running)
        }
        val dateTimeObserver = FakeDateTimeObserver()
        var dateFormatterResult = "Today"
        val dateFormatter = FakeDateFormatter({ _, _, _ -> dateFormatterResult })
        val roomListDataSource = createRoomListDataSource(
            roomListService = roomListService,
            roomListRoomSummaryFactory = aRoomListRoomSummaryFactory(
                dateFormatter = dateFormatter,
            ),
            dateTimeObserver = dateTimeObserver,
        )
        roomListDataSource.roomSummariesFlow.test {
            // Observe room list items changes
            val job = roomListDataSource.launchIn(backgroundScope)
            // Get the initial room list
            val initialRoomList = awaitItem()
            assertThat(initialRoomList).hasSize(2)
            assertThat(initialRoomList[0].roomId).isEqualTo(A_ROOM_ID)
            assertThat(initialRoomList[0].timestamp).isEqualTo(dateFormatterResult)
            assertThat(initialRoomList[1].roomId).isEqualTo(A_ROOM_ID_2)
            assertThat(initialRoomList[1].timestamp).isEqualTo(dateFormatterResult)

            // Stop processing room list updates so we can force a race condition with the date time observer updates
            job.cancel()

            // Trigger a date change and a new item at the same time
            dateFormatterResult = "Yesterday"
            roomList.summaries.tryEmit(listOf(aRoomSummary(roomId = A_ROOM_ID), aRoomSummary(roomId = A_ROOM_ID_3), aRoomSummary(roomId = A_ROOM_ID_2)))
            dateTimeObserver.given(DateTimeObserver.Event.DateChanged(Instant.MIN, Instant.now()))

            // The race condition would have caused the cache indices to be corrupted and only 2 items would be emitted
            val rebuiltRoomList = awaitItem()
            assertThat(rebuiltRoomList).hasSize(3)
            assertThat(rebuiltRoomList[0].roomId).isEqualTo(A_ROOM_ID)
            assertThat(rebuiltRoomList[0].timestamp).isEqualTo(dateFormatterResult)
            assertThat(rebuiltRoomList[1].roomId).isEqualTo(A_ROOM_ID_3)
            assertThat(rebuiltRoomList[1].timestamp).isEqualTo(dateFormatterResult)
            assertThat(rebuiltRoomList[2].roomId).isEqualTo(A_ROOM_ID_2)
            assertThat(rebuiltRoomList[2].timestamp).isEqualTo(dateFormatterResult)

            // Restart processing room list updates
            roomListDataSource.launchIn(backgroundScope)

            // Check there is a new list and it's not the same as the previous one
            val newRoomList = awaitItem()
            assertThat(newRoomList).hasSize(3)
            assertThat(newRoomList[0].roomId).isEqualTo(A_ROOM_ID)
            assertThat(newRoomList[0].timestamp).isEqualTo(dateFormatterResult)
            assertThat(newRoomList[1].roomId).isEqualTo(A_ROOM_ID_3)
            assertThat(newRoomList[1].timestamp).isEqualTo(dateFormatterResult)
            assertThat(newRoomList[2].roomId).isEqualTo(A_ROOM_ID_2)
            assertThat(newRoomList[2].timestamp).isEqualTo(dateFormatterResult)
        }
    }

    private fun TestScope.createRoomListDataSource(
        roomListService: FakeRoomListService = FakeRoomListService(),
        roomListRoomSummaryFactory: RoomListRoomSummaryFactory = aRoomListRoomSummaryFactory(),
        notificationSettingsService: FakeNotificationSettingsService = FakeNotificationSettingsService(),
        dateTimeObserver: FakeDateTimeObserver = FakeDateTimeObserver(),
        analyticsService: FakeAnalyticsService = FakeAnalyticsService(),
        protectedPreviewAuthorizationGate: FakeProtectedPreviewAuthorizationGate = FakeProtectedPreviewAuthorizationGate(),
        deletedIdentityProjection: FakeDeletedIdentityProjection = FakeDeletedIdentityProjection(),
        deletedIdentityProjectionCleaner: DeletedIdentityProjectionCleaner = NoopDeletedIdentityProjectionCleaner,
    ) = RoomListDataSource(
        roomListService = roomListService,
        roomListRoomSummaryFactory = roomListRoomSummaryFactory,
        coroutineDispatchers = testCoroutineDispatchers(),
        notificationSettingsService = notificationSettingsService,
        sessionCoroutineScope = backgroundScope,
        dateTimeObserver = dateTimeObserver,
        analyticsService = analyticsService,
        protectedPreviewAuthorizationGate = protectedPreviewAuthorizationGate,
        deletedIdentityProjection = deletedIdentityProjection,
        deletedIdentityProjectionCleaner = deletedIdentityProjectionCleaner,
    )
}

private object NoopDeletedIdentityProjectionCleaner : DeletedIdentityProjectionCleaner {
    override suspend fun clear(target: DeletedIdentityProjectionCleanupTarget) =
        DeletedIdentityProjectionCleanupResult(target.roomIds, emptySet())
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
