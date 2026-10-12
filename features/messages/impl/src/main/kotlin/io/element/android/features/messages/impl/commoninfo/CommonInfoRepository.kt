/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.security.contentprotection.api.AccountPrivateResourceClient
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.PrivateResourceRecord
import io.element.android.security.contentprotection.api.PrivateResourceScope
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class CommonInfoKind { ADDRESS, PHONE, IDENTITY, TEXT }

data class CommonInfoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val kind: CommonInfoKind = CommonInfoKind.TEXT,
    val content: String = "",
    val recipient: String = "",
    val phone: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val bodyRevision: Long = 0,
    val pinnedAt: Long = 0,
    val uses: Map<String, Long> = emptyMap(),
    val summary: String = "",
) {
    fun sendText(): String = when (kind) {
        CommonInfoKind.ADDRESS -> listOf("$recipient $phone".trim(), content).filter(String::isNotBlank).joinToString("\n")
        else -> content
    }

    fun maskedSummary(): String = when (kind) {
        CommonInfoKind.IDENTITY -> "•••• •••• •••• ••••"
        CommonInfoKind.PHONE -> content.filter(Char::isDigit).let { if (it.length > 7) it.take(3) + " **** " + it.takeLast(4) else "••••" }
        CommonInfoKind.ADDRESS -> content.take(18)
        CommonInfoKind.TEXT -> content.lineSequence().firstOrNull().orEmpty().take(24)
    }
}

internal fun orderCommonInfo(items: List<CommonInfoItem>, now: Long): List<CommonInfoItem> {
    val heat = items.associate { it.id to commonInfoHeat(it, now) }
    return items.sortedWith(compareByDescending<CommonInfoItem> { it.pinnedAt > 0 }
        .thenByDescending { it.pinnedAt }.thenByDescending { heat.getValue(it.id).score }
        .thenByDescending { heat.getValue(it.id).lastUsedAt }.thenByDescending { it.createdAt }.thenBy { it.id })
}

/** The list/index contains masked summaries. Body keys are released only for edit or an explicit title tap. */
@Inject
@SingleIn(SessionScope::class)
class CommonInfoRepository(
    private val matrix: MatrixClient,
    private val resources: AccountPrivateResourceClient,
    private val protectionRuntime: MatrixContentProtectionRuntime,
    @SessionCoroutineScope private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Intent(val id: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Intent>
    }
    internal data class Pending(val operationId: String, val action: String, val item: CommonInfoItem, val failed: Boolean = false)
    internal val pending = MutableStateFlow<List<Pending>>(emptyList())
    internal val display = MutableStateFlow<DisplayList?>(null)
    private var displayGeneration = -1L
    private val bodyCache = LinkedHashMap<String, PrivateResourceRecord>()
    private val queueLock = Mutex()
    private val indexLoad = Mutex()
    private var worker: Job? = null
    private var displayExpiry: Job? = null
    internal fun clearViewCache() { displayExpiry?.cancel(); display.value = null }
    private fun publishDisplay(value: DisplayList, generation: Long) {
        check(generation == resources.authorizationRevision.value) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
        displayGeneration = generation
        display.value = value
        displayExpiry?.cancel()
        displayExpiry = sessionScope.launch {
            while (display.value?.record == value.record) {
                val remaining = remaining(value.record)
                if (remaining <= 0) { display.value = null; break }
                delay(remaining)
            }
        }
    }
    init {
        sessionScope.launch {
            resources.authorizationRevision.drop(1).collect {
                worker?.cancel()
                clearViewCache()
                pending.value = emptyList()
                synchronized(bodyCache) { bodyCache.clear() }
            }
        }
    }
    private suspend fun writeResource(id: String, kind: String, revision: Long, bytes: ByteArray,
        trigger: String = if (revision == 0L) "private_create" else "private_edit"): PrivateResourceRecord {
        val intent = currentCoroutineContext()[Intent]
        val operationId = if (intent == null) UUID.randomUUID().toString()
        else UUID.nameUUIDFromBytes("${intent.id}:$id:$revision".encodeToByteArray()).toString()
        return resources.writeWithLifecycleOperation(scope, id, kind, revision, bytes, operationId, trigger)
    }
    internal suspend fun enqueue(action: String, item: CommonInfoItem = CommonInfoItem()) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        require(action in setOf("save", "pin", "delete", "reset", "use"))
        if (action == "save") require(item.title.trim().length in 1..64 && item.content.trim().length in 1..20_000)
        queueLock.withLock {
            val queue = readMetadata("common-outbox") ?: JSONObject()
            check(queue.length() < 32 && queue.keys().asSequence().none {
                val queued = queue.getJSONObject(it)
                queued.getString("action") != "use" && queued.getJSONObject("item").getString("id") == item.id
            }) {
                "PRIVATE_RESOURCE_BUSY"
            }
            val id = UUID.randomUUID().toString()
            val desired = when (action) {
                "pin" -> item.copy(pinnedAt = if (item.pinnedAt > 0) 0 else System.currentTimeMillis())
                // Successful composer insertion has its own durable intent, never a fabricated Matrix send receipt.
                "use" -> item.copy(content = "", recipient = "", phone = "",
                    uses = mapOf(sha256("fill:$id") to System.currentTimeMillis()))
                else -> item
            }
            val sequence = (queue.keys().asSequence().map { queue.getJSONObject(it).optLong("sequence") }.maxOrNull() ?: 0) + 1
            queue.put(id, JSONObject().put("sequence", sequence).put("action", action).put("item", encode(desired, true)))
            check(queue.toString().encodeToByteArray().size <= 400_000) { "PRIVATE_RESOURCE_BUSY" }
            // The original local intent and user fields are encrypted by SensitiveFileStore before any network mutation.
            persistMetadata("common-outbox", queue)
            publishQueue(queue)
            LifecycleStageTiming.interval("common_local_saved", started, System.nanoTime(), "", action, id)
        }
        startSync()
    }
    private fun publishQueue(queue: JSONObject, failedId: String? = null) {
        pending.value = queue.keys().asSequence().sortedBy { queue.getJSONObject(it).optLong("sequence") }.map { id ->
            val job = queue.getJSONObject(id)
            val full = decode(job.getJSONObject("item"))
            Pending(id, job.getString("action"), full.copy(summary = full.maskedSummary(), content = "", recipient = "", phone = ""), id == failedId)
        }.toList()
    }
    internal fun projected(entries: List<CommonInfoItem>, now: Long = System.currentTimeMillis()): List<CommonInfoItem> {
        var result = entries
        pending.value.forEach { job ->
            result = when (job.action) {
                "save" -> result.filterNot { it.id == job.item.id } + job.item
                "pin" -> result.map { if (it.id == job.item.id) it.copy(pinnedAt = job.item.pinnedAt) else it }
                "reset" -> result.map { it.copy(pinnedAt = 0) }
                "use" -> result.map { if (it.id == job.item.id) it.copy(uses = it.uses + job.item.uses) else it }
                else -> result // A deletion remains disabled/pending until the real destruction completes.
            }
        }
        return orderCommonInfo(result, now)
    }
    internal fun startSync() {
        synchronized(queueLock) {
            if (worker?.isActive == true) return
            val generation = resources.authorizationRevision.value
            var paused = false
            fun pauseDeniedRead() {
                paused = true
                if (generation == resources.authorizationRevision.value) {
                    pending.value = emptyList()
                    clearViewCache()
                    synchronized(bodyCache) { bodyCache.clear() }
                }
                LifecycleStageTiming.observed("common_sync_paused")
            }
            val running = sessionScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val retries = mutableMapOf<String, Int>()
                while (generation == resources.authorizationRevision.value) {
                    var selectedId: String? = null
                    try {
                        val selected = queueLock.withLock {
                            val queue = readMetadata("common-outbox") ?: JSONObject()
                            check(generation == resources.authorizationRevision.value) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                            publishQueue(queue)
                            queue.keys().asSequence().minByOrNull { queue.getJSONObject(it).optLong("sequence") }?.let { it to queue.getJSONObject(it) }
                        } ?: break
                        val (id, job) = selected
                        selectedId = id
                        LifecycleStageTiming.point("common_cloud_start", operationId = id)
                        recoverIndex()
                        withContext(Intent(id)) {
                            val item = decode(job.getJSONObject("item"))
                            when (job.getString("action")) {
                                "save" -> {
                                    val current = loadForDisplay().items.firstOrNull { it.id == item.id }
                                    if (current != null && current.bodyRevision > item.bodyRevision) {
                                        val existing = edit(current)
                                        check(existing.title.trim() == item.title.trim() && existing.sendText() == item.sendText()) { "PRIVATE_RESOURCE_CONFLICT" }
                                    } else save(item)
                                }
                                "pin" -> setPin(item.id, item.pinnedAt)
                                "reset" -> resetOrder()
                                "delete" -> delete(item)
                                "use" -> recordInsertionUse(item)
                            }
                        }
                        check(generation == resources.authorizationRevision.value)
                        queueLock.withLock {
                            val queue = readMetadata("common-outbox") ?: JSONObject()
                            queue.remove(id);persistMetadata("common-outbox", queue.takeIf { it.length() > 0 })
                            check(generation == resources.authorizationRevision.value) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                            publishQueue(queue)
                        }
                        LifecycleStageTiming.point("common_cloud_applied", operationId = id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val id = selectedId
                        // Vault/policy checks also guard encrypted local metadata. A denial must stop
                        // this worker without an uncaught child exception or a plaintext reread loop.
                        if (generation != resources.authorizationRevision.value || id == null) {
                            pauseDeniedRead()
                            break
                        }
                        try {
                            queueLock.withLock {
                                val queue = readMetadata("common-outbox") ?: JSONObject()
                                check(generation == resources.authorizationRevision.value) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                                publishQueue(queue, id)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            pauseDeniedRead()
                            break
                        }
                        val attempts = retries.getOrDefault(id, 0)
                        if ((failure is java.io.IOException || failure is ContentProtectionException.KmsUnavailable) && attempts < 3) {
                            retries[id] = attempts + 1
                            delay(2_000L shl attempts)
                            continue
                        }
                        break
                    }
                }
            }
            worker = running
            running.invokeOnCompletion { cause ->
                synchronized(queueLock) { if (worker == running) worker = null }
                if (!paused && cause == null && sessionScope.isActive && generation == resources.authorizationRevision.value &&
                    pending.value.isNotEmpty() && pending.value.none { it.failed }) startSync()
            }
            running.start()
        }
    }

    private val mutation = Mutex()
    private var identity: String? = null
    private var identityRevision: Long = -1
    private var usageJournal: JSONObject? = null
    val authorizationRevision: StateFlow<Long> = resources.authorizationRevision
    internal fun canImportSource(source: MessageClipboardSource): Boolean = when (source) {
        MessageClipboardSource.Unprotected -> true
        is MessageClipboardSource.Protected ->
            source.sessionId == matrix.sessionId.value &&
                source.authorizationRevision == protectionRuntime.authorizationRevision() &&
                source.expiresAtEpochMillis > System.currentTimeMillis()
    }
    private val scope: PrivateResourceScope get() = PrivateResourceScope(accountIdHash(matrix.sessionId.value),
        accountIdHash(matrix.deviceId.value), deriveAccountVaultId(matrix.sessionId.value, matrix.deviceId.value))

    private suspend fun indexId(): String {
        if (identity == null || identityRevision != resources.authorizationRevision.value) {
            identity = resources.sync(scope, 0).identityHash
            identityRevision = resources.authorizationRevision.value
        }
        return sha256("common-index-v1:${scope.accountIdHash}:$identity")
    }

    internal data class DisplayList(val items: List<CommonInfoItem>, val record: PrivateResourceRecord)
    internal data class DisplayItem(val item: CommonInfoItem, val record: PrivateResourceRecord)
    internal val leaseRevision: StateFlow<Long> get() = resources.revision
    internal fun remaining(record: PrivateResourceRecord): Long = resources.readAuthorizationRemainingMillis(scope, record)
    internal suspend fun renew(record: PrivateResourceRecord): Long = resources.withReadOperation(scope) {
        val current = resources.open(scope, record.resourceId)
        check(!current.deleted && current.revision == record.revision && current.versionId == record.versionId) { "PRIVATE_RESOURCE_CONFLICT" }
        resources.renewReadAuthorization(scope, current)
    }
    suspend fun load(): List<CommonInfoItem> = loadForDisplay().items
    internal suspend fun loadForDisplay(): DisplayList {
        display.value?.takeIf { displayGeneration == resources.authorizationRevision.value && remaining(it.record) > 0 }?.let { return it }
        recoverIndex()
        return indexSnapshot()
    }
    private suspend fun indexSnapshot(): DisplayList = indexLoad.withLock {
        display.value?.takeIf { displayGeneration == resources.authorizationRevision.value && remaining(it.record) > 0 }?.let { return@withLock it }
        val generation = resources.authorizationRevision.value
        resources.withReadOperation(scope) {
            val record = resources.open(scope, indexId())
            DisplayList(orderCommonInfo(readIndex(record), System.currentTimeMillis()), record)
        }.also { publishDisplay(it, generation) }
    }

    private suspend fun readIndex(record: PrivateResourceRecord): List<CommonInfoItem> {
        if (record.revision == 0L || record.deleted) return emptyList()
        val bytes = resources.read(scope, record)
        return try {
            val json = JSONObject(bytes.decodeToString())
            check(json.getInt("format") == 1)
            val entries = json.getJSONArray("items")
            check(entries.length() <= 100)
            (0 until entries.length()).map { decode(entries.getJSONObject(it)) }
        } finally { bytes.fill(0) }
    }

    suspend fun edit(item: CommonInfoItem): CommonInfoItem = editForDisplay(item).item
    internal suspend fun editForDisplay(item: CommonInfoItem): DisplayItem {
        suspend fun project(record: PrivateResourceRecord): DisplayItem {
            val generation = resources.authorizationRevision.value
            check(!record.deleted && record.revision == item.bodyRevision) { "PRIVATE_RESOURCE_CONFLICT" }
            val bytes = resources.read(scope, record)
            return try {
                synchronized(bodyCache) { bodyCache[item.id] = record; while (bodyCache.size > 8) bodyCache.remove(bodyCache.keys.first()) }
                val value = DisplayItem(decode(JSONObject(bytes.decodeToString())).copy(bodyRevision = record.revision, pinnedAt = item.pinnedAt, uses = item.uses), record)
                check(generation == resources.authorizationRevision.value) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                value
            } finally { bytes.fill(0) }
        }
        val cached = synchronized(bodyCache) { bodyCache[item.id]?.takeIf { it.revision == item.bodyRevision && remaining(it) > 0 } }
        // An exact, still-authorized cache read has no new external effect. The client gates both sides of decryption;
        // if the grant expires during this call its normal key-release operation owns the online renewal.
        return if (cached != null) project(cached)
        else resources.withReadOperation(scope) { project(resources.open(scope, item.id)) }
    }

    suspend fun sendText(item: CommonInfoItem): String = edit(item).sendText()

    suspend fun save(item: CommonInfoItem): List<CommonInfoItem> = mutation.withLock {
        require(item.title.trim().length in 1..64 && item.content.trim().length in 1..20_000)
        val existing = indexSnapshot().items
        check(existing.any { it.id == item.id } || existing.size < 100) { "PRIVATE_RESOURCE_LIMIT" }
        val intent = JSONObject().put("id", item.id).put("expectedRevision", item.bodyRevision)
        // This index-repair journal holds opaque pointers. Explicit saves additionally use the encrypted local outbox.
        addIndexIntent(intent)
        val bytes = encode(item, full = true).toString().encodeToByteArray()
        val record = try { writeResource(item.id, "COMMON_BODY", item.bodyRevision, bytes) } finally { bytes.fill(0) }
        check(record.revision > item.bodyRevision)
        synchronized(bodyCache) { bodyCache[item.id] = record }
        // The committed body is already known; do not fetch and decrypt our own just-written ciphertext.
        val updated = updateIndex(if (item.bodyRevision == 0L) "private_create" else "private_edit") { current ->
            val previous = current.firstOrNull { it.id == item.id }
            current.filterNot { it.id == item.id } + item.copy(title = item.title.trim(), bodyRevision = record.revision,
                summary = item.maskedSummary(), content = "", recipient = "", phone = "",
                pinnedAt = previous?.pinnedAt ?: item.pinnedAt, uses = previous?.uses ?: item.uses)
        }
        removeIndexIntent(item.id)
        updated
    }

    private suspend fun applyIndexIntent(intent: JSONObject, strictDelete: Boolean = false): List<CommonInfoItem> {
        val id = intent.getString("id")
        resources.resumePending(scope, id)
        var record = resources.fetch(scope, id)
        if (intent.optString("action") == "delete" && !record.deleted) {
            if (record.revision != intent.getLong("expectedRevision")) {
                check(!strictDelete) { "PRIVATE_RESOURCE_CONFLICT" }
                return readIndex(resources.fetch(scope, indexId()))
            }
            record = resources.delete(scope, record)
        }
        check(!record.deletionPending) { "PRIVATE_RESOURCE_DELETION_PENDING" }
        if (record.deleted) return updateIndex { it.filterNot { candidate -> candidate.id == id } }
        if (record.revision <= intent.getLong("expectedRevision")) return readIndex(resources.fetch(scope, indexId()))
        val bytes = resources.read(scope, record)
        val item = try { decode(JSONObject(bytes.decodeToString())).copy(bodyRevision = record.revision) } finally { bytes.fill(0) }
        return updateIndex(if (intent.getLong("expectedRevision") == 0L) "private_create" else "private_edit") { current ->
            val previous = current.firstOrNull { it.id == item.id }
            check(previous == null || previous.bodyRevision <= item.bodyRevision) { "PRIVATE_RESOURCE_CONFLICT" }
            val updated = item.copy(title = item.title.trim(), summary = item.maskedSummary(), pinnedAt = previous?.pinnedAt ?: item.pinnedAt, uses = previous?.uses ?: item.uses)
            current.filterNot { it.id == item.id } + updated.copy(content = "", recipient = "", phone = "")
        }
    }

    private suspend fun recoverIndex() = mutation.withLock {
        val journal = readIndexIntents()
        for (id in journal.keys().asSequence().toList()) {
            try {
                applyIndexIntent(journal.getJSONObject(id))
                removeIndexIntent(id)
            } catch (failure: IllegalStateException) {
                // A full index must remain manageable so the user can delete an entry and free capacity.
                if (failure.message != "PRIVATE_RESOURCE_LIMIT") throw failure
            }
        }
    }

    private suspend fun readIndexIntents(): JSONObject {
        val value = readMetadata("common-index-pending") ?: return JSONObject()
        return if (value.has("id")) JSONObject().put(value.getString("id"), value) else value
    }

    private suspend fun addIndexIntent(intent: JSONObject) {
        val journal = readIndexIntents()
        val id = intent.getString("id")
        check(journal.has(id) || journal.length() < 100) { "PRIVATE_RESOURCE_BUSY" }
        journal.put(id, intent)
        persistMetadata("common-index-pending", journal)
    }

    private suspend fun removeIndexIntent(id: String) {
        val journal = readIndexIntents()
        journal.remove(id)
        persistMetadata("common-index-pending", journal.takeIf { it.length() > 0 })
    }

    /** Save the transaction association before handing it to the Matrix send queue; process death can resume it. */
    suspend fun stageSend(itemId: String, roomId: String, transactionId: String) = mutation.withLock {
        val journal = usageJournal ?: (readMetadata("usage-pending") ?: JSONObject()).also { usageJournal = it }
        check(journal.length() < 256) { "PRIVATE_RESOURCE_BUSY" }
        val contentId = UUID.nameUUIDFromBytes("$roomId\u0000$transactionId".encodeToByteArray()).toString()
        journal.put(contentId, JSONObject().put("item", itemId).put("room", roomId))
        persistMetadata("usage-pending", journal)
    }

    suspend fun confirmSend(contentId: String, roomId: String, eventId: String, timestamp: Long) = mutation.withLock {
        val journal = usageJournal ?: (readMetadata("usage-pending") ?: JSONObject()).also { usageJournal = it }
        val intent = journal.optJSONObject(contentId) ?: return@withLock
        if (intent.getString("room") != roomId) return@withLock
        val key = sha256("$roomId\u0000$eventId")
        updateIndex("private_usage_commit") { current -> current.map { item ->
            if (item.id != intent.getString("item") || key in item.uses) item else item.copy(uses =
                item.uses.filterValues { it >= timestamp - 30L * 24 * 60 * 60 * 1000 } + (key to timestamp))
        } }
        journal.remove(contentId)
        persistMetadata("usage-pending", journal.takeIf { it.length() > 0 })
    }

    private suspend fun readMetadata(name: String): JSONObject? = resources.readLocalMetadata(scope, name)?.let { bytes ->
        try { JSONObject(bytes.decodeToString()) } finally { bytes.fill(0) }
    }

    private suspend fun recordInsertionUse(usage: CommonInfoItem): List<CommonInfoItem> = mutation.withLock {
        val cutoff = System.currentTimeMillis() - 30 * COMMON_USAGE_DAY_MS
        updateIndex("private_usage_commit") { current -> current.map { item ->
            if (item.id == usage.id) item.copy(uses = item.uses.filterValues { it >= cutoff } + usage.uses) else item
        } }
    }

    private suspend fun persistMetadata(name: String, value: JSONObject?) {
        val bytes = value?.toString()?.encodeToByteArray()
        try { resources.writeLocalMetadata(scope, name, bytes) } finally { bytes?.fill(0) }
    }

    suspend fun pin(item: CommonInfoItem): List<CommonInfoItem> = mutation.withLock {
        val pinnedAt = if (item.pinnedAt > 0) 0 else System.currentTimeMillis()
        updateIndex("private_pin") { current -> current.map { if (it.id == item.id) it.copy(pinnedAt = pinnedAt) else it } }
    }

    private suspend fun setPin(id: String, value: Long): List<CommonInfoItem> = mutation.withLock {
        updateIndex("private_pin") { current ->
            check(current.any { it.id == id }) { "PRIVATE_RESOURCE_CONFLICT" }
            current.map { if (it.id == id) it.copy(pinnedAt = value) else it }
        }
    }

    suspend fun resetOrder(): List<CommonInfoItem> = mutation.withLock {
        updateIndex("private_reset_order") { current -> current.map { it.copy(pinnedAt = 0) } }
    }

    suspend fun delete(item: CommonInfoItem): List<CommonInfoItem> = mutation.withLock {
        val record = resources.fetch(scope, item.id)
        if (record.deleted && !record.deletionPending && indexSnapshot().items.none { it.id == item.id }) return@withLock indexSnapshot().items
        check(record.deleted || record.revision == item.bodyRevision) { "PRIVATE_RESOURCE_CONFLICT" }
        val intent = JSONObject().put("id", item.id).put("expectedRevision", item.bodyRevision).put("action", "delete")
        addIndexIntent(intent)
        val updated = applyIndexIntent(intent, strictDelete = true)
        removeIndexIntent(item.id)
        updated
    }

    private suspend fun updateIndex(trigger: String = "private_edit", change: (List<CommonInfoItem>) -> List<CommonInfoItem>): List<CommonInfoItem> {
        val generation = resources.authorizationRevision.value
        val id = indexId()
        repeat(3) { attempt ->
            val cached = display.value?.takeIf { displayGeneration == resources.authorizationRevision.value && remaining(it.record) > 0 }
            val head = cached?.record ?: resources.fetch(scope, id)
            check(!head.deleted) { "PRIVATE_RESOURCE_DELETED" }
            val updated = change(cached?.items ?: readIndex(head))
            check(updated.size <= 100) { "PRIVATE_RESOURCE_LIMIT" }
            val bytes = JSONObject().put("format", 1).put("items", JSONArray(updated.map { encode(it, false) })).toString().encodeToByteArray()
            try {
                val committed = writeResource(id, "COMMON_INDEX", head.revision, bytes, trigger)
                val ordered = orderCommonInfo(updated, System.currentTimeMillis())
                publishDisplay(DisplayList(ordered, committed), generation)
                return ordered
            } catch (failure: IllegalStateException) {
                if (failure.message != "PRIVATE_RESOURCE_CONFLICT" || attempt == 2) throw failure
                display.value = null
            } finally { bytes.fill(0) }
        }
        error("PRIVATE_RESOURCE_CONFLICT")
    }

    private fun encode(item: CommonInfoItem, full: Boolean): JSONObject = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("kind", item.kind.name); put("created", item.createdAt)
        put("bodyRevision", item.bodyRevision); put("pin", item.pinnedAt); put("uses", JSONObject(item.uses)); put("summary", item.summary)
        if (full) { put("content", item.content); put("recipient", item.recipient); put("phone", item.phone) }
    }

    private fun decode(value: JSONObject): CommonInfoItem = CommonInfoItem(
        id = value.getString("id"), title = value.getString("title"), kind = CommonInfoKind.valueOf(value.getString("kind")),
        content = value.optString("content"), recipient = value.optString("recipient"), phone = value.optString("phone"),
        createdAt = value.getLong("created"), bodyRevision = value.optLong("bodyRevision"), pinnedAt = value.optLong("pin"),
        uses = value.optJSONObject("uses")?.let { events -> events.keys().asSequence().associateWith { events.getLong(it) } }.orEmpty(),
        summary = value.optString("summary"),
    )

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
