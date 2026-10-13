/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline.item.event

import io.element.android.libraries.matrix.api.media.MediaEventOrigin
import io.element.android.libraries.matrix.api.media.MediaSource

/** Bind after protected projections are restored, so restored sources cannot lose event identity. */
fun EventContent.withMediaEventOrigin(roomId: String, eventId: String?): EventContent {
    val origin = MediaEventOrigin(roomId, eventId)
    fun MediaSource.bind() = if (eventOrigin == origin) this else copy(eventOrigin = origin)
    return when (this) {
        is MessageContent -> {
            val boundType = when (val attachment = type) {
                is ImageMessageType -> attachment.copy(source = attachment.source.bind(),
                    info = attachment.info?.copy(thumbnailSource = attachment.info.thumbnailSource?.bind()))
                is StickerMessageType -> attachment.copy(source = attachment.source.bind(),
                    info = attachment.info?.copy(thumbnailSource = attachment.info.thumbnailSource?.bind()))
                is VideoMessageType -> attachment.copy(source = attachment.source.bind(),
                    info = attachment.info?.copy(thumbnailSource = attachment.info.thumbnailSource?.bind()))
                is FileMessageType -> attachment.copy(source = attachment.source.bind(),
                    info = attachment.info?.copy(thumbnailSource = attachment.info.thumbnailSource?.bind()))
                is AudioMessageType -> attachment.copy(source = attachment.source.bind())
                is VoiceMessageType -> attachment.copy(source = attachment.source.bind())
                else -> attachment
            }
            val boundReply = (inReplyTo as? InReplyTo.Ready)?.let { reply ->
                val boundContent = reply.content.withMediaEventOrigin(roomId, reply.eventId.value)
                if (boundContent === reply.content) reply else reply.copy(content = boundContent)
            } ?: inReplyTo
            // Text has no media source. Preserve its immutable identity so a media decoration
            // pass cannot turn every historical text row into a new UI update.
            if (boundType == type && boundReply === inReplyTo) this else copy(type = boundType, inReplyTo = boundReply)
        }
        is StickerContent -> {
            val boundSource = source.bind()
            val boundThumbnail = info.thumbnailSource?.bind()
            if (boundSource === source && boundThumbnail === info.thumbnailSource) this
            else copy(source = boundSource, info = info.copy(thumbnailSource = boundThumbnail))
        }
        else -> this
    }
}
