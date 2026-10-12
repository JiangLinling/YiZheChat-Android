/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.factories

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.NotificationConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_COLOR_INT
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_THREAD_ID
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.ui.components.aMatrixUser
import io.element.android.libraries.matrix.ui.media.test.FakeImageLoader
import io.element.android.libraries.matrix.ui.media.test.FakeInitialsAvatarBitmapGenerator
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import io.element.android.libraries.push.impl.notifications.DefaultNotificationBitmapLoader
import io.element.android.libraries.push.impl.notifications.NotificationActionIds
import io.element.android.libraries.push.impl.notifications.RoomEventGroupInfo
import io.element.android.libraries.push.impl.notifications.channels.DefaultNotificationChannels
import io.element.android.libraries.push.impl.notifications.channels.FakeNotificationChannels
import io.element.android.libraries.push.impl.notifications.channels.NotificationChannels
import io.element.android.libraries.push.impl.notifications.factories.action.AcceptInvitationActionFactory
import io.element.android.libraries.push.impl.notifications.factories.action.MarkAsReadActionFactory
import io.element.android.libraries.push.impl.notifications.factories.action.QuickReplyActionFactory
import io.element.android.libraries.push.impl.notifications.factories.action.RejectInvitationActionFactory
import io.element.android.libraries.push.impl.notifications.fixtures.aFallbackNotifiableEvent
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.InviteNotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.SimpleNotifiableEvent
import io.element.android.services.toolbox.test.sdk.FakeBuildVersionSdkIntProvider
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DefaultNotificationCreatorTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `direct conversation shows only latest message and supplies a chat bubble`() = runTest {
        val result = createNotificationCreator().createMessagesListNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomInfo = RoomEventGroupInfo(A_SESSION_ID, A_ROOM_ID, "Alice", isDm = true),
            threadId = null,
            largeIcon = null,
            lastMessageTimestamp = 200L,
            tickerText = "Alice: Latest message",
            existingNotification = null,
            imageLoader = FakeImageLoader(),
            events = listOf(
                aNotifiableMessageEvent().copy(body = "Earlier message", timestamp = 100L),
                aNotifiableMessageEvent().copy(body = "Latest message", senderDisambiguatedDisplayName = "Alice", timestamp = 200L),
            ),
        )
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(result)!!
        assertThat(style.messages.map { it.text.toString() }).containsExactly("Latest message")
        assertThat(style.conversationTitle).isNull()
        assertThat(style.messages.single().person!!.name.toString()).isEqualTo("Alice")
        assertThat(result.bubbleMetadata).isNotNull()
        assertThat(result.contentIntent).isNotNull()
        assertThat(result.actions.orEmpty()).isEmpty()
        assertThat(result.allowSystemGeneratedContextualActions).isFalse()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `hidden update drops old message sender conversation shortcut and bubble extras`() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val previous = NotificationCompat.Builder(context, "messages")
            .setStyle(NotificationCompat.MessagingStyle("Me").addMessage("Private old body", 100L, "Alice"))
            .setShortcutId("private-room")
            .build()
        val result = createNotificationCreator().createMessagesListNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomInfo = RoomEventGroupInfo(A_SESSION_ID, A_ROOM_ID, "New messages", isDm = true),
            threadId = null,
            largeIcon = null,
            lastMessageTimestamp = 200L,
            tickerText = "New messages: New messages",
            existingNotification = previous,
            imageLoader = FakeImageLoader(),
            events = listOf(aNotifiableMessageEvent().redactPresentation("You have a new message", keepSender = false)),
        )
        assertThat(result.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("You have a new message")
        assertThat(result.extras.containsKey(Notification.EXTRA_MESSAGES)).isFalse()
        assertThat(result.extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)).isFalse()
        assertThat(result.shortcutId).isNull()
        assertThat(result.bubbleMetadata).isNull()
        assertThat(result.tickerText.toString()).isEqualTo("You have a new message")
        assertThat(result.visibility).isEqualTo(NotificationCompat.VISIBILITY_SECRET)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `ntfy hidden sender never becomes current user and background restores alert channel`() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val sut = createNotificationCreator(notificationChannels = FakeNotificationChannels(channelIdForMessage = { _, _ -> "messages" }))
        val old = NotificationCompat.Builder(context, "ntfy_foreground_history").setSilent(true).build()
        val result = sut.createMessagesListNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomInfo = RoomEventGroupInfo(A_SESSION_ID, A_ROOM_ID, "", isDm = true, shouldBing = true),
            threadId = null,
            largeIcon = null,
            lastMessageTimestamp = 123L,
            tickerText = "New message",
            existingNotification = old,
            imageLoader = FakeImageLoader(),
            events = listOf(aNotifiableMessageEvent().copy(senderDisambiguatedDisplayName = "", isNtfyDelivery = true)),
        )
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(result)!!
        assertThat(style.messages.last().person!!.name.toString()).isEqualTo("test")
        assertThat(result.extras.getBoolean(DefaultNotificationCreator.EXTRA_NTFY_DELIVERY)).isTrue()
        assertThat(result.actions.orEmpty()).isEmpty()
        assertThat(result.allowSystemGeneratedContextualActions).isFalse()
        assertThat(result.channelId).isEqualTo("messages")
        assertThat(result.groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_CHILDREN)
    }

    @Test
    fun `test createDiagnosticNotification`() {
        val sut = createNotificationCreator()
        val result = sut.createDiagnosticNotification(
            color = A_COLOR_INT,
        )
        result.commonAssertions(
            expectedGroup = null,
            expectedCategory = NotificationCompat.CATEGORY_STATUS,
        )
    }

    @Test
    fun `test createUnregistrationNotification`() {
        val sut = createNotificationCreator()
        val matrixUser = aMatrixUser()
        val result = sut.createUnregistrationNotification(
            notificationAccountParams = aNotificationAccountParams(
                user = matrixUser,
            ),
        )
        result.commonAssertions(
            expectedGroup = matrixUser.userId.value,
            expectedCategory = NotificationCompat.CATEGORY_ERROR,
        )
    }

    @Test
    fun `test createFallbackNotification`() {
        val sut = createNotificationCreator()
        val result = sut.createFallbackNotification(
            existingNotification = null,
            notificationAccountParams = aNotificationAccountParams(),
            fallbackNotifiableEvents = listOf(
                aFallbackNotifiableEvent(),
            )
        )
        result.commonAssertions(
            expectedCategory = null,
        )
    }

    @Test
    fun `test createSimpleEventNotification`() {
        val sut = createNotificationCreator()
        val result = sut.createSimpleEventNotification(
            notificationAccountParams = aNotificationAccountParams(),
            SimpleNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                noisy = false,
                title = "title",
                description = "description",
                type = null,
                timestamp = A_FAKE_TIMESTAMP,
                soundName = null,
                canBeReplaced = false,
                isRedacted = false,
                isUpdated = false,
            ),
        )
        result.commonAssertions(
            expectedCategory = null,
        )
    }

    @Test
    fun `test createSimpleEventNotification noisy`() {
        val sut = createNotificationCreator(
            enterpriseService = FakeEnterpriseService(
                getNoisyNotificationChannelIdResult = { null },
            ),
        )
        val result = sut.createSimpleEventNotification(
            notificationAccountParams = aNotificationAccountParams(),
            SimpleNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                noisy = true,
                title = "title",
                description = "description",
                type = null,
                timestamp = A_FAKE_TIMESTAMP,
                soundName = null,
                canBeReplaced = false,
                isRedacted = false,
                isUpdated = false,
            ),
        )
        result.commonAssertions(
            expectedCategory = null,
        )
    }

    @Test
    fun `test createRoomInvitationNotification`() {
        val sut = createNotificationCreator()
        val result = sut.createRoomInvitationNotification(
            notificationAccountParams = aNotificationAccountParams(),
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                noisy = false,
                title = "title",
                description = "description",
                type = null,
                timestamp = A_FAKE_TIMESTAMP,
                soundName = null,
                canBeReplaced = false,
                isRedacted = false,
                isUpdated = false,
                roomName = "roomName",
            ),
        )
        result.commonAssertions(
            expectedCategory = null,
        )
        val actionTitles = result.actions?.map { it.title }
        assertThat(actionTitles).isEqualTo(
            listOfNotNull(
                REJECT_INVITATION_ACTION_TITLE.takeIf { NotificationConfig.SHOW_ACCEPT_AND_DECLINE_INVITE_ACTIONS },
                ACCEPT_INVITATION_ACTION_TITLE.takeIf { NotificationConfig.SHOW_ACCEPT_AND_DECLINE_INVITE_ACTIONS },
            )
        )
    }

    @Test
    fun `test createRoomInvitationNotification noisy`() {
        val sut = createNotificationCreator(
            enterpriseService = FakeEnterpriseService(
                getNoisyNotificationChannelIdResult = { null },
            ),
        )
        val result = sut.createRoomInvitationNotification(
            notificationAccountParams = aNotificationAccountParams(),
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                noisy = true,
                title = "title",
                description = "description",
                type = null,
                timestamp = A_FAKE_TIMESTAMP,
                soundName = null,
                canBeReplaced = false,
                isRedacted = false,
                isUpdated = false,
                roomName = "roomName",
            ),
        )
        result.commonAssertions(
            expectedCategory = null,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `test createSummaryListNotification`() {
        val sut = createNotificationCreator()
        val matrixUser = aMatrixUser()
        val result = sut.createSummaryListNotification(
            notificationAccountParams = aNotificationAccountParams(user = matrixUser),
            compatSummary = "compatSummary",
            noisy = false,
            lastMessageTimestamp = 123_456L,
        )
        result.commonAssertions(
            expectedGroup = matrixUser.userId.value,
        )
        assertThat(result.groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_CHILDREN)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `test createSummaryListNotification noisy`() {
        val sut = createNotificationCreator(
            enterpriseService = FakeEnterpriseService(
                getNoisyNotificationChannelIdResult = { null },
            ),
        )
        val matrixUser = aMatrixUser()
        val result = sut.createSummaryListNotification(
            notificationAccountParams = aNotificationAccountParams(user = matrixUser),
            compatSummary = "compatSummary",
            noisy = true,
            lastMessageTimestamp = 123_456L,
        )
        result.commonAssertions(
            expectedGroup = matrixUser.userId.value,
        )
        assertThat(result.groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_CHILDREN)
    }

    @Test
    fun `test createMessagesListNotification`() = runTest {
        val sut = createNotificationCreator()
        val result = sut.createMessagesListNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomInfo = RoomEventGroupInfo(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                roomDisplayName = "roomDisplayName",
                hasSmartReplyError = false,
                shouldBing = false,
                customSound = null,
                isUpdated = false,
            ),
            threadId = null,
            largeIcon = null,
            lastMessageTimestamp = 123_456L,
            tickerText = "tickerText",
            existingNotification = null,
            imageLoader = FakeImageLoader(),
            events = listOf(aNotifiableMessageEvent()),
        )
        result.commonAssertions()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `test createMessagesListNotification should bing and thread`() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val channelId = "message_alert_fallback_test"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)
                enableVibration(true)
            }
        )
        val sut = createNotificationCreator(
            context = context,
            enterpriseService = FakeEnterpriseService(
                getNoisyNotificationChannelIdResult = { null },
            ),
            notificationChannels = FakeNotificationChannels(
                channelIdForMessage = { _, _ -> channelId },
            ),
        )
        val result = sut.createMessagesListNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomInfo = RoomEventGroupInfo(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                roomDisplayName = "roomDisplayName",
                hasSmartReplyError = false,
                shouldBing = true,
                customSound = null,
                isUpdated = false,
            ),
            threadId = A_THREAD_ID,
            largeIcon = null,
            lastMessageTimestamp = 123_456L,
            tickerText = "tickerText",
            existingNotification = null,
            imageLoader = FakeImageLoader(),
            events = listOf(aNotifiableMessageEvent()),
        )
        result.commonAssertions()
        assertThat(result.priority).isEqualTo(NotificationCompat.PRIORITY_HIGH)
        assertThat(result.sound).isNotNull()
        assertThat(result.vibrate?.toList()).containsExactly(0L, 350L, 250L, 350L).inOrder()
    }

    private fun Notification.commonAssertions(
        expectedGroup: String? = aMatrixUser().userId.value,
        expectedCategory: String? = NotificationCompat.CATEGORY_MESSAGE,
    ) {
        assertThat(contentIntent).isNotNull()
        assertThat(group).isEqualTo(expectedGroup)
        assertThat(category).isEqualTo(expectedCategory)
    }
}

const val MARK_AS_READ_ACTION_TITLE = "MarkAsReadAction"
const val QUICK_REPLY_ACTION_TITLE = "QuickReplyAction"
const val ACCEPT_INVITATION_ACTION_TITLE = "AcceptInvitationAction"
const val REJECT_INVITATION_ACTION_TITLE = "RejectInvitationAction"

fun createNotificationCreator(
    context: Context = RuntimeEnvironment.getApplication(),
    buildMeta: BuildMeta = aBuildMeta(),
    enterpriseService: EnterpriseService = FakeEnterpriseService(),
    notificationChannels: NotificationChannels = createNotificationChannels(enterpriseService),
    bitmapLoader: NotificationBitmapLoader = DefaultNotificationBitmapLoader(
        context = context,
        sdkIntProvider = FakeBuildVersionSdkIntProvider(Build.VERSION_CODES.R),
        initialsAvatarBitmapGenerator = FakeInitialsAvatarBitmapGenerator(),
    ),
): NotificationCreator {
    return DefaultNotificationCreator(
        context = context,
        notificationChannels = notificationChannels,
        stringProvider = FakeStringProvider("test"),
        buildMeta = buildMeta,
        pendingIntentFactory = PendingIntentFactory(
            context,
            FakeIntentProvider(),
            FakeSystemClock(),
            NotificationActionIds(buildMeta),
        ),
        markAsReadActionFactory = MarkAsReadActionFactory(
            context = context,
            actionIds = NotificationActionIds(buildMeta),
            stringProvider = FakeStringProvider(MARK_AS_READ_ACTION_TITLE),
            clock = FakeSystemClock(),
        ),
        quickReplyActionFactory = QuickReplyActionFactory(
            context = context,
            actionIds = NotificationActionIds(buildMeta),
            stringProvider = FakeStringProvider(QUICK_REPLY_ACTION_TITLE),
            clock = FakeSystemClock(),
        ),
        bitmapLoader = bitmapLoader,
        acceptInvitationActionFactory = AcceptInvitationActionFactory(
            context = context,
            actionIds = NotificationActionIds(buildMeta),
            stringProvider = FakeStringProvider(ACCEPT_INVITATION_ACTION_TITLE),
            clock = FakeSystemClock(),
        ),
        rejectInvitationActionFactory = RejectInvitationActionFactory(
            context = context,
            actionIds = NotificationActionIds(buildMeta),
            stringProvider = FakeStringProvider(REJECT_INVITATION_ACTION_TITLE),
            clock = FakeSystemClock(),
        ),
    )
}

fun createNotificationChannels(
    enterpriseService: EnterpriseService = FakeEnterpriseService(),
): NotificationChannels {
    val context = RuntimeEnvironment.getApplication()
    val notificationManagerCompat = NotificationManagerCompat.from(context)
    return DefaultNotificationChannels(
        notificationManager = notificationManagerCompat,
        stringProvider = FakeStringProvider(""),
        context = context,
        enterpriseService = enterpriseService,
        appPreferencesStore = io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore(),
    )
}
