/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.SensitiveAssetOwner
import org.junit.Test

class DefaultSensitiveAssetCatalogTest {
    private val catalog = DefaultSensitiveAssetCatalog()

    @Test
    fun `catalog contains every documented sensitive asset id exactly once`() {
        val ids = catalog.all().map { it.id }

        assertThat(ids).containsExactlyElementsIn(documentedIds())
        assertThat(ids.distinct()).hasSize(ids.size)
    }

    @Test
    fun `every sensitive asset is assigned to an accountable owner`() {
        val assetsByOwner = catalog.all().groupBy { it.owner }

        assertThat(assetsByOwner.keys).containsExactlyElementsIn(SensitiveAssetOwner.entries)
        assertThat(assetsByOwner.values.all { it.isNotEmpty() }).isTrue()
    }

    private fun documentedIds(): List<String> = buildList {
        addAll((1..18).map { "L-${it.toString().padStart(3, '0')}" })
        addAll((1..11).map { "M-${it.toString().padStart(3, '0')}" })
        addAll((1..12).map { "S-${it.toString().padStart(3, '0')}" })
    }
}
