/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl.userlist

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.designsystem.theme.components.SearchBarResultState
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.recent.RecentDirectRoom
import io.element.android.libraries.matrix.api.room.recent.getRecentDirectRooms
import io.element.android.libraries.usersearch.api.UserRepository
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

private const val MAX_SUGGESTIONS_COUNT = 5

@AssistedInject
class DefaultUserListPresenter(
    @Assisted val args: UserListPresenterArgs,
    @Assisted val userRepository: UserRepository,
    @Assisted val userListDataStore: UserListDataStore,
    private val matrixClient: MatrixClient,
    private val deletedIdentityProjection: DeletedIdentityProjection,
) : UserListPresenter {
    @AssistedFactory
    @ContributesBinding(SessionScope::class)
    interface DefaultUserListFactory : UserListPresenter.Factory {
        override fun create(
            args: UserListPresenterArgs,
            userRepository: UserRepository,
            userListDataStore: UserListDataStore,
        ): DefaultUserListPresenter
    }

    @Composable
    override fun present(): UserListState {
        val deletedIdentityRevision by deletedIdentityProjection.revision.collectAsState()
        var recentDirectRooms by remember { mutableStateOf(emptyList<RecentDirectRoom>()) }
        LaunchedEffect(deletedIdentityRevision) {
            recentDirectRooms = matrixClient
                .getRecentDirectRooms()
                .filterNot { deletedIdentityProjection.isDeletedMatrixUserId(it.matrixUser.userId.value) }
                .take(MAX_SUGGESTIONS_COUNT)
                .toList()
        }
        var isSearchActive by rememberSaveable { mutableStateOf(false) }
        val selectedUsers by userListDataStore.selectedUsers.collectAsState(emptyList())
        val queryState = rememberTextFieldState()
        var searchResults: SearchBarResultState<ImmutableList<UserSearchResult>> by remember {
            mutableStateOf(SearchBarResultState.Initial())
        }
        var showSearchLoader by remember { mutableStateOf(false) }

        val searchQuery = queryState.text.toString()
        LaunchedEffect(searchQuery, deletedIdentityRevision) {
            searchResults = SearchBarResultState.Initial()
            showSearchLoader = false
            userRepository.search(searchQuery).onEach { state ->
                showSearchLoader = state.isSearching
                val visibleResults = state.results
                    .filterNot { deletedIdentityProjection.isDeletedMatrixUserId(it.matrixUser.userId.value) }
                searchResults = when {
                    visibleResults.isEmpty() && state.isSearching -> SearchBarResultState.Initial()
                    visibleResults.isEmpty() && !state.isSearching -> SearchBarResultState.NoResultsFound()
                    else -> SearchBarResultState.Results(visibleResults.toImmutableList())
                }
            }.launchIn(this)
        }

        fun handleEvent(event: UserListEvents) {
            when (event) {
                is UserListEvents.OnSearchActiveChanged -> isSearchActive = event.active
                is UserListEvents.AddToSelection -> userListDataStore.selectUser(event.matrixUser)
                is UserListEvents.RemoveFromSelection -> userListDataStore.removeUserFromSelection(event.matrixUser)
            }
        }

        return UserListState(
            searchQuery = queryState,
            searchResults = searchResults,
            selectedUsers = selectedUsers.toImmutableList(),
            isSearchActive = isSearchActive,
            showSearchLoader = showSearchLoader,
            selectionMode = args.selectionMode,
            recentDirectRooms = recentDirectRooms.toImmutableList(),
            eventSink = ::handleEvent,
        )
    }
}
