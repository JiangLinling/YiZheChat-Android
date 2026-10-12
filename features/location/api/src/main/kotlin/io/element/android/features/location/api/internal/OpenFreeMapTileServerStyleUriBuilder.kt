/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

/**
 * Keyless fallback map provider used when a MapTiler key is not configured.
 *
 * OpenFreeMap styles include the required map data attribution and are compatible with MapLibre Native.
 */
internal class OpenFreeMapTileServerStyleUriBuilder(
    private val lightStyleUrl: String = "https://tiles.openfreemap.org/styles/liberty",
    private val darkStyleUrl: String = "https://tiles.openfreemap.org/styles/dark",
) : TileServerStyleUriBuilder {
    override fun build(
        customMapStyleUrl: String?,
        darkMode: Boolean,
    ): String {
        return customMapStyleUrl?.takeIf { it.isNotBlank() }
            ?: if (darkMode) darkStyleUrl else lightStyleUrl
    }
}
