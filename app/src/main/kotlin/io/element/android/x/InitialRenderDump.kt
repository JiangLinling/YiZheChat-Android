/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnosticReport
import io.element.android.libraries.matrix.api.timeline.InitialRenderDiagnostics
import io.element.android.libraries.matrix.api.timeline.InitialRenderUnavailable
import java.io.PrintWriter

/** A selector on the existing OS dump route, never an authorization mechanism or a logger. */
internal object InitialRenderDump {
    const val SELECTOR = "--yizhe-initial-render-metadata"

    fun writeIfRequested(
        args: Array<String>?,
        internalPackage: Boolean,
        protectedForegroundVisible: Boolean,
        writer: PrintWriter,
        read: () -> InitialRenderDiagnosticReport = InitialRenderDiagnostics::read,
    ): Boolean {
        val requested = args ?: return false
        if (SELECTOR !in requested) return false
        val unavailable = when {
            requested.size != 1 || requested[0] != SELECTOR -> InitialRenderUnavailable.INVALID_SELECTOR
            !internalPackage || !protectedForegroundVisible -> InitialRenderUnavailable.PROTECTED_FOREGROUND
            else -> null
        }
        if (unavailable != null) writer.println(InitialRenderDiagnostics.unavailableLine(unavailable))
        else InitialRenderDiagnostics.lines(read()).forEach(writer::println)
        return true
    }
}
