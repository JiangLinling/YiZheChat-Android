/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResource
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAcquireResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAuthority
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCapability
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerMutationResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.MEDIA_RESOURCE_CLOSURE_PROTOCOL
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.ExternalSecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaExternalReadLease
import io.element.android.security.securestorage.api.SecureMediaExternalReadPlan
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Product owner using the existing LC16 authority, encrypted store and closure queue. */
@Inject
@SingleIn(AppScope::class)
class ExternalReadHandoff(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val scope: CoroutineScope,
    private val media: ExternalSecureMediaLeaseManager,
    private val lifecycle: AppOperationalStateCoordinator,
    private val owners: LifecycleOperationOwnerAuthority,
    private val gate: ProtectedPreviewAuthorizationGate,
    private val foreground: AppForegroundStateService,
) {
    private val active = ConcurrentHashMap<String, Handoff>()

    fun recipients(local: LocalMedia): List<ExternalReadRecipientOption> {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(local.uri, local.info.mimeType)
        return context.packageManager.queryIntentActivities(intent, 0).mapNotNull { resolved ->
            val activity = resolved.activityInfo ?: return@mapNotNull null
            if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
                activity.applicationInfo.uid == context.applicationInfo.uid || activity.permission != null) {
                    return@mapNotNull null
                }
            ExternalReadRecipientOption(
                SecureMediaExternalRecipient(activity.applicationInfo.uid, activity.packageName, activity.name),
                resolved.loadLabel(context.packageManager).toString()
            )
        }.distinctBy { it.recipient }.sortedWith(compareBy({ it.label }, { it.recipient.packageName }, { it.recipient.activityClassName }))
    }

    /** Selection has finished inside the host. No external activity or URI was published before this call. */
    suspend fun prepare(local: LocalMedia, recipient: SecureMediaExternalRecipient): ExternalReadLaunch {
        requireNotNull(FilePreviewLeaseRegistry.acquire(
            requireNotNull(local.filePreviewLeaseToken),
            local.filePreviewLeaseBinding(),
            "${context.packageName}.securemedia"
        )) { "File preview ownership is unavailable." }
        check(recipients(local).any { it.recipient == recipient }) { "Selected application is no longer available." }
        check(gate.canAttemptPreview())
        val source = media.inspectExternalSource(local.uri)
        val sourceOperationId = requireNotNull(local.lifecycleOperationId)
        require(sourceOperationId == source.owner.operationId)
        val operationId = UUID.randomUUID().toString()
        val sourceOwner = source.owner
        val operation = lifecycle.start(LifecycleOperationRequest(
            operationId = operationId,
            lifecycleId = FullAppLifecycleId.LC_16,
            trigger = "external_open",
            idempotencyKey = "external-$operationId",
            accountIdHash = sourceOwner.accountIdHash,
            matrixDeviceIdHash = sourceOwner.matrixDeviceIdHash,
            roomIdHash = sourceOwner.roomIdHash,
            eventIdHash = sourceOwner.eventIdHash,
            deadlineEpochMillis = source.authorizationDeadlineEpochMillis,
            mediaCleanupProtocol = MEDIA_RESOURCE_CLOSURE_PROTOCOL
        ))
        require(operation.subjects == LifecycleSubjectHashes(
            installationId = sourceOwner.installationId,
            accountIdHash = sourceOwner.accountIdHash,
            matrixDeviceIdHash = sourceOwner.matrixDeviceIdHash,
            roomIdHash = sourceOwner.roomIdHash,
            eventIdHash = sourceOwner.eventIdHash
        ))
        val capability = (owners.acquireOwner(operationId, LifecycleOperationOwnerRole.EXTERNAL_READ, "external-acquire-$operationId")
            as? LifecycleOperationOwnerAcquireResult.Granted)?.capability ?: error("External operation ownership was rejected.")
        val plan = media.prepareExternalRead(local.uri, sourceOperationId, sourceOwner.copy(operationId = operationId), recipient)
        val bindingDigest = plan.binding.digestSha256()
        val reservation = LifecycleMediaReservation(operationId, operation.subjects, listOf(
            LifecycleMediaResource(LifecycleMediaResourceType.LOCAL_MEDIA_OBJECT, plan.objectRecord.recordSha256),
            LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, bindingDigest),
        ).sortedWith(compareBy({ it.type.name }, { it.recordSha256 })))
        val handoff = Handoff(plan, capability, reservation, source.processDeadlineElapsedRealtimeMillis, gate.authorizationRevision.value)
        active[operationId] = handoff
        scope.launch { observe(handoff) }
        try {
            lifecycle.reserveMediaResources(reservation)
            handoff.reserved.set(true)
            val lease = media.issueExternalRead(plan)
            handoff.lease = lease
            check(!handoff.retiring.get() && gate.canAttemptPreview() && gate.authorizationRevision.value == handoff.revision)
            check(recipients(local).any { it.recipient == recipient })
            val intent = Intent(Intent.ACTION_VIEW).setComponent(ComponentName(recipient.packageName, recipient.activityClassName))
                .setDataAndType(lease.uri, local.info.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("", lease.uri) }
            return ExternalReadLaunch(operationId, intent)
        } catch (failure: Throwable) {
            handoff.failed.set(true)
            retire(handoff)
            throw failure
        }
    }

    fun dispatched(operationId: String) {
        active[operationId]?.dispatched?.set(true)
    }
    fun returned(operationId: String) {
        active[operationId]?.let {
            it.resultReceived.set(true)
            if (foreground.isInForeground.value) retire(it)
        }
    }
    fun failed(operationId: String) {
        active[operationId]?.let {
            it.failed.set(true)
            retire(it)
        }
    }

    private fun retire(handoff: Handoff) {
        if (handoff.retiring.compareAndSet(false, true)) {
            runCatching { handoff.plan.close() }
            .onFailure { Timber.w("External read capability cleanup remains pending.") }
        }
    }

    private suspend fun observe(handoff: Handoff) = withContext(NonCancellable) {
        var observedBackground = false
        while (true) {
            if (handoff.dispatched.get()) {
                if (!foreground.isInForeground.value) observedBackground = true
                if ((observedBackground || handoff.resultReceived.get()) && foreground.isInForeground.value) retire(handoff)
            }
            if (!gate.canAttemptPreview() || gate.authorizationRevision.value != handoff.revision ||
                SystemClock.elapsedRealtime() >= handoff.deadline || handoff.plan.observation.value.revoked) {
                    retire(handoff)
                }
            val fact = handoff.plan.observation.value
            if (handoff.retiring.get() && fact.readsQuiescent && fact.cleanupComplete) {
                try {
                    handoff.lease?.uri?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    run {
                        val runtime = handoff.runtimeFact ?: LifecycleMediaRuntimeCleanup(
                            handoff.reservation.operationId,
                            handoff.reservation.subjects,
                            handoff.plan.binding.digestSha256(),
                            System.currentTimeMillis(),
                            true,
                            true,
                            true
                        )
                            .also { handoff.runtimeFact = it }
                        lifecycle.retainMediaRuntimeCleanup(runtime)
                        val outcome = if (fact.bytesCopied > 0) {
                            LifecycleMediaOutcome.SUCCEEDED
                        } else if (handoff.failed.get()) {
                            LifecycleMediaOutcome.FAILED_FINAL
                        } else {
                            LifecycleMediaOutcome.CANCELLED
                        }
                        if (!handoff.ownerCompleted.get()) {
                            check(
                                owners.completeOwner(handoff.capability, "external-complete-${handoff.reservation.operationId}")
                                is LifecycleOperationOwnerMutationResult.Released
                            )
                            handoff.ownerCompleted.set(true)
                        }
                        val reference = "external-read:${handoff.plan.binding.sourceOperationId}:${handoff.plan.binding.digestSha256()}:" +
                            "${fact.descriptorOpens}:${fact.readCalls}:${fact.bytesCopied}:${fact.firstReadAtEpochMillis ?: 0}"
                        val receipts = handoff.receipts ?: listOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE).map {
                            LifecycleClientReceipt(
                                handoff.reservation.operationId,
                                it,
                                LifecyclePhase.CLIENT_APPLIED,
                                if (fact.bytesCopied > 0) "EXTERNAL_BYTES_READ_OWNED_RESOURCES_RELEASED" else "EXTERNAL_NO_BYTES_OWNED_RESOURCES_RELEASED",
                                reference,
                                runtime.releasedAtEpochMillis
                            )
                        }.also { handoff.receipts = it }
                        // Persist observations before acknowledgement can suspend or the process can exit.
                        lifecycle.persistClientReceipts(receipts)
                        lifecycle.sealMediaResources(handoff.reservation.operationId, outcome)
                        lifecycle.enqueueClientReceipts(receipts)
                        active.remove(handoff.reservation.operationId, handoff)
                        return@withContext
                    }
                } catch (failure: Exception) {
                    Timber.w("External read closure acknowledgement remains pending.")
                }
                delay(5_000)
            } else {
                delay(250)
            }
        }
    }

    private class Handoff(val plan: SecureMediaExternalReadPlan, val capability: LifecycleOperationOwnerCapability,
        val reservation: LifecycleMediaReservation, val deadline: Long, val revision: Long) {
        @Volatile var lease: SecureMediaExternalReadLease? = null
        @Volatile var runtimeFact: LifecycleMediaRuntimeCleanup? = null
        @Volatile var receipts: List<LifecycleClientReceipt>? = null
        val reserved = AtomicBoolean(false)
        val dispatched = AtomicBoolean(false)
        val resultReceived = AtomicBoolean(false)
        val retiring = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val ownerCompleted = AtomicBoolean(false)
    }
}

data class ExternalReadRecipientOption(val recipient: SecureMediaExternalRecipient, val label: String)
data class ExternalReadLaunch(val operationId: String, val intent: Intent)
