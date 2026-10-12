/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.room.CallIntentConsensus
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import timber.log.Timber

/** Optional RTC failure/backpressure never restarts the SDK membership subscription. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun roomInfoWithRtc(base: StateFlow<RoomInfo>, observer: () -> Flow<RtcRoomCallSnapshot>): Flow<RoomInfo> {
    val empty = RtcRoomCallSnapshot(emptyList(), CallIntentConsensus.None)
    val rtc = base.map { it.currentUserMembership }.distinctUntilChanged().flatMapLatest { membership ->
        if (membership != CurrentUserMembership.JOINED) flowOf(empty) else flow { emitAll(observer()) }
            .retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                Timber.w("RTC call observer unavailable; reconnecting")
                emit(empty)
                delay((attempt + 1).coerceAtMost(15) * 1_000)
                true
            }.onStart { emit(empty) }
    }.onStart { emit(empty) }
    return combine(base, rtc) { info, call ->
        (if (info.currentUserMembership == CurrentUserMembership.JOINED) call else empty).applyTo(info)
    }
}
