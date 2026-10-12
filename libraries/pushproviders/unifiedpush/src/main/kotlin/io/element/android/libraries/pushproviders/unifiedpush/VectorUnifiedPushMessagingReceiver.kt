/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.push.api.push.FetchPushForegroundServiceManager
import io.element.android.libraries.pushproviders.unifiedpush.embedded.EmbeddedNtfyMessageHandler
import io.element.android.libraries.pushproviders.unifiedpush.registration.EndpointRegistrationHandler
import io.element.android.libraries.pushproviders.unifiedpush.registration.RegistrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber

private val loggerTag = LoggerTag("VectorUnifiedPushMessagingReceiver", LoggerTag.PushLoggerTag)

class VectorUnifiedPushMessagingReceiver : MessagingReceiver() {
    @Inject lateinit var messageHandler: EmbeddedNtfyMessageHandler
    @Inject lateinit var guardServiceStarter: GuardServiceStarter
    @Inject lateinit var unifiedPushStore: UnifiedPushStore
    @Inject lateinit var unifiedPushGatewayResolver: UnifiedPushGatewayResolver
    @Inject lateinit var unifiedPushGatewayUrlResolver: UnifiedPushGatewayUrlResolver
    @Inject lateinit var newGatewayHandler: UnifiedPushNewGatewayHandler
    @Inject lateinit var removedGatewayHandler: UnifiedPushRemovedGatewayHandler
    @Inject lateinit var endpointRegistrationHandler: EndpointRegistrationHandler
    @Inject lateinit var fetchPushForegroundServiceManager: FetchPushForegroundServiceManager
    @AppCoroutineScope
    @Inject lateinit var coroutineScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // We only need to inject this object once
        if (!this::messageHandler.isInitialized) {
            context.bindings<VectorUnifiedPushMessagingReceiverBindings>().inject(this)
        }
        super.onReceive(context, intent)
    }

    /**
     * Called when message is received. The message contains the full POST body of the push message.
     *
     * @param context the Android context
     * @param message the message
     * @param instance connection, for multi-account
     */
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        // A push is also a low-cost signal to refresh the signed YiZheChat management channel.
        // The receiver is package-scoped and carries no message or account data.
        context.sendBroadcast(
            Intent(MANAGEMENT_SYNC_ACTION).setPackage(context.packageName)
        )
        fetchPushForegroundServiceManager.start()
        coroutineScope.launch {
            messageHandler.handle(
                content = message.content,
                clientSecret = instance,
                providerInfo = "${UnifiedPushConfig.NAME} - external distributor",
                startForegroundService = false,
            )
        }
    }

    /**
     * Called when a new endpoint is to be used for sending push messages.
     * You should send the endpoint to your application server and sync for missing notifications.
     */
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Timber.tag(loggerTag.value).d("Received a new UnifiedPush endpoint")
        coroutineScope.launch {
            val gateway = unifiedPushGatewayResolver.getGateway(endpoint.url)
                .let { gatewayResult ->
                    unifiedPushGatewayUrlResolver.resolve(gatewayResult, instance)
                }
            unifiedPushStore.storePushGateway(instance, gateway)
            val result = newGatewayHandler.handle(endpoint.url, gateway, instance)
                .onFailure {
                    Timber.tag(loggerTag.value).e(it, "Failed to handle new gateway")
                }
                .onSuccess {
                    unifiedPushStore.storeUpEndpoint(instance, endpoint.url)
                }
            endpointRegistrationHandler.registrationDone(
                RegistrationResult(
                    clientSecret = instance,
                    result = result,
                )
            )
        }
        guardServiceStarter.stop()
    }

    /**
     * Called when the registration is not possible, eg. no network.
     */
    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Timber.tag(loggerTag.value).e("UnifiedPush registration failed, reason: $reason")
        coroutineScope.launch {
            endpointRegistrationHandler.registrationDone(
                RegistrationResult(
                    clientSecret = instance,
                    result = Result.failure(Exception("Registration failed. Reason: $reason")),
                )
            )
        }
    }

    /**
     * Called when this application is unregistered from receiving push messages.
     */
    override fun onUnregistered(context: Context, instance: String) {
        Timber.tag(loggerTag.value).d("UnifiedPush registration removed")
        coroutineScope.launch {
            removedGatewayHandler.handle(instance)
        }
    }

    private companion object {
        const val MANAGEMENT_SYNC_ACTION = "io.element.android.yizhe.MANAGEMENT_SYNC"
    }
}
