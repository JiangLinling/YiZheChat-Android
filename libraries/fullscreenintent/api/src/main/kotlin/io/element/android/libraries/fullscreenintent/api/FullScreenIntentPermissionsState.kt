/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.fullscreenintent.api

enum class MissingCallPermission {
    FullScreenIntent,
    BackgroundActivity,
    DoNotDisturbAccess,
}

data class FullScreenIntentPermissionsState(
    val permissionGranted: Boolean,
    val doNotDisturbAccessGranted: Boolean,
    val missingPermission: MissingCallPermission?,
    val shouldDisplayBanner: Boolean,
    val eventSink: (FullScreenIntentPermissionsEvents) -> Unit,
)
