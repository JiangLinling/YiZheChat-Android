/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.api

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.time.Duration

/** Fieldless marker: implementations remain issuer-owned opaque process capabilities. */
interface SecureMediaReleaseAuthority : AutoCloseable

enum class SecureMediaReadPurpose {
    LEASE_STREAM,
    SEGMENTED_DECODER,
    EXTERNAL_OPEN,
}

const val SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES: Int = 64 * 1024

data class SecureMediaLogicalResourceDescriptor(
    val schemaVersion: Int = 1,
    val assetId: String,
    val mediaVersion: Long,
    val roomIdSha256Hex: String,
    val contentContractId: String,
    val resourceId: String,
    val contentId: String,
    val kind: String,
    val index: Int,
    val expectedPlaintextLength: Long,
    val immutableContextSha256Hex: String,
    val matrixLocatorSha256Hex: String,
    val outerFrameSha256Hex: String,
    val outerFrameLength: Long,
    val vaultId: AccountVaultId,
    val objectId: SecureObjectId,
    val purpose: SecureObjectPurpose,
) {
    init {
        require(schemaVersion == 1)
        require(mediaVersion >= 0 && index >= 0)
        require(expectedPlaintextLength >= 0 && outerFrameLength >= 0)
        listOf(assetId, contentContractId, resourceId, contentId, kind, vaultId.value, objectId.value)
            .forEach { require(it.isNotBlank()) }
        listOf(roomIdSha256Hex, immutableContextSha256Hex, matrixLocatorSha256Hex, outerFrameSha256Hex)
            .forEach { require(it.matches(SHA256_HEX)) }
    }

    fun canonicalBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(schemaVersion)
            output.writeString(assetId)
            output.writeLong(mediaVersion)
            output.writeString(roomIdSha256Hex)
            output.writeString(contentContractId)
            output.writeString(resourceId)
            output.writeString(contentId)
            output.writeString(kind)
            output.writeInt(index)
            output.writeLong(expectedPlaintextLength)
            output.writeString(immutableContextSha256Hex)
            output.writeString(matrixLocatorSha256Hex)
            output.writeString(outerFrameSha256Hex)
            output.writeLong(outerFrameLength)
            output.writeString(vaultId.value)
            output.writeString(objectId.value)
            output.writeInt(purpose.formatCode)
        }
        bytes.toByteArray()
    }

    fun digestSha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(canonicalBytes()).toHex()

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        write(encoded)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

data class SecureObjectStagingCleanupItem(
    val target: String,
    val removedOrAbsent: Boolean,
)

data class SecureObjectStagingCleanupReport(
    val transactionId: String,
    val items: List<SecureObjectStagingCleanupItem>,
) {
    val complete: Boolean get() = items.isNotEmpty() && items.all { it.removedOrAbsent }
}

class SecureMediaCleanupException(
    val cleanupReport: SecureObjectStagingCleanupReport,
    cause: Throwable? = null,
) : SecureStorageException("Secure media cleanup was incomplete.", cause)

interface SecureObjectStagingTransaction {
    val transactionId: String
    val request: SecureObjectWriteRequest
    val plaintextLength: Long

    /** Commits exactly once into the existing encrypted object store. */
    suspend fun commit(): SecureObjectReference

    /** Idempotent, itemized cleanup. */
    suspend fun abort(): SecureObjectStagingCleanupReport
}

interface SecureObjectStagingStore {
    /** A's output is encrypted before this method returns; no final object or URI is published. */
    suspend fun stage(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (OutputStream) -> Unit,
    ): SecureObjectStagingTransaction
}

enum class SecureMediaAuthorizationRejection {
    AUTHORITY_UNAVAILABLE,
    OWNER_MISMATCH,
    RELEASE_MISMATCH,
    EXPIRED,
    BINDING_INVALID,
    CLOSED,
    PREVIEW_DENIED,
    REVISION_MISMATCH,
}

data class SecureMediaVerifiedAuthorization(
    val authorityId: String,
    val contentId: String,
    val immutableContextSha256Hex: String,
    val authorizationDeadlineEpochMillis: Long,
    val authorizationRevision: Long,
    val revocationScope: String?,
    val externalReadBinding: SecureMediaExternalReadBinding? = null,
    val localAuthorityId: String? = null,
    val authorizationDeadlineElapsedRealtimeMillis: Long? = null,
)

data class SecureMediaValidationTime(
    val bootCount: Long,
    val elapsedRealtimeMillis: Long,
)

sealed interface SecureMediaAuthorizationValidation {
    data class Verified(
        val authorization: SecureMediaVerifiedAuthorization,
        val time: SecureMediaValidationTime,
    ) : SecureMediaAuthorizationValidation
    data class Rejected(val reason: SecureMediaAuthorizationRejection) : SecureMediaAuthorizationValidation
}

interface SecureMediaAuthorizationValidator {
    /** Requests a fresh issuer-owned grant, only while the original internal source is live. */
    suspend fun renewReadAuthority(authority: SecureMediaReleaseAuthority): SecureMediaReleaseAuthority? = null

    /** Own the remote fresh admission in the existing lifecycle engine before the grant can be installed. */
    suspend fun withReadAdmission(
        authority: SecureMediaReleaseAuthority,
        sourceOwner: SecureTransientMediaOwner?,
        install: suspend () -> Boolean,
    ): Boolean = install()

    /** Derive only inside the existing issuer; copied descriptions or missing export evidence fail closed. */
    fun deriveExternalReadAuthority(
        authority: SecureMediaReleaseAuthority,
        binding: SecureMediaExternalReadBinding,
    ): SecureMediaReleaseAuthority? = null

    fun validate(
        authority: SecureMediaReleaseAuthority,
        expectedContentId: String,
        expectedImmutableContextSha256Hex: String,
        expectedAuthorizationRevision: Long? = null,
    ): SecureMediaAuthorizationValidation

    data object RejectAll : SecureMediaAuthorizationValidator {
        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation = SecureMediaAuthorizationValidation.Rejected(
            SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE
        )
    }
}

data class SecureMediaAuthorizedLeaseRequest(
    val staging: SecureObjectStagingTransaction,
    val logicalResource: SecureMediaLogicalResourceDescriptor,
    val releaseAuthority: SecureMediaReleaseAuthority,
    val mimeType: String?,
    val displayName: String?,
    val timeToLive: Duration,
    val allowedReadPurposes: Set<SecureMediaReadPurpose> = setOf(SecureMediaReadPurpose.LEASE_STREAM),
    val requireSeekableDescriptor: Boolean = false,
    val deleteObjectOnRevoke: Boolean = true,
    /** A derived owner can shorten, never extend, the original process-bound deadline. */
    val maximumProcessDeadlineElapsedRealtimeMillis: Long? = null,
)

data class SecureMediaAuthenticatedReadRequest(
    val offset: Long,
    val byteCount: Int,
    val purpose: SecureMediaReadPurpose,
    val callerUid: Int? = null,
)

enum class SecureMediaAuthenticatedReadRejection {
    CLOSED,
    REVOKED,
    AUTHORITY_UNAVAILABLE,
    OWNER_MISMATCH,
    RELEASE_MISMATCH,
    EXPIRED,
    BINDING_INVALID,
    PREVIEW_DENIED,
    REVISION_MISMATCH,
    RESOURCE_MISMATCH,
    PURPOSE_MISMATCH,
    RANGE_INVALID,
    BUSY,
    STORAGE_NOT_FOUND,
    STORAGE_AUTHENTICATION_FAILED,
    STORAGE_KEY_UNAVAILABLE,
    VAULT_DESTROYED,
    STORAGE_UNAVAILABLE,
}

sealed interface SecureMediaAuthenticatedReadResult {
    data class Copied(val byteCount: Int) : SecureMediaAuthenticatedReadResult
    data class Rejected(val reason: SecureMediaAuthenticatedReadRejection) : SecureMediaAuthenticatedReadResult
}

interface AuthorizedSecureMediaLease : SecureMediaLease {
    suspend fun copyAuthenticatedRangeInto(
        request: SecureMediaAuthenticatedReadRequest,
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): SecureMediaAuthenticatedReadResult
}

interface AuthorizedSecureMediaLeaseManager {
    suspend fun issueAuthorized(request: SecureMediaAuthorizedLeaseRequest): AuthorizedSecureMediaLease

    /** Process-local diagnostic receipts; entries mean destruction could not be claimed complete. */
    fun cleanupFailures(): List<SecureObjectStagingCleanupReport>

    /** Causal cleanup failures; the report list above remains the stable compatibility projection. */
    fun cleanupFailureExceptions(): List<SecureMediaCleanupException> = emptyList()
}
