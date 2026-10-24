/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.test

import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.DiscoveryAccessState
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDiscoveryAccess(override val configured: Boolean = false) : DiscoveryAccess {
    override val state = MutableStateFlow(DiscoveryAccessState())
    override suspend fun refreshAccess() = Unit
    override suspend fun enter(): Boolean = state.value.entryVisible.also { if (it) state.value = state.value.copy(entered = true) }
    override suspend fun leave() { state.value = state.value.copy(entered = false) }
}
