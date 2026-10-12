/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.createroom.impl.configureroom

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.CreatedRoom
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.room.alias.RoomAliasHelper
import io.element.android.libraries.matrix.api.room.history.RoomHistoryVisibility
import io.element.android.libraries.matrix.api.room.join.JoinRule
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.spaces.SpaceRoom
import io.element.android.libraries.matrix.ui.media.AvatarAction
import io.element.android.libraries.matrix.ui.room.address.RoomAddressValidity
import io.element.android.libraries.matrix.ui.room.address.RoomAddressValidityEffect
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagedRoomCreationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class ConfigureRoomPresenter(
    @Assisted private val isSpace: Boolean,
    @Assisted private val initialParentSpaceId: RoomId?,
    private val dataStore: CreateRoomConfigStore,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val mediaPreProcessor: MediaPreProcessor,
    private val analyticsService: AnalyticsService,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
    private val featureFlagService: FeatureFlagService,
    private val roomAliasHelper: RoomAliasHelper,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val contentProtectionService: ContentProtectionService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val deletedIdentityProjection: DeletedIdentityProjection,
    private val managementControlService: ManagementControlService,
) : Presenter<ConfigureRoomState> {
    @AssistedFactory
    interface Factory {
        fun create(isSpace: Boolean, initialParentSpaceId: RoomId?): ConfigureRoomPresenter
    }

    private val cameraPermissionPresenter: PermissionsPresenter = permissionsPresenterFactory.create(android.Manifest.permission.CAMERA)
    private var pendingPermissionRequest = false
    private var createdRoomAwaitingCompletion: Pair<RoomId, RoomLifecycleProfileKind>? = null
    private var managedCreationOperation: LifecycleOperationRequest? = null
    private var managedCreationConfig: CreateRoomConfig? = null
    private var managedCreationRequest: ManagedRoomCreationRequest? = null

    @Composable
    override fun present(): ConfigureRoomState {
        val cameraPermissionState = cameraPermissionPresenter.present()
        val createRoomConfig by dataStore.getCreateRoomConfigFlow().collectAsState()
        val deletedIdentityRevision by deletedIdentityProjection.revision.collectAsState()
        val contactSummaries by matrixClient.roomListService.allRooms.summaries.collectAsState(initial = emptyList())
        val contactsLoadingState by matrixClient.roomListService.allRooms.loadingState.collectAsState()
        val contacts = remember(contactSummaries, deletedIdentityRevision) {
            contactSummaries.filter { it.info.directRoomRelationState() == DirectRoomRelationState.ACCEPTED }
                .mapNotNull { summary -> summary.info.heroes.singleOrNull { it.userId != matrixClient.sessionId } }
                .filterNot { deletedIdentityProjection.isDeletedMatrixUserId(it.userId.value) }
                .distinctBy { it.userId }
                .sortedBy { it.displayName ?: it.userId.value }
                .toImmutableList()
        }
        val contactsLoading = contactsLoadingState !is RoomList.LoadingState.Loaded ||
            (contactsLoadingState as? RoomList.LoadingState.Loaded)?.numberOfRooms?.let { it > 0 && contactSummaries.isEmpty() } == true
        LaunchedEffect(contacts, contactsLoading) {
            if (!isSpace && !contactsLoading && createdRoomAwaitingCompletion == null && managedCreationConfig == null) {
                dataStore.setInvites(dataStore.getCreateRoomConfigFlow().value.invites.filter { selected -> contacts.any { it.userId == selected.userId } })
            }
        }
        val homeserverName = remember { matrixClient.userIdServerName() }
        val isKnockFeatureEnabled by remember {
            featureFlagService.isFeatureEnabledFlow(FeatureFlags.Knock)
        }.collectAsState(initial = false)
        val roomAddressValidity = remember {
            mutableStateOf<RoomAddressValidity>(RoomAddressValidity.Unknown)
        }

        val cameraPhotoPicker = mediaPickerProvider.registerCameraPhotoPicker(
            onResult = { uri -> if (uri != null) dataStore.setAvatarUri(uri = uri, cached = true) },
        )
        val galleryImagePicker = mediaPickerProvider.registerGalleryImagePicker(
            onResult = { uri -> if (uri != null) dataStore.setAvatarUri(uri = uri) }
        )

        val avatarActions by remember(createRoomConfig.avatarUri) {
            derivedStateOf {
                listOfNotNull(
                    AvatarAction.TakePhoto,
                    AvatarAction.ChoosePhoto,
                    AvatarAction.Remove.takeIf { createRoomConfig.avatarUri != null },
                ).toImmutableList()
            }
        }

        var spaces by remember { mutableStateOf<ImmutableList<SpaceRoom>>(persistentListOf()) }
        LaunchedEffect(Unit) {
            spaces = matrixClient.spaceService.editableSpaces().getOrElse { emptyList() }.toImmutableList()
            val parentSpace = spaces.find { it.roomId == initialParentSpaceId }
            parentSpace?.let {
                dataStore.setParentSpace(parentSpace = parentSpace, updateVisibility = true)
            }
        }

        LaunchedEffect(cameraPermissionState.permissionGranted) {
            if (cameraPermissionState.permissionGranted && pendingPermissionRequest) {
                pendingPermissionRequest = false
                cameraPhotoPicker.launch()
            }
        }

        RoomAddressValidityEffect(
            client = matrixClient,
            roomAliasHelper = roomAliasHelper,
            newRoomAddress = createRoomConfig.visibilityState.roomAddress().getOrDefault(""),
            knownRoomAddress = null,
        ) { newRoomAddressValidity ->
            roomAddressValidity.value = newRoomAddressValidity
        }

        val localCoroutineScope = rememberCoroutineScope()
        val createRoomAction: MutableState<AsyncAction<RoomId>> = remember { mutableStateOf(AsyncAction.Uninitialized) }

        // Calculate available join rules based:
        // 1. If we are creating a space.
        // 2. If it has a parent space.
        // 3. If knocking is enabled.
        val parentSpace = createRoomConfig.parentSpace
        val availableJoinRules = remember(parentSpace, isSpace, isKnockFeatureEnabled) {
            when {
                isSpace && parentSpace != null -> TODO("Adding a space to a parent space is not supported yet! How did you get here?")
                parentSpace == null || parentSpace.joinRule == JoinRule.Public -> listOfNotNull(
                    JoinRuleItem.PublicVisibility.Public,
                    JoinRuleItem.PublicVisibility.AskToJoin.takeIf { !isSpace && isKnockFeatureEnabled },
                    JoinRuleItem.PrivateVisibility.Private,
                ).toImmutableList()
                else -> listOfNotNull(
                    JoinRuleItem.PrivateVisibility.Restricted(parentSpace.roomId),
                    JoinRuleItem.PrivateVisibility.AskToJoinRestricted(parentSpace.roomId).takeIf { isKnockFeatureEnabled },
                    JoinRuleItem.PrivateVisibility.Private,
                ).toImmutableList()
            }
        }
        val currentJoinRule = createRoomConfig.visibilityState.joinRuleItem
        LaunchedEffect(availableJoinRules, currentJoinRule) {
            // Find matching rule by type (ignoring parentSpaceId parameter for Restricted types)
            val matchingRule = when (currentJoinRule) {
                is JoinRuleItem.PrivateVisibility.Restricted ->
                    availableJoinRules.filterIsInstance<JoinRuleItem.PrivateVisibility.Restricted>().firstOrNull()
                is JoinRuleItem.PrivateVisibility.AskToJoinRestricted ->
                    availableJoinRules.filterIsInstance<JoinRuleItem.PrivateVisibility.AskToJoinRestricted>().firstOrNull()
                else -> availableJoinRules.find { it == currentJoinRule }
            }
            when {
                matchingRule == null -> {
                    // No matching type fallback to Private (always available)
                    dataStore.setJoinRule(JoinRuleItem.PrivateVisibility.Private)
                }
                matchingRule != currentJoinRule -> {
                    // Same type but different params (e.g., different parentSpaceId), update
                    dataStore.setJoinRule(matchingRule)
                }
            }
        }

        fun createRoom(config: CreateRoomConfig) {
            createRoomAction.value = AsyncAction.Uninitialized
            localCoroutineScope.createRoom(config, createRoomAction)
        }

        fun handleEvent(event: ConfigureRoomEvents) {
            if ((createRoomAction.value.isLoading() || createdRoomAwaitingCompletion != null || managedCreationConfig != null) &&
                event != ConfigureRoomEvents.CreateRoom && event != ConfigureRoomEvents.CancelCreateRoom
            ) return
            when (event) {
                is ConfigureRoomEvents.ToggleInvite -> {
                    val selected = createRoomConfig.invites.any { it.userId == event.user.userId }
                    if (selected) {
                        dataStore.setInvites(createRoomConfig.invites.filterNot { it.userId == event.user.userId })
                    } else if (contacts.any { it.userId == event.user.userId }) {
                        dataStore.setInvites(createRoomConfig.invites + event.user)
                    }
                }
                is ConfigureRoomEvents.RoomNameChanged -> dataStore.setRoomName(event.name)
                is ConfigureRoomEvents.TopicChanged -> dataStore.setTopic(event.topic)
                is ConfigureRoomEvents.JoinRuleChanged -> dataStore.setJoinRule(event.joinRuleItem)
                is ConfigureRoomEvents.RoomAddressChanged -> dataStore.setRoomAddress(event.roomAddress)
                is ConfigureRoomEvents.LifecycleProfileChanged -> dataStore.setLifecycleProfileKind(event.profileKind)
                is ConfigureRoomEvents.CreateRoom -> if (!createRoomAction.value.isLoading()) createRoom(createRoomConfig)
                is ConfigureRoomEvents.HandleAvatarAction -> {
                    when (event.action) {
                        AvatarAction.ChoosePhoto -> galleryImagePicker.launch()
                        AvatarAction.TakePhoto -> if (cameraPermissionState.permissionGranted) {
                            cameraPhotoPicker.launch()
                        } else {
                            pendingPermissionRequest = true
                            cameraPermissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                        AvatarAction.Remove -> dataStore.setAvatarUri(uri = null)
                    }
                }
                is ConfigureRoomEvents.SetParentSpace -> {
                    dataStore.setParentSpace(event.space, false)
                }
                ConfigureRoomEvents.CancelCreateRoom -> {
                    createRoomAction.value = AsyncAction.Uninitialized
                }
            }
        }

        return ConfigureRoomState(
            config = createRoomConfig,
            avatarActions = avatarActions,
            createRoomAction = createRoomAction.value,
            cameraPermissionState = cameraPermissionState,
            homeserverName = homeserverName,
            roomAddressValidity = roomAddressValidity.value,
            availableJoinRules = availableJoinRules,
            spaces = spaces,
            isSpace = isSpace,
            eventSink = ::handleEvent,
            contacts = contacts,
            contactsLoading = contactsLoading,
            isConfigurationLocked = createRoomAction.value.isLoading() || createdRoomAwaitingCompletion != null || managedCreationConfig != null,
        )
    }

    private fun CoroutineScope.createRoom(
        config: CreateRoomConfig,
        createRoomAction: MutableState<AsyncAction<RoomId>>
    ) = launch {
        suspend {
            appOperationalStateCoordinator.perform(
                managedCreationOperation ?: lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_12,
                    trigger = "room_create",
                    accountIdHash = accountIdHash(matrixClient.sessionId.value),
                    matrixDeviceIdHash = accountIdHash(matrixClient.deviceId.value),
                ).copy(roomCreationProtocol = "managed-v1".takeIf { !isSpace && managementControlService.supportsManagedRoomCreation })
                    .also { if (!isSpace && managementControlService.supportsManagedRoomCreation) managedCreationOperation = it }
            ) {
            if (createdRoomAwaitingCompletion == null) {
                for (user in config.invites) {
                    check(!deletedIdentityProjection.isDeletedMatrixUserId(user.userId.value)) { "A selected identity has been deleted." }
                    val directRoomId = checkNotNull(matrixClient.findDM(user.userId).getOrThrow())
                    val directInfo = matrixClient.getRoom(directRoomId)?.use { it.info() }
                    check(directInfo?.directRoomRelationState() == DirectRoomRelationState.ACCEPTED) {
                        "A selected contact no longer has an accepted direct relationship."
                    }
                }
            }
            val avatarUrl = managedCreationRequest?.avatarUrl ?: config.avatarUri?.takeIf { createdRoomAwaitingCompletion == null }?.let { uploadAvatar(it.toUri()) }
            val params = when (config.visibilityState) {
                is RoomVisibilityState.Public -> {
                    CreateRoomParameters(
                        name = if (isSpace) config.roomName else config.effectiveRoomName,
                        topic = config.topic,
                        isEncrypted = !isSpace,
                        isDirect = false,
                        visibility = RoomVisibility.Public,
                        joinRuleOverride = config.visibilityState.joinRuleItem.toJoinRule()
                            // No need to specify the public join rule override, since the preset is already PUBLIC_CHAT
                            .takeIf { it != JoinRule.Public },
                        preset = RoomPreset.PUBLIC_CHAT,
                        invite = config.invites.map { it.userId },
                        avatar = avatarUrl,
                        roomAliasName = config.visibilityState.roomAddress(),
                        isSpace = isSpace,
                    )
                }
                is RoomVisibilityState.Private -> {
                    CreateRoomParameters(
                        name = if (isSpace) config.roomName else config.effectiveRoomName,
                        topic = config.topic,
                        isEncrypted = true,
                        isDirect = false,
                        visibility = RoomVisibility.Private,
                        historyVisibilityOverride = RoomHistoryVisibility.Invited,
                        joinRuleOverride = config.visibilityState.joinRuleItem.toJoinRule()
                            // No need to specify the Invite join rule override, since the preset is already PRIVATE_CHAT
                            .takeIf { it != JoinRule.Invite },
                        preset = RoomPreset.PRIVATE_CHAT,
                        invite = config.invites.map { it.userId },
                        avatar = avatarUrl,
                        isSpace = isSpace,
                    )
                }
            }
            val managedRoomId = if (createdRoomAwaitingCompletion == null && !isSpace && managementControlService.supportsManagedRoomCreation) {
                check(managedCreationConfig == null || managedCreationConfig == config) { "The original managed room intent changed." }
                managedCreationConfig = config
                val joinRuleItem = config.visibilityState.joinRuleItem
                val joinRule = when (joinRuleItem) {
                    JoinRuleItem.PrivateVisibility.Private -> "invite"
                    is JoinRuleItem.PrivateVisibility.Restricted -> "restricted"
                    is JoinRuleItem.PrivateVisibility.AskToJoinRestricted -> "knock_restricted"
                    JoinRuleItem.PublicVisibility.Public -> "public"
                    JoinRuleItem.PublicVisibility.AskToJoin -> "knock"
                }
                val parentRoomId = when (joinRuleItem) {
                    is JoinRuleItem.PrivateVisibility.Restricted -> joinRuleItem.parentSpaceId.value
                    is JoinRuleItem.PrivateVisibility.AskToJoinRestricted -> joinRuleItem.parentSpaceId.value
                    else -> null
                }
                val request = managedCreationRequest ?: ManagedRoomCreationRequest(
                    creatorUserId = matrixClient.sessionId.value,
                    name = checkNotNull(config.effectiveRoomName),
                    topic = config.topic,
                    invitees = config.invites.map { it.userId.value },
                    profileKind = config.lifecycleProfileKind.name,
                    visibility = if (config.visibilityState is RoomVisibilityState.Public) "public" else "private",
                    joinRule = joinRule,
                    parentRoomId = parentRoomId,
                    roomAliasName = params.roomAliasName.getOrNull(),
                    avatarUrl = avatarUrl,
                ).also { managedCreationRequest = it }
                RoomId(checkNotNull(managementControlService.createManagedRoom(request)))
            } else null
            val roomId = createdRoomAwaitingCompletion?.first ?: (managedRoomId?.let { Result.success(it) } ?: matrixClient.createRoom(params))
                .onFailure { failure -> Timber.e(failure, "Failed to create room") }
                .getOrThrow()
                .also { createdRoomAwaitingCompletion = it to config.lifecycleProfileKind }

            if (!isSpace) {
                check(createdRoomAwaitingCompletion?.second == config.lifecycleProfileKind) {
                    "The pending room lifecycle selection changed during creation."
                }
                check(contentProtectionService.assignCreatedRoomLifecycle(roomId.value, config.lifecycleProfileKind)) {
                    "The room lifecycle profile was not accepted."
                }
            }

            // Add the newly created room to the parent space too
            if (config.parentSpace != null) {
                Timber.d("Adding room $roomId to parent space ${config.parentSpace.roomId}")
                // Wait until we receive the power level info for the room, as it's needed to check if it can be added to a space
                // TODO create some SDK function that does this instead?
                withTimeoutOrNull(30.seconds) {
                    matrixClient.getRoomInfoFlow(roomId).first { it.getOrNull()?.roomPowerLevels != null }
                } ?: error("Did not receive created room power levels for room $roomId, needed for adding it to a space")

                matrixClient.spaceService.addChildToSpace(spaceId = config.parentSpace.roomId, childId = roomId).getOrThrow()
            }

            roomId
            }
        }.runCatchingUpdatingState(createRoomAction)
            .onSuccess {
                // Clear only after the coordinator accepted every client receipt.
                createdRoomAwaitingCompletion = null
                managedCreationConfig = null
                managedCreationRequest = null
                managedCreationOperation = null
                dataStore.clearCachedData()
                analyticsService.capture(CreatedRoom(isDM = false))
            }
            .onFailure { Timber.e(it, "Could not create room or add it to parent space ${config.parentSpace?.roomId}") }
    }

    private suspend fun uploadAvatar(avatarUri: Uri): String {
        val preprocessed = mediaPreProcessor.process(
            uri = avatarUri,
            mimeType = MimeTypes.Jpeg,
            deleteOriginal = false,
            mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
        ).getOrThrow()
        val byteArray = preprocessed.file.readBytes()
        return matrixClient.uploadMedia(MimeTypes.Jpeg, byteArray).getOrThrow()
    }
}
