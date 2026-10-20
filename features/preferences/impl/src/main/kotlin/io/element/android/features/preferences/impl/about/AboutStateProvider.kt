/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import kotlinx.collections.immutable.toImmutableList

open class AboutStateProvider : PreviewParameterProvider<AboutState> {
    override val values: Sequence<AboutState>
        get() = sequenceOf(
            anAboutState(),
            anAboutState().copy(updateCheck = AsyncData.Loading()),
            anAboutState().copy(
                preparedUpdate = AsyncData.Loading(),
                downloadProgress = AppUpdateDownloadProgress(0L, -1L),
            ),
            anAboutState().copy(
                preparedUpdate = AsyncData.Loading(),
                downloadProgress = AppUpdateDownloadProgress(68_000_000L, 100_000_000L),
            ),
            anAboutState().copy(
                preparedUpdate = AsyncData.Loading(),
                downloadProgress = AppUpdateDownloadProgress(100_000_000L, 100_000_000L),
            ),
            anAboutState().copy(preparedUpdate = AsyncData.Failure(IllegalStateException("Download unavailable"))),
        )
}

class LegalDocumentProvider : PreviewParameterProvider<ElementLegal> {
    override val values = getAllLegals().asSequence()
}

fun anAboutState(
    elementLegals: List<ElementLegal> = getAllLegals(),
) = AboutState(
    elementLegals = elementLegals.toImmutableList(),
    currentVersion = "1.0.0",
    updateCheck = AsyncData.Uninitialized,
    preparedUpdate = AsyncData.Uninitialized,
    downloadProgress = null,
    eventSink = {},
)
