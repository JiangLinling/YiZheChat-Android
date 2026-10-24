/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

data class DiscoveryArticleComment(
    val id: Long, val articleId: Long, val author: String, val content: String, val createdAt: String,
    val parentId: Long? = null, val parentContent: String = "", val parentAuthor: String = "",
    val likes: Int = 0, val pinned: Boolean = false, val authorReply: Boolean = false,
)
data class DiscoveryCommentsPage(val items: List<DiscoveryArticleComment>, val total: Int)
data class DiscoveryCommentsState(val articleId: Long, val items: List<DiscoveryArticleComment> = emptyList(),
    val total: Int = 0, val page: Int = 1, val loading: Boolean = true, val failed: Boolean = false,
    val missing: Boolean = false, val eventSink: (DiscoveryPersonalEvent) -> Unit)
