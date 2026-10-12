/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection
import io.element.android.security.securestorage.api.accountIdHash
import org.junit.Test

class DefaultFriendProfileRepositoryTest {
    @Test
    fun `account tombstone overrides old profile timestamp without changing another friend`() {
        val deletedUser = UserId("@deleted:example.org")
        val retainedUser = UserId("@retained:example.org")
        val deletedRecord = ProfileRecord(
            profile = FriendPrivateProfile(
                userId = deletedUser,
                remarkName = "must disappear",
                phone = "13800000000",
                tags = "old friend",
                description = "private content",
            ),
            modifiedAtEpochMillis = Long.MAX_VALUE,
            deleted = false,
        )
        val retainedRecord = ProfileRecord(
            profile = FriendPrivateProfile(retainedUser, remarkName = "retained"),
            modifiedAtEpochMillis = 23,
            deleted = false,
        )
        val projection = FakeDeletedIdentityProjection(setOf(accountIdHash(deletedUser.value)))

        val result = applyDeletedIdentityTombstones(
            value = mapOf(deletedUser to deletedRecord, retainedUser to retainedRecord),
            deletedIdentityProjection = projection,
        )

        assertThat(result.getValue(deletedUser)).isEqualTo(
            ProfileRecord(
                profile = FriendPrivateProfile(deletedUser),
                modifiedAtEpochMillis = Long.MAX_VALUE,
                deleted = true,
            )
        )
        assertThat(result.getValue(retainedUser)).isEqualTo(retainedRecord)
    }
}
