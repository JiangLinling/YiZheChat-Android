/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.cachecleaner.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultCacheCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `calling clearCache actually removes file in the SUBDIRS_TO_CLEANUP list`() = runTest {
        // Create temp subdirs and fill with 2 files each
        DefaultCacheCleaner.SUBDIRS_TO_CLEANUP.forEach {
            File(temporaryFolder.root, it).apply {
                mkdirs()
                File(this, "temp1").createNewFile()
                File(this, "temp2").createNewFile()
            }
        }

        // Clear cache
        aCacheCleaner().clearCache()

        // Check the files are gone but the sub dirs are not.
        DefaultCacheCleaner.SUBDIRS_TO_CLEANUP.forEach {
            File(temporaryFolder.root, it).apply {
                assertThat(exists()).isTrue()
                assertThat(isDirectory).isTrue()
                assertThat(listFiles()).isEmpty()
            }
        }
    }

    @Test
    fun `clear cache handles unavailable child directories without throwing`() = runTest {
        // A regular file cannot contain cache subdirectories on either Windows or Android.
        // POSIX permission mutation cannot model this failure reliably on Windows.
        val blockedCache = File(temporaryFolder.root, "blocked-cache").apply { writeText("owned test fixture") }
        aCacheCleaner(cacheDir = blockedCache).clearCache()
        runCurrent()
        assertThat(blockedCache.isFile).isTrue()
        assertThat(blockedCache.readText()).isEqualTo("owned test fixture")
    }

    private fun TestScope.aCacheCleaner(cacheDir: File = temporaryFolder.root) = DefaultCacheCleaner(
        coroutineScope = this,
        dispatchers = this.testCoroutineDispatchers(true),
        cacheDir = cacheDir,
    )
}
