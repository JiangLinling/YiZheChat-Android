/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.roomselect.impl

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.api.privateDirectDisplayName
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.roomlist.RoomListFilter
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.roomlist.updateVisibleRange
import io.element.android.libraries.matrix.ui.model.SelectRoomInfo
import io.element.android.libraries.matrix.ui.model.toSelectRoomInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

private const val PAGE_SIZE = 30

/**
 * DataSource for RoomSummaryDetails that can be filtered by a search query,
 * and which only includes rooms the user has joined.
 */
@AssistedInject
class RoomSelectSearchDataSource(
    @Assisted coroutineScope: CoroutineScope,
    roomListService: RoomListService,
    private val friendProfileRepository: FriendProfileRepository,
    coroutineDispatchers: CoroutineDispatchers,
) {
    @AssistedFactory
    interface Factory {
        fun create(coroutineScope: CoroutineScope): RoomSelectSearchDataSource
    }

    private val roomList = roomListService.createRoomList(
        pageSize = PAGE_SIZE,
        source = RoomList.Source.All,
        coroutineScope = coroutineScope
    )
    private val searchQuery = MutableStateFlow("")

    val roomInfoList: Flow<ImmutableList<SelectRoomInfo>> = combine(
        roomList.summaries,
        friendProfileRepository.profiles,
        searchQuery,
    ) { roomSummaries, privateProfiles, query ->
            roomSummaries
                .filter { it.info.currentUserMembership == CurrentUserMembership.JOINED }
                .filter { it.info.directRoomRelationState().canInteract }
                .distinctBy { it.roomId } // This should be removed once we're sure no duplicate Rooms can be received
                .map { roomSummary ->
                        roomSummary.toSelectRoomInfo().withPrivateDirectRoomName(
                        isDirectRoom = roomSummary.info.isDm || roomSummary.info.isDirect,
                        heroUserIds = roomSummary.info.heroes.map { it.userId },
                        heroDisplayNames = roomSummary.info.heroes.map { it.displayName },
                        privateProfiles = privateProfiles,
                    )
                }
                .filter { roomInfo -> roomInfo.matches(query) }
                .toImmutableList()
        }
        .flowOn(coroutineDispatchers.computation)

    suspend fun updateVisibleRange(visibleRange: IntRange) {
        roomList.updateVisibleRange(visibleRange)
    }

    suspend fun setSearchQuery(searchQuery: String) = coroutineScope {
        val normalizedQuery = searchQuery.trim()
        this@RoomSelectSearchDataSource.searchQuery.value = normalizedQuery
        val queryMatchesPrivateRemark = friendProfileRepository.profiles.value.values.any {
            it.remarkName.contains(normalizedQuery, ignoreCase = true)
        }
        val filter = if (normalizedQuery.isBlank() || queryMatchesPrivateRemark) {
            RoomListFilter.all()
        } else {
            RoomListFilter.NormalizedMatchRoomName(normalizedQuery)
        }
        roomList.updateFilter(filter)
    }
}

internal fun SelectRoomInfo.withPrivateDirectRoomName(
    isDirectRoom: Boolean,
    heroUserIds: List<UserId>,
    heroDisplayNames: List<String?> = emptyList(),
    privateProfiles: Map<UserId, FriendPrivateProfile>,
): SelectRoomInfo {
    if (!isDirectRoom) return this
    val heroIndex = heroUserIds.indexOfFirst { privateProfiles.containsKey(it) }
    val heroIndexOrFirst = if (heroIndex >= 0) heroIndex else 0
    val contactUserId = heroUserIds.getOrNull(heroIndexOrFirst) ?: return this
    val matrixDisplayName = heroDisplayNames.getOrNull(heroIndexOrFirst) ?: name
    return copy(name = privateProfiles.privateDirectDisplayName(contactUserId, matrixDisplayName))
}

private fun SelectRoomInfo.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return name?.contains(query, ignoreCase = true) == true ||
        canonicalAlias?.value?.contains(query, ignoreCase = true) == true ||
        heroes.any { hero ->
            hero.displayName?.contains(query, ignoreCase = true) == true ||
                hero.userId.value.contains(query, ignoreCase = true)
        }
}
