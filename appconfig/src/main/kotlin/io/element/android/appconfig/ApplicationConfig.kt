/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

object ApplicationConfig {
    /**
     * Application name used in the UI for string. If empty, the value is taken from the resources `R.string.app_name`.
     * Note that this value is not used for the launcher icon.
     * YiZheChat reads this value from `R.string.app_name` so Android can use a localized label.
     */
    const val APPLICATION_NAME: String = ""

    /**
     * Used in the strings to reference the Element client.
     * Cannot be empty.
     * For YiZheChat, the value is "YiZheChat".
     */
    const val PRODUCTION_APPLICATION_NAME: String = "YiZheChat"

    /**
     * Used in the strings to reference the Element Desktop client, for instance Element Web.
     * Cannot be empty.
     * For YiZheChat, the value is "YiZheChat".
     */
    const val DESKTOP_APPLICATION_NAME: String = "YiZheChat"
}
