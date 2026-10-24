/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import kotlinx.serialization.json.*

@Inject
internal class DiscoveryArticlesRepository(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    private suspend fun read(query: JsonObject): JsonObject = access.withSession { session ->
        val request = query.toString().encodeToByteArray()
        try {
            val response = transport.queryArticles(request, mapOf("Authorization" to "Bearer ${session.secret.value}"))
            try { Json.parseToJsonElement(response.decodeToString()).jsonObject } finally { response.fill(0) }
        } finally { request.fill(0) }
    }

    suspend fun feed(keyword: String, sort: ArticleSort, page: Int, collection: ArticleCollection? = null): DiscoveryArticlePage {
        val value = read(buildJsonObject {
            put("kind", "feed"); put("keyword", keyword); put("sort", sort.wireValue); put("page", page); put("page_size", 10)
            collection?.let { put(it.kind.wireValue + "_id", it.id) }
        })
        require(value.getValue("page").jsonPrimitive.int == page)
        val total = value.getValue("total").jsonPrimitive.int.also { require(it >= 0) }
        return DiscoveryArticlePage(value.getValue("items").jsonArray.map { parseArticle(it.jsonObject) }, total)
    }

    suspend fun personal(kind: DiscoveryPersonalKind, page: Int): DiscoveryPersonalPage {
        val value = read(buildJsonObject { put("kind", kind.wire); put("page", page); put("page_size", kind.pageSize) })
        require(value.count("page") == page && value.count("page_size") == kind.pageSize)
        val total = value.count("total")
        val rows = value.getValue("items").jsonArray.also { require(it.size <= kind.pageSize) }
        val items = rows.map { row ->
            val item = row.jsonObject
            when (kind) {
                DiscoveryPersonalKind.REPLIES -> {
                    val comment = parseComment(item).also { require(it.parentId != null) }
                    DiscoveryPersonalItem.Reply(comment, item.text("article_title"))
                }
                DiscoveryPersonalKind.FAVORITES -> {
                    require(item.getValue("user_state").jsonObject.flag("favorited"))
                    DiscoveryPersonalItem.Article(parseArticle(item))
                }
                DiscoveryPersonalKind.HISTORY -> {
                    val views = item.count("view_count")
                    DiscoveryPersonalItem.Article(parseArticle(item).copy(views = views))
                }
                DiscoveryPersonalKind.FOLLOWS -> {
                    require(item.text("status") == "active" && item.flag("followed"))
                    val name = item.text("nickname").also { require(it.isNotBlank()) }
                    DiscoveryPersonalItem.Author(item.id("author_id"), ArticleCollectionHeader(name, item.text("bio"),
                        item.count("article_count"), item.count("follower_count"), followed = true,
                        followEnabled = item.flag("follow_enabled")), item.text("author_type"))
                }
            }
        }
        require(items.map { it.id }.toSet().size == items.size)
        return DiscoveryPersonalPage(items, total)
    }

    suspend fun comments(articleId: Long, page: Int): DiscoveryCommentsPage {
        val value = read(buildJsonObject { put("kind", "comments"); put("article_id", articleId); put("page", page); put("page_size", 20) })
        require(value.count("page") == page && value.count("page_size") == 20)
        val items = value.getValue("items").jsonArray.also { require(it.size <= 20) }.map {
            parseComment(it.jsonObject).also { comment -> require(comment.articleId == articleId) }
        }
        require(items.map { it.id }.toSet().size == items.size)
        return DiscoveryCommentsPage(items, value.count("total"))
    }

    private fun parseComment(value: JsonObject): DiscoveryArticleComment {
        require(value.text("status") == "visible")
        val id = value.id("comment_id")
        val parent = value["parent_id"]?.takeUnless { it is JsonNull }?.let {
            it.jsonPrimitive.let { number -> require(!number.isString && number.long > 0 && number.long != id); number.long }
        }
        return DiscoveryArticleComment(id, value.id("article_id"), value.text("user_name"), value.text("content"),
            value.text("create_at").replace('T', ' ').take(16), parent, value.text("parent_content"), value.text("parent_user_name"),
            value.count("like_count"), value.flag("is_pinned"), value.flag("is_author_reply"))
    }

    suspend fun collection(collection: ArticleCollection): ArticleCollectionHeader {
        val kind = collection.kind.wireValue
        val value = read(buildJsonObject { put("kind", kind); put(kind + "_id", collection.id) }).getValue(kind).jsonObject
        require(value.id(kind + "_id") == collection.id && value.text("status") == "active")
        val title = value.text(if (collection.kind == ArticleCollectionKind.TAG) "name" else "nickname").also { require(it.isNotBlank()) }
        return ArticleCollectionHeader(title, value.text(if (collection.kind == ArticleCollectionKind.TAG) "alias" else "bio"),
            value.count("article_count"),
            followerCount = if (collection.kind == ArticleCollectionKind.AUTHOR) value.count("follower_count") else null,
            followed = collection.kind == ArticleCollectionKind.AUTHOR && value.flag("followed"),
            followEnabled = collection.kind == ArticleCollectionKind.AUTHOR && value.flag("follow_enabled"))
    }

    suspend fun words(): List<String> = read(buildJsonObject { put("kind", "words") })
        .getValue("items").jsonArray.map { it.jsonObject.text("keyword") }.filter(String::isNotBlank).distinct()

    suspend fun detail(id: Long): DiscoveryArticle = read(buildJsonObject { put("kind", "detail"); put("article_id", id) })
        .getValue("article").jsonObject.let { value ->
            // Interactive controls must not interpret missing or malformed actor state as false.
            val actor = value.getValue("user_state").jsonObject
            for (name in listOf("liked", "favorited")) {
                require(!actor.getValue(name).jsonPrimitive.isString && actor.getValue(name).jsonPrimitive.booleanOrNull != null)
            }
            for (name in listOf("like_count", "favorite_count")) {
                require(!value.getValue(name).jsonPrimitive.isString && value.getValue(name).jsonPrimitive.int >= 0)
            }
            parseArticle(value)
        }.also { require(it.id == id) }

    private fun parseArticle(value: JsonObject) = DiscoveryArticle(
        id = value.getValue("article_id").jsonPrimitive.long.also { require(it > 0) },
        typeId = value.text("type_id"),
        title = value.text("title"), summary = value.text("summary"), content = value.text("content_text"),
        author = (value["author"] as? JsonObject)?.text("nickname").orEmpty(),
        authorId = (value["author"] as? JsonObject)?.id("author_id"),
        tags = (value["tags"] as? JsonArray).orEmpty().map { tag ->
            ArticleTag(tag.jsonObject.id("tag_id"), tag.jsonObject.text("name"))
        }.distinctBy { it.id },
        likes = value["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
        favorites = value["favorite_count"]?.jsonPrimitive?.intOrNull ?: 0,
        liked = (value["user_state"] as? JsonObject)?.get("liked")?.jsonPrimitive?.booleanOrNull ?: false,
        favorited = (value["user_state"] as? JsonObject)?.get("favorited")?.jsonPrimitive?.booleanOrNull ?: false,
        isVideo = value.text("cover_type") == "video",
        commentEnabled = if ("comment_enabled" in value) value.flag("comment_enabled") else false,
    )

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.id(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString && it.long > 0); it.long }
    private fun JsonObject.count(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString && it.int >= 0); it.int }
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
}
