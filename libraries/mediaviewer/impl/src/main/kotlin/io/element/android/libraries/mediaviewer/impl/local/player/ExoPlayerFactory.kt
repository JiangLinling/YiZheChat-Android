/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
@Composable
fun rememberExoPlayer(forAudioOnly: Boolean, continuousProtectedVideo: Boolean = false, progressiveProtectedVideo: Boolean = false): ExoPlayer {
    return if (LocalInspectionMode.current) {
        remember {
            ExoPlayerForPreview()
        }
    } else {
        val context = LocalContext.current
        remember(context, forAudioOnly, continuousProtectedVideo, progressiveProtectedVideo) {
            if (forAudioOnly) {
                // Required for media3-exoplayer-midi to decode MIDI samples produced by DefaultExtractorsFactory.
                val renderersFactory = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                ExoPlayer.Builder(context, renderersFactory).build()
            } else {
                ExoPlayer.Builder(context).apply {
                    if (progressiveProtectedVideo) {
                        // Keep enough media ahead to absorb network and authenticated-read latency.
                        setLoadControl(DefaultLoadControl.Builder()
                            .setBufferDurationsMs(6_000, 20_000, 750, 2_000)
                            .setTargetBufferBytes(16 * 1024 * 1024)
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build())
                    } else if (continuousProtectedVideo) {
                        // Start within the authenticated window, even when byte limits allow only one fragment ahead.
                        setLoadControl(DefaultLoadControl.Builder()
                            .setBufferDurationsMs(500, 6_000, 0, 0)
                            .setTargetBufferBytes(8 * 1024 * 1024)
                            .build())
                    }
                }.build()
            }
        }
    }
}
