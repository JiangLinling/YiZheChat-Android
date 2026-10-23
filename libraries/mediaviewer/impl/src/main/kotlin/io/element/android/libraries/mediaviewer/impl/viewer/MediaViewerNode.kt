/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ForcedDarkElementTheme
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.viewfolder.api.TextFileViewer
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.impl.datasource.FocusedTimelineMediaGalleryDataSourceFactory
import io.element.android.libraries.mediaviewer.impl.datasource.TimelineMediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.local.isOfficeDocument
import io.element.android.libraries.mediaviewer.impl.local.office.OfficePreviewService
import io.element.android.libraries.mediaviewer.impl.model.hasEvent
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoPlaybackOwnerFactory
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.libraries.mediaviewer.impl.local.ProtectedMediaSurface
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import java.security.MessageDigest

@ContributesNode(RoomScope::class)
@AssistedInject
class MediaViewerNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: MediaViewerPresenter.Factory,
    timelineMediaGalleryDataSource: TimelineMediaGalleryDataSource,
    focusedTimelineMediaGalleryDataSourceFactory: FocusedTimelineMediaGalleryDataSourceFactory,
    mediaLoader: MatrixMediaLoader,
    localMediaFactory: LocalMediaFactory,
    segmentedVideoPlaybackOwnerFactory: SegmentedVideoPlaybackOwnerFactory,
    coroutineDispatchers: CoroutineDispatchers,
    systemClock: SystemClock,
    pagerKeysHandler: PagerKeysHandler,
    private val textFileViewer: TextFileViewer,
    private val audioFocus: AudioFocus,
    private val sessionId: SessionId,
    private val matrixClient: MatrixClient,
    private val room: io.element.android.libraries.matrix.api.room.JoinedRoom,
    private val enterpriseService: EnterpriseService,
    private val officePreviewService: OfficePreviewService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val previewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
) : Node(buildContext, plugins = plugins),
    MediaViewerNavigator {
    private val callback: MediaViewerEntryPoint.Callback = callback()
    private val inputs = inputs<MediaViewerEntryPoint.Params>()

    override fun onViewInTimelineClick(eventId: EventId) {
        callback.viewInTimeline(eventId)
    }

    override fun onForwardClick(eventId: EventId, fromPinnedEvents: Boolean) {
        callback.forwardEvent(eventId, fromPinnedEvents)
    }

    override fun onItemDeleted() {
        callback.onDone()
    }

    private val mediaGallerySource = if (inputs.mode == MediaViewerEntryPoint.MediaViewerMode.SingleMedia) {
        SingleMediaGalleryDataSource.createFrom(inputs)
    } else {
        val eventId = inputs.eventId
        if (eventId == null) {
            // Should not happen
            timelineMediaGalleryDataSource
        } else {
            // Can we use a specific timeline?
            val timelineMode = inputs.mode.getTimelineMode()
            when (timelineMode) {
                null -> timelineMediaGalleryDataSource
                Timeline.Mode.Live,
                is Timeline.Mode.FocusedOnEvent,
                is Timeline.Mode.Thread -> {
                    // Does timelineMediaGalleryDataSource knows the eventId?
                    val lastData = timelineMediaGalleryDataSource.getLastData().dataOrNull()
                    val isEventKnown = lastData?.hasEvent(eventId) == true
                    if (isEventKnown) {
                        timelineMediaGalleryDataSource
                    } else {
                        focusedTimelineMediaGalleryDataSourceFactory.createFor(
                            eventId = eventId,
                            mediaItem = inputs.toMediaItem(),
                            onlyPinnedEvents = false,
                        )
                    }
                }
                Timeline.Mode.PinnedEvents -> {
                    focusedTimelineMediaGalleryDataSourceFactory.createFor(
                        eventId = eventId,
                        mediaItem = inputs.toMediaItem(),
                        onlyPinnedEvents = true,
                    )
                }
                Timeline.Mode.Media -> timelineMediaGalleryDataSource
            }
        }
    }

    private val presenter = presenterFactory.create(
        inputs = inputs,
        navigator = this,
        dataSource = MediaViewerDataSource(
            mode = inputs.mode,
            coroutineScope = lifecycleScope,
            downloadScope = matrixClient.sessionCoroutineScope,
            downloadDispatcher = coroutineDispatchers.io,
            dispatcher = coroutineDispatchers.computation,
            galleryDataSource = mediaGallerySource,
            mediaLoader = mediaLoader,
            localMediaFactory = localMediaFactory,
            segmentedVideoPlaybackOwnerFactory = segmentedVideoPlaybackOwnerFactory,
            systemClock = systemClock,
            pagerKeysHandler = pagerKeysHandler,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            accountIdHash = sessionId.value.sha256Hex(),
            matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
            roomId = room.roomId.value,
            allowReusableOriginalCache = !inputs.isOneTimeView,
        )
    )

    @Composable
    override fun View(modifier: Modifier) {
        val colors by remember {
            enterpriseService.semanticColorsFlow(sessionId = sessionId)
        }.collectAsState(SemanticColorsLightDark.default)
        val state = presenter.present()
        val isOfficeDocument = (state.listData.getOrNull(state.currentIndex) as? MediaViewerPageData.MediaViewerData)
            ?.mediaInfo
            .isOfficeDocument()
        // The overlay posts to its own queue; dismissals must clear that same queue.
        CompositionLocalProvider(LocalSnackbarDispatcher provides presenter.snackbarDispatcher) {
            ForcedDarkElementTheme(
                colors = colors,
                lightStatusBar = isOfficeDocument,
            ) {
                // Keep the loader owner stable while its source changes from
                // absent to downloaded. Device lock still disposes the whole viewer.
                ProtectedMediaSurface(previewAuthorizationGate, secureMediaLeaseManager, localMedia = null) {
                MediaViewerView(
                    state = state,
                    textFileViewer = textFileViewer,
                    modifier = modifier,
                    audioFocus = audioFocus,
                    onBackClick = callback::onDone,
                    isOneTimeView = inputs.isOneTimeView,
                    // Only one-time media ends its viewing session at EOF. Ordinary media must
                    // remain open for replay/seek instead of navigating back after every clip.
                    onPlaybackEnded = {
                        callback.onVideoPlaybackEnded(inputs.isOneTimeView)
                    },
                    officePreviewService = officePreviewService,
                    officePreviewAccountId = sessionId.value,
                    officePreviewDeviceId = matrixClient.deviceId.value,
                    protectedSurface = { media, content ->
                        ProtectedMediaSurface(previewAuthorizationGate, secureMediaLeaseManager, media, content)
                    },
                )
                }
            }
        }
    }
}

internal fun MediaViewerEntryPoint.Callback.onVideoPlaybackEnded(isOneTimeView: Boolean) {
    if (isOneTimeView) onPlaybackEnded()
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun MediaViewerEntryPoint.MediaViewerMode.getTimelineMode(): Timeline.Mode? {
    return when (this) {
        is MediaViewerEntryPoint.MediaViewerMode.TimelineImagesAndVideos -> timelineMode
        is MediaViewerEntryPoint.MediaViewerMode.TimelineFilesAndAudios -> timelineMode
        else -> null
    }
}
