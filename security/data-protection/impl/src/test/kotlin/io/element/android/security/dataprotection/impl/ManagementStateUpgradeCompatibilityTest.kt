/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagementStateUpgradeCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    @Test
    fun `older state without publication fields stays readable`() {
        val state = json.decodeFromString<PersistedManagementState>("""{"highest_policy_version":7}""")
        assertThat(state.highestPolicyVersion).isEqualTo(7)
        assertThat(state.policyApplicationHistory).isEmpty()
        assertThat(state.latestPolicyApplication).isNull()
    }

    @Test
    fun `newer empty publication fields preserve authority counters through round trip`() {
        val state = json.decodeFromString<PersistedManagementState>(
            """{"highest_policy_version":7,"highest_tombstone_sequence":12,"policy_application_history":[],"latest_policy_application":null}""",
        )
        assertThat(json.decodeFromString<PersistedManagementState>(json.encodeToString(state))).isEqualTo(state)
        assertThat(state.highestTombstoneSequence).isEqualTo(12)
    }

    @Test
    fun `incomplete populated applications cannot become supported signed application records`() {
        val publication = """{"publication":{"nonce":"synthetic"},"state":"APPLIED"}"""
        for (serialized in listOf(
            """{"highest_policy_version":7,"latest_policy_application":$publication}""",
            """{"highest_policy_version":7,"policy_application_history":[$publication]}""",
        )) {
            assertThrows(SerializationException::class.java) { json.decodeFromString<PersistedManagementState>(serialized) }
        }
    }

    @Test
    fun `unrecognized authority fields are still rejected`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<PersistedManagementState>("""{"unrecognized_authority_state":true}""")
        }
    }
}
