/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.impl.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.enterprise.api.SessionEnterpriseService
import io.element.android.features.startchat.api.StartDMAction
import io.element.android.features.userprofile.api.UserProfileEvents
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.api.UserProfileState
import io.element.android.features.userprofile.api.UserProfileState.ConfirmationDialog
import io.element.android.features.userprofile.api.UserProfileVerificationState
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.powerlevels.canCall
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.TimeoutCancellationException
import io.element.android.libraries.matrix.api.room.powerlevels.use
import io.element.android.libraries.matrix.api.user.MatrixUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@AssistedInject
class UserProfilePresenter(
    @Assisted private val userId: UserId,
    private val client: MatrixClient,
    private val startDMAction: StartDMAction,
    private val sessionEnterpriseService: SessionEnterpriseService,
    private val friendProfileRepository: FriendProfileRepository,
    private val friendshipRemoval: FriendshipRemovalProtocol,
) : Presenter<UserProfileState> {
    @AssistedFactory
    interface Factory {
        fun create(userId: UserId): UserProfilePresenter
    }

    @Composable
    private fun getDmRoomId(): State<RoomId?> {
        return produceState<RoomId?>(initialValue = null, userId) {
            client.roomListService.allRooms.summaries
                .map { rooms -> rooms.map { it.info }.filter { info ->
                    info.inviter?.userId == userId || info.heroes.any { it.userId == userId } || info.id == value
                }.map { info ->
                    listOf(info.id, info.currentUserMembership, info.isDm, info.isDirect,
                        info.joinedMembersCount, info.invitedMembersCount, info.activeMembersCount)
                }.toSet() }
                .distinctUntilChanged()
                .collect { value = client.findDM(userId).getOrNull() }
        }
    }

    @Composable
    private fun getCanCall(roomId: RoomId?, canInteract: Boolean): State<Boolean> {
        val isElementCallAvailable by produceState(initialValue = false, roomId) {
            value = sessionEnterpriseService.isElementCallAvailable()
        }
        return produceState(initialValue = false, isElementCallAvailable, roomId, canInteract) {
            value = when {
                isElementCallAvailable.not() -> false
                client.isMe(userId) || !canInteract -> false
                else ->
                    roomId
                        ?.let { client.getRoom(it) }
                        ?.use { room ->
                            room.info().directRoomRelationState().canInteract &&
                                room.roomPermissions().use(false) { perms -> perms.canCall() }
                        }
                        .orFalse()
            }
        }
    }

    @Composable
    override fun present(): UserProfileState {
        val coroutineScope = rememberCoroutineScope()
        val isCurrentUser = remember { client.isMe(userId) }
        var confirmationDialog by remember { mutableStateOf<ConfirmationDialog?>(null) }
        val startDmActionState: MutableState<AsyncAction<RoomId>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val savePrivateProfileAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val isBlocked: MutableState<AsyncData<Boolean>> = remember { mutableStateOf(AsyncData.Uninitialized) }
        val dmRoomId by getDmRoomId()
        val dmInfo by produceState<io.element.android.libraries.matrix.api.room.RoomInfo?>(null, dmRoomId) {
            dmRoomId?.let { id ->
                client.getRoomInfoFlow(id).collect { value = it.orElse(null) }
            }
        }
        val relation = dmInfo?.directRoomRelationState()
        var friendship by remember(userId) { mutableStateOf<io.element.android.security.dataprotection.api.FriendshipRemovalSnapshot?>(null) }
        LaunchedEffect(userId, dmRoomId, relation) {
            friendship = runCatchingExceptions { friendshipRemoval.preview(userId) }.getOrNull()
        }
        val removed = friendship?.status == io.element.android.security.dataprotection.api.FriendshipRemovalStatus.REMOVED
        val canCall by getCanCall(dmRoomId, relation?.canInteract == true && !removed)
        val removeFriendAction = remember { mutableStateOf<AsyncAction<Unit>>(AsyncAction.Uninitialized) }
        var removalDiagnostic by remember { mutableStateOf<String?>(null) }
        var removal by remember { mutableStateOf<RemoveFriendAction?>(null) }
        LaunchedEffect(Unit) {
            client.ignoredUsersFlow
                .map { ignoredUsers -> userId in ignoredUsers }
                .distinctUntilChanged()
                .onEach { isBlocked.value = AsyncData.Success(it) }
                .launchIn(this)
        }
        val userProfile by produceState<MatrixUser?>(null) { value = client.getProfile(userId).getOrNull() }
        LaunchedEffect(friendProfileRepository) { friendProfileRepository.load() }
        val privateProfiles by friendProfileRepository.profiles.collectAsState()
        val privateProfile = privateProfiles[userId] ?: FriendPrivateProfile(userId)

        fun handleEvent(event: UserProfileEvents) {
            when (event) {
                is UserProfileEvents.BlockUser -> {
                    if (event.needsConfirmation) {
                        confirmationDialog = ConfirmationDialog.Block
                    } else {
                        confirmationDialog = null
                        coroutineScope.blockUser(isBlocked)
                    }
                }
                is UserProfileEvents.UnblockUser -> {
                    if (event.needsConfirmation) {
                        confirmationDialog = ConfirmationDialog.Unblock
                    } else {
                        confirmationDialog = null
                        coroutineScope.unblockUser(isBlocked)
                    }
                }
                is UserProfileEvents.RemoveFriend -> {
                    if (removeFriendAction.value !is AsyncAction.Loading && !isCurrentUser) {
                        removalDiagnostic = null
                        removeFriendAction.value = AsyncAction.Loading
                        coroutineScope.launch {
                            val result = try { runCatchingExceptions {
                                if (event.needsConfirmation || removal == null) {
                                    val action = RemoveFriendAction(client, userId, friendshipRemoval)
                                    action.prepare()
                                    removal = action
                                    removeFriendAction.value = AsyncAction.ConfirmingNoParams
                                } else {
                                    checkNotNull(removal).execute()
                                    friendship = friendship?.copy(status = io.element.android.security.dataprotection.api.FriendshipRemovalStatus.REMOVED)
                                    removeFriendAction.value = AsyncAction.Success(Unit)
                                }
                            } } catch (timeout: TimeoutCancellationException) {
                                Result.failure(timeout)
                            }
                            result.onFailure {
                                removalDiagnostic = when (it) {
                                    is io.element.android.security.dataprotection.api.RoomMembershipFailure -> it.diagnostic
                                    is TimeoutCancellationException -> "REMOVE_EXECUTE:TIMEOUT"
                                    else -> "REMOVE_EXECUTE:UNEXPECTED"
                                }
                                removeFriendAction.value = AsyncAction.Failure(it)
                            }
                        }
                    }
                }
                UserProfileEvents.ClearRemoveFriendState -> {
                    if (removeFriendAction.value !is AsyncAction.Loading) {
                        removeFriendAction.value = AsyncAction.Uninitialized
                        removal = null
                        removalDiagnostic = null
                    }
                }
                UserProfileEvents.ClearConfirmationDialog -> confirmationDialog = null
                UserProfileEvents.ClearBlockUserError -> {
                    isBlocked.value = AsyncData.Success(isBlocked.value.dataOrNull().orFalse())
                }
                UserProfileEvents.StartDM -> {
                    coroutineScope.launch {
                        startDMAction.execute(
                            matrixUser = userProfile ?: MatrixUser(userId),
                            createIfDmDoesNotExist = startDmActionState.value is AsyncAction.Confirming,
                            actionState = startDmActionState,
                        )
                    }
                }
                UserProfileEvents.ClearStartDMState -> {
                    startDmActionState.value = AsyncAction.Uninitialized
                }
                is UserProfileEvents.SavePrivateProfile -> coroutineScope.launch {
                    savePrivateProfileAction.value = AsyncAction.Loading
                    runCatching { friendProfileRepository.save(event.profile.copy(userId = userId)) }
                        .onSuccess { savePrivateProfileAction.value = AsyncAction.Success(Unit) }
                        .onFailure { savePrivateProfileAction.value = AsyncAction.Failure(it) }
                }
                UserProfileEvents.ClearSavePrivateProfileState -> savePrivateProfileAction.value = AsyncAction.Uninitialized
                // Do nothing for other event as they are handled by the RoomMemberDetailsPresenter if needed
                UserProfileEvents.WithdrawVerification,
                is UserProfileEvents.CopyToClipboard -> Unit
            }
        }

        return UserProfileState(
            userId = userId,
            userName = privateProfile.displayName(userProfile?.displayName),
            avatarUrl = userProfile?.avatarUrl,
            isBlocked = isBlocked.value,
            verificationState = UserProfileVerificationState.UNKNOWN,
            startDmActionState = startDmActionState.value,
            displayConfirmationDialog = confirmationDialog,
            isCurrentUser = isCurrentUser,
            dmRoomId = dmRoomId,
            canCall = canCall,
            canRemoveFriend = !isCurrentUser && friendship?.status in setOf(
                io.element.android.security.dataprotection.api.FriendshipRemovalStatus.ACCEPTED,
                io.element.android.security.dataprotection.api.FriendshipRemovalStatus.PENDING,
            ),
            removeFriendAction = removeFriendAction.value,
            removeFriendDiagnostic = removalDiagnostic,
            privateProfile = privateProfile,
            savePrivateProfileAction = savePrivateProfileAction.value,
            snackbarMessage = null,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.blockUser(
        isBlockedState: MutableState<AsyncData<Boolean>>,
    ) = launch {
        isBlockedState.value = AsyncData.Loading(false)
        client.ignoreUser(userId)
            .onSuccess { isBlockedState.value = AsyncData.Success(true) }
            .onFailure {
                isBlockedState.value = AsyncData.Failure(it, false)
            }
        // Note: on success, ignoredUsersFlow will emit new item.
    }

    private fun CoroutineScope.unblockUser(
        isBlockedState: MutableState<AsyncData<Boolean>>,
    ) = launch {
        isBlockedState.value = AsyncData.Loading(true)
        client.unignoreUser(userId)
            .onSuccess { isBlockedState.value = AsyncData.Success(false) }
            .onFailure {
                isBlockedState.value = AsyncData.Failure(it, true)
            }
        // Note: on success, ignoredUsersFlow will emit new item.
    }
}
