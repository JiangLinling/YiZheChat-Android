/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoom
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.ProfileDetails
import uniffi.matrix_sdk_ui.LatestEventValueLocalState

class RoomSummaryFactoryIdentityTest {
    @Test
    fun `confirmed own summary binds exact remote carrier despite enqueue timestamp`() = runTest {
        for (localState in listOf(null, LatestEventValueLocalState.HAS_BEEN_SENT)) {
            assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedSender = A_SESSION_ID.value,
                cachedTimestamp = 999uL, summarySender = A_SESSION_ID.value, summaryOwn = true, localState = localState))
                .isEqualTo("\$confirmed-own")
        }
    }

    @Test
    fun `unconfirmed or failed own send never borrows a persisted remote carrier`() = runTest {
        for (state in listOf(LatestEventValueLocalState.IS_SENDING, LatestEventValueLocalState.CANNOT_BE_SENT)) {
            assertThat(createSummary("\$observed-own", cachedIdentity = "\$confirmed-own", cachedSender = A_SESSION_ID.value,
                summarySender = A_SESSION_ID.value, summaryOwn = true, localState = state)).isNull()
        }
    }

    @Test
    fun `own timestamp exception requires exact authenticated author ciphertext and remote event`() = runTest {
        assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedTimestamp = 999uL,
            summaryOwn = true)).isNull()
        assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedTimestamp = 999uL,
            summarySender = A_SESSION_ID.value, summaryOwn = true)).isNull()
        assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedSender = A_SESSION_ID.value, cachedTimestamp = 999uL,
            summarySender = A_SESSION_ID.value, summaryOwn = true, cachedBody = "yizhe-secure-v1:other")).isNull()
        assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedSender = A_SESSION_ID.value, cachedTimestamp = 999uL,
            summarySender = A_SESSION_ID.value, summaryOwn = true, cachedRemote = false)).isNull()
        assertThat(createSummary(null, cachedIdentity = "\$confirmed-own", cachedSender = A_SESSION_ID.value, cachedTimestamp = 999uL,
            summarySender = A_SESSION_ID.value, summaryOwn = true, cachedOwn = false)).isNull()
    }
    @Test
    fun `closed conversation preview resolves exact persisted Matrix carrier`() = runTest {
        assertThat(createSummary(null, cachedIdentity = "\$cached-carrier")).isEqualTo("\$cached-carrier")
    }

    @Test
    fun `persisted carrier with different author or timestamp is never authorized`() = runTest {
        assertThat(createSummary(null, cachedIdentity = "\$cached-carrier", cachedSender = "@other:next")).isNull()
        assertThat(createSummary(null, cachedIdentity = "\$cached-carrier", cachedTimestamp = 999uL)).isNull()
    }
    @Test
    fun `room summary sends observed canonical identity through existing protected authorization`() = runTest {
        assertThat(createSummary("\$matrix-carrier")).isEqualTo("\$matrix-carrier")
    }

    @Test
    fun `unobserved protected summary waits without inventing a receive identity`() = runTest {
        assertThat(createSummary(null)).isNull()
        assertThat(createSummary("transaction-id")).isNull()
        assertThat(createSummary("room-summary:1000:sender")).isNull()
    }

    @Test
    fun `attachment caption summary binds the canonical carrier and authenticated Matrix author`() = runTest {
        assertThat(createSummary("\$matrix-caption", attachment = true)).isEqualTo("\$matrix-caption")
    }

    @Test
    fun `attachment caption without a canonical carrier does not open a read owner`() = runTest {
        assertThat(createSummary(null, attachment = true)).isNull()
        assertThat(createSummary("room-summary:1000:sender", attachment = true)).isNull()
    }

    private suspend fun createSummary(canonicalIdentity: String?, attachment: Boolean = false,
                                     cachedIdentity: String? = null, cachedSender: String = "@qa:next", cachedTimestamp: ULong = 1000uL,
                                     summarySender: String = "@qa:next", summaryOwn: Boolean = false,
                                     localState: LatestEventValueLocalState? = null, cachedBody: String? = null,
                                     cachedRemote: Boolean = true, cachedOwn: Boolean = true): String? {
        var identity: String? = null
        val owners = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override fun openTimelineReleaseScope(scopeId: String): String = "preview-owner".also { owners += it }
            override fun closeTimelineReleaseScope(scopeId: String) { assertThat(owners.remove(scopeId)).isTrue() }
            override suspend fun observedRoomSummaryEventIdentity(timestamp: Long, sender: String, message: MessageContent): String? {
                assertThat(timestamp).isEqualTo(1000L)
                assertThat(sender).isEqualTo(summarySender)
                assertThat(message.body).isEqualTo(if (attachment) ".yizhe-caption-content.yzm" else "yizhe-secure-v1:opaque")
                if (attachment) assertThat((message.type as ImageMessageType).caption).isEqualTo("yizhe-caption-v1:opaque")
                return canonicalIdentity
            }
            override suspend fun decryptMessageContent(
                eventIdentity: String, message: MessageContent, timelineReleaseIntent: TimelineContentReleaseIntent,
            ): MessageContent {
                assertThat(timelineReleaseIntent.scopeId).isEqualTo("preview-owner")
                assertThat(timelineReleaseIntent.releaseClass).isEqualTo(TimelineContentReleaseClass.HISTORY_PREFETCH)
                assertThat(timelineReleaseIntent.readPurpose).isEqualTo(TimelineContentReadPurpose.ROOM_PREVIEW)
                identity = eventIdentity
                return message
            }
            override suspend fun decryptMessageContent(
                eventIdentity: String, message: MessageContent, timelineReleaseIntent: TimelineContentReleaseIntent,
                senderAccountIdHash: String,
            ): MessageContent {
                val expected = java.security.MessageDigest.getInstance("SHA-256").digest(summarySender.encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertThat(senderAccountIdHash).isEqualTo(expected)
                return decryptMessageContent(eventIdentity, message, timelineReleaseIntent)
            }
        }
        val factory = RoomSummaryFactory(sessionId = A_SESSION_ID, deviceId = A_DEVICE_ID, protectedContentAdapterFactory =
            object : MatrixProtectedContentAdapterFactory {
                override fun create(sessionId: SessionId, deviceId: DeviceId, roomId: RoomId, activeMemberAccountIdHashes: suspend () -> Set<String>,
                                    isRoomEncrypted: suspend () -> Boolean): MatrixProtectedContentAdapter = adapter
            })
        val cachedTimeline = FakeFfiTimeline()
        val room = FakeFfiRoom(timelineLambda = { cachedTimeline }, latestEventLambda = {
            val content = if (attachment) {
                val media = mockk<org.matrix.rustcomponents.sdk.MediaSource>(relaxed = true) {
                    every { url() } returns "mxc://qa/caption"
                    every { toJson() } returns "\"mxc://qa/caption\""
                }
                val message = org.matrix.rustcomponents.sdk.MessageContent(
                    msgType = org.matrix.rustcomponents.sdk.MessageType.Image(org.matrix.rustcomponents.sdk.ImageMessageContent(
                        filename = ".yizhe-caption-content.yzm", caption = "yizhe-caption-v1:opaque",
                        formattedCaption = null, source = media, info = null,
                    )), body = ".yizhe-caption-content.yzm", isEdited = false, mentions = null,
                )
                org.matrix.rustcomponents.sdk.TimelineItemContent.MsgLike(org.matrix.rustcomponents.sdk.MsgLikeContent(
                    kind = org.matrix.rustcomponents.sdk.MsgLikeKind.Message(message), reactions = emptyList(),
                    threadRoot = null, inReplyTo = null, threadSummary = null,
                ))
            } else aRustTimelineItemContentMsgLike("yizhe-secure-v1:opaque")
            cachedIdentity?.let {
                cachedTimeline.latestEventIdentity = it
                cachedTimeline.storeEvent(it, aRustEventTimelineItem(
                    eventOrTransactionId = org.matrix.rustcomponents.sdk.EventOrTransactionId.EventId(it),
                    sender = cachedSender, timestamp = cachedTimestamp,
                    content = cachedBody?.let { aRustTimelineItemContentMsgLike(it) } ?: content,
                    isRemote = cachedRemote, isOwn = cachedOwn,
                ))
            }
            if (localState != null) {
                LatestEventValue.Local(1000uL, summarySender, ProfileDetails.Unavailable, content, localState)
            } else {
                LatestEventValue.Remote(1000uL, summarySender, summaryOwn, ProfileDetails.Unavailable, content)
            }
        })
        factory.create(room)
        assertThat(owners).isEmpty()
        if (localState == LatestEventValueLocalState.IS_SENDING || localState == LatestEventValueLocalState.CANNOT_BE_SENT) {
            assertThat(cachedTimeline.closeCount).isEqualTo(0)
        } else if (canonicalIdentity == null || !canonicalIdentity.startsWith("$")) {
            assertThat(cachedTimeline.closeCount).isEqualTo(1)
        }
        return identity
    }
}
