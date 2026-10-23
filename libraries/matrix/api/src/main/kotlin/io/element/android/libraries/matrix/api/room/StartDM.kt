/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room

import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId

/**
 * Try to find an existing DM with the given user, or create one if none exists and [createIfDmDoesNotExist] is true.
 */
suspend fun MatrixClient.startDM(
    userId: UserId,
    createIfDmDoesNotExist: Boolean,
): StartDMResult {
    return resolveDM(userId, create = false)
        .fold(
            onSuccess = { resolved ->
                val existingDM = resolved?.roomId
                val existingState = existingDM?.let { roomId -> getRoom(roomId)?.info()?.directRoomRelationState() }
                val canReuseExistingRoom = existingDM != null && existingState !in setOf(
                    DirectRoomRelationState.STALE,
                    DirectRoomRelationState.INVALID,
                    DirectRoomRelationState.NOT_DIRECT,
                )
                if (canReuseExistingRoom) {
                    StartDMResult.Success(requireNotNull(existingDM), isNew = false, managedEvidence = resolved?.managedEvidence)
                } else if (createIfDmDoesNotExist) {
                    resolveDM(userId, create = true).fold(
                        { created ->
                            if (created == null) {
                                StartDMResult.Failure(IllegalStateException("Direct room creation returned no room"))
                            } else {
                                StartDMResult.Success(created.roomId, isNew = true, managedEvidence = created.managedEvidence)
                            }
                        },
                        { StartDMResult.Failure(it) }
                    )
                } else {
                    StartDMResult.DmDoesNotExist
                }
            },
            onFailure = { error ->
                StartDMResult.Failure(error)
            }
        )
}

sealed interface StartDMResult {
    data class Success(
        val roomId: RoomId,
        val isNew: Boolean,
        val managedEvidence: DirectRoomOperationEvidence? = null,
    ) : StartDMResult
    data object DmDoesNotExist : StartDMResult
    data class Failure(val throwable: Throwable) : StartDMResult
}
