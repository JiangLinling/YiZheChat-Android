/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.MatrixPatterns
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.usersearch.api.UserListDataSource
import io.element.android.libraries.usersearch.api.UserRepository
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.libraries.usersearch.api.UserSearchResultState
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@ContributesBinding(SessionScope::class)
class MatrixUserRepository(
    private val client: MatrixClient,
    private val dataSource: UserListDataSource
) : UserRepository {
    override fun search(query: String): Flow<UserSearchResultState> = flow {
        val text = query.trim()
        if (text.length < MINIMUM_SEARCH_LENGTH) return@flow
        emit(UserSearchResultState(isSearching = true, results = emptyList()))
        emit(fetchSearchResults(text))
    }

    private suspend fun fetchSearchResults(query: String): UserSearchResultState {
        // Debounce
        delay(DEBOUNCE_TIME_MILLIS)
        var failed = false
        val directory = try {
            dataSource.search(query, MAXIMUM_SEARCH_RESULTS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
            emptyList()
        }
        val results = directory
            .filter { !client.isMe(it.userId) }
            .map { UserSearchResult(it) }
            .toMutableList()

        val exactId = when {
            MatrixPatterns.isUserId(query) -> UserId(query)
            query.removePrefix("@").matches(Regex("[a-z0-9._=/-]{3,255}")) ->
                UserId("@${query.removePrefix("@")}:${client.sessionId.value.substringAfter(':')}")
            else -> null
        }
        if (exactId != null && !client.isMe(exactId) && results.none { it.matrixUser.userId == exactId }) {
            try {
                dataSource.getProfile(exactId)?.takeIf { it.userId == exactId }?.let { results.add(0, UserSearchResult(it)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
        return UserSearchResultState(results = results.distinctBy { it.matrixUser.userId }, isSearching = false, hasError = failed)
    }

    companion object {
        private const val DEBOUNCE_TIME_MILLIS = 250L
        private const val MINIMUM_SEARCH_LENGTH = 3
        private const val MAXIMUM_SEARCH_RESULTS = 10L
    }
}
