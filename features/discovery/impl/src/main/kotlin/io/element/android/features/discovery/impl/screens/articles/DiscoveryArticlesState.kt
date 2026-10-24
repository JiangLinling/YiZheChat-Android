/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

enum class ArticleSort(val wireValue: String) { RECOMMEND("recommend"), LATEST("latest"), HOT("hot"), VIEWS("views") }
enum class ArticleCollectionKind(val wireValue: String) { TAG("tag"), AUTHOR("author") }
data class ArticleCollection(val kind: ArticleCollectionKind, val id: Long) {
    init { require(id > 0) }
}
data class ArticleTag(val id: Long, val name: String)
data class ArticleCollectionHeader(val title: String, val description: String, val articleCount: Int,
    val followerCount: Int? = null, val followed: Boolean = false, val followEnabled: Boolean = false)
data class DiscoveryArticle(val id: Long, val title: String, val summary: String, val content: String = "",
    val author: String = "", val likes: Int = 0, val isVideo: Boolean = false,
    val favorites: Int = 0, val liked: Boolean = false, val favorited: Boolean = false,
    val authorId: Long? = null, val tags: List<ArticleTag> = emptyList(), val views: Int = 0, val commentEnabled: Boolean = false, val typeId: String = "")
data class DiscoveryArticlePage(val items: List<DiscoveryArticle>, val total: Int)
data class DiscoveryArticlesState(
    val searchMode: Boolean = false, val draft: String = "", val keyword: String = "", val sort: ArticleSort = ArticleSort.RECOMMEND,
    val items: List<DiscoveryArticle> = emptyList(), val total: Int = 0, val loading: Boolean = true, val failed: Boolean = false,
    val words: List<String> = emptyList(), val history: List<String> = emptyList(), val eventSink: (DiscoveryArticlesEvent) -> Unit,
    val collection: ArticleCollection? = null, val header: ArticleCollectionHeader? = null, val missing: Boolean = false,
    val interactionBusy: Boolean = false, val interactionPending: Boolean = false, val interactionFailed: Boolean = false,
    val interactionRejected: Boolean = false,
)
sealed interface DiscoveryArticlesEvent {
    data class Input(val value: String) : DiscoveryArticlesEvent
    data class Search(val word: String? = null) : DiscoveryArticlesEvent
    data class Sort(val value: ArticleSort) : DiscoveryArticlesEvent
    data object Reload : DiscoveryArticlesEvent
    data object More : DiscoveryArticlesEvent
    data object Clear : DiscoveryArticlesEvent
    data object Follow : DiscoveryArticlesEvent
    data object ConfirmInteraction : DiscoveryArticlesEvent
}
data class DiscoveryArticleDetailState(val loading: Boolean = true, val failed: Boolean = false, val missing: Boolean = false,
    val article: DiscoveryArticle? = null, val reload: () -> Unit,
    val interactionBusy: Boolean = false, val interactionPending: Boolean = false, val interactionFailed: Boolean = false,
    val interactionRejected: Boolean = false, val eventSink: (DiscoveryArticleDetailEvent) -> Unit = {},
    val readingPending: Boolean = false, val retryReading: () -> Unit = {},
    val commentText: String = "", val commentReply: DiscoveryCommentReply? = null,
    val commentPending: Boolean = false, val commentSent: Boolean = false, val contentRevision: Long = 0)

data class DiscoveryCommentReply(val id: Long, val author: String = "", val content: String = "")

sealed interface DiscoveryArticleDetailEvent {
    data object Like : DiscoveryArticleDetailEvent
    data object Favorite : DiscoveryArticleDetailEvent
    data object Recover : DiscoveryArticleDetailEvent
    data class CommentInput(val value: String) : DiscoveryArticleDetailEvent
    data class Reply(val comment: DiscoveryArticleComment) : DiscoveryArticleDetailEvent
    data object CancelReply : DiscoveryArticleDetailEvent
    data object SendComment : DiscoveryArticleDetailEvent
}
