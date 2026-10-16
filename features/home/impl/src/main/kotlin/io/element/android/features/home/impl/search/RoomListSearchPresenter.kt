/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.search

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.Presenter
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest

@Inject
class RoomListSearchPresenter(
    private val dataSourceFactory: RoomListSearchDataSource.Factory,
    private val friendProfileRepository: FriendProfileRepository,
    private val client: MatrixClient,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : Presenter<RoomListSearchState> {
    @Composable
    override fun present(): RoomListSearchState {
        // Do not use rememberSaveable so that search is not active when the user navigates back to the screen
        var isSearchActive by remember {
            mutableStateOf(false)
        }
        val searchQuery = rememberTextFieldState()

        val coroutineScope = rememberCoroutineScope()
        // The ordinary list owns startup. A second native room list is needed only once
        // search is opened; retain it afterwards to preserve query and close/reopen behavior.
        val lazyDataSource = remember { lazy(LazyThreadSafetyMode.NONE) { dataSourceFactory.create(coroutineScope) } }
        val dataSource = if (isSearchActive || lazyDataSource.isInitialized()) lazyDataSource.value else null

        LaunchedEffect(dataSource, searchQuery.text) {
            val activeDataSource = dataSource ?: return@LaunchedEffect
            val query = searchQuery.text.toString()
            if (query.isBlank()) {
                activeDataSource.setSearchQuery(query)
            } else {
                runCatching {
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(
                            lifecycleId = FullAppLifecycleId.LC_11,
                            trigger = "search",
                            accountIdHash = client.sessionId.value.sha256Hex(),
                            matrixDeviceIdHash = client.deviceId.value.sha256Hex(),
                        )
                    ) {
                        activeDataSource.setSearchQuery(query)
                    }
                }.onFailure { failure ->
                    Timber.w(failure, "Unable to bind room search lifecycle")
                }
            }
        }

        fun handleEvent(event: RoomListSearchEvent) {
            when (event) {
                RoomListSearchEvent.ClearQuery -> {
                    searchQuery.clearText()
                }
                RoomListSearchEvent.ToggleSearchVisibility -> {
                    isSearchActive = !isSearchActive
                    searchQuery.clearText()
                }
                is RoomListSearchEvent.UpdateVisibleRange -> coroutineScope.launch {
                    dataSource?.updateVisibleRange(visibleRange = event.range)
                }
            }
        }

        val searchResults = dataSource?.roomSummaries?.collectAsState(initial = persistentListOf())?.value ?: persistentListOf()
        val friendProfiles by friendProfileRepository.profiles.collectAsState()
        val contactResults = remember(searchQuery.text, friendProfiles) {
            val query = searchQuery.text.toString().trim()
            if (query.isEmpty()) {
                persistentListOf()
            } else {
                friendProfiles.values
                    .filter { profile ->
                        profile.remarkName.contains(query, ignoreCase = true) ||
                            profile.phone.contains(query, ignoreCase = true) ||
                            profile.tags.contains(query, ignoreCase = true) ||
                            profile.description.contains(query, ignoreCase = true) ||
                            profile.userId.value.contains(query, ignoreCase = true)
                    }
                    .sortedBy { it.displayName(null) }
                    .toImmutableList()
            }
        }

        return RoomListSearchState(
            isSearchActive = isSearchActive,
            query = searchQuery,
            results = searchResults,
            contactResults = contactResults,
            eventSink = ::handleEvent,
        )
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
