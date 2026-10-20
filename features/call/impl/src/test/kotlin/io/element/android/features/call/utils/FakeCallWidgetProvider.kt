/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import io.element.android.features.call.impl.security.CallProtectionSession
import io.element.android.features.call.impl.utils.CallWidgetProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver

class FakeCallWidgetProvider(
    private val widgetDriver: MatrixWidgetDriver = FakeMatrixWidgetDriver(),
    private val url: String = "https://call.element.io",
    private val protectionSession: CallProtectionSession? = null,
) : CallWidgetProvider {
    val contactRevocations = kotlinx.coroutines.flow.MutableSharedFlow<Throwable>()
    override fun observeContactRevocation(sessionId: SessionId, roomId: RoomId, operationId: String) = contactRevocations
    var getWidgetCalled = false
        private set
    var incomingRequested = false
        private set

    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        isAudioCall: Boolean,
        clientId: String,
        languageTag: String?,
        theme: String?,
        operationId: String,
        isIncoming: Boolean,
    ): Result<CallWidgetProvider.GetWidgetResult> {
        getWidgetCalled = true
        incomingRequested = isIncoming
        return Result.success(
            CallWidgetProvider.GetWidgetResult(
                driver = widgetDriver,
                url = url,
                protectionSession = protectionSession,
            )
        )
    }
}
