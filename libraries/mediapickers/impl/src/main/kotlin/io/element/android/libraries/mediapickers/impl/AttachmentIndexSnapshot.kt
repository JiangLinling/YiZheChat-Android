/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal val EMPTY_ATTACHMENT_INDEX = AttachmentIndex(emptyList(), emptyList(), 0, false)

/** An encrypted metadata projection. Neither the cache nor its timestamps grant file access. */
internal data class AttachmentIndexSnapshot(
    val disk: AttachmentIndex = EMPTY_ATTACHMENT_INDEX,
    val media: AttachmentIndex = EMPTY_ATTACHMENT_INDEX,
    val added: AttachmentIndex = EMPTY_ATTACHMENT_INDEX,
    val directories: Map<String, Long> = emptyMap(),
    val sourcesKey: String = "",
    val mediaStamp: String = "",
    val baselineComplete: Boolean = false,
    val sharedAccess: Boolean = false,
) {
    @Volatile private var cachedProjection: AttachmentIndex? = null

    fun projection(): AttachmentIndex = cachedProjection ?: mergeAttachmentIndexes(mergeAttachmentIndexes(disk, media), added)
        .also { cachedProjection = it }

    fun encode(): ByteArray = JSONObject()
        .put("schema", 1)
        .put("disk", disk.toJson()).put("media", media.toJson()).put("added", added.toJson())
        .put("directories", JSONObject(directories))
        .put("sources", sourcesKey).put("media_stamp", mediaStamp)
        .put("complete", baselineComplete).put("shared", sharedAccess)
        .toString().encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray): AttachmentIndexSnapshot {
            val json = JSONObject(bytes.decodeToString())
            require(json.getInt("schema") == 1)
            val directories = json.getJSONObject("directories")
            require(directories.length() <= 50_000)
            return AttachmentIndexSnapshot(
                disk = json.getJSONObject("disk").toIndex(),
                media = json.getJSONObject("media").toIndex(),
                added = json.getJSONObject("added").toIndex(),
                directories = directories.keys().asSequence().associateWith { directories.getLong(it) },
                sourcesKey = json.getString("sources"),
                mediaStamp = json.getString("media_stamp"),
                baselineComplete = json.getBoolean("complete"),
                sharedAccess = json.getBoolean("shared"),
            )
        }
    }
}

private fun AttachmentIndex.toJson() = JSONObject()
    .put("files", JSONArray().apply {
        files.forEach { file ->
            put(JSONArray(listOf(
                file.uri.toString(),
                file.name,
                file.mime,
                file.size ?: JSONObject.NULL,
                file.modified,
                file.addedAt,
                file.source,
                file.root
            )))
        }
    })
    .put("folders", JSONArray().apply { folders.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    .put("failed", failedSources).put("truncated", truncated)

private fun JSONObject.toIndex(): AttachmentIndex {
    val files = getJSONArray("files")
    val folders = getJSONArray("folders")
    require(files.length() <= 60_000 && folders.length() <= AttachmentSourceStore.MAX_SOURCES)
    return AttachmentIndex(
        List(files.length()) { i ->
            val row = files.getJSONArray(i)
            AttachmentFile(
                Uri.parse(row.getString(0)),
                row.getString(1),
                row.getString(2),
                if (row.isNull(3)) null else row.getLong(3),
                row.getLong(4),
                row.getLong(5),
                row.getString(6),
                row.getString(7)
            )
        },
        List(folders.length()) { i -> folders.getJSONArray(i).let { it.getString(0) to it.getString(1) } },
        getInt("failed"),
        getBoolean("truncated"),
    )
}

internal fun attachmentSourcesKey(sources: List<AttachmentSource>): String = sha256(
    sources.joinToString("\n") { "${it.uri}|${it.tree}|${it.addedAt}|${it.managed}" }.encodeToByteArray()
)
