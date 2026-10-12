/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.draft

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import android.content.Context
import android.os.Parcel
import android.util.Base64
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.*
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.security.securestorage.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.hours

internal data class MediaSelectionDraft(
    val attachments: List<Attachment>, val selected: List<Int>, val focused: Int,
    val edits: Map<Int, AttachmentImageEdits>, val pending: AttachmentImageEdits?,
    val originalPhotos: Boolean, val videoQuality: VideoCompressionPreset,
    val resources: Map<Int, SecureObjectReference>,
    val sources: List<Attachment> = attachments,
    val committed: List<Int> = emptyList(),
    val burnAfterRead: Boolean = false,
    val inFlight: List<Int> = emptyList(),
    val submissions: Map<Int, MediaSubmissionFact> = emptyMap(),
    val discarded: List<Int> = emptyList(),
    val cancellationRequested: List<Int> = emptyList(),
)

/** LC-14 draft adapter: all URIs, labels and edit choices are stored inside the existing device vault. */
@SingleIn(SessionScope::class)
@Inject
class MediaSelectionDraftStore(
    @ApplicationContext private val context: Context,
    private val client: MatrixClient,
    private val files: SensitiveFileStore,
    private val leases: SecureMediaLeaseManager,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val activeTransfers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal fun beginTransfer(operation: String) {
        check(activeTransfers.add(operation)) { "Original media transfer already has an active owner." }
    }
    internal fun finishTransfer(operation: String) {
        activeTransfers.remove(operation)
    }
    private val prefs get() = context.getSharedPreferences("encrypted_media_editor_drafts_v1", Context.MODE_PRIVATE)
    private val vault get() = deriveAccountVaultId(client.sessionId.value, client.deviceId.value)
    private fun key(operation: String) = MessageDigest.getInstance(
        "SHA-256"
    ).digest((vault.value + operation).toByteArray()).joinToString("") { "%02x".format(it) }

    internal suspend fun load(operation: String): MediaSelectionDraft? = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(operation) }
    }

    internal suspend fun latestSources(roomHash: String, burnAfterRead: Boolean): List<Attachment>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            var latest: Pair<Long, List<Attachment>>? = null
            prefs.all.values.filterIsInstance<String>().forEach { raw ->
                val ref = decodeReference(JSONObject(raw))
                if (ref.vaultId != vault) return@forEach
                require(ref.plaintextLength in 0..8 * 1024 * 1024)
                val json = files.read(ref) { stream ->
                    val bytes = stream.readBytes()
                    try {
                        JSONObject(bytes.decodeToString())
                    } finally {
                        bytes.fill(0)
                    }
                }
                val operation = json.getString("operation")
                if (operation in activeTransfers) return@forEach
                val draft = loadLocked(operation) ?: return@forEach
                if (draft.burnAfterRead == burnAfterRead && draft.sources.all { (it as Attachment.Media).localMedia.lifecycleRoomIdHash == roomHash } &&
                    (draft.hasUnknownMediaSubmission() || draft.inFlight.isNotEmpty() ||
                        draft.cancellationRequested.any { it !in draft.discarded } || draft.selected.any { it !in draft.committed && it !in draft.discarded } ||
                        (draft.selected.isEmpty() && (draft.edits.isNotEmpty() || draft.pending != null)))) {
                    val expires = json.getLong("expires")
                    if (latest == null ||
                        expires > latest!!.first) {
                            latest = expires to draft.sources.map { (it as Attachment.Media).copy(sendImmediately = false, openEditor = false) }
                        }
                }
            }
            latest?.second
        }
    }

    internal suspend fun save(operation: String, draft: MediaSelectionDraft) = withContext(Dispatchers.IO) {
        mutex.withLock { saveLocked(operation, draft) }
    }

    /** Original encrypted draft, independent of the preview's Compose state and job. */
    internal suspend fun recordOriginalSubmission(operation: String, index: Int, fact: MediaSubmissionFact): MediaSubmissionFact =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val original = checkNotNull(loadLocked(operation)) { "Original media draft is unavailable." }
                require(index in original.sources.indices)
                val source = (original.sources[index] as Attachment.Media).localMedia
                require(fact.uploadOperationId == null || fact.uploadOperationId == source.lifecycleOperationId)
                val merged = mergeMediaSubmissionFacts(original.submissions, mapOf(index to fact))
                saveLocked(operation, original.copy(submissions = merged))
                merged.getValue(index)
            }
        }

    internal suspend fun applyOriginalSubmission(operation: String, index: Int, fact: MediaSubmissionFact,
        now: () -> Long = System::currentTimeMillis): MediaSubmissionFact = withContext(Dispatchers.IO) {
        mutex.withLock {
            val original = checkNotNull(loadLocked(operation)) { "Original media draft is unavailable." }
            require(!original.burnAfterRead && index in original.sources.indices)
            val source = (original.sources[index] as Attachment.Media).localMedia
            require(fact.uploadOperationId == source.lifecycleOperationId && fact.phase == MediaSubmissionPhase.ACCEPTED &&
                fact.transactionId != null && fact.localCleanupAtEpochMillis != null)
            check(index !in original.cancellationRequested && index !in original.discarded) { "Original cancellation is unresolved." }
            val merged = mergeMediaSubmissionFacts(original.submissions, mapOf(index to fact))
            val retained = merged.getValue(index)
            if (retained.clientAppliedAtEpochMillis != null) return@withLock retained
            // The checkpoint must be written before taking its real first-applied time.
            val checkpoint = original.copy(submissions = merged, committed = (original.committed + index).distinct(),
                inFlight = original.inFlight - index, pending = null)
            saveLocked(operation, checkpoint)
            val applied = retained.copy(clientAppliedAtEpochMillis = now())
            saveLocked(operation, checkpoint.copy(submissions = merged + (index to applied)))
            applied
        }
    }

    private suspend fun saveLocked(operation: String, draft: MediaSelectionDraft) {
        val previous = loadLocked(operation)
        val heldIndices = (previous?.submissions?.keys.orEmpty() + previous?.inFlight.orEmpty() + previous?.committed.orEmpty()).distinct()
        heldIndices.forEach { index ->
            checkNotNull(previous)
            val original = (previous.sources[index] as Attachment.Media).localMedia
            val incoming = (draft.sources.getOrNull(index) as? Attachment.Media)?.localMedia
            require(incoming != null && incoming.lifecycleOperationId == original.lifecycleOperationId &&
                incoming.lifecycleRoomIdHash == original.lifecycleRoomIdHash) { "Original submission source cannot be replaced." }
            if (previous.submissions[index]?.phase != io.element.android.libraries.matrix.api.media.MediaSubmissionPhase.PRE_SDK) {
                require(incoming == original) { "Original submission source cannot be replaced." }
            }
        }
        val merged = mergeMediaSubmissionFacts(previous?.submissions.orEmpty(), draft.submissions)
        val discarded = (previous?.discarded.orEmpty() + draft.discarded).distinct()
        val committed = (previous?.committed.orEmpty() + draft.committed).distinct()
        require(discarded.none { it in committed })
        val durable = draft.copy(submissions = merged,
            committed = committed, discarded = discarded,
            resources = draft.resources.filterKeys { it !in discarded },
            inFlight = (previous?.inFlight.orEmpty() + draft.inFlight).distinct().filterNot { it in discarded || it in committed },
            cancellationRequested = (previous?.cancellationRequested.orEmpty() + draft.cancellationRequested).distinct())
        val json = encode(durable).put("operation", operation).put("expires", System.currentTimeMillis() + 2.hours.inWholeMilliseconds)
        val bytes = json.toString().encodeToByteArray()
        require(bytes.size <= 8 * 1024 * 1024)
        val ref = try {
            files.write(SecureObjectWriteRequest(
            objectId = SecureObjectId("media-editor-draft-${UUID.randomUUID()}"),
                vaultId = vault,
            purpose = SecureObjectPurpose.EDITING_STAGING,
                associatedData = operation.toByteArray(),
                expectedPlaintextLength = bytes.size.toLong(),
        )) { it.write(bytes) }
        } finally {
            bytes.fill(0)
        }
        val old = prefs.getString(key(operation), null)?.let { decodeReference(JSONObject(it)) }
        if (!prefs.edit().putString(key(operation), encodeReference(ref).toString()).commit()) {
            files.delete(ref)
            error("Unable to persist media draft")
        }
        old?.let { files.delete(it) }
    }

    internal suspend fun clear(operation: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val original = loadLocked(operation)
            check(original?.hasUnknownMediaSubmission() != true) { "Original media submission is unresolved." }
            check(original?.cancellationRequested.orEmpty().all { it in original!!.discarded }) { "Original cancellation is unresolved." }
            check(original?.inFlight.orEmpty().none { original!!.submissions[it]?.phase != MediaSubmissionPhase.PRE_SDK }) { "Original media submission is unresolved." }
            val ref = prefs.getString(key(operation), null)?.let { decodeReference(JSONObject(it)) }
            check(prefs.edit().remove(key(operation)).commit())
            ref?.let { files.delete(it) }
        }
    }

    internal fun restoreResource(reference: SecureObjectReference, source: Attachment.Media): EditedLocalMedia {
        require(reference.vaultId == vault && reference.purpose == SecureObjectPurpose.EDITING_STAGING)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest((source.localMedia.lifecycleOperationId + ":" + source.localMedia.lifecycleRoomIdHash).toByteArray())
            .joinToString("") { "%02x".format(it) }
        require(reference.associatedDataSha256 == expected)
        val lease = leases.issue(SecureMediaLeaseRequest(
            reference,
            source.localMedia.info.mimeType,
            source.localMedia.info.filename,
            1.hours,
            revocationScope = "media-edit-${source.localMedia.lifecycleOperationId}",
            deleteObjectOnRevoke = false
        ))
        return EditedLocalMedia(
            source.localMedia.copy(uri = lease.uri),
            lease = lease,
            reference = reference,
            onClose = { sessionScope.launch { files.delete(reference) } }
        )
    }

    /** Await deletion of this exact editor object; asynchronous close is not cleanup evidence. */
    internal suspend fun retireResource(reference: SecureObjectReference, source: Attachment.Media, leaseUri: android.net.Uri? = null) {
        require(reference.vaultId == vault && reference.purpose == SecureObjectPurpose.EDITING_STAGING)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest((source.localMedia.lifecycleOperationId + ":" + source.localMedia.lifecycleRoomIdHash).toByteArray())
            .joinToString("") { "%02x".format(it) }
        require(reference.associatedDataSha256 == expected)
        leases.revokeScope("media-edit-${source.localMedia.lifecycleOperationId}")
        check(leaseUri == null || !leases.isReadAuthorized(leaseUri)) { "Media edit read access is still active." }
        check(files.delete(reference)) { "Media editor object cleanup is pending." }
    }

    private suspend fun loadLocked(operation: String): MediaSelectionDraft? {
        val raw = prefs.getString(key(operation), null) ?: return null
        val ref = decodeReference(JSONObject(raw))
        require(ref.vaultId == vault && ref.purpose == SecureObjectPurpose.EDITING_STAGING && ref.plaintextLength <= 8 * 1024 * 1024)
        require(ref.associatedDataSha256 == MessageDigest.getInstance("SHA-256").digest(operation.toByteArray()).joinToString("") { "%02x".format(it) })
        val json = files.read(ref) { input ->
            val bytes = input.readBytes()
            try {
                JSONObject(bytes.decodeToString())
            } finally {
                bytes.fill(0)
            }
        }
        val draft = decode(json)
        if (!draft.hasConsistentMediaSources()) {
            // Reject old cross-item corruption before preview or send; reclaim only resources
            // authenticated to this vault and the corresponding original media operation.
            draft.resources.forEach { (index, resource) ->
                val source = (draft.sources[index] as Attachment.Media).localMedia
                val expected = MessageDigest.getInstance("SHA-256")
                    .digest((source.lifecycleOperationId + ":" + source.lifecycleRoomIdHash).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                if (resource.vaultId == vault && resource.purpose == SecureObjectPurpose.EDITING_STAGING &&
                    resource.associatedDataSha256 == expected) {
                    files.delete(resource)
                }
            }
            prefs.edit().remove(key(operation)).commit()
            files.delete(ref)
            return null
        }
        if (json.getLong("expires") < System.currentTimeMillis() &&
            !draft.hasUnknownMediaSubmission() && draft.cancellationRequested.all { it in draft.discarded }) {
            // The encrypted store holds its operation mutex throughout the read callback.
            // Resource deletion must happen after that callback has returned.
            draft.resources.values.forEach { files.delete(it) }
            prefs.edit().remove(key(operation)).commit()
            files.delete(ref)
            return null
        }
        return if (operation in activeTransfers) draft else draft.recoverMediaSubmissions()
    }

    private fun encode(draft: MediaSelectionDraft): JSONObject {
        val parcel = Parcel.obtain()
        val serialized = try {
            parcel.writeList(draft.attachments)
            parcel.writeList(draft.sources)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        return try {
            JSONObject().put("attachments", Base64.encodeToString(serialized, Base64.NO_WRAP))
            .put("submissions", JSONObject().apply { draft.submissions.forEach { (index, fact) ->
                put(index.toString(), JSONObject().put("phase", fact.phase.name).put("transport", fact.transportName)
                    .put("event", fact.eventId).put("transaction", fact.transactionId).put("contentIds", JSONArray(fact.contentIds.toList()))
                    .put("localCleanupAt", fact.localCleanupAtEpochMillis).put("clientAppliedAt", fact.clientAppliedAtEpochMillis).put("uploadOperation", fact.uploadOperationId))
            } })
            .put("selected", JSONArray(draft.selected)).put("focused", draft.focused).put("original", draft.originalPhotos)
            .put("videoQuality", draft.videoQuality.name)
            .put("committed", JSONArray(draft.committed)).put("burnAfterRead", draft.burnAfterRead).put("inFlight", JSONArray(draft.inFlight)).put("discarded", JSONArray(draft.discarded))
            .put("cancellationRequested", JSONArray(draft.cancellationRequested))
            .put("edits", JSONObject().apply { draft.edits.forEach { (index, value) -> put(index.toString(), encodeEdits(value)) } })
            .put("pending", draft.pending?.let(::encodeEdits))
            .put("resources", JSONObject().apply { draft.resources.forEach { (index, value) -> put(index.toString(), encodeReference(value)) } })
        } finally {
            serialized.fill(0)
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun decode(json: JSONObject): MediaSelectionDraft {
        val bytes = Base64.decode(json.getString("attachments"), Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        var sources: List<Attachment> = emptyList()
        val attachments = try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val result = parcel.readArrayList(Attachment::class.java.classLoader) as List<Attachment>
            sources = if (parcel.dataAvail() > 0) parcel.readArrayList(Attachment::class.java.classLoader) as List<Attachment> else result
            result
        } finally {
            parcel.recycle()
            bytes.fill(0)
        }
        require(attachments.isNotEmpty() && attachments.size <= 256 && sources.size == attachments.size)
        val selected = json.getJSONArray("selected").let { array -> List(array.length()) { array.getInt(it) } }
        require(selected.distinct().size == selected.size && selected.all { it in attachments.indices })
        val focused = json.getInt("focused").also { require(it in attachments.indices) }
        val edits = json.getJSONObject("edits").let { obj -> obj.keys().asSequence().associate { it.toInt() to decodeEdits(obj.getJSONObject(it)) } }
        val resources = json.getJSONObject(
            "resources"
        ).let { obj -> obj.keys().asSequence().associate { it.toInt() to decodeReference(obj.getJSONObject(it)) } }
        val committed = json.optJSONArray("committed")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
        val inFlight = json.optJSONArray("inFlight")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
        val submissions = json.optJSONObject("submissions")?.let { obj -> obj.keys().asSequence().associate { key ->
            val fact = obj.getJSONObject(key)
            key.toInt() to MediaSubmissionFact(MediaSubmissionPhase.valueOf(fact.getString("phase")),
                fact.optString("transport").takeIf { it.isNotEmpty() }, fact.optString("event").takeIf { it.isNotEmpty() },
                fact.optString("transaction").takeIf { it.isNotEmpty() },
                fact.optJSONArray("contentIds")?.let { ids -> List(ids.length()) { ids.getString(it) }.toSet() }.orEmpty(),
                fact.optLong("localCleanupAt").takeIf { it > 0 }, fact.optLong("clientAppliedAt").takeIf { it > 0 },
                fact.optString("uploadOperation").takeIf { it.isNotEmpty() })
        } }.orEmpty()
        require(submissions.keys.all { it in attachments.indices })
        val discarded = json.optJSONArray("discarded")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
        require(discarded.all { it in attachments.indices } && discarded.none { it in committed || it in inFlight })
        require(committed.all { it in attachments.indices } && inFlight.all { it in attachments.indices })
        require(edits.keys.all { it in attachments.indices } && resources.keys.all { it in attachments.indices })
        require(selected.size <= 9)
        val cancellationRequested = json.optJSONArray("cancellationRequested")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty()
        require(cancellationRequested.all { it in attachments.indices })
        return MediaSelectionDraft(
            attachments,
            selected,
            focused,
            edits,
            json.optJSONObject("pending")?.let(::decodeEdits),
            json.getBoolean("original"),
            VideoCompressionPreset.valueOf(json.getString("videoQuality")),
            resources,
            sources,
            committed,
            json.optBoolean("burnAfterRead"),
            inFlight,
            submissions,
            discarded,
            cancellationRequested
        )
    }

    private fun encodeEdits(edits: AttachmentImageEdits): JSONObject = JSONObject()
        .put("crop", JSONArray(listOf(edits.cropRect.left, edits.cropRect.top, edits.cropRect.right, edits.cropRect.bottom)))
        .put("rotation", edits.rotationQuarterTurns).put("horizontal", edits.isFlippedHorizontally).put("vertical", edits.isFlippedVertically)
        .put("annotations", JSONArray().apply {
            edits.annotations.forEach { a ->
                put(
                    when (a) {
            is ImageAnnotation.Stroke -> JSONObject().put("type", "stroke").put("points", encodePoints(a.points)).put("color", a.color).put("width", a.width)
            is ImageAnnotation.Mosaic -> JSONObject().put("type", "mosaic").put("points", encodePoints(a.points)).put("width", a.width)
            is ImageAnnotation.Label -> JSONObject().put(
                "type",
                "text"
            ).put("points", encodePoints(listOf(a.point))).put("text", a.text).put("color", a.color).put("size", a.size)
        }
                )
            }
        })

    private fun encodePoints(points: List<ImagePoint>) = JSONArray().apply { points.forEach { put(JSONArray(listOf(it.x, it.y))) } }
    private fun decodeEdits(json: JSONObject): AttachmentImageEdits {
        val crop = json.getJSONArray("crop")
        val annotations = json.getJSONArray("annotations")
        require(annotations.length() <= 256)
        return AttachmentImageEdits(
            NormalizedCropRect(crop.getDouble(0).toFloat(), crop.getDouble(1).toFloat(), crop.getDouble(2).toFloat(), crop.getDouble(3).toFloat()),
            json.getInt("rotation"),
            json.getBoolean("horizontal"),
            json.getBoolean("vertical"),
            List(annotations.length()) { index ->
                val a = annotations.getJSONObject(index)
                val points = a.getJSONArray("points")
                require(points.length() in 1..16384)
                val decoded = List(points.length()) { i -> points.getJSONArray(i).let { ImagePoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
                when (a.getString("type")) {
                    "stroke" -> ImageAnnotation.Stroke(decoded, a.getInt("color"), a.getDouble("width").toFloat())
                    "mosaic" -> ImageAnnotation.Mosaic(decoded, a.getDouble("width").toFloat())
                    "text" -> ImageAnnotation.Label(decoded.first(), a.getString("text"), a.getInt("color"), a.getDouble("size").toFloat())
                    else -> error("Unsupported media annotation")
                }
            }
        )
    }

    private fun encodeReference(r: SecureObjectReference): JSONObject = JSONObject().put("object", r.objectId.value).put("vault", r.vaultId.value)
        .put("purpose", r.purpose.formatCode).put("key", r.keyEnvelopeId.value).put("version", r.formatVersion)
        .put("length", r.plaintextLength).put("cipherLength", r.ciphertextLength).put("aad", r.associatedDataSha256)
    private fun decodeReference(j: JSONObject) = SecureObjectReference(
        SecureObjectId(j.getString("object")),
        AccountVaultId(j.getString("vault")),
        SecureObjectPurpose.fromFormatCode(j.getInt("purpose")) ?: error("Invalid media draft purpose"),
        KeyEnvelopeId(j.getString("key")),
        j.getInt("version"),
        j.getLong("length"),
        j.getLong("cipherLength"),
        j.getString("aad")
    )
}

/** Resolve the nullable presenter binding explicitly; direct presenter tests may omit persistence. */
@BindingContainer
@ContributesTo(SessionScope::class)
object MediaSelectionDraftBindings {
    @Provides
    fun providesMediaSelectionDraftStore(store: MediaSelectionDraftStore): MediaSelectionDraftStore? = store
}

internal fun MediaSelectionDraft.hasConsistentMediaSources(): Boolean {
    val originals = sources.map { (it as Attachment.Media).localMedia }
    if (originals.map { it.lifecycleOperationId }.distinct().size != originals.size) return false
    return attachments.zip(originals).all { (attachment, source) ->
        val media = (attachment as Attachment.Media).localMedia
        media.lifecycleOperationId == source.lifecycleOperationId && media.lifecycleRoomIdHash == source.lifecycleRoomIdHash &&
            media.info.mimeType.isMimeTypeImage() == source.info.mimeType.isMimeTypeImage() &&
            media.info.mimeType.isMimeTypeVideo() == source.info.mimeType.isMimeTypeVideo()
    }
}
