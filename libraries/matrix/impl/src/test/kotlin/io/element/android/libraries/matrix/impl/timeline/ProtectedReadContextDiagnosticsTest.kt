/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import timber.log.Timber

class ProtectedReadContextDiagnosticsTest {
    @Test
    fun `all nine identity fields match only exact equality`() {
        val identity = identity()
        assertEquals(511, ProtectedReadContextDiagnostics.matchingFields(identity, identity.copy()))
        val variations = listOf(
            identity.copy(projection = identity.projection.copy(eventId = "other-event")) to 1,
            identity.copy(operationId = "other-operation") to 2,
            identity.copy(matrixDeviceId = "other-device") to 4,
            identity.copy(installationId = "other-installation") to 8,
            identity.copy(authorizationRevision = 8) to 16,
            identity.copy(previewRevision = 9) to 32,
            identity.copy(releaseClass = TimelineContentReleaseClass.HISTORY_PREFETCH) to 64,
            identity.copy(releaseClass = TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP) to 64,
            identity.copy(encryptedMessage = identity.encryptedMessage.copy(isEdited = true)) to 128,
            identity.copy(readPurpose = TimelineContentReadPurpose.ROOM_PREVIEW) to 256,
        )
        variations.forEach { (other, bit) -> assertEquals(511 xor bit, ProtectedReadContextDiagnostics.matchingFields(identity, other)) }
    }

    @Test
    fun `diagnostic descriptor never includes carrier or device identity`() {
        val identity = identity()
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            ProtectedReadContextDiagnostics.observed("receive_read_owner_replace_attempt_equal", identity,
                identity.copy(readPurpose = TimelineContentReadPurpose.ROOM_PREVIEW))
            val message = tree.messages.joinToString()
            assertFalse(message.contains("PRIVATE_CARRIER"))
            assertFalse(message.contains("PRIVATE_DEVICE"))
            assertFalse(message.contains("PRIVATE_INSTALLATION"))
            assertEquals(true, message.contains("pTIMELINE_cFOREGROUND_VISIBLE_a1_v2_m255"))
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `absent owner is none and logging failure does not escape`() {
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                throw IllegalStateException("fixture logger failure")
            }
        }
        Timber.plant(tree)
        try {
            ProtectedReadContextDiagnostics.observed("receive_read_completed_miss", identity(), null)
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun identity() = ProtectedTimelineDecryptionCache.ReadIdentity(
        projection = ProtectedTimelineDecryptionCache.Key("fixture-event", ContentId("fixture-content"),
            "fixture-cipher-hash", 1, 5, "fixture-session", "fixture-room", Long.MAX_VALUE),
        operationId = "fixture-operation", matrixDeviceId = "PRIVATE_DEVICE", installationId = "PRIVATE_INSTALLATION",
        authorizationRevision = 1, previewRevision = 2, releaseClass = TimelineContentReleaseClass.FOREGROUND_VISIBLE,
        encryptedMessage = MessageContent("PRIVATE_CARRIER", null, false, null, TextMessageType("PRIVATE_CARRIER", null)),
        readPurpose = TimelineContentReadPurpose.TIMELINE,
    )

    private class RecordingTree : Timber.Tree() {
        val messages = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }
}
