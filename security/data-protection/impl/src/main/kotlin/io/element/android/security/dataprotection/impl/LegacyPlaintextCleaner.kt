/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.PlaintextMigrationStep
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class LegacyPlaintextTarget(val completedStep: PlaintextMigrationStep) {
    RETIRED_MEDIA_CACHE(PlaintextMigrationStep.PURGED_RETIRED_MEDIA_CACHE),
    RETIRED_VOICE_CACHE(PlaintextMigrationStep.PURGED_RETIRED_VOICE_CACHE),
    RETIRED_NOTIFICATION_CACHE(PlaintextMigrationStep.PURGED_RETIRED_NOTIFICATION_CACHE),
    UPLOAD_STAGING(PlaintextMigrationStep.PURGED_UPLOAD_STAGING),
    EDITING_STAGING(PlaintextMigrationStep.PURGED_EDITING_STAGING),
    VOICE_RECORDING_STAGING(PlaintextMigrationStep.PURGED_VOICE_RECORDING_STAGING),
    LEGACY_ROOT_CACHE_FILES(PlaintextMigrationStep.PURGED_LEGACY_ROOT_CACHE_FILES),
    LEGACY_LOGS(PlaintextMigrationStep.PURGED_LEGACY_LOGS),
    LEGACY_SCREENSHOT(PlaintextMigrationStep.PURGED_LEGACY_SCREENSHOT),
}

data class LegacyPlaintextCleanupResult(
    val completed: Boolean,
    val deletedFiles: Int,
)

interface LegacyPlaintextCleaner {
    fun clean(target: LegacyPlaintextTarget): LegacyPlaintextCleanupResult
}

interface LegacyPlaintextRoots {
    fun cacheRoot(): File
    fun filesRoot(): File
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidLegacyPlaintextRoots(
    @ApplicationContext private val context: Context,
) : LegacyPlaintextRoots {
    override fun cacheRoot(): File = context.cacheDir

    override fun filesRoot(): File = context.filesDir
}

/** Deletes only fixed, registered paths or the exact UUID temp-file shape used by the old media preprocessor. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FileLegacyPlaintextCleaner(
    private val roots: LegacyPlaintextRoots,
) : LegacyPlaintextCleaner {
    override fun clean(target: LegacyPlaintextTarget): LegacyPlaintextCleanupResult = when (target) {
        LegacyPlaintextTarget.RETIRED_MEDIA_CACHE -> deleteRegisteredPath(roots.cacheRoot(), "temp/media")
        LegacyPlaintextTarget.RETIRED_VOICE_CACHE -> deleteRegisteredPath(roots.cacheRoot(), "temp/voice")
        LegacyPlaintextTarget.RETIRED_NOTIFICATION_CACHE -> deleteRegisteredPath(roots.cacheRoot(), "temp/notif")
        LegacyPlaintextTarget.UPLOAD_STAGING -> deleteRegisteredPath(roots.cacheRoot(), "uploads")
        LegacyPlaintextTarget.EDITING_STAGING -> deleteRegisteredPath(roots.cacheRoot(), "edited-media")
        LegacyPlaintextTarget.VOICE_RECORDING_STAGING -> deleteRegisteredPath(roots.cacheRoot(), "voice_recordings")
        LegacyPlaintextTarget.LEGACY_LOGS -> deleteRegisteredPath(roots.cacheRoot(), "logs")
        LegacyPlaintextTarget.LEGACY_SCREENSHOT -> deleteRegisteredPath(roots.filesRoot(), "screenshot.png")
        LegacyPlaintextTarget.LEGACY_ROOT_CACHE_FILES -> deleteLegacyRootCacheFiles()
    }

    private fun deleteRegisteredPath(root: File, relativePath: String): LegacyPlaintextCleanupResult {
        val safeRoot = root.canonicalFile
        val target = File(safeRoot, relativePath).canonicalFile
        require(target != safeRoot && target.toPath().startsWith(safeRoot.toPath())) {
            "Legacy plaintext target is outside its managed root."
        }
        if (!target.exists()) return LegacyPlaintextCleanupResult(completed = true, deletedFiles = 0)
        var deletedFiles = 0
        Files.walkFileTree(
            target.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    deletedFiles += 1
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                    failure?.let { throw it }
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return LegacyPlaintextCleanupResult(completed = !target.exists(), deletedFiles = deletedFiles)
    }

    private fun deleteLegacyRootCacheFiles(): LegacyPlaintextCleanupResult {
        val cacheRoot = roots.cacheRoot().canonicalFile
        var deletedFiles = 0
        var completed = true
        cacheRoot.listFiles().orEmpty()
            .filter(File::isFile)
            .forEach { file ->
                if (file.delete() || !file.exists()) {
                    deletedFiles += 1
                } else {
                    completed = false
                }
            }
        return LegacyPlaintextCleanupResult(completed = completed, deletedFiles = deletedFiles)
    }
}
