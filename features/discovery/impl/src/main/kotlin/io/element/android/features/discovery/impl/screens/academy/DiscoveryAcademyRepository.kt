/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.academy

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticlesRepository
import io.element.android.features.discovery.impl.screens.home.DiscoveryHomeRepository
import io.element.android.features.discovery.impl.session.DiscoveryFailure

@Inject
internal class DiscoveryAcademyRepository(private val home: DiscoveryHomeRepository, private val articles: DiscoveryArticlesRepository) {
    suspend fun read(articleId: Long): DiscoveryAcademyContent {
        require(articleId > 0)
        val entries = home.load().academy.filter { it.kind == "realm_articles" }
        val article = articles.detail(articleId)
        // Legacy configured entries may point to any published article; retain the original public-detail rule.
        if (article.id != articleId) throw DiscoveryFailure(404)
        val index = entries.indexOfFirst { it.targetArticleId == articleId }
        return DiscoveryAcademyContent(article, if (index >= 0) entries.getOrNull(index + 1) else null)
    }
}
