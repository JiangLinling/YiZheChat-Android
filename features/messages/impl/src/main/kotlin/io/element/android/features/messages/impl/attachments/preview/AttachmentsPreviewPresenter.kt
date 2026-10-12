/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.features.messages.impl.draft.MediaSelectionDraft
import io.element.android.features.messages.impl.draft.MediaSelectionDraftStore
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.MediaCancellationPendingException
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCancelReason
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.performForComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: List<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    @Assisted private val burnAfterRead: Boolean,
    @Assisted private val accountIdHash: String,
    @Assisted private val matrixDeviceIdHash: String,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val attachmentImageEditor: AttachmentImageEditor,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
    private val videoCompressionPresetSelector: VideoCompressionPresetSelector,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val localAttachmentPreviewLeaseService: LocalAttachmentPreviewLeaseService,
    private val timelineController: TimelineController,
    private val mediaDraftStore: MediaSelectionDraftStore? = null,
) : Presenter<AttachmentsPreviewState> {
    @AssistedFactory
    interface Factory {
        fun create(
            attachments: List<Attachment>,
            timelineMode: Timeline.Mode,
            onDoneListener: OnDoneListener,
            inReplyToEventId: EventId?,
            burnAfterRead: Boolean,
            accountIdHash: String,
            matrixDeviceIdHash: String,
        ): AttachmentsPreviewPresenter
    }

    private val mediaSender = mediaSenderFactory.create(timelineMode)
    private val attachment: Attachment = attachments.first()

    @Composable
    override fun present(): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()
        val mediaDraftStore = this.mediaDraftStore.takeIf {
            attachments.all { item ->
                val media = item as Attachment.Media
                media.sendAsFile.not() && (media.localMedia.info.mimeType.isMimeTypeImage() || media.localMedia.info.mimeType.isMimeTypeVideo())
            }
        }

        val sendActionState = remember {
            mutableStateOf<SendActionState>(SendActionState.Idle)
        }
        var draftReady by remember { mutableStateOf(mediaDraftStore == null) }
        var draftError by remember { mutableStateOf(false) }
        val draftOperationId = (attachment as Attachment.Media).localMedia.lifecycleOperationId
        val sourceAttachments = remember { attachments.toMutableStateList() }
        val editedAttachments = remember { attachments.toMutableStateList() }
        var focusedIndex by rememberSaveable { mutableStateOf(attachments.indexOfFirst { (it as Attachment.Media).focused }.coerceAtLeast(0)) }
        val selectedIndices = remember { attachments.indices.filter { (attachments[it] as Attachment.Media).initiallySelected }.toMutableStateList() }
        val committedIndices = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() }
        val discardedIndices = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() }
        val cancellationRequestedIndices = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() }
        val inFlightIndices = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() }
        val restoredUncertainIndices = remember { mutableStateListOf<Int>() }
        val submissionFacts = remember { java.util.concurrent.ConcurrentHashMap<Int, MediaSubmissionFact>() }
        val failedIndices = remember { mutableStateListOf<Int>() }
        var confirmationStillPending by remember { mutableStateOf(false) }
        var recoveryDetailsVisible by remember { mutableStateOf(false) }
        var checkingRecovery by remember { mutableStateOf(false) }
        var recoveryChecks by remember { mutableStateOf(emptyList<MediaRecoveryItemCheck>()) }
        val imageEdits = remember { mutableStateMapOf<Int, AttachmentImageEdits>() }
        val editResources = remember { mutableStateMapOf<Int, io.element.android.features.messages.impl.attachments.preview.imageeditor.EditedLocalMedia>() }
        val retryMutex = remember { Mutex() }
        val handedOff = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val effectiveBurnAfterRead = remember { java.util.concurrent.atomic.AtomicBoolean(burnAfterRead) }
        var originalPhotos by rememberSaveable { mutableStateOf((attachment as Attachment.Media).originalPhotos) }
        var videoQuality by remember { mutableStateOf(VideoCompressionPreset.STANDARD) }
        var videoEditorOpen by remember { mutableStateOf(false) }
        val undo = remember { mutableStateListOf<AttachmentImageEdits>() }
        val redo = remember { mutableStateListOf<AttachmentImageEdits>() }
        DisposableEffect(Unit) {
            onDispose {
                if (!handedOff.get()) {
                editResources.values.forEach { resource ->
                if (mediaDraftStore == null || !draftReady) resource.close() else resource.lease?.close()
            }
            }
            }
        }
        val originalAttachment = sourceAttachments[focusedIndex] as Attachment.Media
        val originalLocalMedia = originalAttachment.localMedia
        val mediaAlbumId = remember(attachments) {
            if (attachments.size > 1 && attachments.all { item ->
                    val mimeType = (item as? Attachment.Media)?.localMedia?.info?.mimeType
                    mimeType?.isMimeTypeImage() == true || mimeType?.isMimeTypeVideo() == true
                }
            ) {
                UUID.randomUUID().toString().replace("-", "")
            } else {
                null
            }
        }
        // Keep one source of truth: delayed pager/gallery callbacks may retain an older composition.
        val currentAttachment = editedAttachments[focusedIndex]
        var canEditImage by remember(focusedIndex) { mutableStateOf(originalLocalMedia.info.canEditImage()) }
        var imageEditorState by remember { mutableStateOf<AttachmentImageEditorState?>(null) }
        var appliedImageEdits by remember(focusedIndex) { mutableStateOf(imageEdits[focusedIndex] ?: AttachmentImageEdits()) }
        var isApplyingImageEdits by remember { mutableStateOf(false) }
        var displayImageEditError by remember { mutableStateOf(false) }
        var editedTempFile by remember { mutableStateOf<File?>(null) }
        val filePreviewController = remember(originalLocalMedia) {
            if (localAttachmentPreviewLeaseService.requiresLease(originalLocalMedia)) {
                AttachmentFilePreviewController(originalLocalMedia, localAttachmentPreviewLeaseService)
            } else null
        }
        val filePreviewLease = filePreviewController?.lease

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        var preprocessMediaJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(Unit) {
            try {
                mediaDraftStore?.load(draftOperationId)?.let { saved ->
                    require(
                        saved.sources.take(attachments.size).map { (it as Attachment.Media).localMedia.lifecycleOperationId } ==
                            attachments.map { (it as Attachment.Media).localMedia.lifecycleOperationId }
                    )
                    require(saved.attachments.all {
                        (it as Attachment.Media).localMedia.lifecycleRoomIdHash ==
                        (attachment as Attachment.Media).localMedia.lifecycleRoomIdHash
                    })
                    require(saved.burnAfterRead == burnAfterRead)
                    val restored = saved.attachments.toMutableList()
                    saved.resources.filterKeys { it !in saved.committed || saved.submissions[it]?.phase != MediaSubmissionPhase.ACCEPTED }.forEach { (index, reference) ->
                        val resource = mediaDraftStore.restoreResource(reference, restored[index] as Attachment.Media)
                        restored[index] = (restored[index] as Attachment.Media).copy(localMedia = resource.localMedia)
                        editResources[index] = resource
                    }
                    sourceAttachments.clear()
                    sourceAttachments.addAll(saved.sources)
                    editedAttachments.clear()
                    editedAttachments.addAll(restored)
                    selectedIndices.clear()
                    selectedIndices.addAll(saved.selected.filterNot { it in saved.committed || it in saved.inFlight || it in saved.discarded || it in saved.cancellationRequested })
                    committedIndices.addAll(saved.committed)
                    discardedIndices.addAll(saved.discarded)
                    cancellationRequestedIndices.addAll(saved.cancellationRequested)
                    inFlightIndices.addAll(saved.inFlight)
                    restoredUncertainIndices.addAll((saved.inFlight + saved.committed.filter {
                        saved.submissions[it]?.phase != MediaSubmissionPhase.ACCEPTED
                    } + saved.cancellationRequested.filterNot { it in saved.discarded }).distinct())
                    submissionFacts.putAll(saved.submissions)
                    failedIndices.addAll(saved.submissions.filterValues { it.phase == MediaSubmissionPhase.PRE_SDK }.keys.filter {
                        it !in saved.committed && it !in saved.cancellationRequested && it !in saved.discarded
                    })
                    focusedIndex = saved.focused
                    imageEdits.putAll(saved.edits)
                    appliedImageEdits = imageEdits[focusedIndex] ?: AttachmentImageEdits()
                    originalPhotos = saved.originalPhotos
                    videoQuality = saved.videoQuality
                    saved.pending?.let {
                        imageEditorState =
                        AttachmentImageEditorState((sourceAttachments[focusedIndex] as Attachment.Media).localMedia, it, false)
                    }
                }
                draftReady = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.e(failure, "Media draft restoration failed")
                draftError = true
            }
        }
        val draftSnapshot = MediaSelectionDraft(
            editedAttachments.toList(),
            selectedIndices.toList(),
            focusedIndex,
            imageEdits.toMap(),
            imageEditorState?.edits,
            originalPhotos,
            videoQuality,
            editResources.mapNotNull { (index, resource) -> resource.reference?.let { index to it } }.toMap(),
            sources = sourceAttachments.toList(),
            committed = committedIndices.toList(),
            discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(),
            burnAfterRead = effectiveBurnAfterRead.get(),
            inFlight = inFlightIndices.toList(),
            submissions = submissionFacts.toMap()
        )
        var draftPersistenceJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(draftSnapshot, draftReady) {
            draftPersistenceJob = currentCoroutineContext()[Job]
            if (draftReady && mediaDraftStore != null && !handedOff.get()) {
                delay(150)
                if (handedOff.get()) return@LaunchedEffect
                try {
                    mediaDraftStore.save(draftOperationId, draftSnapshot)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    draftError = true
                    Timber.e(failure, "Media draft persistence failed")
                }
            }
        }

        val mediaAttachment = currentAttachment as Attachment.Media
        val mediaOptimizationSelectorPresenter = remember(mediaAttachment.localMedia.uri) {
            mediaOptimizationSelectorPresenterFactory.create(
                localMedia = mediaAttachment.localMedia,
                sendAsFile = mediaAttachment.sendAsFile,
            )
        }
        val mediaOptimizationSelectorState by rememberUpdatedState(key(mediaAttachment.localMedia.uri) { mediaOptimizationSelectorPresenter.present() })

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        DisposableEffect(filePreviewController) {
            filePreviewController?.start(coroutineScope)
            onDispose { filePreviewController?.close() }
        }

        LaunchedEffect(
            mediaOptimizationSelectorState.displayMediaSelectorViews,
            mediaOptimizationSelectorState.videoSizeEstimations,
            currentAttachment,
            originalPhotos,
            videoQuality,
            draftReady,
            imageEditorState,
            isApplyingImageEdits,
        ) {
            if (localAttachmentPreviewLeaseService.requiresLease(mediaAttachment.localMedia)) {
                return@LaunchedEffect
            }
            // If the media optimization selector is not displayed, we can pre-process the media
            // to prepare it for sending. This is done to avoid blocking the UI thread when the
            // user clicks on the send button.
            @Suppress("ComplexCondition")
            if (sourceAttachments.size == 1 && draftReady && !draftError && mediaOptimizationSelectorState.displayMediaSelectorViews == false &&
                preprocessMediaJob == null &&
                imageEditorState == null &&
                !isApplyingImageEdits) {
                if (mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo() && mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull() == null) {
                    Timber.d("Waiting for video size estimations to be able to select the best video compression preset before pre-processing the media")
                    return@LaunchedEffect
                }
                val config = getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = mediaAttachment,
                    mediaOptimizationSelectorState = mediaOptimizationSelectorState,
                ) ?: return@LaunchedEffect
                preprocessMediaJob = coroutineScope.preProcessAttachment(
                    attachment = currentAttachment,
                    mediaOptimizationConfig = config.copy(
                        compressImages = !originalPhotos && !mediaAttachment.sendAsFile,
                        videoCompressionPreset = if (mediaAttachment.sendAsFile) config.videoCompressionPreset else videoQuality,
                        videoStartMs = mediaAttachment.videoStartMs,
                        videoEndMs = mediaAttachment.videoEndMs,
                        muteAudio = mediaAttachment.muteAudio,
                    ),
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        LaunchedEffect(originalLocalMedia) {
            canEditImage = originalLocalMedia.info.canEditImage() || attachmentImageEditor.canEdit(originalLocalMedia)
        }

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        LaunchedEffect(maxUploadSize) {
            // Check file upload size if the media won't be processed for upload
            val isImageFile = mediaAttachment.localMedia.info.isImageAttachment()
            val isVideoFile = mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo()
            if (maxUploadSize != null && !(isImageFile || isVideoFile)) {
                // If file size is not known, we're permissive and allow sending. The SDK will cancel the upload if needed.
                val fileSize = mediaAttachment.localMedia.info.fileSize ?: 0L
                if (maxUploadSize < fileSize) {
                    displayFileTooLargeError = true
                }
            }
        }

        val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
        LaunchedEffect(videoSizeEstimations, mediaAttachment.videoStartMs, mediaAttachment.videoEndMs) {
            if (videoSizeEstimations != null) {
                // Check if the video size estimations are too large for the max upload size
                displayFileTooLargeError =
                    mediaAttachment.videoStartMs == 0L && mediaAttachment.videoEndMs == null && videoSizeEstimations.none { it.canUpload }
            }
        }

        fun updateImage(edits: AttachmentImageEdits) {
            val pending = imageEditorState ?: return
            undo.add(pending.edits)
            if (undo.size > 64) undo.removeAt(0)
            redo.clear()
            imageEditorState = pending.copy(edits = edits)
        }
        fun configFor(item: Attachment.Media, config: MediaOptimizationConfig) = config.copy(
            compressImages = !originalPhotos && !item.sendAsFile,
            videoCompressionPreset = if (item.sendAsFile) config.videoCompressionPreset else videoQuality,
            videoStartMs = item.videoStartMs,
            videoEndMs = item.videoEndMs,
            muteAudio = item.muteAudio,
        )
        suspend fun persistSubmission(index: Int, fact: MediaSubmissionFact) {
            val old = submissionFacts[index]
            val merged = io.element.android.features.messages.impl.draft.mergeMediaSubmissionFacts(
                old?.let { mapOf(index to it) }.orEmpty(), mapOf(index to fact))
            submissionFacts[index] = merged.getValue(index)
            mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                attachments = editedAttachments.toList(), sources = sourceAttachments.toList(),
                selected = selectedIndices.toList(), committed = committedIndices.toList(),
                inFlight = inFlightIndices.toList(), submissions = submissionFacts.toMap(),
                discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList()))
        }
        suspend fun checkpoint(index: Int) {
            check(index !in cancellationRequestedIndices && index !in discardedIndices) { "Original cancellation is unresolved." }
            check(mediaDraftStore == null || submissionFacts[index]?.phase == MediaSubmissionPhase.ACCEPTED) {
                "Matrix has not confirmed the original media event."
            }
            failedIndices.remove(index)
            restoredUncertainIndices.remove(index)
            inFlightIndices.remove(index)
            if (index !in committedIndices) committedIndices.add(index)
            mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                attachments = editedAttachments.toList(),
                selected = selectedIndices.toList(),
                sources = sourceAttachments.toList(),
                committed = committedIndices.toList(),
            discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(),
                inFlight = inFlightIndices.toList(),
                submissions = submissionFacts.toMap(),
                pending = null,
                burnAfterRead = effectiveBurnAfterRead.get()
            ))
            submissionFacts[index]?.takeIf { it.phase == MediaSubmissionPhase.ACCEPTED && it.clientAppliedAtEpochMillis == null }?.let { fact ->
                // The actual original checkpoint has been applied. Retain its first occurrence,
                // separately from SDK commit and derived-file cleanup.
                persistSubmission(index, fact.copy(clientAppliedAtEpochMillis = System.currentTimeMillis()))
            }
        }
        suspend fun markSending(index: Int, flashRead: Boolean) {
            check(submissionFacts[index]?.phase.let { it == null || it == MediaSubmissionPhase.PRE_SDK }) {
                "Original media submission must be confirmed before any new SDK dispatch."
            }
            submissionFacts[index] = MediaSubmissionFact(MediaSubmissionPhase.PRE_SDK)
            if (index !in inFlightIndices) inFlightIndices.add(index)
            mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                attachments = editedAttachments.toList(),
                sources = sourceAttachments.toList(),
                selected = selectedIndices.toList(),
                committed = committedIndices.toList(),
            discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(),
                inFlight = inFlightIndices.toList(),
                submissions = submissionFacts.toMap(),
                burnAfterRead = flashRead
            ))
        }
        fun submissionContext(index: Int) = mediaDraftStore?.let {
            MediaSubmissionContext(
                beforeSdk = { transport -> persistSubmission(index, MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, transport)) },
                accepted = { fact ->
                    val retained = it.recordOriginalSubmission(draftOperationId, index, fact)
                    val source = (sourceAttachments[index] as Attachment.Media).localMedia
                    if (!effectiveBurnAfterRead.get() && retained.uploadOperationId == source.lifecycleOperationId) {
                        recoverOriginalMediaUploadCompletion(appOperationalStateCoordinator, source.lifecycleOperationId,
                            accountIdHash, matrixDeviceIdHash, requireNotNull(source.lifecycleRoomIdHash), retained,
                            applyCheckpoint = { it.applyOriginalSubmission(draftOperationId, index, retained) })
                    }
                    // Presentation follows durable ownership; it is never required for receipts.
                    submissionFacts[index] = it.load(draftOperationId)?.submissions?.get(index) ?: retained
                },
                observed = { fact ->
                    submissionFacts[index] = it.recordOriginalSubmission(draftOperationId, index, fact)
                },
                originalDeadline = {
                    val source = (sourceAttachments[index] as Attachment.Media).localMedia
                    val originalRoomHash = requireNotNull(source.lifecycleRoomIdHash)
                    val original = appOperationalStateCoordinator.operation(source.lifecycleOperationId)
                    require(original.operationId == source.lifecycleOperationId &&
                        original.lifecycleId in setOf(FullAppLifecycleId.LC_16, FullAppLifecycleId.LC_21))
                    require(original.subjects.accountIdHash == accountIdHash && original.subjects.matrixDeviceIdHash == matrixDeviceIdHash &&
                        original.subjects.roomIdHash == originalRoomHash)
                    check(!original.status.isTerminal) { "Original media operation is terminal." }
                    original.deadlineEpochMillis
                },
                beforeKmsStore = { id -> persistSubmission(index, MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, contentIds = setOf(id))) },
            )
        }
        fun invalidatePreparation() {
            preprocessMediaJob?.cancel()
            preprocessMediaJob = null
            resetPreparedMedia(sendActionState)
        }
        fun handleEvent(event: AttachmentsPreviewEvent) {
            if (event == AttachmentsPreviewEvent.ShowRecoveryDetails) { recoveryDetailsVisible = true; return }
            if (event == AttachmentsPreviewEvent.DismissRecoveryDetails) { recoveryDetailsVisible = false; return }
            val originalResultPending = restoredUncertainIndices.isNotEmpty() ||
                inFlightIndices.any { submissionFacts[it]?.phase != MediaSubmissionPhase.PRE_SDK }
            val mutatesSource = when (event) {
                is AttachmentsPreviewEvent.UpdateGallerySelection,
                is AttachmentsPreviewEvent.SetOriginalPhotos, is AttachmentsPreviewEvent.SetVideoQuality,
                AttachmentsPreviewEvent.OpenImageEditor, AttachmentsPreviewEvent.ApplyImageEdits,
                AttachmentsPreviewEvent.OpenVideoEditor, is AttachmentsPreviewEvent.ApplyVideoEdits -> true
                else -> false
            }
            if ((originalResultPending || checkingRecovery || focusedIndex in committedIndices) && mutatesSource) return
            if (event == AttachmentsPreviewEvent.CancelAndDismiss && originalResultPending) {
                handleEvent(AttachmentsPreviewEvent.DismissPreservingDraft)
                return
            }
            if (!draftReady || draftError) {
                if (event == AttachmentsPreviewEvent.DismissPreservingDraft) {
                    onDoneListener()
                    return
                }
                if (event == AttachmentsPreviewEvent.CancelAndDismiss) {
                    coroutineScope.launch {
                        mediaDraftStore?.clear(draftOperationId)
                        editResources.values.forEach { it.close() }
                        onDoneListener()
                    }
                }
                return
            }
            when (event) {
                is AttachmentsPreviewEvent.UpdateGallerySelection -> {
                    if (event.items.isEmpty()) return
                    selectedIndices.clear()
                    event.items.forEach { item ->
                        var index = sourceAttachments.indexOfFirst {
                            (it as Attachment.Media).localMedia.lifecycleOperationId ==
                            item.localMedia.lifecycleOperationId
                        }
                        if (index < 0) {
                            index = sourceAttachments.size
                            sourceAttachments.add(item)
                            editedAttachments.add(item)
                        }
                        if (item.initiallySelected && index !in committedIndices && index !in inFlightIndices && index !in discardedIndices && index !in cancellationRequestedIndices) selectedIndices.add(index)
                        if (item.focused) focusedIndex = index
                    }
                    originalPhotos = event.originalPhotos
                    if (event.items.any { it.focused && it.openEditor }) {
                        handleEvent(if ((editedAttachments[focusedIndex] as Attachment.Media).localMedia.info.mimeType.isMimeTypeVideo()) {
                            AttachmentsPreviewEvent.OpenVideoEditor
                        } else {
                            AttachmentsPreviewEvent.OpenImageEditor
                        })
                    }
                    if (event.send) handleEvent(AttachmentsPreviewEvent.SendAttachment)
                }
                is AttachmentsPreviewEvent.FocusAttachment -> {
                    if (isApplyingImageEdits || imageEditorState != null || videoEditorOpen || ongoingSendAttachmentJob.value?.isActive == true) return
                    if (event.index !in editedAttachments.indices) return
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    resetPreparedMedia(sendActionState)
                    focusedIndex = event.index
                }
                is AttachmentsPreviewEvent.ToggleAttachment -> {
                    if (event.index !in editedAttachments.indices || ongoingSendAttachmentJob.value?.isActive == true) return
                    if (event.index in committedIndices || event.index in inFlightIndices ||
                        event.index in discardedIndices || event.index in cancellationRequestedIndices) {
                        recoveryDetailsVisible = true
                        return
                    }
                    if (event.index in selectedIndices) {
                        selectedIndices.remove(event.index)
                    } else if (selectedIndices.size < if (attachments.all { (it as Attachment.Media).sendAsFile }) attachments.size else 9) {
                        selectedIndices.add(event.index)
                    }
                }
                is AttachmentsPreviewEvent.SetOriginalPhotos -> {
                    originalPhotos = event.value
                    invalidatePreparation()
                }
                is AttachmentsPreviewEvent.SetVideoQuality -> {
                    videoQuality = event.preset
                    invalidatePreparation()
                }
                AttachmentsPreviewEvent.OpenVideoEditor -> {
                    videoEditorOpen = true
                }
                AttachmentsPreviewEvent.CloseVideoEditor -> {
                    videoEditorOpen = false
                }
                is AttachmentsPreviewEvent.ApplyVideoEdits -> {
                    require(event.startMs >= 0 && (event.endMs == null || event.endMs > event.startMs))
                    editedAttachments[focusedIndex] = (editedAttachments[focusedIndex] as Attachment.Media)
                        .copy(videoStartMs = event.startMs, videoEndMs = event.endMs, muteAudio = event.mute)
                    videoEditorOpen = false
                    invalidatePreparation()
                }
                is AttachmentsPreviewEvent.ReplaceImageAnnotations -> imageEditorState?.let { updateImage(it.edits.copy(annotations = event.annotations)) }
                is AttachmentsPreviewEvent.AddImageAnnotation -> imageEditorState?.let {
                    updateImage(it.edits.copy(annotations =
                    it.edits.annotations + event.annotation))
                }
                AttachmentsPreviewEvent.UndoImageEdit -> if (undo.isNotEmpty()) {
                    redo.add(imageEditorState!!.edits)
                    imageEditorState = imageEditorState?.copy(edits = undo.removeAt(undo.lastIndex))
                }
                AttachmentsPreviewEvent.RedoImageEdit -> if (redo.isNotEmpty()) {
                    undo.add(imageEditorState!!.edits)
                    imageEditorState = imageEditorState?.copy(edits = redo.removeAt(redo.lastIndex))
                }
                AttachmentsPreviewEvent.ConfirmSubmission -> {
                    recoveryDetailsVisible = true
                    if (checkingRecovery || ongoingSendAttachmentJob.value?.isActive == true) return
                    checkingRecovery = true
                    val indices = (restoredUncertainIndices.toList() + focusedIndex).distinct()
                    coroutineScope.launch {
                        try {
                            val checked = coroutineScope {
                                indices.map { index -> async {
                                    val source = (sourceAttachments[index] as Attachment.Media).localMedia
                                    val operation = inspectOriginalMediaOperation(appOperationalStateCoordinator,
                                        source.lifecycleOperationId, accountIdHash, matrixDeviceIdHash,
                                        source.lifecycleRoomIdHash, effectiveBurnAfterRead.get())
                                    val transport = submissionFacts[index]?.transportName
                                    val canReconcileSubmission = index !in cancellationRequestedIndices && index !in discardedIndices
                                    val fact = transport?.takeIf { canReconcileSubmission }?.let { name ->
                                        val retained = submissionFacts[index]
                                        val observed = runCatchingExceptions { mediaSender.mediaSubmissionFact(name) }.getOrNull()
                                            ?.takeIf { it.phase == MediaSubmissionPhase.ACCEPTED && it.transportName == name }
                                        if (observed != null) io.element.android.features.messages.impl.draft.mergeMediaSubmissionFacts(
                                            retained?.let { mapOf(index to it) }.orEmpty(), mapOf(index to observed)).getValue(index)
                                        else retained?.takeIf { it.phase == MediaSubmissionPhase.ACCEPTED }
                                    }
                                    val verdict = when {
                                        index in discardedIndices -> MediaSubmissionCheck.DISCARDED
                                        index in cancellationRequestedIndices -> MediaSubmissionCheck.CANCEL_PENDING
                                        submissionFacts[index]?.phase == MediaSubmissionPhase.ACCEPTED || fact != null -> MediaSubmissionCheck.ACCEPTED
                                        transport == null -> MediaSubmissionCheck.MISSING_IDENTITY
                                        else -> MediaSubmissionCheck.NOT_CONFIRMED
                                    }
                                    MediaRecoveryItemCheck(index, operation, verdict) to fact
                                } }.awaitAll()
                            }
                            recoveryChecks = checked.map { it.first }
                            checked.forEach { (result, fact) ->
                                if (fact != null) {
                                    persistSubmission(result.index, fact)
                                    checkpoint(result.index)
                                    val source = (sourceAttachments[result.index] as Attachment.Media).localMedia
                                    if (!effectiveBurnAfterRead.get()) {
                                        recoverOriginalMediaUploadCompletion(appOperationalStateCoordinator,
                                            source.lifecycleOperationId, accountIdHash, matrixDeviceIdHash,
                                            requireNotNull(source.lifecycleRoomIdHash), checkNotNull(submissionFacts[result.index]),
                                            applyCheckpoint = { checkpoint(result.index); checkNotNull(submissionFacts[result.index]) })
                                    }
                                    selectedIndices.remove(result.index)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            draftError = true
                            Timber.e(failure, "Media recovery checkpoint failed")
                        } finally {
                            checkingRecovery = false
                            confirmationStillPending = restoredUncertainIndices.isNotEmpty()
                        }
                    }
                }
                AttachmentsPreviewEvent.ShowRecoveryDetails, AttachmentsPreviewEvent.DismissRecoveryDetails -> Unit
                AttachmentsPreviewEvent.RetryAttachment -> {
                    if (ongoingSendAttachmentJob.value?.isActive != true) {
                        invalidatePreparation()
                        handleEvent(AttachmentsPreviewEvent.SendAttachment)
                    }
                }
                AttachmentsPreviewEvent.SendAttachment,
                AttachmentsPreviewEvent.SendFlashReadAttachment -> {
                    val sendAsFlashRead = effectiveBurnAfterRead.get() || event is AttachmentsPreviewEvent.SendFlashReadAttachment
                    effectiveBurnAfterRead.set(sendAsFlashRead)
                    if (checkingRecovery || selectedIndices.isEmpty() || ongoingSendAttachmentJob.value?.isActive == true || isApplyingImageEdits) return
                    if (sendActionState.value is SendActionState.Failure) return
                    val activeFilePreviewLease = filePreviewLease
                    val sendingIndices = selectedIndices.filterNot { it in committedIndices || it in discardedIndices || it in cancellationRequestedIndices ||
                        it in inFlightIndices && submissionFacts[it]?.phase != MediaSubmissionPhase.PRE_SDK }
                    if (sendingIndices.isEmpty()) return
                    filePreviewController?.cancelPreparation()
                    val sendingAttachments = sendingIndices.map { editedAttachments[it] }
                    val selectedResources = sendingIndices.mapNotNull { editResources[it] }
                    handedOff.set(true)
                    val useInlineMediaProgress = timelineMode == Timeline.Mode.Live && sendingAttachments.all { item ->
                        val mimeType = (item as? Attachment.Media)?.localMedia?.info?.mimeType
                        mimeType?.isMimeTypeImage() == true || mimeType?.isMimeTypeVideo() == true
                    }
                    if (useInlineMediaProgress) {
                        Timber.tag("YiZheMediaPresentation").d("YZMEDIA_CLICK elapsed_ms=%d", android.os.SystemClock.elapsedRealtime())
                        val preparedFirst = (sendActionState.value as? SendActionState.Sending.ReadyToUpload)?.mediaInfo.takeIf {
                            sendingAttachments.first() ==
                            currentAttachment
                        }
                        val editedTempFileToDelete = editedTempFile
                        editedTempFile = null
                        // Publish the room-local rows from the send click itself. Starting on the IO dispatcher
                        // first leaves a visible gap on real devices before the coroutine gets CPU time.
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder).takeIf { it.isNotEmpty() }
                        val activeMediaAlbumId = mediaAlbumId.takeUnless { sendAsFlashRead }
                        val optimisticIds = sendingAttachments.mapIndexed { index, item ->
                            val localMedia = (item as Attachment.Media).localMedia
                            timelineController.addOptimisticMediaEcho(
                                localMedia = localMedia,
                                caption = caption.takeIf { index == 0 },
                                mediaAlbum = activeMediaAlbumId?.let {
                                    ProtectedMediaAlbum(id = it, index = index, count = sendingAttachments.size)
                                },
                                burnAfterRead = sendAsFlashRead,
                                preparedMedia = preparedFirst.takeIf { index == 0 },
                            )
                        }
                        val itemTasks = arrayOfNulls<PendingMediaSendTask>(sendingAttachments.size)
                        val preparedItems = Array(sendingAttachments.size) { java.util.concurrent.atomic.AtomicReference<MediaUploadInfo?>() }
                        preparedItems[0].set(preparedFirst)
                        fun settled() = selectedIndices.all { it in committedIndices || it in discardedIndices } && inFlightIndices.isEmpty()
                        fun newItemTask(index: Int): PendingMediaSendTask {
                            val nextAttachment = sendingAttachments[index]
                            val optimisticId = optimisticIds[index]
                            val nextMedia = nextAttachment as Attachment.Media
                            return PendingMediaSendTask(
                                scope = CoroutineScope(sessionCoroutineScope.coroutineContext + dispatchers.io),
                                clearUnqueuedFiles = {
                                    preparedItems[index].get()?.allFiles()?.forEach { file ->
                                        file.safeDelete()
                                        check(!file.exists()) { "Temporary media cleanup is pending." }
                                    }
                                },
                            ) {
                                markSending(sendingIndices[index], sendAsFlashRead)
                                performAttachmentUpload(
                                        lifecycleOperationRequest(
                                            lifecycleId = if (sendAsFlashRead) FullAppLifecycleId.LC_21 else FullAppLifecycleId.LC_16,
                                            trigger = if (sendAsFlashRead) "message_commit" else "upload",
                                            operationId = nextMedia.localMedia.lifecycleOperationId,
                                            accountIdHash = accountIdHash,
                                            matrixDeviceIdHash = matrixDeviceIdHash,
                                            roomIdHash = nextMedia.localMedia.lifecycleRoomIdHash,
                                        )
                                    ) {
                                        val uploadInfo = if (index == 0 && preparedFirst != null) {
                                            timelineController.updateOptimisticMediaProgress(optimisticId, PREPARATION_PROGRESS_END)
                                            preparedFirst
                                        } else {
                                            sendActionState.value = SendActionState.Sending.Processing(displayProgress = false)
                                            mediaSender.preProcessMedia(
                                                uri = nextMedia.localMedia.uri,
                                                mimeType = nextMedia.localMedia.info.mimeType,
                                                mediaOptimizationConfig = configFor(nextMedia, MediaOptimizationConfig(!originalPhotos, videoQuality)),
                                                onProgress = { progress ->
                                                    timelineController.updateOptimisticMediaProgress(
                                                        optimisticId,
                                                        progress.coerceIn(0f, 1f) * PREPARATION_PROGRESS_END,
                                                    )
                                                },
                                            ).getOrElse { failure ->
                                                timelineController.resolveOptimisticLocalEcho(optimisticId, failure)
                                                throw failure
                                            }
                                        }
                                        preparedItems[index].set(uploadInfo)
                                        currentCoroutineContext().ensureActive()
                                        val sent = sendPreProcessedMedia(
                                            mediaUploadInfo = uploadInfo,
                                            caption = caption.takeIf { index == 0 },
                                            sendActionState = sendActionState,
                                            dismissAfterSend = false,
                                            inReplyToEventId = inReplyToEventId.takeUnless { sendAsFlashRead },
                                            burnAfterRead = sendAsFlashRead,
                                            mediaAlbum = activeMediaAlbumId?.let {
                                                ProtectedMediaAlbum(id = it, index = index, count = sendingAttachments.size)
                                            },
                                            optimisticMediaEchoId = optimisticId,
                                            submission = submissionContext(sendingIndices[index]),
                                        )
                                        check(sent) { "A protected attachment was not committed." }
                                        currentCoroutineContext().ensureActive()
                                        checkpoint(sendingIndices[index])
                                    }
                                true
                            }
                        }
                        itemTasks.indices.forEach { index -> itemTasks[index] = newItemTask(index) }
                        suspend fun retireCancelledItem(index: Int) {
                            val resource = editResources[sendingIndices[index]]

                                                    resource?.lease?.close()
                                                    resource?.file?.let { file -> file.safeDelete(); check(!file.exists()) }
                                                    resource?.reference?.let { reference ->
                                                        checkNotNull(mediaDraftStore).retireResource(reference, sourceAttachments[sendingIndices[index]] as Attachment.Media, resource.lease?.uri)
                                                    }
                                                    inFlightIndices.remove(sendingIndices[index])
                                                    discardedIndices.add(sendingIndices[index])
                                                    mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                                                        attachments = editedAttachments.toList(), sources = sourceAttachments.toList(),
                                                        selected = selectedIndices.toList(), committed = committedIndices.toList(),
                                                        discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(), inFlight = inFlightIndices.toList(),
                                                        resources = editResources.filterKeys { it !in discardedIndices }
                                                            .mapNotNull { (key, value) -> value.reference?.let { key to it } }.toMap(),
                                                    ))
                                                    if (settled()) mediaDraftStore?.clear(draftOperationId)
                        }
                        optimisticIds.forEachIndexed { index, optimisticId ->
                            val item = sendingAttachments[index] as Attachment.Media
                            val resource = editResources[sendingIndices[index]]
                            timelineController.registerOptimisticMediaActions(
                                optimisticId,
                                retry = {
                                    ongoingSendAttachmentJob.value?.join()
                                    retryMutex.withLock {
                                        runCatchingExceptions {
                                            val previous = checkNotNull(itemTasks[index])
                                            check(!previous.owner.requested && !previous.owner.enqueueAttempted && previous.owner.cleanupFailure == null) {
                                                "The original media send requires reconciliation before retry."
                                            }
                                            val retry = newItemTask(index)
                                            itemTasks[index] = retry
                                            check(retry.send())
                                            if (settled()) mediaDraftStore?.clear(draftOperationId)
                                        }
                                    }
                                },
                                discard = {
                                    sessionCoroutineScope.launch {
                                    ongoingSendAttachmentJob.value?.join()
                                    if (sendingIndices[index] in committedIndices) resource?.close() else resource?.lease?.close()
                                }
                                },
                                cancelPending = {
                                    val cancelId = "media-cancel-" + MessageDigest.getInstance("SHA-256")
                                        .digest(item.localMedia.lifecycleOperationId.encodeToByteArray())
                                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                    runCatchingExceptions {
                                        withContext(NonCancellable) {
                                        // Retain this exact item before stopping it. A failed control request
                                        // leaves a recoverable cancel intent; it cannot keep the local task running.
                                        val originalTask = checkNotNull(itemTasks[index])
                                        originalTask.requestStop()
                                        cancellationRequestedIndices.add(sendingIndices[index])
                                        val retained = runCatchingExceptions { mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                                            attachments = editedAttachments.toList(), sources = sourceAttachments.toList(),
                                            selected = selectedIndices.toList(), committed = committedIndices.toList(),
                                            discarded = discardedIndices.toList(), inFlight = inFlightIndices.toList(),
                                            submissions = submissionFacts.toMap(),
                                            cancellationRequested = cancellationRequestedIndices.toList(),
                                        )) }
                                        val stopped = originalTask.cancel().getOrThrow()
                                        retained.getOrThrow()
                                        appOperationalStateCoordinator.perform(lifecycleOperationRequest(
                                            lifecycleId = FullAppLifecycleId.LC_16,
                                            trigger = "cancel",
                                            operationId = cancelId,
                                            accountIdHash = accountIdHash,
                                            matrixDeviceIdHash = matrixDeviceIdHash,
                                            roomIdHash = item.localMedia.lifecycleRoomIdHash,
                                            eventIdHash = MessageDigest.getInstance("SHA-256")
                                                .digest(item.localMedia.lifecycleOperationId.encodeToByteArray())
                                                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                                        )) {
                                            when (val result = stopped) {
                                                MediaSendCancellationResult.Cancelled -> retireCancelledItem(index)
                                                is MediaSendCancellationResult.Committed -> {
                                                    appOperationalStateCoordinator.perform(lifecycleOperationRequest(
                                                        lifecycleId = FullAppLifecycleId.LC_21, trigger = "user_redact",
                                                        accountIdHash = accountIdHash, matrixDeviceIdHash = matrixDeviceIdHash,
                                                        roomIdHash = item.localMedia.lifecycleRoomIdHash,
                                                        eventIdHash = MessageDigest.getInstance("SHA-256")
                                                            .digest(result.eventId.value.encodeToByteArray())
                                                            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                                                    )) {
                                                        var withdrawn: Result<Unit> = Result.failure(IllegalStateException("No active timeline"))
                                                        timelineController.invokeOnCurrentTimeline {
                                                            withdrawn = redactEvent(
                                                                io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId.Event(result.eventId), null,
                                                            )
                                                        }
                                                        withdrawn.getOrThrow()
                                                    }
                                                    retireCancelledItem(index)
                                                }
                                                MediaSendCancellationResult.Unknown -> error("Media cancellation is awaiting reconciliation.")
                                            }
                                        }
                                        }
                                    }.onFailure { failure -> timelineController.resolveOptimisticLocalEcho(optimisticId, MediaCancellationPendingException(failure)) }
                                }
                            )
                        }
                        mediaDraftStore?.beginTransfer(draftOperationId)
                        ongoingSendAttachmentJob.value = sessionCoroutineScope.launch(dispatchers.io) {
                            var activeIndex = 0
                            try {
                                mediaDraftStore?.save(
                                    draftOperationId,
                                    draftSnapshot.copy(
                                        attachments = editedAttachments.toList(),
                                        selected = selectedIndices.toList(),
                                        sources = sourceAttachments.toList(),
                                        originalPhotos = originalPhotos,
                                        discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(), committed = committedIndices.toList(), inFlight = inFlightIndices.toList(),
                                    )
                                )
                                if (preparedFirst == null) {
                                    preprocessMediaJob?.cancelAndJoin()
                                    preprocessMediaJob = null
                                    mediaSender.cleanUp()
                                }
                                itemTasks.forEachIndexed { index, task ->
                                    activeIndex = index
                                    checkNotNull(task).send()
                                }
                                if (settled()) sendActionState.value = SendActionState.Done
                                if (settled()) mediaDraftStore?.clear(draftOperationId)
                            } catch (failure: CancellationException) {
                                optimisticIds.drop(activeIndex).forEach { timelineController.resolveOptimisticLocalEcho(it, failure) }
                                throw failure
                            } catch (failure: Throwable) {
                                Timber.e(failure, "Inline protected media transfer failed")
                                optimisticIds.drop(activeIndex).forEach { timelineController.resolveOptimisticLocalEcho(it, failure) }
                                if (sendActionState.value !is SendActionState.Failure) {
                                    sendActionState.value = SendActionState.Failure(failure, null)
                                }
                            } finally {
                                itemTasks.filterNotNull().forEach { it.closeUnused() }
                                inFlightIndices.toList().forEach { index ->
                                    if (submissionFacts[index]?.phase == MediaSubmissionPhase.PRE_SDK) {
                                        inFlightIndices.remove(index)
                                        if (index !in failedIndices) failedIndices.add(index)
                                    } else if (submissionFacts[index]?.phase == MediaSubmissionPhase.UNKNOWN && index !in restoredUncertainIndices) {
                                        restoredUncertainIndices.add(index)
                                    }
                                }
                                mediaDraftStore?.finishTransfer(draftOperationId)
                                editedTempFileToDelete?.safeDelete()
                                if (settled()) selectedResources.filter { it.file != null }.forEach { it.close() }
                                mediaSender.cleanUp()
                            }
                        }
                        Timber.tag("YiZheMediaPresentation").d(
                            "YZMEDIA_NAVIGATE elapsed_ms=%d ids=%s",
                            android.os.SystemClock.elapsedRealtime(),
                            optimisticIds.joinToString(",") { it.value },
                        )
                        onDoneListener()
                        return
                    }
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        if (activeFilePreviewLease != null) {
                            val prepared = if (sendAsFlashRead) {
                                activeFilePreviewLease.cancel(LocalAttachmentPreviewCancelReason.USER_CANCELLED)
                                    .map { Unit }
                            } else {
                                activeFilePreviewLease.transferToUpload()
                            }
                            prepared.onFailure {
                                sendActionState.value = SendActionState.Failure(it, null)
                            }.getOrElse { return@launch }
                        }
                        val selectedConfig = if (mediaOptimizationSelectorState.displayMediaSelectorViews == true) {
                            MediaOptimizationConfig(
                                compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                                videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                            )
                        } else {
                            getAutoPreprocessMediaOptimizationConfig(
                                mediaAttachment = mediaAttachment,
                                mediaOptimizationSelectorState = mediaOptimizationSelectorState,
                            ) ?: mediaOptimizationConfigProvider.get()
                        }
                        // If the media optimization selector is displayed, we need to wait for the user to select the options
                        // before we can pre-process the media.
                        if (mediaOptimizationSelectorState.displayMediaSelectorViews == true || preprocessMediaJob == null || activeFilePreviewLease != null) {
                            preprocessMediaJob = preProcessAttachment(
                                attachment = sendingAttachments.first(),
                                mediaOptimizationConfig = configFor(sendingAttachments.first() as Attachment.Media, selectedConfig),
                                displayProgress = true,
                                sendActionState = sendActionState,
                            )
                        }

                        // If the processing was hidden before, make it visible now
                        if (sendActionState.value is SendActionState.Sending.Processing) {
                            sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                        }

                        // Wait until the media is ready to be uploaded
                        preprocessMediaJob?.join()
                        val mediaUploadInfo = (sendActionState.value as? SendActionState.Sending.ReadyToUpload)?.mediaInfo ?: return@launch
                        // Let observers render the prepared state before upload progress begins.
                        yield()

                        // Pre-processing is done, send the attachment
                        // The protected envelope encrypts both plain and formatted captions, so
                        // one-time media can safely retain the title entered in the preview.
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                            .takeIf { it.isNotEmpty() }

                        val editedTempFileToDelete = editedTempFile
                        editedTempFile = null

                        // Keep the preview visible until the protected send really commits. Closing before the
                        // SDK local event exists hides fail-closed errors and leaves the user with no retry surface.
                        mediaDraftStore?.beginTransfer(draftOperationId)
                        val sendJob = sessionCoroutineScope.launch(dispatchers.io) {
                            var roomRevealed = false
                            val revealRoomWhenLocalEchoExists = {
                                if (!roomRevealed) {
                                    roomRevealed = true
                                    sessionCoroutineScope.launch(dispatchers.main) { onDoneListener() }
                                }
                            }
                            try {
                                mediaDraftStore?.save(
                                    draftOperationId,
                                    draftSnapshot.copy(
                                        attachments = editedAttachments.toList(),
                                        selected = selectedIndices.toList(),
                                        sources = sourceAttachments.toList(),
                                        originalPhotos = originalPhotos
                                    )
                                )
                                val activeMediaAlbumId = mediaAlbumId.takeUnless { sendAsFlashRead }
                                performAttachmentUpload(
                                    lifecycleOperationRequest(
                                        lifecycleId = if (sendAsFlashRead) FullAppLifecycleId.LC_21 else FullAppLifecycleId.LC_16,
                                        trigger = if (sendAsFlashRead) "message_commit" else "upload",
                                        operationId = (sendingAttachments.first() as Attachment.Media).localMedia.lifecycleOperationId,
                                        accountIdHash = accountIdHash,
                                        matrixDeviceIdHash = matrixDeviceIdHash,
                                        roomIdHash = (sendingAttachments.first() as Attachment.Media).localMedia.lifecycleRoomIdHash,
                                    )
                                ) operation@{
                                    markSending(sendingIndices.first(), sendAsFlashRead)
                                    val firstSent = sendPreProcessedMedia(
                                        mediaUploadInfo = mediaUploadInfo,
                                        caption = caption,
                                        sendActionState = sendActionState,
                                        dismissAfterSend = false,
                                        inReplyToEventId = inReplyToEventId.takeUnless { sendAsFlashRead },
                                        burnAfterRead = sendAsFlashRead,
                                        mediaAlbum = activeMediaAlbumId?.let {
                                            ProtectedMediaAlbum(id = it, index = 0, count = sendingAttachments.size)
                                        },
                                        onUploadStarted = revealRoomWhenLocalEchoExists,
                                        submission = submissionContext(sendingIndices.first()),
                                    )
                                    check(firstSent) { "The protected attachment was not committed." }
                                    checkpoint(sendingIndices.first())
                                }
                                sendingAttachments.drop(1).forEachIndexed { offset, nextAttachment ->
                                    val nextMedia = nextAttachment as Attachment.Media
                                    performAttachmentUpload(
                                        lifecycleOperationRequest(
                                            lifecycleId = if (sendAsFlashRead) FullAppLifecycleId.LC_21 else FullAppLifecycleId.LC_16,
                                            trigger = if (sendAsFlashRead) "message_commit" else "upload",
                                            operationId = nextMedia.localMedia.lifecycleOperationId,
                                            accountIdHash = accountIdHash,
                                            matrixDeviceIdHash = matrixDeviceIdHash,
                                            roomIdHash = nextMedia.localMedia.lifecycleRoomIdHash,
                                        )
                                    ) {
                                        sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                                        val nextUploadInfo = mediaSender.preProcessMedia(
                                            uri = nextMedia.localMedia.uri,
                                            mimeType = nextMedia.localMedia.info.mimeType,
                                            mediaOptimizationConfig = configFor(nextMedia, selectedConfig),
                                        ).getOrElse { error ->
                                            sendActionState.value = SendActionState.Failure(error, null)
                                            throw error
                                        }
                                        markSending(sendingIndices[offset + 1], sendAsFlashRead)
                                        val sent = sendPreProcessedMedia(
                                            mediaUploadInfo = nextUploadInfo,
                                            caption = null,
                                            sendActionState = sendActionState,
                                            dismissAfterSend = false,
                                            inReplyToEventId = inReplyToEventId.takeUnless { sendAsFlashRead },
                                            burnAfterRead = sendAsFlashRead,
                                            mediaAlbum = activeMediaAlbumId?.let {
                                                ProtectedMediaAlbum(id = it, index = offset + 1, count = sendingAttachments.size)
                                            },
                                            submission = submissionContext(sendingIndices[offset + 1]),
                                        )
                                        check(sent) { "A protected attachment was not committed." }
                                        checkpoint(sendingIndices[offset + 1])
                                    }
                                }
                                sendActionState.value = SendActionState.Done
                                mediaDraftStore?.clear(draftOperationId)
                                if (!sendAsFlashRead) {
                                    activeFilePreviewLease?.completeUpload()?.getOrThrow()
                                }
                                if (!roomRevealed) {
                                    withContext(dispatchers.main) { onDoneListener() }
                                }
                            } catch (failure: CancellationException) {
                                activeFilePreviewLease?.cancel(LocalAttachmentPreviewCancelReason.UPLOAD_FAILED)
                                throw failure
                            } catch (failure: Throwable) {
                                activeFilePreviewLease?.cancel(LocalAttachmentPreviewCancelReason.UPLOAD_FAILED)
                                // Keep the preview open so the existing failure dialog exposes a real retry path.
                                Timber.e(failure, "Protected attachment lifecycle failed")
                                if (sendActionState.value !is SendActionState.Failure) {
                                    sendActionState.value = SendActionState.Failure(failure, mediaUploadInfo)
                                }
                            } finally {
                                inFlightIndices.toList().forEach { index ->
                                    if (submissionFacts[index]?.phase == MediaSubmissionPhase.PRE_SDK) {
                                        inFlightIndices.remove(index)
                                        if (index !in failedIndices) failedIndices.add(index)
                                    } else if (submissionFacts[index]?.phase == MediaSubmissionPhase.UNKNOWN && index !in restoredUncertainIndices) {
                                        restoredUncertainIndices.add(index)
                                    }
                                }
                                mediaDraftStore?.finishTransfer(draftOperationId)
                                editedTempFileToDelete?.safeDelete()
                                if (sendActionState.value == SendActionState.Done) selectedResources.filter { it.file != null }.forEach { it.close() }
                                mediaSender.cleanUp()
                            }
                        }
                        ongoingSendAttachmentJob.value = sendJob
                    }
                }
                AttachmentsPreviewEvent.DismissPreservingDraft -> {
                    coroutineScope.launch {
                        try {
                            mediaDraftStore?.save(draftOperationId, draftSnapshot.copy(
                                attachments = editedAttachments.toList(),
                                sources = sourceAttachments.toList(),
                                selected = selectedIndices.toList(),
                                committed = committedIndices.toList(),
            discarded = discardedIndices.toList(), cancellationRequested = cancellationRequestedIndices.toList(),
                                inFlight = inFlightIndices.toList(),
                submissions = submissionFacts.toMap(),
                                pending = imageEditorState?.edits,
                            ))
                            preprocessMediaJob?.cancel()
                            preprocessMediaJob = null
                            filePreviewController?.close()
                            onDoneListener()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            draftError = true
                        }
                    }
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> {
                    filePreviewController?.close()
                    displayFileTooLargeError = false
                    displayImageEditError = false
                    isApplyingImageEdits = false

                    // Cancel media preprocessing and sending
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    // If we couldn't send the pre-processed media, remove it
                    mediaSender.cleanUp()
                    ongoingSendAttachmentJob.value?.cancel()

                    // Dismiss the screen
                    handedOff.set(true)
                    sessionCoroutineScope.launch {
                        try {
                            // Stop queued autosaves before clearing, and clear before allowing a new picker entry.
                            draftPersistenceJob?.cancelAndJoin()
                            mediaDraftStore?.clear(draftOperationId)
                            editResources.values.forEach { it.close() }
                            dismiss(sendActionState, editedTempFile)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            draftError = true
                        }
                    }
                }
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    // Cancel media sending
                    ongoingSendAttachmentJob.value?.let {
                        it.cancel()
                        ongoingSendAttachmentJob.value = null
                    }

                    val mediaUploadInfo = sendActionState.value.mediaUploadInfo()
                    sendActionState.value = if (mediaUploadInfo != null) {
                        SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
                    } else {
                        SendActionState.Idle
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor -> {
                    val requestedMedia = (sourceAttachments[focusedIndex] as Attachment.Media).localMedia
                    val resolvedCanEditImage = requestedMedia.info.canEditImage() ||
                        (requestedMedia.uri == originalLocalMedia.uri && canEditImage)
                    if (resolvedCanEditImage) {
                        preprocessMediaJob?.cancel()
                        preprocessMediaJob = null
                        resetPreparedMedia(sendActionState)
                        undo.clear()
                        redo.clear()
                        imageEditorState = AttachmentImageEditorState(
                            localMedia = requestedMedia,
                            edits = imageEdits[focusedIndex] ?: AttachmentImageEdits(),
                            previewDebug = false,
                        )
                    }
                }
                AttachmentsPreviewEvent.CloseImageEditor -> {
                    imageEditorState = null
                }
                is AttachmentsPreviewEvent.UpdateImageCropRect -> {
                    val pendingState = imageEditorState ?: return
                    updateImage(pendingState.edits.copy(cropRect = event.cropRect))
                }
                AttachmentsPreviewEvent.RotateImageToTheLeft -> {
                    val pendingState = imageEditorState ?: return
                    updateImage(pendingState.edits.rotateAntiClockwise())
                }
                AttachmentsPreviewEvent.FlipImageHorizontally -> {
                    val pendingState = imageEditorState ?: return
                    updateImage(pendingState.edits.flipHorizontally())
                }
                AttachmentsPreviewEvent.FlipImageVertically -> {
                    val pendingState = imageEditorState ?: return
                    updateImage(pendingState.edits.flipVertically())
                }
                AttachmentsPreviewEvent.ResetImageEdits -> {
                    updateImage(AttachmentImageEdits())
                }
                AttachmentsPreviewEvent.ApplyImageEdits -> {
                    val pendingState = imageEditorState ?: return
                    if (!pendingState.edits.hasChanges) {
                        editedTempFile?.safeDelete()
                        editedTempFile = null
                        appliedImageEdits = pendingState.edits
                        editedAttachments[focusedIndex] = originalAttachment
                            imageEdits[focusedIndex] = appliedImageEdits
                        editResources.remove(focusedIndex)?.close()
                        imageEditorState = null
                        resetPreparedMedia(sendActionState)
                        return
                    }
                    isApplyingImageEdits = true
                    displayImageEditError = false
                    coroutineScope.launch {
                        val result = withContext(dispatchers.io) {
                            attachmentImageEditor.exportEdits(
                                localMedia = originalLocalMedia,
                                edits = pendingState.edits,
                            )
                        }
                        result.fold(
                            onSuccess = { editedMedia ->
                                editedTempFile?.safeDelete()
                                editedTempFile = editedMedia.file
                                editResources.put(focusedIndex, editedMedia)?.close()
                                appliedImageEdits = pendingState.edits
                                imageEdits[focusedIndex] = appliedImageEdits
                                editedAttachments[focusedIndex] = originalAttachment.copy(localMedia = editedMedia.localMedia)
                                            imageEditorState = null
                                resetPreparedMedia(sendActionState)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to apply image edits")
                                displayImageEditError = true
                            }
                        )
                        isApplyingImageEdits = false
                    }
                }
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    displayImageEditError = false
                }
            }
        }

        val displayedAttachment = filePreviewLease?.let { lease ->
            (currentAttachment as Attachment.Media).copy(localMedia = lease.previewMedia)
        } ?: currentAttachment
        return AttachmentsPreviewState(
            attachment = displayedAttachment,
            filePreviewStates = sourceAttachments.mapIndexedNotNull { index, item ->
                val source = (item as Attachment.Media).localMedia
                if (localAttachmentPreviewLeaseService.requiresLease(source)) {
                    index to if (index == focusedIndex) {
                        filePreviewController?.state ?: AttachmentFilePreviewState.Preparing
                    } else AttachmentFilePreviewState.Preparing
                } else null
            }.toMap(),
            attachmentCount = selectedIndices.size,
            imageEditorState = imageEditorState,
            canEditImage = canEditImage,
            isApplyingImageEdits = isApplyingImageEdits,
            displayImageEditError = displayImageEditError,
            sendActionState = sendActionState.value,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            isBurnAfterRead = burnAfterRead,
            displaySendProgressInline = timelineMode == Timeline.Mode.Live &&
                editedAttachments.all { item ->
                    val mimeType = (item as? Attachment.Media)?.localMedia?.info?.mimeType
                    mimeType?.isMimeTypeImage() == true || mimeType?.isMimeTypeVideo() == true
                },
            eventSink = ::handleEvent,
            attachments = editedAttachments.mapIndexed { index, item -> if (index == focusedIndex) displayedAttachment else item },
            focusedIndex = focusedIndex,
            selectedIndices = selectedIndices.toList(),
            originalPhotos = originalPhotos,
            videoQuality = videoQuality,
            videoEditorOpen = videoEditorOpen,
            draftReady = draftReady,
            draftError = draftError,
            uncertainIndices = restoredUncertainIndices.toList(),
            failedIndices = failedIndices.toList(),
            confirmationStillPending = confirmationStillPending,
            blockedIndices = (committedIndices + restoredUncertainIndices).distinct(),
            acceptedIndices = submissionFacts.filterValues { it.phase == MediaSubmissionPhase.ACCEPTED }.keys.filter {
                it !in cancellationRequestedIndices && it !in discardedIndices
            },
            cancellationRequestedIndices = cancellationRequestedIndices.toList(),
            discardedIndices = discardedIndices.toList(),
            recoveryDetailsVisible = recoveryDetailsVisible,
            checkingRecovery = checkingRecovery,
            recoveryChecks = recoveryChecks,
            sourceAttachments = sourceAttachments.toList(),
        )
    }

    private suspend fun getAutoPreprocessMediaOptimizationConfig(
        mediaAttachment: Attachment.Media,
        mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    ): MediaOptimizationConfig? {
        return if (mediaAttachment.sendAsFile) {
            // If we're sending the media as a file, we can skip image compression and we should select the highest video compression preset that still fits
            // the upload limit (if the estimations are available)
            val videoCompressionPreset = videoCompressionPresetSelector.selectBestVideoPreset(
                expectedVideoPreset = VideoCompressionPreset.HIGH,
                videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations,
            ).dataOrNull() ?: VideoCompressionPreset.HIGH

            MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = videoCompressionPreset,
            )
        } else {
            // Otherwise, we just rely on the user preferences for media optimization
            mediaOptimizationConfigProvider.get()
        }
    }

    private fun CoroutineScope.preProcessAttachment(
        attachment: Attachment,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) = launch(dispatchers.io) {
        when (attachment) {
            is Attachment.Media -> {
                preProcessMedia(
                    mediaAttachment = attachment,
                    mediaOptimizationConfig = mediaOptimizationConfig,
                    displayProgress = displayProgress,
                    sendActionState = sendActionState,
                )
            }
        }
    }

    private suspend fun preProcessMedia(
        mediaAttachment: Attachment.Media,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) {
        sendActionState.value = SendActionState.Sending.Processing(displayProgress = displayProgress)
        mediaSender.preProcessMedia(
            uri = mediaAttachment.localMedia.uri,
            mimeType = mediaAttachment.localMedia.info.mimeType,
            mediaOptimizationConfig = mediaOptimizationConfig,
        ).also { kotlinx.coroutines.currentCoroutineContext().ensureActive() }.fold(
            onSuccess = { mediaUploadInfo ->
                Timber.d("Media ${mediaUploadInfo.file.path.orEmpty().hash()} finished processing, it's now ready to upload")
                sendActionState.value = SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
            },
            onFailure = {
                Timber.e(it, "Failed to pre-process media")
                if (it is CancellationException) {
                    throw it
                } else {
                    sendActionState.value = SendActionState.Failure(it, null)
                }
            }
        )
    }

    private fun dismiss(
        sendActionState: MutableState<SendActionState>,
        editedTempFile: File?,
    ) {
        // Delete the temporary file
        when (attachment) {
            is Attachment.Media -> {
                temporaryUriDeleter.delete(attachment.localMedia.uri)
                sendActionState.value.mediaUploadInfo()?.let { data ->
                    cleanUp(data)
                }
            }
        }
        editedTempFile?.safeDelete()
        // Reset the sendActionState to ensure that dialog is closed before the screen
        sendActionState.value = SendActionState.Done
        onDoneListener()
    }

    private fun cleanUp(
        mediaUploadInfo: MediaUploadInfo,
    ) {
        mediaUploadInfo.allFiles().forEach { file ->
            file.safeDelete()
        }
    }

    private fun resetPreparedMedia(sendActionState: MutableState<SendActionState>) {
        sendActionState.value.mediaUploadInfo()?.let(::cleanUp)
        mediaSender.cleanUp()
        sendActionState.value = SendActionState.Idle
    }

    private suspend fun performAttachmentUpload(
        request: LifecycleOperationRequest,
        block: suspend (String) -> Unit,
    ) {
        var actionApplied = false
        try {
            appOperationalStateCoordinator.perform(request) { operationId ->
                block(operationId)
                actionApplied = true
            }
        } catch (failure: Exception) {
            if (!actionApplied || failure is CancellationException) throw failure
            // The item was accepted; only the existing coordinator's receipt transport failed.
            // Preserve its lifecycle retry state without turning it into a second Matrix send.
            Timber.w(failure, "Upload receipt confirmation pending after accepted media send")
        }
    }

    private suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        sendActionState: MutableState<SendActionState>,
        dismissAfterSend: Boolean,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum? = null,
        onUploadStarted: () -> Unit = {},
        optimisticMediaEchoId: UniqueId? = null,
        submission: MediaSubmissionContext? = null,
    ): Boolean = runCatchingExceptions {
        sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo)
        optimisticMediaEchoId?.let {
            timelineController.updateOptimisticMediaInfo(it, mediaUploadInfo)
            timelineController.updateOptimisticMediaProgress(it, PREPARATION_PROGRESS_END)
        }
        val needsCaption = caption != null || mediaAlbum != null ||
            (mediaUploadInfo is MediaUploadInfo.AnyFile && !burnAfterRead)
        val uploadContext = requireNotNull(currentLifecycleOperationContext())
        var sdkAccepted = false
        val send: suspend () -> Unit = {
        withContext(submission ?: kotlin.coroutines.EmptyCoroutineContext) { mediaSender.sendPreProcessedMedia(
            mediaUploadInfo = mediaUploadInfo,
            caption = caption,
            formattedCaption = null,
            inReplyToEventId = inReplyToEventId,
            burnAfterRead = burnAfterRead,
            mediaAlbum = mediaAlbum,
            onProgress = { progress ->
                sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo, progress)
                optimisticMediaEchoId?.let { optimisticId ->
                    timelineController.updateOptimisticMediaProgress(
                        optimisticId,
                        ENCRYPTION_PROGRESS_END + progress.coerceIn(0f, 1f) * UPLOAD_PROGRESS_WEIGHT,
                    )
                }
            },
            onUploadIdentity = { identity ->
                optimisticMediaEchoId?.let { timelineController.bindOptimisticMediaTransport(it, identity) }
            },
            onUploadStarted = {
                optimisticMediaEchoId?.let { timelineController.updateOptimisticMediaProgress(it, ENCRYPTION_PROGRESS_END) }
                onUploadStarted()
            },
        ).getOrThrow() }
            sdkAccepted = true
        }
        if (!needsCaption) {
            send()
        } else {
            val upload = appOperationalStateCoordinator.operation(uploadContext.operationId)
            require(upload.operationId == uploadContext.operationId && upload.lifecycleId == uploadContext.lifecycleId)
            require(upload.subjects.accountIdHash == accountIdHash && upload.subjects.matrixDeviceIdHash == matrixDeviceIdHash)
            val roomHash = requireNotNull(upload.subjects.roomIdHash)
            val captionId = "media-caption-" + MessageDigest.getInstance("SHA-256")
                .digest("${upload.operationId}:caption".encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            try {
                appOperationalStateCoordinator.performForComponent(
                    lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_15,
                        trigger = "media_caption",
                        operationId = captionId,
                        accountIdHash = accountIdHash,
                        matrixDeviceIdHash = matrixDeviceIdHash,
                        roomIdHash = roomHash,
                    ),
                    component = LifecycleComponent.ANDROID_FRONTEND,
                ) { operationId ->
                    check(operationId == captionId)
                    withContext(uploadContext + MediaCaptionOperationContext(
                        upload.operationId,
                        captionId,
                        accountIdHash,
                        matrixDeviceIdHash,
                        roomHash,
                    )) { send() }
                }
            } catch (failure: Exception) {
                if (!sdkAccepted || failure is CancellationException) throw failure
                // The existing coordinator retains failed receipt transport for retry. The Matrix
                // send already succeeded: never offer to resend this item as a failed upload.
                Timber.w(failure, "Caption frontend receipt confirmation pending after accepted media send")
            }
        }
    }.fold(
        onSuccess = {
            optimisticMediaEchoId?.let { timelineController.resolveOptimisticLocalEcho(it, null) }
            cleanUp(mediaUploadInfo)
            // Reset the sendActionState to ensure that dialog is closed before the screen
            sendActionState.value = SendActionState.Done

            if (dismissAfterSend) {
                onDoneListener()
            }
            true
        },
        onFailure = { error ->
            Timber.e(error, "Failed to send attachment")
            optimisticMediaEchoId?.let { timelineController.resolveOptimisticLocalEcho(it, error) }
            if (error is CancellationException) {
                throw error
            } else {
                sendActionState.value = SendActionState.Failure(error, mediaUploadInfo)
            }
            false
        }
    )
}

private const val PREPARATION_PROGRESS_END = 0.35f
private const val ENCRYPTION_PROGRESS_END = 0.45f
private const val UPLOAD_PROGRESS_WEIGHT = 1f - ENCRYPTION_PROGRESS_END
