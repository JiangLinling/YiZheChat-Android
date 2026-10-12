/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl

import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import im.vector.app.features.analytics.plan.PinUnpinAction
import io.element.android.features.location.test.FakeActiveLiveLocationShareManager
import io.element.android.features.messages.impl.actionlist.ActionListEvent
import io.element.android.features.messages.impl.actionlist.ActionListState
import io.element.android.features.messages.impl.actionlist.anActionListState
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.crypto.identity.anIdentityChangeState
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.link.aLinkState
import io.element.android.features.messages.impl.local.FakeMessageReferenceStore
import io.element.android.features.messages.impl.messagecomposer.MessageComposerEvent
import io.element.android.features.messages.impl.messagecomposer.MessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aMessageComposerState
import io.element.android.features.messages.impl.pinned.banner.aLoadedPinnedMessagesBannerState
import io.element.android.features.messages.impl.threads.list.aThreadListItem
import io.element.android.features.messages.impl.timeline.FakeMarkAsFullyRead
import io.element.android.features.messages.impl.timeline.MarkAsFullyRead
import io.element.android.features.messages.impl.timeline.MESSAGE_REMOVAL_DESTRUCTION_MILLIS
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemPollContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.features.messages.test.timeline.FakeHtmlConverterProvider
import io.element.android.features.messages.test.timeline.voicemessages.composer.FakeDefaultVoiceMessageComposerPresenterFactory
import io.element.android.features.roomcall.api.aStandByCallState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationEvents
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.test.FakeFriendProfileRepository
import io.element.android.libraries.androidutils.clipboard.FakeClipboardHelper
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SendHandle
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toThreadId
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.libraries.matrix.api.room.MessageEventType
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.room.history.RoomHistoryVisibility
import io.element.android.libraries.matrix.api.room.tombstone.SuccessorRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.SendHandleProvider
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_CAPTION
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.A_THREAD_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.core.FakeSendHandle
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.room.powerlevels.FakeRoomPermissions
import io.element.android.libraries.matrix.test.room.threads.FakeThreadsListService
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aTimelineItemDebugInfo
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.recentemojis.api.AddRecentEmoji
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.FakeLifecycleOwner
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.element.android.tests.testutils.testWithLifecycleOwner
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
class MessagesPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `direct chat avatar opens profile without moderation sheet`() = runTest {
        var openedUser: UserId? = null
        val moderationEvents = EventsRecorder<RoomMemberModerationEvents>(expectEvents = false)
        val presenter = createMessagesPresenter(
            joinedRoom = FakeJoinedRoom(baseRoom = FakeBaseRoom(initialRoomInfo = aRoomInfo(isDm = true))),
            navigator = FakeMessagesNavigator(navigateToMemberLambda = { openedUser = it }),
            roomMemberModerationPresenter = Presenter {
                object : RoomMemberModerationState {
                    override val permissions = aRoomMemberModerationState().permissions
                    override val eventSink = moderationEvents
                }
            },
        )
        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            state.eventSink(MessagesEvent.OnUserClicked(MatrixUser(A_USER_ID_2)))
            assertThat(openedUser).isEqualTo(A_USER_ID_2)
            moderationEvents.assertEmpty()
        }
    }

    @Test
    fun `group avatar opens profile without moderation sheet`() = runTest {
        var openedUser: UserId? = null
        val moderationEvents = EventsRecorder<RoomMemberModerationEvents>(expectEvents = false)
        val presenter = createMessagesPresenter(
            navigator = FakeMessagesNavigator(navigateToMemberLambda = { openedUser = it }),
            roomMemberModerationPresenter = Presenter {
                object : RoomMemberModerationState {
                    override val permissions = aRoomMemberModerationState().permissions
                    override val eventSink = moderationEvents
                }
            },
        )
        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            val user = MatrixUser(A_USER_ID_2)
            state.eventSink(MessagesEvent.OnUserClicked(user))
            assertThat(openedUser).isEqualTo(A_USER_ID_2)
            moderationEvents.assertEmpty()
        }
    }

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createMessagesPresenter()
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilTimeout().last()
            assertThat(initialState.roomId).isEqualTo(A_ROOM_ID)
            assertThat(initialState.roomName).isEqualTo("")
            assertThat(initialState.roomAvatar)
                .isEqualTo(AvatarData(id = A_ROOM_ID.value, name = "", url = AN_AVATAR_URL, size = AvatarSize.TimelineRoom))
            assertThat(initialState.userEventPermissions.canSendMessage).isTrue()
            assertThat(initialState.userEventPermissions.canRedactOwn).isTrue()
            assertThat(initialState.snackbarMessage).isNull()
            assertThat(initialState.inviteProgress).isEqualTo(AsyncData.Uninitialized)
            assertThat(initialState.showReinvitePrompt).isFalse()
            assertThat(initialState.showLiveLocationShareBanner).isFalse()
        }
    }

    @Test
    fun `present - exposes live location sharing banner visibility for current room`() = runTest {
        val liveLocationShareManager = FakeActiveLiveLocationShareManager(
            startShareLambda = { _, _ -> Result.success(Unit) },
        )
        liveLocationShareManager.startShare(A_ROOM_ID, 60.seconds)
        val presenter = createMessagesPresenter(liveLocationShareManager = liveLocationShareManager)

        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            assertThat(state.showLiveLocationShareBanner).isTrue()
        }
    }

    @Test
    fun `present - stop live location share delegates to manager for current room`() = runTest {
        val stopShareLambda = lambdaRecorder<RoomId, Result<Unit>> { Result.success(Unit) }
        val liveLocationShareManager = FakeActiveLiveLocationShareManager(
            stopShareLambda = stopShareLambda
        )
        val presenter = createMessagesPresenter(liveLocationShareManager = liveLocationShareManager)

        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            state.eventSink(MessagesEvent.StopLiveLocationShare)
            advanceUntilIdle()
            assert(stopShareLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - check that the room's unread flag is removed`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                markAsReadResult = { lambdaError() }
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            runCurrent()
            assertThat(room.baseRoom.setUnreadFlagCalls).isEqualTo(listOf(false))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle toggling a reaction`() = runTest {
        val coroutineDispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true)
        val toggleReactionSuccess = lambdaRecorder { _: String, _: EventOrTransactionId -> Result.success(true) }
        val toggleReactionFailure =
            lambdaRecorder { _: String, _: EventOrTransactionId -> Result.failure<Boolean>(IllegalStateException("Failed to send reaction")) }
        val addRecentEmojiResult = lambdaRecorder { _: String -> Result.success(Unit) }

        val timeline = FakeTimeline().apply {
            this.toggleReactionLambda = toggleReactionSuccess
        }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(
            timeline = timeline,
            joinedRoom = room,
            addRecentEmoji = AddRecentEmoji { addRecentEmojiResult(it) },
            coroutineDispatchers = coroutineDispatchers,
            sessionCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.ToggleReaction("👍", AN_EVENT_ID.toEventOrTransactionId()))
            advanceUntilIdle()
            assert(toggleReactionSuccess)
                .isCalledOnce()
                .with(value("👍"), value(AN_EVENT_ID.toEventOrTransactionId()))
            // No crashes when sending a reaction failed
            timeline.toggleReactionLambda = toggleReactionFailure
            initialState.eventSink(MessagesEvent.ToggleReaction("👍", AN_EVENT_ID.toEventOrTransactionId()))
            advanceUntilIdle()
            assert(toggleReactionFailure)
                .isCalledOnce()
                .with(value("👍"), value(AN_EVENT_ID.toEventOrTransactionId()))
            addRecentEmojiResult.assertions().isCalledOnce().with(value("👍"))
        }
    }

    @Test
    fun `present - handle toggling a reaction twice`() = runTest {
        val coroutineDispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true)
        var toggle = false
        val toggleReactionSuccess = lambdaRecorder { _: String, _: EventOrTransactionId ->
            toggle = !toggle
            Result.success(toggle)
        }
        val addRecentEmoji = lambdaRecorder { _: String ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.toggleReactionLambda = toggleReactionSuccess
        }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(
            timeline = timeline,
            joinedRoom = room,
            addRecentEmoji = AddRecentEmoji { addRecentEmoji(it) },
            coroutineDispatchers = coroutineDispatchers,
            sessionCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.ToggleReaction("👍", AN_EVENT_ID.toEventOrTransactionId()))
            initialState.eventSink(MessagesEvent.ToggleReaction("👍", AN_EVENT_ID.toEventOrTransactionId()))
            advanceUntilIdle()
            assert(toggleReactionSuccess)
                .isCalledExactly(2)
                .withSequence(
                    listOf(value("👍"), value(AN_EVENT_ID.toEventOrTransactionId())),
                    listOf(value("👍"), value(AN_EVENT_ID.toEventOrTransactionId())),
                )
            skipItems(1)
            addRecentEmoji.assertions().isCalledOnce().with(value("👍"))
        }
    }

    @Test
    fun `present - handle action forward`() = runTest {
        val onForwardEventClickLambda = lambdaRecorder<EventId, Unit> { }
        val navigator = FakeMessagesNavigator(
            onForwardEventClickLambda = onForwardEventClickLambda,
        )
        val presenter = createMessagesPresenter(navigator = navigator)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Forward, aMessageEvent()))
            assertThat(awaitItem().actionListState.target).isEqualTo(ActionListState.Target.None)
            onForwardEventClickLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID))
        }
    }

    @Test
    fun `present - outgoing friend request cannot send or pin messages`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                initialRoomInfo = aRoomInfo(
                    isDm = true,
                    activeMembersCount = 2,
                    invitedMembersCount = 1,
                    joinedMembersCount = 1,
                ),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)

        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            assertThat(state.directRoomRelationState).isEqualTo(DirectRoomRelationState.OUTGOING_REQUEST)
            assertThat(state.userEventPermissions.canSendMessage).isFalse()
            assertThat(state.userEventPermissions.canSendReaction).isFalse()
            assertThat(state.userEventPermissions.canPinUnpin).isFalse()
        }
    }

    @Test
    fun `present - recent emoji failure does not block reaction lifecycle receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val timeline = FakeTimeline().apply {
            toggleReactionLambda = { _, _ -> Result.success(true) }
        }
        val presenter = createMessagesPresenter(
            timeline = timeline,
            addRecentEmoji = AddRecentEmoji { Result.failure(IllegalStateException("local recents unavailable")) },
            appOperationalStateCoordinator = coordinator,
            coroutineDispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            sessionCoroutineScope = this,
        )

        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(MessagesEvent.ToggleReaction("👍", AN_EVENT_ID.toEventOrTransactionId()))
            advanceUntilIdle()

            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - unsigned device confirmation resends before starting call`() = runTest {
        val devices = mapOf(A_USER_ID to listOf(DeviceId("NEW_DEVICE")))
        val sendHandle = FakeSendHandle()
        var resendCompleted = false
        var callStarted = false
        val room = FakeJoinedRoom(
            ignoreDeviceTrustAndResendResult = { actualDevices: Map<UserId, List<DeviceId>>, actualHandle: SendHandle ->
                assertThat(actualDevices).isEqualTo(devices)
                assertThat(actualHandle).isSameInstanceAs(sendHandle)
                resendCompleted = true
                Result.success(Unit)
            },
        )
        val navigator = FakeMessagesNavigator(
            navigateToRoomCallLambda = { roomId, isAudioCall ->
                assertThat(resendCompleted).isTrue()
                assertThat(roomId).isEqualTo(A_ROOM_ID)
                assertThat(isAudioCall).isTrue()
                callStarted = true
            },
        )
        val failedEvent = aMessageEvent(
            sendState = LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice(devices),
            sendHandleProvider = SendHandleProvider { sendHandle },
        )
        val presenter = createMessagesPresenter(joinedRoom = room, navigator = navigator)

        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.ResolveUnsignedDeviceAndStartCall(failedEvent, isAudioCall = true))
            advanceUntilIdle()

            assertThat(resendCompleted).isTrue()
            assertThat(callStarted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - failed unsigned device resend keeps call blocked`() = runTest {
        val devices = mapOf(A_USER_ID to listOf(DeviceId("NEW_DEVICE")))
        val sendHandle = FakeSendHandle()
        var callStarted = false
        val room = FakeJoinedRoom(
            ignoreDeviceTrustAndResendResult = { _, _ -> Result.failure(AN_EXCEPTION) },
        )
        val navigator = FakeMessagesNavigator(
            navigateToRoomCallLambda = { _, _ -> callStarted = true },
        )
        val failedEvent = aMessageEvent(
            sendState = LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice(devices),
            sendHandleProvider = SendHandleProvider { sendHandle },
        )
        val presenter = createMessagesPresenter(joinedRoom = room, navigator = navigator)

        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.ResolveUnsignedDeviceAndStartCall(failedEvent, isAudioCall = false))
            advanceUntilIdle()

            assertThat(callStarted).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - event plaintext copy fails closed until source authorization is projected`() = runTest {
        val clipboardHelper = FakeClipboardHelper()
        val event = aMessageEvent()
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelinePresenter = Presenter { aTimelineState(timelineItems = persistentListOf(event)) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyText, event))
            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            assertThat(clipboardHelper.clipboardContents).isNull()
            assertThat(requireNotNull(feedbackState.favouriteSnackbarMessage).messageResId)
                .isEqualTo(R.string.screen_room_timeline_copy_not_allowed)
            assertThat(feedbackState.snackbarMessage).isNull()
        }
    }

    @Test
    fun `present - handle action copy link`() = runTest {
        val clipboardHelper = FakeClipboardHelper()
        val event = aMessageEvent()
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                eventPermalinkResult = { Result.success("a link") },
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            joinedRoom = room,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyLink, event))
            skipItems(2)
            assertThat(clipboardHelper.clipboardContents).isEqualTo("a link")
        }
    }

    @Test
    fun `present - handle action reply`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, aMessageEvent()))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action reply to an event with no id does nothing`() = runTest {
        val presenter = createMessagesPresenter()
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, aMessageEvent(eventId = null)))
            skipItems(1)
        }
    }

    @Test
    fun `present - handle action reply to an image media message`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val mediaMessage = aMessageEvent(
                content = TimelineItemImageContent(
                    filename = "image.jpg",
                    fileSize = 4 * 1024 * 1024L,
                    caption = null,
                    formattedCaption = null,
                    isEdited = false,
                    mediaSource = MediaSource(AN_AVATAR_URL),
                    thumbnailSource = null,
                    mimeType = MimeTypes.Jpeg,
                    blurhash = null,
                    width = 20,
                    height = 20,
                    thumbnailWidth = null,
                    thumbnailHeight = null,
                    aspectRatio = 1.0f,
                    fileExtension = "jpg",
                    formattedFileSize = "4MB"
                )
            )
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, mediaMessage))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action reply to a video media message`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val mediaMessage = aMessageEvent(
                content = TimelineItemVideoContent(
                    filename = "video.mp4",
                    fileSize = 50 * 1024 * 1024L,
                    caption = null,
                    formattedCaption = null,
                    isEdited = false,
                    duration = 10.milliseconds,
                    mediaSource = MediaSource(AN_AVATAR_URL),
                    thumbnailSource = MediaSource(AN_AVATAR_URL),
                    mimeType = MimeTypes.Mp4,
                    blurHash = null,
                    width = 20,
                    height = 20,
                    thumbnailWidth = 20,
                    thumbnailHeight = 20,
                    aspectRatio = 1.0f,
                    fileExtension = "mp4",
                    formattedFileSize = "50MB"
                )
            )
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, mediaMessage))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action reply to a file media message`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val mediaMessage = aMessageEvent(
                content = TimelineItemFileContent(
                    filename = "file.pdf",
                    fileSize = 10 * 1024 * 1024L,
                    caption = null,
                    isEdited = false,
                    formattedCaption = null,
                    mediaSource = MediaSource(AN_AVATAR_URL),
                    thumbnailSource = MediaSource(AN_AVATAR_URL),
                    formattedFileSize = "10 MB",
                    mimeType = MimeTypes.Pdf,
                    fileExtension = "pdf",
                )
            )
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, mediaMessage))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action edit`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Edit, aMessageEvent()))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Edit(
                        eventOrTransactionId = AN_EVENT_ID.toEventOrTransactionId(),
                        content = (aMessageEvent().content as TimelineItemTextContent).body
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action edit poll`() = runTest {
        val onEditPollClickLambda = lambdaRecorder<EventId, Unit> { }
        val navigator = FakeMessagesNavigator(
            onEditPollClickLambda = onEditPollClickLambda
        )
        val presenter = createMessagesPresenter(navigator = navigator)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.EditPoll, aMessageEvent(content = aTimelineItemPollContent())))
            awaitItem()
            onEditPollClickLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID))
        }
    }

    @Test
    fun `present - handle action end poll`() = runTest {
        val timelineEventSink = EventsRecorder<TimelineEvent>()
        val presenter = createMessagesPresenter(timelineEventSink = timelineEventSink)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.EndPoll, aMessageEvent(content = aTimelineItemPollContent())))
            delay(1)
            timelineEventSink.assertSingle(TimelineEvent.EndPoll(AN_EVENT_ID))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle action redact`() = runTest {
        val coroutineDispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true)
        val coordinator = FakeAppOperationalStateCoordinator()

        val liveTimeline = FakeTimeline()
        val joinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = liveTimeline,
            typingNoticeResult = { Result.success(Unit) },
        )

        val redactEventLambda = lambdaRecorder { _: EventOrTransactionId, _: String? -> Result.success(Unit) }
        liveTimeline.redactEventLambda = redactEventLambda
        val presenter = createMessagesPresenter(
            timeline = liveTimeline,
            joinedRoom = joinedRoom,
            coroutineDispatchers = coroutineDispatchers,
            appOperationalStateCoordinator = coordinator,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val messageEvent = aMessageEvent()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Redact, messageEvent))
            awaitItem()
            assert(redactEventLambda)
                .isCalledOnce()
                .with(value(messageEvent.eventOrTransactionId), value(null))
            val request = coordinator.startedRequests.single()
            fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertThat(request.accountIdHash).isEqualTo(hash(joinedRoom.sessionId.value))
            assertThat(request.matrixDeviceIdHash).isEqualTo(hash(io.element.android.libraries.matrix.test.A_DEVICE_ID.value))
            assertThat(request.roomIdHash).isEqualTo(hash(joinedRoom.roomId.value))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - current explicitly unprotected event plaintext can be copied`() = runTest {
        val clipboardHelper = FakeClipboardHelper()
        val event = aMessageEvent().copy(clipboardSource = MessageClipboardSource.Unprotected)
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelinePresenter = Presenter { aTimelineState(timelineItems = persistentListOf(event)) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyText, event))
            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            assertThat(clipboardHelper.clipboardContents).isEqualTo((event.content as TimelineItemTextContent).body)
            assertThat(requireNotNull(feedbackState.favouriteSnackbarMessage).messageResId)
                .isEqualTo(R.string.screen_room_timeline_message_copied)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - protected copy binds exact current projection authority`() = runTest {
        val clipboardHelper = FakeClipboardHelper()
        val source = protectedClipboardSource(authorizationRevision = 7L)
        val event = aMessageEvent().copy(clipboardSource = source)
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelinePresenter = Presenter { aTimelineState(timelineItems = persistentListOf(event)) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyText, event))
            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            assertThat(clipboardHelper.clipboardContents).isEqualTo((event.content as TimelineItemTextContent).body)
            assertThat(clipboardHelper.protectedSource?.authorizationRevision).isEqualTo(7L)
            assertThat(requireNotNull(feedbackState.favouriteSnackbarMessage).messageResId)
                .isEqualTo(R.string.screen_room_timeline_message_copied)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - stale action cannot copy after current projection authority changes`() = runTest {
        val clipboardHelper = FakeClipboardHelper()
        val staleEvent = aMessageEvent().copy(clipboardSource = protectedClipboardSource(authorizationRevision = 7L))
        val currentEvent = staleEvent.copy(clipboardSource = protectedClipboardSource(authorizationRevision = 8L))
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelinePresenter = Presenter { aTimelineState(timelineItems = persistentListOf(currentEvent)) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyText, staleEvent))
            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            assertThat(clipboardHelper.clipboardContents).isNull()
            assertThat(requireNotNull(feedbackState.favouriteSnackbarMessage).messageResId)
                .isEqualTo(R.string.screen_room_timeline_copy_not_allowed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - rejected copy link does not report success`() = runTest {
        val clipboardHelper = FakeClipboardHelper().apply { allowCopy = false }
        val event = aMessageEvent()
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                eventPermalinkResult = { Result.success("a link") },
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            joinedRoom = room,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.CopyLink, event))
            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            assertThat(clipboardHelper.clipboardContents).isNull()
            assertThat(requireNotNull(feedbackState.favouriteSnackbarMessage).messageResId)
                .isEqualTo(R.string.screen_room_timeline_copy_not_allowed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle action redact uses the server event after optimistic identity is confirmed`() = runTest {
        val coroutineDispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true)
        val liveTimeline = FakeTimeline()
        val joinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(roomPermissions = roomPermissions()),
            liveTimeline = liveTimeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val redactEventLambda = lambdaRecorder { _: EventOrTransactionId, _: String? -> Result.success(Unit) }
        liveTimeline.redactEventLambda = redactEventLambda
        val presenter = createMessagesPresenter(
            timeline = liveTimeline,
            joinedRoom = joinedRoom,
            coroutineDispatchers = coroutineDispatchers,
        )

        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val confirmedEvent = aMessageEvent().copy(id = UniqueId("optimistic:confirmed"))
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Redact, confirmedEvent))
            awaitItem()
            assert(redactEventLambda)
                .isCalledOnce()
                .with(value(confirmedEvent.eventOrTransactionId), value(null))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - successful redaction prepares then commits one removal transition`() = runTest {
        val timelineEvents = mutableListOf<TimelineEvent>()
        val liveTimeline = FakeTimeline().apply {
            redactEventLambda = { _, _ -> Result.success(Unit) }
        }
        val presenter = createMessagesPresenter(
            timeline = liveTimeline,
            joinedRoom = FakeJoinedRoom(
                baseRoom = FakeBaseRoom(roomPermissions = roomPermissions()),
                liveTimeline = liveTimeline,
                typingNoticeResult = { Result.success(Unit) },
            ),
            timelineEventSink = timelineEvents::add,
        )

        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Withdraw, aMessageEvent()))
            runCurrent()

            assertThat(timelineEvents).containsExactly(
                TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID),
                TimelineEvent.CommitMessageRemoval(AN_EVENT_ID),
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - local deletion stays visible for the terminal animation when the store publishes first`() = runTest {
        val event = aMessageEvent()
        val timelineEvents = mutableListOf<TimelineEvent>()
        val messageReferenceStore = FakeMessageReferenceStore()
        val presenter = createMessagesPresenter(
            timelinePresenter = Presenter {
                aTimelineState(
                    timelineItems = persistentListOf(event),
                    eventSink = timelineEvents::add,
                )
            },
            messageReferenceStore = messageReferenceStore,
        )

        presenter.testWithLifecycleOwner {
            val initial = consumeItemsUntilPredicate { state ->
                state.timelineState.timelineItems.any { item ->
                    (item as? io.element.android.features.messages.impl.timeline.model.TimelineItem.Event)?.eventId == AN_EVENT_ID
                }
            }.last()
            initial.eventSink(MessagesEvent.HandleAction(TimelineItemAction.DeleteLocal, event))
            runCurrent()

            val animating = consumeItemsUntilPredicate { state ->
                TimelineEvent.CommitMessageRemoval(AN_EVENT_ID) in timelineEvents &&
                    state.timelineState.timelineItems.any { item ->
                        (item as? io.element.android.features.messages.impl.timeline.model.TimelineItem.Event)?.eventId == AN_EVENT_ID
                    }
            }.last()
            assertThat(messageReferenceStore.state.value.isLocallyDeleted(A_ROOM_ID, AN_EVENT_ID)).isTrue()
            assertThat(animating.timelineState.timelineItems).isNotEmpty()

            advanceTimeBy(MESSAGE_REMOVAL_DESTRUCTION_MILLIS)
            runCurrent()
            val settled = consumeItemsUntilPredicate { it.timelineState.timelineItems.isEmpty() }.last()
            assertThat(settled.timelineState.timelineItems).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - failed redaction emits exactly one cancellation and no commit`() = runTest {
        val timelineEvents = mutableListOf<TimelineEvent>()
        val liveTimeline = FakeTimeline().apply {
            redactEventLambda = { _, _ -> Result.failure(AN_EXCEPTION) }
        }
        val presenter = createMessagesPresenter(
            timeline = liveTimeline,
            joinedRoom = FakeJoinedRoom(
                baseRoom = FakeBaseRoom(roomPermissions = roomPermissions()),
                liveTimeline = liveTimeline,
                typingNoticeResult = { Result.success(Unit) },
            ),
            timelineEventSink = timelineEvents::add,
        )

        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Withdraw, aMessageEvent()))
            runCurrent()

            assertThat(timelineEvents).containsExactly(
                TimelineEvent.PrepareMessageRemoval(AN_EVENT_ID),
                TimelineEvent.CancelMessageRemoval(AN_EVENT_ID),
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle action report content`() = runTest {
        val onReportContentClickLambda = lambdaRecorder { _: EventId, _: UserId -> }
        val navigator = FakeMessagesNavigator(
            onReportContentClickLambda = onReportContentClickLambda
        )
        val presenter = createMessagesPresenter(navigator = navigator)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.ReportContent, aMessageEvent()))
            assertThat(awaitItem().actionListState.target).isEqualTo(ActionListState.Target.None)
            onReportContentClickLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(A_USER_ID))
        }
    }

    @Test
    fun `present - handle action show developer info`() = runTest {
        val onShowEventDebugInfoClickLambda = lambdaRecorder { _: EventId?, _: TimelineItemDebugInfo -> }
        val navigator = FakeMessagesNavigator(
            onShowEventDebugInfoClickLambda = onShowEventDebugInfoClickLambda
        )
        val presenter = createMessagesPresenter(navigator = navigator)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.ViewSource, aMessageEvent()))
            assertThat(awaitItem().actionListState.target).isEqualTo(ActionListState.Target.None)
            onShowEventDebugInfoClickLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(aTimelineItemDebugInfo()))
        }
    }

    @Test
    fun `present - shows prompt to reinvite users in DM`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ).apply {
                givenRoomInfo(aRoomInfo(isDm = true, joinedMembersCount = 1, activeMembersCount = 1))
            },
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            // Initially the composer doesn't have focus, so we don't show the alert
            assertThat(initialState.showReinvitePrompt).isFalse()
            // When the input field is focused we show the alert
            (initialState.composerState.textEditorState as TextEditorState.Markdown).state.hasFocus = true
            // Skip intermediate states
            skipItems(2)
            val focusedState = awaitItem()
            assertThat(focusedState.showReinvitePrompt).isTrue()
            // If it's dismissed then we stop showing the alert
            initialState.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Cancel))
            skipItems(1)
            val dismissedState = awaitItem()
            assertThat(dismissedState.showReinvitePrompt).isFalse()
        }
    }

    @Test
    fun `present - doesn't show reinvite prompt in non-direct room`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ).apply {
                givenRoomInfo(aRoomInfo(isDirect = false, joinedMembersCount = 1, activeMembersCount = 1))
            },
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            assertThat(initialState.showReinvitePrompt).isFalse()
            (initialState.composerState.textEditorState as TextEditorState.Markdown).state.hasFocus = true
            // Skip intermediate events
            skipItems(1)
            val focusedState = awaitItem()
            assertThat(focusedState.showReinvitePrompt).isFalse()
        }
    }

    @Test
    fun `present - doesn't show reinvite prompt if other party is present`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ).apply {
                givenRoomInfo(aRoomInfo(isDirect = true, joinedMembersCount = 2, activeMembersCount = 2))
            },
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            assertThat(initialState.showReinvitePrompt).isFalse()
            (initialState.composerState.textEditorState as TextEditorState.Markdown).state.hasFocus = true
            // Skip intermediate events
            skipItems(1)
            val focusedState = awaitItem()
            assertThat(focusedState.showReinvitePrompt).isFalse()
        }
    }

    @Test
    fun `present - handle reinviting other user when memberlist is ready`() = runTest {
        val inviteUserResult = lambdaRecorder { _: UserId -> Result.success(Unit) }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            typingNoticeResult = { Result.success(Unit) },
            inviteUserResult = inviteUserResult,
        )
        room.givenRoomMembersState(
            RoomMembersState.Ready(
                persistentListOf(
                    aRoomMember(userId = A_SESSION_ID, membership = RoomMembershipState.JOIN),
                    aRoomMember(userId = A_SESSION_ID_2, membership = RoomMembershipState.LEAVE),
                )
            )
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilTimeout().last()
            initialState.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Invite))
            skipItems(1)
            val loadingState = awaitItem()
            assertThat(loadingState.inviteProgress.isLoading()).isTrue()
            val newState = awaitItem()
            assertThat(newState.inviteProgress.isSuccess()).isTrue()
            inviteUserResult.assertions().isCalledOnce().with(value(A_SESSION_ID_2))
        }
    }

    @Test
    fun `present - handle reinviting other user when memberlist is error`() = runTest {
        val inviteUserResult = lambdaRecorder { _: UserId -> Result.success(Unit) }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            typingNoticeResult = { Result.success(Unit) },
            inviteUserResult = inviteUserResult,
        )
        room.givenRoomMembersState(
            RoomMembersState.Error(
                failure = Throwable(),
                prevRoomMembers = persistentListOf(
                    aRoomMember(userId = A_SESSION_ID, membership = RoomMembershipState.JOIN),
                    aRoomMember(userId = A_SESSION_ID_2, membership = RoomMembershipState.LEAVE),
                )
            )
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilTimeout().last()
            initialState.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Invite))
            skipItems(1)
            val loadingState = consumeItemsUntilPredicate { state ->
                state.inviteProgress.isLoading()
            }.last()
            assertThat(loadingState.inviteProgress.isLoading()).isTrue()
            val newState = awaitItem()
            assertThat(newState.inviteProgress.isSuccess()).isTrue()
            inviteUserResult.assertions().isCalledOnce().with(value(A_SESSION_ID_2))
        }
    }

    @Test
    fun `present - handle reinviting other user when memberlist is not ready`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        room.givenRoomMembersState(RoomMembersState.Unknown)
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilTimeout().last()
            initialState.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Invite))
            skipItems(1)
            val loadingState = awaitItem()
            assertThat(loadingState.inviteProgress.isLoading()).isTrue()
            val newState = awaitItem()
            assertThat(newState.inviteProgress.isFailure()).isTrue()
        }
    }

    @Test
    fun `present - handle reinviting other user when inviting fails`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            typingNoticeResult = { Result.success(Unit) },
            inviteUserResult = { Result.failure(RuntimeException("Oops!")) },
        )
        room.givenRoomMembersState(
            RoomMembersState.Ready(
                persistentListOf(
                    aRoomMember(userId = A_SESSION_ID, membership = RoomMembershipState.JOIN),
                    aRoomMember(userId = A_SESSION_ID_2, membership = RoomMembershipState.LEAVE),
                )
            )
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilTimeout().last()
            initialState.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Invite))

            val loadingState = consumeItemsUntilPredicate { state ->
                state.inviteProgress.isLoading()
            }.last()
            assertThat(loadingState.inviteProgress.isLoading()).isTrue()

            val failureState = consumeItemsUntilPredicate { state ->
                state.inviteProgress.isFailure()
            }.last()
            assertThat(failureState.inviteProgress.isFailure()).isTrue()
        }
    }

    @Test
    fun `present - permission to post`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val state = awaitItem()
            assertThat(state.userEventPermissions.canSendMessage).isTrue()
        }
    }

    @Test
    fun `present - no permission to post`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(
                    canSendMessage = false
                ),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            // Default value
            assertThat(awaitItem().userEventPermissions.canSendMessage).isTrue()
            assertThat(awaitItem().userEventPermissions.canSendMessage).isFalse()
        }
    }

    @Test
    fun `present - permission to redact own`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(
                    canRedactOther = false
                ),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = joinedRoom)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilPredicate { it.userEventPermissions.canRedactOwn }.last()
            assertThat(initialState.userEventPermissions.canRedactOwn).isTrue()
            assertThat(initialState.userEventPermissions.canRedactOther).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - permission to redact other`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(
                    canRedactOwn = false
                ),
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = joinedRoom)
        presenter.testWithLifecycleOwner {
            val initialState = consumeItemsUntilPredicate { it.userEventPermissions.canRedactOther }.last()
            assertThat(initialState.userEventPermissions.canRedactOwn).isFalse()
            assertThat(initialState.userEventPermissions.canRedactOther).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle action reply to a poll`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            val poll = aMessageEvent(
                content = aTimelineItemPollContent()
            )
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, poll))
            skipItems(1)
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action pin`() = runTest {
        val failurePinEventLambda = lambdaRecorder { _: EventId -> Result.failure<Boolean>(AN_EXCEPTION) }
        val analyticsService = FakeAnalyticsService()
        val timeline = FakeTimeline()
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val successPinEventLambda = lambdaRecorder { eventId: EventId ->
            room.givenRoomInfo(aRoomInfo(pinnedEventIds = listOf(eventId)))
            Result.success(true)
        }
        val presenter = createMessagesPresenter(
            timeline = timeline,
            joinedRoom = room,
            analyticsService = analyticsService,
        )
        presenter.testWithLifecycleOwner {
            val messageEvent = aMessageEvent(
                content = aTimelineItemTextContent()
            )
            val initialState = awaitItem()

            timeline.pinEventLambda = successPinEventLambda
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Pin, messageEvent))
            assert(successPinEventLambda).isCalledOnce().with(value(messageEvent.eventId))

            timeline.pinEventLambda = failurePinEventLambda
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Pin, messageEvent))
            assert(failurePinEventLambda).isCalledOnce().with(value(messageEvent.eventId))
            val failureState = consumeItemsUntilPredicate { it.snackbarMessage != null }.last()
            assertThat(failureState.snackbarMessage).isNotNull()
            assertThat(analyticsService.capturedEvents).containsExactly(
                PinUnpinAction(kind = PinUnpinAction.Kind.Pin, from = PinUnpinAction.From.Timeline),
                PinUnpinAction(kind = PinUnpinAction.Kind.Pin, from = PinUnpinAction.From.Timeline)
            )
        }
    }

    @Test
    fun `pin mutations wait for authenticated room state before starting the next mutation`() = runTest {
        val calls = mutableListOf<EventId>()
        val timeline = FakeTimeline().apply {
            pinEventLambda = { eventId ->
                calls += eventId
                Result.success(true)
            }
        }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(roomPermissions = roomPermissions()),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(timeline = timeline, joinedRoom = room)

        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Pin, aMessageEvent(eventId = AN_EVENT_ID)))
            runCurrent()
            state.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Pin, aMessageEvent(eventId = AN_EVENT_ID_2)))
            runCurrent()

            assertThat(calls).containsExactly(AN_EVENT_ID)

            room.givenRoomInfo(aRoomInfo(pinnedEventIds = listOf(AN_EVENT_ID)))
            runCurrent()
            assertThat(calls).containsExactly(AN_EVENT_ID, AN_EVENT_ID_2).inOrder()

            room.givenRoomInfo(aRoomInfo(pinnedEventIds = listOf(AN_EVENT_ID, AN_EVENT_ID_2)))
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - favourite action exposes feedback only in the current room`() = runTest {
        val presenter = createMessagesPresenter()
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(
                MessagesEvent.HandleAction(
                    action = TimelineItemAction.Favourite,
                    event = aMessageEvent(content = aTimelineItemTextContent()),
                )
            )

            val feedbackState = consumeItemsUntilPredicate { it.favouriteSnackbarMessage != null }.last()
            val feedback = requireNotNull(feedbackState.favouriteSnackbarMessage)
            assertThat(feedback.messageResId).isEqualTo(R.string.message_favourited)
            assertThat(feedbackState.snackbarMessage).isNull()

            feedbackState.eventSink(MessagesEvent.DismissFavouriteSnackbar(feedback.id))
            assertThat(awaitItem().favouriteSnackbarMessage).isNull()
        }
    }

    @Test
    fun `stable history recovery notice acknowledgement hides the same security state`() = runTest {
        val status = HistoryRecoveryStatus.FAILED_CLOSED
        val preferences = InMemorySessionPreferencesStore()
        val token = historyRecoveryNoticeToken(status, candidateCount = 3)

        assertThat(shouldDisplayHistoryRecoveryBanner(status, acknowledged = false)).isTrue()
        preferences.acknowledgeHistoryRecoveryBanner(A_ROOM_ID.value, token)

        assertThat(preferences.isHistoryRecoveryBannerAcknowledged(A_ROOM_ID.value, token).first()).isTrue()
        assertThat(shouldDisplayHistoryRecoveryBanner(status, acknowledged = true)).isFalse()
    }

    @Test
    fun `new history candidates do not inherit an older notice acknowledgement`() = runTest {
        val status = HistoryRecoveryStatus.FAILED_CLOSED
        val preferences = InMemorySessionPreferencesStore()
        val oldToken = historyRecoveryNoticeToken(status, candidateCount = 2)
        val newToken = historyRecoveryNoticeToken(status, candidateCount = 3)

        preferences.acknowledgeHistoryRecoveryBanner(A_ROOM_ID.value, oldToken)

        assertThat(preferences.isHistoryRecoveryBannerAcknowledged(A_ROOM_ID.value, oldToken).first()).isTrue()
        assertThat(preferences.isHistoryRecoveryBannerAcknowledged(A_ROOM_ID.value, newToken).first()).isFalse()
    }

    @Test
    fun `present - handle action unpin`() = runTest {
        val failureUnpinEventLambda = lambdaRecorder { _: EventId -> Result.failure<Boolean>(AN_EXCEPTION) }
        val timeline = FakeTimeline()
        val analyticsService = FakeAnalyticsService()
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        room.givenRoomInfo(aRoomInfo(pinnedEventIds = listOf(AN_EVENT_ID)))
        val successUnpinEventLambda = lambdaRecorder { _: EventId ->
            room.givenRoomInfo(aRoomInfo(pinnedEventIds = emptyList()))
            Result.success(true)
        }
        val presenter = createMessagesPresenter(
            timeline = timeline,
            joinedRoom = room,
            analyticsService = analyticsService
        )
        presenter.testWithLifecycleOwner {
            val messageEvent = aMessageEvent(
                content = aTimelineItemTextContent()
            )
            val initialState = awaitItem()

            timeline.unpinEventLambda = successUnpinEventLambda
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Unpin, messageEvent))
            assert(successUnpinEventLambda).isCalledOnce().with(value(messageEvent.eventId))

            timeline.unpinEventLambda = failureUnpinEventLambda
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Unpin, messageEvent))
            assert(failureUnpinEventLambda).isCalledOnce().with(value(messageEvent.eventId))
            val failureState = consumeItemsUntilPredicate { it.snackbarMessage != null }.last()
            assertThat(failureState.snackbarMessage).isNotNull()
            assertThat(analyticsService.capturedEvents).containsExactly(
                PinUnpinAction(kind = PinUnpinAction.Kind.Unpin, from = PinUnpinAction.From.Timeline),
                PinUnpinAction(kind = PinUnpinAction.Kind.Unpin, from = PinUnpinAction.From.Timeline)
            )
        }
    }

    @Test
    fun `present - handle action edit caption`() = runTest {
        val messageEvent = aMessageEvent(
            content = aTimelineItemImageContent(
                caption = A_CAPTION,
            )
        )
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.EditCaption, messageEvent))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.EditCaption(
                        eventOrTransactionId = AN_EVENT_ID.toEventOrTransactionId(),
                        content = A_CAPTION,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action add caption`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        val messageEvent = aMessageEvent(
            content = aTimelineItemImageContent(
                caption = null,
            )
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.AddCaption, messageEvent))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.EditCaption(
                        eventOrTransactionId = AN_EVENT_ID.toEventOrTransactionId(),
                        content = "",
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle action remove caption`() = runTest {
        val messageEvent = aMessageEvent(
            content = aTimelineItemImageContent(
                caption = A_CAPTION,
            )
        )
        val editCaptionLambda = lambdaRecorder { _: EventOrTransactionId, _: String?, _: String? -> Result.success(Unit) }
        val timeline = FakeTimeline().apply {
            this.editCaptionLambda = editCaptionLambda
        }
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
            ),
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(
            joinedRoom = room,
            timeline = timeline,
        )
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.RemoveCaption, messageEvent))
            editCaptionLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID.toEventOrTransactionId()), value(null), value(null))
        }
    }

    @Test
    fun `present - handle action view in timeline, it should have no effect`() = runTest {
        val messageEvent = aMessageEvent(
            content = aTimelineItemTextContent()
        )
        val presenter = createMessagesPresenter()
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.ViewInTimeline, messageEvent))
            // No op!
        }
    }

    @Test
    fun `present - room with successor room includes successor info in state`() = runTest {
        val successorRoomId = RoomId("!successor:server.org")
        val successorReason = "This room has been moved to a new location"
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                initialRoomInfo = aRoomInfo(
                    successorRoom = SuccessorRoom(
                        roomId = successorRoomId,
                        reason = successorReason
                    )
                )
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            assertThat(initialState.successorRoom).isNotNull()
            assertThat(initialState.successorRoom?.roomId).isEqualTo(successorRoomId)
            assertThat(initialState.successorRoom?.reason).isEqualTo(successorReason)
        }
    }

    @Test
    fun `present - room without successor room has null successor info in state`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = roomPermissions(),
                initialRoomInfo = aRoomInfo(successorRoom = null)
            ),
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createMessagesPresenter(joinedRoom = room)
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            assertThat(initialState.successorRoom).isNull()
        }
    }

    @Test
    fun `present - when room is encrypted and a DM, the DM user's identity state is fetched onResume`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomPermissions = FakeRoomPermissions(
                    canSendState = { true },
                    canSendMessage = { true },
                    canRedactOther = true,
                    canRedactOwn = true,
                    canPinUnpin = true,
                ),
                initialRoomInfo = aRoomInfo(isDm = true, isEncrypted = true)
            ).apply {
                givenRoomMembersState(RoomMembersState.Ready(persistentListOf(aRoomMember(userId = A_SESSION_ID), aRoomMember(userId = A_USER_ID_2))))
            },
            typingNoticeResult = { Result.success(Unit) },
        )
        val encryptionService = FakeEncryptionService(getUserIdentityResult = { Result.success(IdentityState.Verified) })

        val presenter = createMessagesPresenter(joinedRoom = room, encryptionService = encryptionService)
        val lifecycleOwner = FakeLifecycleOwner()
        presenter.testWithLifecycleOwner(lifecycleOwner) {
            val initialState = awaitItem()
            assertThat(initialState.dmUserVerificationState).isNull()

            skipItems(1)
            ensureAllEventsConsumed()

            lifecycleOwner.givenState(Lifecycle.State.RESUMED)
            assertThat(awaitItem().dmUserVerificationState).isEqualTo(IdentityState.Verified)
        }
    }

    @Test
    fun `present - handle action reply in thread for an event in a thread`() = runTest {
        val openThreadLambda = lambdaRecorder { _: ThreadId, _: EventId? -> }
        val presenter = createMessagesPresenter(
            navigator = FakeMessagesNavigator(onOpenThreadLambda = openThreadLambda),
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.Threads.key to true)
            ),
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(
                MessagesEvent.HandleAction(
                    action = TimelineItemAction.ReplyInThread,
                    event = aMessageEvent(threadInfo = TimelineItemThreadInfo.ThreadResponse(A_THREAD_ID))
                )
            )
            awaitItem()
            openThreadLambda.assertions().isCalledOnce().with(value(A_THREAD_ID), value(null))
        }
    }

    @Test
    fun `present - handle action reply in thread to start a new thread`() = runTest {
        val openThreadLambda = lambdaRecorder { _: ThreadId, _: EventId? -> }
        val presenter = createMessagesPresenter(
            navigator = FakeMessagesNavigator(onOpenThreadLambda = openThreadLambda),
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.Threads.key to true)
            ),
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(
                MessagesEvent.HandleAction(
                    action = TimelineItemAction.ReplyInThread,
                    event = aMessageEvent(
                        // The event id will be used as the thread id instead
                        eventId = AN_EVENT_ID,
                        threadInfo = null,
                    )
                )
            )
            awaitItem()
            openThreadLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID.toThreadId()), value(null))
        }
    }

    @Test
    fun `present - handle action reply in a thread with threads disabled`() = runTest {
        val composerRecorder = EventsRecorder<MessageComposerEvent>()
        val presenter = createMessagesPresenter(
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.Threads.key to false)
            ),
            messageComposerPresenter = { aMessageComposerState(eventSink = composerRecorder) },
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.HandleAction(TimelineItemAction.ReplyInThread, aMessageEvent()))
            awaitItem()
            composerRecorder.assertSingle(
                MessageComposerEvent.SetMode(
                    composerMode = MessageComposerMode.Reply(
                        replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
                        hideImage = false,
                    )
                )
            )
        }
    }

    @Test
    fun `present - handle MarkAsFullyReadAndExit marks the room as fully read and navigates up`() = runTest {
        val markAsFullyReadRecorder = lambdaRecorder<RoomId, EventId, Unit> { _, _ -> }
        val markAsFullyReadUseCase = FakeMarkAsFullyRead(markAsFullyReadRecorder)
        val closeLambda = lambdaRecorder<Unit> {}
        val navigator = FakeMessagesNavigator(closeLambda = closeLambda)

        val presenter = createMessagesPresenter(
            timeline = FakeTimeline(getLatestEventIdResult = { Result.success(AN_EVENT_ID) }),
            markAsFullyRead = markAsFullyReadUseCase,
            navigator = navigator,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.MarkAsFullyReadAndExit)

            runCurrent()

            markAsFullyReadRecorder.assertions().isCalledOnce()
            closeLambda.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - handle MarkAsFullyReadAndExit still navigates up if marking as read fails`() = runTest {
        val markAsFullyReadUseCase = FakeMarkAsFullyRead { _, _ -> error("boom") }
        val closeLambda = lambdaRecorder<Unit> {}
        val navigator = FakeMessagesNavigator(closeLambda = closeLambda)

        val presenter = createMessagesPresenter(
            timeline = FakeTimeline(getLatestEventIdResult = { Result.success(AN_EVENT_ID) }),
            markAsFullyRead = markAsFullyReadUseCase,
            navigator = navigator,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink(MessagesEvent.MarkAsFullyReadAndExit)

            runCurrent()

            closeLambda.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - shows a history icon if the room is encrypted and history is shared`() = runTest {
        val presenter = createMessagesPresenter(
            joinedRoom = FakeJoinedRoom(
                baseRoom = FakeBaseRoom(
                    roomPermissions = roomPermissions(),
                    initialRoomInfo = aRoomInfo(isEncrypted = true, historyVisibility = RoomHistoryVisibility.Shared),
                ),
            ),
        )
        presenter.testWithLifecycleOwner {
            awaitItem()
            runCurrent()
            val state = awaitItem()
            assertThat(state.topBarSharedHistoryIcon).isEqualTo(SharedHistoryIcon.SHARED)
        }
    }

    @Test
    fun `present - shows a 'world_readable' icon if the room is encrypted and history is world_readable`() = runTest {
        val presenter = createMessagesPresenter(
            joinedRoom = FakeJoinedRoom(
                baseRoom = FakeBaseRoom(
                    roomPermissions = roomPermissions(),
                    initialRoomInfo = aRoomInfo(isEncrypted = true, historyVisibility = RoomHistoryVisibility.WorldReadable),
                ),
            ),
        )
        presenter.testWithLifecycleOwner {
            awaitItem()
            runCurrent()
            val state = awaitItem()
            assertThat(state.topBarSharedHistoryIcon).isEqualTo(SharedHistoryIcon.WORLD_READABLE)
        }
    }

    @Test
    fun `present - only has threads enabled if the feature flag is on`() = runTest {
        val itemsFlow = MutableStateFlow(listOf(aThreadListItem()))
        val room = FakeJoinedRoom(
            threadsListService = FakeThreadsListService(items = itemsFlow)
        )
        val featureFlagService = FakeFeatureFlagService(
            initialState = mapOf(FeatureFlags.Threads.key to false)
        )
        val presenter = createMessagesPresenter(
            joinedRoom = room,
            featureFlagService = featureFlagService
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            // The feature flag is disabled, so even if the thread list has items, it will return it doesn't have any
            assertThat(initialState.threads.hasThreads).isFalse()

            // Enable the feature flag, now it should reflect the thread list state
            featureFlagService.setFeatureEnabled(FeatureFlags.RoomThreadList, true)
            skipItems(1)
            assertThat(awaitItem().threads.hasThreads).isTrue()

            // And if we remove the items, it should update accordingly
            itemsFlow.value = emptyList()
            assertThat(awaitItem().threads.hasThreads).isFalse()
        }
    }

    private fun roomPermissions(
        canStartCall: Boolean = true,
        canRedactOther: Boolean = true,
        canRedactOwn: Boolean = true,
        canSendMessage: Boolean = true,
        canSendReaction: Boolean = true,
        canPinUnpin: Boolean = true,
    ) = FakeRoomPermissions(
        canSendState = { type ->
            when (type) {
                StateEventType.CallMember -> canStartCall
                else -> lambdaError()
            }
        },
        canSendMessage = { type ->
            when (type) {
                MessageEventType.RoomMessage -> canSendMessage
                MessageEventType.Reaction -> canSendReaction
                else -> lambdaError()
            }
        },
        canRedactOther = canRedactOther,
        canRedactOwn = canRedactOwn,
        canPinUnpin = canPinUnpin,
    )

    @Test
    fun `previous SDK failure retries through the action menu entry point`() = runTest {
        val called = kotlinx.coroutines.CompletableDeferred<Unit>()
        val presenter = createMessagesPresenter()
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            val event = aMessageEvent(
                sendState = io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState.Failed.Unknown("previous failure"),
                sendHandleProvider = io.element.android.libraries.matrix.api.timeline.item.event.SendHandleProvider {
                    io.element.android.libraries.matrix.api.core.SendHandle { called.complete(Unit); Result.success(Unit) }
                },
            )
            initial.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Retry, event))
            called.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paused current room stays visible and Stop clears it without restarting capture`() = runTest {
        var stops = 0
        val manager = FakeActiveLiveLocationShareManager(stopShareLambda = { stops++; Result.success(Unit) })
        manager.givenPausedShare(A_ROOM_ID, io.element.android.features.location.api.live.PausedLiveLocationShare.RECOVERABLE)
        val presenter = createMessagesPresenter(liveLocationShareManager = manager)
        presenter.testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            assertThat(state.showLiveLocationShareBanner).isTrue()
            assertThat(state.pausedLiveLocationShare).isEqualTo(io.element.android.features.location.api.live.PausedLiveLocationShare.RECOVERABLE)
            assertThat(manager.sharingRoomIds.value).isEmpty()
            state.eventSink(MessagesEvent.StopLiveLocationShare)
            advanceUntilIdle()
            val stoppedState = consumeItemsUntilTimeout().last()
            assertThat(stoppedState.showLiveLocationShareBanner).isFalse()
            assertThat(stoppedState.pausedLiveLocationShare).isNull()
            assertThat(stops).isEqualTo(1)
            assertThat(manager.pausedShares.value).isEmpty()
            assertThat(manager.sharingRoomIds.value).isEmpty()
        }
    }

    @Test
    fun `pause from another room is not projected into current chat`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(RoomId("!other:matrix.org"), io.element.android.features.location.api.live.PausedLiveLocationShare.RECOVERABLE)
        createMessagesPresenter(liveLocationShareManager = manager).testWithLifecycleOwner {
            val state = consumeItemsUntilTimeout().last()
            assertThat(state.showLiveLocationShareBanner).isFalse()
            assertThat(state.pausedLiveLocationShare).isNull()
        }
    }

    private fun TestScope.createMessagesPresenter(
        coroutineDispatchers: CoroutineDispatchers = testCoroutineDispatchers(),
        timeline: Timeline = FakeTimeline(),
        joinedRoom: FakeJoinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = FakeRoomPermissions(
                    canSendState = { true },
                    canSendMessage = { true },
                    canRedactOther = true,
                    canRedactOwn = true,
                    canPinUnpin = true,
                ),
            ).apply {
                givenRoomInfo(aRoomInfo(id = roomId, name = ""))
            },
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        ),
        navigator: FakeMessagesNavigator = FakeMessagesNavigator(),
        clipboardHelper: FakeClipboardHelper = FakeClipboardHelper(),
        analyticsService: FakeAnalyticsService = FakeAnalyticsService(),
        timelineEventSink: (TimelineEvent) -> Unit = {},
        permalinkParser: PermalinkParser = FakePermalinkParser(),
        messageComposerPresenter: Presenter<MessageComposerState> = Presenter {
            aMessageComposerState(
                // Use TextEditorState.Markdown, so that we can request focus manually.
                textEditorState = aTextEditorStateMarkdown(initialText = "", initialFocus = false)
            )
        },
        roomMemberModerationPresenter: Presenter<RoomMemberModerationState> = Presenter {
            aRoomMemberModerationState()
        },
        encryptionService: FakeEncryptionService = FakeEncryptionService(),
        featureFlagService: FakeFeatureFlagService = FakeFeatureFlagService(),
        actionListEventSink: (ActionListEvent) -> Unit = {},
        addRecentEmoji: AddRecentEmoji = AddRecentEmoji { _ -> lambdaError() },
        markAsFullyRead: MarkAsFullyRead = FakeMarkAsFullyRead(),
        liveLocationShareManager: FakeActiveLiveLocationShareManager = FakeActiveLiveLocationShareManager(),
        messageReferenceStore: FakeMessageReferenceStore = FakeMessageReferenceStore(),
        friendProfileRepository: FriendProfileRepository = FakeFriendProfileRepository(),
        sessionPreferencesStore: InMemorySessionPreferencesStore = InMemorySessionPreferencesStore(),
        timelinePresenter: Presenter<io.element.android.features.messages.impl.timeline.TimelineState> = Presenter {
            aTimelineState(eventSink = timelineEventSink)
        },
        appOperationalStateCoordinator: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        sessionCoroutineScope: CoroutineScope = backgroundScope,
    ): MessagesPresenter {
        return MessagesPresenter(
            navigator = navigator,
            room = joinedRoom,
            composerPresenter = messageComposerPresenter,
            voiceMessageComposerPresenterFactory = FakeDefaultVoiceMessageComposerPresenterFactory(backgroundScope),
            timelinePresenter = timelinePresenter,
            timelineProtectionPresenter = { aTimelineProtectionState() },
            identityChangeStatePresenter = { anIdentityChangeState() },
            linkPresenter = { aLinkState() },
            actionListPresenter = { anActionListState(eventSink = actionListEventSink) },
            customReactionPresenter = { aCustomReactionState() },
            reactionSummaryPresenter = { aReactionSummaryState() },
            readReceiptBottomSheetPresenter = { aReadReceiptBottomSheetState() },
            pinnedMessagesBannerPresenter = { aLoadedPinnedMessagesBannerState() },
            roomCallStatePresenter = { aStandByCallState() },
            roomMemberModerationPresenter = roomMemberModerationPresenter,
            snackbarDispatcher = SnackbarDispatcher(),
            dispatchers = coroutineDispatchers,
            clipboardHelper = clipboardHelper,
            htmlConverterProvider = FakeHtmlConverterProvider(),
            buildMeta = aBuildMeta(),
            timelineController = TimelineController(joinedRoom, timeline),
            permalinkParser = permalinkParser,
            analyticsService = analyticsService,
            encryptionService = encryptionService,
            featureFlagService = featureFlagService,
            addRecentEmoji = addRecentEmoji,
            markAsFullyRead = markAsFullyRead,
            liveLocationShareManager = liveLocationShareManager,
            messageReferenceStore = messageReferenceStore,
            friendProfileRepository = friendProfileRepository,
            sessionPreferencesStore = sessionPreferencesStore,
            sessionCoroutineScope = sessionCoroutineScope,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            matrixClient = io.element.android.libraries.matrix.test.FakeMatrixClient(sessionId = joinedRoom.sessionId),
        )
    }
}

private fun protectedClipboardSource(authorizationRevision: Long): MessageClipboardSource.Protected =
    MessageClipboardSource.Protected(
        sessionId = A_SESSION_ID.value,
        roomId = A_ROOM_ID.value,
        eventId = AN_EVENT_ID.value,
        contentVersion = "ciphertext:policy:envelope",
        contentIds = persistentSetOf("content-a"),
        authorizationRevision = authorizationRevision,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )
