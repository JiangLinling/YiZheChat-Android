/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.compose.runtime.remember
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import im.vector.app.features.analytics.plan.Composer
import im.vector.app.features.analytics.plan.Interaction
import io.element.android.features.location.api.LocationService
import io.element.android.features.location.test.FakeLocationService
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.MessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.draft.ComposerDraftService
import io.element.android.features.messages.impl.draft.FakeComposerDraftService
import io.element.android.features.messages.impl.messagecomposer.suggestions.SuggestionsProcessor
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.utils.FakeMentionSpanFormatter
import io.element.android.features.messages.impl.utils.FakeTextPillificationHelper
import io.element.android.features.messages.impl.utils.TextPillificationHelper
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.test.ANOTHER_MESSAGE
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_CAPTION
import io.element.android.libraries.matrix.test.A_MESSAGE
import io.element.android.libraries.matrix.test.A_REPLY
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_TRANSACTION_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.A_USER_ID_3
import io.element.android.libraries.matrix.test.A_USER_ID_4
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.permalink.FakePermalinkBuilder
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.room.powerlevels.FakeRoomPermissions
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aProfileDetails
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaviewer.test.FakeLocalMediaFactory
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.push.test.notifications.conversations.FakeNotificationConversationService
import io.element.android.libraries.slashcommands.api.SlashCommand
import io.element.android.libraries.slashcommands.api.SlashCommandService
import io.element.android.libraries.slashcommands.test.FakeSlashCommandService
import io.element.android.libraries.textcomposer.mentions.MentionSpanProvider
import io.element.android.libraries.textcomposer.mentions.MentionSpanTheme
import io.element.android.libraries.textcomposer.mentions.ResolvedSuggestion
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.Suggestion
import io.element.android.libraries.textcomposer.model.SuggestionType
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.libraries.mediapickers.api.MediaPickCancellation
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.waitForPredicate
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import io.element.android.features.messages.impl.commoninfo.CommonInfoItem
import io.element.android.features.messages.impl.commoninfo.CommonInfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wysiwyg_composer.MentionsState
import java.io.File

@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class MessageComposerPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val pickerProvider = FakePickerProvider().apply {
        givenResult(Uri.parse("content://media/external/images/media/test"))
    }
    private val mediaPreProcessor = FakeMediaPreProcessor()
    private val snackbarDispatcher = SnackbarDispatcher()
    private val mockMediaUrl: Uri = Uri.parse("content://media/external/images/media/local-test")
    private val localMediaFactory = FakeLocalMediaFactory(mockMediaUrl)
    private val analyticsService = FakeAnalyticsService()
    private val notificationConversationService = FakeNotificationConversationService()

    @Test
    fun `FC002 current text send reconciles its bound draft without reopening the room`() = runTest {
        var bound: ComposerDraft? = null
        var matrixSent = false
        var reconciliations = 0
        val drafts = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, _ -> bound = draft }
            reconcileSentTextDraftLambda = {
                reconciliations++
                if (matrixSent) bound?.let { io.element.android.features.messages.impl.draft.ConsumedTextDraft("sent-version", it) }.also { bound = null }
                else null
            }
        }
        val timeline = FakeTimeline().apply { sendMessageLambda = { _, _, _, _, _ -> matrixSent = true; Result.success(Unit) } }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            draftService = drafts, isRichTextEditorEnabled = false,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("sample")
            state.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()
            assertThat(reconciliations).isAtLeast(1)
            assertThat(bound).isNull()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FC002 later edit fences current send draft consumption`() = runTest {
        val queried = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var bound: ComposerDraft? = null
        val drafts = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, _ -> bound = draft }
            reconcileSentTextDraftLambda = {
                queried.complete(Unit)
                release.await()
                bound?.let { io.element.android.features.messages.impl.draft.ConsumedTextDraft("sent-version", it) }
            }
        }
        val timeline = FakeTimeline().apply { sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) } }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            draftService = drafts, isRichTextEditorEnabled = false,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("sent")
            state.eventSink(MessageComposerEvent.SendMessage)
            queried.await()
            state.textEditorState.setMarkdown("later edit")
            state.eventSink(MessageComposerEvent.TypingNotice(true))
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("later edit")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BUG041 durable intent and original draft precede editor clear`() = runTest {
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored: ComposerDraft? = null
        val drafts = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, value, _ -> stored = value }
        }
        val timeline = FakeTimeline().apply { sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) } }
        val room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })
        val outbox = object : io.element.android.security.contentprotection.api.OfflineOutboxService {
            override suspend fun stage(message: io.element.android.security.contentprotection.api.OfflineTextOutboxMessage): Boolean {
                staged.complete(Unit)
                release.await()
                return true
            }
            override suspend fun messages(accountId: String, roomId: String) = emptyList<io.element.android.security.contentprotection.api.OfflineTextOutboxMessage>()
            override suspend fun remove(accountId: String, roomId: String, transactionId: String) = true
        }
        val presenter = createPresenter(room = room, draftService = drafts,
            timelineController = TimelineController(room, timeline, offlineOutboxService = outbox),
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand }))
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            staged.await()
            try {
                // Simulated process interruption at the durable handoff, before the 500ms save timer.
                assertThat(stored?.plainText).isEqualTo(A_MESSAGE)
                assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            } finally { release.complete(Unit) }
            advanceUntilIdle()
            assertThat(state.textEditorState.messageHtml()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BUG041 original restored editor is cleared by its consumed storage version`() = runTest {
        val queried = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        var once = true
        val saved = mutableListOf<ComposerDraft?>()
        val drafts = FakeComposerDraftService().apply {
            loadDraftLambda = { _, _, _ -> original }
            saveDraftLambda = { _, _, draft, _ -> saved += draft }
            textDraftVersionLambda = { "original-version" }
            reconcileSentTextDraftLambda = {
                if (once) {
                    once = false
                    queried.complete(Unit)
                    release.await()
                    io.element.android.features.messages.impl.draft.ConsumedTextDraft("original-version", original)
                } else null
            }
        }
        val presenter = createPresenter(draftService = drafts)
        presenter.test {
            val state = awaitFirstItem()
            queried.await()
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            // Programmatic restoration may emit a typing notice. That is not a newer user edit.
            state.eventSink(MessageComposerEvent.TypingNotice(true))
            state.eventSink(MessageComposerEvent.ToggleFullScreenState)
            // Background/pause saves without an edit must keep the original pending version.
            state.eventSink(MessageComposerEvent.SaveDraft)
            runCurrent()
            assertThat(saved).isEmpty()
            release.complete(Unit)
            advanceUntilIdle()
            // The rich editor consumes a queued view action outside the test scheduler.
            waitForPredicate { state.textEditorState.messageHtml() == "" }
            assertThat(state.textEditorState.messageHtml()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BUG041 unsaved later identical user edit survives original receipt`() = runTest {
        val queried = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        var once = true
        val saved = mutableListOf<ComposerDraft?>()
        val drafts = FakeComposerDraftService().apply {
            loadDraftLambda = { _, _, _ -> original }
            saveDraftLambda = { _, _, draft, _ -> saved += draft }
            textDraftVersionLambda = { "original-version" }
            reconcileSentTextDraftLambda = {
                if (once) {
                    once = false
                    queried.complete(Unit)
                    release.await()
                    io.element.android.features.messages.impl.draft.ConsumedTextDraft("original-version", original)
                } else null
            }
        }
        val presenter = createPresenter(draftService = drafts)
        presenter.test {
            val state = awaitFirstItem()
            queried.await()
            state.textEditorState.setHtml("")
            state.eventSink(MessageComposerEvent.TypingNotice(false))
            runCurrent()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.TypingNotice(true))
            runCurrent()
            state.eventSink(MessageComposerEvent.SaveDraft)
            runCurrent()
            assertThat(saved.last()?.plainText).isEqualTo(A_MESSAGE)
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BUG041 identical new input while initial draft query waits keeps its user ownership`() = runTest {
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        val saved = mutableListOf<ComposerDraft?>()
        val base = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, _ -> saved += draft }
            textDraftVersionLambda = { "original-version" }
            reconcileSentTextDraftLambda = {
                io.element.android.features.messages.impl.draft.ConsumedTextDraft("original-version", original)
            }
        }
        val drafts = object : ComposerDraftService by base {
            override suspend fun loadDraft(roomId: RoomId, threadRoot: ThreadId?, isVolatile: Boolean): ComposerDraft? {
                loading.complete(Unit)
                release.await()
                return original
            }
        }
        createPresenter(draftService = drafts).test {
            val state = awaitFirstItem()
            loading.await()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.TypingNotice(true))
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            assertThat(saved.last()?.plainText).isEqualTo(A_MESSAGE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.commonInfoRepository).isNotNull()
            assertThat(initialState.isFullScreen).isFalse()
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
            assertThat(initialState.mode).isEqualTo(MessageComposerMode.Normal)
            assertThat(initialState.showAttachmentSourcePicker).isFalse()
            assertThat(initialState.canShareLocation).isTrue()
            assertThat(initialState.slashCommandAction).isEqualTo(AsyncAction.Uninitialized)
        }
    }

    @Test
    fun `present - toggle fullscreen`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(MessageComposerEvent.ToggleFullScreenState)
            val fullscreenState = awaitItem()
            assertThat(fullscreenState.isFullScreen).isTrue()
            fullscreenState.eventSink.invoke(MessageComposerEvent.ToggleFullScreenState)
            val notFullscreenState = awaitItem()
            assertThat(notFullscreenState.isFullScreen).isFalse()
        }
    }

    @Test
    fun `present - change message`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setHtml(A_MESSAGE)
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            initialState.textEditorState.setHtml("")
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
        }
    }

    @Test
    fun `present - change mode to edit`() = runTest {
        val loadDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: Boolean ->
            ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        }
        val updateDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: ComposerDraft?, _: Boolean -> }
        val draftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
            this.saveDraftLambda = updateDraftLambda
        }
        val presenter = createPresenter(
            draftService = draftService,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            var state = awaitFirstItem()
            val mode = anEditMode(message = ANOTHER_MESSAGE)
            state.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            assertThat(state.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            state = backToNormalMode(state)
            // The message that was being edited is cleared and volatile draft is loaded
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)

            assert(loadDraftLambda)
                .isCalledExactly(2)
                .withSequence(
                    // Automatic load of draft
                    listOf(value(A_ROOM_ID), value(null), value(false)),
                    // Load of volatile draft when closing edit mode
                    listOf(value(A_ROOM_ID), value(null), value(true))
                )

            assert(updateDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), any(), value(true))
        }
    }

    @Test
    fun `present - change mode to edit caption`() = runTest {
        val loadDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: Boolean ->
            ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        }
        val updateDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: ComposerDraft?, _: Boolean -> }
        val draftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
            this.saveDraftLambda = updateDraftLambda
        }
        val presenter = createPresenter(
            draftService = draftService,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            var state = awaitFirstItem()
            val mode = anEditCaptionMode(caption = A_CAPTION)
            state.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_CAPTION)
            state = backToNormalMode(state)
            // The caption that was being edited is cleared and volatile draft is loaded
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)

            assert(loadDraftLambda)
                .isCalledExactly(2)
                .withSequence(
                    // Automatic load of draft
                    listOf(value(A_ROOM_ID), value(null), value(false)),
                    // Load of volatile draft when closing edit mode
                    listOf(value(A_ROOM_ID), value(null), value(true))
                )
            assert(updateDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), any(), value(true))
        }
    }

    @Test
    fun `present - change mode to edit caption and send the caption`() = runTest {
        val savedDrafts = mutableListOf<Pair<ComposerDraft?, Boolean>>()
        val clearedVersions = mutableListOf<String?>()
        val draftService = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, isVolatile -> savedDrafts += draft to isVolatile }
            captureProtectedCaptionDraftVersionLambda = { _, _ -> "draft-v1" }
            clearProtectedCaptionDraftIfVersionLambda = { _, _, version ->
                clearedVersions += version
                true
            }
        }
        val editCaptionLambda = lambdaRecorder { _: EventOrTransactionId, _: String?, _: String? ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.editCaptionLambda = editCaptionLambda
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(
            room = joinedRoom,
            isRichTextEditorEnabled = false,
            draftService = draftService,
        )
        val permalinkBuilder = FakePermalinkBuilder(permalinkForUserLambda = { Result.success("") })
        presenter.test {
            var state = awaitFirstItem()
            val mode = anEditCaptionMode(caption = A_CAPTION)
            state.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_CAPTION)
            state.eventSink.invoke(MessageComposerEvent.SendMessage)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo("")
            waitForPredicate { analyticsService.capturedEvents.size == 1 }
            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = true,
                    isReply = false,
                    messageType = Composer.MessageType.Text,
                )
            )
            assert(editCaptionLambda)
                .isCalledOnce()
                .with(value(AN_EVENT_ID.toEventOrTransactionId()), value(A_CAPTION), value(null))
            assertThat(clearedVersions).containsExactly("draft-v1")
        }
    }

    @Test
    fun `present - successful caption edit does not clear a replacement draft`() = runTest {
        val draftService = FakeComposerDraftService().apply {
            captureProtectedCaptionDraftVersionLambda = { _, _ -> "draft-v1" }
            clearProtectedCaptionDraftIfVersionLambda = { _, _, expected ->
                assertThat(expected).isEqualTo("draft-v1")
                false // A controlled save-vs-clear interleaving installed draft-v2.
            }
        }
        val timeline = FakeTimeline().apply {
            editCaptionLambda = { _, _, _ -> Result.success(Unit) }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            isRichTextEditorEnabled = false,
            draftService = draftService,
        )
        val permalinkBuilder = FakePermalinkBuilder(permalinkForUserLambda = { Result.success("") })
        presenter.test {
            var state = awaitFirstItem()
            val mode = anEditCaptionMode(caption = A_CAPTION)
            state.eventSink(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            state.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()
            // A rejected versioned clear deliberately leaves the composer unchanged.
            expectNoEvents()
            val current = state
            assertThat(current.mode).isEqualTo(mode)
            assertThat(current.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_CAPTION)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - clear success fences a SaveDraft queued with the sent caption`() = runTest {
        val clearStarted = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        val savedDrafts = mutableListOf<ComposerDraft?>()
        val draftService = FakeComposerDraftService().apply {
            captureProtectedCaptionDraftVersionLambda = { _, _ -> "draft-v1" }
            clearProtectedCaptionDraftIfVersionLambda = { _, _, _ ->
                clearStarted.complete(Unit)
                allowClear.await()
                true
            }
            saveDraftLambda = { _, _, draft, _ -> savedDrafts += draft }
        }
        val timeline = FakeTimeline().apply {
            editCaptionLambda = { _, _, _ -> Result.success(Unit) }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            isRichTextEditorEnabled = false,
            draftService = draftService,
        )
        presenter.test {
            var state = awaitFirstItem()
            state.eventSink(MessageComposerEvent.SetMode(anEditCaptionMode(caption = A_CAPTION)))
            state = awaitItem()
            state.eventSink(MessageComposerEvent.SendMessage)
            clearStarted.await()
            // Captures the still-visible sent caption, then queues behind the clear/reset mutex.
            state.eventSink(MessageComposerEvent.SaveDraft)
            allowClear.complete(Unit)
            advanceUntilIdle()
            assertThat(savedDrafts.filter { it?.draftType is ComposerDraftType.EditCaption }).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - old cleanup cannot reset a new same-text caption edit generation`() = runTest {
        val clearStarted = CompletableDeferred<Unit>()
        val allowOldClear = CompletableDeferred<Unit>()
        val savedDrafts = mutableListOf<ComposerDraft?>()
        val draftService = FakeComposerDraftService().apply {
            captureProtectedCaptionDraftVersionLambda = { _, _ -> "draft-v1" }
            clearProtectedCaptionDraftIfVersionLambda = { _, _, _ ->
                clearStarted.complete(Unit)
                allowOldClear.await()
                true
            }
            saveDraftLambda = { _, _, draft, _ -> savedDrafts += draft }
        }
        val timeline = FakeTimeline().apply {
            editCaptionLambda = { _, _, _ -> Result.success(Unit) }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            isRichTextEditorEnabled = false,
            draftService = draftService,
        )
        presenter.test {
            var state = awaitFirstItem()
            val sameTextMode = anEditCaptionMode(caption = A_CAPTION)
            state.eventSink(MessageComposerEvent.SetMode(sameTextMode))
            state = awaitItem()
            state.eventSink(MessageComposerEvent.SendMessage)
            clearStarted.await()

            // Starts a distinct edit generation while old send cleanup is suspended.
            state.eventSink(MessageComposerEvent.SetMode(sameTextMode))
            allowOldClear.complete(Unit)
            advanceUntilIdle()
            // Equal mode/text need not emit a new Compose state; generation is internal.
            expectNoEvents()
            val newSession = state
            assertThat(newSession.mode).isEqualTo(sameTextMode)
            assertThat(newSession.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo(A_CAPTION)
            newSession.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()
            assertThat(savedDrafts.filter { it?.draftType is ComposerDraftType.EditCaption }).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - change mode to reply after edit`() = runTest {
        val loadDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: Boolean ->
            ComposerDraft(A_MESSAGE, A_MESSAGE, ComposerDraftType.NewMessage)
        }
        val updateDraftLambda = lambdaRecorder { _: RoomId, _: ThreadId?, _: ComposerDraft?, _: Boolean -> }
        val draftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
            this.saveDraftLambda = updateDraftLambda
        }
        val presenter = createPresenter(
            draftService = draftService,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            var state = awaitFirstItem()
            val editMode = anEditMode(message = ANOTHER_MESSAGE)
            state.eventSink.invoke(MessageComposerEvent.SetMode(editMode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(editMode)
            assertThat(state.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)

            val replyMode = aReplyMode()
            state.eventSink.invoke(MessageComposerEvent.SetMode(replyMode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(replyMode)
            assertThat(state.textEditorState.messageHtml()).isEmpty()

            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))

            assert(updateDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), any(), value(true))
        }
    }

    @Test
    fun `present - change mode to reply`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            var state = awaitFirstItem()
            val mode = aReplyMode()
            state.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            assertThat(state.textEditorState.messageHtml()).isEqualTo("")
            backToNormalMode(state)
        }
    }

    @Test
    fun `present - cancel reply`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            var state = awaitFirstItem()
            val mode = aReplyMode()
            state.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            state.textEditorState.setHtml(A_REPLY)
            state = backToNormalMode(state)

            // The message typed while replying is not cleared
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_REPLY)
        }
    }

    @Test
    fun `present - send message with rich text enabled`() = runTest {
        val presenter = createPresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
                },
                typingNoticeResult = { Result.success(Unit) }
            ),
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setHtml(A_MESSAGE)
            val withMessageState = awaitItem()
            assertThat(withMessageState.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            withMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageHtml()).isEqualTo("")
            waitForPredicate { analyticsService.capturedEvents.size == 1 }
            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = false,
                    isReply = false,
                    messageType = Composer.MessageType.Text,
                )
            )
        }
    }

    @Test
    fun `present - send retains room before lifecycle admission and releases after enqueue`() = runTest {
        var retained = false
        var closing = false
        var destroyed = false
        var sent = 0
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { _, _, _, _, _ ->
                check(retained && !destroyed)
                sent++
                Result.success(Unit)
            }
        }
        val room = object : JoinedRoom by FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        ) {
            override suspend fun <T> withSendLifetime(block: suspend () -> T): T {
                check(!closing)
                retained = true
                try {
                    return block()
                } finally {
                    retained = false
                    if (closing) destroyed = true
                }
            }

            override fun destroy() {
                closing = true
                if (!retained) destroyed = true
            }
        }
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = {
            if (it.lifecycleId == io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_13) {
                // Reproduce Back during the first remote admission, before Matrix protection starts.
                room.destroy()
                check(retained && !destroyed)
            }
            null
        })
        val presenter = createPresenter(
            room = room,
            appOperationalStateCoordinator = coordinator,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand }),
        )
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()
            assertThat(sent).isEqualTo(1)
            assertThat(retained).isFalse()
            assertThat(destroyed).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - ordinary text clears before remote admission and keeps later draft`() = runTest {
        val admitted = CompletableDeferred<Unit>()
        val allowAdmission = CompletableDeferred<Unit>()
        val fakeCoordinator = FakeAppOperationalStateCoordinator()
        val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by fakeCoordinator {
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest):
                io.element.android.security.dataprotection.api.LifecycleOperation {
                if (request.trigger == "send") {
                    admitted.complete(Unit)
                    allowAdmission.await()
                }
                return fakeCoordinator.start(request)
            }
        }
        val sent = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = coordinator,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand }),
        )
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            admitted.await()
            assertThat(state.textEditorState.messageHtml()).isEmpty()
            assertThat(sent).isEmpty()
            state.textEditorState.setHtml(ANOTHER_MESSAGE)
            allowAdmission.complete(Unit)
            advanceUntilIdle()
            assertThat(sent).containsExactly(A_MESSAGE)
            assertThat(state.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            val admittedRequest = fakeCoordinator.startedRequests.single { it.trigger == "send" }
            assertThat(admittedRequest.accountIdHash).isEqualTo(accountIdHash(FakeMatrixClient().sessionId.value))
            assertThat(admittedRequest.matrixDeviceIdHash).isEqualTo(accountIdHash(FakeMatrixClient().deviceId.value))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - next text sends while previous protected transport is pending`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val allowFirst = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendMessage(
                body: String,
                htmlBody: String?,
                intentionalMentions: List<IntentionalMention>,
                msgType: MsgType,
                asPlainText: Boolean,
            ): Result<Unit> {
                sent += body
                if (body == A_MESSAGE) { firstStarted.complete(Unit); allowFirst.await() }
                return Result.success(Unit)
            }
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String,
                htmlBody: String?,
                intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?,
                msgType: MsgType,
                asPlainText: Boolean,
            ): Result<Unit> {
                return sendMessage(body, htmlBody, intentionalMentions, msgType, asPlainText)
            }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand }),
        )
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            firstStarted.await()
            state.textEditorState.setHtml(ANOTHER_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()
            assertThat(sent).containsExactly(A_MESSAGE, ANOTHER_MESSAGE).inOrder()
            allowFirst.complete(Unit)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - repeated send taps submit one message`() = runTest {
        val sendMessageResult = lambdaRecorder { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply { sendMessageLambda = sendMessageResult },
                typingNoticeResult = { Result.success(Unit) },
            ),
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )

        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setHtml(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            state.eventSink(MessageComposerEvent.SendMessage)

            advanceUntilIdle()

            sendMessageResult.assertions().isCalledOnce()
                .with(value(A_MESSAGE), any(), value(emptyList<IntentionalMention>()), value(MsgType.MSG_TYPE_TEXT), value(false))
        }
    }

    @Test
    fun `present - empty send request is ignored`() = runTest {
        val sendMessageResult = lambdaRecorder(ensureNeverCalled = true) { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply { sendMessageLambda = sendMessageResult },
                typingNoticeResult = { Result.success(Unit) },
            ),
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )

        presenter.test {
            val state = awaitFirstItem()
            state.eventSink(MessageComposerEvent.SendMessage)

            advanceUntilIdle()

            sendMessageResult.assertions().isNeverCalled()
        }
    }

    @Test
    fun `present - failed send clears composer immediately and exposes the error`() = runTest {
        val failure = IllegalStateException("Secure delivery is unavailable")
        val presenter = createPresenter(
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendMessageLambda = { _, _, _, _, _ -> Result.failure(failure) }
                },
                typingNoticeResult = { Result.success(Unit) }
            ),
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml(), state.slashCommandAction) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setHtml(A_MESSAGE)
            val withMessageState = awaitItem()
            withMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)

            var failedState = awaitItem()
            while (failedState.slashCommandAction !is AsyncAction.Failure) {
                failedState = awaitItem()
            }
            assertThat(failedState.textEditorState.messageHtml()).isEmpty()
            assertThat(failedState.slashCommandAction).isEqualTo(AsyncAction.Failure(failure))
            assertThat(analyticsService.capturedEvents).isEmpty()
        }
    }

    @Test
    fun `present - send message with plain text enabled`() = runTest {
        val permalinkBuilder = FakePermalinkBuilder(permalinkForUserLambda = { Result.success("") })
        val presenter = createPresenter(
            isRichTextEditorEnabled = false,
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
                },
                typingNoticeResult = { Result.success(Unit) }
            ),
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            val messageMarkdown = state.textEditorState.messageMarkdown(permalinkBuilder)
            remember(state, messageMarkdown) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setMarkdown(A_MESSAGE)
            val withMessageState = awaitItem()
            assertThat(withMessageState.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
            assertThat(withMessageState.textEditorState.messageHtml()).isNull()
            withMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo("")
            waitForPredicate { analyticsService.capturedEvents.size == 1 }
            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = false,
                    isReply = false,
                    messageType = Composer.MessageType.Text,
                )
            )
        }
    }

    @Test
    fun `present - edit sent message`() = runTest {
        val editMessageLambda = lambdaRecorder { _: EventOrTransactionId, _: String, _: String?, _: List<IntentionalMention> ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.editMessageLambda = editMessageLambda
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(
            joinedRoom,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
            val mode = anEditMode()
            initialState.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            val withMessageState = awaitItem()
            assertThat(withMessageState.mode).isEqualTo(mode)
            assertThat(withMessageState.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            withMessageState.textEditorState.setHtml(ANOTHER_MESSAGE)
            val withEditedMessageState = awaitItem()
            assertThat(withEditedMessageState.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            withEditedMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)
            skipItems(1)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageHtml()).isEqualTo("")

            advanceUntilIdle()

            assert(editMessageLambda)
                .isCalledOnce()
                .with(value(AN_EVENT_ID.toEventOrTransactionId()), value(ANOTHER_MESSAGE), value(ANOTHER_MESSAGE), any())

            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = true,
                    isReply = false,
                    messageType = Composer.MessageType.Text,
                )
            )
        }
    }

    @Test
    fun `present - protected edit event not found fails closed and preserves draft`() = runTest {
        val timelineEditMessageLambda = lambdaRecorder { _: EventOrTransactionId, _: String, _: String?, _: List<IntentionalMention> ->
            Result.failure<Unit>(TimelineException.EventNotFound)
        }
        val timeline = FakeTimeline().apply {
            this.editMessageLambda = timelineEditMessageLambda
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createPresenter(
            joinedRoom,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml(), state.slashCommandAction) { state }
        }.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
            val mode = anEditMode()
            initialState.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            val withMessageState = awaitItem()
            assertThat(withMessageState.mode).isEqualTo(mode)
            assertThat(withMessageState.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            withMessageState.textEditorState.setHtml(ANOTHER_MESSAGE)
            val withEditedMessageState = awaitItem()
            assertThat(withEditedMessageState.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            withEditedMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)
            advanceUntilIdle()

            val failedState = awaitItem()
            assertThat(failedState.mode).isEqualTo(mode)
            assertThat(failedState.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            assertThat(failedState.slashCommandAction).isEqualTo(AsyncAction.Failure(TimelineException.EventNotFound))

            assert(timelineEditMessageLambda)
                .isCalledOnce()
                .with(value(AN_EVENT_ID.toEventOrTransactionId()), value(ANOTHER_MESSAGE), value(ANOTHER_MESSAGE), any())

            // A protected edit that never commits must not be reported as a sent message.
            assertThat(analyticsService.capturedEvents).isEmpty()
        }
    }

    @Test
    fun `present - edit not sent message`() = runTest {
        val editMessageLambda = lambdaRecorder { _: EventOrTransactionId, _: String, _: String?, _: List<IntentionalMention> ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.editMessageLambda = editMessageLambda
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val presenter = createPresenter(
            joinedRoom,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
            val mode = anEditMode(eventOrTransactionId = A_TRANSACTION_ID.toEventOrTransactionId())
            initialState.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            val withMessageState = awaitItem()
            assertThat(withMessageState.mode).isEqualTo(mode)
            assertThat(withMessageState.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            withMessageState.textEditorState.setHtml(ANOTHER_MESSAGE)
            val withEditedMessageState = awaitItem()
            assertThat(withEditedMessageState.textEditorState.messageHtml()).isEqualTo(ANOTHER_MESSAGE)
            withEditedMessageState.eventSink.invoke(MessageComposerEvent.SendMessage)
            skipItems(1)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageHtml()).isEqualTo("")

            advanceUntilIdle()

            assert(editMessageLambda)
                .isCalledOnce()
                .with(value(A_TRANSACTION_ID.toEventOrTransactionId()), value(ANOTHER_MESSAGE), value(ANOTHER_MESSAGE), any())

            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = true,
                    isReply = false,
                    messageType = Composer.MessageType.Text,
                )
            )
        }
    }

    @Test
    fun `present - reply message`() = runTest {
        val replyMessageLambda = lambdaRecorder { _: EventId?, _: String, _: String?, _: List<IntentionalMention>, _: Boolean, _: MsgType ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.replyMessageLambda = replyMessageLambda
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(
            joinedRoom,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.textEditorState.messageHtml()).isEqualTo("")
            val mode = aReplyMode()
            initialState.eventSink.invoke(MessageComposerEvent.SetMode(mode))
            val state = awaitItem()
            assertThat(state.mode).isEqualTo(mode)
            assertThat(state.textEditorState.messageHtml()).isEqualTo("")
            state.textEditorState.setHtml(A_REPLY)
            assertThat(state.textEditorState.messageHtml()).isEqualTo(A_REPLY)
            state.eventSink.invoke(MessageComposerEvent.SendMessage)
            val messageSentState = awaitItem()
            assertThat(messageSentState.textEditorState.messageHtml()).isEqualTo("")

            advanceUntilIdle()

            assert(replyMessageLambda)
                .isCalledOnce()
                .with(any(), value(A_REPLY), value(A_REPLY), any(), value(false), value(MsgType.MSG_TYPE_TEXT))

            assertThat(analyticsService.capturedEvents).containsExactly(
                Composer(
                    inThread = false,
                    isEditing = false,
                    isReply = true,
                    messageType = Composer.MessageType.Text,
                )
            )
        }
    }

    @Test
    fun `present - Open attachments menu`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.showAttachmentSourcePicker).isFalse()
            initialState.eventSink(MessageComposerEvent.AddAttachment)
            assertThat(awaitItem().showAttachmentSourcePicker).isTrue()
        }
    }

    @Test
    fun `present - Dismiss attachments menu`() = runTest {
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.AddAttachment)
            skipItems(1)

            initialState.eventSink(MessageComposerEvent.DismissAttachmentMenu)
            assertThat(awaitItem().showAttachmentSourcePicker).isFalse()
        }
    }

    @Test
    fun `present - Pick image from gallery`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        var previewAttachments: ImmutableList<Attachment>? = null
        val onPreviewAttachmentLambda = lambdaRecorder { attachments: ImmutableList<Attachment>, _: EventId? ->
            previewAttachments = attachments
        }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            navigator = navigator,
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenMimeType(MimeTypes.Images)
        mediaPreProcessor.givenResult(
            Result.success(
                MediaUploadInfo.Image(
                    file = File("/some/path"),
                    imageInfo = ImageInfo(
                        width = null,
                        height = null,
                        mimetype = null,
                        size = null,
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                    ),
                    thumbnailFile = File("/some/path")
                )
            )
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            onPreviewAttachmentLambda.assertions().isCalledOnce()
            val request = coordinator.startedRequests.single()
            val localMedia = (previewAttachments?.single() as Attachment.Media).localMedia
            assertThat(localMedia.lifecycleOperationId).isEqualTo("fake-picked-${request.operationId}-0")
            assertThat(localMedia.lifecycleRoomIdHash).isEqualTo(request.roomIdHash)
            assertThat(pickerProvider.completedOperationIds).containsExactly(request.operationId)
        }
    }

    @Test
    fun `present - Pick video from gallery`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            navigator = navigator,
        )
        pickerProvider.givenMimeType(MimeTypes.Videos)
        mediaPreProcessor.givenResult(
            Result.success(
                MediaUploadInfo.Video(
                    file = File("/some/path"),
                    videoInfo = VideoInfo(
                        width = null,
                        height = null,
                        mimetype = null,
                        duration = null,
                        size = null,
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                    ),
                    thumbnailFile = File("/some/path")
                )
            )
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            onPreviewAttachmentLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - Pick media from gallery & cancel does nothing`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(appOperationalStateCoordinator = coordinator)
        with(pickerProvider) {
            givenResult(null) // Simulate a user canceling the flow
            givenMimeType(MimeTypes.Images)
        }
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            assertThat(pickerProvider.completedOperationIds).containsExactly(coordinator.startedRequests.single().operationId)
            assertThat(coordinator.startedRequests.single().accountIdHash).isNotNull()
            assertThat(coordinator.startedRequests.single().matrixDeviceIdHash).isNotNull()
            assertThat(coordinator.clientReceipts).hasSize(2)
            assertThat(coordinator.clientReceipts.map { it.operationId }.distinct())
                .containsExactly(coordinator.startedRequests.single().operationId)
            assertThat(coordinator.clientReceipts.map { it.resultCode }.distinct()).containsExactly("GALLERY_PICK_CANCELLED")
        }
    }

    @Test
    fun `present - gallery result with mismatched room authority is rejected`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val presenter = createPresenter(
            navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = onPreviewAttachmentLambda),
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenMimeType(MimeTypes.Images)
        pickerProvider.givenOperationTransform { operation ->
            operation.copy(roomIdHash = "b".repeat(64))
        }

        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()

            onPreviewAttachmentLambda.assertions().isNeverCalled()
            assertThat(pickerProvider.completedOperationIds).containsExactly(coordinator.startedRequests.single().operationId)
            assertThat(coordinator.clientReceipts).isEmpty()
        }
    }

    @Test
    fun `present - gallery cancellation with failed cleanup cannot receipt success`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(appOperationalStateCoordinator = coordinator)
        pickerProvider.givenResult(null)
        pickerProvider.completeOperationResult = false
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).isEmpty()
            assertThat(pickerProvider.completedOperationIds).containsExactly(coordinator.startedRequests.single().operationId)
        }
    }

    @Test
    fun `present - gallery cancellation from another account cannot receipt success`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by base {
            override suspend fun operation(operationId: String): io.element.android.security.dataprotection.api.LifecycleOperation {
                val operation = base.operation(operationId)
                return operation.copy(subjects = operation.subjects.copy(accountIdHash = "a".repeat(64)))
            }
        }
        val presenter = createPresenter(appOperationalStateCoordinator = coordinator)
        pickerProvider.givenResult(null)
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            assertThat(base.clientReceipts).isEmpty()
            assertThat(pickerProvider.completedOperationIds).containsExactly(base.startedRequests.single().operationId)
        }
    }

    @Test
    fun `present - camera cancellation receipts the original operation after cleanup`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenResult(null)
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            val operationId = coordinator.startedRequests.single().operationId
            assertThat(pickerProvider.completedOperationIds).containsExactly(operationId)
            assertThat(coordinator.clientReceipts).hasSize(2)
            assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).containsExactly(operationId)
            assertThat(coordinator.clientReceipts.map { it.resultCode }.distinct()).containsExactly("CAMERA_PICK_CANCELLED")
        }
    }

    @Test
    fun `camera cancellation survives receipt handoff failure and reopens with original fact`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        pickerProvider.givenResult(null)
        val failing = object : AppOperationalStateCoordinator by base {
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) {
                error("Simulated durable receipt storage failure")
            }
        }
        val first = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = failing,
        )
        first.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            assertThat(base.clientReceipts).isEmpty()
            assertThat(pickerProvider.pendingCancellations()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        val original = pickerProvider.pendingCancellations().single()
        createPresenter(appOperationalStateCoordinator = base).test {
            awaitFirstItem()
            advanceUntilIdle()
            assertThat(base.clientReceipts).hasSize(2)
            assertThat(base.clientReceipts.map { it.operationId }.distinct()).containsExactly(original.operation.operationId)
            assertThat(base.clientReceipts.map { it.occurredAtEpochMillis }.distinct()).containsExactly(original.completedAtEpochMillis)
            assertThat(base.clientReceipts.map { it.resultCode }.distinct()).containsExactly("CAMERA_PICK_CANCELLED")
            assertThat(pickerProvider.pendingCancellations()).isEmpty()
            assertThat(base.startedRequests).hasSize(1)
            assertThat(pickerProvider.completedOperationIds).containsExactly(original.operation.operationId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cold cancellation recovery rejects changed account device room time or lifecycle`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        pickerProvider.givenResult(null)
        val first = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = base,
        )
        pickerProvider.acknowledgeCancellationResult = false
        first.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        val original = pickerProvider.pendingCancellations().single()
        val authority = base.operation(original.operation.operationId)
        base.clientReceipts.clear()
        pickerProvider.acknowledgeCancellationResult = true
        listOf(
            authority.copy(operationId = "another-operation-0001"),
            authority.copy(lifecycleId = io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_16),
            authority.copy(subjects = authority.subjects.copy(accountIdHash = "a".repeat(64))),
            authority.copy(subjects = authority.subjects.copy(matrixDeviceIdHash = "a".repeat(64))),
            authority.copy(subjects = authority.subjects.copy(roomIdHash = "a".repeat(64))),
            authority.copy(createdAtEpochMillis = authority.createdAtEpochMillis - 1),
            authority.copy(deadlineEpochMillis = authority.deadlineEpochMillis + 1),
        ).forEach { changed ->
            val coordinator = object : AppOperationalStateCoordinator by base {
                override suspend fun operation(operationId: String) = changed
            }
            createPresenter(appOperationalStateCoordinator = coordinator).test {
                awaitFirstItem()
                advanceUntilIdle()
                assertThat(base.clientReceipts).isEmpty()
                assertThat(pickerProvider.pendingCancellations()).containsExactly(original)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `expired cancellation recovery retains original deadline and completion time without launching picker`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        pickerProvider.givenResult(null)
        pickerProvider.acknowledgeCancellationResult = false
        createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = base,
        ).test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        val retained = pickerProvider.pendingCancellations().single()
        val now = System.currentTimeMillis()
        val original = MediaPickCancellation(retained.operation.copy(createdAtEpochMillis = now - 10_000, deadlineEpochMillis = now - 5_000), now - 9_000)
        pickerProvider.cancellationFacts.clear()
        pickerProvider.cancellationFacts += original
        pickerProvider.acknowledgeCancellationResult = true
        val authority = base.operation(original.operation.operationId).copy(
            createdAtEpochMillis = original.operation.createdAtEpochMillis,
            deadlineEpochMillis = original.operation.deadlineEpochMillis,
        )
        val enqueued = mutableListOf<LifecycleClientReceipt>()
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun operation(operationId: String) = authority
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) {
                enqueued += receipts
            }
        }
        base.clientReceipts.clear()
        pickerProvider.completedOperationIds.clear()
        createPresenter(appOperationalStateCoordinator = coordinator).test {
            awaitFirstItem()
            advanceUntilIdle()
            assertThat(enqueued).hasSize(2)
            assertThat(enqueued.map { it.operationId }.distinct()).containsExactly(original.operation.operationId)
            assertThat(enqueued.map { it.occurredAtEpochMillis }.distinct()).containsExactly(original.completedAtEpochMillis)
            assertThat(pickerProvider.pendingCancellations()).isEmpty()
            assertThat(pickerProvider.completedOperationIds).isEmpty()
            assertThat(base.startedRequests).hasSize(1)
            assertThat(coordinator.operation(original.operation.operationId).deadlineEpochMillis).isEqualTo(original.operation.deadlineEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - camera cancellation with journal cleanup failure cannot receipt success`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenResult(null)
        pickerProvider.completeOperationResult = false
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).isEmpty()
            assertThat(pickerProvider.completedOperationIds).containsExactly(coordinator.startedRequests.single().operationId)
        }
    }

    @Test
    fun `present - camera plaintext cleanup failure retains the recovery journal and submits no receipt`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenResult(null)
        pickerProvider.operationFailureReason = io.element.android.libraries.mediapickers.api.MediaPickFailureReason.TEMPORARY_FILE_CLEANUP_FAILED
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).isEmpty()
            assertThat(pickerProvider.completedOperationIds).isEmpty()
        }
    }

    @Test
    fun `present - camera cancellation from another account cannot receipt success`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val coordinator = object : io.element.android.security.dataprotection.api.AppOperationalStateCoordinator by base {
            override suspend fun operation(operationId: String): io.element.android.security.dataprotection.api.LifecycleOperation {
                val operation = base.operation(operationId)
                return operation.copy(subjects = operation.subjects.copy(accountIdHash = "a".repeat(64)))
            }
        }
        val presenter = createPresenter(
            permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            appOperationalStateCoordinator = coordinator,
        )
        pickerProvider.givenResult(null)
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            assertThat(base.clientReceipts).isEmpty()
            assertThat(pickerProvider.completedOperationIds).containsExactly(base.startedRequests.single().operationId)
        }
    }

    @Test
    fun `present - Pick file from storage will open the preview`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            navigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles)
            onPreviewAttachmentLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - create poll`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(room = room)
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.AddAttachment)
            val attachmentOpenState = awaitItem()
            assertThat(attachmentOpenState.showAttachmentSourcePicker).isTrue()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)
            val finalState = awaitItem()
            assertThat(finalState.showAttachmentSourcePicker).isFalse()
        }
    }

    @Test
    fun `present - share location`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(room = room)
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.AddAttachment)
            val attachmentOpenState = awaitItem()
            assertThat(attachmentOpenState.showAttachmentSourcePicker).isTrue()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.Location)
            val finalState = awaitItem()
            assertThat(finalState.showAttachmentSourcePicker).isFalse()
        }
    }

    @Test
    fun `present - Take photo`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() }
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            permissionPresenter = permissionPresenter,
            navigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            onPreviewAttachmentLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - Take photo with permission request`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val permissionPresenter = FakePermissionsPresenter()
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            permissionPresenter = permissionPresenter,
            navigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            permissionPresenter.setPermissionGranted()
            onPreviewAttachmentLambda.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `camera denial exposes settings recovery and resumes original photo once`() = runTest {
        val permissionPresenter = FakePermissionsPresenter().apply { setPermissionDenied() }
        val preview = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val presenter = createPresenter(
            permissionPresenter = permissionPresenter,
            navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = preview),
        )
        presenter.test {
            val initial = awaitFirstItem()
            initial.textEditorState.setHtml(A_MESSAGE)
            initial.eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.showCameraPermissionRationaleDialog).isTrue()
            assertThat(denied.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            preview.assertions().isNeverCalled()
            denied.eventSink(MessageComposerEvent.OpenCameraPermissionSettings)
            assertThat(awaitItem().showCameraPermissionRationaleDialog).isFalse()
            permissionPresenter.setPermissionGranted()
            preview.assertions().isCalledOnce()
            assertThat(denied.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `camera denial cancel drops pending photo before later grant`() = runTest {
        val permissionPresenter = FakePermissionsPresenter().apply { setPermissionDenied() }
        val preview = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val presenter = createPresenter(
            permissionPresenter = permissionPresenter,
            navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = preview),
        )
        presenter.test {
            awaitFirstItem().eventSink(MessageComposerEvent.PickAttachmentSource.PhotoFromCamera)
            val denied = awaitItem()
            assertThat(denied.showCameraPermissionRationaleDialog).isTrue()
            denied.eventSink(MessageComposerEvent.DismissCameraPermissionRationale)
            assertThat(awaitItem().showCameraPermissionRationaleDialog).isFalse()
            permissionPresenter.setPermissionGranted()
            advanceUntilIdle()
            preview.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelled denied video never launches after later camera grant and preserves draft`() = runTest {
        val permissionPresenter = FakePermissionsPresenter().apply { setPermissionDenied() }
        val preview = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val presenter = createPresenter(
            permissionPresenter = permissionPresenter,
            navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = preview),
        )
        presenter.test {
            val initial = awaitFirstItem()
            initial.textEditorState.setHtml(A_MESSAGE)
            initial.eventSink(MessageComposerEvent.PickAttachmentSource.VideoFromCamera)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.showCameraPermissionRationaleDialog).isTrue()
            preview.assertions().isNeverCalled()
            denied.eventSink(MessageComposerEvent.DismissCameraPermissionRationale)
            advanceUntilIdle()
            permissionPresenter.setPermissionGranted()
            advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertThat(restored.showCameraPermissionRationaleDialog).isFalse()
            assertThat(restored.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            preview.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - Record video`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() }
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            permissionPresenter = permissionPresenter,
            navigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.VideoFromCamera)
            onPreviewAttachmentLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - Record video with permission request`() = runTest {
        val room = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        )
        val permissionPresenter = FakePermissionsPresenter()
        val onPreviewAttachmentLambda = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        val navigator = FakeMessagesNavigator(
            onPreviewAttachmentLambda = onPreviewAttachmentLambda
        )
        val presenter = createPresenter(
            room = room,
            permissionPresenter = permissionPresenter,
            navigator = navigator,
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.PickAttachmentSource.VideoFromCamera)
            val permissionState = awaitItem()
            assertThat(permissionState.showAttachmentSourcePicker).isFalse()
            permissionPresenter.setPermissionGranted()
            skipItems(1)
            onPreviewAttachmentLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - errors are tracked`() = runTest {
        val testException = Exception("Test error")
        val presenter = createPresenter()
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.Error(testException))
            assertThat(analyticsService.trackedErrors).containsExactly(testException)
        }
    }

    @Test
    fun `present - ToggleTextFormatting toggles text formatting`() = runTest {
        val presenter = createPresenter(isRichTextEditorEnabled = false)
        presenter.test {
            val initialState = awaitFirstItem()
            assertThat(initialState.showTextFormatting).isFalse()
            initialState.eventSink(MessageComposerEvent.AddAttachment)
            val composerOptions = awaitItem()
            assertThat(composerOptions.showAttachmentSourcePicker).isTrue()
            composerOptions.eventSink(MessageComposerEvent.ToggleTextFormatting(true))
            skipItems(2) // composer options closed
            val showTextFormatting = awaitItem()
            assertThat(showTextFormatting.showAttachmentSourcePicker).isFalse()
            assertThat(showTextFormatting.showTextFormatting).isTrue()
            assertThat(analyticsService.capturedEvents).containsExactly(
                Interaction(index = null, interactionType = null, name = Interaction.Name.MobileRoomComposerFormattingEnabled)
            )
            analyticsService.capturedEvents.clear()
            showTextFormatting.eventSink(MessageComposerEvent.ToggleTextFormatting(false))
            skipItems(1)
            val finished = awaitItem()
            assertThat(finished.showTextFormatting).isFalse()
            assertThat(analyticsService.capturedEvents).isEmpty()
        }
    }

    @Test
    fun `present - room member mention suggestions`() = runTest {
        val currentUser = aRoomMember(userId = A_USER_ID, membership = RoomMembershipState.JOIN)
        val invitedUser = aRoomMember(userId = A_USER_ID_3, membership = RoomMembershipState.INVITE)
        val bob = aRoomMember(userId = A_USER_ID_2, membership = RoomMembershipState.JOIN)
        val david = aRoomMember(userId = A_USER_ID_4, displayName = "Dave", membership = RoomMembershipState.JOIN)
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = FakeRoomPermissions(
                    canTriggerRoomNotification = true,
                )
            ),
            typingNoticeResult = { Result.success(Unit) }
        ).apply {
            givenRoomMembersState(
                RoomMembersState.Ready(
                    persistentListOf(currentUser, invitedUser, bob, david),
                )
            )
            givenRoomInfo(aRoomInfo(isDirect = false))
        }
        val presenter = createPresenter(
            room = room,
            slashCommandService = FakeSlashCommandService(
                getSuggestionsResult = { _, _ -> emptyList() },
            ),
        )
        presenter.test {
            val initialState = awaitItem()

            // A null suggestion (no suggestion was received) returns nothing
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(null))
            assertThat(awaitItem().suggestions).isEmpty()

            // An empty suggestion returns the room and joined members that are not the current user
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "")))
            assertThat(awaitItem().suggestions)
                .containsExactly(ResolvedSuggestion.AtRoom, ResolvedSuggestion.Member(bob), ResolvedSuggestion.Member(david))

            // A suggestion containing a part of "room" will also return the room mention
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "roo")))
            assertThat(awaitItem().suggestions).containsExactly(ResolvedSuggestion.AtRoom)

            // A non-empty suggestion will return those joined members whose user id matches it
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "bob")))
            assertThat(awaitItem().suggestions).containsExactly(ResolvedSuggestion.Member(bob))

            // A non-empty suggestion will return those joined members whose display name matches it
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "dave")))
            assertThat(awaitItem().suggestions).containsExactly(ResolvedSuggestion.Member(david))

            // If the suggestion isn't a mention, no suggestions are returned
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Command, "")))
            assertThat(awaitItem().suggestions).isEmpty()
        }
    }

    @Test
    fun `present - room mention suggestions no permission`() = runTest {
        val currentUser = aRoomMember(userId = A_USER_ID, membership = RoomMembershipState.JOIN)
        val invitedUser = aRoomMember(userId = A_USER_ID_3, membership = RoomMembershipState.INVITE)
        val bob = aRoomMember(userId = A_USER_ID_2, membership = RoomMembershipState.JOIN)
        val david = aRoomMember(userId = A_USER_ID_4, displayName = "Dave", membership = RoomMembershipState.JOIN)
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = FakeRoomPermissions(
                    canTriggerRoomNotification = false,
                )
            ),
            typingNoticeResult = { Result.success(Unit) }
        ).apply {
            givenRoomMembersState(
                RoomMembersState.Ready(
                    persistentListOf(currentUser, invitedUser, bob, david),
                )
            )
            givenRoomInfo(aRoomInfo(isDirect = false))
        }
        val presenter = createPresenter(room)
        presenter.test {
            val initialState = awaitItem()
            // An empty suggestion returns the joined members that are not the current user, but not the room
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "")))
            skipItems(1)
            assertThat(awaitItem().suggestions)
                .containsExactly(ResolvedSuggestion.Member(bob), ResolvedSuggestion.Member(david))
        }
    }

    @Test
    fun `present - room member mention suggestions in a DM`() = runTest {
        val currentUser = aRoomMember(userId = A_USER_ID, membership = RoomMembershipState.JOIN)
        val invitedUser = aRoomMember(userId = A_USER_ID_3, membership = RoomMembershipState.INVITE)
        val bob = aRoomMember(userId = A_USER_ID_2, membership = RoomMembershipState.JOIN)
        val david = aRoomMember(userId = A_USER_ID_4, displayName = "Dave", membership = RoomMembershipState.JOIN)
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = FakeRoomPermissions(canTriggerRoomNotification = true),
            ),
            typingNoticeResult = { Result.success(Unit) }
        ).apply {
            givenRoomMembersState(
                RoomMembersState.Ready(
                    persistentListOf(currentUser, invitedUser, bob, david),
                )
            )
            givenRoomInfo(
                aRoomInfo(
                    isDm = true,
                    activeMembersCount = 2,
                )
            )
        }
        val presenter = createPresenter(room)
        presenter.test {
            val initialState = awaitItem()
            // An empty suggestion returns the joined members that are not the current user, but not the room
            initialState.eventSink(MessageComposerEvent.SuggestionReceived(Suggestion(0, 0, SuggestionType.Mention, "")))
            skipItems(1)
            assertThat(awaitItem().suggestions)
                .containsExactly(ResolvedSuggestion.Member(bob), ResolvedSuggestion.Member(david))
        }
    }

    fun `present - InsertSuggestion`() = runTest {
        val presenter = createPresenter(
            permalinkBuilder = FakePermalinkBuilder(
                permalinkForUserLambda = {
                    Result.success("https://matrix.to/#/${A_USER_ID_2.value}")
                }
            )
        )
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setHtml("Hey @bo")
            initialState.eventSink(MessageComposerEvent.InsertSuggestion(ResolvedSuggestion.Member(aRoomMember(userId = A_USER_ID_2))))

            assertThat(initialState.textEditorState.messageHtml())
                .isEqualTo("Hey <a href='https://matrix.to/#/${A_USER_ID_2.value}'>${A_USER_ID_2.value}</a>")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - send messages with intentional mentions`() = runTest {
        val replyMessageLambda = lambdaRecorder { _: EventId?, _: String, _: String?, _: List<IntentionalMention>, _: Boolean, _: MsgType ->
            Result.success(Unit)
        }
        val editMessageLambda = lambdaRecorder { _: EventOrTransactionId, _: String, _: String?, _: List<IntentionalMention> ->
            Result.success(Unit)
        }
        val sendMessageResult = lambdaRecorder { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.replyMessageLambda = replyMessageLambda
            this.editMessageLambda = editMessageLambda
            sendMessageLambda = sendMessageResult
        }
        val room = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) }
        )
        val presenter = createPresenter(
            room = room,
            slashCommandService = FakeSlashCommandService(
                parseResult = { _, _, _ -> SlashCommand.NotACommand }
            ),
        )
        presenter.test {
            val initialState = awaitFirstItem()

            // Check intentional mentions on message sent
            val mentionUser1 = listOf(A_USER_ID.value)
            (initialState.textEditorState as? TextEditorState.Rich)?.richTextEditorState?.mentionsState = MentionsState(
                userIds = mentionUser1,
                roomIds = emptyList(),
                roomAliases = emptyList(),
                hasAtRoomMention = false
            )
            initialState.textEditorState.setHtml(A_MESSAGE)
            initialState.eventSink(MessageComposerEvent.SendMessage)

            advanceUntilIdle()

            sendMessageResult.assertions().isCalledOnce()
                .with(value(A_MESSAGE), any(), value(listOf(IntentionalMention.User(A_USER_ID))), value(MsgType.MSG_TYPE_TEXT), value(false))

            // Check intentional mentions on reply sent
            initialState.eventSink(MessageComposerEvent.SetMode(aReplyMode()))
            val mentionUser2 = listOf(A_USER_ID_2.value)
            (awaitItem().textEditorState as? TextEditorState.Rich)?.richTextEditorState?.mentionsState = MentionsState(
                userIds = mentionUser2,
                roomIds = emptyList(),
                roomAliases = emptyList(),
                hasAtRoomMention = false
            )

            initialState.textEditorState.setHtml(A_MESSAGE)
            initialState.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()

            assert(replyMessageLambda)
                .isCalledOnce()
                .with(any(), any(), any(), value(listOf(IntentionalMention.User(A_USER_ID_2))), value(false), value(MsgType.MSG_TYPE_TEXT))

            // Check intentional mentions on edit message
            skipItems(1)
            initialState.eventSink(MessageComposerEvent.SetMode(anEditMode()))
            val mentionUser3 = listOf(A_USER_ID_3.value)
            (awaitItem().textEditorState as? TextEditorState.Rich)?.richTextEditorState?.mentionsState = MentionsState(
                userIds = mentionUser3,
                roomIds = emptyList(),
                roomAliases = emptyList(),
                hasAtRoomMention = false
            )

            initialState.textEditorState.setHtml(A_MESSAGE)
            initialState.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()

            assert(editMessageLambda)
                .isCalledOnce()
                .with(any(), any(), any(), value(listOf(IntentionalMention.User(A_USER_ID_3))))

            skipItems(1)
        }
    }

    @Test
    fun `present - send uri`() = runTest {
        val presenter = createPresenter(
            room = FakeJoinedRoom(
                typingNoticeResult = { Result.success(Unit) },
                liveTimeline = FakeTimeline().apply {
                    sendFileLambda = { _, _, _, _, _ ->
                        Result.success(FakeMediaUploadHandler())
                    }
                }
            ),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageHtml()) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(MessageComposerEvent.SendUri(Uri.parse("content://uri")))
            waitForPredicate { mediaPreProcessor.processCallCount == 1 }
        }
    }

    @Test
    fun `present - handle typing notice event`() = runTest {
        val typingNoticeResult = lambdaRecorder<Boolean, Result<Unit>> { Result.success(Unit) }
        val room = FakeJoinedRoom(
            typingNoticeResult = typingNoticeResult,
        )
        val presenter = createPresenter(room = room)
        presenter.test {
            val initialState = awaitFirstItem()
            typingNoticeResult.assertions().isNeverCalled()
            initialState.eventSink.invoke(MessageComposerEvent.TypingNotice(true))
            initialState.eventSink.invoke(MessageComposerEvent.TypingNotice(false))
            advanceUntilIdle()
            typingNoticeResult.assertions().isCalledExactly(2)
                .withSequence(
                    listOf(value(true)),
                    listOf(value(false)),
                )
        }
    }

    @Test
    fun `present - handle typing notice event when sending typing notice is disabled`() = runTest {
        val typingNoticeResult = lambdaRecorder<Boolean, Result<Unit>> { Result.success(Unit) }
        val room = FakeJoinedRoom(
            typingNoticeResult = typingNoticeResult
        )
        val store = InMemorySessionPreferencesStore(
            isSendTypingNotificationsEnabled = false
        )
        val presenter = createPresenter(room = room, sessionPreferencesStore = store)
        presenter.test {
            val initialState = awaitFirstItem()
            typingNoticeResult.assertions().isNeverCalled()
            initialState.eventSink.invoke(MessageComposerEvent.TypingNotice(true))
            initialState.eventSink.invoke(MessageComposerEvent.TypingNotice(false))
            typingNoticeResult.assertions().isNeverCalled()
        }
    }

    @Test
    fun `present - active normal editor durably saves the latest draft without pause`() = runTest {
        val saved = mutableListOf<ComposerDraft?>()
        val service = FakeComposerDraftService().apply { saveDraftLambda = { _, _, draft, volatile -> if (!volatile) saved += draft } }
        val presenter = createPresenter(isRichTextEditorEnabled = false, draftService = service)
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("unfinished first")
            state.textEditorState.setMarkdown("unfinished latest")
            advanceUntilIdle()
            assertThat(saved.map { it?.plainText }).containsExactly("unfinished latest")
            state.textEditorState.reset()
            advanceUntilIdle()
            assertThat(saved.last()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - restored durable draft is not replaced by the initially empty editor`() = runTest {
        val saved = mutableListOf<ComposerDraft?>()
        val draft = ComposerDraft("retained unsent", null, ComposerDraftType.NewMessage)
        val service = FakeComposerDraftService().apply {
            loadDraftLambda = { _, _, _ -> draft }
            saveDraftLambda = { _, _, value, _ -> saved += value }
        }
        val presenter = createPresenter(isRichTextEditorEnabled = false, draftService = service)
        presenter.test {
            val state = awaitFirstItem()
            advanceUntilIdle()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo(draft.plainText)
            assertThat(saved).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - when there is no draft, nothing is restored`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ -> null }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val presenter = createPresenter(draftService = composerDraftService)
        presenter.test {
            awaitFirstItem()
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))

            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - when there is a draft for new message with plain text, it is restored`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ ->
            ComposerDraft(plainText = A_MESSAGE, htmlText = null, draftType = ComposerDraftType.NewMessage)
        }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )
        presenter.test {
            skipItems(2)
            awaitItem().also { state ->
                assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
                assertThat(state.textEditorState.messageHtml()).isNull()
            }
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))

            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - when there is a draft for new message with rich text, it is restored`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ ->
            ComposerDraft(
                plainText = A_MESSAGE,
                htmlText = A_MESSAGE,
                draftType = ComposerDraftType.NewMessage
            )
        }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )
        presenter.test {
            skipItems(1)
            awaitItem().also { state ->
                assertThat(state.showTextFormatting).isTrue()
                assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
                assertThat(state.textEditorState.messageHtml()).isEqualTo(A_MESSAGE)
            }
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - when there is a draft for edit, it is restored`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ ->
            ComposerDraft(
                plainText = A_MESSAGE,
                htmlText = null,
                draftType = ComposerDraftType.Edit(AN_EVENT_ID)
            )
        }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )
        presenter.test {
            skipItems(2)
            awaitItem().also { state ->
                assertThat(state.showTextFormatting).isFalse()
                assertThat(state.mode).isEqualTo(anEditMode())
                assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
                assertThat(state.textEditorState.messageHtml()).isNull()
            }
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))

            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - when there is a caption edit draft, it is restored as caption edit`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ ->
            ComposerDraft(
                plainText = A_CAPTION,
                htmlText = null,
                draftType = ComposerDraftType.EditCaption(AN_EVENT_ID),
            )
        }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )

        presenter.test {
            skipItems(2)
            awaitItem().also { state ->
                assertThat(state.mode).isEqualTo(anEditCaptionMode(caption = A_CAPTION))
                assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_CAPTION)
            }
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - restored caption draft target failure never falls back to new message`() = runTest {
        val targetFailure = IllegalStateException("target is no longer editable")
        val editCaptionLambda = lambdaRecorder { _: EventOrTransactionId, _: String?, _: String? ->
            Result.failure<Unit>(targetFailure)
        }
        val timeline = FakeTimeline().apply {
            this.editCaptionLambda = editCaptionLambda
        }
        val composerDraftService = FakeComposerDraftService().apply {
            loadDraftLambda = { _, _, _ ->
                ComposerDraft(A_CAPTION, null, ComposerDraftType.EditCaption(AN_EVENT_ID))
            }
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            draftService = composerDraftService,
            isRichTextEditorEnabled = false,
            permalinkBuilder = permalinkBuilder,
        )

        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            remember(state, state.textEditorState.messageMarkdown(permalinkBuilder), state.slashCommandAction) { state }
        }.test {
            var restored = awaitFirstItem()
            while (restored.mode !is MessageComposerMode.EditCaption ||
                restored.textEditorState.messageMarkdown(permalinkBuilder) != A_CAPTION) {
                restored = awaitItem()
            }
            restored.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()

            val failed = expectMostRecentItem()
            assertThat(failed.mode).isEqualTo(anEditCaptionMode(caption = A_CAPTION))
            assertThat(failed.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_CAPTION)
            assertThat(failed.slashCommandAction).isEqualTo(AsyncAction.Failure(targetFailure))
            assert(editCaptionLambda).isCalledOnce()
            assertThat(analyticsService.capturedEvents).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - when there is a draft for reply, it is restored`() = runTest {
        val loadDraftLambda = lambdaRecorder<RoomId, ThreadId?, Boolean, ComposerDraft?> { _, _, _ ->
            ComposerDraft(
                plainText = A_MESSAGE,
                htmlText = null,
                draftType = ComposerDraftType.Reply(AN_EVENT_ID)
            )
        }
        val composerDraftService = FakeComposerDraftService().apply {
            this.loadDraftLambda = loadDraftLambda
        }
        val loadReplyDetailsLambda = lambdaRecorder<EventId, InReplyTo> { eventId ->
            InReplyTo.Pending(eventId)
        }
        val timeline = FakeTimeline().apply {
            this.loadReplyDetailsLambda = loadReplyDetailsLambda
        }
        val room = FakeJoinedRoom(
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        )
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            room = room,
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )
        presenter.test {
            skipItems(2)
            awaitItem().also { state ->
                assertThat(state.showTextFormatting).isFalse()
                assertThat(state.mode).isEqualTo(aReplyMode())
                assertThat(state.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
                assertThat(state.textEditorState.messageHtml()).isNull()
            }
            assert(loadDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(false))

            assert(loadReplyDetailsLambda)
                .isCalledOnce()
                .with(value(AN_EVENT_ID))

            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `present - cold reply draft resolves when its timeline target arrives`() = runTest {
        verifyDelayedReplyDraft(cancelReply = false)
    }

    @Test
    fun `present - delayed reply restoration cannot replace a cancelled reply`() = runTest {
        verifyDelayedReplyDraft(cancelReply = true)
    }

    private suspend fun TestScope.verifyDelayedReplyDraft(cancelReply: Boolean) {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        var reads = 0
        val timeline = FakeTimeline(timelineItems = items).apply {
            loadReplyDetailsLambda = { eventId ->
                reads++
                if (items.value.isEmpty()) InReplyTo.Pending(eventId) else InReplyTo.Ready(
                    eventId, aMessageContent(body = A_REPLY), A_USER_ID, aProfileDetails(),
                )
            }
        }
        val drafts = FakeComposerDraftService().apply {
            loadDraftLambda = { _, _, _ -> ComposerDraft(A_MESSAGE, null, ComposerDraftType.Reply(AN_EVENT_ID)) }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            draftService = drafts,
            isRichTextEditorEnabled = false,
        )
        presenter.test {
            var state = awaitFirstItem()
            while (state.mode !is MessageComposerMode.Reply) state = awaitItem()
            runCurrent()
            assertThat(reads).isEqualTo(1)
            if (cancelReply) {
                state.eventSink(MessageComposerEvent.SetMode(MessageComposerMode.Normal))
                runCurrent()
            }
            items.value = listOf(MatrixTimelineItem.Event(UniqueId("restored-target"), anEventTimelineItem(content = aMessageContent(A_REPLY))))
            runCurrent()
            state = expectMostRecentItem()
            if (cancelReply) {
                assertThat(state.mode).isEqualTo(MessageComposerMode.Normal)
                assertThat(reads).isEqualTo(1)
            } else {
                val reply = state.mode as MessageComposerMode.Reply
                assertThat(reply.replyToDetails).isInstanceOf(InReplyToDetails.Ready::class.java)
                assertThat((reply.replyToDetails as InReplyToDetails.Ready).textContent).isEqualTo(A_REPLY)
                assertThat(reply.eventId).isEqualTo(AN_EVENT_ID)
                assertThat(reads).isEqualTo(2)
            }
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo(A_MESSAGE)
            assertThat(analyticsService.capturedEvents).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - when save draft event is invoked and composer is empty then service is called with null draft`() = runTest {
        val saveDraftLambda = lambdaRecorder<RoomId, ThreadId?, ComposerDraft?, Boolean, Unit> { _, _, _, _ -> }
        val composerDraftService = FakeComposerDraftService().apply {
            this.saveDraftLambda = saveDraftLambda
        }
        val presenter = createPresenter(draftService = composerDraftService)
        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink.invoke(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()
            assert(saveDraftLambda)
                .isCalledOnce()
                .with(value(A_ROOM_ID), value(null), value(null), value(false))
        }
    }

    @Test
    fun `present - withdrawn re-edit text is never written to persistent drafts across pause ordering`() = runTest {
        val persistedDrafts = mutableListOf<ComposerDraft?>()
        val composerDraftService = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, isVolatile ->
                if (!isVolatile) persistedDrafts += draft
            }
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            isRichTextEditorEnabled = false,
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )

        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            val messageMarkdown = state.textEditorState.messageMarkdown(permalinkBuilder)
            remember(state, messageMarkdown) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.RestoreWithdrawnText(A_MESSAGE))
            advanceUntilIdle()

            val withdrawnState = expectMostRecentItem()
            assertThat(withdrawnState.containsVolatileWithdrawnText).isTrue()
            assertThat(withdrawnState.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)

            // SaveDraft and ClearWithdrawnText are emitted by separate lifecycle observers.
            // Both orderings must only clear persistent storage, never expose the plaintext.
            withdrawnState.eventSink(MessageComposerEvent.SaveDraft)
            withdrawnState.eventSink(MessageComposerEvent.ClearWithdrawnText)
            withdrawnState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            val clearedState = expectMostRecentItem()
            assertThat(clearedState.containsVolatileWithdrawnText).isFalse()
            assertThat(clearedState.textEditorState.messageMarkdown(permalinkBuilder)).isEmpty()
            assertThat(persistedDrafts).isNotEmpty()
            assertThat(persistedDrafts).containsExactlyElementsIn(List(persistedDrafts.size) { null })
        }
    }

    @Test
    fun `present - when save draft event is invoked and composer is not empty then service is called`() = runTest {
        val saveDraftLambda = lambdaRecorder<RoomId, ThreadId?, ComposerDraft?, Boolean, Unit> { _, _, _, _ -> }
        val composerDraftService = FakeComposerDraftService().apply {
            this.saveDraftLambda = saveDraftLambda
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            isRichTextEditorEnabled = false,
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            val state = presenter.present()
            val messageMarkdown = state.textEditorState.messageMarkdown(permalinkBuilder)
            remember(state, messageMarkdown) { state }
        }.test {
            val initialState = awaitFirstItem()
            initialState.textEditorState.setMarkdown(A_MESSAGE)

            val withMessageState = awaitItem()
            assertThat(withMessageState.textEditorState.messageMarkdown(permalinkBuilder)).isEqualTo(A_MESSAGE)
            withMessageState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            withMessageState.eventSink(MessageComposerEvent.ToggleTextFormatting(true))
            skipItems(1)
            val withFormattingState = awaitItem()
            assertThat(withFormattingState.showTextFormatting).isTrue()
            withFormattingState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            withFormattingState.eventSink(MessageComposerEvent.SetMode(anEditMode()))
            val withEditModeState = awaitItem()
            assertThat(withEditModeState.mode).isEqualTo(anEditMode())
            withEditModeState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            withEditModeState.eventSink(MessageComposerEvent.SetMode(aReplyMode()))
            val withReplyModeState = awaitItem()
            assertThat(withReplyModeState.mode).isEqualTo(aReplyMode())
            withReplyModeState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            assert(saveDraftLambda)
                .isCalledExactly(5)
                .withSequence(
                    listOf(
                        value(A_ROOM_ID),
                        value(null),
                        value(ComposerDraft(plainText = A_MESSAGE, htmlText = null, draftType = ComposerDraftType.NewMessage)),
                        value(false)
                    ),
                    listOf(
                        value(A_ROOM_ID),
                        value(null),
                        value(ComposerDraft(plainText = A_MESSAGE, htmlText = A_MESSAGE, draftType = ComposerDraftType.NewMessage)),
                        value(false)
                    ),
                    listOf(
                        value(A_ROOM_ID),
                        value(null),
                        value(ComposerDraft(plainText = A_MESSAGE, htmlText = A_MESSAGE, draftType = ComposerDraftType.NewMessage)),
                        // The volatile draft created when switching to edit mode.
                        value(true)
                    ),
                    listOf(
                        value(A_ROOM_ID),
                        value(null),
                        value(ComposerDraft(plainText = A_MESSAGE, htmlText = A_MESSAGE, draftType = ComposerDraftType.Edit(AN_EVENT_ID))),
                        value(false)
                    ),
                    listOf(
                        value(A_ROOM_ID),
                        value(null),
                        // When moving from edit mode, text composer is cleared, so the draft is null
                        value(null),
                        value(false)
                    )
                )
        }
    }

    @Test
    fun `present - caption edit draft preserves target identity and room scope`() = runTest {
        val saveDraftLambda = lambdaRecorder<RoomId, ThreadId?, ComposerDraft?, Boolean, Unit> { _, _, _, _ -> }
        val composerDraftService = FakeComposerDraftService().apply {
            // Entering edit mode also saves the prior normal draft as volatile.
            this.saveDraftLambda = { roomId, threadRoot, draft, isVolatile ->
                if (!isVolatile) saveDraftLambda(roomId, threadRoot, draft, isVolatile)
            }
        }
        val permalinkBuilder = FakePermalinkBuilder()
        val presenter = createPresenter(
            isRichTextEditorEnabled = false,
            draftService = composerDraftService,
            permalinkBuilder = permalinkBuilder,
        )

        presenter.test {
            val initialState = awaitFirstItem()
            initialState.eventSink(MessageComposerEvent.SetMode(anEditCaptionMode(caption = A_CAPTION)))
            val captionState = awaitItem()
            captionState.textEditorState.setMarkdown(A_CAPTION)
            // Writing identical text must not require a synthetic state emission.
            captionState.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()

            assert(saveDraftLambda)
                .isCalledOnce()
                .with(
                    value(A_ROOM_ID),
                    value(null),
                    value(ComposerDraft(A_CAPTION, null, ComposerDraftType.EditCaption(AN_EVENT_ID))),
                    value(false),
                )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `superseded autosave never receipts an unapplied draft`() = runTest {
        verifySupersededDraftAdmission(isCaption = false)
    }

    @Test
    fun `unresolved previous draft blocks repeated new intent admission`() = runTest {
        var preparations = 0
        var writes = 0
        var admissions = 0
        val drafts = FakeComposerDraftService().apply {
            preparePersistentDraftWriteLambda = { _, _ -> preparations++; error("original draft transition is unresolved") }
            saveDraftLambda = { _, _, _, _ -> writes++ }
        }
        val coordinator = object : AppOperationalStateCoordinator by FakeAppOperationalStateCoordinator() {
            override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (String) -> Unit) {
                admissions++
                error("must recover before admitting a new intent")
            }
        }
        createPresenter(isRichTextEditorEnabled = false, draftService = drafts, appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep current text")
            advanceUntilIdle()
            repeat(3) { state.eventSink(MessageComposerEvent.SaveDraft); advanceUntilIdle() }
            assertThat(preparations).isAtLeast(3)
            assertThat(admissions).isEqualTo(0)
            assertThat(writes).isEqualTo(0)
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep current text")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `draft superseded during prior recovery is never admitted`() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdNext = false
        val saved = mutableListOf<String?>()
        var admissions = 0
        val drafts = FakeComposerDraftService().apply {
            preparePersistentDraftWriteLambda = { _, _ ->
                if (holdNext) { holdNext = false; preparing.complete(Unit); release.await() }
            }
            saveDraftLambda = { _, _, draft, _ -> saved += draft?.plainText }
        }
        val coordinator = object : AppOperationalStateCoordinator by FakeAppOperationalStateCoordinator() {
            override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (String) -> Unit) {
                admissions++
                block(request.operationId)
            }
        }
        createPresenter(isRichTextEditorEnabled = false, draftService = drafts, appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            holdNext = true
            state.textEditorState.setMarkdown("superseded during recovery")
            runCurrent(); advanceTimeBy(501); runCurrent()
            assertThat(preparing.isCompleted).isTrue()
            state.textEditorState.setMarkdown("current draft")
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(saved).containsExactly("current draft")
            assertThat(admissions).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelled caption mode never receipts its unapplied draft`() = runTest {
        verifySupersededDraftAdmission(isCaption = true)
    }

    private suspend fun TestScope.verifySupersededDraftAdmission(isCaption: Boolean) {
        val base = FakeAppOperationalStateCoordinator()
        val admitted = CompletableDeferred<LifecycleOperationRequest>()
        val returnAdmission = CompletableDeferred<Unit>()
        var holdNextAdmission = !isCaption
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit) {
                perform(request, block)
            }
            override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation {
                val operation = base.start(request)
                if (request.lifecycleId.name == "LC_14" && holdNextAdmission) {
                    holdNextAdmission = false
                    admitted.complete(request)
                    returnAdmission.await()
                }
                return operation
            }
        }
        val saved = mutableListOf<Pair<String?, ComposerDraft?>>()
        val drafts = object : ComposerDraftService by FakeComposerDraftService() {
            override suspend fun updateDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?, isVolatile: Boolean) {
                if (!isVolatile) saved += currentLifecycleOperationContext()?.operationId to draft
            }
        }
        createPresenter(isRichTextEditorEnabled = false, draftService = drafts,
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            if (isCaption) {
                state.eventSink(MessageComposerEvent.SetMode(anEditCaptionMode(caption = A_CAPTION)))
                runCurrent()
                holdNextAdmission = true
                expectMostRecentItem().eventSink(MessageComposerEvent.SaveDraft)
                runCurrent()
            } else {
                state.textEditorState.setMarkdown("superseded draft")
                runCurrent()
                advanceTimeBy(501)
                runCurrent()
            }
            assertThat(admitted.isCompleted).isTrue()
            val original = admitted.await()
            assertThat(original.accountIdHash).isNotNull()
            assertThat(original.matrixDeviceIdHash).isNotNull()
            assertThat(original.roomIdHash).isNotNull()
            if (isCaption) {
                state.eventSink(MessageComposerEvent.SetMode(MessageComposerMode.Normal))
            } else {
                state.textEditorState.setMarkdown("current draft")
            }
            runCurrent()
            returnAdmission.complete(Unit)
            advanceUntilIdle()
            assertThat(saved.map { it.first }).doesNotContain(original.operationId)
            assertThat(base.clientReceipts.filter { it.operationId == original.operationId }).isEmpty()
            assertThat(saved.none { it.second?.draftType is ComposerDraftType.EditCaption }).isTrue()
            if (!isCaption) {
                assertThat(saved.map { it.second?.plainText }).containsExactly("current draft")
                val currentId = saved.single().first
                assertThat(currentId).isNotNull()
                assertThat(base.clientReceipts.filter { it.operationId == currentId }).hasSize(2)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline draft admission preserves the current local draft without claiming completion`() = runTest {
        val saved = mutableListOf<ComposerDraft?>()
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { java.io.IOException("TEST_OFFLINE") })
        val drafts = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, isVolatile -> if (!isVolatile) saved += draft }
        }
        createPresenter(isRichTextEditorEnabled = false, draftService = drafts,
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("offline draft")
            state.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()
            assertThat(saved.map { it?.plainText }).containsExactly("offline draft")
            assertThat(coordinator.startedRequests.filter { it.lifecycleId.name == "LC_14" }).hasSize(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            assertThat(coordinator.pendingLocalDraftReceipts).hasSize(2)
            assertThat(coordinator.pendingLocalDraftReceipts.map { it.operationId }.distinct())
                .containsExactly(coordinator.startedRequests.single { it.lifecycleId.name == "LC_14" }.operationId)
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("offline draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `draft receipt transport failure retains both original facts without a second draft write`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val pending = mutableListOf<LifecycleClientReceipt>()
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit) {
                perform(request, block)
            }
            override suspend fun persistClientReceipts(receipts: List<LifecycleClientReceipt>) {
                pending += receipts
            }
            override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation {
                if (receipt.component == LifecycleComponent.ANDROID_SERVICE) throw java.io.IOException("TEST_RECEIPT_RESPONSE_LOST")
                return base.recordClientReceipt(receipt)
            }
        }
        val saved = mutableListOf<ComposerDraft?>()
        val drafts = FakeComposerDraftService().apply {
            saveDraftLambda = { _, _, draft, isVolatile -> if (!isVolatile) saved += draft }
        }
        createPresenter(isRichTextEditorEnabled = false, draftService = drafts,
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("retained draft")
            state.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()
            assertThat(saved.map { it?.plainText }).containsExactly("retained draft")
            val original = base.startedRequests.single { it.lifecycleId.name == "LC_14" }
            assertThat(pending.map { it.operationId }.distinct()).containsExactly(original.operationId)
            assertThat(pending.map { it.component }).containsExactly(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
            assertThat(base.clientReceipts.single().component).isEqualTo(LifecycleComponent.ANDROID_FRONTEND)
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("retained draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - common insertion preserves drafts and never sends in both editors`() = runTest {
        for (rich in listOf(false, true)) {
            val generation = MutableStateFlow(0L)
            val repository = mockk<CommonInfoRepository>(relaxed = true) {
                every { authorizationRevision } returns generation
                coEvery { sendText(any()) } returns "COMMON VALUE"
            }
            val sent = mutableListOf<String>()
            val timeline = FakeTimeline().apply {
                sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) }
            }
            val presenter = createPresenter(
                room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
                commonInfoRepository = repository, isRichTextEditorEnabled = rich,
            )
            presenter.test {
                val state = awaitFirstItem()
                state.textEditorState.setMarkdown("draft ")
                var completed: Result<Unit>? = null
                state.eventSink(MessageComposerEvent.InsertCommonInfo(CommonInfoItem(title = "test")) { completed = it })
                advanceUntilIdle()
                assertThat(completed?.isSuccess).isTrue()
                assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("draft COMMON VALUE")
                assertThat(sent).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `present - common insertion keeps text typed during read and rejects concurrent insertion`() = runTest {
        val started = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val generation = MutableStateFlow(0L)
        val repository = mockk<CommonInfoRepository>(relaxed = true) {
            every { authorizationRevision } returns generation
            coEvery { sendText(any()) } coAnswers { started.complete(Unit); allowRead.await(); "COMMON" }
        }
        val presenter = createPresenter(commonInfoRepository = repository)
        presenter.test {
            val state = awaitFirstItem()
            var completed: Result<Unit>? = null
            state.eventSink(MessageComposerEvent.InsertCommonInfo(CommonInfoItem(title = "test")) { completed = it })
            started.await()
            state.textEditorState.setMarkdown("new draft ")
            var second: Result<Unit>? = null
            state.eventSink(MessageComposerEvent.InsertCommonInfo(CommonInfoItem(title = "test")) { second = it })
            assertThat(second?.isFailure).isTrue()
            allowRead.complete(Unit)
            advanceUntilIdle()
            assertThat(completed?.isSuccess).isTrue()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("new draft COMMON")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - common insertion rejects plaintext returned after authorization change`() = runTest {
        val generation = MutableStateFlow(0L)
        val repository = mockk<CommonInfoRepository>(relaxed = true) {
            every { authorizationRevision } returns generation
            coEvery { sendText(any()) } coAnswers { generation.value = 1L; "PRIVATE VALUE" }
        }
        val presenter = createPresenter(commonInfoRepository = repository)
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            var completed: Result<Unit>? = null
            state.eventSink(MessageComposerEvent.InsertCommonInfo(CommonInfoItem(title = "test")) { completed = it })
            advanceUntilIdle()
            assertThat(completed?.isFailure).isTrue()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - common direct send sends only the item and preserves the composer draft`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val repository = mockk<CommonInfoRepository>(relaxed = true) {
            coEvery { sendText(any()) } returns "COMMON ONLY"
        }
        val sent = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) }
        }
        val ownerCheckedTimeline = object : Timeline by timeline {
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String,
                htmlBody: String?,
                intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?,
                msgType: MsgType,
                asPlainText: Boolean,
            ): Result<Unit> {
                assertTextReceiptOwner(coordinator, transactionId.value)
                return timeline.sendMessageTransaction(transactionId, body, htmlBody, intentionalMentions, repliedToEventId, msgType, asPlainText)
            }
        }
        val presenter = createPresenter(
            room = FakeJoinedRoom(liveTimeline = ownerCheckedTimeline, typingNoticeResult = { Result.success(Unit) }),
            commonInfoRepository = repository, appOperationalStateCoordinator = coordinator,
        )
        presenter.test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            var completed: Result<Unit>? = null
            state.eventSink(MessageComposerEvent.SendCommonInfo(CommonInfoItem(title = "test")) { completed = it })
            advanceUntilIdle()
            assertThat(completed?.isSuccess).isTrue()
            assertThat(sent).containsExactly("COMMON ONLY")
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emotion sends preserve draft and each tap has its own lifecycle operation`() = runTest {
        val sent = mutableListOf<String>()
        val coordinator = FakeAppOperationalStateCoordinator()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ ->
                assertThat(coordinator.startedRequests).isNotEmpty()
                sent += body
                Result.success(Unit)
            }
        }
        val ownerCheckedTimeline = object : Timeline by timeline {
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String,
                htmlBody: String?,
                intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?,
                msgType: MsgType,
                asPlainText: Boolean,
            ): Result<Unit> {
                assertTextReceiptOwner(coordinator, transactionId.value)
                return timeline.sendMessageTransaction(transactionId, body, htmlBody, intentionalMentions, repliedToEventId, msgType, asPlainText)
            }
        }
        createPresenter(room = FakeJoinedRoom(liveTimeline = ownerCheckedTimeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            state.eventSink(MessageComposerEvent.SendEmotion("fireworks"))
            state.eventSink(MessageComposerEvent.SendEmotion("fortune"))
            advanceUntilIdle()
            assertThat(sent).containsExactly("🎆", "[福气满满]").inOrder()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            assertThat(coordinator.startedRequests.filter { it.lifecycleId == io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_13 }
                .map { it.operationId }.toSet()).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emotion cannot bypass edit mode or send an unknown identifier`() = runTest {
        val sent = mutableListOf<String>()
        val timeline = FakeTimeline().apply { sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) } }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })).test {
            val state = awaitFirstItem()
            state.eventSink(MessageComposerEvent.SendEmotion("not-in-catalog"))
            state.eventSink(MessageComposerEvent.SetMode(anEditMode()))
            advanceUntilIdle()
            expectMostRecentItem().eventSink(MessageComposerEvent.SendEmotion("fireworks"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emotion cannot bypass flash read mode`() = runTest {
        val sent = mutableListOf<String>()
        val timeline = FakeTimeline().apply { sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) } }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })).test {
            awaitFirstItem().eventSink(MessageComposerEvent.ToggleBurnAfterRead)
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.isBurnAfterReadEnabled).isTrue()
            state.eventSink(MessageComposerEvent.SendEmotion("fireworks"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emotion lifecycle rejection preserves draft and does not call transport`() = runTest {
        val sent = mutableListOf<String>()
        val timeline = FakeTimeline().apply { sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) } }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(startFailure = { IllegalStateException("test rejection") })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            state.eventSink(MessageComposerEvent.SendEmotion("fireworks"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            assertThat(expectMostRecentItem().slashCommandAction).isInstanceOf(AsyncAction.Failure::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun ReceiveTurbine<MessageComposerState>.backToNormalMode(state: MessageComposerState, skipCount: Int = 0): MessageComposerState {
        state.eventSink.invoke(MessageComposerEvent.CloseSpecialMode)
        skipItems(skipCount)
        val normalState = awaitItem()
        assertThat(normalState.mode).isEqualTo(MessageComposerMode.Normal)
        return normalState
    }

    @Test fun `recent image batch appears once and does not cascade on reopen`() = runTest {
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long) = listOf(
                io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/1"), nowEpochMillis),
                io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/2"), nowEpochMillis),
            )
        }
        createPresenter(pickerProvider = provider).test {
            awaitFirstItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            val opened = expectMostRecentItem()
            assertThat(opened.showAttachmentSourcePicker).isTrue()
            assertThat(opened.recentImages).hasSize(2)
            opened.eventSink(MessageComposerEvent.DismissAttachmentMenu)
            advanceUntilIdle()
            expectMostRecentItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().recentImages).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `recent query cancelled by another action cannot reopen the panel`() = runTest {
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long): List<io.element.android.libraries.mediapickers.api.RecentImage> {
                kotlinx.coroutines.delay(100L)
                return listOf(io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/1"), nowEpochMillis))
            }
        }
        createPresenter(pickerProvider = provider).test {
            val state = awaitFirstItem()
            state.eventSink(MessageComposerEvent.AddAttachment)
            state.eventSink(MessageComposerEvent.DismissAttachmentMenu)
            advanceUntilIdle()
            assertThat(state.showAttachmentSourcePicker).isFalse()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `recent query timeout still opens normal attachments without a late row`() = runTest {
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long): List<io.element.android.libraries.mediapickers.api.RecentImage> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(1_000L)
                }
                return emptyList()
            }
        }
        createPresenter(pickerProvider = provider).test {
            awaitFirstItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceTimeBy(251L)
            runCurrent()
            val state = expectMostRecentItem()
            assertThat(state.showAttachmentSourcePicker).isTrue()
            assertThat(state.recentImages).isEmpty()
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `recent selection opens one preview with only tapped image selected`() = runTest {
        val one = Uri.parse("content://media/1")
        val two = Uri.parse("content://media/2")
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long) = listOf(one, two).map {
                io.element.android.libraries.mediapickers.api.RecentImage(it, nowEpochMillis)
            }
            override suspend fun selectRecentImages(operation: io.element.android.libraries.mediapickers.api.MediaPickOperation, uris: List<Uri>) =
                io.element.android.libraries.mediapickers.api.MediaPickBatchResult(operation, uris.map {
                    io.element.android.libraries.mediapickers.api.MediaPickItemResult.Ready(
                        io.element.android.libraries.mediapickers.api.PickedMedia(it, "image/png", operationId = operation.operationId),
                    )
                })
        }
        var previews = 0
        var attachments = emptyList<Attachment>()
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { items, _ -> previews++; attachments = items })
        createPresenter(pickerProvider = provider, navigator = navigator).test {
            awaitFirstItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            val state = expectMostRecentItem()
            state.eventSink(MessageComposerEvent.SelectRecentImage(two))
            state.eventSink(MessageComposerEvent.SelectRecentImage(two))
            advanceUntilIdle()
            assertThat(previews).isEqualTo(1)
            assertThat(attachments).hasSize(1)
            assertThat(attachments.map { (it as Attachment.Media).initiallySelected }).containsExactly(true)
            assertThat(attachments.map { (it as Attachment.Media).focused }).containsExactly(true)
            assertThat(attachments.all { (it as Attachment.Media).fromRecentImages }).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test fun `relay edit retries the original operation and preserves the composer draft`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val coordinator = relayAdmissionCoordinator(base)
        val calls = mutableListOf<Pair<String?, String>>()
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> {
                assertThat(relayEventId).isEqualTo(AN_EVENT_ID)
                assertThat(itemId).isEqualTo("my-stable-entry")
                assertThat(expectedRevision).isEqualTo(3L)
                assertThat(currentLifecycleOperationContext()?.lifecycleId?.name).isEqualTo("LC_15")
                calls += currentLifecycleOperationContext()?.operationId to entry
                return if (calls.size == 1) Result.failure(IllegalStateException("queue response lost")) else Result.success(Unit)
            }
        }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            val results = mutableListOf<Result<Unit>>()
            val edit = MessageComposerEvent.EditRelayEntry(AN_EVENT_ID, "my-stable-entry", 3L, "  edited entry  ",
                onComplete = { results += it }, operationId = "11111111-1111-4111-8111-111111111111")
            state.eventSink(edit)
            advanceUntilIdle()
            assertThat(results.single().isFailure).isTrue()
            assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }).isEmpty()
            var recovered: io.element.android.libraries.matrix.api.timeline.RelayEditRecovery? = null
            state.eventSink(MessageComposerEvent.LoadRelayEditRecovery(AN_EVENT_ID) { recovered = it.getOrThrow() })
            advanceUntilIdle()
            assertThat(recovered).isEqualTo(io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(
                "relay-edit-${edit.operationId}", AN_EVENT_ID.value, "my-stable-entry", 3L, "edited entry",
            ))
            state.eventSink(edit)
            advanceUntilIdle()
            assertThat(results.last().isSuccess).isTrue()
            assertThat(calls.map { it.first }.distinct()).containsExactly("relay-edit-${edit.operationId}")
            assertThat(calls.map { it.second }).containsExactly("edited entry", "edited entry").inOrder()
            assertThat(base.startedRequests.filter { it.lifecycleId.name == "LC_15" }).hasSize(2)
            assertThat(base.startedRequests.filter { it.lifecycleId.name == "LC_15" }.map { it.eventIdHash }.distinct()).hasSize(1)
            assertThat(base.startedRequests.filter { it.lifecycleId.name == "LC_15" }.all { it.accountIdHash != null && it.matrixDeviceIdHash != null && it.roomIdHash != null }).isTrue()
            assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }).hasSize(2)
            assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }.map { it.operationId }.distinct()).containsExactly("relay-edit-${edit.operationId}")
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `relay accepted original intent is observed without a second edit`() = runTest {
        for (terminal in listOf(false, true)) {
            val base = FakeAppOperationalStateCoordinator()
            val coordinator = relayAdmissionCoordinator(base, terminal = terminal, externalComplete = true)
            var edits = 0
            var observations = 0
            val timeline = object : Timeline by FakeTimeline() {
                override suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> {
                    edits++
                    return Result.success(Unit)
                }
                override suspend fun verifyAcceptedRelayEditIntent(relayEventId: EventId, itemId: String,
                    expectedRevision: Long, entry: String,
                ): Result<Unit> {
                    assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo("relay-edit-22222222-2222-4222-8222-222222222222")
                    assertThat(relayEventId).isEqualTo(AN_EVENT_ID)
                    assertThat(itemId).isEqualTo("my-stable-entry")
                    assertThat(expectedRevision).isEqualTo(3L)
                    assertThat(entry).isEqualTo("original edited entry")
                    observations++
                    return Result.success(Unit)
                }
            }
            createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
                appOperationalStateCoordinator = coordinator).test {
                val state = awaitFirstItem()
                var result: Result<Unit>? = null
                state.eventSink(MessageComposerEvent.EditRelayEntry(AN_EVENT_ID, "my-stable-entry", 3L, "original edited entry",
                    onComplete = { result = it }, operationId = "22222222-2222-4222-8222-222222222222"))
                advanceUntilIdle()
                assertThat(result?.isSuccess).isTrue()
                assertThat(observations).isEqualTo(if (terminal) 2 else 1)
                assertThat(edits).isEqualTo(0)
                assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }).hasSize(if (terminal) 0 else 2)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test fun `relay accepted operation with conflicting intent preserves draft and reports failure`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        var edits = 0
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> {
                edits++
                return Result.success(Unit)
            }
            override suspend fun verifyAcceptedRelayEditIntent(relayEventId: EventId, itemId: String,
                expectedRevision: Long, entry: String,
            ): Result<Unit> = Result.failure(IllegalStateException("immutable encrypted intent differs"))
        }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = relayAdmissionCoordinator(base, terminal = true)).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep draft")
            var result: Result<Unit>? = null
            state.eventSink(MessageComposerEvent.EditRelayEntry(AN_EVENT_ID, "my-stable-entry", 3L, "changed entry",
                onComplete = { result = it }, operationId = "33333333-3333-4333-8333-333333333333"))
            advanceUntilIdle()
            assertThat(result?.isFailure).isTrue()
            assertThat(edits).isEqualTo(0)
            assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }).isEmpty()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep draft")
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test fun `loading relay recovery observes original terminal and clears only verified journal without admission`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val recovery = io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(
            "relay-edit-22222222-2222-4222-8222-222222222222", AN_EVENT_ID.value, "my-stable-entry", 3, "original edited entry")
        val delegate = FakeTimeline()
        delegate.retainRelayEditRecovery(recovery).getOrThrow()
        var verified = 0
        val timeline = object : Timeline by delegate {
            override suspend fun verifyAcceptedRelayEditIntent(relayEventId: EventId, itemId: String,
                expectedRevision: Long, entry: String): Result<Unit> {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(recovery.operationId)
                assertThat(entry).isEqualTo(recovery.entry)
                verified++
                return Result.success(Unit)
            }
            override suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> =
                error("Recovery cannot edit")
        }
        val template = relayAdmissionCoordinator(base, terminal = true).start(io.element.android.security.dataprotection.api.lifecycleOperationRequest(
            io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_15, "relay_operation", "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), recovery.operationId))
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation = error("Recovery cannot admit")
            override suspend fun operation(operationId: String): LifecycleOperation = template.copy(subjects = template.subjects.copy(
                accountIdHash = accountIdHash(FakeJoinedRoom().sessionId.value),
                matrixDeviceIdHash = accountIdHash(FakeMatrixClient().deviceId.value),
                roomIdHash = accountIdHash(A_ROOM_ID.value),
                eventIdHash = accountIdHash(AN_EVENT_ID.value)))
        }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            appOperationalStateCoordinator = coordinator).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown("keep ordinary draft")
            var result: Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?>? = null
            state.eventSink(MessageComposerEvent.LoadRelayEditRecovery(AN_EVENT_ID) { result = it })
            advanceUntilIdle()
            assertThat(result?.isSuccess).isTrue()
            assertThat(result?.getOrThrow()).isNull()
            assertThat(verified).isEqualTo(1)
            assertThat(delegate.pendingRelayEditRecovery(AN_EVENT_ID).getOrThrow()).isNull()
            assertThat(base.clientReceipts.filter { it.operationId.startsWith("relay-edit-") }).isEmpty()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("keep ordinary draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun relayAdmissionCoordinator(base: FakeAppOperationalStateCoordinator, terminal: Boolean = false,
        externalComplete: Boolean = false,
    ) = object : AppOperationalStateCoordinator by base {
        override suspend fun operation(operationId: String): LifecycleOperation = admission(base.operation(operationId))
        override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation = admission(base.start(request))
        private fun admission(operation: LifecycleOperation): LifecycleOperation {
            if (operation.lifecycleId.name != "LC_15") return operation
            val android = setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
            val required = android + setOf(LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE,
                LifecycleComponent.DATABASE, LifecycleComponent.KMS)
            return operation.copy(status = if (terminal) LifecycleOperationStatus.SUCCEEDED else LifecycleOperationStatus.RUNNING,
                terminalReceiptId = "original-terminal".takeIf { terminal },
                steps = required.map { component ->
                    val complete = terminal || externalComplete && component !in android
                    LifecycleStep(stepId = "original-${component.name}", component = component,
                        status = if (complete) LifecycleStepStatus.COMPLETE else LifecycleStepStatus.PENDING,
                        attemptCount = 1, authorityRevision = "authority-1".takeIf { complete },
                        receiptReference = "original-receipt-${component.name}".takeIf { complete },
                        updatedAtEpochMillis = operation.updatedAtEpochMillis)
                })
        }
    }

    @Test
    fun `gallery opens a new selection even when an old editor draft exists`() = runTest {
        val store = mockk<io.element.android.features.messages.impl.draft.MediaSelectionDraftStore>(relaxed = true)
        val old = mockk<Attachment.Media>()
        coEvery { store.latestSources(any(), any()) } returns listOf(old)
        val preview = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        pickerProvider.givenResult(null)
        createPresenter(mediaSelectionDraftStore = store,
            navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = preview)).test {
            val state = awaitFirstItem()
            state.eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            assertThat(awaitItem().showAttachmentSourcePicker).isTrue()
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            assertThat(pickerProvider.completedOperationIds).hasSize(1)
            preview.assertions().isNeverCalled()
            io.mockk.coVerify(exactly = 0) { store.clear(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `fresh screenshot expands to older gallery pages and selects only the tapped older image`() = runTest {
        val images = (80 downTo 40).map {
            io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/$it"), it * 1000L)
        }
        var selectedUris = emptyList<Uri>()
        var nextPageCalls = 0
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long) = listOf(images.first().copy(addedAtEpochMillis = nowEpochMillis))
            override suspend fun galleryImages(nowEpochMillis: Long, before: io.element.android.libraries.mediapickers.api.RecentImage?, pageSize: Int) =
                if (before == null) listOf(images.first().copy(addedAtEpochMillis = nowEpochMillis)) + images.drop(1).take(39)
                else {
                    nextPageCalls++
                    assertThat(before.uri).isEqualTo(images[39].uri)
                    images.drop(40)
                }
            override suspend fun selectRecentImages(operation: io.element.android.libraries.mediapickers.api.MediaPickOperation, uris: List<Uri>):
                io.element.android.libraries.mediapickers.api.MediaPickBatchResult {
                selectedUris = uris
                return io.element.android.libraries.mediapickers.api.MediaPickBatchResult(operation, uris.map {
                    io.element.android.libraries.mediapickers.api.MediaPickItemResult.Ready(
                        io.element.android.libraries.mediapickers.api.PickedMedia(it, "image/png", operationId = operation.operationId))
                })
            }
        }
        val preview = lambdaRecorder { _: ImmutableList<Attachment>, _: EventId? -> }
        createPresenter(pickerProvider = provider, navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = preview)).test {
            awaitFirstItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            val first = expectMostRecentItem()
            assertThat(first.recentImages).hasSize(40)
            first.eventSink(MessageComposerEvent.LoadMoreRecentImages(images[39].uri))
            first.eventSink(MessageComposerEvent.LoadMoreRecentImages(images[39].uri))
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(nextPageCalls).isEqualTo(1)
            assertThat(loaded.recentImages.map { it.uri }).containsExactlyElementsIn(images.map { it.uri }).inOrder()
            loaded.eventSink(MessageComposerEvent.SelectRecentImage(images.last().uri))
            advanceUntilIdle()
            assertThat(selectedUris).containsExactly(images.last().uri)
            preview.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `gallery page completing after dismissal cannot restore the strip`() = runTest {
        val provider = object : PickerProvider by FakePickerProvider() {
            override suspend fun recentImages(nowEpochMillis: Long) = listOf(
                io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/100"), nowEpochMillis))
            override suspend fun galleryImages(nowEpochMillis: Long, before: io.element.android.libraries.mediapickers.api.RecentImage?, pageSize: Int):
                List<io.element.android.libraries.mediapickers.api.RecentImage> {
                if (before != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(100L) }
                return (100 downTo 61).map {
                    io.element.android.libraries.mediapickers.api.RecentImage(Uri.parse("content://media/$it"), nowEpochMillis)
                }
            }
        }
        createPresenter(pickerProvider = provider).test {
            awaitFirstItem().eventSink(MessageComposerEvent.AddAttachment)
            advanceUntilIdle()
            val state = expectMostRecentItem()
            state.eventSink(MessageComposerEvent.LoadMoreRecentImages(state.recentImages.last().uri))
            runCurrent()
            state.eventSink(MessageComposerEvent.DismissRecentImages)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().recentImages).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FC015 rich text rapid taps consume one draft across handoff`() = rapidTapsAcrossHandoff(richText = true)

    @Test
    fun `FC015 markdown rapid taps consume one draft across handoff`() = rapidTapsAcrossHandoff(richText = false)

    private fun rapidTapsAcrossHandoff(richText: Boolean) = runTest {
        val binding = CompletableDeferred<Unit>()
        val allowBinding = CompletableDeferred<Unit>()
        val staging = CompletableDeferred<Unit>()
        val allowStaging = CompletableDeferred<Unit>()
        val sending = CompletableDeferred<Unit>()
        val allowTransport = CompletableDeferred<Unit>()
        val boundOperations = mutableListOf<String>()
        val stagedTransactions = mutableListOf<String>()
        val sentTransactions = mutableListOf<String>()
        val coordinator = FakeAppOperationalStateCoordinator()
        val drafts = object : ComposerDraftService by FakeComposerDraftService() {
            override suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String) {
                boundOperations += operationId
                binding.complete(Unit)
                allowBinding.await()
            }
        }
        val outbox = object : io.element.android.security.contentprotection.api.OfflineOutboxService {
            override suspend fun stage(message: io.element.android.security.contentprotection.api.OfflineTextOutboxMessage): Boolean {
                stagedTransactions += message.transactionId
                staging.complete(Unit)
                allowStaging.await()
                return true
            }
            override suspend fun messages(accountId: String, roomId: String) = emptyList<io.element.android.security.contentprotection.api.OfflineTextOutboxMessage>()
            override suspend fun remove(accountId: String, roomId: String, transactionId: String) = true
        }
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String, htmlBody: String?, intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?, msgType: MsgType, asPlainText: Boolean,
            ): Result<Unit> {
                sentTransactions += transactionId.value
                sending.complete(Unit)
                allowTransport.await()
                return Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })
        createPresenter(room = room, draftService = drafts, isRichTextEditorEnabled = richText,
            appOperationalStateCoordinator = coordinator,
            timelineController = TimelineController(room, timeline, offlineOutboxService = outbox),
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            if (richText) state.textEditorState.setHtml(A_MESSAGE) else state.textEditorState.setMarkdown(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            binding.await()
            repeat(2) { state.eventSink(MessageComposerEvent.SendMessage) }
            allowBinding.complete(Unit)
            staging.await()
            repeat(2) { state.eventSink(MessageComposerEvent.SendMessage) }
            allowStaging.complete(Unit)
            sending.await()
            try {
                assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
                // These clicks arrive after capture is released, while the original network send waits.
                repeat(3) { state.eventSink(MessageComposerEvent.SendMessage) }
                runCurrent()
                assertThat(boundOperations).hasSize(1)
                assertThat(stagedTransactions).hasSize(1)
                assertThat(sentTransactions).containsExactly(stagedTransactions.single())
                val operation = coordinator.startedRequests.single { it.trigger == "send" }
                assertThat(boundOperations).containsExactly(operation.operationId)
                assertThat(operation.operationId).isEqualTo(
                    ordinaryMessageLifecycleRequest(room, FakeMatrixClient(sessionId = room.sessionId), "send", stagedTransactions.single()).operationId)
            } finally { allowTransport.complete(Unit) }
            advanceUntilIdle()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FC015 actual edit during persistence remains visible and can send separately`() = runTest {
        val staging = CompletableDeferred<Unit>()
        val allowStaging = CompletableDeferred<Unit>()
        val transactions = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val outbox = object : io.element.android.security.contentprotection.api.OfflineOutboxService {
            override suspend fun stage(message: io.element.android.security.contentprotection.api.OfflineTextOutboxMessage): Boolean {
                transactions += message.transactionId
                if (transactions.size == 1) { staging.complete(Unit); allowStaging.await() }
                return true
            }
            override suspend fun messages(accountId: String, roomId: String) = emptyList<io.element.android.security.contentprotection.api.OfflineTextOutboxMessage>()
            override suspend fun remove(accountId: String, roomId: String, transactionId: String) = true
        }
        val timeline = FakeTimeline().apply { sendMessageLambda = { body, _, _, _, _ -> sent += body; Result.success(Unit) } }
        val room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })
        createPresenter(room = room, isRichTextEditorEnabled = false,
            timelineController = TimelineController(room, timeline, offlineOutboxService = outbox),
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            staging.await()
            state.textEditorState.setMarkdown(ANOTHER_MESSAGE)
            state.eventSink(MessageComposerEvent.TypingNotice(true))
            runCurrent()
            allowStaging.complete(Unit)
            advanceUntilIdle()
            assertThat(sent).containsExactly(A_MESSAGE)
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo(ANOTHER_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            advanceUntilIdle()
            assertThat(sent).containsExactly(A_MESSAGE, ANOTHER_MESSAGE).inOrder()
            assertThat(transactions.distinct()).hasSize(2)
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FC015 intentional identical input sends again while first transport waits`() = runTest {
        val sending = CompletableDeferred<Unit>()
        val secondSending = CompletableDeferred<Unit>()
        val allowFirst = CompletableDeferred<Unit>()
        val transactions = mutableListOf<String>()
        val operations = mutableListOf<String>()
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String, htmlBody: String?, intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?, msgType: MsgType, asPlainText: Boolean,
            ): Result<Unit> {
                assertThat(body).isEqualTo(A_MESSAGE)
                transactions += transactionId.value
                operations += requireNotNull(currentLifecycleOperationContext()).operationId
                if (transactions.size == 1) { sending.complete(Unit); allowFirst.await() } else secondSending.complete(Unit)
                return Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })
        createPresenter(room = room, isRichTextEditorEnabled = false,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            sending.await()
            try {
                assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
                state.textEditorState.setMarkdown(A_MESSAGE)
                state.eventSink(MessageComposerEvent.TypingNotice(true))
                state.eventSink(MessageComposerEvent.SendMessage)
                secondSending.await()
                assertThat(transactions.distinct()).hasSize(2)
                assertThat(operations.distinct()).hasSize(2)
                assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            } finally { allowFirst.complete(Unit) }
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun `FC015 empty send taps do not invalidate original draft receipt consumption`() = runTest {
        val sending = CompletableDeferred<Unit>()
        val allowTransport = CompletableDeferred<Unit>()
        var bound: ComposerDraft? = null
        var matrixSent = false
        val drafts = object : ComposerDraftService by FakeComposerDraftService() {
            override suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String) { bound = draft }
            override suspend fun reconcileSentTextDraft(roomId: RoomId, canConsume: () -> Boolean):
                io.element.android.features.messages.impl.draft.ConsumedTextDraft? {
                if (!matrixSent || !canConsume()) return null
                return bound?.let {
                    bound = null
                    io.element.android.features.messages.impl.draft.ConsumedTextDraft("original-version", it)
                }
            }
        }
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendMessageTransaction(
                transactionId: io.element.android.libraries.matrix.api.core.TransactionId,
                body: String, htmlBody: String?, intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?, msgType: MsgType, asPlainText: Boolean,
            ): Result<Unit> {
                sending.complete(Unit)
                allowTransport.await()
                matrixSent = true
                return Result.success(Unit)
            }
        }
        createPresenter(room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) }),
            draftService = drafts, isRichTextEditorEnabled = false,
            slashCommandService = FakeSlashCommandService(parseResult = { _, _, _ -> SlashCommand.NotACommand })).test {
            val state = awaitFirstItem()
            state.textEditorState.setMarkdown(A_MESSAGE)
            state.eventSink(MessageComposerEvent.SendMessage)
            sending.await()
            try {
                assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
                assertThat(bound?.plainText).isEqualTo(A_MESSAGE)
                repeat(3) { state.eventSink(MessageComposerEvent.SendMessage) }
                runCurrent()
            } finally { allowTransport.complete(Unit) }
            advanceUntilIdle()
            assertThat(bound).isNull()
            assertThat(state.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }


    private fun TestScope.createPresenter(
        room: JoinedRoom = FakeJoinedRoom(
            typingNoticeResult = { Result.success(Unit) }
        ),
        timeline: Timeline = room.liveTimeline,
        navigator: MessagesNavigator = FakeMessagesNavigator(),
        pickerProvider: PickerProvider = this@MessageComposerPresenterTest.pickerProvider,
        locationService: LocationService = FakeLocationService(true),
        sessionPreferencesStore: SessionPreferencesStore = InMemorySessionPreferencesStore(),
        mediaPreProcessor: MediaPreProcessor = this@MessageComposerPresenterTest.mediaPreProcessor,
        snackbarDispatcher: SnackbarDispatcher = this@MessageComposerPresenterTest.snackbarDispatcher,
        permissionPresenter: PermissionsPresenter = FakePermissionsPresenter(),
        permalinkBuilder: PermalinkBuilder = FakePermalinkBuilder(),
        permalinkParser: PermalinkParser = FakePermalinkParser(),
        mentionSpanProvider: MentionSpanProvider = MentionSpanProvider(
            permalinkParser = permalinkParser,
            mentionSpanFormatter = FakeMentionSpanFormatter(),
            mentionSpanTheme = MentionSpanTheme(A_USER_ID)
        ),
        textPillificationHelper: TextPillificationHelper = FakeTextPillificationHelper(),
        isRichTextEditorEnabled: Boolean = true,
        draftService: ComposerDraftService = FakeComposerDraftService(),
        mediaOptimizationConfigProvider: FakeMediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
        isInThread: Boolean = false,
        slashCommandService: SlashCommandService = FakeSlashCommandService(),
        appOperationalStateCoordinator: io.element.android.security.dataprotection.api.AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        matrixClient: MatrixClient = FakeMatrixClient(sessionId = room.sessionId),
        commonInfoRepository: CommonInfoRepository = mockk(relaxed = true),
        mediaSelectionDraftStore: io.element.android.features.messages.impl.draft.MediaSelectionDraftStore? = null,
        timelineController: TimelineController = TimelineController(room, timeline),
    ) = MessageComposerPresenter(
        navigator = navigator,
        sessionCoroutineScope = this,
        isInThread = isInThread,
        room = room,
        matrixClient = matrixClient,
        mediaPickerProvider = pickerProvider,
        sessionPreferencesStore = sessionPreferencesStore,
        localMediaFactory = localMediaFactory,
        mediaSenderFactory = MediaSenderFactory { timelineMode ->
            DefaultMediaSender(
                preProcessor = mediaPreProcessor,
                room = room,
                timelineMode = timelineMode,
                mediaOptimizationConfigProvider = {
                    MediaOptimizationConfig(
                        compressImages = true,
                        videoCompressionPreset = VideoCompressionPreset.STANDARD
                    )
                }
            )
        },
        snackbarDispatcher = snackbarDispatcher,
        analyticsService = analyticsService,
        locationService = locationService,
        messageComposerContext = DefaultMessageComposerContext(),
        richTextEditorStateFactory = TestRichTextEditorStateFactory(),
        roomAliasSuggestionsDataSource = FakeRoomAliasSuggestionsDataSource(),
        permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionPresenter),
        permalinkParser = permalinkParser,
        permalinkBuilder = permalinkBuilder,
        timelineController = timelineController,
        draftService = draftService,
        mentionSpanProvider = mentionSpanProvider,
        pillificationHelper = textPillificationHelper,
        suggestionsProcessor = SuggestionsProcessor(slashCommandService = slashCommandService),
        mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
        notificationConversationService = notificationConversationService,
        slashCommandService = slashCommandService,
        appOperationalStateCoordinator = appOperationalStateCoordinator,
        localAttachmentPreviewLeaseService = io.mockk.mockk(relaxed = true),
        commonInfoRepository = commonInfoRepository,
        mediaSelectionDraftStore = mediaSelectionDraftStore,
    ).apply {
        isTesting = true
        showTextFormatting = isRichTextEditorEnabled
    }

    private suspend fun assertTextReceiptOwner(coordinator: FakeAppOperationalStateCoordinator, transactionId: String) {
        val context = requireNotNull(currentLifecycleOperationContext())
        val owner = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[io.element.android.security.dataprotection.api.TextVersionReceiptOwner])
        val request = coordinator.startedRequests.single { it.operationId == context.operationId }
        val room = FakeJoinedRoom()
        val expected = ordinaryMessageLifecycleRequest(room, FakeMatrixClient(sessionId = room.sessionId), "send", transactionId)
        assertThat(request.operationId).isEqualTo(expected.operationId)
        assertThat(owner.operationId).isEqualTo(request.operationId)
        assertThat(owner.accountIdHash).isEqualTo(expected.accountIdHash)
        assertThat(owner.matrixDeviceIdHash).isEqualTo(expected.matrixDeviceIdHash)
        assertThat(owner.roomIdHash).isEqualTo(expected.roomIdHash)
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitFirstItem(): T {
        skipItems(1)
        return awaitItem()
    }
}

fun anEditMode(
    eventOrTransactionId: EventOrTransactionId = AN_EVENT_ID.toEventOrTransactionId(),
    message: String = A_MESSAGE,
) = MessageComposerMode.Edit(eventOrTransactionId, message)

fun anEditCaptionMode(
    eventOrTransactionId: EventOrTransactionId = AN_EVENT_ID.toEventOrTransactionId(),
    caption: String = A_CAPTION,
) = MessageComposerMode.EditCaption(
    eventOrTransactionId = eventOrTransactionId,
    content = caption,
)

fun aReplyMode() = MessageComposerMode.Reply(
    replyToDetails = InReplyToDetails.Loading(AN_EVENT_ID),
    hideImage = false,
)
