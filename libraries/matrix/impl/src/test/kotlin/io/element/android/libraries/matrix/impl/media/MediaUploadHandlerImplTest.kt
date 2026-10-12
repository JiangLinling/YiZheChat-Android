/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle

class MediaUploadHandlerImplTest {
    @Test fun `successful join without accepted Matrix fact remains failure`() = runTest {
        val handle = mockk<SendAttachmentJoinHandle>()
        coEvery { handle.join() } returns Unit // Also the native cancellation return value.
        val upload = MediaUploadHandlerImpl(emptyList(), handle, verifyAcceptance = { error("Unknown original event") })
        assertTrue(upload.await().isFailure)
    }
    @Test fun `confirmed Matrix event permits completion`() = runTest {
        val handle = mockk<SendAttachmentJoinHandle>()
        coEvery { handle.join() } returns Unit
        var confirmed = false
        val upload = MediaUploadHandlerImpl(emptyList(), handle, verifyAcceptance = { confirmed = true })
        assertTrue(upload.await().isSuccess)
        assertTrue(confirmed)
    }
}
