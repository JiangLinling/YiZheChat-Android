/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.discovery.api.DiscoveryError
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

@Composable
internal fun DiscoveryAccessView(busy: Boolean, error: DiscoveryError?, onRetry: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) CircularProgressIndicator()
        Text(stringResource(when {
            busy -> R.string.screen_discovery_entering
            error == DiscoveryError.DENIED -> R.string.screen_discovery_denied
            error == DiscoveryError.SESSION_EXPIRED -> R.string.screen_discovery_expired
            else -> R.string.screen_discovery_unavailable
        }))
        if (!busy) TextButton(onClick = onRetry) { Text(stringResource(R.string.screen_discovery_retry)) }
        TextButton(onClick = onExit) { Text(stringResource(R.string.screen_discovery_close)) }
    }
}

@PreviewsDayNight
@Composable
private fun DiscoveryAccessPreview(@PreviewParameter(DiscoveryAccessStateProvider::class) state: DiscoveryAccessPreviewState) = ElementPreview {
    DiscoveryAccessView(state.busy, state.error, {}, {})
}

internal data class DiscoveryAccessPreviewState(val busy: Boolean, val error: DiscoveryError?)
internal class DiscoveryAccessStateProvider : PreviewParameterProvider<DiscoveryAccessPreviewState> {
    override val values = sequenceOf(DiscoveryAccessPreviewState(true, null)) +
        DiscoveryError.entries.asSequence().map { DiscoveryAccessPreviewState(false, it) }
}
