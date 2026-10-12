/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.api.logs

import com.google.common.truth.Truth.assertThat
import io.element.android.features.rageshake.api.reporter.BugReporter
import io.element.android.features.rageshake.api.reporter.BugReportAttachment
import io.element.android.features.rageshake.api.reporter.BugReporterListener
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WriteToFilesConfigurationFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `production configuration deletes legacy logs and disables persistence`() {
        val directory = temporaryFolder.newFolder("logs")
        File(directory, "legacy.log").writeText("commercial secret")
        val reporter = FakeBugReporter(directory)

        val result = reporter.createWriteToFilesConfiguration(allowPlaintextDebugLogs = false)

        assertThat(result).isEqualTo(WriteToFilesConfiguration.Disabled)
        assertThat(directory.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `debug configuration retains at most one day of files`() {
        val reporter = FakeBugReporter(temporaryFolder.newFolder("logs"))

        val result = reporter.createWriteToFilesConfiguration(allowPlaintextDebugLogs = true)

        assertThat(result).isInstanceOf(WriteToFilesConfiguration.Enabled::class.java)
        assertThat((result as WriteToFilesConfiguration.Enabled).numberOfFiles).isEqualTo(24)
    }
}

private class FakeBugReporter(
    private val directory: File,
) : BugReporter {
    override suspend fun sendBugReport(
        withDevicesLogs: Boolean,
        withCrashLogs: Boolean,
        withScreenshot: Boolean,
        problemDescription: String,
        canContact: Boolean,
        sendPushRules: Boolean,
        attachments: List<BugReportAttachment>,
        listener: BugReporterListener,
    ) = Unit

    override fun logDirectory(): File = directory
    override fun setCurrentTracingLogLevel(logLevel: String) = Unit
    override fun saveLogCat(): File? = null
}
