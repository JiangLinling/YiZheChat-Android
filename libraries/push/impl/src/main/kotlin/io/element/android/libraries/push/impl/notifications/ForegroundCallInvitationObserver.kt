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
                is NavigationState.Room -> nav
                is NavigationState.Thread -> nav.parentRoom
                else -> null
            }
        }.distinctUntilChanged().flatMapLatest { nav ->
            if (nav == null) return@flatMapLatest emptyFlow()
            flow {
                val client = clients.getOrRestore(nav.parentSession.sessionId).getOrNull() ?: return@flow
                val room = client.getJoinedRoom(nav.roomId) ?: return@flow
                try {
                    val seen = mutableSetOf<io.element.android.libraries.matrix.api.core.EventId>()
                    room.liveTimeline.timelineItems.collect { items ->
                        val candidates = items.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                            .filter { !client.isMe(it.sender) && it.content is CallNotifyContent }
                            .groupBy { it.sender }.values.mapNotNull { it.maxByOrNull { event -> event.timestamp } }
                        for (event in candidates) {
                            val id = event.eventId ?: continue
                            val content = event.content as CallNotifyContent
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
                }
            }.catch { Timber.w(it, "Foreground RTC observer unavailable; push delivery remains active") }
        }
    }
}
