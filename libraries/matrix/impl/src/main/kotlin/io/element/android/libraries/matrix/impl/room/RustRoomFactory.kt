/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.appconfig.TimelineConfig
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.notificationsettings.NotificationSettingsService
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembershipObserver
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.roomlist.awaitLoaded
import io.element.android.libraries.matrix.impl.room.powerlevels.PowerLevelEventTransport
import io.element.android.libraries.matrix.impl.room.powerlevels.RoomPowerLevelUpdater
import io.element.android.libraries.matrix.impl.room.preview.RoomPreviewInfoMapper
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.recordTransaction
import io.element.android.services.analyticsproviders.api.recordChildTransaction
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import timber.log.Timber
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.util.concurrent.atomic.AtomicBoolean

class RustRoomFactory(
    private val sessionId: SessionId,
    private val deviceId: DeviceId,
    private val notificationSettingsService: NotificationSettingsService,
    private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val systemClock: SystemClock,
    private val roomContentForwarder: RoomContentForwarder,
    private val roomListService: RoomListService,
    private val innerClient: Client,
    private val roomSyncSubscriber: RoomSyncSubscriber,
    private val timelineEventFilterFactory: TimelineEventFilterFactory,
    private val featureFlagService: FeatureFlagService,
    private val roomMembershipObserver: RoomMembershipObserver,
    private val roomInfoMapper: RoomInfoMapper,
    private val analyticsService: AnalyticsService,
    private val matrixProtectedContentAdapterFactory: MatrixProtectedContentAdapterFactory = DisabledMatrixProtectedContentAdapterFactory,
    private val membershipLifecycle: RoomMembershipLifecycle? = null,
) {
    private val dispatcher = dispatchers.computation.limitedParallelism(1)
    private val mutex = Mutex()
    private val powerLevelMutationMutex = Mutex()
    private val isDestroyed: AtomicBoolean = AtomicBoolean(false)
    private val roomOwnerToken = Any()

    fun ownsCurrentRoom(room: JoinedRoom): Boolean =
        !isDestroyed.get() && room is JoinedRustRoom && room.belongsToClient(roomOwnerToken, sessionId, room.roomId)

    private val rtcEventHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun powerLevelTransport(room: Room) = object : PowerLevelEventTransport {
        override suspend fun read(): JsonObject = withContext(dispatchers.io) {
            val session = innerClient.session()
            check(session.userId == sessionId.value && session.deviceId == deviceId.value && session.accessToken.isNotBlank()) {
                "Matrix power-level authority session mismatch"
            }
            val url = session.homeserverUrl.toHttpUrl().newBuilder()
                .addPathSegments("_matrix/client/v3/rooms").addPathSegment(room.id())
                .addPathSegment("state").addPathSegment("m.room.power_levels").addPathSegment("").build()
            check(url.isHttps) { "Matrix power-level authority requires HTTPS" }
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}")
                .header("Cache-Control", "no-cache").build()
            rtcEventHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Matrix power-level authority unavailable" }
                val source = response.body.source()
                source.request(65_536)
                check(source.buffer.size < 65_536) { "Matrix power-level authority response too large" }
                Json.parseToJsonElement(source.buffer.readUtf8()) as? JsonObject
                    ?: error("Invalid Matrix power-level authority content")
            }
        }

        override suspend fun send(content: JsonObject): String =
            room.sendStateEventRaw("m.room.power_levels", "", content.toString())
    }

    private val eventFilters = TimelineConfig.excludedEvents
        .takeIf { it.isNotEmpty() }
        ?.let { listStateEventType ->
            timelineEventFilterFactory.create(listStateEventType)
        }

    suspend fun destroy() {
        isDestroyed.set(true)
        withContext(NonCancellable + dispatcher) {
            mutex.withLock {
                Timber.d("Destroying room factory")
            }
        }
    }

    suspend fun getBaseRoom(roomId: RoomId): RustBaseRoom? = withContext(dispatcher) {
        mutex.withLock {
            if (isDestroyed.get()) {
                Timber.d("Room factory is destroyed, returning null for $roomId")
                return@withContext null
            }
            val room = awaitRoomInRoomList(roomId) ?: return@withContext null
            getBaseRoom(sdkRoom = room, roomInfo = room.roomInfo())
        }
    }

    private fun getBaseRoom(sdkRoom: Room, roomInfo: RoomInfo) = RustBaseRoom(
        sessionId = sessionId,
        deviceId = deviceId,
        innerRoom = sdkRoom,
        coroutineDispatchers = dispatchers,
        roomSyncSubscriber = roomSyncSubscriber,
        roomMembershipObserver = roomMembershipObserver,
        roomInfoMapper = roomInfoMapper,
        initialRoomInfo = roomInfoMapper.map(roomInfo),
        membershipLifecycle = membershipLifecycle,
        sessionCoroutineScope = sessionCoroutineScope,
    )

    suspend fun getJoinedRoomOrPreview(roomId: RoomId, serverNames: List<String>): GetRoomResult? = withContext(dispatcher) {
        val roomOpenStartedAt = System.nanoTime()
        val result = mutex.withLock {
            if (isDestroyed.get()) {
                Timber.d("Room factory is destroyed, returning null for $roomId")
                return@withContext null
            }

            val sdkRoom = awaitRoomInRoomList(roomId) ?: return@withLock null
            val roomInfo = sdkRoom.roomInfo()

            val parentTransaction = analyticsService.getLongRunningTransaction(AnalyticsLongRunningTransaction.OpenRoom)

            if (roomInfo.membership == Membership.JOINED) {
                analyticsService.recordTransaction(
                    name = "Get joined room",
                    operation = "RustRoomFactory.getJoinedRoomOrPreview",
                    parentTransaction = parentTransaction,
                ) { transaction ->
                    val hideThreadedEvents = featureFlagService.isFeatureEnabled(FeatureFlags.Threads)
                    // Init the live timeline in the SDK from the Room
                    val timeline = transaction.recordChildTransaction(
                        operation = "sdkRoom.timelineWithConfiguration",
                        description = "Get timeline from the SDK",
                    ) {
                        sdkRoom.timelineWithConfiguration(
                            TimelineConfiguration(
                                focus = TimelineFocus.Live(hideThreadedEvents = hideThreadedEvents),
                                filter = eventFilters?.let(TimelineFilter::EventFilter) ?: TimelineFilter.All,
                                internalIdPrefix = "live",
                                dateDividerMode = DateDividerMode.DAILY,
                                trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
                                reportUtds = true,
                            )
                        )
                    }
                    val baseRoom = getBaseRoom(sdkRoom, roomInfo)

                    GetRoomResult.Joined(
                        JoinedRustRoom(
                            clientOwnerToken = roomOwnerToken,
                            baseRoom = baseRoom,
                            notificationSettingsService = notificationSettingsService,
                            roomContentForwarder = roomContentForwarder,
                            liveInnerTimeline = timeline,
                            coroutineDispatchers = dispatchers,
                            systemClock = systemClock,
                            featureFlagService = featureFlagService,
                            powerLevelMutationMutex = powerLevelMutationMutex,
                            updateRawUserPowerLevels = RoomPowerLevelUpdater(
                                powerLevelTransport(sdkRoom), powerLevelMutationMutex,
                            )::updateUsers,
                            fetchRtcEvent = if (NextDeploymentConfig.isNextDeployment) { eventId ->
                                withContext(dispatchers.io) {
                                    val session = innerClient.session()
                                    val url = session.homeserverUrl.toHttpUrl().newBuilder()
                                        .addPathSegments("_matrix/client/v3/rooms").addPathSegment(sdkRoom.id())
                                        .addPathSegment("event").addPathSegment(eventId).build()
                                    check(url.isHttps) { "RTC event authority requires HTTPS" }
                                    val request = Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}").build()
                                    rtcEventHttpClient.newCall(request).execute().use { response ->
                                        check(response.isSuccessful) { "RTC event authority unavailable" }
                                        val source = response.body.source()
                                        source.request(65_536)
                                        check(source.buffer.size < 65_536) { "RTC event authority response too large" }
                                        val bytes = source.buffer.readByteArray()
                                        Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
                                    }
                                }
                            } else null,
                            redactRtcMembershipEvent = if (NextDeploymentConfig.isNextDeployment) { eventId ->
                                withContext(dispatchers.io) {
                                    check(eventId.startsWith('$')) { "RTC cleanup event identity invalid" }
                                    val session = innerClient.session()
                                    val url = session.homeserverUrl.toHttpUrl().newBuilder()
                                        .addPathSegments("_matrix/client/v3/rooms").addPathSegment(sdkRoom.id())
                                        .addPathSegment("redact").addPathSegment(eventId)
                                        .addPathSegment("rtc-departure-$eventId").build()
                                    check(url.isHttps) { "RTC cleanup requires HTTPS" }
                                    val request = Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}")
                                        .put(io.element.android.libraries.matrix.impl.widget.rtcMembershipRedactionContent(eventId)
                                            .toRequestBody("application/json".toMediaType())).build()
                                    rtcEventHttpClient.newCall(request).execute().use { response ->
                                        check(response.isSuccessful) { "RTC cleanup not accepted" }
                                    }
                                }
                            } else null,
                            protectedContentAdapter = matrixProtectedContentAdapterFactory.create(
                                sessionId = sessionId,
                                deviceId = deviceId,
                                roomId = RoomId(sdkRoom.id()),
                                activeMemberAccountIdHashes = {
                                    baseRoom.getMembers(ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS).getOrThrow()
                                        .protectedRoomAudienceAccountIdHashes()
                                        .requireProtectedRoomSender(accountIdHash(sessionId.value))
                                },
                                isRoomEncrypted = { baseRoom.roomInfoFlow.value.isEncrypted == true },
                            ),
                        )
                    )
                }
            } else {
                analyticsService.recordTransaction(
                    name = "Get preview of room",
                    operation = "RustRoomFactory.getJoinedRoomOrPreview",
                    parentTransaction = parentTransaction,
                ) {
                    val preview = try {
                        sdkRoom.previewRoom(via = serverNames)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get room preview for $roomId")
                        return@recordTransaction null
                    }

                    GetRoomResult.NotJoined(
                        NotJoinedRustRoom(
                            sessionId = sessionId,
                            localRoom = getBaseRoom(sdkRoom, roomInfo),
                            previewInfo = RoomPreviewInfoMapper.map(preview.info()),
                        )
                    )
                }
            }
        }
        Timber.tag("YiZheTimelinePerf").d(
            "stage=room_open duration_ms=%d",
            (System.nanoTime() - roomOpenStartedAt) / 1_000_000L,
        )
        result
    }

    /**
     * Get the Rust room for a room, retrying after the room list is loaded if necessary.
     */
    private suspend fun awaitRoomInRoomList(roomId: RoomId): Room? {
        // The widget driver sends encrypted RTC membership through Room.client. Resolve the Room
        // from this RustMatrixClient's Client cache so the room and sender always share the same
        // BaseClient. RoomListService can still expose a list entry while the corresponding client
        // cache entry is unavailable, which makes Rust fail locally with InsufficientData before
        // any Matrix request is sent.
        var sdkRoom = innerClient.getRoom(roomId.value)
        if (sdkRoom == null) {
            // ... otherwise, lets wait for the SS to load all rooms and check again.
            roomListService.allRooms.awaitLoaded()
            sdkRoom = innerClient.getRoom(roomId.value)
        }

        if (sdkRoom == null) {
            Timber.d("Room not found for $roomId")
            return null
        }

        return sdkRoom
    }
}

sealed interface GetRoomResult {
    data class Joined(val joinedRoom: JoinedRoom) : GetRoomResult
    data class NotJoined(val notJoinedRoom: NotJoinedRustRoom) : GetRoomResult

    val room: BaseRoom?
        get() = when (this) {
            is Joined -> joinedRoom
            is NotJoined -> notJoinedRoom.localRoom
        }
}
