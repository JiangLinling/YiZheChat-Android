/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.academy

import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticle
import io.element.android.features.discovery.impl.screens.home.DiscoveryHomeCard

data class DiscoveryAcademyContent(val article: DiscoveryArticle, val next: DiscoveryHomeCard?)
data class DiscoveryAcademyState(val loading: Boolean = true, val failed: Boolean = false, val missing: Boolean = false,
    val content: DiscoveryAcademyContent? = null, val reload: () -> Unit)
