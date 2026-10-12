/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.local

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.contentprotection.api.AccountPrivateResourceClient
import io.element.android.security.contentprotection.api.PrivateResourceScope
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Owns reference-only sync. The local suppression index is never serialized into this document. */
@Inject
@SingleIn(SessionScope::class)
class FavouriteReferenceSynchronizer(private val matrix: MatrixClient, private val resources: AccountPrivateResourceClient) {
    private val mutex = Mutex()
    private var identity: String? = null
    private var identityRevision: Long = -1
    private val scope get() = PrivateResourceScope(accountIdHash(matrix.sessionId.value), accountIdHash(matrix.deviceId.value),
        deriveAccountVaultId(matrix.sessionId.value, matrix.deviceId.value))

    suspend fun refresh(): Set<String> = update(emptySet(), emptySet(), onlyRead = true)

    suspend fun update(added: Set<String>, removed: Set<String>, onlyRead: Boolean = false): Set<String> = mutex.withLock {
        if (identity == null || identityRevision != resources.revision.value) {
            identity = resources.sync(scope, 0).identityHash
            identityRevision = resources.revision.value
        }
        val id = MessageDigest.getInstance("SHA-256").digest("favourite-index-v1:${scope.accountIdHash}:$identity".encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        resources.resumePending(scope, id)
        repeat(3) { attempt ->
            val head = resources.fetch(scope, id)
            check(!head.deleted) { "PRIVATE_RESOURCE_DELETED" }
            val previous = if (head.revision == 0L) emptySet() else {
                val bytes = resources.read(scope, head)
                try {
                    val values = JSONObject(bytes.decodeToString()).getJSONArray("references")
                    check(values.length() <= 2000)
                    (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) }
                } finally { bytes.fill(0) }
            }
            if (onlyRead) return@withLock previous
            val next = (previous + added) - removed
            check(next.size <= 2000 && next.all { key ->
                key.length <= 1024 && key.startsWith("!") && key.substringAfter('\u0000', "").startsWith("$")
            }) { "PRIVATE_RESOURCE_LIMIT" }
            if (next == previous) return@withLock next
            // Classify the effective change after rebasing. No-op inputs must not create lifecycle operations.
            val hasAdditions = (next - previous).isNotEmpty()
            val hasRemovals = (previous - next).isNotEmpty()
            check(!(hasAdditions && hasRemovals)) { "FAVOURITE_MIXED_MUTATION" }
            val trigger = if (hasAdditions) "favourite_add" else "favourite_remove"
            val bytes = JSONObject().put("format", 1).put("references", JSONArray(next.sorted())).toString().encodeToByteArray()
            try {
                resources.writeWithLifecycleOperation(scope, id, "FAVOURITE_REFERENCE", head.revision, bytes, UUID.randomUUID().toString(), trigger)
                return@withLock next
            } catch (failure: IllegalStateException) {
                if (failure.message != "PRIVATE_RESOURCE_CONFLICT" || attempt == 2) throw failure
            } finally { bytes.fill(0) }
        }
        error("PRIVATE_RESOURCE_CONFLICT")
    }
}
