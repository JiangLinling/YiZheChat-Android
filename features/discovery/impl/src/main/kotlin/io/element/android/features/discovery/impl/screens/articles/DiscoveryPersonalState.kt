/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

enum class DiscoveryPersonalKind(val wire: String, val pageSize: Int) { FAVORITES("favorites", 10), FOLLOWS("follows", 20), HISTORY("history", 10), REPLIES("replies", 20) }
sealed interface DiscoveryPersonalItem {
    val id: Long
    data class Reply(val comment: DiscoveryArticleComment, val articleTitle: String) : DiscoveryPersonalItem { override val id = comment.id }
    data class Article(val value: DiscoveryArticle) : DiscoveryPersonalItem { override val id = value.id }
    data class Author(override val id: Long, val header: ArticleCollectionHeader, val type: String) : DiscoveryPersonalItem
}
data class DiscoveryPersonalPage(val items: List<DiscoveryPersonalItem>, val total: Int)
data class DiscoveryPersonalState(val kind: DiscoveryPersonalKind, val items: List<DiscoveryPersonalItem> = emptyList(),
    val total: Int = 0, val page: Int = 1, val loading: Boolean = true, val failed: Boolean = false,
    val eventSink: (DiscoveryPersonalEvent) -> Unit)
sealed interface DiscoveryPersonalEvent {
    data object Refresh : DiscoveryPersonalEvent
    data object Retry : DiscoveryPersonalEvent
    data object More : DiscoveryPersonalEvent
}
