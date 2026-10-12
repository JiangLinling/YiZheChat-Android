/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import io.element.android.features.call.impl.security.CallProtectionRequest
import io.element.android.features.call.impl.security.CallProtectionService
import io.element.android.features.call.impl.security.CallProtectionSession
import io.element.android.security.securestorage.api.AccountVaultId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeCallProtectionService(
    private val authorizeResult: (CallProtectionRequest) -> CallProtectionSession = {
        CallProtectionSession(AccountVaultId("fake-vault"), "https://appassets.androidplatform.net", blockScreenCapture = true)
    },
) : CallProtectionService {
    val deviceAccess = MutableStateFlow(true)
    val requests = mutableListOf<CallProtectionRequest>()

    override suspend fun authorize(request: CallProtectionRequest): CallProtectionSession {
        requests += request
        return authorizeResult(request)
    }

    override fun observeDeviceAccess(session: CallProtectionSession): Flow<Boolean> = deviceAccess
}
