package io.element.android.features.call.ui

import android.media.AudioDeviceInfo
import android.content.Context
import android.media.AudioManager
import android.webkit.WebView
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.utils.SerializableAudioDevice
import io.element.android.features.call.impl.utils.WebViewAudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoAudioDeviceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `route monitoring starts before playback and late widget readiness restores receiver`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val receiver = mockk<AudioDeviceInfo>(relaxed = true)
            every { receiver.id } returns 1
            every { receiver.type } returns AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            every { receiver.isSink } returns true
            every { receiver.productName } returns "Receiver"
            val audio = mockk<AudioManager>(relaxed = true)
            every { audio.availableCommunicationDevices } returns listOf(receiver)
            every { audio.communicationDevice } returns receiver
            every { audio.setCommunicationDevice(receiver) } returns true
            val context = mockk<Context>(relaxed = true)
            every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
            every { context.getSystemService(android.os.PowerManager::class.java) } returns null
            every { context.mainExecutor } returns Executor { it.run() }
            every { context.getString(any()) } returns "Receiver"
            val view = mockk<WebView>(relaxed = true)
            every { view.context } returns context
            val manager = WebViewAudioManager(view, this, {}, preferSpeaker = true, startOnSpeaker = false)
            manager.onCallStarted()
            // No onTrackReady callback has happened, yet native drift must already be observed.
            verify(exactly = 1) { audio.registerAudioDeviceCallback(any(), null) }
            verify(exactly = 1) { audio.addOnCommunicationDeviceChangedListener(any(), any()) }
            manager.onWidgetReady()
            verify(atLeast = 2) { audio.setCommunicationDevice(receiver) }
            verify(atLeast = 2) { view.evaluateJavascript(match { it.contains("onAudioPlaybackStarted") }, null) }
            manager.onCallStopped()
            verify(exactly = 1) { audio.removeOnCommunicationDeviceChangedListener(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test
    fun `video receiver retains real identity without upstream camera disabling hint`() {
        val receiver = SerializableAudioDevice("1", "Receiver", AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val video = receiver.forVideoCall(true)
        assertThat(video.id).isEqualTo(receiver.id)
        assertThat(video.name).isEqualTo(receiver.name)
        assertThat(video.type).isEqualTo(receiver.type)
        assertThat(video.isEarpiece).isFalse()
        assertThat(video.isSpeaker).isFalse()
        assertThat(receiver.forVideoCall(false)).isEqualTo(receiver)
        assertThat(receiver.isEarpiece).isTrue()
    }

    @Test
    fun `speaker and external device metadata remain intact`() {
        listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET).forEach { type ->
            val device = SerializableAudioDevice(type.toString(), "Output", type)
            assertThat(device.forVideoCall(true)).isEqualTo(device)
        }
    }
}
