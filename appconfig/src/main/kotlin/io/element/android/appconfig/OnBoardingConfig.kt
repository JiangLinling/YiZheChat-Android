/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

object OnBoardingConfig {
    /** Whether the user can create an account using the app. */
    const val CAN_CREATE_ACCOUNT = true

    /**
     * The preferred initial homeserver for a new session. Users can still choose another server.
     */
    val DEFAULT_HOMESERVER_URL: String = BuildConfig.YIZHECHAT_HOMESERVER_URL
}
