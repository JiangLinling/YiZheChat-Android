/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.RoomCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.dataprotection.api.hasLocalVoiceDraftSourceDenial
import io.element.android.security.dataprotection.api.isLocalVoiceDraftSourceAuthorizedAt
import io.element.android.security.dataprotection.api.localVoiceDraftSourceExpiresAtEpochMillis
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

data class ProtectedVoiceDraft(
    val operationId: String,
    val uri: Uri,
    val mimeType: String,
    val waveform: List<Float>,
    val duration: Duration,
    val authorizationDeadlineEpochMillis: Long,
    internal val reference: SecureObjectReference,
    internal val lease: SecureMediaLease,
)

interface ProtectedVoiceDraftStore {
    /** Monotonic signal for hard authorization transitions affecting composer-owned drafts. */
    val revocationGeneration: StateFlow<Long>
    suspend fun protect(operationId: String, recording: VoiceRecorderState.Finished): ProtectedVoiceDraft
    suspend fun reauthorize(draft: ProtectedVoiceDraft): ProtectedVoiceDraft
    suspend fun discard(draft: ProtectedVoiceDraft): Boolean
    suspend fun recoverAbandonedDrafts(): Int
}

/**
 * Performs the LC-17 capture -> LC-16 transfer handoff without leaving the encoder scratch file
 * as the preview/send source. The journal contains only encrypted-object identifiers and exists so
 * process recovery can delete an object whose process-bound lease no longer exists.
 */
@ContributesBinding(RoomScope::class)
class DefaultProtectedVoiceDraftStore(
    @ApplicationContext context: Context,
    private val sensitiveFileStore: SensitiveFileStore,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    @RoomCoroutineScope roomCoroutineScope: CoroutineScope,
    private val matrixClient: MatrixClient,
    room: JoinedRoom,
) : ProtectedVoiceDraftStore {
    private val preferences = context.getSharedPreferences(JOURNAL_NAME, Context.MODE_PRIVATE)
    private val vaultId = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
    private val roomIdHash = room.roomId.value.sha256Hex()
    private val activeOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val mutableRevocationGeneration = MutableStateFlow(0L)
    override val revocationGeneration: StateFlow<Long> = mutableRevocationGeneration.asStateFlow()

    init {
        var observedAuthorizationRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
        roomCoroutineScope.launch {
            combine(
                protectedPreviewAuthorizationGate.accessState,
                protectedPreviewAuthorizationGate.authorizationRevision,
            ) { accessState, revision -> accessState to revision }
                .collect { (accessState, revision) ->
                    val authorizationChanged = revision != observedAuthorizationRevision
                    observedAuthorizationRevision = revision
                    if (!accessState.allowsPreviewAttempt || authorizationChanged) {
                        activeOperationIds.toList().forEach { operationId ->
                            secureMediaLeaseManager.revokeScope(revocationScope(operationId))
                        }
                        mutableRevocationGeneration.update { generation -> generation + 1L }
                    }
                }
        }
    }

    override suspend fun protect(
        operationId: String,
        recording: VoiceRecorderState.Finished,
    ): ProtectedVoiceDraft {
        require(operationId.length in 16..256)
        require(recording.file.isFile) { "Voice capture scratch file is unavailable." }
        val reference = sensitiveFileStore.write(
            SecureObjectWriteRequest(
                objectId = SecureObjectId("voice-draft-${UUID.randomUUID()}"),
                vaultId = vaultId,
                purpose = SecureObjectPurpose.VOICE_MESSAGE,
                associatedData = associatedData(operationId, recording.mimeType),
                expectedPlaintextLength = recording.file.length(),
            )
        ) { output ->
            recording.file.inputStream().buffered().use { input -> input.copyTo(output) }
        }
        ACTIVE_OBJECT_IDS.add(reference.objectId.value)
        if (!writeJournal(operationId, reference)) {
            ACTIVE_OBJECT_IDS.remove(reference.objectId.value)
            sensitiveFileStore.delete(reference)
            error("Unable to persist protected voice draft cleanup journal.")
        }

        activeOperationIds.add(operationId)
        val issuedLease = try {
            issueLease(
                reference = reference,
                mimeType = recording.mimeType,
                operationId = operationId,
            )
        } catch (failure: Exception) {
            activeOperationIds.remove(operationId)
            ACTIVE_OBJECT_IDS.remove(reference.objectId.value)
            if (sensitiveFileStore.delete(reference)) removeJournal(reference)
            throw failure
        }
        return ProtectedVoiceDraft(
            operationId = operationId,
            uri = issuedLease.lease.uri,
            mimeType = recording.mimeType,
            waveform = recording.waveform,
            duration = recording.duration,
            authorizationDeadlineEpochMillis = issuedLease.authorizationDeadlineEpochMillis,
            reference = reference,
            lease = issuedLease.lease,
        )
    }

    override suspend fun reauthorize(draft: ProtectedVoiceDraft): ProtectedVoiceDraft {
        // A draft can outlive its process-bound read lease while the encrypted object remains valid.
        // Close the old grant before issuing a fresh bounded grant for retry/send.
        draft.lease.close()
        val renewedLease = issueLease(
            reference = draft.reference,
            mimeType = draft.mimeType,
            operationId = draft.operationId,
        )
        return draft.copy(
            uri = renewedLease.lease.uri,
            authorizationDeadlineEpochMillis = renewedLease.authorizationDeadlineEpochMillis,
            lease = renewedLease.lease,
        )
    }

    override suspend fun discard(draft: ProtectedVoiceDraft): Boolean {
        draft.lease.close()
        secureMediaLeaseManager.revokeScope(revocationScope(draft.operationId))
        activeOperationIds.remove(draft.operationId)
        ACTIVE_OBJECT_IDS.remove(draft.reference.objectId.value)
        val deleted = sensitiveFileStore.delete(draft.reference)
        if (deleted) removeJournal(draft.reference)
        return deleted
    }

    override suspend fun recoverAbandonedDrafts(): Int {
        var deleted = 0
        preferences.all.values.filterIsInstance<String>().forEach { encoded ->
            val entry = runCatching { decodeJournalEntry(encoded) }.getOrNull() ?: return@forEach
            if (entry.roomIdHash != roomIdHash || entry.reference.objectId.value in ACTIVE_OBJECT_IDS) return@forEach
            val reference = entry.reference
            val wasDeleted = runCatching {
                cleanupAbandonedVoiceDraft(
                    operationId = entry.operationId,
                    revokeScope = secureMediaLeaseManager::revokeScope,
                    deleteObject = { sensitiveFileStore.delete(reference) },
                )
            }.getOrDefault(false)
            if (wasDeleted) {
                removeJournal(reference)
                deleted++
            }
        }
        return deleted
    }

    private fun writeJournal(operationId: String, reference: SecureObjectReference): Boolean = preferences.edit()
        .putString(journalKey(reference), encodeReference(operationId, reference))
        .commit()

    private fun removeJournal(reference: SecureObjectReference) {
        preferences.edit().remove(journalKey(reference)).commit()
    }

    private fun associatedData(operationId: String, mimeType: String): ByteArray =
        "voice-draft-v1\u0000$operationId\u0000$roomIdHash\u0000$mimeType".encodeToByteArray()

    private fun journalKey(reference: SecureObjectReference): String = "draft:${reference.objectId.value}"

    private fun revocationScope(operationId: String): String = "voice-draft:$operationId"

    internal suspend fun issueLease(
        reference: SecureObjectReference,
        mimeType: String,
        operationId: String,
    ): IssuedVoiceDraftLease {
        val sourceIdentity = currentSourceIdentity()
        val authorizationRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
        check(dataProtectionPolicyProvider.current().source != DataProtectionPolicySource.FAIL_CLOSED) {
            "Voice draft access is blocked by policy."
        }
        check(protectedPreviewAuthorizationGate.canAttemptPreview()) { "Voice draft access is not currently authorized." }
        val sourceAuthorization = resolveVoiceDraftSourceAuthorization(
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            accountIdHash = sourceIdentity.accountIdHash,
            matrixDeviceIdHash = sourceIdentity.matrixDeviceIdHash,
            contextStillCurrent = {
                currentSourceIdentity() == sourceIdentity &&
                    protectedPreviewAuthorizationGate.canAttemptPreview() &&
                    protectedPreviewAuthorizationGate.authorizationRevision.value == authorizationRevision
            },
        )
        // Refresh can suspend. Every mutable authority input used by the lease must be sampled again.
        val nowEpochMillis = System.currentTimeMillis()
        val activePolicy = dataProtectionPolicyProvider.current()
        check(activePolicy.source != DataProtectionPolicySource.FAIL_CLOSED) { "Voice draft access is blocked by policy." }
        check(protectedPreviewAuthorizationGate.canAttemptPreview()) { "Voice draft access is not currently authorized." }
        check(protectedPreviewAuthorizationGate.authorizationRevision.value == authorizationRevision) {
            "Voice draft authorization changed during source refresh."
        }
        check(currentSourceIdentity() == sourceIdentity) { "Voice draft source identity changed during authorization." }
        check(sourceAuthorizationStillCurrent(sourceAuthorization, sourceIdentity)) {
            "Voice draft source authorization changed before lease issue."
        }
        val deadlineEpochMillis = voiceDraftLeaseDeadline(
            nowEpochMillis = nowEpochMillis,
            policy = activePolicy.policy,
            sourceAuthorizationDeadlineEpochMillis = sourceAuthorization.localVoiceDraftSourceExpiresAtEpochMillis,
        )
        check(deadlineEpochMillis > nowEpochMillis) { "Voice draft authorization has expired." }
        val lease = secureMediaLeaseManager.issue(
            SecureMediaLeaseRequest(
                reference = reference,
                mimeType = mimeType,
                displayName = null,
                timeToLive = (deadlineEpochMillis - nowEpochMillis).milliseconds,
                revocationScope = revocationScope(operationId),
                requireSeekableDescriptor = true,
                deleteObjectOnRevoke = false,
                isReadAuthorized = {
                    val currentPolicy = runCatching { dataProtectionPolicyProvider.current() }.getOrNull()
                    System.currentTimeMillis() < deadlineEpochMillis &&
                        sourceAuthorizationStillCurrent(sourceAuthorization) &&
                        protectedPreviewAuthorizationGate.canAttemptPreview() &&
                        protectedPreviewAuthorizationGate.authorizationRevision.value == authorizationRevision &&
                        currentPolicy?.source != DataProtectionPolicySource.FAIL_CLOSED &&
                        currentPolicy?.policy?.policyId == activePolicy.policy.policyId &&
                        currentPolicy.policy.version == activePolicy.policy.version
                },
            )
        )
        return IssuedVoiceDraftLease(lease, deadlineEpochMillis)
    }

    private fun sourceAuthorizationStillCurrent(
        expected: AppOperationalSnapshot,
        expectedIdentity: VoiceDraftSourceIdentity = currentSourceIdentity(),
    ): Boolean {
        val current = appOperationalStateCoordinator.state.value.sourceAuthorizationSnapshot()
        val nowEpochMillis = System.currentTimeMillis()
        return currentSourceIdentity() == expectedIdentity &&
            current?.isLocalVoiceDraftSourceAuthorizedAt(
                nowEpochMillis = nowEpochMillis,
                accountIdHash = expectedIdentity.accountIdHash,
                matrixDeviceIdHash = expectedIdentity.matrixDeviceIdHash,
            ) == true &&
            current.signingKeyId == expected.signingKeyId &&
            current.signatureBase64 == expected.signatureBase64
    }

    private fun currentSourceIdentity(): VoiceDraftSourceIdentity = VoiceDraftSourceIdentity(
        accountIdHash = matrixClient.sessionId.value.sha256Hex(),
        matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
    )

    internal data class IssuedVoiceDraftLease(
        val lease: SecureMediaLease,
        val authorizationDeadlineEpochMillis: Long,
    )

    private data class VoiceDraftSourceIdentity(
        val accountIdHash: String,
        val matrixDeviceIdHash: String,
    )

    private data class JournalEntry(
        val operationId: String,
        val roomIdHash: String,
        val reference: SecureObjectReference,
    )

    private fun encodeReference(operationId: String, reference: SecureObjectReference): String = JSONObject()
        .put("operationId", operationId)
        .put("roomIdHash", roomIdHash)
        .put("objectId", reference.objectId.value)
        .put("vaultId", reference.vaultId.value)
        .put("purpose", reference.purpose.name)
        .put("keyEnvelopeId", reference.keyEnvelopeId.value)
        .put("formatVersion", reference.formatVersion)
        .put("plaintextLength", reference.plaintextLength)
        .put("ciphertextLength", reference.ciphertextLength)
        .put("associatedDataSha256", reference.associatedDataSha256)
        .toString()

    private fun decodeJournalEntry(encoded: String): JournalEntry = JSONObject(encoded).let { value ->
        JournalEntry(
            operationId = value.getString("operationId"),
            roomIdHash = value.getString("roomIdHash"),
            reference = SecureObjectReference(
                objectId = SecureObjectId(value.getString("objectId")),
                vaultId = io.element.android.security.securestorage.api.AccountVaultId(value.getString("vaultId")),
                purpose = SecureObjectPurpose.valueOf(value.getString("purpose")),
                keyEnvelopeId = io.element.android.security.securestorage.api.KeyEnvelopeId(value.getString("keyEnvelopeId")),
                formatVersion = value.getInt("formatVersion"),
                plaintextLength = value.getLong("plaintextLength"),
                ciphertextLength = value.getLong("ciphertextLength"),
                associatedDataSha256 = value.getString("associatedDataSha256"),
            ),
        )
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val JOURNAL_NAME = "protected_voice_draft_cleanup_v1"
        val ACTIVE_OBJECT_IDS = ConcurrentHashMap.newKeySet<String>()
    }
}

internal fun voiceDraftLeaseDeadline(
    nowEpochMillis: Long,
    policy: DataProtectionPolicyV1,
    sourceAuthorizationDeadlineEpochMillis: Long,
): Long {
    val localCap = nowEpochMillis.saturatedPlus(VOICE_DRAFT_LEASE_MAX_TTL.inWholeMilliseconds)
    val retentionDeadline = nowEpochMillis.saturatedPlus(policy.contentRetentionMillis)
    return minOf(
        localCap,
        retentionDeadline,
        policy.expiresAtEpochMillis ?: Long.MAX_VALUE,
        sourceAuthorizationDeadlineEpochMillis,
    )
}

internal suspend fun resolveVoiceDraftSourceAuthorization(
    appOperationalStateCoordinator: AppOperationalStateCoordinator,
    accountIdHash: String,
    matrixDeviceIdHash: String,
    contextStillCurrent: () -> Boolean = { true },
    nowEpochMillis: () -> Long = System::currentTimeMillis,
): AppOperationalSnapshot {
    val initialState = appOperationalStateCoordinator.state.value
    val initialNow = nowEpochMillis()
    initialState.sourceAuthorizationSnapshot()
        ?.takeIf { snapshot ->
            snapshot.isLocalVoiceDraftSourceAuthorizedAt(
                nowEpochMillis = initialNow,
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
            )
        }
        ?.let { return it }

    check(initialState.canRefreshVoiceDraftSourceAuthorization(initialNow, accountIdHash, matrixDeviceIdHash)) {
        "Voice draft source authorization is blocked or outside its refreshable state."
    }

    val refreshed = try {
        appOperationalStateCoordinator.refresh(accountIdHash, matrixDeviceIdHash)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }
    check(contextStillCurrent()) { "Voice draft authorization changed during source refresh." }

    val refreshedNow = nowEpochMillis()
    val snapshot = sequenceOf(
        refreshed,
        appOperationalStateCoordinator.state.value.sourceAuthorizationSnapshot(),
    ).filterNotNull().firstOrNull { candidate ->
        candidate.isLocalVoiceDraftSourceAuthorizedAt(
            nowEpochMillis = refreshedNow,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
    }
    check(snapshot != null) { "Voice draft source authorization is unavailable or expired." }
    return snapshot
}

private fun AppOperationalCoordinatorState.sourceAuthorizationSnapshot(): AppOperationalSnapshot? = when (this) {
    is AppOperationalCoordinatorState.Available -> snapshot
    is AppOperationalCoordinatorState.RetryableFailure -> lastVerifiedSnapshot
    AppOperationalCoordinatorState.Discovering -> null
}

private fun AppOperationalCoordinatorState.canRefreshVoiceDraftSourceAuthorization(
    nowEpochMillis: Long,
    accountIdHash: String,
    matrixDeviceIdHash: String,
): Boolean {
    val snapshot = sourceAuthorizationSnapshot() ?: return true
    if (
        snapshot.accountIdHash == accountIdHash &&
        snapshot.matrixDeviceIdHash == matrixDeviceIdHash &&
        snapshot.hasLocalVoiceDraftSourceDenial()
    ) {
        return false
    }
    if (snapshot.accountIdHash != accountIdHash || snapshot.matrixDeviceIdHash != matrixDeviceIdHash) return true
    return nowEpochMillis >= snapshot.localVoiceDraftSourceExpiresAtEpochMillis
}

internal suspend fun cleanupAbandonedVoiceDraft(
    operationId: String,
    revokeScope: (String) -> Unit,
    deleteObject: suspend () -> Boolean,
): Boolean {
    require(operationId.length in 16..256)
    revokeScope("voice-draft:$operationId")
    return deleteObject()
}

private fun Long.saturatedPlus(delta: Long): Long =
    if (delta > Long.MAX_VALUE - this) Long.MAX_VALUE else this + delta

internal val VOICE_DRAFT_LEASE_MAX_TTL = 30.minutes

