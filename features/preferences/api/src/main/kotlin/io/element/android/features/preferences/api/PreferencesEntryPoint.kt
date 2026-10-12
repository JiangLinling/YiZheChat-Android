/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.api

import android.os.Parcelable
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.user.MatrixUser
import kotlinx.parcelize.Parcelize

interface PreferencesEntryPoint : FeatureEntryPoint {
    sealed interface InitialTarget : Parcelable {
        @Parcelize
        data object Root : InitialTarget

        @Parcelize
        data object NotificationSettings : InitialTarget

        @Parcelize
        data object ChatSecurity : InitialTarget

        @Parcelize
        data object NotificationTroubleshoot : InitialTarget

        @Parcelize
        data object DeveloperSettings : InitialTarget

        @Parcelize
        data object AdvancedSettings : InitialTarget

        @Parcelize
        data object Labs : InitialTarget

        @Parcelize
        data object AnalyticsSettings : InitialTarget

        @Parcelize
        data object About : InitialTarget

        @Parcelize
        data object LockScreenSettings : InitialTarget

        @Parcelize
        data class UserProfile(val matrixUser: MatrixUser) : InitialTarget

        @Parcelize
        data object BlockedUsers : InitialTarget

        @Parcelize
        data object MessageFavourites : InitialTarget

        @Parcelize
        data object SignOut : InitialTarget

        @Parcelize
        data object AccountDeactivation : InitialTarget
    }

    data class Params(val initialElement: InitialTarget) : NodeInputs

    fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        params: Params,
        callback: Callback,
    ): Node

    interface Callback : Plugin {
        fun navigateToMyQrCode() {}
        fun navigateToAddAccount()
        fun navigateToLinkNewDevice()
        fun navigateToBugReport()
        fun navigateToSecureBackup()
        fun navigateToSetUpRecoveryKey() = navigateToSecureBackup()
        fun navigateToEnterRecoveryKey() = navigateToSecureBackup()
        fun navigateToChangeRecoveryKey() = navigateToSecureBackup()
        fun navigateToRoomNotificationSettings(roomId: RoomId)
        fun navigateToEvent(roomId: RoomId, eventId: EventId)
    }

    fun createAppDeveloperSettingsNode(
        parentNode: Node,
        buildContext: BuildContext,
        callback: DeveloperSettingsCallback,
    ): Node

    interface DeveloperSettingsCallback : Plugin {
        fun onDone()
    }
}
