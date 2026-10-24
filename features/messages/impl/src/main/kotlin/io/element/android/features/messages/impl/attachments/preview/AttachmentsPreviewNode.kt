/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ForcedDarkElementTheme
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediapickers.api.MediaPickItemResult
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

@ContributesNode(RoomScope::class)
@AssistedInject
class AttachmentsPreviewNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: AttachmentsPreviewPresenter.Factory,
    private val localMediaRenderer: LocalMediaRenderer,
    private val sessionId: SessionId,
    private val matrixClient: MatrixClient,
    private val enterpriseService: EnterpriseService,
    private val pickerProvider: PickerProvider,
    private val localMediaFactory: LocalMediaFactory,
    private val coordinator: AppOperationalStateCoordinator,
) : Node(buildContext, plugins = plugins) {
    data class Inputs(
        val attachments: List<Attachment>,
        val timelineMode: Timeline.Mode,
        val inReplyToEventId: EventId?,
        val burnAfterRead: Boolean,
    ) : NodeInputs

    interface Callback : Plugin {
        fun onDone()
    }

    private val snackbarDispatcher = SnackbarDispatcher()
    private val inputs: Inputs = inputs()
    private val callback: Callback = callback()

    private val onDoneListener = OnDoneListener {
        callback.onDone()
    }

    private val presenter = presenterFactory.create(
        attachments = inputs.attachments,
        timelineMode = inputs.timelineMode,
        onDoneListener = onDoneListener,
        inReplyToEventId = inputs.inReplyToEventId,
        burnAfterRead = inputs.burnAfterRead,
        accountIdHash = sessionId.value.sha256Hex(),
        matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
    )

    @Composable
    override fun View(modifier: Modifier) = CompositionLocalProvider(LocalSnackbarDispatcher provides snackbarDispatcher) {
        val colors by remember {
            enterpriseService.semanticColorsFlow(sessionId = sessionId)
        }.collectAsState(SemanticColorsLightDark.default)
        ForcedDarkElementTheme(
            colors = colors,
        ) {
            val state = presenter.present()
            val galleryScope = rememberCoroutineScope()
            val roomHash = requireNotNull((state.sourceAttachments.first() as Attachment.Media).localMedia.lifecycleRoomIdHash)
            fun reportGalleryFailure() {
                snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.CommonStrings.common_error))
            }
            val galleryPicker = pickerProvider.registerOperationGalleryPicker(
                state.selectedIndices.map { index ->
                    (state.sourceAttachments[index] as Attachment.Media).let {
                        PickedMedia(
                            uri = it.localMedia.uri,
                            mimeType = it.localMedia.info.mimeType,
                            operationId = it.localMedia.lifecycleOperationId,
                            originalPhotos = state.originalPhotos
                        )
                    }
                }
            ) { result ->
                galleryScope.launch {
                    try {
                        if (result.items.isNotEmpty() && result.items.all {
                            it is MediaPickItemResult.Failed &&
                                it.reason == io.element.android.libraries.mediapickers.api.MediaPickFailureReason.CANCELLED
                        }) {
                            // Closing the gallery cancels media selection; the room text draft remains independent.
                            state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
                            return@launch
                        }
                        if (!isAuthoritativeGallerySelection(
                                coordinator,
                                result.operation,
                                roomHash,
                                sessionId.value.sha256Hex(),
                                matrixClient.deviceId.value.sha256Hex()
                            )) {
                            reportGalleryFailure()
                            return@launch
                        }
                        val items = result.items.filterIsInstance<MediaPickItemResult.Ready>().map { it.media }
                        if (result.items.any {
                            it is MediaPickItemResult.Failed &&
                                it.reason != io.element.android.libraries.mediapickers.api.MediaPickFailureReason.CANCELLED
                        }) {
                            reportGalleryFailure()
                        }
                        if (items.isNotEmpty()) {
                            state.eventSink(AttachmentsPreviewEvent.UpdateGallerySelection(items.map { item ->
                                (state.sourceAttachments.firstOrNull { (it as Attachment.Media).localMedia.uri == item.uri } as? Attachment.Media)
                                    ?: Attachment.Media(
                                        localMediaFactory.createFromUri(item.uri, item.mimeType, null, null)
                                            .copy(lifecycleRoomIdHash = roomHash, lifecycleOperationId = requireNotNull(item.operationId)),
                                        fromGallery = true,
                                    )
                            }.mapIndexed { index, attachment ->
                                (attachment as Attachment.Media).copy(
                                    initiallySelected = items[index].initiallySelected,
                                    focused = items[index].focused,
                                    openEditor = items[index].openEditor,
                                )
                            }, items.first().originalPhotos, items.first().sendImmediately))
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        reportGalleryFailure()
                    } finally {
                        result.operation?.operationId?.let(pickerProvider::completeOperation)
                    }
                }
            }
            var initialEditorHandled by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.draftReady) {
                if (state.draftReady && !state.draftError && !initialEditorHandled) {
                    initialEditorHandled = true
                    if ((state.attachment as Attachment.Media).openEditor) {
                        state.eventSink(if ((state.attachment as Attachment.Media).localMedia.info.mimeType?.startsWith("video/") == true) {
                            AttachmentsPreviewEvent.OpenVideoEditor
                        } else {
                            AttachmentsPreviewEvent.OpenImageEditor
                        })
                    }
                }
            }
            var automaticSendHandled by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.draftReady) {
                if (state.draftReady && !state.draftError && !automaticSendHandled &&
                    (inputs.attachments.firstOrNull() as? Attachment.Media)?.sendImmediately == true) {
                    automaticSendHandled = true
                    state.eventSink(AttachmentsPreviewEvent.SendAttachment)
                }
            }
            AttachmentsPreviewView(
                state = state,
                localMediaRenderer = localMediaRenderer,
                officePreviewAccountId = sessionId.value,
                officePreviewDeviceId = matrixClient.deviceId.value,
                modifier = modifier,
                onReturnToGallery = {
                    galleryScope.launch {
                        runCatchingExceptions {
                            val startedAt = System.currentTimeMillis()
                            val authority = coordinator.start(lifecycleOperationRequest(
                                lifecycleId = FullAppLifecycleId.LC_14,
                                trigger = "draft_change",
                                roomIdHash = roomHash,
                                accountIdHash = sessionId.value.sha256Hex(),
                                matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                                deadlineEpochMillis = startedAt + 10 * 60_000L,
                            ))
                            galleryPicker.launch(MediaPickOperation(
                                operationId = authority.operationId,
                                source = MediaPickSource.GALLERY,
                                roomIdHash = requireNotNull(authority.subjects.roomIdHash),
                                createdAtEpochMillis = authority.createdAtEpochMillis,
                                deadlineEpochMillis = authority.deadlineEpochMillis,
                            ))
                        }.onFailure { reportGalleryFailure() }
                    }
                },
            )
        }
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
