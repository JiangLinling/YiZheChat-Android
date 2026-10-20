/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.BaseDirectory
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.DeletionScope
import java.io.File

data class LocalSessionDeletionReport(
    val sessionRecordRemoved: Boolean,
    val sessionFilesDeleted: Boolean,
)

interface DeletionSessionCleaner {
    suspend fun delete(scope: DeletionScope.Account): LocalSessionDeletionReport
    suspend fun hasReplacement(scope: DeletionScope.Account): Boolean = false
}

/** Removes the credential row and both random per-session directories using pre-validated, direct-child paths. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDeletionSessionCleaner(
    private val sessionStore: SessionStore,
    @BaseDirectory private val sessionsBaseDirectory: File,
    @CacheDirectory private val cacheBaseDirectory: File,
) : DeletionSessionCleaner {
    override suspend fun hasReplacement(scope: DeletionScope.Account): Boolean = sessionStore.getSession(scope.localSessionId)?.let {
        it.sessionPath != scope.sessionFilePath || it.cachePath != scope.sessionCachePath
    } == true

    override suspend fun delete(scope: DeletionScope.Account): LocalSessionDeletionReport {
        val sessionFiles = runCatchingExceptions {
            validatedDirectChild(scope.sessionFilePath, sessionsBaseDirectory) to
                validatedDirectChild(scope.sessionCachePath, cacheBaseDirectory)
        }.getOrElse {
            return LocalSessionDeletionReport(sessionRecordRemoved = false, sessionFilesDeleted = false)
        }

        val recordRemoved = runCatchingExceptions {
            sessionStore.removeSessionIfPathsMatch(scope.localSessionId, scope.sessionFilePath, scope.sessionCachePath)
        }.isSuccess
        val filesDeleted = recordRemoved && deleteIfPresent(sessionFiles.first) && deleteIfPresent(sessionFiles.second)
        return LocalSessionDeletionReport(
            sessionRecordRemoved = recordRemoved,
            sessionFilesDeleted = filesDeleted,
        )
    }

    private fun validatedDirectChild(path: String, expectedParent: File): File {
        val parent = expectedParent.canonicalFile
        val candidate = File(path).canonicalFile
        require(candidate != parent && candidate.parentFile == parent) { "Session deletion path is outside its managed root." }
        return candidate
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.deleteRecursively()
}
