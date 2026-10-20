/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.user.editprofile

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.media.AvatarAction
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@AssistedInject
class EditUserProfilePresenter(
    @Assisted private val matrixUser: MatrixUser,
    @Assisted private val navigator: EditUserProfileNavigator,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val mediaPreProcessor: MediaPreProcessor,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
) : Presenter<EditUserProfileState> {
    private val cameraPermissionPresenter: PermissionsPresenter = permissionsPresenterFactory.create(android.Manifest.permission.CAMERA)
    private var pendingPermissionRequest = false

    @AssistedFactory
    interface Factory {
        fun create(
            matrixUser: MatrixUser,
            navigator: EditUserProfileNavigator,
        ): EditUserProfilePresenter
    }

    @Composable
    override fun present(): EditUserProfileState {
        val cameraPermissionState = cameraPermissionPresenter.present()
        var userAvatarUri by rememberSaveable { mutableStateOf(matrixUser.avatarUrl) }
        var userDisplayName by rememberSaveable { mutableStateOf(matrixUser.displayName) }
        // Acknowledge each successful server mutation separately; retry only the part that failed.
        var savedName by rememberSaveable { mutableStateOf(matrixUser.displayName) }
        var savedAvatarSelection by rememberSaveable { mutableStateOf(matrixUser.avatarUrl) }
        var refreshPending by rememberSaveable { mutableStateOf(false) }
        val cameraPhotoPicker = mediaPickerProvider.registerCameraPhotoPicker(
            onResult = { uri ->
                if (uri != null) {
                    temporaryUriDeleter.delete(userAvatarUri?.toUri())
                    userAvatarUri = uri.toString()
                }
            }
        )
        val galleryImagePicker = mediaPickerProvider.registerGalleryImagePicker(
            onResult = { uri ->
                if (uri != null) {
                    temporaryUriDeleter.delete(userAvatarUri?.toUri())
                    userAvatarUri = uri.toString()
                }
            }
        )

        val avatarActions by remember(userAvatarUri) {
            derivedStateOf {
                listOfNotNull(
                    AvatarAction.TakePhoto,
                    AvatarAction.ChoosePhoto,
                    AvatarAction.Remove.takeIf { userAvatarUri != null },
                ).toImmutableList()
            }
        }

        LaunchedEffect(cameraPermissionState.permissionGranted) {
            if (cameraPermissionState.permissionGranted && pendingPermissionRequest) {
                pendingPermissionRequest = false
                cameraPhotoPicker.launch()
            }
        }

        val homeserverCapabilities = matrixClient.homeserverCapabilities()
        val canChangeDisplayName = produceState(true) {
            value = homeserverCapabilities.canChangeDisplayName().getOrDefault(true)
        }
        val canChangeAvatar = produceState(true) {
            value = homeserverCapabilities.canChangeAvatarUrl().getOrDefault(true)
        }

        val saveAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val localCoroutineScope = rememberCoroutineScope()

        val baseline = matrixUser.copy(displayName = savedName, avatarUrl = savedAvatarSelection)
        val nameChanged = hasDisplayNameChanged(userDisplayName, baseline)
        val canSave = (!nameChanged || !userDisplayName.isNullOrBlank()) &&
            (nameChanged || hasAvatarUrlChanged(userAvatarUri, baseline) || refreshPending)

        fun discardLocalAvatar() {
            if (userAvatarUri != matrixUser.avatarUrl) temporaryUriDeleter.delete(userAvatarUri?.toUri())
        }

        fun handleEvent(event: EditUserProfileEvent) {
            if (saveAction.value is AsyncAction.Loading) return
            when (event) {
                is EditUserProfileEvent.Save -> localCoroutineScope.saveChanges(
                    name = userDisplayName,
                    avatarUri = userAvatarUri?.toUri(),
                    currentUser = baseline,
                    action = saveAction,
                    onNameSaved = { savedName = userDisplayName?.trim(); refreshPending = true },
                    onAvatarSaved = { savedAvatarSelection = userAvatarUri; refreshPending = true },
                    onRefreshed = { refreshPending = false },
                    onCompleted = ::discardLocalAvatar,
                )
                is EditUserProfileEvent.HandleAvatarAction -> {
                    when (event.action) {
                        AvatarAction.ChoosePhoto -> galleryImagePicker.launch()
                        AvatarAction.TakePhoto -> if (cameraPermissionState.permissionGranted) {
                            cameraPhotoPicker.launch()
                        } else {
                            pendingPermissionRequest = true
                            cameraPermissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                        AvatarAction.Remove -> {
                            temporaryUriDeleter.delete(userAvatarUri?.toUri())
                            userAvatarUri = null
                        }
                    }
                }
                is EditUserProfileEvent.UpdateDisplayName -> userDisplayName = event.name
                EditUserProfileEvent.Exit -> {
                    when (saveAction.value) {
                        is AsyncAction.Confirming -> {
                            // Close the dialog right now
                            saveAction.value = AsyncAction.Uninitialized
                            discardLocalAvatar()
                            navigator.close()
                        }
                        AsyncAction.Loading -> Unit
                        is AsyncAction.Failure,
                        is AsyncAction.Success -> {
                            // Should not happen
                        }
                        AsyncAction.Uninitialized -> {
                            if (canSave) {
                                saveAction.value = AsyncAction.ConfirmingCancellation
                            } else {
                                discardLocalAvatar()
                                navigator.close()
                            }
                        }
                    }
                }
                EditUserProfileEvent.CloseDialog -> saveAction.value = AsyncAction.Uninitialized
            }
        }

        return EditUserProfileState(
            userId = matrixUser.userId,
            displayName = userDisplayName.orEmpty(),
            userAvatarUrl = userAvatarUri,
            avatarActions = avatarActions,
            saveButtonEnabled = canSave && saveAction.value !is AsyncAction.Loading,
            saveAction = saveAction.value,
            cameraPermissionState = cameraPermissionState,
            canChangeDisplayName = canChangeDisplayName.value && saveAction.value !is AsyncAction.Loading,
            canChangeAvatarUrl = canChangeAvatar.value && saveAction.value !is AsyncAction.Loading,
            eventSink = ::handleEvent,
        )
    }

    private fun hasDisplayNameChanged(name: String?, currentUser: MatrixUser) =
        name?.trim() != currentUser.displayName?.trim()

    private fun hasAvatarUrlChanged(avatarUri: String?, currentUser: MatrixUser) =
        avatarUri?.trim() != currentUser.avatarUrl?.trim()

    private fun CoroutineScope.saveChanges(
        name: String?,
        avatarUri: Uri?,
        currentUser: MatrixUser,
        action: MutableState<AsyncAction<Unit>>,
        onNameSaved: () -> Unit,
        onAvatarSaved: () -> Unit,
        onRefreshed: () -> Unit,
        onCompleted: () -> Unit,
    ) {
        if (action.value is AsyncAction.Loading) return
        if (hasDisplayNameChanged(name, currentUser) && name.isNullOrBlank()) return
        action.value = AsyncAction.Loading
        launch {
            val nameChanged = hasDisplayNameChanged(name, currentUser)
            val avatarChanged = hasAvatarUrlChanged(avatarUri?.toString(), currentUser)
            val nameResult = if (nameChanged) matrixClient.setDisplayName(name.orEmpty().trim()).onSuccess { onNameSaved() }
                else Result.success(Unit)
            val avatarResult = if (avatarChanged) updateAvatar(avatarUri).onSuccess { onAvatarSaved() }
                else Result.success(Unit)
            // Refresh the authoritative profile even after partial success, so other screens do not keep stale data.
            val refreshed = matrixClient.getUserProfile()
            if (refreshed.isSuccess) onRefreshed()
            val failure = when {
                nameResult.isFailure && avatarResult.isFailure -> ProfileSaveFailure(ProfileSaveFailure.Part.Both)
                nameResult.isFailure -> ProfileSaveFailure(ProfileSaveFailure.Part.Name)
                avatarResult.isFailure -> ProfileSaveFailure(ProfileSaveFailure.Part.Avatar)
                refreshed.isFailure -> ProfileSaveFailure(ProfileSaveFailure.Part.Refresh)
                else -> null
            }
            if (failure == null) {
                onCompleted()
                action.value = AsyncAction.Success(Unit)
            } else {
                action.value = AsyncAction.Failure(failure)
            }
        }
    }

    private suspend fun updateAvatar(avatarUri: Uri?): Result<Unit> = runCatchingExceptions {
        if (avatarUri == null) {
            matrixClient.removeAvatar().getOrThrow()
        } else {
            val preprocessed = mediaPreProcessor.process(
                uri = avatarUri,
                mimeType = MimeTypes.Jpeg,
                deleteOriginal = false,
                retainTemporarySource = true,
                mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
            ).getOrThrow()
            var bytes: ByteArray? = null
            try {
                bytes = preprocessed.file.readBytes()
                matrixClient.uploadAvatar(MimeTypes.Jpeg, bytes).getOrThrow()
            } finally {
                bytes?.fill(0)
                preprocessed.allFiles().distinct().forEach { file ->
                    if (file.exists() && !file.delete()) Timber.w("Avatar temporary output cleanup pending")
                }
            }
        }
    }
}

internal class ProfileSaveFailure(val part: Part) : Exception("Profile update incomplete") {
    enum class Part { Name, Avatar, Both, Refresh }
}
