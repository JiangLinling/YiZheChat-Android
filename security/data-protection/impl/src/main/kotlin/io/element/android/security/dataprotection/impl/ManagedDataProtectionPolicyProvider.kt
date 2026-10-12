/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.RuntimeDataProtectionPolicy

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ManagedDataProtectionPolicyProvider(
    private val repository: SignedManagementStateRepository,
) : DataProtectionPolicyProvider {
    init {
        RuntimeDataProtectionPolicy.install(this)
    }

    override fun current(): ActiveDataProtectionPolicy = when (val state = repository.currentPolicyState()) {
        CurrentPolicyState.NoAcceptedPolicy -> BASELINE_POLICY
        is CurrentPolicyState.Active -> ActiveDataProtectionPolicy(state.policy, DataProtectionPolicySource.VERIFIED_REMOTE)
        is CurrentPolicyState.AccessBlocked -> FAIL_CLOSED_POLICY
    }

    override fun resolve(policyId: String, version: Long): ActiveDataProtectionPolicy? =
        repository.verifiedPolicy(policyId, version)?.let { policy ->
            ActiveDataProtectionPolicy(policy, DataProtectionPolicySource.VERIFIED_REMOTE)
        }
}
