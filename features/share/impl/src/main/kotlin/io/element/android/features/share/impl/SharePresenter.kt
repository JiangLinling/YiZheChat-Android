/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.share.api.OnSharedData
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.ShareResult
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

@AssistedInject
class SharePresenter(
    @Assisted private val shareIntentData: ShareIntentData,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val matrixClient: MatrixClient,
    private val mediaSenderRoomFactory: MediaSenderRoomFactory,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val onSharedData: OnSharedData,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : Presenter<ShareState> {
    @AssistedFactory
    fun interface Factory {
        fun create(shareIntentData: ShareIntentData): SharePresenter
    }

    private val shareActionState: MutableState<AsyncAction<ShareResult>> = mutableStateOf(AsyncAction.Uninitialized)

    fun onRoomSelected(roomIds: List<RoomId>) {
        sessionCoroutineScope.share(shareIntentData, roomIds)
    }

    @Composable
    override fun present(): ShareState {
        fun handleEvent(event: ShareEvents) {
            when (event) {
                ShareEvents.ClearError -> shareActionState.value = AsyncAction.Uninitialized
            }
        }

        return ShareState(
            shareAction = shareActionState.value,
            eventSink = ::handleEvent,
        )
    }

    private data class AcquiredRoom(
        val room: JoinedRoom,
        val ownedByShareFlow: Boolean,
    )

    private suspend fun getJoinedRoom(roomId: RoomId): AcquiredRoom? {
        val activeRoom = activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId)
        return if (activeRoom != null) {
            AcquiredRoom(activeRoom, ownedByShareFlow = false)
        } else {
            matrixClient.getJoinedRoom(roomId)?.let { AcquiredRoom(it, ownedByShareFlow = true) }
        }
    }

    private fun CoroutineScope.share(
        shareIntentData: ShareIntentData,
        roomIds: List<RoomId>,
    ) = launch {
        var offeredRoomHandoff: JoinedRoom? = null
        var shareFailure: Throwable? = null
        suspend {
            val result = when (shareIntentData) {
                is ShareIntentData.PlainText -> {
                    roomIds
                        .map { roomId ->
                            val room = getJoinedRoom(roomId)?.room ?: return@map false
                            runCatching {
                                appOperationalStateCoordinator.perform(
                                    lifecycleOperationRequest(
                                        lifecycleId = FullAppLifecycleId.LC_13,
                                        trigger = "send",
                                        accountIdHash = matrixClient.sessionId.value.sha256Hex(),
                                        matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                                        roomIdHash = roomId.value.sha256Hex(),
                                    )
                                ) {
                                    room.liveTimeline.sendMessage(
                                        body = shareIntentData.content,
                                        htmlBody = null,
                                        intentionalMentions = emptyList(),
                                    ).getOrThrow()
                                }
                            }.also { result ->
                                result.exceptionOrNull()?.let { failure ->
                                    if (failure is CancellationException) throw failure
                                    if (shareFailure == null) shareFailure = failure
                                }
                            }.isSuccess
                        }
                        .all { it }
                }
                is ShareIntentData.Uris -> {
                    val filesToShare = shareIntentData.uris
                    if (filesToShare.isEmpty()) {
                        false
                    } else {
                        val expectedUploads = roomIds.size * filesToShare.size
                        var startedUploads = 0
                        roomIds
                            .map { roomId ->
                                val acquiredRoom = getJoinedRoom(roomId) ?: return@map false
                                val room = acquiredRoom.room
                                val handoffCandidate = room.takeIf {
                                    roomIds.size == 1 && acquiredRoom.ownedByShareFlow
                                }
                                val mediaSender = mediaSenderRoomFactory.create(room = room)
                                filesToShare
                                    .map { fileToShare ->
                                        val result = runCatching {
                                            appOperationalStateCoordinator.perform(
                                                lifecycleOperationRequest(
                                                    lifecycleId = FullAppLifecycleId.LC_16,
                                                    trigger = "upload",
                                                    accountIdHash = matrixClient.sessionId.value.sha256Hex(),
                                                    matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                                                    roomIdHash = roomId.value.sha256Hex(),
                                                )
                                            ) {
                                                mediaSender.sendMedia(
                                                    caption = shareIntentData.text,
                                                    uri = fileToShare.uri,
                                                    mimeType = fileToShare.mimeType,
                                                    mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
                                                    onUploadStarted = {
                                                        startedUploads += 1
                                                        if (startedUploads == expectedUploads) {
                                                            // Every SDK local echo now exists. Reveal the destination immediately;
                                                            // encryption/upload continues in this session-scoped coroutine and the
                                                            // timeline remains authoritative for progress, retry and failure.
                                                            offeredRoomHandoff = handoffCandidate
                                                            shareActionState.value = AsyncAction.Success(
                                                                ShareResult(roomIds, offeredRoomHandoff)
                                                            )
                                                        }
                                                    },
                                                ).getOrThrow()
                                            }
                                        }
                                        // If the coroutine was cancelled, destroy the room and rethrow the exception
                                        val cancellationException = result.exceptionOrNull() as? CancellationException
                                        if (cancellationException != null) {
                                            if (room !== offeredRoomHandoff &&
                                                activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId) == null
                                            ) {
                                                room.destroy()
                                            }
                                            throw cancellationException
                                        }
                                        result.exceptionOrNull()?.let { if (shareFailure == null) shareFailure = it }
                                        result.isSuccess
                                    }
                                    .all { isSuccess -> isSuccess }
                                    .also {
                                        if (room !== offeredRoomHandoff &&
                                            activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId) == null
                                        ) {
                                            room.destroy()
                                        }
                                    }
                            }
                            .all { it }
                    }
                }
            }

            if (!result) {
                throw shareFailure ?: IllegalStateException("Failed to handle incoming share intent")
            }
            // Release incoming share resources only after the product action succeeds.
            onSharedData(shareIntentData)
            ShareResult(roomIds, offeredRoomHandoff)
        }.runCatchingUpdatingState(shareActionState)
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
