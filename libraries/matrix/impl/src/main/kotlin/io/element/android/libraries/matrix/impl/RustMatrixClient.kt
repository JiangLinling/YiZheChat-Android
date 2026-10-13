/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import io.element.android.libraries.androidutils.file.getSizeOfFiles
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.core.data.bytes
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.extensions.mapFailure
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.HomeserverCapabilitiesProvider
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.analytics.SdkStoreSizes
import io.element.android.libraries.matrix.api.auth.OpenIdToken
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.exception.AccountDeactivationException
import io.element.android.libraries.matrix.api.linknewdevice.LinkDesktopHandler
import io.element.android.libraries.matrix.api.linknewdevice.LinkMobileHandler
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.oauth.AccountManagementAction
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.NotJoinedRoom
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipObserver
import io.element.android.libraries.matrix.api.room.alias.ResolvedRoomAlias
import io.element.android.libraries.matrix.api.room.history.RoomHistoryVisibility
import io.element.android.libraries.matrix.api.room.join.JoinRule
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.spaces.SpaceService
import io.element.android.libraries.matrix.api.sync.SlidingSyncVersion
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.api.user.MatrixSearchUserResults
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.impl.encryption.RustEncryptionService
import io.element.android.libraries.matrix.impl.exception.mapClientException
import io.element.android.libraries.matrix.impl.linknewdevice.RustLinkDesktopHandler
import io.element.android.libraries.matrix.impl.linknewdevice.RustLinkMobileHandler
import io.element.android.libraries.matrix.impl.linknewdevice.RustQrCodeDataParser
import io.element.android.libraries.matrix.impl.mapper.map
import io.element.android.libraries.matrix.impl.media.DisabledMatrixProtectedMediaService
import io.element.android.libraries.matrix.impl.media.MatrixProtectedMediaService
import io.element.android.libraries.matrix.impl.media.RustMediaLoader
import io.element.android.libraries.matrix.impl.media.RustMediaPreviewService
import io.element.android.libraries.matrix.impl.media.SecureMatrixMediaLoader
import io.element.android.libraries.matrix.impl.notification.RustNotificationService
import io.element.android.libraries.matrix.impl.notificationsettings.RustNotificationSettingsService
import io.element.android.libraries.matrix.impl.oauth.toRustAction
import io.element.android.libraries.matrix.impl.pushers.RustPushersService
import io.element.android.libraries.matrix.impl.room.GetRoomResult
import io.element.android.libraries.matrix.impl.room.NotJoinedRustRoom
import io.element.android.libraries.matrix.impl.room.RoomContentForwarder
import io.element.android.libraries.matrix.impl.room.RoomInfoMapper
import io.element.android.libraries.matrix.impl.room.RoomSyncSubscriber
import io.element.android.libraries.matrix.impl.room.RustRoomFactory
import io.element.android.libraries.matrix.impl.room.TimelineEventFilterFactory
import io.element.android.libraries.matrix.impl.room.history.map
import io.element.android.libraries.matrix.impl.room.join.map
import io.element.android.libraries.matrix.impl.room.location.map
import io.element.android.libraries.matrix.impl.room.preview.RoomPreviewInfoMapper
import io.element.android.libraries.matrix.impl.roomdirectory.RustRoomDirectoryService
import io.element.android.libraries.matrix.impl.roomdirectory.map
import io.element.android.libraries.matrix.impl.roomlist.RoomListFactory
import io.element.android.libraries.matrix.impl.roomlist.createPreviewIdentityTimeline
import io.element.android.libraries.matrix.impl.roomlist.RustRoomListService
import io.element.android.libraries.matrix.impl.roomlist.roomOrNull
import io.element.android.libraries.matrix.impl.spaces.RustSpaceService
import io.element.android.libraries.matrix.impl.sync.RustSyncService
import io.element.android.libraries.matrix.impl.sync.map
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixContentProtectionRuntime
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.usersearch.UserSearchResultMapper
import io.element.android.libraries.matrix.impl.util.SessionPathsProvider
import io.element.android.libraries.matrix.impl.util.cancelAndDestroy
import io.element.android.libraries.matrix.impl.util.mxCallbackFlow
import io.element.android.libraries.matrix.impl.verification.RustSessionVerificationService
import io.element.android.libraries.matrix.impl.workmanager.PerformDatabaseVacuumRequestBuilder
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.workmanager.api.WorkManagerRequestType
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingMatrixException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingGlobalDestroyException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionPreparationExpiredException
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.AuthDataPasswordDetails
import org.matrix.rustcomponents.sdk.BeaconInfoListener
import org.matrix.rustcomponents.sdk.BeaconInfoUpdate
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.IgnoredUsersListener
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.SendQueueRoomErrorListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber
import java.io.File
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.element.android.security.securestorage.api.accountIdHash as hashAccountId
import org.matrix.rustcomponents.sdk.CreateRoomParameters as RustCreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset as RustRoomPreset
import org.matrix.rustcomponents.sdk.SyncService as ClientSyncService

private const val PERMANENT_DELETION_COMMIT_ATTEMPTS = 60
private const val PERMANENT_DELETION_PROPAGATION_RETRY_MILLIS = 1_000L
private val joinedRoomsResponseJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class JoinedRoomsResponse(
    @SerialName("joined_rooms") val joinedRooms: List<String>,
)

@Suppress("LargeClass")
class RustMatrixClient(
    private val innerClient: Client,
    private val sessionStore: SessionStore,
    private val sessionDelegate: RustClientSessionDelegate,
    private val innerSyncService: ClientSyncService,
    appCoroutineScope: CoroutineScope,
    dispatchers: CoroutineDispatchers,
    baseCacheDirectory: File,
    clock: SystemClock,
    timelineEventFilterFactory: TimelineEventFilterFactory,
    private val featureFlagService: FeatureFlagService,
    private val analyticsService: AnalyticsService,
    private val workManagerScheduler: WorkManagerScheduler,
    sensitiveFileStore: SensitiveFileStore,
    secureMediaLeaseManager: SecureMediaLeaseManager,
    secureFileStoreEnabled: Boolean,
    private val dataDeletionCoordinator: DataDeletionCoordinator,
    private val permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val deviceRootKeyService: DeviceRootKeyService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime = DisabledMatrixContentProtectionRuntime,
    private val matrixProtectedContentAdapterFactory: MatrixProtectedContentAdapterFactory = DisabledMatrixProtectedContentAdapterFactory,
    private val matrixProtectedMediaService: MatrixProtectedMediaService = DisabledMatrixProtectedMediaService,
    private val managementControlService: io.element.android.security.dataprotection.api.ManagementControlService? = null,
) : MatrixClient {
    override val sessionId: UserId = UserId(innerClient.userId())
    override val deviceId: DeviceId = DeviceId(innerClient.deviceId())
    private val accountVaultId = deriveAccountVaultId(sessionId.value, deviceId.value)
    override val sessionCoroutineScope = appCoroutineScope.childScope(dispatchers.main, "Matrix session")
    private val sessionDispatcher = dispatchers.io.limitedParallelism(64)
    private val authenticatedApiHttpClient = OkHttpClient()
    private val ephemeralMediaHttpClient = authenticatedApiHttpClient.newBuilder()
        .cache(null).followRedirects(false).followSslRedirects(false).build()
    private val matrixProtectionAttached = AtomicBoolean(true)

    private val innerRoomListService = innerSyncService.roomListService()

    // TODO refactor this and `innerNotificationClient` to be behind a suspend function instead
    private val innerSpaceService = runBlocking { innerClient.spaceService() }

    override val roomMembershipObserver = RoomMembershipObserver()

    override val syncService = RustSyncService(
        inner = innerSyncService,
        dispatcher = sessionDispatcher,
        sessionCoroutineScope = sessionCoroutineScope
    )
    override val pushersService = RustPushersService(
        client = innerClient,
        dispatchers = dispatchers,
    )
    private val notificationProcessSetup = NotificationProcessSetup.SingleProcess(innerSyncService)
    private val innerNotificationClient = runBlocking { innerClient.notificationClient(notificationProcessSetup) }
    override val notificationService = RustNotificationService(
        sessionId = sessionId,
        deviceId = deviceId,
        notificationClient = innerNotificationClient,
        dispatchers = dispatchers,
        clock = clock,
        protectedContentAdapterFactory = matrixProtectedContentAdapterFactory,
    )
    override val notificationSettingsService = RustNotificationSettingsService(innerClient, sessionCoroutineScope, dispatchers)
    override val encryptionService = RustEncryptionService(
        client = innerClient,
        syncService = syncService,
        sessionCoroutineScope = sessionCoroutineScope,
        dispatchers = dispatchers,
    )

    override val roomDirectoryService = RustRoomDirectoryService(
        client = innerClient,
        sessionDispatcher = sessionDispatcher,
    )

    private val sessionPathsProvider = SessionPathsProvider(sessionStore)

    private val roomSyncSubscriber: RoomSyncSubscriber = RoomSyncSubscriber(innerRoomListService, dispatchers)

    override val roomListService: RoomListService = RustRoomListService(
        innerRoomListService = innerRoomListService,
        sessionCoroutineScope = sessionCoroutineScope,
        sessionDispatcher = sessionDispatcher,
        roomListFactory = RoomListFactory(
            innerRoomListService = innerRoomListService,
            analyticsService = analyticsService,
            sessionId = sessionId,
            deviceId = deviceId,
            protectedContentAdapterFactory = matrixProtectedContentAdapterFactory,
            previewTimelines = io.element.android.libraries.matrix.impl.roomlist.RoomPreviewTimelineCache(sessionCoroutineScope) { roomId ->
                innerClient.getRoom(roomId.value)?.use { room ->
                    room.createPreviewIdentityTimeline()
                }
            },
        ),
        roomSyncSubscriber = roomSyncSubscriber,
    )

    override val spaceService: SpaceService = RustSpaceService(
        innerSpaceService = innerSpaceService,
        roomMembershipObserver = roomMembershipObserver,
        sessionCoroutineScope = sessionCoroutineScope,
        sessionDispatcher = sessionDispatcher,
        analyticsService = analyticsService,
    )

    override val ownBeaconInfoUpdates = mxCallbackFlow {
        val listener = object : BeaconInfoListener {
            override fun onUpdate(update: BeaconInfoUpdate) {
                trySend(update.map())
            }
        }
        innerClient.subscribeToOwnBeaconInfoUpdates(listener)
    }

    override val sessionVerificationService = RustSessionVerificationService(
        client = innerClient,
        isSyncServiceReady = syncService.syncState.map { it == SyncState.Running },
        sessionCoroutineScope = sessionCoroutineScope,
    )

    private val roomInfoMapper = RoomInfoMapper()

    private val membershipLifecycle = managementControlService?.let {
        io.element.android.libraries.matrix.impl.room.RoomMembershipLifecycle(
            sessionId.value, deviceId.value, it, appOperationalStateCoordinator,
            isProjected = { roomId, target, action ->
                innerClient.getRoom(roomId)?.use { room ->
                    if (target == sessionId.value) {
                        room.membership() == if (action == "join") Membership.JOINED else Membership.LEFT
                    } else {
                        val expected = if (action == "ban") org.matrix.rustcomponents.sdk.MembershipState.Ban
                            else org.matrix.rustcomponents.sdk.MembershipState.Leave
                        room.member(target).membership == expected
                    }
                } == true
            },
        ).also { lifecycle -> lifecycle.startRecovery(sessionCoroutineScope) }
    }

    private val roomFactory = RustRoomFactory(
        roomListService = roomListService,
        innerClient = innerClient,
        sessionId = sessionId,
        deviceId = deviceId,
        notificationSettingsService = notificationSettingsService,
        sessionCoroutineScope = sessionCoroutineScope,
        dispatchers = dispatchers,
        systemClock = clock,
        roomContentForwarder = RoomContentForwarder(
            roomListService = innerRoomListService,
            sessionId = sessionId,
            deviceId = deviceId,
            protectedContentAdapterFactory = matrixProtectedContentAdapterFactory,
            matrixMediaLoader = { matrixMediaLoader },
            cacheDirectory = baseCacheDirectory,
        ),
        roomSyncSubscriber = roomSyncSubscriber,
        timelineEventFilterFactory = timelineEventFilterFactory,
        membershipLifecycle = membershipLifecycle,
        roomMembershipObserver = roomMembershipObserver,
        roomInfoMapper = roomInfoMapper,
        featureFlagService = featureFlagService,
        analyticsService = analyticsService,
        matrixProtectedContentAdapterFactory = matrixProtectedContentAdapterFactory,
    )

    private val rustMediaLoader = RustMediaLoader(
        baseCacheDirectory = baseCacheDirectory,
        dispatchers = dispatchers,
        innerClient = innerClient,
        ephemeralMediaContent = { source ->
            val storedSession = sessionStore.getSession(sessionId.value)
                ?: throw SecurityException("Matrix session is unavailable.")
            check(storedSession.isTokenValid) { "Matrix session token is unavailable." }
            // SessionStore holds Vault envelopes. Only the current SDK session has runtime credentials.
            val session = innerClient.session()
            check(session.userId == sessionId.value && session.deviceId == deviceId.value &&
                session.homeserverUrl.trimEnd('/') == storedSession.homeserverUrl.trimEnd('/') &&
                session.accessToken.isNotBlank()) { "Matrix runtime session does not match the active account." }
            io.element.android.libraries.matrix.impl.media.loadUncachedEncryptedMatrixContent(
                source, session.homeserverUrl, session.accessToken, ephemeralMediaHttpClient,
            )
        },
    )
    override val matrixMediaLoader: MatrixMediaLoader = if (secureFileStoreEnabled) {
        SecureMatrixMediaLoader(
            delegate = rustMediaLoader,
            vaultId = accountVaultId,
            sensitiveFileStore = sensitiveFileStore,
            secureMediaLeaseManager = secureMediaLeaseManager,
            sessionCoroutineScope = sessionCoroutineScope,
            clock = clock,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            protectedMediaService = matrixProtectedMediaService,
            accountIdHash = hashAccountId(sessionId.value),
            matrixDeviceIdHash = hashAccountId(deviceId.value),
            requireInnerProtection = { dataProtectionPolicyProvider.current().policy.requireContentEnvelope },
            downloadDispatcher = sessionDispatcher,
            cleanupRetryScope = sessionCoroutineScope,
            progressiveVideoEnabled = { featureFlagService.isFeatureEnabled(FeatureFlags.ProgressiveProtectedVideoPlayback) },
            progressiveVideoAcquirer = { source, wireLength ->
                val stored = sessionStore.getSession(sessionId.value)
                    ?: throw SecurityException("Matrix session is unavailable.")
                check(stored.isTokenValid)
                val session = innerClient.session()
                check(session.userId == sessionId.value && session.deviceId == deviceId.value &&
                    session.homeserverUrl.trimEnd('/') == stored.homeserverUrl.trimEnd('/') && session.accessToken.isNotBlank())
                io.element.android.libraries.matrix.impl.media.openProgressiveProtectedVideo(
                    source, wireLength, session.homeserverUrl, session.accessToken, ephemeralMediaHttpClient,
                    matrixProtectedMediaService, accountVaultId, hashAccountId(sessionId.value),
                    fullDownload = {
                        matrixMediaLoader.downloadMediaFile(source, "video/mp4", filename = null, useCache = false).getOrThrow()
                    },
                )
            },
        )
    } else {
        rustMediaLoader
    }

    override val mediaPreviewService = RustMediaPreviewService(
        sessionCoroutineScope = sessionCoroutineScope,
        innerClient = innerClient,
        sessionDispatcher = sessionDispatcher,
    )

    private var clientDelegateTaskHandle: TaskHandle? = innerClient.setDelegate(sessionDelegate)

    private val _userProfile: MutableStateFlow<MatrixUser> = MutableStateFlow(
        MatrixUser(
            userId = sessionId,
            displayName = null,
            avatarUrl = null,
        )
    )

    override val userProfile: StateFlow<MatrixUser> = _userProfile

    override val ignoredUsersFlow = mxCallbackFlow<ImmutableList<UserId>> {
        // Fetch the initial value manually, the SDK won't return it automatically
        channel.trySend(innerClient.ignoredUsers().map(::UserId).toImmutableList())

        innerClient.subscribeToIgnoredUsers(object : IgnoredUsersListener {
            override fun call(ignoredUserIds: List<String>) {
                channel.trySend(ignoredUserIds.map(::UserId).toImmutableList())
            }
        })
    }
        .buffer(Channel.UNLIMITED)
        .stateIn(sessionCoroutineScope, started = SharingStarted.Eagerly, initialValue = persistentListOf())

    init {
        matrixContentProtectionRuntime.attach()
        // Make sure the session delegate has a reference to the client to be able to logout on auth error
        sessionDelegate.bindClient(this)

        sessionCoroutineScope.launch {
            // Start notification settings
            notificationSettingsService.start()

            // Update the user profile in the session store if needed
            sessionStore.getSession(sessionId.value)?.let { sessionData ->
                _userProfile.emit(
                    MatrixUser(
                        userId = sessionId,
                        displayName = sessionData.userDisplayName,
                        avatarUrl = sessionData.userAvatarUrl,
                    )
                )
            }
            // Force a refresh of the profile
            getUserProfile()
        }

        // Schedule regular database vacuuming to ensure DB performance remains optimal
        scheduleDatabaseVacuum()
    }

    override fun userIdServerName(): String {
        return runCatchingExceptions {
            innerClient.userIdServerName()
        }
            .onFailure {
                Timber.w(it, "Failed to get userIdServerName")
            }
            .getOrNull()
            ?: sessionId.value.substringAfter(":")
    }

    override suspend fun getUrl(url: String): Result<ByteArray> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.getUrl(url)
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun recoverySessionAccessToken(): Result<String> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            val stored = sessionStore.getSession(sessionId.value)
                ?: throw SecurityException("RECOVERY_MATRIX_SESSION_UNAVAILABLE")
            val runtime = innerClient.session()
            check(stored.isTokenValid && stored.userId == sessionId.value && stored.deviceId == deviceId.value &&
                runtime.userId == stored.userId && runtime.deviceId == stored.deviceId &&
                runtime.homeserverUrl.trimEnd('/') == stored.homeserverUrl.trimEnd('/')) {
                "RECOVERY_MATRIX_SESSION_UNAVAILABLE"
            }
            runtime.accessToken.also {
                check(it.length in 16..8192 && '\r' !in it && '\n' !in it && !it.startsWith("yzvault1:")) {
                    "RECOVERY_MATRIX_SESSION_UNAVAILABLE"
                }
            }
        }
    }

    override suspend fun requestOpenIdToken(): Result<OpenIdToken> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.requestOpenidToken().let {
                OpenIdToken(
                    accessToken = it.accessToken,
                    tokenType = it.tokenType,
                    matrixServerName = it.matrixServerName,
                    expiresInSeconds = it.expiresInSeconds.toLong(),
                )
            }
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun getCurrentRoomInfo(roomId: RoomId) = withContext(sessionDispatcher) {
        runCatchingExceptions {
            checkNotNull(innerClient.getRoom(roomId.value)) { "Local SDK room is unavailable" }.use { room ->
                roomInfoMapper.map(room.roomInfo()).also { check(it.id == roomId) }
            }
        }
    }

    override suspend fun getRoom(roomId: RoomId): BaseRoom? = withContext(sessionDispatcher) {
        roomFactory.getBaseRoom(roomId)
    }

    override suspend fun getJoinedRoom(roomId: RoomId): JoinedRoom? = withContext(sessionDispatcher) {
        (roomFactory.getJoinedRoomOrPreview(roomId, emptyList()) as? GetRoomResult.Joined)?.joinedRoom
    }

    /**
     * Wait for the room to be available in the client with the correct membership for the current user.
     * @param roomId the room id to wait for
     * @param timeout the timeout to wait for the room to be available
     * @param currentUserMembership the membership to wait for
     * @throws TimeoutCancellationException if the room is not available after the timeout
     */
    private suspend fun awaitRoom(
        roomId: RoomId,
        timeout: Duration,
        currentUserMembership: CurrentUserMembership,
    ): RoomInfo {
        return withTimeout(timeout) {
            getRoomInfoFlow(roomId)
                .mapNotNull { roomInfo -> roomInfo.getOrNull() }
                .first { info -> info.currentUserMembership == currentUserMembership }
                // Ensure that the room is ready
                .also { innerClient.awaitRoomRemoteEcho(roomId.value).destroy() }
        }
    }

    override suspend fun findDM(userId: UserId): Result<RoomId?> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            if (managementControlService?.supportsManagedRoomCreation == true) {
                managedDirectRooms().execute(userId.value, false)?.let(::RoomId)
            } else innerClient.getDmRoom(userId.value)?.use { RoomId(it.id()) }
        }
    }

    override suspend fun getJoinedRoomIds(): Result<Set<RoomId>> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.rooms()
                .filter { it.membership() == Membership.JOINED }
                .map { RoomId(it.id()) }
                .toSet()
        }
    }

    override suspend fun getRemoteJoinedRoomIds(): Result<Set<RoomId>> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            val session = innerClient.session()
            val request = Request.Builder()
                .url("${session.homeserverUrl.trimEnd('/')}/_matrix/client/v3/joined_rooms")
                .header("Authorization", "Bearer ${session.accessToken}")
                .get()
                .build()
            authenticatedApiHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "joined_rooms request failed with HTTP ${response.code}" }
                joinedRoomsResponseJson.decodeFromString<JoinedRoomsResponse>(response.body.bytes().decodeToString())
                    .joinedRooms
                    .mapTo(linkedSetOf(), ::RoomId)
            }
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun ignoreUser(userId: UserId): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.ignoreUser(userId.value)
        }
    }

    override suspend fun unignoreUser(userId: UserId): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.unignoreUser(userId.value)
        }
    }

    override suspend fun createRoom(createRoomParams: CreateRoomParameters): Result<RoomId> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            check(!(managementControlService?.supportsManagedRoomCreation == true && createRoomParams.isDirect)) {
                "Managed direct rooms require the original LC11 invitation operation"
            }
            val hasPublicAccess = createRoomParams.preset == RoomPreset.PUBLIC_CHAT || createRoomParams.joinRuleOverride == JoinRule.Public
            val powerLevels = defaultRoomCreationPowerLevels(isSpace = createRoomParams.isSpace, isPublic = hasPublicAccess)

            val rustParams = RustCreateRoomParameters(
                name = createRoomParams.name,
                topic = createRoomParams.topic,
                isEncrypted = createRoomParams.isEncrypted,
                isDirect = createRoomParams.isDirect,
                visibility = createRoomParams.visibility.map(),
                preset = when (createRoomParams.preset) {
                    RoomPreset.PRIVATE_CHAT -> RustRoomPreset.PRIVATE_CHAT
                    RoomPreset.TRUSTED_PRIVATE_CHAT -> RustRoomPreset.TRUSTED_PRIVATE_CHAT
                    RoomPreset.PUBLIC_CHAT -> RustRoomPreset.PUBLIC_CHAT
                },
                invite = createRoomParams.invite?.map { it.value },
                avatar = createRoomParams.avatar,
                powerLevelContentOverride = powerLevels.copy(
                    invite = if (createRoomParams.joinRuleOverride == JoinRule.Knock) {
                        // override the invite power level so it's the same as kick.
                        RoomMember.Role.Moderator.powerLevel.toInt()
                    } else {
                        powerLevels.invite
                    }
                ),
                joinRuleOverride = createRoomParams.joinRuleOverride?.map(),
                historyVisibilityOverride = createRoomParams.historyVisibilityOverride?.map(),
                canonicalAlias = createRoomParams.roomAliasName.getOrNull(),
                isSpace = createRoomParams.isSpace,
            )
            val roomId = RoomId(innerClient.createRoom(rustParams))
            // Wait to receive the room back from the sync but do not returns failure if it fails.
            try {
                awaitRoom(roomId, 30.seconds, CurrentUserMembership.JOINED)
            } catch (e: Exception) {
                Timber.e(e, "Timeout waiting for the room to be available in the room list")
            }
            roomId
        }
    }

    override suspend fun createDM(userId: UserId): Result<RoomId> {
        if (managementControlService?.supportsManagedRoomCreation == true) return withContext(sessionDispatcher) {
            runCatchingExceptions {
                RoomId(checkNotNull(managedDirectRooms().execute(userId.value, true)))
            }
        }
        val createRoomParams = CreateRoomParameters(
            name = null,
            isEncrypted = true,
            isDirect = true,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
            historyVisibilityOverride = RoomHistoryVisibility.Invited,
            invite = listOf(userId),
        )
        return createRoom(createRoomParams)
    }

    private fun managedDirectRooms() = ManagedDirectRoomLifecycle(checkNotNull(managementControlService),
        appOperationalStateCoordinator, hashAccountId(sessionId.value), hashAccountId(deviceId.value)) { roomId ->
        withTimeout(30.seconds) {
            while (innerClient.getRoom(roomId)?.use { true } != true) delay(100)
        }
    }

    override suspend fun getProfile(userId: UserId): Result<MatrixUser> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.getProfile(userId.value).map()
        }
    }

    override suspend fun getUserProfile(): Result<MatrixUser> = getProfile(sessionId)
        .onSuccess { matrixUser ->
            _userProfile.emit(matrixUser)
            // Also update our session storage
            sessionStore.updateUserProfile(
                sessionId = sessionId.value,
                displayName = matrixUser.displayName,
                avatarUrl = matrixUser.avatarUrl,
            )
        }

    override suspend fun searchUsers(searchTerm: String, limit: Long): Result<MatrixSearchUserResults> =
        withContext(sessionDispatcher) {
            runCatchingExceptions {
                innerClient.searchUsers(searchTerm, limit.toULong()).let(UserSearchResultMapper::map)
            }
        }

    override suspend fun setDisplayName(displayName: String): Result<Unit> =
        withContext(sessionDispatcher) {
            runCatchingExceptions { innerClient.setDisplayName(displayName) }
        }

    override suspend fun uploadAvatar(mimeType: String, data: ByteArray): Result<Unit> =
        withContext(sessionDispatcher) {
            runCatchingExceptions { innerClient.uploadAvatar(mimeType, data) }
        }

    override suspend fun removeAvatar(): Result<Unit> =
        withContext(sessionDispatcher) {
            runCatchingExceptions { innerClient.removeAvatar() }
        }

    override suspend fun joinRoom(roomId: RoomId): Result<RoomInfo?> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            val join: suspend () -> Unit = { innerClient.joinRoomById(roomId.value).destroy() }
            membershipLifecycle?.perform(roomId.value, sessionId.value, "join", join) ?: join()
            try {
                awaitRoom(roomId, 10.seconds, CurrentUserMembership.JOINED)
            } catch (e: Exception) {
                Timber.e(e, "Timeout waiting for the room to be available in the room list")
                null
            }
        }
    }.mapFailure { it.mapClientException() }

    override suspend fun joinRoomByIdOrAlias(roomIdOrAlias: RoomIdOrAlias, serverNames: List<String>): Result<RoomInfo?> = withContext(sessionDispatcher) {
        if (membershipLifecycle != null && io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
            return@withContext runCatchingExceptions {
                val resolved = when (roomIdOrAlias) {
                    is RoomIdOrAlias.Id -> roomIdOrAlias.roomId
                    is RoomIdOrAlias.Alias -> RoomId(checkNotNull(innerClient.resolveRoomAlias(roomIdOrAlias.roomAlias.value)).roomId)
                }
                joinRoom(resolved).getOrThrow()
            }
        }
        runCatchingExceptions {
            val roomId = innerClient.joinRoomByIdOrAlias(
                roomIdOrAlias = roomIdOrAlias.identifier,
                serverNames = serverNames,
            ).use {
                RoomId(it.id())
            }
            try {
                awaitRoom(roomId, 10.seconds, CurrentUserMembership.JOINED)
            } catch (e: Exception) {
                Timber.e(e, "Timeout waiting for the room to be available in the room list")
                null
            }
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun knockRoom(roomIdOrAlias: RoomIdOrAlias, message: String, serverNames: List<String>): Result<RoomInfo?> = withContext(
        sessionDispatcher
    ) {
        runCatchingExceptions {
            val roomId = innerClient.knock(roomIdOrAlias.identifier, message, serverNames).use {
                RoomId(it.id())
            }
            try {
                awaitRoom(roomId, 10.seconds, CurrentUserMembership.KNOCKED)
            } catch (e: Exception) {
                Timber.e(e, "Timeout waiting for the room to be available in the room list")
                null
            }
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun trackRecentlyVisitedRoom(roomId: RoomId): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.trackRecentlyVisitedRoom(roomId.value)
        }
    }

    override suspend fun getRecentlyVisitedRooms(): Result<List<RoomId>> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.getRecentlyVisitedRooms().map(::RoomId)
        }
    }

    override suspend fun resolveRoomAlias(roomAlias: RoomAlias): Result<Optional<ResolvedRoomAlias>> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            val result = innerClient.resolveRoomAlias(roomAlias.value)?.let {
                ResolvedRoomAlias(
                    roomId = RoomId(it.roomId),
                    servers = it.servers,
                )
            }
            Optional.ofNullable(result)
        }
    }

    override suspend fun getRoomPreview(roomIdOrAlias: RoomIdOrAlias, serverNames: List<String>): Result<NotJoinedRoom> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            when (roomIdOrAlias) {
                is RoomIdOrAlias.Alias -> {
                    val roomId = innerClient.resolveRoomAlias(roomIdOrAlias.roomAlias.value)?.roomId?.let { RoomId(it) }

                    var room = (roomId?.let { roomFactory.getJoinedRoomOrPreview(it, serverNames) } as? GetRoomResult.NotJoined)?.notJoinedRoom
                    if (room == null) {
                        val preview = innerClient.getRoomPreviewFromRoomAlias(roomIdOrAlias.roomAlias.value)
                        room = NotJoinedRustRoom(sessionId, null, RoomPreviewInfoMapper.map(preview.info()))
                    }
                    room
                }
                is RoomIdOrAlias.Id -> {
                    var room = (roomFactory.getJoinedRoomOrPreview(roomIdOrAlias.roomId, serverNames) as? GetRoomResult.NotJoined)?.notJoinedRoom

                    if (room == null) {
                        val preview = innerClient.getRoomPreviewFromRoomId(roomIdOrAlias.roomId.value, serverNames)
                        room = NotJoinedRustRoom(sessionId, null, RoomPreviewInfoMapper.map(preview.info()))
                    }
                    room
                }
            }
        }.mapFailure { it.mapClientException() }
    }

    internal suspend fun destroy() {
        matrixContentProtectionRuntime.clearSensitiveCaches()
        if (matrixProtectionAttached.compareAndSet(true, false)) {
            matrixContentProtectionRuntime.detach()
        }
        innerNotificationClient.close()

        roomFactory.destroy()
        syncService.destroy()
        notificationSettingsService.destroy()
        notificationProcessSetup.destroy()

        sessionCoroutineScope.cancel()
        clientDelegateTaskHandle?.cancelAndDestroy()
        sessionVerificationService.destroy()

        sessionDelegate.clearCurrentClient()
        innerRoomListService.close()
        innerSpaceService.close()
        notificationService.close()
        encryptionService.close()
        innerClient.close()
    }

    override suspend fun getCacheSize(): Long {
        return getCacheSize(includeCryptoDb = false)
    }

    override suspend fun getDatabaseSizes(): Result<SdkStoreSizes> = runCatchingExceptions {
        innerClient.getStoreSizes().run {
            SdkStoreSizes(
                stateStore = stateStore?.bytes,
                eventCacheStore = eventCacheStore?.bytes,
                mediaStore = mediaStore?.bytes,
                cryptoStore = cryptoStore?.bytes,
            )
        }
    }

    override suspend fun clearCache() {
        innerClient.clearCaches(innerSyncService)
        destroy()
    }

    override suspend fun logout(userInitiated: Boolean, ignoreSdkError: Boolean) {
        // Remove current delegate so we don't receive an auth error
        detachSessionDelegate()
        withContext(sessionDispatcher) {
            val removalScope = if (userInitiated) {
                val installationId = deviceRootKeyService.ensureReady().installationId
                val accountHash = hashAccountId(sessionId.value)
                val matrixDeviceIdHash = sha256Hex(deviceId.value)
                check(
                    accountReactivationScopeStore.markAccountRemoved(
                        accountIdHash = accountHash,
                        installationId = installationId,
                        matrixDeviceIdHash = matrixDeviceIdHash,
                    )
                ) { "Unable to persist the account reactivation boundary." }
                Triple(accountHash, installationId, matrixDeviceIdHash)
            } else {
                null
            }
            if (userInitiated) {
                try {
                    innerClient.logout()
                } catch (failure: Throwable) {
                    if (ignoreSdkError) {
                        Timber.e(failure, "Fail to call logout on HS. Still delete local files.")
                    } else {
                        requireNotNull(removalScope).let { (accountHash, installationId, matrixDeviceIdHash) ->
                            check(
                                accountReactivationScopeStore.restoreAccountActive(
                                    accountIdHash = accountHash,
                                    installationId = installationId,
                                    matrixDeviceIdHash = matrixDeviceIdHash,
                                )
                            ) { "Unable to roll back the account reactivation boundary." }
                        }
                        // If the logout failed we need to restore the delegate without hiding the logout error.
                        restoreSessionDelegate(failure)
                        Timber.e(failure, "Fail to call logout on HS.")
                        throw failure
                    }
                }
            }
            val deletionRequest = if (userInitiated) prepareProtectedAccountDeletion(DeletionReason.ACCOUNT_LOGOUT) else null
            destroy()

            if (userInitiated) {
                completeProtectedAccountDeletion(requireNotNull(deletionRequest))
            }
        }
    }

    override fun canDeactivateAccount(): Boolean {
        return runCatchingExceptions {
            innerClient.canDeactivateAccount()
        }
            .getOrNull()
            .orFalse()
    }

    override suspend fun deactivateAccount(password: String, eraseData: Boolean): Result<Unit> = withContext(sessionDispatcher) {
        Timber.w("Deactivating account")
        runCatchingExceptions {
            // Remove current delegate so we don't receive an auth error.
            detachSessionDelegate()
            val accountHash = hashAccountId(sessionId.value)
            var deletionHandle = permanentAccountDeletionCoordinator.pending(accountHash)
            if (deletionHandle != null) {
                val resumed = permanentAccountDeletionCoordinator.commitRemote(deletionHandle)
                if (resumed.isSuccess) {
                    finishPermanentlyDeletedAccount(deletionHandle)
                    return@runCatchingExceptions
                }
                when (val failure = resumed.exceptionOrNull()) {
                    is PermanentAccountDeletionAwaitingMatrixException,
                    is PermanentAccountDeletionAwaitingGlobalDestroyException -> Unit
                    // PREPARED already means FROZEN. Keep the durable operation fail-closed even if
                    // an older server reports expiry; it must be recovered under the same identity.
                    is PermanentAccountDeletionPreparationExpiredException -> throw failure
                    else -> throw requireNotNull(failure)
                }
            }
            if (deletionHandle == null) {
                deletionHandle = permanentAccountDeletionCoordinator.prepare(
                    localDeletionRequest = buildProtectedAccountDeletionRequest(DeletionReason.USER_REQUEST),
                    eraseData = eraseData,
                ).getOrThrow()
            }
            var matrixDeactivated = false
            // First call without AuthData, should fail
            try {
                val firstAttempt = runCatchingExceptions {
                    innerClient.deactivateAccount(
                        authData = null,
                        eraseData = eraseData,
                    )
                }
                if (firstAttempt.isFailure) {
                    Timber.w(firstAttempt.exceptionOrNull(), "Expected failure, try again")
                    // This is expected, try again with the password
                    runCatchingExceptions {
                        innerClient.deactivateAccount(
                            authData = AuthData.Password(
                                passwordDetails = AuthDataPasswordDetails(
                                    identifier = sessionId.value,
                                    password = password,
                                ),
                            ),
                            eraseData = eraseData,
                        )
                    }.getOrThrow()
                }
                matrixDeactivated = true
            } catch (failure: Throwable) {
                if (!matrixDeactivated) {
                    // prepare() has already frozen the account. Preserve its confirmation secret and
                    // operation identity so a corrected password resumes the exact same deletion.
                    val reportedFailure = failure.toAccountDeactivationFailure()
                    restoreSessionDelegate(reportedFailure)
                    throw reportedFailure
                }
                throw failure
            }

            commitPermanentDeletionWithPropagationRetry(deletionHandle)
            finishPermanentlyDeletedAccount(deletionHandle)
        }.onFailure {
            Timber.e(it, "Failed to deactivate account")
        }
    }

    private fun detachSessionDelegate() {
        // The delegate registration is owned by its TaskHandle. Calling setDelegate(null) while
        // that registration is active is rejected by the Rust SDK as "Delegate already initialized".
        val previousHandle = clientDelegateTaskHandle
        clientDelegateTaskHandle = null
        previousHandle?.cancelAndDestroy()
    }

    private fun restoreSessionDelegate(primaryFailure: Throwable) {
        runCatchingExceptions { innerClient.setDelegate(sessionDelegate) }
            .onSuccess { clientDelegateTaskHandle = it }
            .onFailure { restorationFailure ->
                primaryFailure.addSuppressed(restorationFailure)
                Timber.e(restorationFailure, "Failed to restore Matrix session delegate after account action failure.")
            }
    }

    private fun Throwable.toAccountDeactivationFailure(): Throwable {
        return if (
            this is ClientException.Generic &&
            listOfNotNull(msg, details, message, cause?.message).any { errorText ->
                errorText.contains("Invalid username or password", ignoreCase = true) ||
                    errorText.contains("Invalid password", ignoreCase = true)
            }
        ) {
            AccountDeactivationException.InvalidCredentials(this)
        } else {
            this
        }
    }

    internal suspend fun deleteProtectedAccountData(reason: DeletionReason) {
        val request = prepareProtectedAccountDeletion(reason)
        completeProtectedAccountDeletion(request)
    }

    private suspend fun prepareProtectedAccountDeletion(reason: DeletionReason): DeletionRequest {
        val request = buildProtectedAccountDeletionRequest(reason)
        val report = dataDeletionCoordinator.prepare(request)
        check(DeletionStep.REQUESTED in report.completedSteps && report.errorCode == null) {
            "Unable to durably prepare protected account cleanup."
        }
        return request
    }

    private suspend fun buildProtectedAccountDeletionRequest(reason: DeletionReason): DeletionRequest {
        val policy = dataProtectionPolicyProvider.current().policy
        val lifecycleContext = currentLifecycleOperationContext()
        val deletionOperationId = lifecycleContext
            ?.takeIf { it.lifecycleId == FullAppLifecycleId.LC_22 }
            ?.operationId
            ?: UUID.randomUUID().toString()
        val sessionPaths = requireNotNull(sessionPathsProvider.provides(sessionId)) {
            "Unable to locate protected account session paths."
        }
        val request = DeletionRequest(
            operationId = DeletionOperationId(deletionOperationId),
            scope = DeletionScope.Account(
                accountIdHash = hashAccountId(sessionId.value),
                vaultId = accountVaultId.value,
                localSessionId = sessionId.value,
                sessionFilePath = sessionPaths.fileDirectory.path,
                sessionCachePath = sessionPaths.cacheDirectory.path,
            ),
            reason = reason,
            policyVersion = policy.version,
            requestedAtEpochMillis = System.currentTimeMillis(),
        )
        return request
    }

    private suspend fun commitPermanentDeletionWithPropagationRetry(handle: PermanentAccountDeletionHandle) {
        var lastFailure: Throwable? = null
        repeat(PERMANENT_DELETION_COMMIT_ATTEMPTS) { attempt ->
            val result = permanentAccountDeletionCoordinator.commitRemote(handle)
            if (result.isSuccess) return
            val failure = requireNotNull(result.exceptionOrNull())
            lastFailure = failure
            if (
                failure !is PermanentAccountDeletionAwaitingMatrixException &&
                failure !is PermanentAccountDeletionAwaitingGlobalDestroyException ||
                attempt == PERMANENT_DELETION_COMMIT_ATTEMPTS - 1
            ) {
                throw failure
            }
            delay(PERMANENT_DELETION_PROPAGATION_RETRY_MILLIS)
        }
        throw requireNotNull(lastFailure)
    }

    private suspend fun finishPermanentlyDeletedAccount(handle: PermanentAccountDeletionHandle) {
        destroy()
        val report = permanentAccountDeletionCoordinator.completeLocal(
            handle = handle,
            confirmedPreconditions = setOf(
                DeletionStep.STOPPED_SYNC_AND_UI,
                DeletionStep.PURGED_MEMORY,
                DeletionStep.CLOSED_DATABASES,
            ),
        )
        if (!report.isComplete) {
            Timber.w("Permanent local account cleanup requires retry: ${report.errorCode ?: "UNKNOWN"}")
        }
    }

    private suspend fun completeProtectedAccountDeletion(request: DeletionRequest) {
        val report = dataDeletionCoordinator.delete(
            request.copy(
                confirmedPreconditions = setOf(
                    DeletionStep.STOPPED_SYNC_AND_UI,
                    DeletionStep.PURGED_MEMORY,
                    DeletionStep.CLOSED_DATABASES,
                )
            )
        )
        if (!report.isComplete) {
            Timber.w("Protected account cleanup requires retry: ${report.errorCode ?: "UNKNOWN"}")
        }
    }

    private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    override suspend fun getAccountManagementUrl(action: AccountManagementAction?): Result<String?> = withContext(sessionDispatcher) {
        val rustAction = action?.toRustAction()
        runCatchingExceptions {
            innerClient.accountUrl(rustAction)
        }
    }

    override suspend fun uploadMedia(mimeType: String, data: ByteArray): Result<String> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.uploadMedia(mimeType, data, progressWatcher = null)
        }
    }

    override fun getRoomInfoFlow(roomId: RoomId): Flow<Optional<RoomInfo>> {
        return mxCallbackFlow {
            val roomNotFound = innerRoomListService.roomOrNull(roomId.value).use { it == null }
            if (roomNotFound) {
                channel.send(Optional.empty())
            }
            innerClient.subscribeToRoomInfo(roomId.value, object : RoomInfoListener {
                override fun call(roomInfo: org.matrix.rustcomponents.sdk.RoomInfo) {
                    val mappedRoomInfo = roomInfoMapper.map(roomInfo)
                    channel.trySend(Optional.of(mappedRoomInfo))
                }
            })
        }.distinctUntilChanged()
    }

    override suspend fun setAllSendQueuesEnabled(enabled: Boolean) {
        withContext(sessionDispatcher) {
            Timber.i("setAllSendQueuesEnabled($enabled)")
            tryOrNull {
                innerClient.enableAllSendQueues(enabled)
            }
        }
    }

    override fun sendQueueDisabledFlow(): Flow<RoomId> = mxCallbackFlow {
        innerClient.subscribeToSendQueueStatus(object : SendQueueRoomErrorListener {
            override fun onError(roomId: String, error: ClientException) {
                trySend(RoomId(roomId))
            }
        })
    }.buffer(Channel.UNLIMITED)

    override suspend fun currentSlidingSyncVersion(): Result<SlidingSyncVersion> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.session().slidingSyncVersion.map()
        }
    }

    override suspend fun canReportRoom(): Boolean = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.isReportRoomApiSupported()
        }.getOrDefault(false)
    }

    override suspend fun ensureServerVersionsReady(): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            // MatrixRTC delayed membership reads the SDK's in-memory server versions and unstable
            // features. A restored client can have enough room state to render the call while that
            // cache is stale or empty. availableSlidingSyncVersions() is allowed to reuse the
            // persisted cache, so reset it first to force an authenticated /versions request.
            innerClient.resetSupportedVersions()
            val availableSyncVersions = innerClient.availableSlidingSyncVersions()
            check(availableSyncVersions.isNotEmpty()) {
                "Homeserver version discovery returned no supported sync transport."
            }
            innerClient.isReportRoomApiSupported()
            Timber.d("Homeserver version cache refreshed before MatrixRTC initialization.")
            Unit
        }.mapFailure { it.mapClientException() }
    }

    override suspend fun isLivekitRtcSupported(): Boolean = withContext(sessionDispatcher) {
        innerClient.isLivekitRtcSupported()
    }

    override suspend fun getMaxFileUploadSize(): Result<Long> = withContext(sessionDispatcher) {
        runCatchingExceptions { innerClient.getMaxMediaUploadSize().toLong() }
    }

    override suspend fun addRecentEmoji(emoji: String): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.addRecentEmoji(emoji)
        }
    }

    override suspend fun getRecentEmojis(): Result<List<String>> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.getRecentEmojis().map { it.emoji }
        }
    }

    override suspend fun canLinkNewDevice(): Result<Boolean> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.isLoginWithQrCodeSupported()
        }
    }

    override fun createLinkMobileHandler(): Result<LinkMobileHandler> {
        return runCatchingExceptions {
            val handler = innerClient.newGrantLoginWithQrCodeHandler()
            RustLinkMobileHandler(
                inner = handler,
                sessionCoroutineScope = sessionCoroutineScope,
                sessionDispatcher = sessionDispatcher,
            )
        }
    }

    override fun createLinkDesktopHandler(): Result<LinkDesktopHandler> {
        return runCatchingExceptions {
            val handler = innerClient.newGrantLoginWithQrCodeHandler()
            RustLinkDesktopHandler(
                inner = handler,
                sessionCoroutineScope = sessionCoroutineScope,
                sessionDispatcher = sessionDispatcher,
                qrCodeDataParser = RustQrCodeDataParser(),
            )
        }
    }

    override suspend fun markRoomAsFullyRead(roomId: RoomId, eventId: EventId): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            val room = innerClient.getRoom(roomId.value) ?: error("Could not fetch associated room")
            room.markAsFullyReadUnchecked(eventId.value)
        }
    }

    override suspend fun performDatabaseVacuum(): Result<Unit> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            Timber.d("Performing database vacuuming for session $sessionId...")
            innerClient.optimizeStores()
        }
    }

    override suspend fun getMapStyleUrl(): Result<String?> = withContext(sessionDispatcher) {
        runCatchingExceptions {
            innerClient.tileServer()?.mapStyleUrl
        }
    }

    override suspend fun resetWellKnownConfig(): Result<Unit> {
        return runCatchingExceptions {
            Timber.d("Resetting well-known config for session $sessionId")
            innerClient.resetWellKnown()
        }
    }

    private suspend fun getCacheSize(
        includeCryptoDb: Boolean = false,
    ): Long = withContext(sessionDispatcher) {
        val sessionDirectory = sessionPathsProvider.provides(sessionId) ?: return@withContext 0L
        val cacheSize = sessionDirectory.cacheDirectory.getSizeOfFiles()
        if (includeCryptoDb) {
            cacheSize + sessionDirectory.fileDirectory.getSizeOfFiles()
        } else {
            cacheSize + listOf(
                "matrix-sdk-state.sqlite3",
                "matrix-sdk-state.sqlite3-shm",
                "matrix-sdk-state.sqlite3-wal",
            ).map { fileName ->
                File(sessionDirectory.fileDirectory, fileName)
            }.sumOf { file ->
                file.length()
            }
        }
    }

    private fun scheduleDatabaseVacuum() {
        // If there's already a periodic work request, do not schedule another one
        if (workManagerScheduler.hasPendingWork(sessionId, WorkManagerRequestType.DB_VACUUM)) return

        Timber.i("Scheduling periodic database vacuuming for session $sessionId")
        val request = PerformDatabaseVacuumRequestBuilder(sessionId)
        sessionCoroutineScope.launch { workManagerScheduler.submit(request) }
    }

    override fun homeserverCapabilities(): HomeserverCapabilitiesProvider {
        return RustHomeserverCapabilitiesProvider(innerClient.homeserverCapabilities())
    }
}

private fun defaultRoomCreationPowerLevels(isPublic: Boolean, isSpace: Boolean) = PowerLevels(
    usersDefault = null,
    // Only admins should be able to send events in general
    eventsDefault = if (isSpace) 100 else null,
    stateDefault = null,
    ban = null,
    kick = null,
    redact = null,
    invite = if (isPublic) 0 else 50,
    notifications = null,
    users = mapOf(),
    events = if (!isSpace) {
        mapOf(
            "m.call.member" to 0,
            "org.matrix.msc3401.call.member" to 0,
        )
    } else {
        mapOf()
    }
)
