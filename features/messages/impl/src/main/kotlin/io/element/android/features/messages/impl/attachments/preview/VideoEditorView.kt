/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.libraries.androidutils.media.runAndRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VideoEditorView(attachment: Attachment.Media, onCancel: () -> Unit, onDone: (Long, Long?, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var durationFailed by remember(attachment.localMedia.uri) { mutableStateOf(false) }
    val duration by produceState<Long?>(null, attachment.localMedia.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().runAndRelease {
                setDataSource(context, attachment.localMedia.uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            }
            }.getOrNull()
        }
        durationFailed = value == null || value!! <= 0
    }
    var start by rememberSaveable(attachment.localMedia.uri.toString()) { mutableFloatStateOf(attachment.videoStartMs / 1000f) }
    var end by rememberSaveable(attachment.localMedia.uri.toString()) { mutableStateOf<Float?>(attachment.videoEndMs?.div(1000f)) }
    var mute by rememberSaveable(attachment.localMedia.uri.toString()) { mutableStateOf(attachment.muteAudio) }
    val maxSeconds = (duration ?: 1L).coerceAtLeast(1) / 1000f
    val endSeconds = end ?: maxSeconds
    Surface(modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(stringResource(R.string.media_cancel)) }
            TextButton(onClick = {
                onDone((start * 1000).toLong(), if (end ==
                null) {
                    null
                } else {
                    (endSeconds * 1000).toLong()
                }, mute)
            }, enabled = duration != null && endSeconds > start, colors = ButtonDefaults.textButtonColors(
                contentColor = Color.White
            )) { Text(stringResource(R.string.media_done)) }
        }
        EditableVideoPlayer(attachment.localMedia.uri, (start * 1000).toLong(), if (end ==
            null) {
                null
            } else {
                (endSeconds * 1000).toLong()
            }, mute, Modifier.weight(1f).fillMaxWidth())
        if (duration != null) {
            Text(stringResource(R.string.media_clip_duration, "%.1f".format(endSeconds - start)), Modifier.padding(16.dp))
            RangeSlider(value = start..endSeconds, onValueChange = {
                if (it.endInclusive - it.start >= minOf(.2f, maxSeconds)) {
                    start = it.start
                    end = it.endInclusive
                }
            }, valueRange = 0f..maxSeconds, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.media_original_sound))
                Switch(checked = !mute, onCheckedChange = { mute = !it })
            }
        } else {
            Text(stringResource(if (durationFailed) R.string.media_video_unavailable else R.string.media_video_loading), Modifier.padding(16.dp))
        }
    }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun EditableVideoPlayer(uri: Uri, startMs: Long = 0, endMs: Long? = null, mute: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(uri, startMs, endMs) {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
        endMs?.let { clip.setEndPositionMs(it) }
        player.setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(clip.build()).build())
        player.prepare()
    }
    LaunchedEffect(mute) { player.volume = if (mute) 0f else 1f }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
        this.player = player
        useController = true
    }
        },
        modifier = modifier,
        update = { it.player = player }
    )
}
