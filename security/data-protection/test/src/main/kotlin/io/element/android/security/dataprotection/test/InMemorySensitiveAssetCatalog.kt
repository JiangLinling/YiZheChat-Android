/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.SensitiveAssetCatalog
import io.element.android.security.dataprotection.api.SensitiveAssetDescriptor

class InMemorySensitiveAssetCatalog(
    private val assets: List<SensitiveAssetDescriptor> = emptyList(),
) : SensitiveAssetCatalog {
    override fun all(): List<SensitiveAssetDescriptor> = assets
}
