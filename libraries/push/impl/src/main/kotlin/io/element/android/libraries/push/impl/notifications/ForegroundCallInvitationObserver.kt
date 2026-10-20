package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.Inject
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.exception.NotificationResolverException
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import io.element.android.services.appnavstate.api.AppNavigationStateService
import io.element.android.services.appnavstate.api.NavigationState
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import timber.log.Timber

/** A visible room can be marked read before its push is delivered. Resolve fresh invitations
 * from its authenticated timeline through the same notification policy as a push. */
@Inject
class ForegroundCallInvitationObserver(
    private val navigation: AppNavigationStateService,
    private val clients: MatrixClientProvider,
    private val resolver: CallNotificationEventResolver,
    private val clock: SystemClock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun invitations(): Flow<NotifiableRingingCallEvent> {
        if (!NextDeploymentConfig.isNextDeployment) return emptyFlow()
        return navigation.appNavigationState.map { state ->
            if (!state.isInForeground) null else when (val nav = state.navigationState) {
                is NavigationState.Session -> nav.sessionId to null
                is NavigationState.Room -> nav.parentSession.sessionId to nav.roomId
                is NavigationState.Thread -> nav.parentRoom.parentSession.sessionId to nav.parentRoom.roomId
                else -> null
            }
        }.distinctUntilChanged().flatMapLatest { scope ->
            Timber.d("Foreground RTC scope: session=%s room=%s", scope?.first, scope?.second)
            if (scope == null) return@flatMapLatest emptyFlow()
            flow {
                val client = clients.getOrRestore(scope.first).getOrNull() ?: return@flow
                emitAll(client.roomListService.allRooms.summaries.map { rooms ->
                    // A room-list preview is only a hint to open its authenticated timeline.
                    // It never supplies an event ID, lifetime, or ringing authorization.
                    (listOfNotNull(scope.second) + rooms.filter {
                        isFreshForegroundCallHint(it.latestEvent, scope.first, clock.epochMillis())
                    }.map { it.roomId }).distinct().sortedBy { it.value }
                }.distinctUntilChanged().flatMapLatest { roomIds ->
                    Timber.d("Foreground RTC observed rooms=%s", roomIds)
                    merge(*roomIds.map { roomId ->
                        roomInvitations(NavigationState.Room("foreground-call", roomId,
                            NavigationState.Session("foreground-call", scope.first)))
                    }.toTypedArray())
                })
            }.catch { Timber.w(it, "Foreground RTC room discovery unavailable; push delivery remains active") }
        }
    }

    private fun roomInvitations(nav: NavigationState.Room): Flow<NotifiableRingingCallEvent> =
            flow {
                val client = clients.getOrRestore(nav.parentSession.sessionId).getOrNull() ?: return@flow
                val room = client.getJoinedRoom(nav.roomId) ?: return@flow
                Timber.d("Foreground RTC room subscription started: %s", nav.roomId)
                try {
                    val seen = mutableSetOf<io.element.android.libraries.matrix.api.core.EventId>()
                    room.liveTimeline.timelineItems.collect { items ->
                        val candidates = items.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                            .filter { !client.isMe(it.sender) && it.content is CallNotifyContent }
                            .groupBy { it.sender }.values.mapNotNull { it.maxByOrNull { event -> event.timestamp } }
                        for (event in candidates) {
                            val id = event.eventId ?: continue
                            val content = event.content as CallNotifyContent
                            Timber.d("Foreground RTC candidate: id=%s age=%d state=%s seen=%s", id,
                                clock.epochMillis() - event.timestamp, content.lifecycleState, id in seen)
                            if (id in seen || content.lifecycleState != null || client.sessionId in content.declinedBy ||
                                event.timestamp > clock.epochMillis() || clock.epochMillis() - event.timestamp >= 90_000) continue
                            val notification = client.notificationService.getNotifications(mapOf(nav.roomId to listOf(id)))
                                .getOrNull()?.get(id) ?: continue
                            val data = notification.getOrNull() ?: run {
                                if (notification.exceptionOrNull() != NotificationResolverException.EventFilteredOut) return@run null
                                val info = room.info()
                                val settings = client.notificationSettingsService
                                val callsEnabled = settings.isCallEnabled().getOrNull() == true
                                val mode = settings.getRoomNotificationSettings(nav.roomId, info.isEncrypted == true, info.isDm)
                                    .getOrNull()?.mode
                                val member = room.getUpdatedMember(event.sender).getOrNull()
                                val authority = if (callsEnabled && mode != null && mode != io.element.android.libraries.matrix.api.room.RoomNotificationMode.MUTE &&
                                    (info.isDm || info.isDirect) && info.joinedMembersCount == 2L &&
                                    member?.membership == io.element.android.libraries.matrix.api.room.RoomMembershipState.JOIN && !member.isIgnored
                                ) room.loadRtcNotification(id).getOrNull() else null
                                val recovered = recoverForegroundRingingNotification(
                                    event, info, client.sessionId, callsEnabled, mode, clock.epochMillis(), authority,
                                )
                                Timber.d("Foreground RTC filtered invitation: callsEnabled=%s mode=%s recovered=%s", callsEnabled, mode, recovered != null)
                                recovered
                            } ?: continue
                            val ringing = resolver.resolveEvent(client.sessionId, data).getOrNull() as? NotifiableRingingCallEvent
                                ?: continue
                            if (ringing.expirationTimestamp <= clock.epochMillis()) continue
                            seen += id
                            if (seen.size > 128) seen.remove(seen.first())
                            Timber.d("Foreground RTC invitation resolved through notification policy")
                            emit(ringing)
                        }
                    }
                } finally {
                    // This observer acquired its own room. Retire its timeline and owner scopes
                    // when navigation changes or observation is cancelled.
                    room.close()
                    Timber.d("Foreground RTC room subscription stopped: %s", nav.roomId)
                }
            }.catch { Timber.w(it, "Foreground RTC observer unavailable; push delivery remains active") }
}

internal fun isFreshForegroundCallHint(
    latest: io.element.android.libraries.matrix.api.roomlist.LatestEventValue,
    sessionId: io.element.android.libraries.matrix.api.core.SessionId,
    now: Long,
): Boolean {
    val event = latest as? io.element.android.libraries.matrix.api.roomlist.LatestEventValue.Remote ?: return false
    val content = event.content as? CallNotifyContent ?: return false
    return !event.isOwn && event.senderId != sessionId && content.lifecycleState == null &&
        sessionId !in content.declinedBy && event.timestamp <= now && now - event.timestamp < 90_000
}
