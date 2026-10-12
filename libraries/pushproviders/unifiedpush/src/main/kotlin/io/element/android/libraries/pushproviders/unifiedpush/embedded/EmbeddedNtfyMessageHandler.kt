/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import dev.zacsweers.metro.Inject
import io.element.android.libraries.push.api.push.FetchPushForegroundServiceManager
import io.element.android.libraries.pushproviders.api.PushHandler
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushConfig
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushParser
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import kotlinx.coroutines.withContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.performForComponent
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

@Inject
class EmbeddedNtfyMessageHandler(
    private val pushParser: UnifiedPushParser,
    private val pushHandler: PushHandler,
    private val fetchPushForegroundServiceManager: FetchPushForegroundServiceManager,
    private val registrar: EmbeddedNtfyRegistrar,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) {
    suspend fun handle(
        content: ByteArray,
        clientSecret: String,
        providerInfo: String = BUILT_IN_PROVIDER_INFO,
        startForegroundService: Boolean = true,
        managementToken: String? = null,
    ): Boolean {
        if (startForegroundService) {
            fetchPushForegroundServiceManager.start()
        }
        val pushData = pushParser.parse(content, clientSecret)
        if (pushData == null) {
            Timber.w("Invalid data received from built-in push")
            pushHandler.handleInvalid(
                providerInfo = "$providerInfo - invalid",
                data = "<redacted>",
            )
            fetchPushForegroundServiceManager.stop()
            return false
        }
        // Cursor retries and provider redelivery must own the same registration/event intent.
        // Only credential digests enter the operation ID; admission still verifies the actual
        // authenticated installation and the original room/event subjects on the server.
        val operationId = UUID.nameUUIDFromBytes(
            "embedded-ntfy-delivery-v1:${clientSecret.sha256()}:${pushData.roomId.value.sha256()}:${pushData.eventId.value.sha256()}"
                .encodeToByteArray()
        ).toString()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_19,
            trigger = "delivery_claim",
            roomIdHash = pushData.roomId.value.sha256(),
            eventIdHash = pushData.eventId.value.sha256(),
            operationId = operationId,
        )
        val admitted = appOperationalStateCoordinator.start(request)
        require(admitted.operationId == operationId && admitted.lifecycleId == request.lifecycleId &&
            admitted.subjects.roomIdHash == request.roomIdHash && admitted.subjects.eventIdHash == request.eventIdHash)
        if (admitted.status.isTerminal) {
            // A signed failure also retires this push intent. Matrix sync remains
            // message truth; never restart an expired/revoked delivery or forge success.
            check(!admitted.terminalReceiptId.isNullOrBlank()) { "Terminal push delivery requires its original receipt." }
            fetchPushForegroundServiceManager.stop()
            return false
        }
        check(!admitted.status.isTerminal && admitted.deadlineEpochMillis > System.currentTimeMillis()) {
            "Original push delivery is no longer active."
        }
        return withContext(LifecycleOperationContext(admitted.operationId, admitted.lifecycleId)) {
            appOperationalStateCoordinator.performForComponent(request, LifecycleComponent.ANDROID_SERVICE) {
                handleParsedPush(pushData, providerInfo, managementToken)
            }
        }
    }

    private suspend fun handleParsedPush(
        pushData: io.element.android.libraries.pushproviders.api.PushData,
        providerInfo: String,
        managementToken: String?,
    ): Boolean {
        val claimDecision = managementToken?.let { token ->
            registrar.claimDelivery(
                managementToken = token,
                eventId = pushData.eventId.value,
                roomId = pushData.roomId.value,
            ).onFailure {
                Timber.w(it, "Unable to claim built-in push delivery; processing locally")
            }.getOrDefault(DeliveryClaimDecision.PROCESS_UNCOORDINATED)
        } ?: DeliveryClaimDecision.PROCESS_UNCOORDINATED
        if (claimDecision == DeliveryClaimDecision.SUPPRESS) {
            Timber.d("Suppressing duplicate built-in push delivery")
            fetchPushForegroundServiceManager.stop()
            return false
        }

        val handled = pushHandler.handle(
            pushData = pushData,
            providerInfo = providerInfo,
        )
        if (managementToken != null) {
            val stateResult = if (handled) {
                registrar.completeDelivery(managementToken, pushData.eventId.value, pushData.roomId.value)
            } else {
                registrar.releaseDelivery(managementToken, pushData.eventId.value, pushData.roomId.value)
            }
            stateResult.onFailure {
                Timber.w(it, "Unable to report built-in push delivery state")
            }
            if (handled) {
                stateResult.getOrThrow()
            }
        }
        if (!handled) {
            fetchPushForegroundServiceManager.stop()
        }
        return handled
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUILT_IN_PROVIDER_INFO = "${UnifiedPushConfig.NAME} - built-in ntfy"
    }
}
