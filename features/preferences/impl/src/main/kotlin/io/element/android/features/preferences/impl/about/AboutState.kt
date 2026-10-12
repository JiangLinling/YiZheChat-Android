/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import io.element.android.libraries.architecture.AsyncData
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.PreparedAppUpdate
import kotlinx.collections.immutable.ImmutableList

data class AboutState(
    val elementLegals: ImmutableList<ElementLegal>,
    val currentVersion: String,
    val updateCheck: AsyncData<ManagedAppRelease?>,
    val preparedUpdate: AsyncData<PreparedAppUpdate>,
    val downloadProgress: AppUpdateDownloadProgress?,
    val eventSink: (AboutEvent) -> Unit,
)

sealed interface AboutEvent {
    data object CheckForUpdate : AboutEvent
    data class DownloadUpdate(val release: ManagedAppRelease) : AboutEvent
    data object ClearUpdateError : AboutEvent
}
