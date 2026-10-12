/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.emotions

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.features.messages.impl.R
import kotlinx.coroutines.delay

internal data class EmotionOptions(val autoplay: Boolean = true, val sound: Boolean = true, val vibration: Boolean = true)

internal fun reducedEmotionMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Authored image playback is media time, independent of the system's nonzero transition speed. */
internal suspend fun animateEmotionProgress(durationMillis: Int, update: (Float) -> Unit) {
    val start = withFrameNanos { it }
    var progress: Float
    do {
        progress = ((withFrameNanos { it } - start).toDouble() / (durationMillis * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
        update(progress)
    } while (progress < 1f)
}

/** Composition/lifecycle cancellation owns all three channels, including a pending sound load. */
@Composable
internal fun EmotionPlayback(emotion: ChatEmotion, options: EmotionOptions, modifier: Modifier = Modifier, scene: Boolean = true, onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val reduced = reducedEmotionMotion(context)
    var progress by remember(emotion) { mutableFloatStateOf(0f) }
    var completed by remember(emotion) { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, emotion) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) completed = true }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(emotion, resumed, reduced, completed) {
        if (completed) { onFinished(); return@LaunchedEffect }
        if (!resumed) return@LaunchedEffect
        if (reduced) delay(900) else animateEmotionProgress(emotion.durationMillis) { progress = it }
        completed = true
    }
    DisposableEffect(emotion, resumed, completed, options, reduced) {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val quietAllowed = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive && notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        var active = true
        var pool: SoundPool? = null
        var vibrator: Vibrator? = null
        if (resumed && !completed && !reduced && emotion.tier == 2 && quietAllowed) {
            if (options.sound) {
                val activePool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
                pool = activePool
                activePool.setOnLoadCompleteListener { loaded, sample, status ->
                    if (active && status == 0 && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive) {
                        loaded.play(sample, .25f, .25f, 1, 0, 1f)
                    }
                }
                activePool.load(context, when (emotion.id) {
                    "firecracker" -> R.raw.emotion_crackle
                    "fireworks" -> R.raw.emotion_sparkle
                    "prosperity" -> R.raw.emotion_fortune
                    else -> R.raw.emotion_celebrate
                }, 1)
            }
            if (options.vibration && Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1) {
                vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 22, 130, 28), intArrayOf(0, 55, 0, 75), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 18, 130, 22), -1)
                }
            }
        }
        onDispose {
            active = false
            pool?.setOnLoadCompleteListener(null)
            pool?.release()
            vibrator?.cancel()
        }
    }
    EmotionArtwork(emotion, if (reduced) 0f else progress, modifier.testTag("emotion_playback_${emotion.id}"), scene,
        playing = resumed && !completed && !reduced)
}
