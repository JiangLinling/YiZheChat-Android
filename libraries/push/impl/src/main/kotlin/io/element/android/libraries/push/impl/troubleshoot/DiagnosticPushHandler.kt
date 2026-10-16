/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.troubleshoot

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.matrix.api.core.EventId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

@SingleIn(AppScope::class)
@Inject
class DiagnosticPushHandler {
    private val _state = MutableSharedFlow<EventId>()
    val state: SharedFlow<EventId> = _state
    private class Attempt(val eventId: EventId, val received: AtomicBoolean = AtomicBoolean(false))
    private val pendingEvent = AtomicReference<Attempt?>(null)

    fun beginTest(): EventId = EventId("\$YIZHE_PUSH_DIAGNOSTIC_${UUID.randomUUID()}").also {
        pendingEvent.set(Attempt(it))
    }

    fun isCurrentTest(eventId: EventId): Boolean = pendingEvent.get()?.eventId == eventId

    fun clearPendingTest() {
        pendingEvent.set(null)
    }

    suspend fun handlePush(eventId: EventId) {
        val expected = pendingEvent.get()
        if (expected?.eventId == eventId && expected.received.compareAndSet(false, true)) {
            _state.emit(eventId)
        }
    }

    companion object {
        fun isDiagnosticEvent(eventId: EventId): Boolean =
            eventId.value == "\$THIS_IS_A_FAKE_EVENT_ID" ||
                DIAGNOSTIC_EVENT_PATTERN.matches(eventId.value)

        private val DIAGNOSTIC_EVENT_PATTERN = Regex(
            "\\\$YIZHE_PUSH_DIAGNOSTIC_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
    }
}
