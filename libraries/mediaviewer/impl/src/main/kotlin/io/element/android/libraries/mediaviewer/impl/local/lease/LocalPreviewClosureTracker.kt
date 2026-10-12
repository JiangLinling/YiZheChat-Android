/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.lease

import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCleanup
import java.util.concurrent.atomic.AtomicBoolean

/** Joins observed runtime generations with owned source cleanup; scheduling cleanup is never a fact. */
internal class LocalPreviewClosureTracker(private val onClean: () -> Unit) {
    private val lock = Any()
    private var sealed = false
    private var active = 0
    private var failed = false
    private var emitted = false
    private var hostRelease: FilePreviewLeaseReleaseResult? = null
    private var sourceCleanup: LocalAttachmentPreviewCleanup? = null

    fun beginRuntime(): FilePreviewRuntime? = synchronized(lock) {
        if (sealed) return null
        active++
        val completed = AtomicBoolean(false)
        FilePreviewRuntime { cleanup ->
            if (completed.compareAndSet(false, true)) {
                synchronized(lock) {
                    active--
                    failed = failed || !cleanup.isComplete
                }
                emitIfClean()
            }
        }
    }

    fun seal() {
        synchronized(lock) { sealed = true }
        emitIfClean()
    }

    fun hostReleased(result: FilePreviewLeaseReleaseResult) {
        synchronized(lock) { hostRelease = result }
        emitIfClean()
    }

    fun sourceReleased(result: LocalAttachmentPreviewCleanup) {
        synchronized(lock) { sourceCleanup = result }
        emitIfClean()
    }

    private fun emitIfClean() {
        val ready = synchronized(lock) {
            val host = hostRelease
            if (!emitted && sealed && !failed && active == 0 && sourceCleanup?.isComplete == true &&
                host?.leaseTokenMatched == true && host.activeStreamsClosed && host.hostHandleCloseCompleted) {
                emitted = true
                true
            } else {
                false
            }
        }
        if (ready) try {
            onClean()
        } catch (failure: Throwable) {
            synchronized(lock) { emitted = false }
            throw failure
        }
    }
}
