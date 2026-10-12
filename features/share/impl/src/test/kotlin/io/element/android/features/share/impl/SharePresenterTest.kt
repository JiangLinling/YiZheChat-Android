/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import android.net.Uri
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.share.api.OnSharedData
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.ShareResult
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.test.A_MESSAGE
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaSender
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.security.contentprotection.api.ContentProtectionException
import java.security.MessageDigest
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharePresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createSharePresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - on room selected error then clear error`() = runTest {
        val presenter = createSharePresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val failure = awaitItem()
            assertThat(failure.shareAction.isFailure()).isTrue()
            failure.eventSink.invoke(ShareEvents.ClearError)
            assertThat(awaitItem().shareAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - on room selected ok`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
            },
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.PlainText(A_MESSAGE),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(ShareResult(listOf(A_ROOM_ID))))
        }
    }

    @Test
    fun `present - send text ok`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
            },
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.PlainText(A_MESSAGE),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(ShareResult(listOf(A_ROOM_ID))))
        }
    }

    @Test
    fun `share binds the current account device and room before sending`() = runTest {
        val matrixClient = FakeMatrixClient()
        var sends = 0
        matrixClient.givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom(liveTimeline = FakeTimeline().apply {
            sendMessageLambda = { _, _, _, _, _ -> sends++; Result.success(Unit) }
        }))
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { request ->
            if (request.accountIdHash != matrixClient.sessionId.value.sha256Hex() ||
                request.matrixDeviceIdHash != matrixClient.deviceId.value.sha256Hex() ||
                request.roomIdHash != A_ROOM_ID.value.sha256Hex()) SecurityException("Wrong share subject") else null
        })
        val presenter = createSharePresenter(matrixClient = matrixClient, appOperationalStateCoordinator = coordinator)
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat(awaitItem().shareAction.isSuccess()).isTrue()
            assertThat(sends).isEqualTo(1)
            assertThat(coordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_13)
        }
    }

    @Test
    fun `recipient failure keeps its classification and does not consume incoming share`() = runTest {
        val failure = ContentProtectionException.RecipientDeviceUnavailable()
        var consumed = false
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom(liveTimeline = FakeTimeline().apply {
                sendMessageLambda = { _, _, _, _, _ -> Result.failure(failure) }
            }))
        }
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createSharePresenter(matrixClient = matrixClient, appOperationalStateCoordinator = coordinator,
            onSharedData = OnSharedData { consumed = true })
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat((awaitItem().shareAction as AsyncAction.Failure).error).isSameInstanceAs(failure)
            assertThat(consumed).isFalse()
            assertThat(coordinator.clientReceipts).isEmpty()
        }
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `present - send media ok`() = runTest {
        val sendMediaResult = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline(),
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val mediaSender = FakeMediaSender(
            sendMediaResult = sendMediaResult,
        )
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.Uris(
                text = A_MESSAGE,
                listOf(
                    UriToShare(
                        uri = Uri.parse("content://image.jpg"),
                        mimeType = MimeTypes.Jpeg,
                    )
                )
            ),
            mediaSenderRoomFactory = MediaSenderRoomFactory { mediaSender },
            appOperationalStateCoordinator = lifecycleCoordinator,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(
                AsyncAction.Success(ShareResult(listOf(A_ROOM_ID), roomToHandoff = joinedRoom))
            )
            sendMediaResult.assertions().isCalledOnce()
            assertThat(lifecycleCoordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
            assertThat(lifecycleCoordinator.startedRequests.single().trigger).isEqualTo("upload")
            assertThat(lifecycleCoordinator.startedRequests.single().accountIdHash).isEqualTo(matrixClient.sessionId.value.sha256Hex())
            assertThat(lifecycleCoordinator.startedRequests.single().matrixDeviceIdHash).isEqualTo(matrixClient.deviceId.value.sha256Hex())
            assertThat(lifecycleCoordinator.startedRequests.single().roomIdHash).isEqualTo(A_ROOM_ID.value.sha256Hex())
        }
    }
}

internal fun TestScope.createSharePresenter(
    shareIntentData: ShareIntentData = ShareIntentData.PlainText(A_MESSAGE),
    matrixClient: MatrixClient = FakeMatrixClient(),
    activeRoomsHolder: ActiveRoomsHolder = DefaultActiveRoomsHolder(),
    mediaSenderRoomFactory: MediaSenderRoomFactory = MediaSenderRoomFactory { FakeMediaSender() },
    mediaOptimizationConfigProvider: MediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
    onSharedData: OnSharedData = OnSharedData {},
    appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
): SharePresenter {
    return SharePresenter(
        shareIntentData = shareIntentData,
        sessionCoroutineScope = this,
        matrixClient = matrixClient,
        activeRoomsHolder = activeRoomsHolder,
        mediaSenderRoomFactory = mediaSenderRoomFactory,
        mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
        onSharedData = onSharedData,
        appOperationalStateCoordinator = appOperationalStateCoordinator,
    )
}
