/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.api.user.MatrixSearchUserResults
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.FakeMatrixClient
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class MatrixUserListDataSourceTest {
    @Test
    fun `get profile - absent account is no match`() = runTest {
        val client = FakeMatrixClient()
        client.givenGetProfileResult(A_USER_ID, Result.failure(
            ClientException.MatrixApi(ErrorKind.NotFound, "M_NOT_FOUND", "Profile not found", null)
        ))
        assertThat(MatrixUserListDataSource(client).getProfile(A_USER_ID)).isNull()
    }

    @Test
    fun `get profile - forbidden is not an absent account`() = runTest {
        val failure = ClientException.MatrixApi(ErrorKind.Forbidden, "M_FORBIDDEN", "Forbidden", null)
        val client = FakeMatrixClient()
        client.givenGetProfileResult(A_USER_ID, Result.failure(failure))
        assertThat(runCatching { MatrixUserListDataSource(client).getProfile(A_USER_ID) }.exceptionOrNull()).isSameInstanceAs(failure)
    }

    @Test
    fun `search - returns users on success`() = runTest {
        val matrixClient = FakeMatrixClient()
        matrixClient.givenSearchUsersResult(
            searchTerm = "test",
            result = Result.success(
                MatrixSearchUserResults(
                    results = persistentListOf(
                        aMatrixUserProfile(),
                        aMatrixUserProfile(userId = A_USER_ID_2)
                    ),
                    limited = false
                )
            )
        )
        val dataSource = MatrixUserListDataSource(matrixClient)

        val results = dataSource.search("test", 2)
        assertThat(results).containsExactly(
            aMatrixUserProfile(),
            aMatrixUserProfile(userId = A_USER_ID_2)
        )
    }

    @Test
    fun `search - preserves error instead of claiming no matches`() = runTest {
        val matrixClient = FakeMatrixClient()
        matrixClient.givenSearchUsersResult(
            searchTerm = "test",
            result = Result.failure(RuntimeException("Ruhroh"))
        )
        val dataSource = MatrixUserListDataSource(matrixClient)

        assertThat(runCatching { dataSource.search("test", 2) }.exceptionOrNull()?.message).isEqualTo("Ruhroh")
    }

    @Test
    fun `get profile - returns user on success`() = runTest {
        val matrixClient = FakeMatrixClient()
        matrixClient.givenGetProfileResult(
            userId = A_USER_ID,
            result = Result.success(aMatrixUserProfile())
        )
        val dataSource = MatrixUserListDataSource(matrixClient)

        val result = dataSource.getProfile(A_USER_ID)
        assertThat(result).isEqualTo(aMatrixUserProfile())
    }

    @Test
    fun `get profile - preserves errors other than not found`() = runTest {
        val matrixClient = FakeMatrixClient()
        matrixClient.givenGetProfileResult(
            userId = A_USER_ID,
            result = Result.failure(RuntimeException("Ruhroh"))
        )
        val dataSource = MatrixUserListDataSource(matrixClient)

        assertThat(runCatching { dataSource.getProfile(A_USER_ID) }.exceptionOrNull()?.message).isEqualTo("Ruhroh")
    }

    private fun aMatrixUserProfile(
        userId: UserId = A_USER_ID,
        displayName: String = A_USER_NAME,
        avatarUrl: String = AN_AVATAR_URL
    ) = MatrixUser(userId, displayName, avatarUrl)
}
