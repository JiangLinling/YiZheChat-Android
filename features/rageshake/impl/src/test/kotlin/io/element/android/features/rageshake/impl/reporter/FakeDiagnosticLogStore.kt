/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.rageshake.impl.reporter

import io.element.android.security.securestorage.api.DiagnosticCollectionState
import io.element.android.security.securestorage.api.DiagnosticMigrationResult
import io.element.android.security.securestorage.api.SecureDiagnosticEvent
import io.element.android.security.securestorage.api.SecureDiagnosticLogStore
import io.element.android.security.securestorage.api.SecureDiagnosticSnapshot
import java.io.File

internal class FakeDiagnosticLogStore : SecureDiagnosticLogStore {
    var result = SecureDiagnosticSnapshot(
        "12345678-1234-1234-1234-123456789abc",
        DiagnosticCollectionState.AVAILABLE,
        listOf(SecureDiagnosticEvent(1000, 6, "HttpClient", "http_status=503", "java.net.SocketTimeoutException\n at Client.send(Client.kt:42)")),
        0,
        0,
        null
    )
    var reads = 0
    override fun append(priority: Int, source: String?, message: String, throwable: Throwable?, location: String?) = Unit
    override fun flushBlocking(timeoutMillis: Long) = true
    override fun reportFailure(code: String) = Unit
    override suspend fun snapshot(): SecureDiagnosticSnapshot {
        reads++
        return result
    }
    override suspend fun migrateLegacy(files: List<File>) = DiagnosticMigrationResult(0, 0, 0)
}
