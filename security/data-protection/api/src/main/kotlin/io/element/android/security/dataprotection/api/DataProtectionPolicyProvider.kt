/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import kotlinx.coroutines.flow.StateFlow

enum class DataProtectionPolicySource {
    BUILT_IN_BASELINE,
    VERIFIED_REMOTE,
    FAIL_CLOSED,
}

data class ActiveDataProtectionPolicy(
    val policy: DataProtectionPolicyV1,
    val source: DataProtectionPolicySource,
)

interface DataProtectionPolicyProvider {
    fun current(): ActiveDataProtectionPolicy

    /**
     * Resolves the exact verified policy that originally protected existing content.
     *
     * Implementations backed only by a single policy remain safe by returning that policy only
     * when both identifiers match. Managed implementations may resolve retained signed history.
     */
    fun resolve(policyId: String, version: Long): ActiveDataProtectionPolicy? = current().takeIf {
        it.policy.policyId == policyId && it.policy.version == version
    }
}

/**
 * Emits whenever a newly synchronized management policy must be applied to live UI surfaces.
 */
interface DataProtectionPolicyRevisionProvider {
    val policyRevision: StateFlow<Long>
}
