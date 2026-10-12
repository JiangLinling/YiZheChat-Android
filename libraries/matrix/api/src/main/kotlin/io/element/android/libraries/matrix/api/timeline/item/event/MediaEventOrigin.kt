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
    fun MediaSource.bind() = copy(eventOrigin = origin)
    return when (this) {
        is MessageContent -> copy(
            type = when (val attachment = type) {
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
            },
            inReplyTo = (inReplyTo as? InReplyTo.Ready)?.let { reply ->
                reply.copy(content = reply.content.withMediaEventOrigin(roomId, reply.eventId.value))
            } ?: inReplyTo,
        )
        is StickerContent -> copy(source = source.bind(), info = info.copy(thumbnailSource = info.thumbnailSource?.bind()))
        else -> this
    }
}
