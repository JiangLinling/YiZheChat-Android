/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.widget

import kotlinx.coroutines.flow.Flow

interface MatrixWidgetDriver : AutoCloseable {
    val id: String
    val incomingMessages: Flow<String>
    /** Exact Matrix event ID acknowledged for this widget's outgoing ringing invitation. */
    val outgoingCallInvitation: kotlinx.coroutines.flow.StateFlow<String?>

    suspend fun run()
    suspend fun send(message: String)

    /** Best-effort departure of this widget's own RTC membership after local media has stopped. */
    suspend fun stopCallMembership()
}
