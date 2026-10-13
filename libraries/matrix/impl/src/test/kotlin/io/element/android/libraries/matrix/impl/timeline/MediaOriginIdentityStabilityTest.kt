/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaEventOrigin
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.withMediaEventOrigin
import org.junit.Test

class MediaOriginIdentityStabilityTest {
    private val text = MessageContent("text", null, false, null, TextMessageType("text", null))
    private val audio = text.copy(type = AudioMessageType("audio", null, null, MediaSource("mxc://server/audio"), null))
    private val room = "!room:server"

    @Test fun `plain text keeps its source object`() {
        assertThat(text.withMediaEventOrigin(room, "\$event")).isSameInstanceAs(text)
    }

    @Test fun `text reply with no media keeps its source object`() {
        val reply = text.copy(inReplyTo = InReplyTo.Ready(EventId("\$target"), text, UserId("@user:server"), ProfileDetails.Unavailable))
        assertThat(reply.withMediaEventOrigin(room, "\$event")).isSameInstanceAs(reply)
    }

    @Test fun `already bound media reuses only the exact current origin`() {
        val local = audio.withMediaEventOrigin(room, null)
        assertThat(local.withMediaEventOrigin(room, null)).isSameInstanceAs(local)
        val remote = local.withMediaEventOrigin(room, "\$event") as MessageContent
        assertThat(remote).isNotSameInstanceAs(local)
        assertThat((remote.type as AudioMessageType).source.eventOrigin).isEqualTo(MediaEventOrigin(room, "\$event"))
        assertThat(remote.withMediaEventOrigin(room, "\$event")).isSameInstanceAs(remote)
        val moved = remote.withMediaEventOrigin("!other:server", "\$event") as MessageContent
        assertThat((moved.type as AudioMessageType).source.eventOrigin).isEqualTo(MediaEventOrigin("!other:server", "\$event"))
    }

    @Test fun `reply media always uses target identity and rebinding stays idempotent`() {
        val reply = text.copy(inReplyTo = InReplyTo.Ready(EventId("\$target"), audio, UserId("@user:server"), ProfileDetails.Unavailable))
        val bound = reply.withMediaEventOrigin(room, "\$enclosing") as MessageContent
        val target = (bound.inReplyTo as InReplyTo.Ready).content as MessageContent
        assertThat((target.type as AudioMessageType).source.eventOrigin).isEqualTo(MediaEventOrigin(room, "\$target"))
        assertThat(bound.withMediaEventOrigin(room, "\$enclosing")).isSameInstanceAs(bound)
    }
}
