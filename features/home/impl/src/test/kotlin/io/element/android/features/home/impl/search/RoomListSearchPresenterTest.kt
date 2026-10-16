/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.search

import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.datasource.aRoomListRoomSummaryFactory
import io.element.android.features.userprofile.test.FakeFriendProfileRepository
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.matrix.api.roomlist.RoomListFilter
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomListSearchPresenterTest {
    @Test
    fun `present - initial state`() = runTest {
        var searchListsCreated = 0
        val roomList = FakeDynamicRoomList()
        val presenter = createRoomListSearchPresenter(
            roomListService = FakeRoomListService(
                allRooms = FakeDynamicRoomList(),
                createRoomListLambda = { searchListsCreated++; roomList },
            )
        )
        presenter.test {
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
                assertThat(state.query.text.toString()).isEmpty()
                assertThat(state.results).isEmpty()
            }
            testScheduler.advanceUntilIdle()
            assertThat(searchListsCreated).isEqualTo(0)
            assertThat(roomList.summaries.subscriptionCount.value).isEqualTo(0)
            roomList.summaries.emit(listOf(aRoomSummary()))
            testScheduler.advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun `present - search initializes on demand and is retained across close and reopen`() = runTest {
        var searchListsCreated = 0
        val roomList = FakeDynamicRoomList()
        val presenter = createRoomListSearchPresenter(
            roomListService = FakeRoomListService(
                allRooms = FakeDynamicRoomList(),
                createRoomListLambda = { searchListsCreated++; roomList },
            )
        )
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            val opened = awaitItem()
            assertThat(opened.isSearchActive).isTrue()
            assertThat(searchListsCreated).isEqualTo(1)
            opened.query.edit { append("Search") }
            val queried = awaitItem()
            assertThat(roomList.currentFilter.value).isEqualTo(RoomListFilter.NormalizedMatchRoomName("Search"))
            queried.eventSink(RoomListSearchEvent.ClearQuery)
            val cleared = awaitItem()
            assertThat(cleared.query.text.toString()).isEmpty()
            assertThat(roomList.currentFilter.value).isEqualTo(RoomListFilter.None)
            cleared.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            val closed = awaitItem()
            assertThat(closed.isSearchActive).isFalse()
            assertThat(closed.query.text.toString()).isEmpty()
            assertThat(roomList.currentFilter.value).isEqualTo(RoomListFilter.None)
            closed.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            assertThat(awaitItem().isSearchActive).isTrue()
            assertThat(searchListsCreated).isEqualTo(1)
            roomList.summaries.emit(listOf(aRoomSummary()))
            assertThat(awaitItem().results).hasSize(1)
        }
    }

    @Test
    fun `present - toggle search visibility`() = runTest {
        val presenter = createRoomListSearchPresenter()
        presenter.test {
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
                state.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            }
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isTrue()
                state.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            }
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
            }
        }
    }

    @Test
    fun `present - query search changes`() = runTest {
        val roomList = FakeDynamicRoomList()
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            awaitItem().let { state ->
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.None
                )
                state.query.edit { append("Search") }
            }
            awaitItem().let { state ->
                assertThat(state.query.text).isEqualTo("Search")
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.NormalizedMatchRoomName("Search")
                )
                state.eventSink(RoomListSearchEvent.ClearQuery)
            }
            awaitItem().let { state ->
                assertThat(state.query.text.toString()).isEmpty()
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.None
                )
            }
        }
    }

    @Test
    fun `present - room list changes`() = runTest {
        val roomList = FakeDynamicRoomList()
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            awaitItem().let { state ->
                assertThat(state.results).isEmpty()
            }
            roomList.summaries.emit(
                listOf(aRoomSummary())
            )
            awaitItem().let { state ->
                assertThat(state.results).hasSize(1)
            }
            roomList.summaries.emit(emptyList())
            awaitItem().let { state ->
                assertThat(state.results).isEmpty()
            }
        }
    }

    @Test
    fun `present - query finds encrypted local friend profile`() = runTest {
        val friendId = UserId("@alice:example.org")
        val presenter = createRoomListSearchPresenter(
            friendProfileRepository = FakeFriendProfileRepository(
                mapOf(friendId to FriendPrivateProfile(userId = friendId, remarkName = "爱丽丝", tags = "同事"))
            )
        )
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            val initialState = awaitItem()
            initialState.query.edit { append("同事") }
            val resultState = awaitItem()
            assertThat(resultState.contactResults.map { it.userId }).containsExactly(friendId)
        }
    }

    @Test
    fun `present - lifecycle failure does not perform room search`() = runTest {
        val roomList = FakeDynamicRoomList()
        val coordinator = FakeAppOperationalStateCoordinator(
            startFailure = { IllegalStateException("lifecycle unavailable") },
        )
        val presenter = createRoomListSearchPresenter(
            roomListService = FakeRoomListService(createRoomListLambda = { roomList }),
            appOperationalStateCoordinator = coordinator,
        )
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            val initialState = awaitItem()
            initialState.query.edit { append("Search") }
            awaitItem()
            testScheduler.advanceUntilIdle()

            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(roomList.currentFilter.value).isEqualTo(RoomListFilter.None)
        }
    }

    @Test
    fun `present - UpdateVisibleRange triggers pagination when near end`() = runTest {
        val loadMoreLambda = lambdaRecorder<Unit> { }
        val roomList = FakeDynamicRoomList(loadMoreLambda = loadMoreLambda)
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            awaitItem().eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            val initialState = awaitItem()
            // Post some rooms to simulate loaded content
            val rooms = (1..10).map { aRoomSummary() }
            roomList.summaries.emit(rooms)
            skipItems(1)

            // UpdateVisibleRange near end should trigger loadMore
            initialState.eventSink(RoomListSearchEvent.UpdateVisibleRange(IntRange(0, 9)))
            // Give time for the coroutine to complete
            testScheduler.advanceUntilIdle()

            assert(loadMoreLambda).isCalledOnce()
        }
    }
}

fun TestScope.createRoomListSearchPresenter(
    roomListService: RoomListService = FakeRoomListService(),
    friendProfileRepository: FriendProfileRepository = FakeFriendProfileRepository(),
    appOperationalStateCoordinator: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
): RoomListSearchPresenter {
    return RoomListSearchPresenter(
        dataSourceFactory = object : RoomListSearchDataSource.Factory {
            override fun create(coroutineScope: CoroutineScope): RoomListSearchDataSource {
                return RoomListSearchDataSource(
                    roomListService = roomListService,
                    roomSummaryFactory = aRoomListRoomSummaryFactory(
                        dateFormatter = FakeDateFormatter(),
                        roomLatestEventFormatter = FakeRoomLatestEventFormatter(),
                    ),
                    coroutineDispatchers = testCoroutineDispatchers(),
                    coroutineScope = coroutineScope,
                    deletedIdentityProjection = FakeDeletedIdentityProjection(),
                )
            }
        },
        friendProfileRepository = friendProfileRepository,
        client = FakeMatrixClient(),
        appOperationalStateCoordinator = appOperationalStateCoordinator,
    )
}
