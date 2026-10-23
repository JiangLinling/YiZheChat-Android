/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.DirectRoomOperationEvidence
import io.element.android.libraries.matrix.api.room.DirectRoomResolution
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.admitManagedDirectRoomOperations
import io.element.android.security.dataprotection.api.managedDirectRoomApplicationReceipts
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/** The authority owns durable intent identity. Android only acknowledges its actual local sync. */
internal class ManagedDirectRoomLifecycle(
    private val management: ManagementControlService,
    private val coordinator: AppOperationalStateCoordinator,
    private val accountHash: String,
    private val deviceHash: String,
    private val awaitLocalRoom: suspend (String) -> Unit,
) {
    suspend fun execute(peer: String, create: Boolean): String? = resolve(peer, create)?.roomId?.value

    suspend fun resolve(peer: String, create: Boolean): DirectRoomResolution? {
        check(management.supportsManagedRoomCreation)
        val sample = timingSamples.updateAndGet { (it + 1).coerceAtMost(17) }
        val result = measureStage(sample, "resolve_http") {
            management.managedDirectRoom(accountHash, deviceHash, peer, create)
        }
        if (result == null) {
            check(!create) { "Managed direct creation returned no room" }
            return null
        }
        measureStage(sample, "await_local_room") { awaitLocalRoom(result.roomId) }
        val operations = measureStage(sample, "service_admission") {
            coordinator.admitManagedDirectRoomOperations(result, accountHash, deviceHash)
        }
        val receipts = managedDirectRoomApplicationReceipts(operations, LifecycleComponent.ANDROID_SERVICE)
        if (receipts.isNotEmpty()) measureStage(sample, "service_receipts") { coordinator.enqueueClientReceipts(receipts) }
        val roomId = RoomId(result.roomId)
        return DirectRoomResolution(roomId, DirectRoomOperationEvidence(
            roomId = roomId,
            accountIdHash = accountHash,
            matrixDeviceIdHash = deviceHash,
            peerUserId = UserId(peer),
            status = result.status,
            operationIds = result.clientOperationIds.toList(),
            installationId = result.installationId,
            issuedAtEpochMillis = result.issuedAtEpochMillis,
            expiresAtEpochMillis = result.expiresAtEpochMillis,
        ))
    }

    // Invocation-local timings only. Stop after 16 resolutions per process, including profile lookups.
    // No identifiers or exception text enter diagnostics; this never changes admission or persistence.
    private suspend fun <T> measureStage(sample: Int, stage: String, block: suspend () -> T): T {
        if (sample > 16) return block()
        val startedAt = System.nanoTime()
        var outcome = "failed"
        return try {
            block().also { outcome = "completed" }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            Timber.tag("YiZheTimelinePerf").d(
                "stage=direct_%s sample=%d outcome=%s duration_ms=%d",
                stage,
                sample,
                outcome,
                (System.nanoTime() - startedAt) / 1_000_000L,
            )
        }
    }

    private companion object {
        val timingSamples = AtomicInteger()
    }
}
