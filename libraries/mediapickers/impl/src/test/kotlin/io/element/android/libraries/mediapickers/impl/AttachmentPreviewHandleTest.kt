/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AttachmentPreviewHandleTest {
    private val source = mockk<LocalMedia>()
    private val projection = mockk<LocalMedia>()
    private val service = mockk<LocalAttachmentPreviewLeaseService>()

    @Test
    fun `protected document renders only the owned projection and releases access on close`() = runTest {
        val lease = mockk<LocalAttachmentPreviewLease>(relaxed = true)
        every { service.requiresLease(source) } returns true
        every { lease.previewMedia } returns projection
        coEvery { service.openForBrowse(source) } returns Result.success(lease)
        val handle = AttachmentPreviewHandle.open(source, service)
        assertThat(handle.media).isSameInstanceAs(projection)
        handle.close()
        handle.close()
        verify(exactly = 1) { lease.close() }
        coVerify(exactly = 0) { service.open(any()) }
    }

    @Test
    fun `rejected document lease does not fall back to rendering the raw source`() = runTest {
        every { service.requiresLease(source) } returns true
        coEvery { service.openForBrowse(source) } returns Result.failure(SecurityException("Preview blocked"))
        val result = runCatching { AttachmentPreviewHandle.open(source, service) }
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `non leased media retains its source and does not open an upload preview owner`() = runTest {
        every { service.requiresLease(source) } returns false
        val handle = AttachmentPreviewHandle.open(source, service)
        assertThat(handle.media).isSameInstanceAs(source)
        handle.close()
        coVerify(exactly = 0) { service.open(any()) }
        coVerify(exactly = 0) { service.openForBrowse(any()) }
    }
}
