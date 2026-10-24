/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import kotlinx.serialization.json.*

@Inject
internal class DiscoveryHomeRepository(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    suspend fun load(): DiscoveryHomeContent = access.withSession { session ->
        val bytes = transport.getHome(mapOf("Authorization" to "Bearer ${session.secret.value}"))
        try {
            parse(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
        } finally { bytes.fill(0) }
    }

    private fun parse(value: JsonObject): DiscoveryHomeContent {
        fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
        fun cards(key: String, id: String, subtitle: String) = value.getValue(key).jsonArray.mapIndexed { index, item ->
            val row = item.jsonObject
            val target = if (key in setOf("realm_articles", "features")) row["article_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
                require(!it.isString && it.long > 0); it.long
            } else null
            if (key == "realm_articles") require(target != null && row.text("type_id") == "realm")
            val courseId = if (key == "open_sessions") row["course_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
                require(!it.isString && it.long in 1..Int.MAX_VALUE.toLong()); it.long
            } else null
            val sessionId = if (key == "open_sessions") row["session_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
                require(!it.isString && it.long in 1..Int.MAX_VALUE.toLong()); it.long
            } else null
            DiscoveryHomeCard(row.text(id).ifBlank { "item-$index" }, row.text("title"), row.text(subtitle), key, target,
                courseId, sessionId, if (key == "banners") row.text("link_type") else "",
                if (key == "banners") row.text("link_value") else "")
        }
        return DiscoveryHomeContent(
            cards("banners", "content_id", "subtitle"),
            cards("realm_articles", "article_id", "description").also { rows ->
                require(rows.size <= 4 && rows.map { it.targetArticleId }.toSet().size == rows.size)
            }.ifEmpty { cards("features", "content_id", "description").take(4) },
            cards("open_sessions", "session_id", "venue"),
            cards("news", "article_id", "location"),
        )
    }
}
