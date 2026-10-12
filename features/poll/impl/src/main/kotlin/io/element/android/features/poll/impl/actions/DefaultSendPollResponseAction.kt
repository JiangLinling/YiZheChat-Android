/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.poll.impl.actions

import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.PollVote
import io.element.android.features.poll.api.actions.SendPollResponseAction
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.api.AnalyticsService
import java.security.MessageDigest

@ContributesBinding(RoomScope::class)
class DefaultSendPollResponseAction(
    private val analyticsService: AnalyticsService,
    private val room: JoinedRoom,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : SendPollResponseAction {
    override suspend fun execute(timeline: Timeline, pollStartId: EventId, answerId: String): Result<Unit> {
        return runCatchingExceptions {
            appOperationalStateCoordinator.perform(pollOperationRequest(pollStartId)) {
                timeline.sendPollResponse(
                    pollStartId = pollStartId,
                    answers = listOf(answerId),
                ).getOrThrow()
            }
        }.onSuccess {
            analyticsService.capture(PollVote())
        }
    }

    private fun pollOperationRequest(eventId: EventId) = lifecycleOperationRequest(
        lifecycleId = FullAppLifecycleId.LC_15,
        trigger = "poll",
        roomIdHash = room.roomId.value.sha256Hex(),
        eventIdHash = eventId.value.sha256Hex(),
    )
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
