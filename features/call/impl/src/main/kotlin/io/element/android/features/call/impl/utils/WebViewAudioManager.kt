/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.content.ContextCompat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This class manages the audio devices for a WebView.
 *
 * It listens for audio device changes and updates the WebView with the available devices.
 * It also handles the selection of the audio device by the user in the WebView and the default audio device based on the device type.
 *
 * See also: [Element Call controls docs.](https://github.com/element-hq/element-call/blob/livekit/docs/controls.md#audio-devices)
 */
class WebViewAudioManager(
    private val webView: WebView,
    private val coroutineScope: CoroutineScope,
    private val onInvalidAudioDeviceAdded: (InvalidAudioDeviceReason) -> Unit,
    private val preferSpeaker: Boolean = false,
    private val startOnSpeaker: Boolean = preferSpeaker,
) {
    private val json by lazy {
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }

    /**
     * Whether to disable bluetooth audio devices. This must be done on Android versions lower than Android 12,
     * since the WebView approach breaks when using the legacy Bluetooth audio APIs.
     */
    private val disableBluetoothAudioDevices = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /**
     * This flag indicates whether the WebView audio is enabled or not. By default, it is enabled.
     */
    private val isWebViewAudioEnabled = AtomicBoolean(true)

    /**
     * Store the device id requested by EC, and re-set it if something try to switch (only android S+).
     */
    private var ecRequestedDeviceId: String? = null
    private var publishedDeviceId: String? = null

    /**
     * The list of device types that are considered as communication devices, sorted by likelihood of it being used for communication.
     */
    private val wantedDeviceTypes = listOf(
        // Paired bluetooth device with microphone
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        // USB devices which can play or record audio
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        // Wired audio devices
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        // The built-in earpiece of the device
        if (startOnSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        // The built-in speaker of the device
        if (startOnSpeaker) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private val audioDeviceComparator = Comparator<AudioDeviceInfo> { a, b ->
        // If the device type is not in the wantedDeviceTypes list, we give it a high index, (i.e. low priority)
        val indexOfA = wantedDeviceTypes.indexOf(a.type).let { if (it == -1) Int.MAX_VALUE else it }
        val indexOfB = wantedDeviceTypes.indexOf(b.type).let { if (it == -1) Int.MAX_VALUE else it }
        indexOfA.compareTo(indexOfB)
    }

    private val audioManager = webView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * This wake lock is used to turn off the screen when the proximity sensor is triggered during a call,
     * if the selected audio device is the built-in earpiece.
     */
    private val proximitySensorWakeLock by lazy {
        webView.context.getSystemService<PowerManager>()
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "${webView.context.packageName}:ProximitySensorCallWakeLock")
    }

    /**
     * Used to ensure that only one coroutine can access the proximity sensor wake lock at a time, preventing re-acquiring or re-releasing it.
     */
    private val proximitySensorMutex = Mutex()

    /**
     * This listener tracks the current communication device and updates the WebView when it changes.
     */
    @get:RequiresApi(Build.VERSION_CODES.S)
    private val commsDeviceChangedListener by lazy {
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Timber.d("Audio device changed, type: ${device?.id}")
            val wantedDevice = this.ecRequestedDeviceId
            if (wantedDevice != null && this.ecRequestedDeviceId != device?.id?.toString()) {
                // We want to ensure that we stick to what EC selected even if it was changed outside
                Timber.d("Audio device changed to unwanted device ${device?.id}, enforce using the expected device $wantedDevice")
                audioManager.selectAudioDevice(wantedDevice)
            }
            publishAudioRoute()
        }
    }

    /**
     * This callback is used to listen for audio device changes coming from the OS.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val validNewDevices = addedDevices.orEmpty().filter { it.type in wantedDeviceTypes && it.isSink }
            if (validNewDevices.isEmpty()) return

            // We need to calculate the available devices ourselves, since calling `listAudioDevices` will return an outdated list
            val audioDevices = (listAudioDevices() + validNewDevices).distinctBy { it.id }.sortedWith(audioDeviceComparator)
            setAvailableAudioDevices(audioDevices.map { SerializableAudioDevice.fromAudioDeviceInfo(it, webView.context) })
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // Update the available devices
            // Element Call will then decide to switch devices if needed
            setAvailableAudioDevices()
        }
    }

    /**
     * Previously selected device, used to restore the selection when the selected device is removed.
     */
    private var previousSelectedDevice: AudioDeviceInfo? = null

    private var hasRegisteredCallbacks = false

    /**
     * Marks if the WebView audio is in call mode or not.
     */
    val isInCallMode = AtomicBoolean(false)

    init {
        // Apparently, registering the javascript interface takes a while, so registering and immediately using it doesn't work
        // We register it ahead of time to avoid this issue
        registerWebViewDeviceSelectedCallback()
    }

    /**
     * Call this method when the call starts to enable in-call audio mode.
     *
     * It'll set the audio mode to [AudioManager.MODE_IN_COMMUNICATION] if possible, register the audio device callback and set the available audio devices.
     */
    fun onCallStarted() {
        if (!isInCallMode.compareAndSet(false, true)) {
            Timber.w("Audio: tried to enable webview in-call audio mode while already in it")
            return
        }

        Timber.d("Audio: enabling webview in-call audio mode")

        audioManager.mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Set 'voice call' mode so volume keys actually control the call volume
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            // Workaround for Android 12 and lower, otherwise changing the audio device doesn't work
            AudioManager.MODE_NORMAL
        }

        // Route audio before WebRTC starts playback. Otherwise Android can briefly pick the
        // speaker and Element Call only corrects it after its playback callback arrives.
        val initialDevice = listAudioDevices().firstOrNull()
        previousSelectedDevice = initialDevice
        ecRequestedDeviceId = initialDevice?.id?.toString()
        audioManager.selectAudioDevice(initialDevice)
        registerAudioCallbacks()
        setWebViewAndroidNativeBridge()
    }

    /** Native admission can precede JS loading. Rebind when the real controls become available. */
    fun onWidgetReady() {
        setWebViewAndroidNativeBridge()
        if (!isInCallMode.get()) return
        ecRequestedDeviceId?.let { audioManager.selectAudioDevice(it) }
        setAvailableAudioDevices()
    }

    private fun registerAudioCallbacks() {
        if (hasRegisteredCallbacks) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(webView.context), commsDeviceChangedListener)
        }
        hasRegisteredCallbacks = true
    }

    /**
     * Call this method when the call stops to disable in-call audio mode.
     *
     * It's the counterpart of [onCallStarted], and should be called as a pair with it once the call has ended.
     */
    fun onCallStopped() {
        if (!isInCallMode.compareAndSet(true, false)) {
            Timber.w("Audio: tried to disable webview in-call audio mode while already disabled")
            return
        }

        // Since this should run when the call is no longer running, it should be OK to not use the mutex here
        if (proximitySensorWakeLock?.isHeld == true) {
            proximitySensorWakeLock?.release()
        }

        audioManager.mode = AudioManager.MODE_NORMAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
            if (hasRegisteredCallbacks) {
                audioManager.removeOnCommunicationDeviceChangedListener(commsDeviceChangedListener)
            }
        }

        if (hasRegisteredCallbacks) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        hasRegisteredCallbacks = false
        ecRequestedDeviceId = null
        publishedDeviceId = null
        previousSelectedDevice = null
    }

    /**
     * Registers the WebView audio device selected callback.
     *
     * This should be called when the WebView is created to ensure that the callback is set before any audio device selection is made.
     */
    private fun registerWebViewDeviceSelectedCallback() {
        val webViewAudioDeviceSelectedCallback = AndroidWebViewAudioBridge(
            onAudioDeviceSelected = { selectedDeviceId ->
                previousSelectedDevice = listAudioDevices().find { it.id.toString() == selectedDeviceId }
                this.ecRequestedDeviceId = selectedDeviceId
                audioManager.selectAudioDevice(selectedDeviceId)
            },
            onAudioPlaybackStarted = {
                coroutineScope.launch(Dispatchers.Main) {
                    if (!isInCallMode.get()) return@launch
                    ecRequestedDeviceId?.let { audioManager.selectAudioDevice(it) }
                    // Playback is ready now, so expose devices immediately. Delaying this made the
                    // UI and native route disagree for two seconds on every new call.
                    setAvailableAudioDevices()

                    // Registering the audio devices changed callback will also set the default audio device
                    registerAudioCallbacks()
                }
            }
        )
        Timber.d("Setting androidNativeBridge javascript interface in webview")
        webView.addJavascriptInterface(webViewAudioDeviceSelectedCallback, "androidNativeBridge")
    }

    /**
     * Assigns the callback in the WebView to be called when the user selects an audio device.
     *
     * It should be called with some delay after [registerWebViewDeviceSelectedCallback].
     */
    private fun setWebViewAndroidNativeBridge() {
        Timber.d("Adding callback in controls.onAudioPlaybackStarted")
        webView.evaluateJavascript("if (window.controls) window.controls.onAudioPlaybackStarted = () => { androidNativeBridge.onTrackReady(); };", null)
        Timber.d("Adding callback in controls.onOutputDeviceSelect")
        webView.evaluateJavascript("if (window.controls) window.controls.onOutputDeviceSelect = (id) => { androidNativeBridge.setOutputDevice(id); };", null)
    }

    /**
     * Returns the list of available audio devices, sorted by likelihood of it being used for communication.
     *
     * On Android 11 ([Build.VERSION_CODES.R]) and lower, it returns the list of output devices as a fallback.
     */
    private fun listAudioDevices(): List<AudioDeviceInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            val rawAudioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            rawAudioDevices.filter { it.type in wantedDeviceTypes && it.isSink }
        }.sortedWith(audioDeviceComparator)
    }

    /**
     * Sets the available audio devices in the WebView.
     *
     * @param devices The list of audio devices to set. If not provided, it will use the current list of audio devices.
     */
    private fun setAvailableAudioDevices(
        devices: List<SerializableAudioDevice> = listAudioDevices().map {
            SerializableAudioDevice.fromAudioDeviceInfo(it, webView.context)
        },
    ) {
        Timber.d("Updating available audio devices")
        val deviceList = json.encodeToString(devices.map { it.forVideoCall(preferSpeaker) })
        webView.evaluateJavascript("window.controls?.setAvailableOutputDevices($deviceList);", {
            Timber.d("Audio: setAvailableOutputDevices result: $it")
        })
        publishAudioRoute()
    }

    /** Switch only Android's output route. Camera state belongs to the camera control. */
    fun toggleSpeaker() {
        if (!preferSpeaker || !isInCallMode.get()) return
        val current = currentAudioDevice()
        val targetType = if (current?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        } else {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val target = listAudioDevices().firstOrNull { it.type == targetType } ?: return
        previousSelectedDevice = target
        ecRequestedDeviceId = target.id.toString()
        audioManager.selectAudioDevice(target)
        publishAudioRoute()
    }

    private fun currentAudioDevice(): AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice
    } else {
        previousSelectedDevice
    }

    private fun publishAudioRoute() {
        if (!preferSpeaker || !isInCallMode.get()) return
        coroutineScope.launch(Dispatchers.Main) {
            if (!isInCallMode.get()) return@launch
            val devices = listAudioDevices()
            val current = currentAudioDevice()
            val speakerOn = current?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            // Publish confirmed OS routing, including asynchronous communication-device changes.
            val selectedId = current?.id?.toString()
            if (selectedId != null && selectedId != publishedDeviceId) {
                publishedDeviceId = selectedId
                webView.evaluateJavascript("window.controls?.setOutputDevice(${json.encodeToString(selectedId)});", null)
            }
            val canToggle = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } &&
                devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            webView.evaluateJavascript(
                "window.yizheCallAudio = {speakerOn:$speakerOn,canToggle:$canToggle}; window.yizheRefreshCallPresentation?.();",
                null,
            )
        }
    }

    /**
     * Selects the audio device on the OS based on the provided device id.
     *
     * It will select the device only if it is available in the list of audio devices.
     *
     * @param device The id of the audio device to select.
     */
    private fun AudioManager.selectAudioDevice(device: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioDevice = availableCommunicationDevices.find { it.id.toString() == device }
            selectAudioDevice(audioDevice)
        } else {
            val rawAudioDevices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val audioDevice = rawAudioDevices.find { it.id.toString() == device }
            selectAudioDevice(audioDevice)
        }
    }

    /**
     * Selects the audio device on the OS based on the provided device info.
     *
     * @param device The info of the audio device to select, or none to clear the selected device.
     */
    private fun AudioManager.selectAudioDevice(device: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) {
                runCatchingExceptions {
                    Timber.d(
                        "Setting communication device: ${device.id} - " +
                            deviceName(webView.context, device.type, device.productName.toString())
                    )
                    if (!setCommunicationDevice(device)) {
                        Timber.w("Failed to setCommunication device")
                    }
                }.onFailure {
                    Timber.e(it, "Could not set communication device.")
                }
            } else {
                runCatchingExceptions {
                    clearCommunicationDevice()
                }.onFailure {
                    Timber.e(it, "Could not clear communication device.")
                }
            }
        } else {
            // On Android 11 and lower, we don't have the concept of communication devices
            // We have to call the right methods based on the device type
            if (device != null) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && disableBluetoothAudioDevices) {
                    Timber.w("Bluetooth audio devices are disabled on this Android version")
                    setAudioEnabled(false)
                    onInvalidAudioDeviceAdded(InvalidAudioDeviceReason.BT_AUDIO_DEVICE_DISABLED)
                    return
                }
                setAudioEnabled(true)
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = false
                isBluetoothScoOn = false
            }
        }

        coroutineScope.launch {
            proximitySensorMutex.withLock {
                @Suppress("WakeLock", "WakeLockTimeout")
                if (!preferSpeaker && device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    if (proximitySensorWakeLock?.isHeld == false) {
                        // If the device is the built-in earpiece, we need to acquire the proximity sensor wake lock
                        proximitySensorWakeLock?.acquire()
                    }
                } else if (proximitySensorWakeLock?.isHeld == true) {
                    // If the device is no longer the earpiece, we need to release the wake lock
                    proximitySensorWakeLock?.release()
                }
            }
        }
        publishAudioRoute()
    }

    /**
     * Sets whether the audio is enabled for Element Call in the WebView.
     * It will only perform the change if the audio state has changed.
     */
    private fun setAudioEnabled(enabled: Boolean) {
        coroutineScope.launch(Dispatchers.Main) {
            Timber.d("Setting audio enabled in Element Call: $enabled")
            if (isWebViewAudioEnabled.getAndSet(enabled) != enabled) {
                webView.evaluateJavascript("window.controls.setAudioEnabled($enabled);", null)
            }
        }
    }
}

/**
 * This class is used to handle the audio device selection in the WebView.
 * It listens for the audio device selection event and calls the callback with the selected device ID.
 */
private class AndroidWebViewAudioBridge(
    private val onAudioDeviceSelected: (String) -> Unit,
    private val onAudioPlaybackStarted: () -> Unit,
) {
    @JavascriptInterface
    fun setOutputDevice(id: String) {
        Timber.d("Audio device selected in webview, id: $id")
        onAudioDeviceSelected(id)
    }

    @JavascriptInterface
    fun onTrackReady() {
        // This method can be used to notify the WebView that the audio track is ready
        // It can be used to start playing audio or to update the UI
        Timber.d("Audio track is ready")

        onAudioPlaybackStarted()
    }
}

private fun deviceName(context: Context, type: Int, name: String): String {
    val typePart = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_bluetooth)
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_usb_accessory)
        AudioDeviceInfo.TYPE_USB_DEVICE -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_usb_device)
        AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_usb_headset)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_wired_headset)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_wired_headphones)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_earpiece)
        else -> context.getString(io.element.android.features.call.impl.R.string.call_audio_output_unknown)
    }
    return if (isBuiltIn(type)) {
        typePart
    } else {
        "$typePart - $name"
    }
}

private fun isBuiltIn(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> true
    else -> false
}

enum class InvalidAudioDeviceReason {
    BT_AUDIO_DEVICE_DISABLED,
}

/**
 * This class is used to serialize the audio device information to JSON.
 */
@Suppress("unused")
@Serializable
internal data class SerializableAudioDevice(
    val id: String,
    val name: String,
    @Transient val type: Int = 0,
    // These have to be part of the constructor for the JSON serializer to pick them up
    val isEarpiece: Boolean = type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    val isSpeaker: Boolean = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    val isExternalHeadset: Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
) {
    // Element Call interprets the earpiece hint as a request to disable video. For video
    // calls retain the real id/name and route, but omit that camera-affecting policy hint.
    // Android and the native output control remain the source of truth for routing.
    fun forVideoCall(isVideoCall: Boolean): SerializableAudioDevice =
        if (isVideoCall && isEarpiece) copy(isEarpiece = false) else this

    companion object {
        fun fromAudioDeviceInfo(audioDeviceInfo: AudioDeviceInfo, context: Context): SerializableAudioDevice {
            return SerializableAudioDevice(
                id = audioDeviceInfo.id.toString(),
                name = deviceName(context, type = audioDeviceInfo.type, name = audioDeviceInfo.productName.toString()),
                type = audioDeviceInfo.type,
            )
        }
    }
}
