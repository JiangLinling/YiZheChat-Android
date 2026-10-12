package io.element.android.libraries.push.impl.workmanager

import io.element.android.libraries.push.impl.db.PushRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** The caller has persisted the current batch; a newly queued push takes priority over cache warming. */
internal suspend fun runYieldingNotificationSync(
    pendingRequests: Flow<List<PushRequest>>,
    processedEventIds: Set<String>,
    sync: suspend () -> Unit,
) = coroutineScope {
    val incoming = async(start = CoroutineStart.UNDISPATCHED) {
        pendingRequests.first { requests -> requests.any { it.eventId !in processedEventIds } }
    }
    val warming = async { sync() }
    try {
        select<Unit> {
            incoming.onAwait { warming.cancel() }
            warming.onAwait { }
        }
    } finally {
        incoming.cancel()
        warming.cancel()
    }
}
