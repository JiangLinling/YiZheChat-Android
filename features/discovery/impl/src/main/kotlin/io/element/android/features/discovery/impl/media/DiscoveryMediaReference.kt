/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import kotlinx.serialization.json.*

internal enum class DiscoveryMediaBinding(val wire: String) {
    ARTICLE_COVER("article_cover"), ARTICLE_MEDIA("article_media"), ARTICLE_AUTHOR_AVATAR("article_author_avatar"),
    COURSE_COVER("course_cover"), COURSE_MEDIA("course_media"),
}
internal enum class DiscoveryMediaAccess { PUBLIC_LEGACY, PRIVATE }
internal enum class DiscoveryMediaKind(val wire: String) { IMAGE("image"), VIDEO("video") }

/** Metadata is a locator and content version, never permission to release plaintext. No URL or local path is accepted. */
internal data class DiscoveryMediaReference(
    val assetId: String,
    val binding: DiscoveryMediaBinding,
    val objectId: String,
    val access: DiscoveryMediaAccess,
    val kind: DiscoveryMediaKind,
    val mimeType: String,
    val byteLength: Long,
    val sha256: String,
) {
    init {
        require(listOf(assetId, objectId).all { it.matches(Regex("[1-9][0-9]{0,19}")) })
        require(byteLength > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        require(mimeType.length <= 120 && mimeType.matches(Regex("${kind.wire}/[A-Za-z0-9.+-]+")))
    }
    override fun toString() = "DiscoveryMediaReference(binding=$binding, kind=$kind, details=<redacted>)"

    fun range(start: Long, length: Int): DiscoveryMediaRange = DiscoveryMediaRange(this, start, length)
}

internal data class DiscoveryMediaRange(val reference: DiscoveryMediaReference, val start: Long, val length: Int) {
    init {
        require(start >= 0 && length in 1..262144 && start < reference.byteLength)
        require(length.toLong() <= reference.byteLength - start)
    }
    val endInclusive get() = start + length - 1
    fun request(): ByteArray = buildJsonObject {
        put("schema_version", 1); put("asset_id", reference.assetId)
        put("binding", buildJsonObject { put("type", reference.binding.wire); put("id", reference.objectId) })
        put("sha256", reference.sha256); put("start", start); put("length", length)
    }.toString().encodeToByteArray()
}
