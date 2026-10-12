/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.EventId

data class ForwardPair(val eventId: EventId, val roomId: RoomId)
sealed interface ForwardPairState {
    data object Pending : ForwardPairState
    data object Sending : ForwardPairState
    data object Success : ForwardPairState
    data class Failed(val reason: String?) : ForwardPairState
    data class Ambiguous(val reason: String?) : ForwardPairState
}

data class ForwardMessagesState(
    val forwardAction: AsyncAction<List<RoomId>>,
    val pairStates: Map<ForwardPair, ForwardPairState>,
    val eventSink: (ForwardMessagesEvents) -> Unit
)
