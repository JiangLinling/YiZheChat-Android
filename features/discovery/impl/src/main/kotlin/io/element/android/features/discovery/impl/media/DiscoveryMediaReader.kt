/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.OutputStream

@Inject
@SingleIn(SessionScope::class)
internal class DiscoveryMediaReader(
    private val access: DefaultDiscoveryAccess,
    private val gate: AndroidDiscoveryIdentityGate,
    private val clock: AndroidDiscoveryClock,
    private val transport: DiscoveryMediaTransport,
    private val previewBoundary: DiscoveryMediaPreviewBoundary,
) {
    private val slots = Semaphore(2)

    /**
     * Call only inside SecureObjectStagingStore.stage's encrypted output callback. The store must abort if this throws.
     * No plaintext file, decoded frame, persistent object or lease may be published before this returns successfully.
     * The caller remains responsible for a real LC-16 owner and a separately verified local preview grant.
     */
    suspend fun copyVerifiedToEncryptedStaging(reference: DiscoveryMediaReference, encryptedOutput: OutputStream) {
        val originalOwner = gate.current() ?: throw DiscoveryFailure(401)
        val integrity = DiscoveryMediaIntegrity(reference)
        try {
            var offset = 0L
            while (offset < reference.byteLength) {
                currentCoroutineContext().ensureActive()
                if (gate.current() != originalOwner) throw CancellationException("Discovery media identity changed")
                val range = reference.range(offset, minOf(262144L, reference.byteLength - offset).toInt())
                withRange(range) { bytes ->
                    if (gate.current() != originalOwner) throw CancellationException("Discovery media identity changed")
                    // withSession dispatches to Main. Explicitly return CPU/file work to IO without detaching the caller Job.
                    writeDiscoveryMediaChunk(integrity, range, bytes, encryptedOutput)
                }
                offset += range.length
            }
            currentCoroutineContext().ensureActive()
            if (gate.current() != originalOwner) throw CancellationException("Discovery media identity changed")
            integrity.finish()
        } finally { integrity.close() }
    }

    /** Caller owns a visible-page job and must not retain this array or decode an incomplete file. No persistent plaintext is created. */
    suspend fun withRange(range: DiscoveryMediaRange, consume: suspend (ByteArray) -> Unit) = previewBoundary.whileCurrent { checkPreview ->
        val owner = gate.current() ?: throw DiscoveryFailure(401)
        slots.withPermit {
            access.withSession { session ->
                if (gate.current() != owner) throw CancellationException("Discovery media identity changed")
                checkPreview()
                val remaining = minOf(5000L, session.expiresAtMillis - clock.wallMillis(), session.expiresAtElapsedMillis - clock.elapsedMillis())
                if (remaining <= 0) throw DiscoveryFailure(401)
                coroutineScope {
                    val requestJob = currentCoroutineContext().job
                    val watch = launch {
                        gate.scope.first { it != owner }
                        requestJob.cancel(CancellationException("Discovery media identity changed"))
                    }
                    try {
                        withTimeout(remaining) {
                            val bytes = transport.read(range, session.secret.value, remaining)
                            try {
                                ensureActive()
                                if (gate.current() != owner) throw CancellationException("Discovery media identity changed")
                                checkPreview()
                                consume(bytes)
                                checkPreview()
                                ensureActive()
                            } finally { bytes.fill(0) }
                        }
                    } finally { watch.cancel() }
                }
            }
        }
    }
}

internal suspend fun writeDiscoveryMediaChunk(integrity: DiscoveryMediaIntegrity, range: DiscoveryMediaRange,
    bytes: ByteArray, encryptedOutput: OutputStream) = withContext(Dispatchers.IO) {
    ensureActive()
    integrity.append(range, bytes)
    encryptedOutput.write(bytes)
    ensureActive()
}
