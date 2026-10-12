/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.*
import org.junit.Test

class MediaEventOriginTest {
    private val source = MediaSource("mxc://example.org/shared")
    private val room = "!room:example.org"
    private val event = "\$event"
    private fun message(type: MessageType, reply: InReplyTo? = null) = MessageContent("media", reply, false, null, type)

    @Test fun `all attachments and thumbnails acquire the enclosing event identity`() {
        val imageInfo = ImageInfo(null, null, null, null, null, source, null)
        val types = listOf(
            ImageMessageType("image", null, null, source, imageInfo),
            StickerMessageType("sticker", null, null, source, imageInfo),
            FileMessageType("file", null, null, source, FileInfo(null, null, null, source)),
            AudioMessageType("audio", null, null, source, null),
            VoiceMessageType("voice", null, null, source, null, null),
            VideoMessageType("video", null, null, source, null),
        )
        types.forEach { type ->
            val mapped = (message(type).withMediaEventOrigin(room, event) as MessageContent).type
            val bound = when (mapped) {
                is ImageMessageType -> mapped.source.also { assertThat(mapped.info?.thumbnailSource?.eventOrigin).isEqualTo(MediaEventOrigin(room, event)) }
                is StickerMessageType -> mapped.source.also { assertThat(mapped.info?.thumbnailSource?.eventOrigin).isEqualTo(MediaEventOrigin(room, event)) }
                is FileMessageType -> mapped.source.also { assertThat(mapped.info?.thumbnailSource?.eventOrigin).isEqualTo(MediaEventOrigin(room, event)) }
                is AudioMessageType -> mapped.source
                is VoiceMessageType -> mapped.source
                is VideoMessageType -> mapped.source
                else -> error("Attachment lost")
            }
            assertThat(bound.eventOrigin).isEqualTo(MediaEventOrigin(room, event))
            assertThat(bound.safeUrl).isEqualTo(source.safeUrl)
        }
        val sticker = StickerContent("sticker", null, imageInfo, source, null).withMediaEventOrigin(room, event) as StickerContent
        assertThat(sticker.source.eventOrigin).isEqualTo(MediaEventOrigin(room, event))
        assertThat(sticker.info.thumbnailSource?.eventOrigin).isEqualTo(MediaEventOrigin(room, event))
    }

    @Test fun `reply preview binds its own target and never the enclosing message event`() {
        val reply = InReplyTo.Ready(EventId("\$target"), message(AudioMessageType("audio", null, null, source, null)),
            UserId("@alice:example.org"), ProfileDetails.Unavailable)
        val bound = message(TextMessageType("reply", null), reply).withMediaEventOrigin(room, event) as MessageContent
        val target = (bound.inReplyTo as InReplyTo.Ready).content as MessageContent
        assertThat((target.type as AudioMessageType).source.eventOrigin).isEqualTo(MediaEventOrigin(room, "\$target"))
    }

    @Test fun `local echo keeps event unknown and canonical sync changes cache identity`() {
        val original = message(AudioMessageType("audio", null, null, source, null))
        val echo = (original.withMediaEventOrigin(room, null) as MessageContent).type as AudioMessageType
        val remote = (original.withMediaEventOrigin(room, event) as MessageContent).type as AudioMessageType
        assertThat(echo.source.eventOrigin?.eventId).isNull()
        assertThat(echo.source).isNotEqualTo(remote.source)
        assertThat(remote.source).isNotEqualTo(remote.source.copy(eventOrigin = MediaEventOrigin(room, "\$other")))
        assertThat(remote.source.toString()).doesNotContain(room)
        assertThat(remote.source.eventOrigin.toString()).doesNotContain(event)
    }
}
