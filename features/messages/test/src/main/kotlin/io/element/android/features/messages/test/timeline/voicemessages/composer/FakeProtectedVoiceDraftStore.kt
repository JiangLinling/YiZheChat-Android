/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.test.timeline.voicemessages.composer

import android.net.Uri
import io.element.android.features.messages.impl.voicemessages.composer.ProtectedVoiceDraft
import io.element.android.features.messages.impl.voicemessages.composer.ProtectedVoiceDraftStore
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeProtectedVoiceDraftStore : ProtectedVoiceDraftStore {
    private val mutableRevocationGeneration = MutableStateFlow(0L)
    override val revocationGeneration: StateFlow<Long> = mutableRevocationGeneration.asStateFlow()

    fun revokeComposerDrafts() {
        mutableRevocationGeneration.value += 1L
    }

    override suspend fun protect(
        operationId: String,
        recording: VoiceRecorderState.Finished,
    ): ProtectedVoiceDraft {
        val uri = Uri.fromFile(recording.file)
        return ProtectedVoiceDraft(
            operationId = operationId,
            uri = uri,
            mimeType = recording.mimeType,
            waveform = recording.waveform,
            duration = recording.duration,
            authorizationDeadlineEpochMillis = Long.MAX_VALUE,
            reference = SecureObjectReference(
                objectId = SecureObjectId("fake-voice-draft"),
                vaultId = AccountVaultId("fake-vault"),
                purpose = SecureObjectPurpose.VOICE_MESSAGE,
                keyEnvelopeId = KeyEnvelopeId("fake-key-envelope"),
                formatVersion = 1,
                plaintextLength = recording.file.length(),
                ciphertextLength = recording.file.length(),
                associatedDataSha256 = "fake",
            ),
            lease = object : SecureMediaLease {
                override val uri: Uri = uri
                override fun openInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun close() = Unit
            },
        )
    }

    override suspend fun reauthorize(draft: ProtectedVoiceDraft): ProtectedVoiceDraft = draft

    override suspend fun discard(draft: ProtectedVoiceDraft): Boolean = true

    override suspend fun recoverAbandonedDrafts(): Int = 0
}
