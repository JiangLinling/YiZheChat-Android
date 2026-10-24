/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMediaPreviewBoundaryTest {
    @Test fun `locked preview never starts reading`() = runTest {
        val gate = PreviewGate().apply { accessState.value = accessState.value.copy(applicationUnlocked = false) }
        var started = false
        try {
            DiscoveryMediaPreviewBoundary(gate).whileCurrent { started = true }
            fail("Locked preview was admitted")
        } catch (_: CancellationException) { }
        assertFalse(started)
    }

    @Test fun `lock cancels blocked read and runs byte cleanup before unlock`() = runTest {
        val gate = PreviewGate()
        val started = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(1, 2, 3)
        val job = launch {
            DiscoveryMediaPreviewBoundary(gate).whileCurrent {
                try { started.complete(Unit); awaitCancellation() } finally { bytes.fill(0) }
            }
        }
        started.await()
        gate.accessState.value = gate.accessState.value.copy(platformUnlocked = false)
        job.join()
        gate.accessState.value = gate.accessState.value.copy(platformUnlocked = true)
        assertTrue(job.isCancelled)
        assertArrayEquals(ByteArray(3), bytes)
    }

    @Test fun `revision change rejects handoff even before flow collector resumes`() = runTest {
        val gate = PreviewGate()
        var handedOff = false
        try {
            DiscoveryMediaPreviewBoundary(gate).whileCurrent { check ->
                gate.authorizationRevision.value++
                check()
                handedOff = true
            }
            fail("Changed authorization was admitted")
        } catch (_: CancellationException) { }
        assertFalse(handedOff)
    }
}

private class PreviewGate : ProtectedPreviewAuthorizationGate {
    override val accessState = MutableStateFlow(ProtectedPreviewAccessState(true, true, true))
    override val authorizationRevision = MutableStateFlow(0L)
    override fun canAttemptPreview() = accessState.value.allowsPreviewAttempt
    override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean) = contentProtectionAuthorized && canAttemptPreview()
}
