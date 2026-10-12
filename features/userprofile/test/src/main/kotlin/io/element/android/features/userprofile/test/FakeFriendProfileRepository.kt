/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.test

import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeFriendProfileRepository(
    initialProfiles: Map<UserId, FriendPrivateProfile> = emptyMap(),
) : FriendProfileRepository {
    private val mutableProfiles = MutableStateFlow(initialProfiles)
    override val profiles: StateFlow<Map<UserId, FriendPrivateProfile>> = mutableProfiles

    override suspend fun load() = Unit
    override suspend fun save(profile: FriendPrivateProfile) { mutableProfiles.value += profile.userId to profile }
    override suspend fun delete(userId: UserId) { mutableProfiles.value -= userId }
}
