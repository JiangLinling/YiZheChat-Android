/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.AccountVaultId
import kotlinx.coroutines.flow.StateFlow

data class PrivateResourceScope(val accountIdHash: String, val matrixDeviceIdHash: String, val vaultId: AccountVaultId)

data class PrivateResourceRecord(
    val resourceId: String,
    val kind: String,
    val revision: Long,
    val versionId: String?,
    val deleted: Boolean,
    val ciphertext: ByteArray = byteArrayOf(),
    val nonce: ByteArray = byteArrayOf(),
    val deletionPending: Boolean = false,
)

data class PrivateResourceChanges(val identityHash: String, val cursor: Long, val hasMore: Boolean, val records: List<PrivateResourceRecord>)

/** Ciphertext transfer never unlocks keys. Plaintext is returned only under a current signed grant. */
interface AccountPrivateResourceClient {
    val revision: StateFlow<Long>
    /** Security changes invalidate every display; an unrelated cached grant expiring does not. */
    val authorizationRevision: StateFlow<Long> get() = revision
    fun readAuthorizationRemainingMillis(scope: PrivateResourceScope, record: PrivateResourceRecord): Long = 0
    /** Renew only while a view is showing this exact resource. Never extends an old grant. */
    suspend fun renewReadAuthorization(scope: PrivateResourceScope, record: PrivateResourceRecord): Long =
        throw UnsupportedOperationException("Read authorization renewal is unavailable")
    suspend fun <T> withReadOperation(scope: PrivateResourceScope, block: suspend () -> T): T = block()
    suspend fun fetch(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord
    /** Current ciphertext head and its signed device-bound grant in one authority round trip. */
    suspend fun open(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord = fetch(scope, resourceId)
    suspend fun sync(scope: PrivateResourceScope, cursor: Long): PrivateResourceChanges
    suspend fun read(scope: PrivateResourceScope, record: PrivateResourceRecord): ByteArray
    suspend fun write(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long, plaintext: ByteArray): PrivateResourceRecord
    suspend fun writeWithOperation(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long,
        plaintext: ByteArray, operationId: String): PrivateResourceRecord = write(scope, resourceId, kind, expectedRevision, plaintext)
    /** Persist this semantic trigger with the ciphertext intent; replay retains its original lifecycle identity. */
    suspend fun writeWithLifecycleOperation(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long,
        plaintext: ByteArray, operationId: String, trigger: String): PrivateResourceRecord =
        writeWithOperation(scope, resourceId, kind, expectedRevision, plaintext, operationId)
    suspend fun delete(scope: PrivateResourceScope, record: PrivateResourceRecord): PrivateResourceRecord
    suspend fun resumePending(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord?
    /** Encrypted local convergence metadata. This is never a display/read capability for a resource body. */
    suspend fun readLocalMetadata(scope: PrivateResourceScope, name: String): ByteArray?
    suspend fun writeLocalMetadata(scope: PrivateResourceScope, name: String, bytes: ByteArray?)
    /** Page closure removes the page's plaintext; it must not call this global invalidation hook. */
    fun invalidate()
}
