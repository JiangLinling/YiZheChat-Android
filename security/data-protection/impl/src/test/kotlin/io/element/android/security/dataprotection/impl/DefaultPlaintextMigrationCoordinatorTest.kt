/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.PlaintextMigrationStep
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class DefaultPlaintextMigrationCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `migration removes every registered plaintext target and preserves unrelated files`() = runTest {
        val roots = TestLegacyPlaintextRoots(temporaryFolder.root.resolve("cache"), temporaryFolder.root.resolve("files"))
        roots.populateAllTargets()
        val unrelatedCache = roots.cacheRoot().resolve("http-cache/index").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("not a registered plaintext target")
        }
        val unrelatedFile = roots.filesRoot().resolve("notification_sounds/custom.ogg").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("user-selected sound")
        }
        val coordinator = coordinator(roots)

        val report = coordinator.migrate()

        assertThat(report.retryRequired).isFalse()
        assertThat(report.completedSteps).contains(PlaintextMigrationStep.COMPLETE)
        LegacyPlaintextTarget.entries.forEach { target ->
            assertThat(report.completedSteps).contains(target.completedStep)
        }
        assertThat(unrelatedCache.exists()).isTrue()
        assertThat(unrelatedFile.exists()).isTrue()
        assertThat(roots.registeredTargetsExist()).isFalse()
    }

    @Test
    fun `completed migration still removes scratch plaintext created by a previous process`() = runTest {
        val roots = TestLegacyPlaintextRoots(temporaryFolder.root.resolve("cache"), temporaryFolder.root.resolve("files"))
        val coordinator = coordinator(roots)
        assertThat(coordinator.migrate().retryRequired).isFalse()
        val lateUpload = roots.cacheRoot().resolve("uploads/late.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("late plaintext")
        }

        val secondReport = coordinator.migrate()

        assertThat(secondReport.retryRequired).isFalse()
        assertThat(lateUpload.exists()).isFalse()
    }

    @Test
    fun `cleanup failure is durable and next attempt resumes from the failed target`() = runTest {
        val roots = TestLegacyPlaintextRoots(temporaryFolder.root.resolve("cache"), temporaryFolder.root.resolve("files"))
        val delegate = FileLegacyPlaintextCleaner(roots)
        val cleaner = FailOncePlaintextCleaner(delegate, LegacyPlaintextTarget.EDITING_STAGING)
        val coordinator = coordinator(roots, cleaner)

        val first = coordinator.migrate()
        val second = coordinator.migrate()

        assertThat(first.retryRequired).isTrue()
        assertThat(first.errorCode).isEqualTo("PLAINTEXT_CLEANUP_FAILED")
        assertThat(second.retryRequired).isFalse()
        assertThat(cleaner.calls.getValue(LegacyPlaintextTarget.RETIRED_MEDIA_CACHE)).isEqualTo(1)
        assertThat(cleaner.calls.getValue(LegacyPlaintextTarget.EDITING_STAGING)).isEqualTo(2)
    }

    @Test
    fun `legacy root scan deletes direct cache files without traversing unrelated cache directories`() {
        val roots = TestLegacyPlaintextRoots(temporaryFolder.root.resolve("cache"), temporaryFolder.root.resolve("files"))
        roots.cacheRoot().mkdirs()
        val oldTemp = roots.cacheRoot().resolve("${UUID.randomUUID()}123456.jpg").apply { writeText("plaintext") }
        val cameraFile = roots.cacheRoot().resolve("photo.jpg").apply { writeText("plaintext") }
        val nestedCacheFile = roots.cacheRoot().resolve("http-cache/index").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("keep")
        }

        val result = FileLegacyPlaintextCleaner(roots).clean(LegacyPlaintextTarget.LEGACY_ROOT_CACHE_FILES)

        assertThat(result.completed).isTrue()
        assertThat(oldTemp.exists()).isFalse()
        assertThat(cameraFile.exists()).isFalse()
        assertThat(nestedCacheFile.exists()).isTrue()
    }

    private fun coordinator(
        roots: TestLegacyPlaintextRoots,
        cleaner: LegacyPlaintextCleaner = FileLegacyPlaintextCleaner(roots),
    ): DefaultPlaintextMigrationCoordinator = DefaultPlaintextMigrationCoordinator(
        cleaner = cleaner,
        journal = FilePlaintextMigrationJournal(testSecureStorageRoots()),
        sensitiveFileStore = InMemorySensitiveFileStore(),
    )

    private fun testSecureStorageRoots(): SecureStorageRoots = object : SecureStorageRoots {
        override fun persistentRoot(): File = temporaryFolder.root.resolve("secure")
        override fun runtimeRoot(): File = temporaryFolder.root.resolve("secure-runtime")
    }
}

private class FailOncePlaintextCleaner(
    private val delegate: LegacyPlaintextCleaner,
    private val failedTarget: LegacyPlaintextTarget,
) : LegacyPlaintextCleaner {
    val calls = LegacyPlaintextTarget.entries.associateWith { 0 }.toMutableMap()
    private var failed = false

    override fun clean(target: LegacyPlaintextTarget): LegacyPlaintextCleanupResult {
        calls[target] = calls.getValue(target) + 1
        if (target == failedTarget && !failed) {
            failed = true
            return LegacyPlaintextCleanupResult(completed = false, deletedFiles = 0)
        }
        return delegate.clean(target)
    }
}

private class TestLegacyPlaintextRoots(
    private val cache: File,
    private val files: File,
) : LegacyPlaintextRoots {
    override fun cacheRoot(): File = cache

    override fun filesRoot(): File = files

    fun populateAllTargets() {
        listOf(
            "temp/media/legacy.jpg",
            "temp/voice/legacy.ogg",
            "temp/notif/legacy.jpg",
            "uploads/upload.bin",
            "edited-media/edit.jpg",
            "voice_recordings/recording.ogg",
            "logs/legacy.log",
        ).forEach { relativePath ->
            cache.resolve(relativePath).apply {
                requireNotNull(parentFile).mkdirs()
                writeText("plaintext")
            }
        }
        cache.resolve("${UUID.randomUUID()}123456.jpg").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("plaintext")
        }
        files.resolve("screenshot.png").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("plaintext")
        }
    }

    fun registeredTargetsExist(): Boolean = listOf(
        cache.resolve("temp/media"),
        cache.resolve("temp/voice"),
        cache.resolve("temp/notif"),
        cache.resolve("uploads"),
        cache.resolve("edited-media"),
        cache.resolve("voice_recordings"),
        cache.resolve("logs"),
        files.resolve("screenshot.png"),
    ).any(File::exists) || cache.listFiles().orEmpty().any { it.isFile && it.name.length > 40 && it.extension == "jpg" }
}
