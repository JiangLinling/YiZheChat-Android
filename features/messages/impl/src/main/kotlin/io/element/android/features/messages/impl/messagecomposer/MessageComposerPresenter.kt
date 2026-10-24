/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.Composer
import im.vector.app.features.analytics.plan.Interaction
import io.element.android.features.location.api.LocationService
import io.element.android.features.messages.impl.MessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.Attachment.Media
import io.element.android.features.messages.impl.attachments.preview.error.sendAttachmentError
import io.element.android.features.messages.impl.commoninfo.CommonInfoRepository
import io.element.android.features.messages.impl.draft.ComposerDraftService
import io.element.android.features.messages.impl.draft.MediaSelectionDraftStore
import io.element.android.features.messages.impl.messagecomposer.suggestions.RoomAliasSuggestionsDataSource
import io.element.android.features.messages.impl.messagecomposer.suggestions.SuggestionsProcessor
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.utils.TextPillificationHelper
import io.element.android.libraries.androidutils.clipboard.InAppClipboard
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.api.room.getDirectRoomMember
import io.element.android.libraries.matrix.api.room.powerlevels.use
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.matrix.ui.messages.reply.map
import io.element.android.libraries.mediapickers.api.MediaPickBatchResult
import io.element.android.libraries.mediapickers.api.MediaPickCancellation
import io.element.android.libraries.mediapickers.api.MediaPickFailureReason
import io.element.android.libraries.mediapickers.api.MediaPickItemResult
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediapickers.api.RecentImage
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.push.api.notifications.conversations.NotificationConversationService
import io.element.android.libraries.slashcommands.api.SlashCommand
import io.element.android.libraries.slashcommands.api.SlashCommandService
import io.element.android.libraries.slashcommands.api.message
import io.element.android.libraries.textcomposer.mentions.MentionSpanProvider
import io.element.android.libraries.textcomposer.mentions.ResolvedSuggestion
import io.element.android.libraries.textcomposer.model.MarkdownTextEditorState
import io.element.android.libraries.textcomposer.model.Message
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.Suggestion
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import io.element.android.security.dataprotection.api.RelayEditException
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.performIdempotentRelayEdit
import io.element.android.security.dataprotection.api.performMessageWithDeferredReceipts
import io.element.android.security.dataprotection.api.reconcileOriginalRelayEdit
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analyticsproviders.api.trackers.captureInteraction
import io.element.android.wysiwyg.compose.RichTextEditorState
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val TIMELINE_PERFORMANCE_TAG = "YiZheTimelinePerf"
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MEDIA_PICK_OPERATION_TIMEOUT_MILLIS = 10 * 60_000L

/** Bind the originating LC-13 action to its actual session owner, never to an optional UI snapshot. */
internal fun ordinaryMessageLifecycleRequest(room: JoinedRoom, client: MatrixClient, trigger: String, transactionId: String? = null) =
    lifecycleOperationRequest(
        lifecycleId = FullAppLifecycleId.LC_13,
        trigger = trigger,
        accountIdHash = accountIdHash(client.sessionId.value).also {
            require(client.sessionId == room.sessionId) { "Message lifecycle session owner does not match the room." }
        },
        matrixDeviceIdHash = accountIdHash(client.deviceId.value),
        roomIdHash = accountIdHash(room.roomId.value),
        operationId = transactionId?.let {
            "lc13-send-" + accountIdHash("${client.sessionId.value}\u0000${client.deviceId.value}\u0000${room.roomId.value}\u0000$trigger\u0000$it")
        } ?: java.util.UUID.randomUUID().toString(),
    )

@Suppress("LargeClass")
@AssistedInject
class MessageComposerPresenter(
    @Assisted private val navigator: MessagesNavigator,
    @Assisted private val timelineController: TimelineController,
    @Assisted private val isInThread: Boolean,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val sessionPreferencesStore: SessionPreferencesStore,
    private val localMediaFactory: LocalMediaFactory,
    mediaSenderFactory: MediaSenderFactory,
    @Assisted private val snackbarDispatcher: SnackbarDispatcher,
    private val analyticsService: AnalyticsService,
    private val locationService: LocationService,
    private val messageComposerContext: DefaultMessageComposerContext,
    private val richTextEditorStateFactory: RichTextEditorStateFactory,
    private val roomAliasSuggestionsDataSource: RoomAliasSuggestionsDataSource,
    private val permalinkParser: PermalinkParser,
    private val permalinkBuilder: PermalinkBuilder,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
    private val draftService: ComposerDraftService,
    private val mentionSpanProvider: MentionSpanProvider,
    private val pillificationHelper: TextPillificationHelper,
    private val suggestionsProcessor: SuggestionsProcessor,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val notificationConversationService: NotificationConversationService,
    private val slashCommandService: SlashCommandService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val mediaSelectionDraftStore: MediaSelectionDraftStore? = null,
    private val localAttachmentPreviewLeaseService: LocalAttachmentPreviewLeaseService,
    private val commonInfoRepository: CommonInfoRepository,
) : Presenter<MessageComposerState> {
    @AssistedFactory
    interface Factory {
        fun create(
            snackbarDispatcher: SnackbarDispatcher,
            timelineController: TimelineController,
            navigator: MessagesNavigator,
            isInThread: Boolean,
        ): MessageComposerPresenter
    }

    private val mediaSender = mediaSenderFactory.create(timelineMode = timelineController.mainTimelineMode())

    private val cameraPermissionPresenter = permissionsPresenterFactory.create(Manifest.permission.CAMERA)
    private var pendingEvent: MessageComposerEvent? = null
    private val suggestionSearchTrigger = MutableStateFlow<Suggestion?>(null)

    // Used to disable some UI related elements in tests
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var isTesting: Boolean = false

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var showTextFormatting: Boolean by mutableStateOf(false)

    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
    @Composable
    override fun present(): MessageComposerState {
        val localCoroutineScope = rememberCoroutineScope()

        LaunchedEffect(mediaPickerProvider, appOperationalStateCoordinator) {
            mediaPickerProvider.pendingCancellations().forEach { cancellation ->
                handOffMediaPickCancellation(cancellation)
            }
        }

        LaunchedEffect(timelineController, commonInfoRepository) {
            val repository = commonInfoRepository ?: return@LaunchedEffect
            timelineController.activeTimelineFlow().flatMapLatest { it.timelineItems }.collect { items ->
                items.filterIsInstance<MatrixTimelineItem.Event>().forEach { item ->
                    val confirmation = commonInfoSendConfirmation(item) ?: return@forEach
                    try { repository.confirmSend(confirmation.contentId, room.roomId.value, confirmation.eventId, confirmation.timestamp) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Encrypted association is retained for a later convergence pass. */ }
                }
            }
        }

        val roomInfo by room.roomInfoFlow.collectAsState()

        val richTextEditorState = richTextEditorStateFactory.remember()
        if (isTesting) {
            richTextEditorState.isReadyToProcessActions = true
        }
        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)

        val cameraPermissionState = cameraPermissionPresenter.present()

        val canShareLocation = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            canShareLocation.value = locationService.isServiceAvailable()
        }

        var isBurnAfterReadEnabled: Boolean by rememberSaveable { mutableStateOf(false) }
        var pickGalleryMediaAsSticker by remember { mutableStateOf(false) }
        fun consumeMediaPickResult(
            result: MediaPickBatchResult,
            expectedSource: MediaPickSource,
            consume: (List<PickedMedia>) -> Unit,
        ) {
            localCoroutineScope.launch {
                val operationId = result.operation?.operationId
                var cancellationSettled = false
                try {
                    val cancelled = result.items.isNotEmpty() &&
                        result.items.all { it is MediaPickItemResult.Failed && it.reason == MediaPickFailureReason.CANCELLED }
                    if (!isAuthorizedMediaPick(result.operation, expectedSource, allowExpiredCancellation = cancelled)) {
                        reportPickerAuthorityFailure()
                        return@launch
                    }
                    reportPickerFailures(result)
                    if (expectedSource == MediaPickSource.GALLERY) {
                        runCatchingExceptions {
                            sessionPreferencesStore.acknowledgeSelectedImages(result.readyItems().map { it.uri.toString() }, System.currentTimeMillis())
                        }
                    }
                    consume(result.readyItems())
                    cancellationSettled = cancelled
                } finally {
                    withContext(NonCancellable) {
                        val temporaryCleanupFailed = result.items.any {
                            it is MediaPickItemResult.Failed && it.reason == MediaPickFailureReason.TEMPORARY_FILE_CLEANUP_FAILED
                        }
                        if (cancellationSettled) {
                            val cancellation = result.operation?.let(mediaPickerProvider::completeCancelledOperation)
                            if (cancellation == null) {
                                Timber.w("Media picker cancellation cleanup was not persisted; no completion receipt submitted")
                            } else {
                                handOffMediaPickCancellation(cancellation)
                            }
                        } else if (!temporaryCleanupFailed && operationId != null) {
                            mediaPickerProvider.completeOperation(operationId)
                        }
                    }
                }
            }
        }
        val galleryMediaPicker = mediaPickerProvider.registerOperationGalleryPicker { result ->
            consumeMediaPickResult(result, MediaPickSource.GALLERY) { pickedItems ->
                val firstItem = pickedItems.firstOrNull()
                val firstItemMimeType = firstItem?.mimeType
                if (pickGalleryMediaAsSticker && firstItemMimeType?.startsWith("image/") == true) {
                    pickGalleryMediaAsSticker = false
                    localCoroutineScope.launch { sendSticker(firstItem.uri, firstItemMimeType) }
                } else {
                    pickGalleryMediaAsSticker = false
                    val wasBurnAfterRead = isBurnAfterReadEnabled
                    if (handlePickedMedia(pickedItems, burnAfterRead = wasBurnAfterRead, fromGallery = true) && wasBurnAfterRead) {
                        isBurnAfterReadEnabled = false
                    }
                }
            }
        }
        val filesPicker = mediaPickerProvider.registerAttachmentPicker(
            accountId = room.sessionId.value,
            roomIdHash = room.roomId.value.sha256Hex(),
            previewLeaseService = localAttachmentPreviewLeaseService,
        ) { items ->
            val wasBurnAfterRead = isBurnAfterReadEnabled
            if (handlePickedMedia(items, sendAsFile = true, burnAfterRead = wasBurnAfterRead) && wasBurnAfterRead) {
                isBurnAfterReadEnabled = false
            }
        }
        val cameraPhotoPicker = mediaPickerProvider.registerOperationCameraPhotoPicker { result ->
            consumeMediaPickResult(result, MediaPickSource.CAMERA_PHOTO) { pickedItems ->
                val wasBurnAfterRead = isBurnAfterReadEnabled
                if (handlePickedMedia(pickedItems, burnAfterRead = wasBurnAfterRead) && wasBurnAfterRead) {
                    isBurnAfterReadEnabled = false
                }
            }
        }
        val cameraVideoPicker = mediaPickerProvider.registerCameraVideoPicker { uri ->
            val wasBurnAfterRead = isBurnAfterReadEnabled
            if (handlePickedMedia(uri, MimeTypes.Mp4, burnAfterRead = wasBurnAfterRead) && wasBurnAfterRead) {
                isBurnAfterReadEnabled = false
            }
        }
        val isFullScreen = rememberSaveable {
            mutableStateOf(false)
        }
        var showAttachmentSourcePicker: Boolean by remember { mutableStateOf(false) }
        var recentImages by remember { mutableStateOf<kotlinx.collections.immutable.ImmutableList<RecentImage>>(persistentListOf()) }
        var recentImagesHaveMore by remember { mutableStateOf(false) }
        var recentImagesLoading by remember { mutableStateOf(false) }
        var recentImagesSnapshotTime by remember { mutableStateOf(0L) }
        var recentImageQuery by remember { mutableStateOf<Job?>(null) }
        val recentImageGeneration = remember { AtomicLong(0) }
        val recentImageSelectionInProgress = remember { AtomicBoolean(false) }
        DisposableEffect(Unit) {
            onDispose {
                recentImageGeneration.incrementAndGet()
                recentImageQuery?.cancel()
            }
        }
        var showRelayDialog: Boolean by remember { mutableStateOf(false) }
        var containsVolatileWithdrawnText by remember { mutableStateOf(false) }

        val sendTypingNotifications by remember {
            sessionPreferencesStore.isSendTypingNotificationsEnabled()
        }.collectAsState(initial = true)

        LaunchedEffect(cameraPermissionState.permissionGranted) {
            if (cameraPermissionState.permissionGranted) {
                when (pendingEvent) {
                    is MessageComposerEvent.PickAttachmentSource.PhotoFromCamera -> runCatchingExceptions {
                        cameraPhotoPicker.launch(beginMediaPick(MediaPickSource.CAMERA_PHOTO))
                    }.onFailure(::reportPickerLaunchFailure)
                    is MessageComposerEvent.PickAttachmentSource.VideoFromCamera -> cameraVideoPicker.launch()
                    else -> Unit
                }
                pendingEvent = null
            }
        }

        val suggestions = remember { mutableStateListOf<ResolvedSuggestion>() }
        ResolveSuggestionsEffect(suggestions)

        DisposableEffect(Unit) {
            // Declare that the user is not typing anymore when the composer is disposed
            onDispose {
                sessionCoroutineScope.launch {
                    if (sendTypingNotifications) {
                        room.typingNotice(false)
                    }
                }
            }
        }

        val textEditorState by rememberUpdatedState(
            if (showTextFormatting) {
                TextEditorState.Rich(richTextEditorState, roomInfo.isEncrypted == true)
            } else {
                TextEditorState.Markdown(markdownTextEditorState, roomInfo.isEncrypted == true)
            }
        )

        val slashCommandAction = remember { mutableStateOf<AsyncAction<Unit>>(AsyncAction.Uninitialized) }
        val messageSendInProgress = remember { AtomicBoolean(false) }
        var isFlashReadPreparing by remember { mutableStateOf(false) }
        val captionEditGeneration = remember { AtomicLong(0L) }
        val captionDraftMutationMutex = remember { Mutex() }
        val restoredTextVersion = remember { AtomicReference<String?>(null) }
        val currentTextSendRevision = remember { MutableStateFlow<Long?>(null) }
        val restoredSourceDraft = remember { AtomicReference<ComposerDraft?>(null) }
        val restoredEditorDraft = remember { AtomicReference<ComposerDraft?>(null) }
        val composerUserRevision = remember { AtomicLong(0L) }
        val restoredUserRevision = remember { AtomicLong(0L) }
        val observedEditorDraft = remember { AtomicReference<ComposerDraft?>(null) }
        val lastPersistedEditorRevision = remember { AtomicLong(0L) }
        var draftRestored by remember { mutableStateOf(false) }
        val lastPersistedDraft = remember { AtomicReference<ComposerDraft?>(null) }

        LaunchedEffect(Unit) {
            val restorationRevision = composerUserRevision.get()
            val initialEditorDraft = createDraftFromState(markdownTextEditorState, richTextEditorState)
            val draft = draftService.loadDraft(
                roomId = room.roomId,
                // TODO support threads in composer
                threadRoot = null,
                isVolatile = false
            )
            Timber.d("FC002 draft editor restore room=%s loaded=%s revision=%s", room.roomId, draft != null, restorationRevision)
            val canRestore = composerUserRevision.get() == restorationRevision &&
                createDraftFromState(markdownTextEditorState, richTextEditorState) == initialEditorDraft
            var editorDraft = createDraftFromState(markdownTextEditorState, richTextEditorState)
            if (draft != null && canRestore) {
                // setHtml emits a view action; it does not await the rendered editor state.
                // Seed programmatic typing notices, then observe that action before establishing
                // the restored editor's ownership. Real intervening edits keep their revision.
                observedEditorDraft.set(draft)
                applyDraft(draft, markdownTextEditorState, richTextEditorState)
                editorDraft = if (draft.plainText.isNotBlank()) {
                    snapshotFlow { createDraftFromState(markdownTextEditorState, richTextEditorState) to composerUserRevision.get() }
                        .first { (rendered, revision) ->
                            revision != restorationRevision ||
                                (rendered?.draftType == draft.draftType && !rendered?.plainText.isNullOrBlank())
                        }.first
                } else createDraftFromState(markdownTextEditorState, richTextEditorState)
            }
            restoredTextVersion.set(draftService.textDraftVersion(room.roomId))
            restoredUserRevision.set(restorationRevision)
            restoredSourceDraft.set(draft)
            restoredEditorDraft.set(editorDraft.takeIf { canRestore })
            observedEditorDraft.set(editorDraft)
            lastPersistedEditorRevision.set(restorationRevision)
            lastPersistedDraft.set(if (canRestore) editorDraft else draft)
            draftRestored = true
            Timber.d("FC002 draft editor ready room=%s applied=%s version=%s revision=%s",
                room.roomId, draft != null && canRestore, restoredTextVersion.get(), composerUserRevision.get())
        }

        LaunchedEffect(Unit) {
            // Wait once without keying this effect by the mutable restoration state: the
            // initial effect can otherwise start after restoration and then be cancelled
            // by its own false -> true recomposition while consuming the original receipt.
            snapshotFlow { draftRestored }.first { it }
            combine(appOperationalStateCoordinator.observeLifecycleOperations(FullAppLifecycleId.LC_13), currentTextSendRevision) { _, revision -> revision }
                .collect { currentSendRevision ->
                // A current send also retains an encrypted draft until its original Matrix receipt.
                // Recheck when the handoff becomes ready even if the operation map changed first.
                if (restoredTextVersion.get() == null && currentSendRevision == null) return@collect
                val revision = composerUserRevision.get()
                // An unsaved later edit, including identical retyped text, owns the editor.
                if (currentSendRevision != null && currentSendRevision != revision) return@collect
                if (restoredTextVersion.get() != null && restoredUserRevision.get() != revision) return@collect
                val consumed = try { draftService.reconcileSentTextDraft(room.roomId) { composerUserRevision.get() == revision } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@collect }
                consumed ?: return@collect
                if (currentSendRevision != null) {
                    if (currentTextSendRevision.value == revision) currentTextSendRevision.value = null
                    // The current send already cleared its editor at handoff. A later edit stays visible.
                    return@collect
                }
                if (restoredTextVersion.get() == consumed.version && restoredUserRevision.get() == revision &&
                    composerUserRevision.get() == revision && restoredSourceDraft.get() == consumed.draft &&
                    createDraftFromState(markdownTextEditorState, richTextEditorState) == restoredEditorDraft.get()) {
                    resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                    lastPersistedDraft.set(null)
                    restoredTextVersion.set(null)
                }
            }
        }

        val pendingReplyEventId = (messageComposerContext.composerMode as? MessageComposerMode.Reply)
            ?.replyToDetails?.let { (it as? InReplyToDetails.Loading)?.eventId }
        LaunchedEffect(pendingReplyEventId, timelineController) {
            val eventId = pendingReplyEventId ?: return@LaunchedEffect
            val timeline = timelineController.activeTimelineFlow().value
            var details = timeline.loadReplyDetails(eventId).map(permalinkParser)
            if (details is InReplyToDetails.Loading) {
                // Cold restoration can precede the first authorized timeline projection. Resolve
                // the same target when it arrives, without fetching a pending SDK request again.
                val restoredTimeline = timelineController.activeTimelineFlow().flatMapLatest { current ->
                    current.timelineItems.map { items -> current to items }
                }.first { (_, items) ->
                    items.any { it is MatrixTimelineItem.Event && it.eventId == eventId }
                }.first
                details = restoredTimeline.loadReplyDetails(eventId).map(permalinkParser)
            }
            val currentMode = messageComposerContext.composerMode as? MessageComposerMode.Reply
            if (currentMode?.eventId == eventId && currentMode.replyToDetails is InReplyToDetails.Loading) {
                messageComposerContext.composerMode = currentMode.copy(replyToDetails = details)
            }
        }

        LaunchedEffect(Unit) {
            // Save settled editor changes while the page is active: a process kill has no ON_PAUSE.
            // Wait for restoration before observing the initially empty editor. Recalled text remains volatile.
            snapshotFlow {
                if (!draftRestored) null else {
                    val draft = if (containsVolatileWithdrawnText) null else createDraftFromState(markdownTextEditorState, richTextEditorState)
                    draft to composerUserRevision.get()
                }
            }.filterNotNull().onEach { (draft, _) ->
                if (observedEditorDraft.getAndSet(draft) != draft) composerUserRevision.incrementAndGet()
            }.map { (draft, _) -> draft to composerUserRevision.get() }.debounce(500L).collect { (draft, editorRevision) ->
                val generation = captionEditGeneration.get()
                if (lastPersistedDraft.get() != draft || lastPersistedEditorRevision.get() != editorRevision) {
                    localCoroutineScope.updateDraft(
                        draft,
                        isVolatile = false,
                        captionEditGeneration = captionEditGeneration,
                        expectedCaptionGeneration = generation,
                        captionDraftMutationMutex = captionDraftMutationMutex,
                        isCurrentDraft = {
                            val current = if (containsVolatileWithdrawnText) null else createDraftFromState(markdownTextEditorState, richTextEditorState)
                            current == draft && composerUserRevision.get() == editorRevision
                        },
                        onDraftApplied = { lastPersistedDraft.set(draft); lastPersistedEditorRevision.set(editorRevision) },
                    ).join()
                }
            }
        }

        fun handleEvent(event: MessageComposerEvent) {
            // An unsaved new edit (even the same text) cannot be cleared by an older send receipt.
            val currentEditorDraft = createDraftFromState(markdownTextEditorState, richTextEditorState)
            if (observedEditorDraft.getAndSet(currentEditorDraft) != currentEditorDraft) composerUserRevision.incrementAndGet()
            // Send taps do not edit the draft, including taps rejected by the capture guard.
            if (event is MessageComposerEvent.SetMode || event is MessageComposerEvent.InsertCommonInfo ||
                event is MessageComposerEvent.InsertSuggestion || event is MessageComposerEvent.ToggleTextFormatting ||
                event is MessageComposerEvent.RestoreWithdrawnText || event == MessageComposerEvent.ClearWithdrawnText ||
                event == MessageComposerEvent.CloseSpecialMode || event == MessageComposerEvent.PasteFromInAppClipboard) {
                composerUserRevision.incrementAndGet()
            }
            // An intervening user action invalidates unfinished discovery; typing notices do not.
            if (event != MessageComposerEvent.AddAttachment && event !is MessageComposerEvent.SelectRecentImage &&
                event !is MessageComposerEvent.LoadMoreRecentImages && event !is MessageComposerEvent.RecentImageUnavailable && event != MessageComposerEvent.DismissRecentImages &&
                event !is MessageComposerEvent.TypingNotice && event !is MessageComposerEvent.SuggestionReceived) {
                recentImageGeneration.incrementAndGet()
                recentImageQuery?.cancel()
            }
            when (event) {
                is MessageComposerEvent.InsertCommonInfo -> {
                    val repository = commonInfoRepository
                    if (repository == null || !messageSendInProgress.compareAndSet(false, true)) {
                        event.onComplete(Result.failure(IllegalStateException("PRIVATE_RESOURCE_BUSY")))
                    } else localCoroutineScope.launch {
                        try {
                            val result = runCatchingExceptions {
                                val generation = repository.authorizationRevision.value
                                val literal = repository.sendText(event.item)
                                check(repository.authorizationRevision.value == generation) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                                // Use the same append path as an explicit paste. Preserve the current draft and reply/edit mode.
                                textEditorState.appendText(literal)
                                // Usage persistence is local and encrypted. Its cloud work does not block the composer.
                                runCatchingExceptions { repository.enqueue("use", event.item) }.onFailure {
                                    io.element.android.security.dataprotection.api.LifecycleStageTiming.point("common_fill_usage_failed")
                                }
                                Unit
                            }
                            event.onComplete(result)
                        } finally {
                            messageSendInProgress.set(false)
                        }
                    }
                }
                is MessageComposerEvent.SendEmotion -> {
                    val emotion = io.element.android.features.messages.impl.emotions.ChatEmotions.byId(event.id)
                    // Explicit direct send preserves the draft; never bypass reply/edit/flash-read semantics.
                    if (emotion != null && messageComposerContext.composerMode is MessageComposerMode.Normal &&
                        !isBurnAfterReadEnabled) {
                        localCoroutineScope.launch {
                            var echo: io.element.android.libraries.matrix.api.core.UniqueId? = null
                            try {
                                val result = runCatchingExceptions {
                                    val localEcho = timelineController.addOptimisticLocalEcho(emotion.token, null)
                                    echo = localEcho
                                    timelineController.stageOptimisticLocalEcho(localEcho).getOrThrow()
                                    appOperationalStateCoordinator.performMessageWithDeferredReceipts(
                                        ordinaryMessageLifecycleRequest(room, matrixClient, "send", localEcho.value),
                                    ) {
                                        val sent = timelineController.sendOptimisticLocalEcho(localEcho)
                                        timelineController.resolveOptimisticLocalEcho(localEcho, sent.exceptionOrNull())
                                        sent.getOrThrow()
                                    }
                                }
                                result.onFailure { error ->
                                    echo?.let { timelineController.resolveOptimisticLocalEcho(it, error) }
                                    slashCommandAction.value = AsyncAction.Failure(error)
                                }
                            } catch (cancelled: CancellationException) {
                                echo?.let { timelineController.resolveOptimisticLocalEcho(it, cancelled) }
                                throw cancelled
                            }
                        }
                    }
                }
                is MessageComposerEvent.SendCommonInfo -> {
                    val repository = commonInfoRepository
                    if (repository == null || !messageSendInProgress.compareAndSet(false, true)) {
                        event.onComplete(Result.failure(IllegalStateException("PRIVATE_RESOURCE_BUSY")))
                    } else localCoroutineScope.launch {
                        var commonEcho: io.element.android.libraries.matrix.api.core.UniqueId? = null
                        try {
                            val result = runCatchingExceptions {
                                // This presenter/controller owns the captured room. Never rewrite or consume the composer draft/mode.
                                val literal = repository.sendText(event.item)
                                val localEcho = timelineController.addOptimisticLocalEcho(literal, null)
                                commonEcho = localEcho
                                timelineController.stageOptimisticLocalEcho(localEcho).getOrThrow()
                                repository.stageSend(event.item.id, room.roomId.value, localEcho.value)
                                appOperationalStateCoordinator.performMessageWithDeferredReceipts(
                                    ordinaryMessageLifecycleRequest(room, matrixClient, "send", localEcho.value),
                                ) {
                                    val sent = timelineController.sendOptimisticLocalEcho(localEcho)
                                    timelineController.resolveOptimisticLocalEcho(localEcho, sent.exceptionOrNull())
                                    sent.getOrThrow()
                                }
                            }
                            if (result.isFailure) commonEcho?.let { timelineController.resolveOptimisticLocalEcho(it, result.exceptionOrNull()) }
                            event.onComplete(result)
                        } catch (cancelled: CancellationException) {
                            commonEcho?.let { timelineController.resolveOptimisticLocalEcho(it, cancelled) }
                            throw cancelled
                        } finally {
                            messageSendInProgress.set(false)
                        }
                    }
                }
                MessageComposerEvent.ToggleFullScreenState -> isFullScreen.value = !isFullScreen.value
                MessageComposerEvent.CloseSpecialMode -> {
                    if (messageComposerContext.composerMode.isEditing) {
                        localCoroutineScope.launch {
                            resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = true)
                        }
                    } else {
                        messageComposerContext.composerMode = MessageComposerMode.Normal
                    }
                }
                is MessageComposerEvent.SendMessage -> {
                    if (messageSendInProgress.compareAndSet(false, true)) {
                        sessionCoroutineScope.sendMessage(
                            markdownTextEditorState = markdownTextEditorState,
                            richTextEditorState = richTextEditorState,
                            slashCommandAction = slashCommandAction,
                            clickStartedAtNanos = System.nanoTime(),
                            burnAfterRead = isBurnAfterReadEnabled,
                            onBurnAfterReadSent = { isBurnAfterReadEnabled = false },
                            onFlashReadPreparing = { isFlashReadPreparing = it },
                            onVolatileTextConsumed = { containsVolatileWithdrawnText = false },
                             onTextDraftHandedOff = {
                                 lastPersistedDraft.set(null)
                                 restoredTextVersion.set(null)
                                 // Rich-editor reset is asynchronous. Establish the consumed editor
                                 // before releasing capture so later taps cannot recapture its old text.
                                 snapshotFlow { createDraftFromState(markdownTextEditorState, richTextEditorState) }.first { it == null }
                                 observedEditorDraft.set(null)
                                 currentTextSendRevision.value = composerUserRevision.get()
                             },
                            composerUserRevision = composerUserRevision,
                            expectedComposerUserRevision = composerUserRevision.get(),
                            messageSendInProgress = messageSendInProgress,
                            captionEditGeneration = captionEditGeneration,
                            captionDraftMutationMutex = captionDraftMutationMutex,
                        )
                    }
                }
                MessageComposerEvent.SendFlashReadMessage -> {
                    if (messageSendInProgress.compareAndSet(false, true)) {
                        sessionCoroutineScope.sendMessage(
                            markdownTextEditorState = markdownTextEditorState,
                            richTextEditorState = richTextEditorState,
                            slashCommandAction = slashCommandAction,
                            clickStartedAtNanos = System.nanoTime(),
                            burnAfterRead = true,
                            onBurnAfterReadSent = { isBurnAfterReadEnabled = false },
                            onFlashReadPreparing = { isFlashReadPreparing = it },
                            onVolatileTextConsumed = { containsVolatileWithdrawnText = false },
                            onTextDraftHandedOff = { lastPersistedDraft.set(null); restoredTextVersion.set(null) },
                            composerUserRevision = composerUserRevision,
                            expectedComposerUserRevision = composerUserRevision.get(),
                            messageSendInProgress = messageSendInProgress,
                            captionEditGeneration = captionEditGeneration,
                            captionDraftMutationMutex = captionDraftMutationMutex,
                        )
                    }
                }
                MessageComposerEvent.ToggleBurnAfterRead -> {
                    if (messageComposerContext.composerMode is MessageComposerMode.Normal) {
                        isBurnAfterReadEnabled = !isBurnAfterReadEnabled
                    }
                }
                is MessageComposerEvent.SendUri -> {
                    val inReplyToEventId = (messageComposerContext.composerMode as? MessageComposerMode.Reply)?.eventId
                    sessionCoroutineScope.sendAttachment(
                        attachment = Media(
                            localMedia = localMediaFactory.createFromUri(
                                uri = event.uri,
                                mimeType = null,
                                name = null,
                                formattedFileSize = null
                            ),
                        ),
                        inReplyToEventId = inReplyToEventId,
                    )

                    // Reset composer since the attachment has been sent
                    messageComposerContext.composerMode = MessageComposerMode.Normal
                }
                is MessageComposerEvent.SetMode -> {
                    if (event.composerMode is MessageComposerMode.EditCaption) {
                        captionEditGeneration.incrementAndGet()
                    }
                    if (event.composerMode !is MessageComposerMode.Normal) {
                        isBurnAfterReadEnabled = false
                    }
                    localCoroutineScope.setMode(event.composerMode, markdownTextEditorState, richTextEditorState)
                }
                is MessageComposerEvent.InvalidateReplyProjection -> {
                    messageComposerContext.composerMode = invalidateReplyProjection(messageComposerContext.composerMode, event)
                }
                is MessageComposerEvent.RestoreReplyProjection -> {
                    messageComposerContext.composerMode = restoreReplyProjection(messageComposerContext.composerMode, event)
                }
                is MessageComposerEvent.RestoreWithdrawnText -> {
                    containsVolatileWithdrawnText = true
                    messageComposerContext.composerMode = MessageComposerMode.Normal
                    localCoroutineScope.launch {
                        // A recalled message must never inherit or create a persistent draft preview.
                        // Clear both the Matrix draft and its room-list preview before exposing the
                        // plaintext to the in-memory editor.
                        updateDraft(null, isVolatile = false).join()
                        setText(event.text, markdownTextEditorState, richTextEditorState, requestFocus = true)
                    }
                }
                MessageComposerEvent.ClearWithdrawnText -> {
                    if (containsVolatileWithdrawnText) {
                        localCoroutineScope.launch {
                            // Keep the volatile guard set until the editor has actually been cleared.
                            // Otherwise ON_PAUSE can race with SaveDraft and persist the recalled text
                            // between this event and the asynchronous setText call.
                            updateDraft(null, isVolatile = false).join()
                            setText("", markdownTextEditorState, richTextEditorState)
                            messageComposerContext.composerMode = MessageComposerMode.Normal
                            containsVolatileWithdrawnText = false
                        }
                    }
                }
                MessageComposerEvent.AddAttachment -> {
                    recentImageQuery?.cancel()
                    val generation = recentImageGeneration.incrementAndGet()
                    recentImages = persistentListOf()
                    recentImagesHaveMore = false
                    recentImagesLoading = false
                    recentImageQuery = localCoroutineScope.launch {
                        // Decide the row before opening the panel; never insert a late row under a moving finger.
                        val now = System.currentTimeMillis()
                        val batch = queryRecentImageSuggestions(
                            scope = localCoroutineScope,
                            readCandidates = {
                                val fresh = mediaPickerProvider.recentImages(now)
                                if (fresh.isEmpty()) emptyList() else mediaPickerProvider.galleryImages(now).ifEmpty { fresh }
                            },
                            claimCandidates = { candidates ->
                                val fresh = candidates.filter { now - it.addedAtEpochMillis in 0..120_000L }
                                val claimed = sessionPreferencesStore.claimRecentImageSuggestions(fresh.map { it.uri.toString() }, now)
                                if (claimed.isEmpty()) emptyList() else candidates
                            },
                        )
                        if (recentImageGeneration.get() == generation) {
                            recentImages = batch.toImmutableList()
                            recentImagesHaveMore = batch.size == 40
                            recentImagesSnapshotTime = now
                            showAttachmentSourcePicker = true
                        }
                    }
                }
                is MessageComposerEvent.LoadMoreRecentImages -> {
                    val cursor = recentImages.lastOrNull()
                    if (showAttachmentSourcePicker && recentImagesHaveMore && !recentImagesLoading && cursor?.uri == event.after) {
                        val generation = recentImageGeneration.get()
                        recentImagesLoading = true
                        recentImageQuery = localCoroutineScope.launch {
                            val page = queryRecentImageSuggestions(localCoroutineScope,
                                readCandidates = { mediaPickerProvider.galleryImages(recentImagesSnapshotTime, cursor) },
                                claimCandidates = { it })
                            if (recentImageGeneration.get() == generation) {
                                recentImagesLoading = false
                                if (showAttachmentSourcePicker && recentImages.lastOrNull() == cursor) {
                                    recentImages = (recentImages + page).distinctBy { it.uri }.toImmutableList()
                                    recentImagesHaveMore = page.size == 40 && recentImages.lastOrNull() != cursor
                                }
                            }
                        }
                    }
                }
                MessageComposerEvent.DismissRecentImages -> {
                    recentImageGeneration.incrementAndGet()
                    recentImageQuery?.cancel()
                    recentImages = persistentListOf()
                }
                is MessageComposerEvent.RecentImageUnavailable -> {
                    // Withdraw the row instead of shifting other pictures under a finger.
                    if (recentImages.any { it.uri == event.uri }) recentImages = persistentListOf()
                }
                is MessageComposerEvent.SelectRecentImage -> {
                    val batch = recentImages
                    if (showAttachmentSourcePicker && batch.any { it.uri == event.uri } && recentImageSelectionInProgress.compareAndSet(false, true)) {
                        recentImageGeneration.incrementAndGet()
                        recentImageQuery?.cancel()
                        showAttachmentSourcePicker = false
                        recentImages = persistentListOf()
                        localCoroutineScope.launch {
                            var operation: MediaPickOperation? = null
                            try {
                                operation = beginMediaPick(MediaPickSource.GALLERY)
                                val result = mediaPickerProvider.selectRecentImages(operation, listOf(event.uri))
                                if (!isAuthorizedMediaPick(result.operation, MediaPickSource.GALLERY)) {
                                    reportPickerAuthorityFailure()
                                    return@launch
                                }
                                reportPickerFailures(result)
                                val ready = result.readyItems()
                                if (ready.none { it.uri == event.uri }) return@launch
                                val wasBurnAfterRead = isBurnAfterReadEnabled
                                val selected = ready.map { it.copy(initiallySelected = it.uri == event.uri, focused = it.uri == event.uri) }
                                if (handlePickedMedia(selected, burnAfterRead = wasBurnAfterRead, fromGallery = true, fromRecentImages = true) && wasBurnAfterRead) {
                                    isBurnAfterReadEnabled = false
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                reportPickerLaunchFailure(failure)
                            } finally {
                                operation?.operationId?.let(mediaPickerProvider::completeOperation)
                                recentImageSelectionInProgress.set(false)
                            }
                        }
                    }
                }
                MessageComposerEvent.PasteFromInAppClipboard -> {
                    showAttachmentSourcePicker = false
                    InAppClipboard.getPlainText()?.let { text ->
                        localCoroutineScope.launch {
                            textEditorState.appendText(text)
                            textEditorState.requestFocus()
                        }
                    }
                }
                MessageComposerEvent.StartRelay -> {
                    showAttachmentSourcePicker = false
                    showRelayDialog = true
                }
                MessageComposerEvent.DismissRelay -> showRelayDialog = false
                is MessageComposerEvent.CreateRelay -> {
                    val title = event.title.trim()
                    val initialEntry = event.initialEntry.trim()
                    if (title.isNotEmpty() && initialEntry.isNotEmpty()) {
                        localCoroutineScope.launch {
                            var accepted = false
                            runCatchingExceptions {
                                appOperationalStateCoordinator.perform(relayOperationRequest()) {
                                    timelineController.activeTimelineFlow().value.createRelay(title, initialEntry).getOrThrow()
                                    accepted = true
                                    // Matrix has accepted the protected relay at this point. LC-15
                                    // receipt transport must not hold the editor open.
                                    showRelayDialog = false
                                }
                            }.also { event.onComplete(if (accepted) Result.success(Unit) else it) }.fold(
                                onSuccess = {},
                                onFailure = { slashCommandAction.value = AsyncAction.Failure(it) },
                            )
                        }
                    }
                }
                is MessageComposerEvent.AppendRelay -> {
                    val entry = event.entry.trim()
                    if (entry.isNotEmpty()) {
                        localCoroutineScope.launch {
                            var accepted = false
                            runCatchingExceptions {
                                appOperationalStateCoordinator.perform(relayOperationRequest(event.relayEventId)) {
                                    timelineController.activeTimelineFlow().value.appendRelay(event.relayEventId, entry).getOrThrow()
                                    accepted = true
                                }
                            }.also { event.onComplete(if (accepted) Result.success(Unit) else it) }.onFailure {
                                slashCommandAction.value = AsyncAction.Failure(it)
                            }
                        }
                    }
                }
                is MessageComposerEvent.CloseRelay -> {
                    localCoroutineScope.launch {
                        var accepted = false
                        runCatchingExceptions {
                            appOperationalStateCoordinator.perform(relayOperationRequest(event.relayEventId)) {
                                timelineController.activeTimelineFlow().value.closeRelay(event.relayEventId).getOrThrow()
                                accepted = true
                            }
                        }.also { event.onComplete(if (accepted) Result.success(Unit) else it) }.onFailure {
                            slashCommandAction.value = AsyncAction.Failure(it)
                        }
                    }
                }
                is MessageComposerEvent.LoadRelayEditRecovery -> {
                    localCoroutineScope.launch {
                        event.onComplete(runCatchingExceptions {
                            val timeline = timelineController.activeTimelineFlow().value
                            val pending = timeline.pendingRelayEditRecovery(event.relayEventId).getOrThrow()
                            if (pending == null) null else {
                                val resolved = appOperationalStateCoordinator.reconcileOriginalRelayEdit(
                                    relayOperationRequest(event.relayEventId, pending.operationId),
                                    verifyAcceptedIntent = {
                                        timeline.verifyAcceptedRelayEditIntent(event.relayEventId, pending.itemId,
                                            pending.expectedRevision, pending.entry).getOrThrow()
                                    },
                                    resolveOriginal = { timeline.resolveRelayEditRecovery(event.relayEventId, pending.operationId).getOrThrow() },
                                )
                                pending.takeUnless { resolved }
                            }
                        }.fold({ Result.success(it) }, {
                            Result.failure(it as? RelayEditException ?: RelayEditException(RelayEditException.Reason.AUTHORITY_UNAVAILABLE, it))
                        }))
                    }
                }
                is MessageComposerEvent.EditRelayEntry -> {
                    val entry = event.entry.trim()
                    if (entry.isNotEmpty()) {
                        localCoroutineScope.launch {
                            var accepted = false
                            runCatchingExceptions {
                                val operationId = "relay-edit-" + java.util.UUID.fromString(event.operationId).toString()
                                val timeline = timelineController.activeTimelineFlow().value
                                val request = relayOperationRequest(event.relayEventId, operationId)
                                // Retain the exact protected intent before admission or any external effect.
                                // Reopening the relay discovers this same operation after process death.
                                withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
                                    timeline.retainRelayEditRecovery(io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(
                                        operationId, event.relayEventId.value, event.itemId, event.expectedRevision, entry,
                                    )).getOrThrow()
                                }
                                appOperationalStateCoordinator.performIdempotentRelayEdit(
                                    request,
                                    verifyAcceptedIntent = {
                                        timeline.verifyAcceptedRelayEditIntent(
                                            event.relayEventId, event.itemId, event.expectedRevision, entry,
                                        ).getOrThrow()
                                        accepted = true
                                    },
                                    reconcileExpired = {
                                        appOperationalStateCoordinator.reconcileRelayPreparation(
                                            io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery(
                                                checkNotNull(request.accountIdHash), checkNotNull(request.matrixDeviceIdHash),
                                                checkNotNull(request.roomIdHash), checkNotNull(request.eventIdHash), operationId,
                                                room.roomId.value, java.util.UUID.nameUUIDFromBytes(
                                                    "relay-edit-v1\u0000${room.roomId.value}\u0000$operationId".encodeToByteArray(),
                                                ).toString(),
                                            ),
                                        )
                                    },
                                ) {
                                    timeline.editRelayEntry(
                                        event.relayEventId, event.itemId, event.expectedRevision, entry,
                                    ).getOrThrow()
                                    accepted = true
                                }
                                appOperationalStateCoordinator.reconcileOriginalRelayEdit(request,
                                    verifyAcceptedIntent = {
                                        timeline.verifyAcceptedRelayEditIntent(event.relayEventId, event.itemId, event.expectedRevision, entry).getOrThrow()
                                    },
                                    resolveOriginal = { timeline.resolveRelayEditRecovery(event.relayEventId, operationId).getOrThrow() },
                                )
                                Unit
                            }.fold({ Result.success(it) }, {
                                Result.failure(it as? RelayEditException ?: RelayEditException(RelayEditException.Reason.AUTHORITY_UNAVAILABLE, it))
                            }).also { event.onComplete(if (accepted) Result.success(Unit) else it) }.onFailure {
                                slashCommandAction.value = AsyncAction.Failure(it)
                            }
                        }
                    }
                }
                MessageComposerEvent.DismissAttachmentMenu -> {
                    recentImageGeneration.incrementAndGet()
                    recentImageQuery?.cancel()
                    recentImages = persistentListOf()
                    showAttachmentSourcePicker = false
                }
                MessageComposerEvent.DismissCameraPermissionRationale -> {
                    pendingEvent = null
                    cameraPermissionState.eventSink(PermissionsEvent.CloseDialog)
                }
                MessageComposerEvent.OpenCameraPermissionSettings -> {
                    cameraPermissionState.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
                }
                MessageComposerEvent.PickAttachmentSource.FromGallery -> {
                    showAttachmentSourcePicker = false
                    pickGalleryMediaAsSticker = false
                    localCoroutineScope.launch {
                        runCatchingExceptions {
                            // A new selection must never be replaced by a retained editor draft.
                            galleryMediaPicker.launch(beginMediaPick(MediaPickSource.GALLERY))
                        }.onFailure(::reportPickerLaunchFailure)
                    }
                }
                MessageComposerEvent.PickAttachmentSource.FromGalleryAsSticker -> {
                    showAttachmentSourcePicker = false
                    pickGalleryMediaAsSticker = true
                    localCoroutineScope.launch {
                        runCatchingExceptions {
                            galleryMediaPicker.launch(beginMediaPick(MediaPickSource.GALLERY))
                        }.onFailure(::reportPickerLaunchFailure)
                    }
                }
                MessageComposerEvent.PickAttachmentSource.FromFiles -> {
                    showAttachmentSourcePicker = false
                    localCoroutineScope.launch { filesPicker.launch() }
                }
                MessageComposerEvent.PickAttachmentSource.PhotoFromCamera -> {
                    showAttachmentSourcePicker = false
                    if (cameraPermissionState.permissionGranted) {
                        localCoroutineScope.launch {
                            runCatchingExceptions {
                                cameraPhotoPicker.launch(beginMediaPick(MediaPickSource.CAMERA_PHOTO))
                            }.onFailure(::reportPickerLaunchFailure)
                        }
                    } else {
                        pendingEvent = event
                        cameraPermissionState.eventSink(PermissionsEvent.RequestPermissions)
                    }
                }
                MessageComposerEvent.PickAttachmentSource.VideoFromCamera -> {
                    showAttachmentSourcePicker = false
                    if (cameraPermissionState.permissionGranted) {
                        localCoroutineScope.launch { cameraVideoPicker.launch() }
                    } else {
                        pendingEvent = event
                        cameraPermissionState.eventSink(PermissionsEvent.RequestPermissions)
                    }
                }
                MessageComposerEvent.PickAttachmentSource.Location -> {
                    showAttachmentSourcePicker = false
                    // Navigation to the location picker screen is done at the view layer
                }
                MessageComposerEvent.PickAttachmentSource.Poll -> {
                    showAttachmentSourcePicker = false
                    // Navigation to the create poll screen is done at the view layer
                }
                is MessageComposerEvent.ToggleTextFormatting -> {
                    showAttachmentSourcePicker = false
                    localCoroutineScope.toggleTextFormatting(event.enabled, markdownTextEditorState, richTextEditorState)
                }
                is MessageComposerEvent.Error -> {
                    analyticsService.trackError(event.error)
                }
                is MessageComposerEvent.TypingNotice -> {
                    if (sendTypingNotifications) {
                        localCoroutineScope.launch {
                            room.typingNotice(event.isTyping)
                        }
                    }
                }
                is MessageComposerEvent.SuggestionReceived -> {
                    suggestionSearchTrigger.value = event.suggestion
                }
                is MessageComposerEvent.InsertSuggestion -> {
                    localCoroutineScope.launch {
                        if (showTextFormatting) {
                            when (val suggestion = event.resolvedSuggestion) {
                                is ResolvedSuggestion.AtRoom -> {
                                    richTextEditorState.insertAtRoomMentionAtSuggestion()
                                }
                                is ResolvedSuggestion.Member -> {
                                    val text = suggestion.roomMember.userId.value
                                    val link = permalinkBuilder.permalinkForUser(suggestion.roomMember.userId).getOrNull() ?: return@launch
                                    richTextEditorState.insertMentionAtSuggestion(text = text, link = link)
                                }
                                is ResolvedSuggestion.Alias -> {
                                    val text = suggestion.roomAlias.value
                                    val link = permalinkBuilder.permalinkForRoomAlias(suggestion.roomAlias).getOrNull() ?: return@launch
                                    richTextEditorState.insertMentionAtSuggestion(text = text, link = link)
                                }
                                is ResolvedSuggestion.Command -> {
                                    richTextEditorState.replaceSuggestion(suggestion.command.command)
                                }
                            }
                        } else if (markdownTextEditorState.currentSuggestion != null) {
                            markdownTextEditorState.insertSuggestion(
                                resolvedSuggestion = event.resolvedSuggestion,
                                mentionSpanProvider = mentionSpanProvider,
                            )
                            suggestionSearchTrigger.value = null
                        }
                    }
                }
                MessageComposerEvent.SaveDraft -> {
                    val draft = if (containsVolatileWithdrawnText) null else createDraftFromState(markdownTextEditorState, richTextEditorState)
                    val expectedCaptionGeneration = captionEditGeneration.get()
                    val editorRevision = composerUserRevision.get()
                    Timber.d("FC002 draft checkpoint room=%s restored=%s present=%s source_present=%s version=%s revision=%s",
                        room.roomId, draftRestored, draft != null, restoredSourceDraft.get() != null, restoredTextVersion.get(), editorRevision)
                    // A lifecycle checkpoint is not a new edit. Keep a restored send's exact
                    // storage version so its original authority confirmation can still consume it.
                    if (restoredTextVersion.get() != null && lastPersistedDraft.get() == draft &&
                        lastPersistedEditorRevision.get() == editorRevision) return
                    sessionCoroutineScope.updateDraft(
                        draft,
                        isVolatile = false,
                        captionEditGeneration = captionEditGeneration,
                        expectedCaptionGeneration = expectedCaptionGeneration,
                        captionDraftMutationMutex = captionDraftMutationMutex,
                        isCurrentDraft = {
                            val current = if (containsVolatileWithdrawnText) null else createDraftFromState(markdownTextEditorState, richTextEditorState)
                            current == draft && composerUserRevision.get() == editorRevision
                        },
                        onDraftApplied = { lastPersistedDraft.set(draft); lastPersistedEditorRevision.set(editorRevision) },
                    )
                }
                MessageComposerEvent.ClearSlashError -> {
                    slashCommandAction.value = AsyncAction.Uninitialized
                }
            }
        }

        val resolveMentionDisplay = remember {
            { text: String, url: String ->
                val mentionSpan = mentionSpanProvider.getMentionSpanFor(text, url)
                if (mentionSpan != null) {
                    TextDisplay.Custom(mentionSpan)
                } else {
                    TextDisplay.Plain
                }
            }
        }

        val resolveAtRoomMentionDisplay = remember {
            {
                val mentionSpan = mentionSpanProvider.createEveryoneMentionSpan()
                TextDisplay.Custom(mentionSpan)
            }
        }

        return MessageComposerState(
            textEditorState = textEditorState,
            isFullScreen = isFullScreen.value,
            mode = messageComposerContext.composerMode,
            showAttachmentSourcePicker = showAttachmentSourcePicker,
            showRelayDialog = showRelayDialog,
            showTextFormatting = showTextFormatting,
            canShareLocation = canShareLocation.value,
            canPasteFromInAppClipboard = InAppClipboard.hasPlainText(),
            isBurnAfterReadEnabled = isBurnAfterReadEnabled,
            isFlashReadPreparing = isFlashReadPreparing,
            containsVolatileWithdrawnText = containsVolatileWithdrawnText,
            suggestions = suggestions.toImmutableList(),
            resolveMentionDisplay = resolveMentionDisplay,
            resolveAtRoomMentionDisplay = resolveAtRoomMentionDisplay,
            slashCommandAction = slashCommandAction.value,
            eventSink = ::handleEvent,
            commonInfoRepository = commonInfoRepository,
            showCameraPermissionRationaleDialog = cameraPermissionState.showDialog,
            recentImages = if (showAttachmentSourcePicker) recentImages else persistentListOf(),
        )
    }

    @OptIn(FlowPreview::class)
    @Composable
    private fun ResolveSuggestionsEffect(
        suggestions: SnapshotStateList<ResolvedSuggestion>,
    ) {
        LaunchedEffect(Unit) {
            val currentUserId = room.sessionId

            suspend fun canSendRoomMention(): Boolean {
                val userCanSendAtRoom = room.roomPermissions().use(false) { perms ->
                    perms.canOwnUserTriggerRoomNotification()
                }
                return !room.isDm() && userCanSendAtRoom
            }

            // This will trigger a search immediately when `@` is typed
            val mentionStartTrigger = suggestionSearchTrigger.filter { it?.text.isNullOrEmpty() }
            // This will start a search when the user changes the text after the `@` with a debounce to prevent too much wasted work
            val mentionCompletionTrigger = suggestionSearchTrigger.debounce(0.3.seconds).filter { !it?.text.isNullOrEmpty() }

            val mentionTriggerFlow = merge(mentionStartTrigger, mentionCompletionTrigger)

            val roomAliasSuggestionsFlow = roomAliasSuggestionsDataSource
                .getAllRoomAliasSuggestions()
                .stateIn(this, SharingStarted.Lazily, emptyList())

            combine(mentionTriggerFlow, room.membersStateFlow, roomAliasSuggestionsFlow) { suggestion, roomMembersState, roomAliasSuggestions ->
                val result = suggestionsProcessor.process(
                    suggestion = suggestion,
                    roomMembersState = roomMembersState,
                    roomAliasSuggestions = roomAliasSuggestions,
                    currentUserId = currentUserId,
                    canSendRoomMention = ::canSendRoomMention,
                    isInThread = isInThread,
                )
                suggestions.clear()
                suggestions.addAll(result)
            }
                .collect()
        }
    }

    private fun CoroutineScope.sendMessage(
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
        slashCommandAction: MutableState<AsyncAction<Unit>>,
        clickStartedAtNanos: Long,
        burnAfterRead: Boolean,
        onBurnAfterReadSent: () -> Unit,
        onFlashReadPreparing: (Boolean) -> Unit,
        onVolatileTextConsumed: () -> Unit,
        onTextDraftHandedOff: suspend () -> Unit,
        composerUserRevision: AtomicLong,
        expectedComposerUserRevision: Long,
        messageSendInProgress: AtomicBoolean,
        captionEditGeneration: AtomicLong,
        captionDraftMutationMutex: Mutex,
    ) = launch {
        var ownsComposerSendGuard = true
        var immediateEchoId: io.element.android.libraries.matrix.api.core.UniqueId? = null
        try {
        if (burnAfterRead) onFlashReadPreparing(true)
        room.withSendLifetime {
        timelineController.withComposerSendLifetime {
        val initialMode = messageComposerContext.composerMode
        val initialMessage = currentComposerMessage(markdownTextEditorState, richTextEditorState, withMentions = true)
        if ((initialMode is MessageComposerMode.Normal || initialMode is MessageComposerMode.Reply) && initialMessage.markdown.isBlank()) {
            Timber.w("Ignoring an empty message send request")
            return@withComposerSendLifetime
        }
        // Ordinary text has a UI-owned echo before any network admission. This is not a delivery
        // acknowledgement: protected transport still runs inside the lifecycle operation below.
        // Commands, edits, relay replies and burn-after-read retain their dedicated semantics.
        if (initialMode is MessageComposerMode.Normal && !burnAfterRead && !initialMessage.markdown.startsWith("/")) {
            immediateEchoId = timelineController.addOptimisticLocalEcho(
                body = initialMessage.markdown,
                htmlBody = initialMessage.html,
                intentionalMentions = initialMessage.intentionalMentions,
            )
            captionDraftMutationMutex.lock()
            try {
                val capturedDraft = ComposerDraft(initialMessage.markdown, initialMessage.html, ComposerDraftType.NewMessage)
                appOperationalStateCoordinator.performLocalDraft(lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_14, trigger = "draft_change",
                    accountIdHash = accountIdHash(room.sessionId.value), matrixDeviceIdHash = accountIdHash(matrixClient.deviceId.value),
                    roomIdHash = room.roomId.value.sha256Hex(),
                )) {
                    draftService.bindTextSendDraft(room.roomId, capturedDraft,
                        ordinaryMessageLifecycleRequest(room, matrixClient, "send", checkNotNull(immediateEchoId).value).operationId)
                }
                timelineController.stageOptimisticLocalEcho(checkNotNull(immediateEchoId)).getOrThrow()
                // Persistence can suspend while a later edit is made. Only clear the captured editor.
                if (composerUserRevision.get() == expectedComposerUserRevision && messageComposerContext.composerMode == initialMode &&
                    currentComposerMessage(markdownTextEditorState, richTextEditorState, withMentions = true) == initialMessage) {
                    resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                    onVolatileTextConsumed()
                    onTextDraftHandedOff()
                }
            } finally { captionDraftMutationMutex.unlock() }
            // Guard only capture/clear, not the network lifetime. An older send must never clear
            // a later send's guard or re-read the later draft after suspended admission.
            ownsComposerSendGuard = false
            messageSendInProgress.set(false)
            val feedbackAtNanos = System.nanoTime()
            val feedbackMillis = (feedbackAtNanos - clickStartedAtNanos) / NANOS_PER_MILLISECOND
            Timber.tag("YiZheMessageLatency").d(
                "stage=click_captured identity=%s click_epoch_ms=%d feedback_ms=%d",
                immediateEchoId?.value,
                System.currentTimeMillis() - feedbackMillis,
                feedbackMillis,
            )
            Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                "stage=local_echo duration_ms=%d",
                (System.nanoTime() - clickStartedAtNanos) / NANOS_PER_MILLISECOND,
            )
        }
        val lifecycleId = when {
            burnAfterRead -> FullAppLifecycleId.LC_21
            initialMessage.markdown.startsWith("/relay ") || initialMessage.markdown.startsWith("/relay-") ->
                FullAppLifecycleId.LC_15
            else -> FullAppLifecycleId.LC_13
        }
        val trigger = when (lifecycleId) {
            FullAppLifecycleId.LC_21 -> "message_commit"
            FullAppLifecycleId.LC_15 -> "relay_operation"
            else -> when (initialMode) {
                is MessageComposerMode.Edit,
                is MessageComposerMode.EditCaption -> "edit"
                is MessageComposerMode.Reply -> "reply"
                else -> "send"
            }
        }
            appOperationalStateCoordinator.performMessageWithDeferredReceipts(
                if (lifecycleId == FullAppLifecycleId.LC_13) {
                    ordinaryMessageLifecycleRequest(room, matrixClient, trigger, immediateEchoId?.value)
                } else {
                    lifecycleOperationRequest(
                    lifecycleId = lifecycleId,
                    trigger = trigger,
                    roomIdHash = room.roomId.value.sha256Hex(),
                )
                }
            ) operation@{ operationId ->
        LifecycleStageTiming.point("send_operation_bind", immediateEchoId?.value.orEmpty(), operationId = operationId)
        val message = if (immediateEchoId != null) {
            initialMessage
        } else {
            currentComposerMessage(markdownTextEditorState, richTextEditorState, withMentions = true)
        }
        val capturedMode = if (immediateEchoId != null) initialMode else messageComposerContext.composerMode
        val relayTarget = (capturedMode as? MessageComposerMode.Reply)?.let { replyMode ->
            timelineController.activeTimelineFlow().value.timelineItems.first()
                .filterIsInstance<MatrixTimelineItem.Event>()
                .firstOrNull { it.eventId == replyMode.eventId }
                ?.takeIf { item ->
                    val body = (item.event.content as? MessageContent)?.body.orEmpty()
                    body.startsWith("接龙：") && !body.lineSequence().any { it == "（已结束）" }
                }
        }
        val relayReply = message.markdown.trim()

        val relayResult: Result<Unit>? = when {
            capturedMode is MessageComposerMode.Normal && message.markdown.startsWith("/relay ") -> {
                val title = message.markdown.removePrefix("/relay ").trim()
                timelineController.activeTimelineFlow().value.createRelay(title)
            }
            capturedMode is MessageComposerMode.Reply && message.markdown.startsWith("/relay-add ") -> {
                val entry = message.markdown.removePrefix("/relay-add ").trim()
                timelineController.activeTimelineFlow().value.appendRelay(capturedMode.eventId, entry)
            }
            capturedMode is MessageComposerMode.Reply && message.markdown.trim() == "/relay-close" -> {
                timelineController.activeTimelineFlow().value.closeRelay(capturedMode.eventId)
            }
            capturedMode is MessageComposerMode.Reply && relayTarget != null &&
                relayReply == "结束接龙" && relayTarget.event.sender == room.sessionId -> {
                timelineController.activeTimelineFlow().value.closeRelay(capturedMode.eventId)
            }
            capturedMode is MessageComposerMode.Reply && relayTarget != null && relayReply.isNotEmpty() -> {
                timelineController.activeTimelineFlow().value.appendRelay(capturedMode.eventId, relayReply)
            }
            else -> null
        }
        if (relayResult != null) {
            relayResult.fold(
                onSuccess = {
                    resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                    onVolatileTextConsumed()
                },
                onFailure = { slashCommandAction.value = AsyncAction.Failure(it) },
            )
            relayResult.getOrThrow()
            return@operation
        }

        val slashCommand = if (capturedMode is MessageComposerMode.Normal) {
            slashCommandService.parse(
                textMessage = message.markdown,
                formattedMessage = message.html,
                isInThreadTimeline = isInThread,
            )
        } else {
            SlashCommand.NotACommand
        }

        when (slashCommand) {
            is SlashCommand.NotACommand -> Unit
            is SlashCommand.Error -> {
                val failure = IllegalArgumentException(slashCommand.message())
                slashCommandAction.value = AsyncAction.Failure(failure)
                throw failure
            }
            is SlashCommand.SlashCommandNavigation -> {
                when (slashCommand) {
                    is SlashCommand.ShowUser -> {
                        navigator.navigateToMember(slashCommand.userId)
                    }
                    SlashCommand.DevTools -> {
                        navigator.navigateToDeveloperSettings()
                    }
                }
                resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = capturedMode is MessageComposerMode.Edit)
                onVolatileTextConsumed()
                return@operation
            }
            is SlashCommand.SlashCommandSendMessage -> {
                timelineController.invokeOnCurrentTimeline {
                    slashCommandService.proceedSendMessage(slashCommand, this)
                        .onFailure { cause ->
                            Timber.e(cause, "Failed to proceed with admin slash command")
                            slashCommandAction.value = AsyncAction.Failure(cause)
                        }
                        .onSuccess {
                            // Reset composer
                            resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = capturedMode is MessageComposerMode.Edit)
                            onVolatileTextConsumed()
                        }
                }
                return@operation
            }
            is SlashCommand.SlashCommandAdmin -> {
                slashCommandAction.value = AsyncAction.Loading
                slashCommandService.proceedAdmin(slashCommand)
                    .onFailure { cause ->
                        Timber.e(cause, "Failed to proceed with admin slash command")
                        slashCommandAction.value = AsyncAction.Failure(cause)
                    }
                    .onSuccess {
                        // Reset composer
                        resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = capturedMode is MessageComposerMode.Edit)
                        onVolatileTextConsumed()
                        slashCommandAction.value = AsyncAction.Uninitialized
                    }
                return@operation
            }
        }

        when (capturedMode) {
            is MessageComposerMode.Attachment,
            is MessageComposerMode.Normal -> {
                if (burnAfterRead) {
                    var result: Result<Unit>? = null
                    timelineController.invokeOnCurrentTimeline {
                        result = sendBurnAfterReadMessage(
                            body = message.markdown,
                            htmlBody = message.html,
                            intentionalMentions = message.intentionalMentions,
                        )
                    }
                    onFlashReadPreparing(false)
                    result?.fold(
                        onSuccess = {
                            resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                            onVolatileTextConsumed()
                            onBurnAfterReadSent()
                        },
                        onFailure = { cause ->
                            Timber.e(cause, "Failed to send burn-after-read message")
                            slashCommandAction.value = AsyncAction.Failure(cause)
                        },
                    )
                    if (result == null) {
                        val failure = IllegalStateException("The protected timeline is unavailable.")
                        slashCommandAction.value = AsyncAction.Failure(failure)
                        throw failure
                    }
                    checkNotNull(result) { "The protected timeline is unavailable." }.getOrThrow()
                    return@operation
                }
                val localEchoId = immediateEchoId ?: timelineController.addOptimisticLocalEcho(
                    body = message.markdown,
                    htmlBody = message.html,
                    intentionalMentions = message.intentionalMentions,
                )
                if (immediateEchoId == null) {
                    resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                    onVolatileTextConsumed()
                    Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                        "stage=local_echo duration_ms=%d",
                        (System.nanoTime() - clickStartedAtNanos) / NANOS_PER_MILLISECOND,
                    )
                }
                var result: Result<Unit>? = null
                result = timelineController.sendOptimisticLocalEcho(localEchoId)
                timelineController.resolveOptimisticLocalEcho(localEchoId, result?.exceptionOrNull())
                result?.exceptionOrNull()?.let { cause ->
                    Timber.e(cause, "Failed to send message")
                    slashCommandAction.value = AsyncAction.Failure(cause)
                    throw cause
                }
                checkNotNull(result) { "The protected timeline is unavailable." }.getOrThrow()
            }
            is MessageComposerMode.Edit -> {
                var result: Result<Unit>? = null
                timelineController.invokeOnCurrentTimeline {
                    result = editMessage(capturedMode.eventOrTransactionId, message.markdown, message.html, message.intentionalMentions)
                }
                result?.fold(
                    onSuccess = {
                        resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = true)
                        onVolatileTextConsumed()
                    },
                    onFailure = { cause ->
                        // A direct JoinedRoom edit would create ordinary Matrix content and bypass
                        // the protected timeline. Keep both the edit mode and draft intact so the
                        // user can retry after the protected path becomes available.
                        Timber.e(cause, "Failed to send protected edit")
                        slashCommandAction.value = AsyncAction.Failure(cause)
                    },
                )
                if (result == null) {
                    val failure = IllegalStateException("The protected timeline is unavailable.")
                    slashCommandAction.value = AsyncAction.Failure(failure)
                    throw failure
                }
                checkNotNull(result) { "The protected timeline is unavailable." }.getOrThrow()
            }
            is MessageComposerMode.EditCaption -> {
                val sentCaptionGeneration = captionEditGeneration.get()
                val draftVersion = draftService.captureProtectedCaptionDraftVersion(room.roomId, threadRoot = null)
                var result: Result<Unit>? = null
                timelineController.invokeOnCurrentTimeline {
                    result = editCaption(
                        capturedMode.eventOrTransactionId,
                        caption = message.markdown,
                        formattedCaption = message.html
                    )
                }
                result?.fold(
                    onSuccess = {
                        launch {
                            captionDraftMutationMutex.withLock {
                                if (captionEditGeneration.get() != sentCaptionGeneration) return@withLock
                                val cleared = draftService.clearProtectedCaptionDraftIfVersion(
                                    roomId = room.roomId,
                                    threadRoot = null,
                                    version = draftVersion,
                                )
                                if (cleared && captionEditGeneration.compareAndSet(sentCaptionGeneration, sentCaptionGeneration + 1)) {
                                    // Fence SaveDraft work captured before the successful clear.
                                    resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = true)
                                    onVolatileTextConsumed()
                                }
                            }
                        }
                    },
                    onFailure = { cause ->
                        Timber.e(cause, "Failed to send protected caption edit")
                        slashCommandAction.value = AsyncAction.Failure(cause)
                    },
                )
                if (result == null) {
                    val failure = IllegalStateException("The protected timeline is unavailable.")
                    slashCommandAction.value = AsyncAction.Failure(failure)
                    throw failure
                }
                checkNotNull(result) { "The protected timeline is unavailable." }.getOrThrow()
            }
            is MessageComposerMode.Reply -> {
                val localEchoId = timelineController.addOptimisticLocalEcho(
                    body = message.markdown,
                    htmlBody = message.html,
                    intentionalMentions = message.intentionalMentions,
                    repliedToEventId = capturedMode.eventId,
                )
                resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = false)
                onVolatileTextConsumed()
                Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                    "stage=local_echo duration_ms=%d",
                    (System.nanoTime() - clickStartedAtNanos) / NANOS_PER_MILLISECOND,
                )
                var result: Result<Unit>? = null
                result = timelineController.sendOptimisticLocalEcho(localEchoId)
                timelineController.resolveOptimisticLocalEcho(localEchoId, result?.exceptionOrNull())
                result?.exceptionOrNull()?.let { cause ->
                    Timber.e(cause, "Failed to send reply")
                    slashCommandAction.value = AsyncAction.Failure(cause)
                    throw cause
                }
                checkNotNull(result) { "The protected timeline is unavailable." }.getOrThrow()
            }
        }

        val roomInfo = room.info()
        val roomMembers = room.membersStateFlow.value

        notificationConversationService.onSendMessage(
            sessionId = room.sessionId,
            roomId = roomInfo.id,
            roomName = roomInfo.name,
            roomIsDirect = roomInfo.isDm,
            roomAvatarUrl = roomInfo.avatarUrl ?: roomMembers.getDirectRoomMember(roomInfo = roomInfo, sessionId = room.sessionId)?.avatarUrl,
        )

        analyticsService.capture(
            Composer(
                inThread = capturedMode.inThread,
                isEditing = capturedMode.isEditing,
                isReply = capturedMode.isReply,
                // Set proper type when we'll be sending other types of messages.
                messageType = Composer.MessageType.Text,
            )
        )
            }
        }
        }
        } catch (failure: Exception) {
            immediateEchoId?.let { timelineController.resolveOptimisticLocalEcho(it, failure) }
            if (failure is CancellationException) throw failure
            Timber.e(failure, "Message lifecycle operation failed")
            if (slashCommandAction.value !is AsyncAction.Failure) {
                slashCommandAction.value = AsyncAction.Failure(failure)
            }
        } finally {
            if (burnAfterRead) onFlashReadPreparing(false)
            if (ownsComposerSendGuard) messageSendInProgress.set(false)
        }
    }

    private fun CoroutineScope.sendAttachment(
        attachment: Attachment,
        inReplyToEventId: EventId?,
    ) = when (attachment) {
        is Attachment.Media -> {
            launch {
                appOperationalStateCoordinator.perform(
                    lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_16,
                        trigger = "upload",
                        roomIdHash = room.roomId.value.sha256Hex(),
                        operationId = attachment.localMedia.lifecycleOperationId,
                    )
                ) {
                    sendMedia(
                        uri = attachment.localMedia.uri,
                        mimeType = attachment.localMedia.info.mimeType,
                        inReplyToEventId = inReplyToEventId,
                    ).getOrThrow()
                }
            }
        }
    }

    private fun handlePickedMedia(
        uri: Uri?,
        mimeType: String? = null,
        sendAsFile: Boolean = false,
        burnAfterRead: Boolean = false,
    ): Boolean {
        uri ?: return false
        return handlePickedMedia(
            items = listOf(PickedMedia(uri, mimeType)),
            sendAsFile = sendAsFile,
            burnAfterRead = burnAfterRead,
        )
    }

    private fun handlePickedMedia(
        items: List<PickedMedia>,
        sendAsFile: Boolean = false,
        burnAfterRead: Boolean = false,
        fromGallery: Boolean = false,
        fromRecentImages: Boolean = false,
    ): Boolean {
        if (items.isEmpty()) return false
        val mediaAttachments = try {
            items.map { item ->
                val createdLocalMedia = localMediaFactory.createFromUri(
                    uri = item.uri,
                    mimeType = item.mimeType,
                    name = null,
                    formattedFileSize = null,
                )
                val localMedia = createdLocalMedia.copy(
                    lifecycleOperationId = item.operationId ?: createdLocalMedia.lifecycleOperationId,
                    lifecycleRoomIdHash = room.roomId.value.sha256Hex(),
                )
                Attachment.Media(
                    localMedia,
                    sendAsFile = sendAsFile,
                    originalPhotos = item.originalPhotos,
                    sendImmediately = item.sendImmediately,
                    fromGallery = fromGallery,
                    initiallySelected = item.initiallySelected,
                    focused = item.focused,
                    openEditor = item.openEditor,
                    fromRecentImages = fromRecentImages,
                )
            }.toImmutableList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w(failure, "Unable to prepare selected attachment")
            snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.CommonStrings.common_error))
            return false
        }
        val inReplyToEventId = (messageComposerContext.composerMode as? MessageComposerMode.Reply)?.eventId
        navigator.navigateToPreviewAttachments(
            attachments = mediaAttachments,
            inReplyToEventId = inReplyToEventId,
            burnAfterRead = burnAfterRead,
        )

        // Reset composer since the attachment will be sent in a separate flow
        messageComposerContext.composerMode = MessageComposerMode.Normal
        return true
    }

    private suspend fun sendMedia(
        uri: Uri,
        mimeType: String,
        inReplyToEventId: EventId?,
    ) = runCatchingExceptions {
        mediaSender.sendMedia(
            uri = uri,
            mimeType = mimeType,
            mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
            inReplyToEventId = inReplyToEventId,
        ).getOrThrow()
    }
        .onFailure { cause ->
            Timber.e(cause, "Failed to send attachment")
            if (cause is CancellationException) {
                throw cause
            } else {
                val snackbarMessage = SnackbarMessage(sendAttachmentError(cause))
                snackbarDispatcher.post(snackbarMessage)
            }
        }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private suspend fun beginMediaPick(source: MediaPickSource): MediaPickOperation {
        val createdAt = System.currentTimeMillis()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_14,
            trigger = "draft_change",
            accountIdHash = accountIdHash(room.sessionId.value),
            matrixDeviceIdHash = accountIdHash(matrixClient.deviceId.value),
            roomIdHash = room.roomId.value.sha256Hex(),
            deadlineEpochMillis = createdAt + MEDIA_PICK_OPERATION_TIMEOUT_MILLIS,
        )
        val authority = appOperationalStateCoordinator.start(request)
        return MediaPickOperation(
            operationId = authority.operationId,
            source = source,
            roomIdHash = checkNotNull(authority.subjects.roomIdHash),
            createdAtEpochMillis = authority.createdAtEpochMillis,
            deadlineEpochMillis = authority.deadlineEpochMillis,
        )
    }

    private suspend fun isAuthorizedMediaPick(
        operation: MediaPickOperation?,
        expectedSource: MediaPickSource,
        allowExpiredCancellation: Boolean = false,
    ): Boolean {
        operation ?: return false
        val now = System.currentTimeMillis()
        if (operation.source != expectedSource || operation.roomIdHash != room.roomId.value.sha256Hex()) return false
        if (!allowExpiredCancellation && now >= operation.deadlineEpochMillis) return false
        val authority = runCatchingExceptions {
            appOperationalStateCoordinator.operation(operation.operationId)
        }.getOrNull() ?: return false
        return authority.lifecycleId == FullAppLifecycleId.LC_14 &&
            authority.operationId == operation.operationId &&
            authority.subjects.accountIdHash == accountIdHash(room.sessionId.value) &&
            authority.subjects.matrixDeviceIdHash == accountIdHash(matrixClient.deviceId.value) &&
            authority.subjects.roomIdHash == operation.roomIdHash &&
            authority.createdAtEpochMillis == operation.createdAtEpochMillis &&
            authority.deadlineEpochMillis == operation.deadlineEpochMillis &&
            authority.status in NON_TERMINAL_RESUMABLE_STATUSES &&
            (allowExpiredCancellation || now < authority.deadlineEpochMillis)
    }

    private suspend fun handOffMediaPickCancellation(cancellation: MediaPickCancellation) {
        runCatchingExceptions {
            val operation = cancellation.operation
            val authority = appOperationalStateCoordinator.operation(operation.operationId)
            if (authority.lifecycleId != FullAppLifecycleId.LC_14 ||
                authority.operationId != operation.operationId ||
                authority.subjects.accountIdHash != accountIdHash(room.sessionId.value) ||
                authority.subjects.matrixDeviceIdHash != accountIdHash(matrixClient.deviceId.value) ||
                authority.subjects.roomIdHash != operation.roomIdHash ||
                authority.createdAtEpochMillis != operation.createdAtEpochMillis ||
                authority.deadlineEpochMillis != operation.deadlineEpochMillis
            ) return@runCatchingExceptions
            if (!authority.status.isTerminal) {
                if (authority.status !in NON_TERMINAL_RESUMABLE_STATUSES) return@runCatchingExceptions
                val resultCode = when (operation.source) {
                    MediaPickSource.GALLERY -> "GALLERY_PICK_CANCELLED"
                    MediaPickSource.CAMERA_PHOTO -> "CAMERA_PICK_CANCELLED"
                }
                val reference = when (operation.source) {
                    MediaPickSource.GALLERY -> "gallery-pick-cancelled"
                    MediaPickSource.CAMERA_PHOTO -> "camera-pick-cancelled"
                }
                // Preserve the native fact's original time across process loss; never replay the picker.
                appOperationalStateCoordinator.enqueueClientReceipts(
                    listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
                        LifecycleClientReceipt(operation.operationId, component, LifecyclePhase.CLIENT_APPLIED,
                            resultCode, "$reference:${component.name}:${operation.operationId}", cancellation.completedAtEpochMillis)
                    }
                )
            }
            mediaPickerProvider.acknowledgeCancellation(cancellation)
        }.onFailure {
            // The existing native journal still owns this actual fact; a new presenter retries it.
            Timber.w("Media picker cancellation receipt handoff failed; original recovery fact retained")
        }
    }

    private fun MediaPickBatchResult.readyItems(): List<PickedMedia> =
        items.filterIsInstance<MediaPickItemResult.Ready>().map(MediaPickItemResult.Ready::media)

    private fun reportPickerFailures(result: MediaPickBatchResult) {
        if (result.items.any {
                it is MediaPickItemResult.Failed &&
                    it.reason != io.element.android.libraries.mediapickers.api.MediaPickFailureReason.CANCELLED
            }
        ) {
            snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.CommonStrings.common_error))
        }
    }

    private fun reportPickerLaunchFailure(failure: Throwable) {
        Timber.w(failure, "Unable to start protected media pick operation")
        snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.CommonStrings.common_error))
    }

    private fun reportPickerAuthorityFailure() {
        Timber.w("Ignoring media picker result without an active matching LC-14 operation")
        snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.CommonStrings.common_error))
    }

    private fun relayOperationRequest(eventId: EventId? = null, operationId: String = java.util.UUID.randomUUID().toString()) = lifecycleOperationRequest(
        lifecycleId = FullAppLifecycleId.LC_15,
        trigger = "relay_operation",
        accountIdHash = accountIdHash(room.sessionId.value),
        matrixDeviceIdHash = accountIdHash(matrixClient.deviceId.value),
        roomIdHash = room.roomId.value.sha256Hex(),
        eventIdHash = eventId?.value?.sha256Hex(),
        operationId = operationId,
    )

    private fun CoroutineScope.updateDraft(
        draft: ComposerDraft?,
        isVolatile: Boolean,
        captionEditGeneration: AtomicLong? = null,
        expectedCaptionGeneration: Long? = null,
        captionDraftMutationMutex: Mutex? = null,
        isCurrentDraft: (() -> Boolean)? = null,
        onDraftApplied: (() -> Unit)? = null,
    ) = launch {
        captionDraftMutationMutex?.lock()
        try {
        fun isStaleCaptionGeneration(): Boolean = isCurrentDraft?.invoke() == false ||
            (draft?.draftType is ComposerDraftType.EditCaption &&
                expectedCaptionGeneration != null && expectedCaptionGeneration != captionEditGeneration?.get())
        if (isStaleCaptionGeneration()) return@launch
        try {
            if (!isVolatile) draftService.preparePersistentDraftWrite(room.roomId, null)
            // Automatic editor-empty checkpoints defer to the original retained handoff. They
            // must not create an LC-14 applied receipt for a clear that has not actually occurred.
            if (!isVolatile && draft == null && draftService.hasPendingTextSendDraft(room.roomId)) return@launch
            // Recovery can suspend. Do not admit a draft superseded while the original write was being recovered.
            if (isStaleCaptionGeneration()) return@launch
            appOperationalStateCoordinator.performLocalDraft(
                lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_14,
                    trigger = "draft_change",
                    accountIdHash = accountIdHash(room.sessionId.value),
                    matrixDeviceIdHash = accountIdHash(matrixClient.deviceId.value),
                    roomIdHash = room.roomId.value.sha256Hex(),
                )
            ) {
                // Supersession cannot become an applied fact; persistent stores own their versioned result outbox.
                if (isStaleCaptionGeneration()) throw CancellationException("Composer draft was superseded before persistence.")
                draftService.updateDraft(
                    roomId = room.roomId,
                    draft = draft,
                    isVolatile = isVolatile,
                    // TODO support threads in composer
                    threadRoot = null,
                )
                onDraftApplied?.invoke()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Timber.w(failure, "Unable to bind composer draft lifecycle")
        }
        } finally {
            captionDraftMutationMutex?.unlock()
        }
    }

    private suspend fun applyDraft(
        draft: ComposerDraft,
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
    ) {
        val htmlText = draft.htmlText
        val markdownText = draft.plainText
        if (htmlText != null) {
            showTextFormatting = true
            setText(htmlText, markdownTextEditorState, richTextEditorState, requestFocus = true)
        } else {
            showTextFormatting = false
            setText(markdownText, markdownTextEditorState, richTextEditorState, requestFocus = true)
        }
        when (val draftType = draft.draftType) {
            ComposerDraftType.NewMessage -> messageComposerContext.composerMode = MessageComposerMode.Normal
            is ComposerDraftType.Edit -> messageComposerContext.composerMode = MessageComposerMode.Edit(
                eventOrTransactionId = draftType.eventId.toEventOrTransactionId(),
                content = htmlText ?: markdownText
            )
            is ComposerDraftType.EditCaption -> messageComposerContext.composerMode = MessageComposerMode.EditCaption(
                eventOrTransactionId = draftType.eventId.toEventOrTransactionId(),
                content = htmlText ?: markdownText,
            )
            is ComposerDraftType.Reply -> {
                messageComposerContext.composerMode = MessageComposerMode.Reply(
                    replyToDetails = InReplyToDetails.Loading(draftType.eventId),
                    // I guess it's fine to always render the image when restoring a draft
                    hideImage = false
                )
            }
        }
    }

    private fun createDraftFromState(
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
    ): ComposerDraft? {
        val message = currentComposerMessage(markdownTextEditorState, richTextEditorState, withMentions = false)
        val draftType = when (val mode = messageComposerContext.composerMode) {
            is MessageComposerMode.Attachment,
            is MessageComposerMode.Normal -> ComposerDraftType.NewMessage
            is MessageComposerMode.Edit -> {
                mode.eventOrTransactionId.eventId?.let { eventId -> ComposerDraftType.Edit(eventId) }
            }
            is MessageComposerMode.Reply -> ComposerDraftType.Reply(mode.eventId)
            is MessageComposerMode.EditCaption -> {
                mode.eventOrTransactionId.eventId?.let { eventId -> ComposerDraftType.EditCaption(eventId) }
            }
        }
        return if (draftType == null || message.markdown.isBlank()) {
            null
        } else {
            ComposerDraft(
                draftType = draftType,
                htmlText = message.html,
                plainText = message.markdown,
            )
        }
    }

    private fun currentComposerMessage(
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
        withMentions: Boolean,
    ): Message {
        return if (showTextFormatting) {
            val html = richTextEditorState.messageHtml
            val markdown = richTextEditorState.messageMarkdown
            val mentions = richTextEditorState.mentionsState
                .takeIf { withMentions }
                ?.let { state ->
                    buildList {
                        if (state.hasAtRoomMention) {
                            add(IntentionalMention.Room)
                        }
                        for (userId in state.userIds) {
                            add(IntentionalMention.User(UserId(userId)))
                        }
                    }
                }
                .orEmpty()
            Message(html = html, markdown = markdown, intentionalMentions = mentions)
        } else {
            val markdown = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
            val mentions = if (withMentions) {
                markdownTextEditorState.getMentions()
            } else {
                emptyList()
            }
            Message(html = null, markdown = markdown, intentionalMentions = mentions)
        }
    }

    private fun CoroutineScope.toggleTextFormatting(
        enabled: Boolean,
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState
    ) = launch {
        showTextFormatting = enabled
        if (showTextFormatting) {
            val markdown = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
            richTextEditorState.setMarkdown(markdown)
            richTextEditorState.requestFocus()
            analyticsService.captureInteraction(Interaction.Name.MobileRoomComposerFormattingEnabled)
        } else {
            val markdown = richTextEditorState.messageMarkdown
            val markdownWithMentions = pillificationHelper.pillify(markdown, false)
            markdownTextEditorState.text.update(markdownWithMentions, true)
            // Give some time for the focus of the previous editor to be cleared
            delay(100)
            markdownTextEditorState.requestFocusAction()
        }
    }

    private fun CoroutineScope.setMode(
        newComposerMode: MessageComposerMode,
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
    ) = launch {
        val currentComposerMode = messageComposerContext.composerMode
        when (newComposerMode) {
            is MessageComposerMode.Edit -> {
                if (currentComposerMode.isEditing.not()) {
                    val draft = createDraftFromState(markdownTextEditorState, richTextEditorState)
                    updateDraft(draft, isVolatile = true).join()
                }
                setText(newComposerMode.content, markdownTextEditorState, richTextEditorState, requestFocus = true)
            }
            is MessageComposerMode.EditCaption -> {
                if (currentComposerMode.isEditing.not()) {
                    val draft = createDraftFromState(markdownTextEditorState, richTextEditorState)
                    updateDraft(draft, isVolatile = true).join()
                }
                setText(newComposerMode.content, markdownTextEditorState, richTextEditorState, requestFocus = true)
            }
            else -> {
                // When coming from edit, just clear the composer as it'd be weird to reset a volatile draft in this scenario.
                if (currentComposerMode.isEditing) {
                    setText("", markdownTextEditorState, richTextEditorState)
                }
            }
        }
        messageComposerContext.composerMode = newComposerMode
    }

    private suspend fun resetComposer(
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
        fromEdit: Boolean,
    ) {
        // Use the volatile draft only when coming from edit mode otherwise.
        val draft = if (fromEdit) {
            draftService.loadDraft(
                roomId = room.roomId,
                // TODO support threads in composer
                threadRoot = null,
                isVolatile = true
            )
        } else {
            null
        }

        if (draft != null) {
            applyDraft(draft, markdownTextEditorState, richTextEditorState)
        } else {
            setText("", markdownTextEditorState, richTextEditorState)
            messageComposerContext.composerMode = MessageComposerMode.Normal
        }
    }

    private suspend fun sendSticker(uri: Uri, mimeType: String) = runCatchingExceptions {
        mediaSender.sendSticker(
            uri = uri,
            mimeType = mimeType,
            mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
        ).getOrThrow()
    }.onFailure { cause ->
        Timber.e(cause, "Failed to send protected sticker")
        if (cause is CancellationException) throw cause
        snackbarDispatcher.post(SnackbarMessage(sendAttachmentError(cause)))
    }

    private suspend fun setText(
        content: String,
        markdownTextEditorState: MarkdownTextEditorState,
        richTextEditorState: RichTextEditorState,
        requestFocus: Boolean = false,
    ) {
        if (showTextFormatting) {
            richTextEditorState.setHtml(content)
            if (requestFocus) {
                richTextEditorState.requestFocus()
            }
        } else {
            if (content.isEmpty()) {
                markdownTextEditorState.selection = IntRange.EMPTY
            }
            val pillifiedContent = pillificationHelper.pillify(content, false)
            markdownTextEditorState.text.update(pillifiedContent, true)
            if (requestFocus) {
                markdownTextEditorState.requestFocusAction()
            }
        }
    }
}
