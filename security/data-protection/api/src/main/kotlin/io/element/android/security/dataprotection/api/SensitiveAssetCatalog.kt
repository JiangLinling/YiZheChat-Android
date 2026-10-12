/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

interface SensitiveAssetCatalog {
    fun all(): List<SensitiveAssetDescriptor>

    fun find(id: String): SensitiveAssetDescriptor? = all().firstOrNull { it.id == id }
}
