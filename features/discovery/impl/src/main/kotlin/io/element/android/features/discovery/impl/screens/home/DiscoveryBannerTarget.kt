/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import java.net.URI

internal sealed interface DiscoveryBannerTarget {
    data object Home : DiscoveryBannerTarget
    data object Courses : DiscoveryBannerTarget
    data object Services : DiscoveryBannerTarget
    data object Profile : DiscoveryBannerTarget
    data class Course(val id: Long, val sessionId: Long?) : DiscoveryBannerTarget
    data class Articles(val search: Boolean) : DiscoveryBannerTarget
    data class Article(val id: Long) : DiscoveryBannerTarget
    data class Academy(val id: Long) : DiscoveryBannerTarget
    data class Personal(val kind: DiscoveryPersonalKind) : DiscoveryBannerTarget
}

/** Convert only supported mini-program paths to native destinations. No web intents, arbitrary URLs or fallback redirects. */
internal fun DiscoveryHomeCard.bannerTarget(): DiscoveryBannerTarget? = when (linkType) {
    "course_list" -> DiscoveryBannerTarget.Courses
    "course_detail" -> positiveId(linkValue)?.let { DiscoveryBannerTarget.Course(it, null) }
    "path", "custom_path", "courses" -> nativePath(linkValue)
    else -> null
}

private fun positiveId(value: String?): Long? = value?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
    ?.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }

private fun nativePath(value: String): DiscoveryBannerTarget? {
    if (value.length > 500) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null || uri.rawPath != uri.path) return null
    val query = linkedMapOf<String, String>()
    uri.rawQuery?.let { raw ->
        if (raw.isEmpty()) return null
        for (part in raw.split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || pair[0] in query || pair[1].isEmpty()) return null
            query[pair[0]] = pair[1]
        }
    }
    fun static(target: DiscoveryBannerTarget): DiscoveryBannerTarget? = target.takeIf { query.isEmpty() }
    fun article(make: (Long) -> DiscoveryBannerTarget): DiscoveryBannerTarget? =
        if (query.keys == setOf("id")) positiveId(query["id"])?.let(make) else null
    return when (uri.rawPath) {
        "/pages/index/index/index" -> static(DiscoveryBannerTarget.Home)
        "/pages/courses/index/index" -> static(DiscoveryBannerTarget.Courses)
        "/pages/shop/index/index" -> static(DiscoveryBannerTarget.Services)
        "/pages/profile/index/index" -> static(DiscoveryBannerTarget.Profile)
        "/pages/articles/feed/feed" -> static(DiscoveryBannerTarget.Articles(false))
        "/pages/articles/search/search" -> static(DiscoveryBannerTarget.Articles(true))
        "/pages/articles/favorites/favorites" -> static(DiscoveryBannerTarget.Personal(DiscoveryPersonalKind.FAVORITES))
        "/pages/articles/follows/follows" -> static(DiscoveryBannerTarget.Personal(DiscoveryPersonalKind.FOLLOWS))
        "/pages/articles/history/history" -> static(DiscoveryBannerTarget.Personal(DiscoveryPersonalKind.HISTORY))
        "/pages/articles/replies/replies" -> static(DiscoveryBannerTarget.Personal(DiscoveryPersonalKind.REPLIES))
        "/pages/articles/detail/detail" -> article { DiscoveryBannerTarget.Article(it) }
        "/pages/academy/index/index" -> if (query.keys == setOf("article_id")) {
            positiveId(query["article_id"])?.let { DiscoveryBannerTarget.Academy(it) }
        } else null
        "/pages/courses/detail/detail" -> {
            if (query.keys !in setOf(setOf("id"), setOf("id", "session_id"))) return null
            val courseId = positiveId(query["id"]) ?: return null
            val sessionId = if ("session_id" in query) positiveId(query["session_id"]) ?: return null else null
            DiscoveryBannerTarget.Course(courseId, sessionId)
        }
        else -> null
    }
}
