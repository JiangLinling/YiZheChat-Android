/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import android.os.Bundle
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.push.api.push.FetchPushForegroundServiceManager
import io.element.android.libraries.pushproviders.api.PushHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

private val loggerTag = LoggerTag("YiZheHuaweiMessagingService", LoggerTag.PushLoggerTag)

class YiZheHuaweiMessagingService : HmsMessageService() {
    @Inject lateinit var huaweiPushProvider: HuaweiPushProvider
    @Inject lateinit var diagnostics: HuaweiPushDiagnostics
    @Inject lateinit var newTokenHandler: HuaweiPushNewTokenHandler
    @Inject lateinit var pushParser: HuaweiPushParser
    @Inject lateinit var pushHandler: PushHandler
    @Inject lateinit var fetchPushForegroundServiceManager: FetchPushForegroundServiceManager
    @AppCoroutineScope
    @Inject lateinit var coroutineScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        bindings<HuaweiPushBindings>().inject(this)
    }

    override fun onNewToken(token: String?) {
        super.onNewToken(token)
        handleNewToken(token)
    }

    override fun onNewToken(token: String?, bundle: Bundle?) {
        super.onNewToken(token, bundle)
        handleNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        if (!isHuaweiPushTarget(Build.MANUFACTURER, Build.BRAND) || message == null) return
        val callbackAt = System.currentTimeMillis()
        val isHighPriority = message.urgency == RemoteMessage.PRIORITY_HIGH
        if (isHighPriority) {
            Timber.tag(loggerTag.value).i("Starting ForegroundServiceManager for high-priority Huawei push")
            fetchPushForegroundServiceManager.start()
        }
        coroutineScope.launch {
            val data = message.dataOfMap.orEmpty()
            val resolvedKind = data["kind"]?.lowercase() ?: resolveKindFromData(data)
            val nestedEventType = readNestedField(data, "event_type")
            val pushData = pushParser.parse(data)
            if (pushData == null) {
                Timber.tag(loggerTag.value).w("Invalid Huawei Push data received")
                pushHandler.handleInvalid(
                    providerInfo = HuaweiPushConfig.NAME,
                    data = data.keys.sorted().joinToString(","),
                )
                if (isHighPriority) fetchPushForegroundServiceManager.stop()
                return@launch
            }

            val sentTime = message.sentTime.takeIf { it > 0L } ?: callbackAt
            val callbackDelayMs = callbackAt - sentTime
            val callbackForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val callbackState = ProcessLifecycleOwner.get().lifecycle.currentState
            Timber.tag(loggerTag.value).i(
                "onMessageReceived() received | type=%s nestedEventType=%s event=%s room=%s callbackDelayMs=%s processState=%s lifecycle=%s",
                resolvedKind ?: "message",
                nestedEventType ?: "n/a",
                pushData.eventId,
                pushData.roomId,
                callbackDelayMs,
                callbackForeground,
                callbackState,
            )

            // Huawei is only a wake-up transport. Never trust its unencrypted `kind` hint to
            // decide which UI is allowed to open. The common push pipeline resolves the Matrix
            // event, validates real call invites, decrypts authorized messages, and applies the
            // same notification rules used by the primary ntfy path. This also makes a malformed
            // or stale vendor payload fail closed to ordinary notification handling instead of
            // opening the incoming-call screen directly.
            val handled = pushHandler.handle(
                pushData = pushData,
                providerInfo = HuaweiPushConfig.NAME,
            )
            Timber.tag(loggerTag.value).i(
                "VENDOR_FALLBACK handoff result | kind=%s handled=%s event=%s room=%s",
                resolvedKind ?: "message",
                handled,
                pushData.eventId,
                pushData.roomId,
            )
            if (!handled && isHighPriority) fetchPushForegroundServiceManager.stop()
        }
    }

    private fun resolveKindFromData(data: Map<String, String>): String? {
        return readNestedField(data, "kind")?.lowercase()
    }

    private fun readNestedField(data: Map<String, String>, key: String): String? {
        val raw = data["data"] ?: return null
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw).optString(key) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun handleNewToken(token: String?) {
        if (!isHuaweiPushTarget(Build.MANUFACTURER, Build.BRAND)) return
        huaweiPushProvider.recordTokenRefresh(token)
        if (token.isNullOrBlank()) {
            diagnostics.recordFailure(IllegalArgumentException("Huawei Push token is blank"))
            return
        }
        diagnostics.recordStage("TOKEN_CALLBACK")
        coroutineScope.launch {
            newTokenHandler.handle(token).fold(
                onSuccess = { diagnostics.recordSuccess() },
                onFailure = { diagnostics.recordFailure(it) },
            )
        }
    }
}
