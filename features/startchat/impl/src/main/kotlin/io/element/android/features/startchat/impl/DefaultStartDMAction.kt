/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import androidx.compose.runtime.MutableState
import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.CreatedRoom
import io.element.android.features.startchat.api.ConfirmingStartDmWithMatrixUser
import io.element.android.features.startchat.api.StartDMAction
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.StartDMResult
import io.element.android.libraries.matrix.api.room.startDM
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.admitManagedDirectRoomOperations
import io.element.android.security.dataprotection.api.managedDirectRoomApplicationReceipts
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@ContributesBinding(SessionScope::class)
class DefaultStartDMAction(
    private val matrixClient: MatrixClient,
    private val analyticsService: AnalyticsService,
    private val managementControlService: io.element.android.security.dataprotection.api.ManagementControlService,
    private val appOperationalStateCoordinator: io.element.android.security.dataprotection.api.AppOperationalStateCoordinator,
) : StartDMAction {
    override suspend fun execute(
        matrixUser: MatrixUser,
        createIfDmDoesNotExist: Boolean,
        actionState: MutableState<AsyncAction<RoomId>>,
    ) {
        actionState.value = AsyncAction.Loading
        when (val result = matrixClient.startDM(matrixUser.userId, createIfDmDoesNotExist)) {
            is StartDMResult.Success -> {
                if (result.isNew) {
                    analyticsService.capture(CreatedRoom(isDM = true))
                }
                try {
                    // Complete all signed reads before publishing the UI transition. Navigation can
                    // cancel this action immediately after Success, so persist the actual fact atomically.
                    val operations = if (managementControlService.supportsManagedRoomCreation) {
                        val applied = checkNotNull(managementControlService.managedDirectRoom(
                            accountIdHash(matrixClient.sessionId.value), accountIdHash(matrixClient.deviceId.value), matrixUser.userId.value, false))
                        check(applied.roomId == result.roomId.value) { "Original direct room binding changed" }
                        appOperationalStateCoordinator.admitManagedDirectRoomOperations(
                            applied, accountIdHash(matrixClient.sessionId.value), accountIdHash(matrixClient.deviceId.value))
                    } else emptyList()
                    withContext(NonCancellable) {
                        actionState.value = AsyncAction.Success(result.roomId)
                        appOperationalStateCoordinator.enqueueClientReceipts(
                            managedDirectRoomApplicationReceipts(operations, LifecycleComponent.ANDROID_FRONTEND))
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    actionState.value = AsyncAction.Failure(failure)
                }
            }
            is StartDMResult.Failure -> {
                actionState.value = AsyncAction.Failure(result.throwable)
            }
            StartDMResult.DmDoesNotExist -> {
                val identityState = matrixClient.encryptionService.getUserIdentity(matrixUser.userId, fallbackToServer = false).getOrNull()
                actionState.value = ConfirmingStartDmWithMatrixUser(
                    matrixUser = matrixUser,
                    isUserIdentityUnknown = identityState == null
                )
            }
        }
    }
}
