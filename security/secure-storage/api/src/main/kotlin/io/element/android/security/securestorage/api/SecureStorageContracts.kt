/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.api

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration

open class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SecureObjectNotFoundException : SecureStorageException("Secure object not found.")

class SecureObjectAuthenticationException(cause: Throwable? = null) :
    SecureStorageException("Secure object authentication failed.", cause)

class SecureStorageKeyUnavailableException(cause: Throwable? = null) :
    SecureStorageException("Secure storage key is unavailable.", cause)

class SecureVaultDestroyedException : SecureStorageException("Secure vault has been destroyed.")

@JvmInline
value class SecureObjectId(val value: String)

@JvmInline
value class AccountVaultId(val value: String)

@JvmInline
value class KeyEnvelopeId(val value: String)

enum class SecureObjectPurpose(val formatCode: Int) {
    IMAGE(1),
    IMAGE_THUMBNAIL(2),
    VOICE_MESSAGE(3),
    VIDEO(4),
    VIDEO_THUMBNAIL(5),
    ATTACHMENT(6),
    UPLOAD_STAGING(7),
    EDITING_STAGING(8),
    SHARE_STAGING(9),
    NOTIFICATION_MEDIA(10),
    LOG(11),
    OFFLINE_LEASE(12),
    CONTACT_PROFILE(13),
    OFFLINE_OUTBOX(14),
    MESSAGE_REFERENCE_INDEX(15),
    DOCUMENT_PREVIEW(16),
    ACCOUNT_PRIVATE_RESOURCE(17),
    ;

    companion object {
        fun fromFormatCode(formatCode: Int): SecureObjectPurpose? = entries.firstOrNull { it.formatCode == formatCode }
    }
}

data class SecureObjectWriteRequest(
    val objectId: SecureObjectId,
    val vaultId: AccountVaultId,
    val purpose: SecureObjectPurpose,
    val associatedData: ByteArray,
    val expectedPlaintextLength: Long? = null,
    /** Only for single-use, process-owned media objects; never for reusable encrypted caches. */
    val transientMediaOwner: SecureTransientMediaOwner? = null,
)

data class SecureObjectIdentity(
    val objectId: SecureObjectId,
    val vaultId: AccountVaultId,
    val purpose: SecureObjectPurpose,
)

data class SecureObjectReference(
    val objectId: SecureObjectId,
    val vaultId: AccountVaultId,
    val purpose: SecureObjectPurpose,
    val keyEnvelopeId: KeyEnvelopeId,
    val formatVersion: Int,
    val plaintextLength: Long,
    val ciphertextLength: Long,
    val associatedDataSha256: String,
)

data class SecureStoreMaintenanceReport(
    val partialFilesDeleted: Int,
    val orphanDataFilesDeleted: Int,
    val orphanKeyEnvelopesDeleted: Int,
)

data class SecureVaultDeletionReport(
    val vaultId: AccountVaultId,
    val tombstoneWritten: Boolean,
    val keyDestroyed: Boolean,
    val filesDeleted: Boolean,
) {
    val cryptographicEraseCompleted: Boolean = tombstoneWritten && keyDestroyed
}

interface SensitiveFileStore {
    suspend fun transientMediaObjectRecord(identity: SecureObjectIdentity): SecureTransientMediaObjectRecord? = null

    suspend fun transientMediaObjectDeletion(identity: SecureObjectIdentity): SecureTransientObjectDeletion? = null

    /** Only the unpublished object's owner may call this after all its staging work/buffers have stopped. */
    suspend fun discardUnissuedTransientMediaObject(record: SecureTransientMediaObjectRecord): SecureTransientObjectDeletion? = null

    /** Publish the exact cleanup obligation before staging or object creation. Contains no content or keys. */
    suspend fun registerTransientMediaObject(request: SecureObjectWriteRequest) {
        check(request.transientMediaOwner == null) { "Durable transient media ownership is unavailable." }
    }

    /** Retry only released objects or objects owned by a previous process; never re-download content. */
    suspend fun recoverTransientMediaObjects(): List<SecureTransientObjectDeletion> = emptyList()

    /** Retire transport responsibility only after the caller verifies the server acknowledgement of this exact fact. */
    suspend fun acknowledgeTransientMediaDeletion(fact: SecureTransientObjectDeletion, receiptReference: String): Unit =
        error("Transient media cleanup acknowledgement is unavailable.")

    /** Called only after local lease revocation and all owned read buffers have been released. */
    suspend fun recordTransientMediaLeaseQuiescent(reference: SecureObjectReference) = Unit

    /**
     * Encrypts bytes as they are written. The supplied stream is owned by the store and must not escape this callback.
     */
    suspend fun write(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (OutputStream) -> Unit,
    ): SecureObjectReference

    /**
     * Authenticates the complete encrypted object before returning, even if [readPlaintext] only consumes a prefix.
     */
    suspend fun <T> read(
        reference: SecureObjectReference,
        readPlaintext: suspend (InputStream) -> T,
    ): T

    /**
     * Authenticates and decrypts only the requested plaintext range.
     *
     * This is intended for process-bound viewers, such as Android's PDF renderer, which require a seekable file
     * descriptor. Implementations must not create a plaintext staging file. Every returned byte must come from an
     * independently authenticated encrypted chunk, and callers must wipe the returned array after use.
     */
    suspend fun readRange(
        reference: SecureObjectReference,
        offset: Long,
        byteCount: Int,
    ): ByteArray

    /**
     * Authenticates and decrypts into a caller-owned buffer whose reference exists before suspension.
     *
     * Implementations that support authorized handoff must override this method. The fail-closed default preserves
     * source compatibility for stores that are not decoder-capable without routing through [readRange], whose returned
     * array could otherwise be lost at a cancelled suspension boundary.
     */
    suspend fun readRangeInto(
        reference: SecureObjectReference,
        offset: Long,
        byteCount: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int = throw SecureStorageException("Caller-owned authenticated range reads are unavailable.")

    suspend fun delete(reference: SecureObjectReference): Boolean

    /** Deletes every encrypted generation for an exact object identity.
     *
     * This supports durable write-ahead cleanup when a caller crashes after [write] publishes an object but before
     * the returned reference can be journaled. Callers must use unguessable, single-use object identities.
     */
    suspend fun deleteObject(identity: SecureObjectIdentity): Boolean

    suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport

    suspend fun runMaintenance(): SecureStoreMaintenanceReport
}

interface SecureStorageRoots {
    fun persistentRoot(): File
    fun runtimeRoot(): File
}

data class SecureMediaLeaseRequest(
    val reference: SecureObjectReference,
    val mimeType: String?,
    val displayName: String?,
    val timeToLive: Duration,
    /** Optional process-memory revocation domain, for example one burn-after-read view session. */
    val revocationScope: String? = null,
    /** Requests a random-access descriptor backed directly by authenticated encrypted storage. */
    val requireSeekableDescriptor: Boolean = false,
    /** Persistent encrypted caches keep their object when a process-bound viewer lease closes. */
    val deleteObjectOnRevoke: Boolean = true,
    /**
     * Live authorization check evaluated whenever the capability is resolved or read.
     *
     * Returning false (or throwing) permanently revokes this lease. This lets a consumer bind the
     * local TTL to a shorter policy/lifecycle grant and to lock or remote-revocation state.
     */
    val isReadAuthorized: () -> Boolean = { true },
)

/**
 * A process-bound capability for reading one encrypted media object.
 *
 * The URI contains only a random token. The object reference, filename and MIME type stay in memory and are removed
 * when the lease is closed or expires. A lease intentionally does not survive process death.
 */
interface SecureMediaLease : AutoCloseable {
    /** False retires the source. A successful refresh must be a new signed grant, never a local deadline extension. */
    suspend fun maintainReadAuthorization(): Boolean = false

    val uri: Uri

    fun openInputStream(): InputStream

    override fun close()
}

interface SecureMediaLeaseManager {
    fun issue(request: SecureMediaLeaseRequest): SecureMediaLease

    /**
     * Revalidates a process-bound secure-media URI without minting or extending its lease.
     * Unknown, malformed, expired, or revoked capabilities must return false.
     */
    fun isReadAuthorized(uri: Uri): Boolean = false

    /** Same admission as [isReadAuthorized]; null means currently authorized, never a new grant.
     * A reason describes this check only. It cannot reconstruct why an already-retired token disappeared.
     */
    fun readAuthorizationRejection(uri: Uri): SecureMediaAuthenticatedReadRejection? =
        if (isReadAuthorized(uri)) null else SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE

    /** Immediately revokes every process-bound media capability issued for [scope]. */
    fun revokeScope(scope: String) = Unit

    /** Immediately revokes every process-bound media capability backed by [vaultId]. */
    fun revokeVault(vaultId: AccountVaultId) = Unit
}
