/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.api

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class SecureMediaExternalRecipient(val uid: Int, val packageName: String, val activityClassName: String) {
    init {
        require(uid >= 0)
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(activityClassName.matches(Regex("[A-Za-z0-9_.$]+")))
    }
}

/** Attribution and purpose of an issuer-owned local export child; these fields alone never grant access. */
data class SecureMediaExternalReadBinding(
    val owner: SecureTransientMediaOwner,
    val sourceOperationId: String,
    val sourcePolicyId: String,
    val sourcePolicyVersion: Long,
    val sourceResourceSha256: String,
    val targetResourceSha256: String,
    val recipient: SecureMediaExternalRecipient,
) {
    init {
        require(sourceOperationId.matches(Regex("[A-Za-z0-9_-]{16,256}")))
        require(owner.operationId != sourceOperationId)
        require(!owner.installationId.isNullOrBlank())
        require(sourcePolicyId.isNotBlank() && sourcePolicyVersion >= 0)
        require(listOf(sourceResourceSha256, targetResourceSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    fun digestSha256(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            listOf(
                "EXTERNAL_OPEN",
                owner.operationId,
                sourceOperationId,
                owner.accountIdHash,
                owner.matrixDeviceIdHash,
                owner.installationId.orEmpty(),
                owner.roomIdHash.orEmpty(),
                owner.eventIdHash.orEmpty(),
                sourcePolicyId,
                sourceResourceSha256,
                targetResourceSha256,
                recipient.packageName,
                recipient.activityClassName
            ).forEach { value ->
                val encoded = value.encodeToByteArray()
                out.writeInt(encoded.size)
                out.write(encoded)
            }
            out.writeLong(sourcePolicyVersion)
            out.writeInt(recipient.uid)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class SecureTransientMediaObjectRecord(
    val owner: SecureTransientMediaOwner,
    val identity: SecureObjectIdentity,
    val recordSha256: String,
)

data class SecureMediaExternalSource(
    val owner: SecureTransientMediaOwner,
    val logicalResource: SecureMediaLogicalResourceDescriptor,
    val authorizationDeadlineEpochMillis: Long,
    val processDeadlineElapsedRealtimeMillis: Long,
)

/** Observations made by our provider/store, never a third-party rendering or erasure receipt. */
data class SecureMediaExternalReadObservation(
    val descriptorOpens: Long = 0,
    val readCalls: Long = 0,
    val bytesCopied: Long = 0,
    val firstReadAtEpochMillis: Long? = null,
    val revoked: Boolean = false,
    val readsQuiescent: Boolean = false,
    val cleanupComplete: Boolean = false,
    val cleanupPending: Boolean = false,
)

/** No URI or plaintext exists at preparation. Implementations are process-local and issuer-owned. */
interface SecureMediaExternalReadPlan : AutoCloseable {
    val binding: SecureMediaExternalReadBinding
    val objectRecord: SecureTransientMediaObjectRecord
    val observation: StateFlow<SecureMediaExternalReadObservation>
}

interface SecureMediaExternalReadLease : SecureMediaLease {
    val binding: SecureMediaExternalReadBinding
    val objectRecord: SecureTransientMediaObjectRecord
    val observation: StateFlow<SecureMediaExternalReadObservation>
}

interface ExternalSecureMediaLeaseManager {
    /** Exact live source admission. Legacy/unbound sources cannot be promoted by caller metadata. */
    fun inspectExternalSource(uri: Uri): SecureMediaExternalSource

    /** Publish metadata-only cleanup intent; caller must reserve these resources before [issueExternalRead]. */
    suspend fun prepareExternalRead(
        sourceUri: Uri,
        sourceOperationId: String,
        owner: SecureTransientMediaOwner,
        recipient: SecureMediaExternalRecipient,
    ): SecureMediaExternalReadPlan

    suspend fun issueExternalRead(plan: SecureMediaExternalReadPlan): SecureMediaExternalReadLease
}
