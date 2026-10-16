/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.draft

import android.content.Context
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.RoomCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleDraftApplied
import io.element.android.security.dataprotection.api.LifecycleDraftWriteContext
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.SecureObjectIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/** Persists caption-edit text without passing a synthetic event id through the Matrix SDK. */
@Inject
class ProtectedCaptionDraftStore(
    @ApplicationContext context: Context,
    private val sensitiveFileStore: SensitiveFileStore,
    matrixClient: MatrixClient,
    room: JoinedRoom,
    @RoomCoroutineScope roomCoroutineScope: CoroutineScope,
    private val lifecycle: AppOperationalStateCoordinator,
) : ComposerDraftStore {
    private val preferences = context.getSharedPreferences(REFERENCE_STORE_NAME, Context.MODE_PRIVATE)
    private val vaultId = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
    private val boundRoomId = room.roomId
    private val roomIdHash = boundRoomId.value.sha256Hex()
    private val vaultIdHash = vaultId.value.sha256Hex()
    private val accountHash = matrixClient.sessionId.value.sha256Hex()
    private val deviceHash = matrixClient.deviceId.value.sha256Hex()
    private val referenceKey = "caption:$vaultIdHash:$roomIdHash"
    private val mutationMutex = mutexes.getOrPut(referenceKey) { Mutex() }
    private val textVersionKey = "text-version:$vaultIdHash:$roomIdHash"
    private val textSendKey = "text-send:$vaultIdHash:$roomIdHash"
    private val transitionKey = "transition:$vaultIdHash:$roomIdHash"
    private val factPrefix = "applied:$vaultIdHash:$roomIdHash:"
    private val transitionRetiredPrefix = "transition-retired:$vaultIdHash:$roomIdHash:"
    private val transitionStagedPrefix = "transition-staged:$vaultIdHash:$roomIdHash:"

    init {
        roomCoroutineScope.launch {
            runCatching { mutationMutex.withLock { cleanupRetiredLocked(); cleanupTransitionsLocked(); flushAppliedFactsLocked() } }
        }
    }

    /** All persistent composer access serializes through the existing room/vault store. */
    suspend fun prepareComposerDraftWrite(roomId: RoomId, threadRoot: ThreadId?, matrix: MatrixComposerDraftStore) {
        requireScope(roomId, threadRoot)
        mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            check(preferences.all.keys.count { it.startsWith(factPrefix) } < 4_096) { "Draft result handoff requires recovery." }
        }
    }

    suspend fun loadComposerDraft(roomId: RoomId, threadRoot: ThreadId?, matrix: MatrixComposerDraftStore): ComposerDraft? {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            val hadSendBinding = currentTextSendLocked() != null
            val consumed = consumeSentTextDraftLocked(matrix)
            val caption = loadDraftLocked()
            Timber.d("FC002 draft restore room=%s send_binding=%s sent_consumed=%s caption_present=%s version=%s",
                roomId, hadSendBinding, consumed != null, caption != null, preferences.getString(textVersionKey, null))
            caption ?: matrix.loadDraft(roomId, threadRoot)
        }
    }

    suspend fun updateComposerDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?, matrix: MatrixComposerDraftStore) {
        requireScope(roomId, threadRoot)
        val owner = currentCoroutineContext()[LifecycleDraftWriteContext]
        mutationMutex.withLock {
            recoverTransitionLocked(matrix) // Never replay an old write after a newer write is committed.
            flushAppliedFactsLocked()
            Timber.d("FC002 draft write room=%s source=protected present=%s send_binding=%s owner_present=%s version=%s",
                roomId, draft != null, currentTextSendLocked() != null, owner != null, preferences.getString(textVersionKey, null))
            // The automatic empty-editor checkpoint is not a delivery confirmation. Retain the
            // exact pending draft; a nonempty later edit receives a different storage version.
            check(draft != null || currentTextSendLocked() == null) { "The original send draft requires authority reconciliation." }
            owner?.let {
                lifecycle.validateLocalDraftRecovery(it.operationId, accountHash, deviceHash, roomIdHash)
                it.claimResultOwnership(it.operationId)
                check(preferences.getString(factPrefix + it.operationId.sha256Hex(), null) == null) {
                    "Original draft was applied and only requires result handoff."
                }
            }
            val pending = JSONObject().put("schema", 1).put("operationId", owner?.operationId ?: JSONObject.NULL)
                .put("draft", draft?.let(::encodeTransitionDraft) ?: JSONObject.NULL)
            writeTransitionLocked(pending)
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
        }
    }

    suspend fun captureComposerDraftVersion(roomId: RoomId, threadRoot: ThreadId?, matrix: MatrixComposerDraftStore): String? {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            preferences.getString(referenceKey, null)?.also { validateReference(decodeReference(it)) }
        }
    }

    suspend fun clearComposerDraftIfVersion(roomId: RoomId, threadRoot: ThreadId?, version: String?, matrix: MatrixComposerDraftStore): Boolean {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            if (preferences.getString(referenceKey, null) != version) return@withLock false
            clearLocked() // Caption-only CAS; it must never clear a newer ordinary Matrix draft.
            true
        }
    }

    suspend fun textDraftVersion(roomId: RoomId): String? {
        requireScope(roomId, null)
        return mutationMutex.withLock { preferences.getString(textVersionKey, null) }
    }

    suspend fun hasPendingTextSendDraft(roomId: RoomId): Boolean {
        requireScope(roomId, null)
        return mutationMutex.withLock { currentTextSendLocked() != null }
    }

    suspend fun bindTextSendDraft(roomId: RoomId, draft: ComposerDraft, operationId: String, matrix: MatrixComposerDraftStore) {
        requireScope(roomId, null)
        require(draft.draftType == ComposerDraftType.NewMessage && operationId.matches(Regex("lc13-send-[0-9a-f]{64}")))
        mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            val owner = currentCoroutineContext()[LifecycleDraftWriteContext]
            owner?.let {
                lifecycle.validateLocalDraftRecovery(it.operationId, accountHash, deviceHash, roomIdHash)
                it.claimResultOwnership(it.operationId)
            }
            // Content stays in the existing encrypted transition/Matrix store. Preferences only
            // retain opaque pointers; the original LC-13 owner stays authenticated in the encrypted
            // transition. Body equality never owns a clear.
            writeTransitionLocked(JSONObject().put("schema", 1).put("operationId", owner?.operationId ?: JSONObject.NULL)
                .put("draft", encodeTransitionDraft(draft)).put("textSendOperationId", operationId))
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
        }
    }

    suspend fun reconcileSentTextDraft(roomId: RoomId, matrix: MatrixComposerDraftStore, canConsume: () -> Boolean = { true }): ConsumedTextDraft? {
        requireScope(roomId, null)
        return mutationMutex.withLock {
            recoverTransitionLocked(matrix)
            flushAppliedFactsLocked()
            consumeSentTextDraftLocked(matrix, canConsume)
        }
    }

    private suspend fun currentTextSendLocked(): JSONObject? {
        val reference = preferences.getString(textSendKey, null)?.let(::decodeReference) ?: return null
        validateTransitionReference(reference)
        val version = reference.objectId.value.sha256Hex()
        if (version != preferences.getString(textVersionKey, null)) return null
        val original = sensitiveFileStore.read(reference) { input ->
            val plaintext = input.readBytes()
            try { require(plaintext.size <= MAX_DRAFT_BYTES); JSONObject(plaintext.decodeToString()) }
            finally { plaintext.fill(0) }
        }
        require(original.getInt("schema") == 1 && original.getJSONObject("draft").getString("kind") == "new")
        val operationId = original.getString("textSendOperationId")
        require(operationId.matches(Regex("lc13-send-[0-9a-f]{64}")))
        return JSONObject().put("operationId", operationId).put("version", version)
    }

    private suspend fun consumeSentTextDraftLocked(matrix: MatrixComposerDraftStore, canConsume: () -> Boolean = { true }): ConsumedTextDraft? {
        val send = currentTextSendLocked() ?: return null
        val operationId = send.getString("operationId")
        // This reads the signed authority through the existing coordinator. Neither enqueue success,
        // a UI bubble, nor cached operations/terminal projection can authorize consumption.
        val operation = try { lifecycle.operation(operationId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                Timber.d("FC002 draft consume room=%s operation=%s authority=unavailable", boundRoomId, operationId)
                return null
            }
        if (operation.operationId != operationId ||
            operation.lifecycleId != FullAppLifecycleId.LC_13 ||
            operation.subjects.accountIdHash != accountHash || operation.subjects.matrixDeviceIdHash != deviceHash ||
            operation.subjects.roomIdHash != roomIdHash) {
            Timber.d("FC002 draft consume room=%s operation=%s authority=identity_mismatch", boundRoomId, operationId)
            return null
        }
        val matrixFact = operation.steps.singleOrNull {
            it.component == LifecycleComponent.MATRIX && it.required
        } ?: run {
            Timber.d("FC002 draft consume room=%s operation=%s authority=matrix_step_missing", boundRoomId, operationId)
            return null
        }
        val queriedMatrixCommit = matrixFact.authorityRevision?.matches(Regex("matrix-event-query:[0-9a-f]{64}")) == true &&
            matrixFact.receiptReference?.matches(Regex("matrix-event-query:[0-9a-f]{64}")) == true
        // The registration endpoint also verifies Matrix storage and sender ownership before signing
        // this original operation's commit. Its canonical receipt differs from the retry query adapter.
        val registeredMatrixCommit = matrixFact.authorityRevision == "matrix-storage:v1" &&
            matrixFact.resultCode == "MATRIX_EVENT_VERIFIED" &&
            matrixFact.resultReference?.matches(Regex("[0-9a-f]{64}")) == true &&
            matrixFact.receiptReference?.matches(Regex("matrix-event:[A-Za-z0-9_-]{24}")) == true
        if (matrixFact.status != LifecycleStepStatus.COMPLETE || !(queriedMatrixCommit || registeredMatrixCommit)) {
            Timber.d("FC002 draft consume room=%s operation=%s authority=matrix_unconfirmed", boundRoomId, operationId)
            return null
        }
        val draft = matrix.loadDraft(boundRoomId, null) ?: run {
            Timber.d("FC002 draft consume room=%s operation=%s authority=confirmed draft=absent", boundRoomId, operationId)
            return null
        }
        if (draft.draftType != ComposerDraftType.NewMessage) {
            Timber.d("FC002 draft consume room=%s operation=%s authority=confirmed draft=other_mode", boundRoomId, operationId)
            return null
        }
        val version = send.getString("version")
        if (!canConsume()) {
            Timber.d("FC002 draft consume room=%s operation=%s authority=confirmed revision=fenced", boundRoomId, operationId)
            return null
        }
        try {
            lifecycle.performLocalDraft(lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_14, trigger = "draft_change",
                accountIdHash = accountHash, matrixDeviceIdHash = deviceHash, roomIdHash = roomIdHash,
            )) {
                val owner = currentCoroutineContext()[LifecycleDraftWriteContext]
                owner?.let {
                    lifecycle.validateLocalDraftRecovery(it.operationId, accountHash, deviceHash, roomIdHash)
                }
                check(canConsume()) { "A newer editor revision owns the draft." }
                owner?.claimResultOwnership(owner.operationId)
                writeTransitionLocked(JSONObject().put("schema", 1).put("operationId", owner?.operationId ?: JSONObject.NULL)
                    .put("draft", JSONObject.NULL))
                recoverTransitionLocked(matrix)
                flushAppliedFactsLocked()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Cleanup/result transport can fail after the local clear was committed. That must
            // not leave the live editor presenting a consumed version as a new send intent.
            val locallyCommitted = preferences.getString(transitionKey, null) == null &&
                preferences.getString(textSendKey, null) == null && preferences.getString(textVersionKey, null) != version
            if (!locallyCommitted || matrix.loadDraft(boundRoomId, null) != null) throw failure
        }
        Timber.d("FC002 draft consume room=%s operation=%s authority=confirmed result=consumed version=%s", boundRoomId, operationId, version)
        return ConsumedTextDraft(version, draft)
    }

    private fun transitionAssociatedData() = "composer-transition-v1\u0000${vaultId.value}\u0000$roomIdHash".encodeToByteArray()

    private fun validateTransitionReference(reference: SecureObjectReference) {
        require(reference.vaultId == vaultId && reference.purpose == SecureObjectPurpose.EDITING_STAGING)
        require(reference.plaintextLength in 1..MAX_DRAFT_BYTES.toLong())
        require(reference.associatedDataSha256 == transitionAssociatedData().sha256Hex())
    }

    private suspend fun writeTransitionLocked(value: JSONObject) {
        check(preferences.getString(transitionKey, null) == null)
        check(preferences.all.keys.count { it.startsWith(factPrefix) } < 4_096) { "Draft result handoff requires recovery." }
        val id = SecureObjectId("composer-transition-${UUID.randomUUID()}")
        val stagedKey = transitionStagedPrefix + id.value
        val plaintext = value.toString().encodeToByteArray()
        try {
            require(plaintext.size <= MAX_DRAFT_BYTES)
            check(preferences.edit().putString(stagedKey, id.value).commit()) { "Unable to retain draft staging identity." }
            val reference = sensitiveFileStore.write(SecureObjectWriteRequest(id, vaultId, SecureObjectPurpose.EDITING_STAGING,
                transitionAssociatedData(), plaintext.size.toLong())) { it.write(plaintext) }
            validateTransitionReference(reference)
            check(preferences.edit().putString(transitionKey, encodeReference(reference)).remove(stagedKey).commit()) {
                "Unable to retain original composer transition."
            }
        } finally { plaintext.fill(0) }
    }

    private suspend fun recoverTransitionLocked(matrix: MatrixComposerDraftStore) {
        cleanupTransitionsLocked()
        val reference = preferences.getString(transitionKey, null)?.let(::decodeReference) ?: return
        validateTransitionReference(reference)
        val value = sensitiveFileStore.read(reference) { input ->
            val plaintext = input.readBytes()
            try {
                require(plaintext.size <= MAX_DRAFT_BYTES)
                JSONObject(plaintext.decodeToString())
            } finally { plaintext.fill(0) }
        }
        require(value.getInt("schema") == 1)
        require(value.has("operationId") && value.has("draft"))
        val operationId = value.optString("operationId").takeUnless { value.isNull("operationId") }
        operationId?.let { lifecycle.validateLocalDraftRecovery(it, accountHash, deviceHash, roomIdHash) }
        val draft = if (value.isNull("draft")) null else decodeTransitionDraft(value.getJSONObject("draft"))
        val textOperation = value.optString("textSendOperationId").takeIf { it.isNotBlank() }
        if (textOperation != null) {
            require(draft?.draftType == ComposerDraftType.NewMessage && textOperation.matches(Regex("lc13-send-[0-9a-f]{64}")))
        }
        // Commit the destination before clearing the old store. A crash retains this exact
        // encrypted intent; no subsequent persistent write can pass the unresolved transition.
        val apply: suspend () -> Unit = {
            if (draft?.draftType is ComposerDraftType.EditCaption) {
                updateDraftLocked(draft)
                matrix.updateDraft(boundRoomId, null, null)
            } else {
                matrix.updateDraft(boundRoomId, null, draft)
                updateDraftLocked(null)
            }
        }
        if (operationId != null) {
            kotlinx.coroutines.withContext(LifecycleOperationContext(
                operationId, FullAppLifecycleId.LC_14,
            )) { apply() }
        } else {
            apply()
        }
        val version = reference.objectId.value.sha256Hex()
        val editor = preferences.edit().remove(transitionKey).putString(textVersionKey, version)
        preferences.getString(textSendKey, null)?.let { retained ->
            val previous = decodeReference(retained)
            validateTransitionReference(previous)
            editor.putString(transitionRetiredPrefix + previous.objectId.value, retained)
        }
        if (textOperation != null) {
            require(draft?.draftType == ComposerDraftType.NewMessage && textOperation.matches(Regex("lc13-send-[0-9a-f]{64}")))
            // Retain the authenticated encrypted original transition as the handoff binding.
            editor.putString(textSendKey, encodeReference(reference))
        } else {
            editor.remove(textSendKey).putString(transitionRetiredPrefix + reference.objectId.value, encodeReference(reference))
        }

        operationId?.let {
            val fact = LifecycleDraftApplied(it, accountHash, deviceHash, roomIdHash,
                reference.objectId.value.sha256Hex(), System.currentTimeMillis())
            editor.putString(factPrefix + it.sha256Hex(), encodeFact(fact))
        }
        check(editor.commit()) { "Unable to commit original draft result." }
        cleanupTransitionsLocked()
    }

    private suspend fun flushAppliedFactsLocked() {
        preferences.all.filterKeys { it.startsWith(factPrefix) }.forEach { (key, encoded) ->
            val fact = decodeFact(checkNotNull(encoded as? String))
            require(key == factPrefix + fact.operationId.sha256Hex())
            require(fact.accountIdHash == accountHash && fact.matrixDeviceIdHash == deviceHash && fact.roomIdHash == roomIdHash)
            // Network/receipt-store failure retains the completed version. It does not block
            // reading a saved draft or cause the corresponding write to be replayed.
            val accepted = try { lifecycle.recoverLocalDraftApplied(fact) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (accepted) check(preferences.edit().remove(key).commit()) { "Unable to retire draft result handoff." }
        }
    }

    private suspend fun cleanupTransitionsLocked() {
        preferences.all.filterKeys { it.startsWith(transitionRetiredPrefix) }.forEach { (key, encoded) ->
            val reference = decodeReference(checkNotNull(encoded as? String))
            validateTransitionReference(reference)
            if (sensitiveFileStore.delete(reference)) check(preferences.edit().remove(key).commit())
        }
        preferences.all.filterKeys { it.startsWith(transitionStagedPrefix) }.forEach { (key, encoded) ->
            val id = checkNotNull(encoded as? String)
            require(key == transitionStagedPrefix + id && id.startsWith("composer-transition-"))
            if (sensitiveFileStore.deleteObject(SecureObjectIdentity(SecureObjectId(id), vaultId, SecureObjectPurpose.EDITING_STAGING))) {
                check(preferences.edit().remove(key).commit())
            }
        }
    }

    private fun encodeFact(fact: LifecycleDraftApplied) = JSONObject().put("operationId", fact.operationId)
        .put("accountIdHash", fact.accountIdHash).put("matrixDeviceIdHash", fact.matrixDeviceIdHash).put("roomIdHash", fact.roomIdHash)
        .put("storageVersionHash", fact.storageVersionHash).put("occurredAtEpochMillis", fact.occurredAtEpochMillis).toString()

    private fun decodeFact(encoded: String) = JSONObject(encoded).let {
        LifecycleDraftApplied(it.getString("operationId"), it.getString("accountIdHash"), it.getString("matrixDeviceIdHash"),
            it.getString("roomIdHash"), it.getString("storageVersionHash"), it.getLong("occurredAtEpochMillis"))
    }

    private fun encodeTransitionDraft(draft: ComposerDraft): JSONObject {
        val (kind, event) = when (val type = draft.draftType) {
            ComposerDraftType.NewMessage -> "new" to null
            is ComposerDraftType.Edit -> "edit" to type.eventId.value
            is ComposerDraftType.EditCaption -> "caption" to type.eventId.value
            is ComposerDraftType.Reply -> "reply" to type.eventId.value
        }
        return JSONObject().put("kind", kind).put("event", event ?: JSONObject.NULL)
            .put("plainText", draft.plainText).put("htmlText", draft.htmlText ?: JSONObject.NULL)
    }

    private fun decodeTransitionDraft(value: JSONObject): ComposerDraft {
        fun event(): EventId = EventId(value.getString("event").also { require(it.startsWith('$') && it.length <= MAX_EVENT_ID_CHARS) })
        val type = when (value.getString("kind")) {
            "new" -> ComposerDraftType.NewMessage
            "edit" -> ComposerDraftType.Edit(event())
            "caption" -> ComposerDraftType.EditCaption(event())
            "reply" -> ComposerDraftType.Reply(event())
            else -> error("Unknown retained composer draft kind.")
        }
        return ComposerDraft(value.getString("plainText"), value.optString("htmlText").takeUnless { value.isNull("htmlText") }, type)
    }

    override suspend fun loadDraft(roomId: RoomId, threadRoot: ThreadId?): ComposerDraft? {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock { loadDraftLocked() }
    }

    private suspend fun loadDraftLocked(): ComposerDraft? {
        cleanupRetiredLocked()
        val reference = preferences.getString(referenceKey, null)?.let(::decodeReference) ?: return null
        validateReference(reference)
        return sensitiveFileStore.read(reference) { input ->
            val plaintext = input.readBytes()
            try {
                require(plaintext.size <= MAX_DRAFT_BYTES) { "Protected caption draft is too large." }
                decodeDraft(plaintext.decodeToString())
            } finally {
                plaintext.fill(0)
            }
        }
    }

    override suspend fun updateDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?) {
        requireScope(roomId, threadRoot)
        mutationMutex.withLock { updateDraftLocked(draft) }
    }

    suspend fun captureVersion(roomId: RoomId, threadRoot: ThreadId?): String? {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock {
            cleanupRetiredLocked()
            preferences.getString(referenceKey, null)?.also { validateReference(decodeReference(it)) }
        }
    }

    suspend fun clearIfVersion(roomId: RoomId, threadRoot: ThreadId?, version: String?): Boolean {
        requireScope(roomId, threadRoot)
        return mutationMutex.withLock {
            cleanupRetiredLocked()
            if (preferences.getString(referenceKey, null) != version) return@withLock false
            clearLocked()
            true
        }
    }

    private suspend fun updateDraftLocked(draft: ComposerDraft?) {
        cleanupRetiredLocked()
        if (draft == null) {
            clearLocked()
            return
        }
        require(draft.draftType is ComposerDraftType.EditCaption)
        val plaintext = encodeDraft(draft).encodeToByteArray()
        require(plaintext.size <= MAX_DRAFT_BYTES) { "Protected caption draft is too large." }
        val previous = preferences.getString(referenceKey, null)?.let(::decodeReference)?.also(::validateReference)
        val reference = try {
            sensitiveFileStore.write(
                SecureObjectWriteRequest(
                    objectId = SecureObjectId("caption-draft-${UUID.randomUUID()}"),
                    vaultId = vaultId,
                    purpose = SecureObjectPurpose.EDITING_STAGING,
                    associatedData = associatedData(),
                    expectedPlaintextLength = plaintext.size.toLong(),
                )
            ) { output -> output.write(plaintext) }
        } finally {
            plaintext.fill(0)
        }
        val editor = preferences.edit().putString(referenceKey, encodeReference(reference))
        previous?.let { editor.putString(retiredKey(it), encodeReference(it)) }
        if (!editor.commit()) {
            sensitiveFileStore.delete(reference)
            error("Unable to commit protected caption draft reference.")
        }
        previous?.let { old ->
            if (sensitiveFileStore.delete(old)) preferences.edit().remove(retiredKey(old)).commit()
        }
    }

    private suspend fun clearLocked() {
        val reference = preferences.getString(referenceKey, null)?.let(::decodeReference) ?: return
        validateReference(reference)
        check(
            preferences.edit()
                .putString(retiredKey(reference), encodeReference(reference))
                .remove(referenceKey)
                .commit()
        ) { "Unable to clear protected caption draft reference." }
        if (sensitiveFileStore.delete(reference)) preferences.edit().remove(retiredKey(reference)).commit()
    }

    private suspend fun cleanupRetiredLocked() {
        preferences.all
            .filterKeys { it.startsWith(retiredPrefix) }
            .forEach { (key, encoded) ->
                val reference = (encoded as? String)?.let { runCatching { decodeReference(it) }.getOrNull() }
                    ?: return@forEach
                if (runCatching { validateReference(reference) }.isSuccess && sensitiveFileStore.delete(reference)) {
                    preferences.edit().remove(key).commit()
                }
            }
    }

    private fun requireScope(roomId: RoomId, threadRoot: ThreadId?) {
        require(roomId == boundRoomId) { "Caption draft belongs to another room." }
        require(threadRoot == null) { "Caption drafts in threads are not supported." }
    }

    private fun validateReference(reference: SecureObjectReference) {
        require(reference.vaultId == vaultId) { "Caption draft belongs to another account or device." }
        require(reference.purpose == SecureObjectPurpose.EDITING_STAGING)
        require(reference.plaintextLength in 1..MAX_DRAFT_BYTES.toLong())
        require(reference.associatedDataSha256 == associatedData().sha256Hex()) {
            "Caption draft room binding does not match."
        }
    }

    private fun associatedData(): ByteArray =
        "caption-draft-v1\u0000${vaultId.value}\u0000$roomIdHash".encodeToByteArray()

    private val retiredPrefix: String get() = "retired:$vaultIdHash:$roomIdHash:"

    private fun retiredKey(reference: SecureObjectReference): String = retiredPrefix + reference.objectId.value

    private fun encodeDraft(draft: ComposerDraft): String {
        val type = draft.draftType as ComposerDraftType.EditCaption
        return JSONObject()
            .put("schema", 1)
            .put("eventId", type.eventId.value)
            .put("plainText", draft.plainText)
            .put("htmlText", draft.htmlText)
            .toString()
    }

    private fun decodeDraft(encoded: String): ComposerDraft = JSONObject(encoded).let { value ->
        require(value.getInt("schema") == 1)
        val eventId = value.getString("eventId")
        require(eventId.startsWith('$') && eventId.length <= MAX_EVENT_ID_CHARS)
        ComposerDraft(
            plainText = value.getString("plainText"),
            htmlText = value.optString("htmlText").takeUnless { value.isNull("htmlText") },
            draftType = ComposerDraftType.EditCaption(EventId(eventId)),
        )
    }

    private fun encodeReference(reference: SecureObjectReference): String = JSONObject()
        .put("schema", 1)
        .put("objectId", reference.objectId.value)
        .put("vaultId", reference.vaultId.value)
        .put("purpose", reference.purpose.name)
        .put("keyEnvelopeId", reference.keyEnvelopeId.value)
        .put("formatVersion", reference.formatVersion)
        .put("plaintextLength", reference.plaintextLength)
        .put("ciphertextLength", reference.ciphertextLength)
        .put("associatedDataSha256", reference.associatedDataSha256)
        .toString()

    private fun decodeReference(encoded: String): SecureObjectReference = JSONObject(encoded).let { value ->
        require(value.getInt("schema") == 1)
        SecureObjectReference(
            objectId = SecureObjectId(value.getString("objectId")),
            vaultId = AccountVaultId(value.getString("vaultId")),
            purpose = SecureObjectPurpose.valueOf(value.getString("purpose")),
            keyEnvelopeId = KeyEnvelopeId(value.getString("keyEnvelopeId")),
            formatVersion = value.getInt("formatVersion"),
            plaintextLength = value.getLong("plaintextLength"),
            ciphertextLength = value.getLong("ciphertextLength"),
            associatedDataSha256 = value.getString("associatedDataSha256"),
        )
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val mutexes = ConcurrentHashMap<String, Mutex>()
        const val REFERENCE_STORE_NAME = "protected_caption_draft_references_v1"
        const val MAX_DRAFT_BYTES = 256 * 1024
        const val MAX_EVENT_ID_CHARS = 4_096
    }
}
