/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import android.content.Context
import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.contentprotection.api.*
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** App-owned key cache; separate, bounded concurrency cannot drain the message release queue. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultAccountPrivateResourceClient(
    private val kms: KmsShareClient,
    private val deviceKeys: DeviceShareKeyService,
    private val vault: VaultKeyService,
    private val trust: DeviceTrustService,
    private val policies: DataProtectionPolicyProvider,
    private val coordinator: AppOperationalStateCoordinator,
    private val runtime: MatrixContentProtectionRuntime,
    private val files: SensitiveFileStore,
    @ApplicationContext context: Context,
) : AccountPrivateResourceClient {
    private val preferences = context.getSharedPreferences("account_private_resource_refs_v1", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val permits = Semaphore(2)
    private data class LockEntry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private data class Cached(val wrapped: VaultWrappedKey, val binding: ByteArray, val deadline: Long, val generation: Long)
    private val locks = mutableMapOf<String, LockEntry>()
    private val cache = LinkedHashMap<String, Cached>()
    private val observed = LinkedHashMap<String, PrivateResourceRecord>()
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null
    private val mutableAuthorizationRevision = MutableStateFlow(0L)
    private val mutableRevision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = mutableRevision
    override val authorizationRevision: StateFlow<Long> = mutableAuthorizationRevision

    init {
        runtime.registerSensitiveCacheClearer(::invalidate)
        runtime.registerAuthorizationRefreshListener { if (it.mode == MatrixAuthorizationRefreshMode.HARD_INVALIDATION) invalidate() }
        runtime.registerContentInvalidationListener { invalidation ->
            if (invalidation.contentId.value.startsWith("private-resource-v1:")) invalidate()
        }
    }

    override fun invalidate() {
        synchronized(cache) {
            cache.values.forEach { it.wrapped.ciphertext.fill(0); it.binding.fill(0) }
            cache.clear()
            expiryJob?.cancel()
            mutableAuthorizationRevision.update { it + 1 }
            mutableRevision.update { it + 1 }
        }
    }

    private suspend fun gate(scope: PrivateResourceScope, generation: Long): String {
        check(mutableAuthorizationRevision.value == generation && runtime.isConnected()) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
        check(vault.isUnlocked(scope.vaultId)) { "PRIVATE_RESOURCE_LOCKED" }
        val policy = policies.current()
        check(policy.source == DataProtectionPolicySource.VERIFIED_REMOTE) { "PRIVATE_RESOURCE_POLICY_UNAVAILABLE" }
        val now = System.currentTimeMillis()
        check(policy.policy.effectiveAtEpochMillis <= now && (policy.policy.expiresAtEpochMillis == null || checkNotNull(policy.policy.expiresAtEpochMillis) > now)) {
            "PRIVATE_RESOURCE_POLICY_UNAVAILABLE"
        }
        val device = trust.current()
        check(device.allowsLocalVaultAccess && device.riskSignals.none { it.blocksLocalVaultAccess }) { "PRIVATE_RESOURCE_DEVICE_RESTRICTED" }
        check(device.remotelyTrusted || device.managedLicense?.let {
            it.isActive(System.currentTimeMillis()) && it.allowsAccount(scope.accountIdHash) && "PROTECTED_PROFILE" in it.allowedScopes
        } == true) { "PRIVATE_RESOURCE_DEVICE_RESTRICTED" }
        check(mutableAuthorizationRevision.value == generation) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
        val descriptor = deviceKeys.descriptor()
        check(descriptor.installationId == device.installationId) { "PRIVATE_RESOURCE_DEVICE_RESTRICTED" }
        return "${policy.policy.policyId}:${policy.policy.version}:${descriptor.keyId}:${device.installationId}:${device.attestationVersion}:${device.trustState}:${device.managedLicense?.revision}"
    }

    private suspend fun <T> locked(scope: PrivateResourceScope, id: String, action: suspend () -> T): T {
        val key = "${scope.vaultId.value}:$id"
        val entry = synchronized(locks) {
            check(locks.size < 64 || key in locks) { "PRIVATE_RESOURCE_BUSY" }
            locks.getOrPut(key) { LockEntry() }.also { it.users++ }
        }
        return try { entry.mutex.withLock { action() } } finally {
            synchronized(locks) { if (--entry.users == 0) locks.remove(key) }
        }
    }

    private suspend fun base(scope: PrivateResourceScope, id: String, operation: String): JsonObject {
        val device = deviceKeys.descriptor()
        return buildJsonObject {
            put("protocol_version", 1); put("account_id_hash", scope.accountIdHash)
            put("requesting_installation_id", device.installationId); put("requesting_key_id", device.keyId)
            put("request_nonce_b64", ByteArray(24).also(random::nextBytes).b64())
            put("resource_id", id); put("operation_id", operation)
        }
    }

    private suspend fun rpc(action: String, body: JsonObject): JsonObject = permits.withPermit {
        withContext(Dispatchers.IO) { Json.parseToJsonElement(kms.requestPrivateResource(action, body.toString())).jsonObject }
    }

    private class ReadOperation(val scope: PrivateResourceScope) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ReadOperation>
    }
    override suspend fun <T> withReadOperation(scope: PrivateResourceScope, block: suspend () -> T): T =
        operation(scope, "private_open") { withContext(ReadOperation(scope)) { block() } }

    private suspend fun <T> operation(scope: PrivateResourceScope, trigger: String, id: String = UUID.randomUUID().toString(), action: suspend (String) -> T): T {
        val readOwner = currentCoroutineContext()[ReadOperation]
        val parent = currentLifecycleOperationContext()
        if (readOwner?.scope == scope && parent?.lifecycleId == FullAppLifecycleId.LC_25 && trigger in setOf("private_open", "private_sync")) {
            return action(parent.operationId)
        }
        return coordinator.performPrivateWithDeferredReceipts(LifecycleOperationRequest(
            operationId = id, lifecycleId = FullAppLifecycleId.LC_25, trigger = trigger, idempotencyKey = id,
            accountIdHash = scope.accountIdHash, matrixDeviceIdHash = scope.matrixDeviceIdHash,
            deadlineEpochMillis = System.currentTimeMillis() + 120_000,
        ), action)
    }

    override suspend fun fetch(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord = operation(scope, "private_sync") { op ->
        val response = rpc("fetch", base(scope, resourceId, op))
        persistSnapshot(scope, response)
        decode(response)
    }

    override suspend fun open(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord = locked(scope, resourceId) {
        val generation = mutableAuthorizationRevision.value
        val stamp = gate(scope, generation)
        operation(scope, "private_open") { op ->
            val start = SystemClock.elapsedRealtime()
            val response = rpc("open", base(scope, resourceId, op))
            val record = decode(response)
            persistSnapshot(scope, response)
            if (record.revision > 0 && !record.deleted && !record.deletionPending) {
                val key = unwrap(scope, record, response, generation, start)
                try {
                    val binding = context(scope, record)
                    val wrapped = vault.wrapKey(scope.vaultId, VaultKeyDomain.CACHE, key, binding)
                    check(gate(scope, generation) == stamp)
                    synchronized(cache) {
                        check(mutableAuthorizationRevision.value == generation)
                        checkObserved(scope, record)
                        cache.put(cacheId(scope, record) + ":" + stamp, Cached(wrapped, binding,
                            start + response.long("expires_at_ms") - response.long("server_time_ms"), generation))
                            ?.let { it.wrapped.ciphertext.fill(0); it.binding.fill(0) }
                        while (cache.size > 32) cache.remove(cache.keys.first())?.let { it.wrapped.ciphertext.fill(0); it.binding.fill(0) }
                        armExpiry()
                    }
                } finally { key.fill(0) }
            }
            check(gate(scope, generation) == stamp)
            record
        }
    }

    override suspend fun sync(scope: PrivateResourceScope, cursor: Long): PrivateResourceChanges = operation(scope, "private_sync") { op ->
        val value = rpc("sync", buildJsonObject { base(scope, "private-resource-sync-v1", op).forEach { (k, v) -> put(k, v) }; put("cursor", cursor) })
        value.getValue("resources").jsonArray.forEach { persistSnapshot(scope, it.jsonObject) }
        PrivateResourceChanges(value.string("identity_sha256"), value.long("cursor"), value.getValue("has_more").jsonPrimitive.boolean,
            value.getValue("resources").jsonArray.map { decode(it.jsonObject) })
    }

    override suspend fun read(scope: PrivateResourceScope, record: PrivateResourceRecord): ByteArray = locked(scope, record.resourceId) {
        val generation = mutableAuthorizationRevision.value
        val stamp = gate(scope, generation)
        val snapshot = readPending(scope, "state:snapshot:" + record.resourceId)
        check(snapshot?.get("deleted")?.jsonPrimitive?.boolean != true) { "PRIVATE_RESOURCE_DELETED" }
        check(snapshot == null || snapshot.long("revision") <= record.revision) { "PRIVATE_RESOURCE_CONFLICT" }
        check(!record.deleted && !record.deletionPending && record.revision > 0 && record.ciphertext.size >= 16 && record.nonce.size == 12)
        val key = contentKey(scope, record, generation)
        try {
            check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
            checkObserved(scope, record)
            check(readAuthorizationRemainingMillis(scope, record) > 0) { "PRIVATE_RESOURCE_AUTHORIZATION_EXPIRED" }
            val plaintext = withContext(Dispatchers.Default) { crypt(Cipher.DECRYPT_MODE, key, record.nonce, context(scope, record), record.ciphertext) }
            try { check(gate(scope, generation) == stamp); checkObserved(scope, record); check(readAuthorizationRemainingMillis(scope, record) > 0) { "PRIVATE_RESOURCE_AUTHORIZATION_EXPIRED" }; plaintext }
            catch (failure: Throwable) { plaintext.fill(0); throw failure }
        } finally { key.fill(0) }
    }

    private fun cacheId(scope: PrivateResourceScope, record: PrivateResourceRecord) = "${scope.vaultId.value}:${record.resourceId}:${record.versionId}:${record.kind}"

    private suspend fun contentKey(scope: PrivateResourceScope, record: PrivateResourceRecord, generation: Long, minimumRemainingMillis: Long = 0): ByteArray {
        val stamp = gate(scope, generation)
        val id = cacheId(scope, record) + ":" + stamp
        val cached = synchronized(cache) { cache[id]?.takeIf { it.generation == generation && it.deadline - SystemClock.elapsedRealtime() > minimumRemainingMillis } }
        if (cached != null) {
            val key = vault.unwrapKey(scope.vaultId, cached.wrapped, cached.binding)
            try { check(gate(scope, generation) == stamp); check(cached.deadline > SystemClock.elapsedRealtime()); return key }
            catch (failure: Throwable) { key.fill(0); throw failure }
        }
        return operation(scope, "private_open") { op ->
            val start = SystemClock.elapsedRealtime()
            val body = rpc("release", buildJsonObject {
                base(scope, record.resourceId, op).forEach { (k, v) -> put(k, v) }
                put("resource_kind", record.kind); put("version_id", checkNotNull(record.versionId))
            })
            unwrap(scope, record, body, generation, start).also { key ->
                try {
                    val binding = context(scope, record)
                    val wrapped = vault.wrapKey(scope.vaultId, VaultKeyDomain.CACHE, key, binding)
                    check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                    synchronized(cache) {
                        check(mutableAuthorizationRevision.value == generation)
                        checkObserved(scope, record)
                        cache[id] = Cached(wrapped, binding, start + body.long("expires_at_ms") - body.long("server_time_ms"), generation)
                        while (cache.size > 32) cache.remove(cache.keys.first())?.let { it.wrapped.ciphertext.fill(0); it.binding.fill(0) }
                        armExpiry()
                    }
                } catch (failure: Throwable) { key.fill(0); throw failure }
            }
        }
    }

    private suspend fun unwrap(scope: PrivateResourceScope, record: PrivateResourceRecord, body: JsonObject, generation: Long, start: Long): ByteArray {
        gate(scope, generation)
        val stamp = gate(scope, generation)
        val binding = context(scope, record)
        check(body.string("version_id") == record.versionId && body.string("resource_kind") == record.kind && body.string("context_sha256") == binding.hex())
        val descriptor = deviceKeys.descriptor()
        val envelope = DeviceShareEnvelope(descriptor.installationId, descriptor.keyId, descriptor.algorithm, body.string("encrypted_key_b64").unb64())
        val key = try { deviceKeys.unwrapShare(envelope, ContentId("private-resource-v1:${scope.accountIdHash}:${record.resourceId}"), binding) }
        finally { envelope.ciphertext.fill(0) }
        try {
            check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
            val duration = body.long("expires_at_ms") - body.long("server_time_ms")
            val ceiling = if (record.kind in setOf("COMMON_BODY", "COMMON_INDEX", "COMMON_MANAGEMENT")) 300_000L else 60_000L
            check(duration in 1..ceiling) { "PRIVATE_RESOURCE_INVALID_GRANT" }
            check(key.size == 32 && SystemClock.elapsedRealtime() < start + body.long("expires_at_ms") - body.long("server_time_ms"))
            return key
        } catch (failure: Throwable) { key.fill(0); throw failure }
    }

    override suspend fun write(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long, plaintext: ByteArray): PrivateResourceRecord =
        writeWithOperation(scope, resourceId, kind, expectedRevision, plaintext, UUID.randomUUID().toString())

    override suspend fun writeWithOperation(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long, plaintext: ByteArray, operationId: String): PrivateResourceRecord =
        writeWithLifecycleOperation(scope, resourceId, kind, expectedRevision, plaintext, operationId,
            if (expectedRevision == 0L) "private_create" else "private_edit")

    override suspend fun writeWithLifecycleOperation(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long,
        plaintext: ByteArray, operationId: String, trigger: String): PrivateResourceRecord =
        locked(scope, resourceId) {
            require(trigger in setOf("private_create", "private_edit", "private_pin", "private_reset_order", "private_usage_commit",
                "favourite_add", "favourite_remove"))
            require(plaintext.size in 1..512_000)
            val generation = mutableAuthorizationRevision.value
            val stamp = gate(scope, generation)
            val previous = readPending(scope, resourceId)
            if (previous != null) {
                val resumed = commitPending(scope, previous, generation)
                check(!resumed.deleted) { "PRIVATE_RESOURCE_DELETED" }
                check(resumed.versionId == previous["version_id"]?.jsonPrimitive?.content) { "PRIVATE_RESOURCE_CONFLICT" }
                // The prior intent committed; the caller must fetch/rebase rather than overwrite its result.
                if (previous.string("fingerprint") == digest(plaintext).hex()) return@locked resumed
                error("PRIVATE_RESOURCE_CONFLICT")
            }
            val op = operationId
            operation(scope, trigger, op) { operationId ->
                val start = SystemClock.elapsedRealtime()
                val request = buildJsonObject {
                    base(scope, resourceId, operationId).forEach { (k, v) -> put(k, v) }
                    put("resource_kind", kind); put("expected_revision", expectedRevision)
                }
                val grant = rpc("prepare", request)
                check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                val prepared = PrivateResourceRecord(resourceId, kind, expectedRevision, grant.string("version_id"), false)
                val key = unwrap(scope, prepared, grant, generation, start)
                val nonce = ByteArray(12).also(random::nextBytes)
                val binding = context(scope, prepared)
                val wrapped = try { vault.wrapKey(scope.vaultId, VaultKeyDomain.CACHE, key, binding) } catch (failure: Throwable) {
                    key.fill(0); throw failure
                }
                val ciphertext = try { crypt(Cipher.ENCRYPT_MODE, key, nonce, binding, plaintext) } finally { key.fill(0) }
                try {
                val pending = buildJsonObject {
                    request.forEach { (k, v) -> put(k, v) }; put("version_id", prepared.versionId)
                    put("ciphertext_b64", ciphertext.b64()); put("nonce_b64", nonce.b64()); put("fingerprint", digest(plaintext).hex()); put("__trigger", trigger)
                }
                check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                writePending(scope, resourceId, pending)
                val record = decode(rpc("put", buildJsonObject { pending.filterKeys { it != "fingerprint" && it != "__trigger" }.forEach { (k, v) -> put(k, v) } }))
                persistSnapshot(scope, recordJson(record))
                check(gate(scope, generation) == stamp) { "PRIVATE_RESOURCE_AUTHORIZATION_CHANGED" }
                clearPending(scope, resourceId)
                evict(scope, resourceId)
                synchronized(cache) {
                    check(mutableAuthorizationRevision.value == generation)
                    checkObserved(scope, record)
                    cache[cacheId(scope, record) + ":" + stamp] = Cached(wrapped, binding,
                        start + grant.long("expires_at_ms") - grant.long("server_time_ms"), generation)
                    while (cache.size > 32) cache.remove(cache.keys.first())?.let { it.wrapped.ciphertext.fill(0); it.binding.fill(0) }
                    armExpiry()
                }
                record
                } catch (failure: Throwable) { wrapped.ciphertext.fill(0); binding.fill(0); throw failure }
            }
        }

    private suspend fun commitPending(scope: PrivateResourceScope, pending: JsonObject, generation: Long): PrivateResourceRecord {
        return try {
            val deleting = pending["__action"]?.jsonPrimitive?.content == "delete"
            // Older ciphertext intents were created as private_edit. Never rename their original lifecycle on replay.
            val trigger = if (deleting) "private_delete" else pending["__trigger"]?.jsonPrimitive?.content ?: "private_edit"
            operation(scope, trigger, pending.string("operation_id")) { operationId ->
                check(operationId == pending.string("operation_id"))
                gate(scope, generation)
                val body = buildJsonObject {
                    pending.filterKeys { it != "fingerprint" && it != "request_nonce_b64" && it != "__action" && it != "__trigger" }.forEach { (k, v) -> put(k, v) }
                    put("request_nonce_b64", ByteArray(24).also(random::nextBytes).b64())
                }
                val record = decode(rpc(if (deleting) "delete" else "put", body))
                persistSnapshot(scope, recordJson(record))
                gate(scope, generation)
                clearPending(scope, record.resourceId)
                evict(scope, record.resourceId)
                record
            }
        } catch (failure: Exception) {
            // A preparation can expire before its ciphertext commits. A separately signed current head
            // must prove this version is absent before discarding its ciphertext intent. Never convert
            // a pending deletion or a committed version into a successful old lifecycle receipt.
            val expired = failure is ContentProtectionException.Expired
            val conflict = failure is IllegalStateException && failure.message == "PRIVATE_RESOURCE_CONFLICT"
            if ((!expired && !conflict) || pending["__action"]?.jsonPrimitive?.content == "delete") throw failure
            val current = fetch(scope, pending.string("resource_id"))
            gate(scope, generation)
            val superseded = current.versionId != pending.string("version_id") && (expired || current.revision > pending.long("expected_revision"))
            if (!current.deleted && !superseded) throw failure
            clearPending(scope, current.resourceId)
            evict(scope, current.resourceId)
            current
        }
    }

    override suspend fun resumePending(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord? = locked(scope, resourceId) {
        val generation = mutableAuthorizationRevision.value
        gate(scope, generation)
        readPending(scope, resourceId)?.let { commitPending(scope, it, generation) }
    }

    override suspend fun delete(scope: PrivateResourceScope, record: PrivateResourceRecord): PrivateResourceRecord = locked(scope, record.resourceId) {
        val generation = mutableAuthorizationRevision.value
        gate(scope, generation)
        val existing = readPending(scope, record.resourceId)
        val pending = if (existing?.get("__action")?.jsonPrimitive?.content == "delete") existing else buildJsonObject {
            base(scope, record.resourceId, UUID.randomUUID().toString()).forEach { (k, v) -> put(k, v) }
            put("expected_revision", record.revision); put("resource_kind", record.kind); put("__action", "delete")
        }
        writePending(scope, record.resourceId, pending)
        commitPending(scope, pending, generation)
    }

    private fun evict(scope: PrivateResourceScope, id: String) = synchronized(cache) {
        cache.keys.filter { it.startsWith("${scope.vaultId.value}:$id:") }.forEach { cache.remove(it)?.wrapped?.ciphertext?.fill(0) }
        armExpiry()
    }

    private fun observationId(scope: PrivateResourceScope, id: String) = "${scope.vaultId.value}:$id"

    private fun checkObserved(scope: PrivateResourceScope, record: PrivateResourceRecord) = synchronized(cache) {
        observed[observationId(scope, record.resourceId)]?.let { latest ->
            check(!latest.deleted && !latest.deletionPending) { "PRIVATE_RESOURCE_DELETED" }
            check(latest.revision <= record.revision && (latest.revision != record.revision || latest.versionId == record.versionId)) { "PRIVATE_RESOURCE_CONFLICT" }
        }
    }

    private fun observe(scope: PrivateResourceScope, record: PrivateResourceRecord) = synchronized(cache) {
        val id = observationId(scope, record.resourceId)
        val previous = observed[id]
        if (previous != null && (previous.deleted || previous.revision > record.revision || previous.revision == record.revision && previous.deletionPending)) return@synchronized
        if (previous?.versionId != record.versionId || record.deleted || record.deletionPending) evict(scope, record.resourceId)
        observed[id] = record.copy(ciphertext = byteArrayOf(), nonce = byteArrayOf())
        while (observed.size > 128) {
            val old = observed.keys.first()
            cache.keys.filter { it.startsWith("$old:") }.forEach { cache.remove(it)?.wrapped?.ciphertext?.fill(0) }
            observed.remove(old)
        }
    }

    private fun armExpiry() {
        expiryJob?.cancel()
        val deadline = cache.values.minOfOrNull { it.deadline } ?: return
        expiryJob = expiryScope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            synchronized(cache) {
                val now = SystemClock.elapsedRealtime()
                val expired = cache.filterValues { it.deadline <= now }.keys.toList()
                expired.forEach { cache.remove(it)?.let { entry -> entry.wrapped.ciphertext.fill(0); entry.binding.fill(0) } }
                // Legacy displays still clear on expiry; new displays check their own grant deadline.
                if (expired.isNotEmpty()) mutableRevision.update { it + 1 }
                armExpiry()
            }
        }
    }

    override fun readAuthorizationRemainingMillis(scope: PrivateResourceScope, record: PrivateResourceRecord): Long = synchronized(cache) {
        val prefix = cacheId(scope, record) + ":"
        (cache.filter { (id, entry) -> id.startsWith(prefix) && entry.generation == mutableAuthorizationRevision.value }
            .values.maxOfOrNull { it.deadline - SystemClock.elapsedRealtime() } ?: 0).coerceAtLeast(0)
    }

    override suspend fun renewReadAuthorization(scope: PrivateResourceScope, record: PrivateResourceRecord): Long = locked(scope, record.resourceId) {
        val generation = mutableAuthorizationRevision.value
        val stamp = gate(scope, generation)
        checkObserved(scope, record)
        contentKey(scope, record, generation, minimumRemainingMillis = 10_000).fill(0)
        check(gate(scope, generation) == stamp)
        checkObserved(scope, record)
        readAuthorizationRemainingMillis(scope, record).also { check(it > 0) }
    }

    private fun context(scope: PrivateResourceScope, record: PrivateResourceRecord): ByteArray = digest(buildJsonObject {
        // Same canonical key order as server canonical_json(sort_keys=True).
        put("account", scope.accountIdHash); put("domain", "account-private-resource-v1"); put("kind", record.kind)
        put("resource", record.resourceId); put("version", checkNotNull(record.versionId))
    }.toString().encodeToByteArray())

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input)
    }

    private fun decode(value: JsonObject) = PrivateResourceRecord(value.string("resource_id"), value["resource_kind"]?.jsonPrimitive?.content ?: "COMMON_BODY",
        value.long("revision"), value["version_id"]?.jsonPrimitive?.contentOrNull, value.getValue("deleted").jsonPrimitive.boolean,
        value["ciphertext_b64"]?.jsonPrimitive?.content?.unb64() ?: byteArrayOf(), value["nonce_b64"]?.jsonPrimitive?.content?.unb64() ?: byteArrayOf(),
        value["deletion_pending"]?.jsonPrimitive?.boolean ?: false)

    private fun pendingKey(scope: PrivateResourceScope, id: String) = digest("${scope.vaultId.value}:$id".encodeToByteArray()).hex()

    private fun recordJson(record: PrivateResourceRecord) = buildJsonObject {
        put("resource_id", record.resourceId); put("resource_kind", record.kind); put("revision", record.revision)
        put("version_id", record.versionId); put("deleted", record.deleted); put("deletion_pending", record.deletionPending)
        if (!record.deleted && record.ciphertext.isNotEmpty()) { put("ciphertext_b64", record.ciphertext.b64()); put("nonce_b64", record.nonce.b64()) }
    }

    private suspend fun persistSnapshot(scope: PrivateResourceScope, response: JsonObject) = locked(scope, "state:snapshot:" + response.string("resource_id")) {
        // Only KMS ciphertext and opaque metadata are cached. No resource CEK survives the process.
        observe(scope, decode(response))
        if (!vault.isUnlocked(scope.vaultId)) return@locked
        val id = "state:snapshot:" + response.string("resource_id")
        val cached = readPending(scope, id)
        val normalized = recordJson(decode(response))
        if (cached != null && (cached.long("revision") > normalized.long("revision") || cached["deleted"]?.jsonPrimitive?.boolean == true)) return@locked
        if (cached == normalized) return@locked
        writePending(scope, id, normalized)
    }

    private suspend fun writePending(scope: PrivateResourceScope, id: String, body: JsonObject) {
        val key = pendingKey(scope, id)
        val previous = pendingReference(key)
        val bytes = body.toString().encodeToByteArray()
        val ref = try { files.write(SecureObjectWriteRequest(SecureObjectId(UUID.randomUUID().toString()), scope.vaultId,
            SecureObjectPurpose.ACCOUNT_PRIVATE_RESOURCE, key.encodeToByteArray(), bytes.size.toLong())) { it.write(bytes) } } finally { bytes.fill(0) }
        val encoded = buildJsonObject {
            put("object", ref.objectId.value); put("vault", ref.vaultId.value); put("envelope", ref.keyEnvelopeId.value)
            put("format", ref.formatVersion); put("plain", ref.plaintextLength); put("cipher", ref.ciphertextLength); put("aad", ref.associatedDataSha256)
        }
        if (!preferences.edit().putString(key, encoded.toString()).commit()) {
            files.delete(ref)
            error("PRIVATE_RESOURCE_STORAGE_UNAVAILABLE")
        }
        previous?.let { files.delete(it) }
    }

    private fun pendingReference(key: String): SecureObjectReference? = preferences.getString(key, null)?.let { encoded ->
        val ref = Json.parseToJsonElement(encoded).jsonObject
        SecureObjectReference(SecureObjectId(ref.string("object")), AccountVaultId(ref.string("vault")), SecureObjectPurpose.ACCOUNT_PRIVATE_RESOURCE,
            KeyEnvelopeId(ref.string("envelope")), ref.getValue("format").jsonPrimitive.int, ref.long("plain"), ref.long("cipher"), ref.string("aad"))
    }

    private suspend fun readPending(scope: PrivateResourceScope, id: String): JsonObject? = pendingReference(pendingKey(scope, id))?.let { ref ->
        check(ref.vaultId == scope.vaultId)
        check(ref.associatedDataSha256 == digest(pendingKey(scope, id).encodeToByteArray()).hex())
        files.read(ref) { input -> val bytes = input.readBytes(); try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) } }
    }

    private suspend fun clearPending(scope: PrivateResourceScope, id: String) {
        val key = pendingKey(scope, id)
        val previous = pendingReference(key)
        check(preferences.edit().remove(key).commit())
        previous?.let { files.delete(it) }
    }

    override suspend fun readLocalMetadata(scope: PrivateResourceScope, name: String): ByteArray? = locked(scope, "local-$name") {
        require(name in setOf("common-index-pending", "usage-pending", "sync-checkpoint", "common-outbox"))
        val generation = mutableAuthorizationRevision.value
        val stamp = gate(scope, generation)
        val value = readPending(scope, "local-$name")?.string("bytes")?.unb64()
        try { check(gate(scope, generation) == stamp); value } catch (failure: Throwable) { value?.fill(0); throw failure }
    }

    override suspend fun writeLocalMetadata(scope: PrivateResourceScope, name: String, bytes: ByteArray?): Unit = locked(scope, "local-$name") {
        require(name in setOf("common-index-pending", "usage-pending", "sync-checkpoint", "common-outbox"))
        val generation = mutableAuthorizationRevision.value
        val stamp = gate(scope, generation)
        if (bytes == null) clearPending(scope, "local-$name") else {
            require(bytes.size <= 512_000)
            writePending(scope, "local-$name", buildJsonObject { put("bytes", bytes.b64()) })
        }
        check(gate(scope, generation) == stamp)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun ByteArray.b64() = Base64.getEncoder().encodeToString(this)
    private fun String.unb64() = Base64.getDecoder().decode(this)
}
