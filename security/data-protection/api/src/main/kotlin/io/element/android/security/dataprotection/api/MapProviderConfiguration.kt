/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

data class MapProviderConfiguration(
    val provider: String = "legacy",
    val jsKey: String = "",
    val serviceHost: String = "",
)

interface MapProviderConfigurationService {
    suspend fun fetch(): Result<MapProviderConfiguration>
}
