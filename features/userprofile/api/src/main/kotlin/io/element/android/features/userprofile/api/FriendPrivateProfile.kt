/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.api

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class FriendPrivateProfile(
    val userId: UserId,
    val remarkName: String = "",
    val phone: String = "",
    val tags: String = "",
    val description: String = "",
) {
    fun displayName(fallback: String?): String = remarkName.trim().ifEmpty { fallback?.trim().orEmpty() }.ifEmpty { userId.value }
}

/**
 * Resolves a user-facing name without ever publishing the private remark to Matrix.
 * All UI surfaces should use this helper instead of reading the Matrix display name directly.
 */
fun Map<UserId, FriendPrivateProfile>.privateDisplayName(userId: UserId, fallback: String?): String =
    this[userId]?.displayName(fallback) ?: fallback?.trim().orEmpty().ifEmpty { userId.value }

/**
 * Resolves a one-to-one room name.  A Matrix room alias is an implementation
 * detail and must never be used as the fallback for a direct conversation.
 */
fun Map<UserId, FriendPrivateProfile>.privateDirectDisplayName(userId: UserId, matrixDisplayName: String?): String =
    this[userId]?.displayName(matrixDisplayName.matrixDisplayNameOrNull())
        ?: matrixDisplayName.matrixDisplayNameOrNull().orEmpty().ifEmpty { userId.value }

fun String?.matrixDisplayNameOrNull(): String? = this?.trim()?.takeUnless(::isBootstrapAlias)?.takeIf { it.isNotEmpty() }

private fun isBootstrapAlias(value: String): Boolean =
    BOOTSTRAP_ALIAS_PATTERN.matches(value.trim())

private val BOOTSTRAP_ALIAS_PATTERN = Regex("^onboarding-[0-9a-f]{32}$")

interface FriendProfileRepository {
    val profiles: StateFlow<Map<UserId, FriendPrivateProfile>>

    suspend fun load()
    suspend fun save(profile: FriendPrivateProfile)
    suspend fun delete(userId: UserId)
}
