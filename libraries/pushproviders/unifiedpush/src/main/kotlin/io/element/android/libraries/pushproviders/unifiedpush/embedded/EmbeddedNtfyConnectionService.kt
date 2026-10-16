/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.Inject
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.pushproviders.unifiedpush.R
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class EmbeddedNtfyConnectionService : Service() {
    @Inject lateinit var registrationStore: EmbeddedNtfyRegistrationStore
    @Inject lateinit var messageHandler: EmbeddedNtfyMessageHandler
    @Inject lateinit var jsonProvider: JsonProvider
    @Inject lateinit var unifiedPushStore: UnifiedPushStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val openedSockets = ConcurrentHashMap<String, WebSocket>()
    private val lastSocketActivityAt = ConcurrentHashMap<String, Long>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val connectionRefreshMutex = Mutex()
    private val messageQueue = Channel<QueuedMessage>(Channel.UNLIMITED)
    private val forcedRefreshLock = Any()
    private var lastForcedConnectionRefreshAt: Long? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestNetworkRecoveryRefresh()
        }
    }
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(ntfyWebSocketPingIntervalSeconds(Build.MANUFACTURER), TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        bindings<EmbeddedNtfyConnectionServiceBindings>().inject(this)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CommonDrawables.ic_notification)
            .setContentTitle(getString(R.string.embedded_ntfy_foreground_title))
            .setContentText(getString(R.string.embedded_ntfy_foreground_message))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val foregroundStart = runCatchingExceptions {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        }
        if (foregroundStart.isFailure) {
            Timber.e(foregroundStart.exceptionOrNull(), "Unable to start built-in push foreground service")
            stopSelf()
            return
        }
        serviceScope.launch {
            processMessages()
        }
        serviceScope.launch {
            monitorConnectionLiveness()
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reconnectExisting = claimForcedConnectionRefresh(FORCED_CONNECTION_REFRESH_INTERVAL_MILLIS)
        serviceScope.launch {
            refreshConnections(reconnectExisting)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (networkCallbackRegistered) {
            runCatchingExceptions {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }.onFailure {
                Timber.w(it, "Unable to unregister built-in push network callback")
            }
        }
        serviceScope.cancel()
        sockets.values.forEach { it.close(NORMAL_CLOSURE_STATUS, "service stopped") }
        sockets.clear()
        openedSockets.clear()
        lastSocketActivityAt.clear()
        httpClient.dispatcher.executorService.shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.embedded_ntfy_channel_name))
            .setVibrationEnabled(false)
            .setSound(null, null)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        runCatchingExceptions {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }.onSuccess {
            networkCallbackRegistered = true
        }.onFailure {
            Timber.w(it, "Unable to monitor network recovery for built-in push")
        }
    }

    private fun requestNetworkRecoveryRefresh() {
        if (!claimForcedConnectionRefresh(NETWORK_RECOVERY_REFRESH_INTERVAL_MILLIS)) return
        Timber.d("Network available; refreshing built-in push connections")
        serviceScope.launch {
            refreshConnections(reconnectExisting = true)
        }
    }

    private fun claimForcedConnectionRefresh(minimumIntervalMillis: Long): Boolean = synchronized(forcedRefreshLock) {
        val now = SystemClock.elapsedRealtime()
        val shouldRefresh = shouldForceConnectionRefresh(lastForcedConnectionRefreshAt, now, minimumIntervalMillis)
        if (shouldRefresh) {
            lastForcedConnectionRefreshAt = now
        }
        shouldRefresh
    }

    private suspend fun refreshConnections(reconnectExisting: Boolean): Unit = connectionRefreshMutex.withLock {
        val registrations = try {
            registrationStore.getAll()
        } catch (failure: Exception) {
            Timber.e(failure, "Unable to load built-in push credentials")
            stopSelf()
            return
        }
        if (registrations.isEmpty()) {
            stopSelf()
            return
        }
        val activeSecrets = registrations.mapTo(mutableSetOf()) { it.clientSecret }
        sockets.entries.toList().forEach { (clientSecret, socket) ->
            if (clientSecret !in activeSecrets) {
                if (sockets.remove(clientSecret, socket)) {
                    openedSockets.remove(clientSecret, socket)
                    lastSocketActivityAt.remove(clientSecret)
                    reconnectAttempts.remove(clientSecret)
                    socket.close(NORMAL_CLOSURE_STATUS, "registration removed")
                }
            }
        }
        if (reconnectExisting) {
            // A foreground service can survive while its WebSocket silently becomes unusable after
            // long device sleep or an OEM network freeze. A later start request means the app is
            // active again, so replace any cached sockets instead of treating their map entries as
            // proof of a live connection. The persisted ntfy cursor replays messages missed while
            // disconnected.
            sockets.entries.toList().forEach { (clientSecret, socket) ->
                if (sockets.remove(clientSecret, socket)) {
                    openedSockets.remove(clientSecret, socket)
                    lastSocketActivityAt.remove(clientSecret)
                    reconnectAttempts.remove(clientSecret)
                    socket.cancel()
                }
            }
        }
        registrations.forEach { registration ->
            if (!sockets.containsKey(registration.clientSecret)) {
                connect(registration)
            }
        }
    }

    private fun connect(registration: EmbeddedNtfyRegistration) {
        val request = try {
            val url = registration.server.toHttpUrl().newBuilder()
                .addPathSegment(registration.topic)
                .addPathSegment("ws")
                .apply {
                    registration.lastMessageId?.let { addQueryParameter("since", it) }
                }
                .build()
            Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(registration.username, registration.password))
                .build()
        } catch (failure: Exception) {
            Timber.e(failure, "Invalid built-in push registration")
            return
        }
        val listener = ConnectionListener(registration)
        val socket = httpClient.newWebSocket(request, listener)
        val previous = sockets.putIfAbsent(registration.clientSecret, socket)
        if (previous != null) {
            socket.close(NORMAL_CLOSURE_STATUS, "duplicate connection")
        } else {
            lastSocketActivityAt[registration.clientSecret] = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun monitorConnectionLiveness() {
        val manufacturer = Build.MANUFACTURER
        val checkIntervalMillis = ntfyLivenessCheckIntervalMillis(manufacturer)
        val staleTimeoutMillis = ntfyStaleConnectionTimeoutMillis(
            manufacturer = manufacturer,
            isNextDeployment = NextDeploymentConfig.isNextDeployment,
        )
        while (serviceScope.isActive) {
            delay(checkIntervalMillis)
            refreshStaleConnections(
                now = SystemClock.elapsedRealtime(),
                staleTimeoutMillis = staleTimeoutMillis,
            )
        }
    }

    private suspend fun refreshStaleConnections(
        now: Long,
        staleTimeoutMillis: Long,
    ): Unit = connectionRefreshMutex.withLock {
        val staleClientSecrets = sockets.keys.filter { clientSecret ->
            shouldRefreshNtfyOpening(openedSockets[clientSecret] === sockets[clientSecret],
                lastSocketActivityAt[clientSecret], now, staleTimeoutMillis)
        }
        if (staleClientSecrets.isEmpty()) return

        val registrations = try {
            registrationStore.getAll().associateBy { it.clientSecret }
        } catch (failure: Exception) {
            Timber.e(failure, "Unable to reload built-in push credentials for liveness recovery")
            return
        }
        var refreshedConnectionCount = 0
        staleClientSecrets.forEach { clientSecret ->
            val socket = sockets[clientSecret] ?: return@forEach
            if (!shouldRefreshNtfyOpening(
                    opened = openedSockets[clientSecret] === socket,
                    lastActivityAt = lastSocketActivityAt[clientSecret],
                    now = SystemClock.elapsedRealtime(),
                    timeoutMillis = staleTimeoutMillis,
                )
            ) return@forEach
            if (sockets.remove(clientSecret, socket)) {
                openedSockets.remove(clientSecret, socket)
                lastSocketActivityAt.remove(clientSecret)
                reconnectAttempts.remove(clientSecret)
                socket.cancel()
                registrations[clientSecret]?.let(::connect)
                refreshedConnectionCount += 1
            }
        }
        if (refreshedConnectionCount > 0) {
            Timber.w("Refreshed %d stale built-in push connection(s)", refreshedConnectionCount)
        }
    }

    private suspend fun processMessages() {
        for (queuedMessage in messageQueue) {
            val envelope = queuedMessage.envelope
            val message = envelope.messageBytes()
            if (message == null) {
                Timber.w("Ignoring built-in push message with unsupported encoding")
                continue
            }
            var processingAttempt = 0
            while (serviceScope.isActive) {
                try {
                    messageHandler.handle(
                        content = message,
                        clientSecret = queuedMessage.registration.clientSecret,
                        managementToken = queuedMessage.registration.managementToken,
                    )
                    break
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    processingAttempt += 1
                    Timber.e(
                        failure,
                        "Unable to process built-in push message; preserving cursor for ordered retry (attempt %d)",
                        processingAttempt,
                    )
                    delay(messageRetryDelayMillis(processingAttempt))
                }
            }
            envelope.id?.let { messageId ->
                var cursorAttempt = 0
                while (serviceScope.isActive) {
                    try {
                        registrationStore.updateLastMessageId(queuedMessage.registration.clientSecret, messageId)
                        break
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        cursorAttempt += 1
                        Timber.e(
                            failure,
                            "Unable to persist built-in push cursor; preserving message order (attempt %d)",
                            cursorAttempt,
                        )
                        delay(messageRetryDelayMillis(cursorAttempt))
                    }
                }
            }
        }
    }

    private fun reconnect(
        registration: EmbeddedNtfyRegistration,
        socket: WebSocket,
        retryAfterHeader: String? = null,
    ) {
        if (!sockets.remove(registration.clientSecret, socket) || !serviceScope.isActive) return
        openedSockets.remove(registration.clientSecret, socket)
        lastSocketActivityAt.remove(registration.clientSecret)
        val attempt = reconnectAttempts.merge(registration.clientSecret, 1, Int::plus) ?: 1
        val delayMillis = ntfyReconnectDelayMillis(
            attempt = attempt,
            retryAfterHeader = retryAfterHeader,
            jitterMillis = Random.nextLong(0, RECONNECT_JITTER_MAX_MILLIS + 1),
        )
        serviceScope.launch {
            delay(delayMillis)
            val current = try {
                registrationStore.get(registration.clientSecret)
            } catch (failure: Exception) {
                Timber.e(failure, "Unable to reload built-in push credentials")
                null
            }
            if (current != null && serviceScope.isActive && !sockets.containsKey(current.clientSecret)) {
                connect(current)
            }
        }
    }

    private fun retireInvalidCredential(registration: EmbeddedNtfyRegistration, socket: WebSocket) {
        if (!sockets.remove(registration.clientSecret, socket) || !serviceScope.isActive) return
        openedSockets.remove(registration.clientSecret, socket)
        lastSocketActivityAt.remove(registration.clientSecret)
        reconnectAttempts.remove(registration.clientSecret)
        socket.cancel()
        serviceScope.launch {
            try {
                registrationStore.remove(registration.clientSecret)
                unifiedPushStore.storeUpEndpoint(registration.clientSecret, null)
                unifiedPushStore.storePushGateway(registration.clientSecret, null)
                Timber.w("Retired rejected built-in push credential; a later active-session registration will reprovision it")
                if (registrationStore.getAll().isEmpty()) stopSelf()
            } catch (failure: Exception) {
                // Do not retry the rejected socket. A later service start retries local cleanup.
                Timber.e(failure, "Unable to retire rejected built-in push credential")
            }
        }
    }

    private inner class ConnectionListener(
        private val registration: EmbeddedNtfyRegistration,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sockets[registration.clientSecret] !== webSocket) {
                webSocket.close(NORMAL_CLOSURE_STATUS, "superseded connection")
                return
            }
            reconnectAttempts.remove(registration.clientSecret)
            openedSockets[registration.clientSecret] = webSocket
            lastSocketActivityAt[registration.clientSecret] = SystemClock.elapsedRealtime()
            Timber.d("Built-in push connection established")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sockets[registration.clientSecret] !== webSocket) return
            lastSocketActivityAt[registration.clientSecret] = SystemClock.elapsedRealtime()
            val envelope = try {
                jsonProvider().decodeFromString<NtfyEnvelope>(text)
            } catch (failure: Exception) {
                Timber.w("Ignoring malformed built-in push envelope")
                return
            }
            if (envelope.event == "message") {
                messageQueue.trySend(QueuedMessage(registration, envelope))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            reconnect(registration, webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "Built-in push connection interrupted")
            when (ntfyFailureAction(response?.code)) {
                NtfyFailureAction.RETIRE_CREDENTIAL -> retireInvalidCredential(registration, webSocket)
                NtfyFailureAction.RETRY -> reconnect(
                    registration,
                    webSocket,
                    retryAfterHeader = response?.takeIf { it.code == 429 }?.header("Retry-After"),
                )
            }
        }
    }

    private data class QueuedMessage(
        val registration: EmbeddedNtfyRegistration,
        val envelope: NtfyEnvelope,
    )

    companion object {
        private const val CHANNEL_ID = "embedded_ntfy_connection"
        private const val NOTIFICATION_ID = 1011
        private const val RECONNECT_JITTER_MAX_MILLIS = 1_000L
        private const val NORMAL_CLOSURE_STATUS = 1000
        internal const val FORCED_CONNECTION_REFRESH_INTERVAL_MILLIS = 30_000L
        internal const val NETWORK_RECOVERY_REFRESH_INTERVAL_MILLIS = 10_000L
        internal const val AGGRESSIVE_PING_INTERVAL_SECONDS = 3L
        internal const val DEFAULT_PING_INTERVAL_SECONDS = 30L
        // This watchdog bounds opening only. An established ntfy WebSocket can
        // remain text-silent indefinitely while Ping/Pong is healthy. OkHttp's
        // configured ping timeout calls onFailure for a lost established socket.
        internal const val AGGRESSIVE_STALE_CONNECTION_TIMEOUT_MILLIS = 60_000L
        internal const val STALE_CONNECTION_TIMEOUT_MILLIS = 60_000L

        fun start(context: Context) {
            runCatchingExceptions {
                ContextCompat.startForegroundService(context, Intent(context, EmbeddedNtfyConnectionService::class.java))
            }.onFailure {
                Timber.e(it, "Unable to request built-in push service start")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedNtfyConnectionService::class.java))
        }
    }
}

internal fun ntfyWebSocketPingIntervalSeconds(manufacturer: String): Long =
    if (manufacturer.requiresAggressiveNtfyRecovery()) {
        EmbeddedNtfyConnectionService.AGGRESSIVE_PING_INTERVAL_SECONDS
    } else {
        EmbeddedNtfyConnectionService.DEFAULT_PING_INTERVAL_SECONDS
    }

internal fun ntfyLivenessCheckIntervalMillis(manufacturer: String): Long =
    TimeUnit.SECONDS.toMillis(ntfyWebSocketPingIntervalSeconds(manufacturer))

internal fun ntfyStaleConnectionTimeoutMillis(manufacturer: String, isNextDeployment: Boolean): Long =
    if (isNextDeployment && manufacturer.requiresAggressiveNtfyRecovery()) {
        EmbeddedNtfyConnectionService.AGGRESSIVE_STALE_CONNECTION_TIMEOUT_MILLIS
    } else {
        EmbeddedNtfyConnectionService.STALE_CONNECTION_TIMEOUT_MILLIS
    }

private fun String.requiresAggressiveNtfyRecovery(): Boolean = when (trim().lowercase()) {
    "huawei", "honor", "xiaomi", "redmi", "poco" -> true
    else -> false
}

internal fun shouldForceConnectionRefresh(
    lastRefreshAt: Long?,
    now: Long,
    minimumIntervalMillis: Long = EmbeddedNtfyConnectionService.FORCED_CONNECTION_REFRESH_INTERVAL_MILLIS,
): Boolean = lastRefreshAt == null || now - lastRefreshAt >= minimumIntervalMillis

internal fun isConnectionStale(
    lastActivityAt: Long?,
    now: Long,
    timeoutMillis: Long = EmbeddedNtfyConnectionService.STALE_CONNECTION_TIMEOUT_MILLIS,
): Boolean = lastActivityAt != null && now - lastActivityAt >= timeoutMillis

internal fun shouldRefreshNtfyOpening(opened: Boolean, lastActivityAt: Long?, now: Long, timeoutMillis: Long): Boolean =
    !opened && isConnectionStale(lastActivityAt, now, timeoutMillis)

internal fun messageRetryDelayMillis(attempt: Int): Long =
    TimeUnit.SECONDS.toMillis(min(60, 1 shl min(attempt.coerceAtLeast(1), 6)).toLong())

internal enum class NtfyFailureAction {
    RETIRE_CREDENTIAL,
    RETRY,
}

internal fun ntfyFailureAction(statusCode: Int?): NtfyFailureAction = when (statusCode) {
    401, 403 -> NtfyFailureAction.RETIRE_CREDENTIAL
    else -> NtfyFailureAction.RETRY
}

internal fun ntfyReconnectDelayMillis(
    attempt: Int,
    retryAfterHeader: String?,
    jitterMillis: Long,
): Long {
    val exponentialMillis = TimeUnit.SECONDS.toMillis(
        min(60, 1 shl min(attempt.coerceAtLeast(1), 6)).toLong()
    )
    val retryAfterMillis = retryAfterHeader
        ?.trim()
        ?.toLongOrNull()
        ?.coerceIn(0L, TimeUnit.DAYS.toSeconds(1))
        ?.let { TimeUnit.SECONDS.toMillis(it) }
        ?: 0L
    return max(exponentialMillis, retryAfterMillis) + jitterMillis.coerceIn(0L, 1_000L)
}
