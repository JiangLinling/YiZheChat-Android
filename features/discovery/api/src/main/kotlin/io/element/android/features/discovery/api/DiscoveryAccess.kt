/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.api

import kotlinx.coroutines.flow.StateFlow

data class DiscoveryAccessState(
    val entryVisible: Boolean = false,
    val entered: Boolean = false,
    val busy: Boolean = false,
    val error: DiscoveryError? = null,
)

enum class DiscoveryError { UNAVAILABLE, DENIED, SESSION_EXPIRED }

/** Guest-only entry. Failures never log out, refresh or mutate the host account. */
interface DiscoveryAccess {
    /** False means no observer work or network calls are needed at all. */
    val configured: Boolean
    val state: StateFlow<DiscoveryAccessState>
    suspend fun refreshAccess()
    suspend fun enter(): Boolean
    suspend fun leave()
}
