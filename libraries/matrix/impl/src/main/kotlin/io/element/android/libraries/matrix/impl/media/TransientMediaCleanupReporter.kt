/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber

/** Existing object journal is the outbox; transport never recreates an object or a lifecycle operation. */
internal class TransientMediaCleanupReporter(
    private val store: SensitiveFileStore,
    private val coordinator: AppOperationalStateCoordinator,
) {
    private var lastAttempted: String? = null

    suspend fun recoverAndReport() {
        // Several account sessions can share the same app-scoped store. Do not multiply background transports.
        if (!transport.tryLock()) return
        try {
            val facts = store.recoverTransientMediaObjects()
                .filter { it.leaseQuiescentAtEpochMillis != null && it.owner.installationId != null }
                .sortedBy { it.recordSha256 }
            val cursor = lastAttempted
            val ordered = if (cursor == null) facts else facts.filter { it.recordSha256 > cursor } + facts.filter { it.recordSha256 <= cursor }
            for (fact in ordered.take(32)) {
                lastAttempted = fact.recordSha256
                try {
                    val owner = fact.owner
                    val observation = LifecycleMediaObjectCleanup(
                        operationId = owner.operationId,
                        subjects = LifecycleSubjectHashes(installationId = owner.installationId, accountIdHash = owner.accountIdHash,
                            matrixDeviceIdHash = owner.matrixDeviceIdHash, roomIdHash = owner.roomIdHash, eventIdHash = owner.eventIdHash),
                        recordSha256 = fact.recordSha256,
                        confirmedAbsentAtEpochMillis = fact.confirmedAbsentAtEpochMillis,
                        leaseQuiescentAtEpochMillis = checkNotNull(fact.leaseQuiescentAtEpochMillis),
                    )
                    val acknowledgement = coordinator.recordMediaObjectCleanup(observation)
                    store.acknowledgeTransientMediaDeletion(fact, acknowledgement)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the original immutable fact and continue other resources. Retry never changes its timestamp.
                    Timber.w("Transient media cleanup acknowledgement remains pending.")
                }
            }
        } finally {
            transport.unlock()
        }
    }

    private companion object { val transport = Mutex() }
}
