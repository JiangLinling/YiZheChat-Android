/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.api

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.libraries.matrix.api.user.MatrixUser
import kotlinx.parcelize.Parcelize

fun interface PreferencesRootRenderer {
    @Composable
    fun Render(
        onNavigate: (PreferencesRootDestination) -> Unit,
        modifier: Modifier,
    )
}

sealed interface PreferencesRootDestination : Parcelable {
    @Parcelize
    data object MyQrCode : PreferencesRootDestination

    @Parcelize
    data object AddAccount : PreferencesRootDestination

    @Parcelize
    data object SecureBackup : PreferencesRootDestination

    @Parcelize
    data object LinkNewDevice : PreferencesRootDestination

    @Parcelize
    data object BugReport : PreferencesRootDestination

    @Parcelize
    data object Analytics : PreferencesRootDestination

    @Parcelize
    data object About : PreferencesRootDestination

    @Parcelize
    data object DeveloperSettings : PreferencesRootDestination

    @Parcelize
    data object NotificationSettings : PreferencesRootDestination

    @Parcelize
    data object ChatSecurity : PreferencesRootDestination

    @Parcelize
    data object LockScreenSettings : PreferencesRootDestination

    @Parcelize
    data object AdvancedSettings : PreferencesRootDestination

    @Parcelize
    data object Labs : PreferencesRootDestination

    @Parcelize
    data class UserProfile(val matrixUser: MatrixUser) : PreferencesRootDestination

    @Parcelize
    data object BlockedUsers : PreferencesRootDestination

    @Parcelize
    data object MessageFavourites : PreferencesRootDestination

    @Parcelize
    data object SignOut : PreferencesRootDestination

    @Parcelize
    data object AccountDeactivation : PreferencesRootDestination
}
