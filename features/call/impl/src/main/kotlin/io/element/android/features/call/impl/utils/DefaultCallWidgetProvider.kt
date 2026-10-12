/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.call.impl.security.CallProtectionDeniedException
import io.element.android.features.call.impl.security.CallProtectionRequest
import io.element.android.features.call.impl.security.CallProtectionService
import io.element.android.features.call.impl.security.EMBEDDED_CALL_WIDGET_BASE_URL
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

@ContributesBinding(AppScope::class)
class DefaultCallWidgetProvider(
    private val matrixClientsProvider: MatrixClientProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val callWidgetSettingsProvider: CallWidgetSettingsProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val callProtectionService: CallProtectionService,
) : CallWidgetProvider {
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
        val preparationStarted = TimeSource.Monotonic.markNow()
        fun logPreparationPhase(phase: String) {
            Timber.tag("RTCWidgetSetup").d("phase=%s elapsedMs=%d", phase, preparationStarted.elapsedNow().inWholeMilliseconds)
        }
        logPreparationPhase("provider_started")
        var ownedRoom: JoinedRoom? = null
        return runCatchingExceptions {
        val matrixClient = matrixClientsProvider.getOrRestore(sessionId)
            .onFailure { Timber.e("Element Call initialization failed at session restore.") }
            .getOrThrow()
        logPreparationPhase("client_restored")
        // The widget driver and its Room must come from the same restored Matrix client. An active
        // room can survive a client/activity replacement and still match by session and room id,
        // while its Rust Room no longer exists in the restored client's BaseClient. That stale
        // handle fails encrypted MatrixRTC membership sends locally with InsufficientData.
        val roomSelection = selectCallRoomForDeployment(
            isNextDeployment = NextDeploymentConfig.isNextDeployment,
            matrixClient = matrixClient,
            activeRoomsHolder = activeRoomsHolder,
            sessionId = sessionId,
            roomId = roomId,
        ) ?: run {
                Timber.e("Element Call initialization failed at room lookup.")
                error("Room not found")
            }
        logPreparationPhase("current_room_ready")
        val room = roomSelection.room
        if (roomSelection.ownedByCall) ownedRoom = room

        val customBaseUrl = appPreferencesStore.getCustomElementCallBaseUrlFlow().firstOrNull()
        logPreparationPhase("preferences_ready")
        val baseUrl = protectedCallWidgetBaseUrl(NextDeploymentConfig.isNextDeployment, customBaseUrl)

        val roomInfo = room.info()
        val isEncrypted = roomInfo.isEncrypted ?: room.getUpdatedIsEncrypted()
            .onFailure { Timber.e("Element Call initialization failed while reading room encryption.") }
            .getOrThrow()
        logPreparationPhase("encryption_ready")
        val protectionSession = runCatchingExceptions {
            callProtectionService.authorize(
                CallProtectionRequest(
                    accountId = sessionId.value,
                    deviceId = matrixClient.deviceId.value,
                    roomEncrypted = isEncrypted,
                    widgetBaseUrl = baseUrl,
                )
            )
        }
            .onFailure { failure ->
                val reason = (failure as? CallProtectionDeniedException)?.reason?.name ?: "UNEXPECTED"
                Timber.e("Element Call initialization failed at protected-call authorization: $reason.")
            }
            .getOrThrow()
        logPreparationPhase("protection_authorized")
        // The visible room already holds the live banner projection. Reuse only that call
        // fact while the fresh driver's room is loading its initial sticky snapshot.
        val hasActiveCall = room.info().hasRoomCall || (NextDeploymentConfig.isNextDeployment &&
            (isIncoming || activeRoomsHolder.getActiveRoomMatching(sessionId, roomId)?.info()?.hasRoomCall == true))
        val widgetSettings = runCatchingExceptions {
            callWidgetSettingsProvider.provide(
                baseUrl = baseUrl,
                encrypted = isEncrypted,
                direct = room.isDm(),
                isAudioCall = isAudioCall,
                // The caller validated the exact invitation before admission. Sticky RTC
                // membership may not yet be reflected in hasRoomCall; answering must join
                // that call rather than publish a second ringing invitation.
                hasActiveCall = hasActiveCall,
            )
        }
            .onFailure { Timber.e("Element Call initialization failed while creating widget settings.") }
            .getOrThrow()
        logPreparationPhase("settings_ready")
        val callUrl = room.generateWidgetWebViewUrl(
            widgetSettings = widgetSettings,
            clientId = clientId,
            languageTag = languageTag,
            theme = theme,
        )
            .onFailure { Timber.e("Element Call initialization failed while generating the widget URL.") }
            .getOrThrow()
        logPreparationPhase("url_ready")
        val experienceUrl = if (NextDeploymentConfig.isNextDeployment && !room.isDm()) {
            groupCallExperienceUrl(callUrl, isAudioCall, joining = hasActiveCall)
        } else callUrl
        val operationBoundCallUrl = bindRtcOperation(experienceUrl, operationId)

        val driver = room.getWidgetDriver(widgetSettings)
            .onFailure { Timber.e("Element Call initialization failed while creating the widget driver.") }
            .getOrThrow()
        logPreparationPhase("driver_ready")
        val retainedDriver = ownedRoom?.let { acquiredRoom ->
            CallOwnedMatrixWidgetDriver(driver, acquiredRoom).also { ownedRoom = null }
        } ?: driver

        CallWidgetProvider.GetWidgetResult(
            driver = retainedDriver,
            url = operationBoundCallUrl,
            protectionSession = protectionSession,
            invitationRoom = room.takeIf { NextDeploymentConfig.isNextDeployment },
        )
        }.onFailure {
            logPreparationPhase("provider_failed")
            // The driver assumes ownership only after it has been created successfully.
            ownedRoom?.destroy()
            ownedRoom = null
        }
    }
}

internal data class CallRoomSelection(
    val room: JoinedRoom,
    val ownedByCall: Boolean,
)

internal suspend fun selectCallRoomForDeployment(
    isNextDeployment: Boolean,
    matrixClient: MatrixClient,
    activeRoomsHolder: ActiveRoomsHolder,
    sessionId: SessionId,
    roomId: RoomId,
): CallRoomSelection? {
    if (isNextDeployment) {
        matrixClient.ensureServerVersionsReady()
            .onFailure { Timber.e("Element Call initialization failed during homeserver capability discovery.") }
            .getOrThrow()
        // ActiveRoomsHolder cannot prove that its Room belongs to this restored MatrixClient
        // generation. Next therefore owns a fresh current-client Room or fails closed.
        return matrixClient.getJoinedRoom(roomId)?.let { CallRoomSelection(it, ownedByCall = true) }
    }
    // Preserve the existing non-Next lookup order and ownership behavior.
    return (activeRoomsHolder.getActiveRoomMatching(sessionId, roomId) ?: matrixClient.getJoinedRoom(roomId))
        ?.let { CallRoomSelection(it, ownedByCall = false) }
}

internal class CallOwnedMatrixWidgetDriver(
    private val delegate: MatrixWidgetDriver,
    private val ownedRoom: JoinedRoom,
) : MatrixWidgetDriver {
    private val closed = AtomicBoolean(false)

    override val id: String = delegate.id
    override val outgoingCallInvitation = delegate.outgoingCallInvitation
    override val incomingMessages: Flow<String> = delegate.incomingMessages

    override suspend fun run() = delegate.run()

    override suspend fun send(message: String) = delegate.send(message)

    override suspend fun stopCallMembership() = delegate.stopCallMembership()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val driverFailure = runCatching { delegate.close() }.exceptionOrNull()
        val roomFailure = runCatching { ownedRoom.destroy() }.exceptionOrNull()
        if (driverFailure != null) {
            roomFailure?.let(driverFailure::addSuppressed)
            throw driverFailure
        }
        roomFailure?.let { throw it }
    }
}

/** Keep group membership semantics while using the same one-tap ringing/answer flow as a direct call. */
internal fun groupCallExperienceUrl(callUrl: String, isAudioCall: Boolean, joining: Boolean): String {
    val parameters = linkedMapOf(
        "skipLobby" to "true",
        "returnToLobby" to "false",
        "sendNotificationType" to if (joining) "notification" else "ring",
        // Element Call's pickup mode ends the entire call on the first decline.
        // Group invitations ring normally without that one-to-one termination rule.
        "waitForCallPickup" to "false",
        // A participant leaving must not end the other participants' group session.
        "autoLeave" to "false",
        "intent" to (if (joining) "join_existing" else "start_call") + if (isAudioCall) "_voice" else "",
    )
    val beforeFragment = callUrl.substringBefore('#')
    val fragment = callUrl.substringAfter('#', "")
    val route = fragment.substringBefore('?')
    val original = fragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in parameters }
    // Preserve SDK-owned encoded identities byte-for-byte. Element Call gives the
    // fragment precedence over query parameters; these override only UI behavior.
    val configured = original + parameters.map { (key, value) -> "$key=$value" }
    return "$beforeFragment#$route?${configured.joinToString("&")}"
}

internal fun bindRtcOperation(callUrl: String, operationId: String): String {
    require(
        operationId.length in 16..256 && operationId.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }
    ) {
        "RTC lifecycle operation identity is invalid."
    }
    val parsed = URI(callUrl)
    require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null) {
        "RTC widget URL is invalid."
    }
    val operationParameter = "yizhe_rtc_operation_id=$operationId"
    val fragmentIndex = callUrl.indexOf('#')
    val beforeFragment = if (fragmentIndex >= 0) callUrl.substring(0, fragmentIndex) else callUrl
    val fragment = if (fragmentIndex >= 0) callUrl.substring(fragmentIndex) else ""
    val separator = when (parsed.rawQuery) {
        null -> "?"
        "" -> ""
        else -> "&"
    }
    // Matrix SDK places widget identity and room authority in an already percent-encoded fragment.
    // Rebuilding the URI from raw components encodes '%' again, so Element Call receives an invalid
    // parentUrl and falls back to its unauthenticated guest flow. Append only to the query and keep
    // the SDK-owned fragment byte-for-byte intact.
    return "$beforeFragment$separator$operationParameter$fragment"
}

/** The bundled call UI and the homeserver's RTC services have separate trust boundaries. */
internal fun protectedCallWidgetBaseUrl(isNextDeployment: Boolean, customBaseUrl: String?): String {
    if (!isNextDeployment) return customBaseUrl ?: EMBEDDED_CALL_WIDGET_BASE_URL
    check(customBaseUrl == null || customBaseUrl == EMBEDDED_CALL_WIDGET_BASE_URL) {
        "Next requires the trusted embedded Element Call UI."
    }
    return EMBEDDED_CALL_WIDGET_BASE_URL
}
