/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.SegmentedOuterFetchReceipt
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaAcquisition
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaAcquisitionRequest
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaResource
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaResourceAcquirer
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@ContributesBinding(RoomScope::class)
@Inject
class DefaultSegmentedProtectedMediaResourceAcquirer(
    private val matrixClient: MatrixClient,
    private val sessionStore: SessionStore,
    private val authorityService: MatrixProtectedMediaAuthorityService,
    private val leaseManager: AuthorizedSecureMediaLeaseManager,
) : SegmentedProtectedMediaResourceAcquirer {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun acquire(
        request: SegmentedProtectedMediaAcquisitionRequest,
    ): SegmentedProtectedMediaAcquisition {
        require(request.acquisitionId.isNotBlank())
        require(request.timeToLive.isPositive())
        val session = sessionStore.getSession(matrixClient.sessionId.value)
            ?: throw SecurityException("Matrix session is unavailable.")
        if (!session.isTokenValid || session.accessToken.isBlank()) {
            throw SecurityException("Matrix session token is unavailable.")
        }
        val fetched = fetchVerified(
            resource = request.resource,
            homeserverUrl = session.homeserverUrl,
            accessToken = session.accessToken,
        )
        val frame = try {
            decryptMatrixOuter(fetched.ciphertext, request.resource)
        } finally {
            fetched.ciphertext.fill(0)
        }
        try {
            return stageAndIssue(request, frame, fetched.receipt)
        } finally {
            frame.fill(0)
        }
    }

    private suspend fun fetchVerified(
        resource: SegmentedProtectedMediaResource,
        homeserverUrl: String,
        accessToken: String,
    ): VerifiedFetch {
        val locator = resource.locator
        val fetched = fetchVerifiedMatrixCiphertext(
            httpClient = httpClient,
            mxcUri = locator.mxcUri,
            homeserverUrl = homeserverUrl,
            accessToken = accessToken,
            expectedSha256Hex = locator.expectedOuterSha256Hex,
            expectedLength = locator.expectedOuterLength,
            maximumBytes = MAX_OUTER_BYTES,
        )
        return VerifiedFetch(
            ciphertext = fetched.ciphertext,
            receipt = SegmentedOuterFetchReceipt(
                mxcAuthority = fetched.mxcAuthority,
                homeserverAuthority = fetched.homeserverAuthority,
                actualOuterSha256Hex = locator.expectedOuterSha256Hex,
                actualOuterLength = fetched.ciphertext.size.toLong(),
                authenticated = true,
                redirectCount = 0,
            ),
        )
    }

    private fun decryptMatrixOuter(ciphertext: ByteArray, resource: SegmentedProtectedMediaResource): ByteArray {
        return decryptSegmentedMatrixOuter(ciphertext, resource.locator.encryptedFile)
    }

    private suspend fun stageAndIssue(
        request: SegmentedProtectedMediaAcquisitionRequest,
        frame: ByteArray,
        receipt: SegmentedOuterFetchReceipt,
    ): SegmentedProtectedMediaAcquisition {
        val resource = request.resource
        val inspection = authorityService.inspect(frame)
        // This is the established SecureMatrixMediaLoader mapping: the authenticated
        // YZMF policy tuple owns contentContractId/contentPolicyVersion; it is not the media schema version.
        requireSegmentedPolicyBinding(
            contentContractId = resource.contentContractId,
            contentPolicyVersion = resource.contentPolicyVersion,
            inspectedPolicyId = inspection.policyId,
            inspectedPolicyVersion = inspection.policyVersion,
        )
        if (
            inspection.contentId != resource.contentId ||
            inspection.kind != resource.protectedContentKind ||
            inspection.roomIdSha256Hex != resource.roomIdSha256Hex ||
            inspection.immutableContextSha256Hex != resource.immutableContextSha256Hex ||
            inspection.expectedPlaintextLength != resource.expectedPlaintextLength
        ) {
            throw SecurityException("Inner protected-media authority does not match the authenticated manifest resource.")
        }
        val vaultId = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
        val objectId = SecureObjectId("segmented-${sha256Hex((resource.resourceId + "\u0000" + request.acquisitionId).encodeToByteArray())}")
        val descriptor = SecureMediaLogicalResourceDescriptor(
            assetId = resource.assetId,
            mediaVersion = resource.contentPolicyVersion,
            roomIdSha256Hex = resource.roomIdSha256Hex,
            contentContractId = resource.contentContractId,
            resourceId = resource.resourceId,
            contentId = resource.contentId,
            kind = resource.protectedContentKind,
            index = resource.index,
            expectedPlaintextLength = resource.expectedPlaintextLength,
            immutableContextSha256Hex = resource.immutableContextSha256Hex,
            matrixLocatorSha256Hex = sha256Hex(resource.locator.mxcUri.encodeToByteArray()),
            outerFrameSha256Hex = sha256Hex(frame),
            outerFrameLength = frame.size.toLong(),
            vaultId = vaultId,
            objectId = objectId,
            purpose = SecureObjectPurpose.VIDEO,
        )
        val stagingStore = leaseManager as? SecureObjectStagingStore
            ?: throw SecurityException("Secure encrypted staging is unavailable.")
        var authority: SecureMediaReleaseAuthority? = null
        var staging: SecureObjectStagingTransaction? = null
        try {
            staging = stagingStore.stage(
                SecureObjectWriteRequest(
                    objectId = objectId,
                    vaultId = vaultId,
                    purpose = SecureObjectPurpose.VIDEO,
                    associatedData = descriptor.canonicalBytes(),
                    expectedPlaintextLength = resource.expectedPlaintextLength,
                )
            ) { encryptedStagingOutput ->
                authority = authorityService.decryptToEncryptedStaging(
                    frame = frame,
                    vaultId = vaultId,
                    accountIdHash = accountIdHash(matrixClient.sessionId.value),
                    plaintext = encryptedStagingOutput,
                )
            }
            val lease = leaseManager.issueAuthorized(
                SecureMediaAuthorizedLeaseRequest(
                    staging = staging,
                    logicalResource = descriptor,
                    releaseAuthority = requireNotNull(authority),
                    mimeType = request.mimeType,
                    displayName = request.displayName,
                    timeToLive = request.timeToLive,
                    allowedReadPurposes = setOf(SecureMediaReadPurpose.SEGMENTED_DECODER),
                    requireSeekableDescriptor = false,
                    deleteObjectOnRevoke = true,
                )
            )
            authority = null
            staging = null
            return SegmentedProtectedMediaAcquisition(lease, receipt)
        } catch (failure: Throwable) {
            authority?.close()
            val transaction = staging
            if (transaction != null) {
                val cleanup = withContext(NonCancellable) { transaction.abort() }
                if (!cleanup.complete) throw SecureMediaCleanupException(cleanup, failure)
            }
            throw failure
        }
    }

    private data class VerifiedFetch(
        val ciphertext: ByteArray,
        val receipt: SegmentedOuterFetchReceipt,
    )

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_OUTER_BYTES = 2_200_000L
        const val FETCH_BUFFER_BYTES = 64 * 1024
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

internal fun readVerifiedCiphertext(
    input: InputStream,
    expectedLength: Long,
    expectedSha256Hex: String,
    digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    chunkBytes: Int = 64 * 1024,
    onBufferAllocated: (ByteArray) -> Unit = {},
): ByteArray {
    require(expectedLength in 1L..Int.MAX_VALUE.toLong())
    require(chunkBytes > 0)
    val ciphertext = ByteArray(expectedLength.toInt())
    onBufferAllocated(ciphertext)
    var delivered = false
    try {
        var actualLength = 0
        while (actualLength < ciphertext.size) {
            val count = input.read(
                ciphertext,
                actualLength,
                minOf(chunkBytes, ciphertext.size - actualLength),
            )
            if (count < 0) break
            actualLength = Math.addExact(actualLength, count)
            digest.update(ciphertext, actualLength - count, count)
        }
        if (actualLength == ciphertext.size && input.read() >= 0) {
            throw SecurityException("Matrix media exceeded the authenticated byte bound.")
        }
        val actualHash = digest.digest().toHexString()
        if (actualLength.toLong() != expectedLength || actualHash != expectedSha256Hex) {
            throw SecurityException("Matrix media ciphertext receipt mismatched the protected descriptor.")
        }
        delivered = true
        return ciphertext
    } finally {
        if (!delivered) ciphertext.fill(0)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

internal fun requireSegmentedPolicyBinding(
    contentContractId: String,
    contentPolicyVersion: Long,
    inspectedPolicyId: String,
    inspectedPolicyVersion: Long,
) {
    if (inspectedPolicyId != contentContractId || inspectedPolicyVersion != contentPolicyVersion) {
        throw SecurityException("Inner protected-media policy does not match the authenticated event policy.")
    }
}

internal suspend fun Call.awaitResponseCancellable(): Response {
    val responseOwner = AtomicReference<Response?>()
    val response = suspendCancellableCoroutine<Response> { continuation ->
        continuation.invokeOnCancellation {
            cancel()
            responseOwner.getAndSet(null)?.close()
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    continuation.resumeWithException(failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    responseOwner.set(response)
                    continuation.resume(response) { _, cancelledResponse, _ ->
                        if (responseOwner.compareAndSet(cancelledResponse, null)) {
                            cancelledResponse.close()
                        }
                    }
                }
            }
        )
    }
    responseOwner.compareAndSet(response, null)
    return response
}

internal suspend fun <T : Any> Response.consumeCancellable(
    call: Call,
    onCancelledValue: (T) -> Unit = {},
    block: (Response) -> T,
): T {
    val responseOwner = AtomicReference<Response?>(this)
    val valueOwner = AtomicReference<T?>()
    val result = suspendCancellableCoroutine<T> { continuation ->
        continuation.invokeOnCancellation {
            call.cancel()
            responseOwner.getAndSet(null)?.close()
            valueOwner.getAndSet(null)?.let(onCancelledValue)
        }
        try {
            val value = use(block)
            responseOwner.compareAndSet(this, null)
            valueOwner.set(value)
            continuation.resume(value) { _, cancelledValue, _ ->
                if (valueOwner.compareAndSet(cancelledValue, null)) {
                    onCancelledValue(cancelledValue)
                }
            }
        } catch (failure: Throwable) {
            responseOwner.compareAndSet(this, null)
            continuation.resumeWithException(failure)
        }
    }
    valueOwner.compareAndSet(result, null)
    return result
}

internal fun decryptSegmentedMatrixOuter(
    ciphertext: ByteArray,
    encrypted: io.element.android.security.contentprotection.api.SegmentedMatrixEncryptedFile,
): ByteArray {
    require(encrypted.version == "v2" && encrypted.algorithm == "A256CTR")
    require(encrypted.keyType == "oct" && encrypted.extractable)
    require(encrypted.keyOperations == setOf("encrypt", "decrypt"))
    val key = try {
        Base64.getUrlDecoder().decode(encrypted.keyBase64Url)
    } catch (failure: IllegalArgumentException) {
        throw SecurityException("Matrix media key is invalid.", failure)
    }
    val iv = try {
        Base64.getDecoder().decode(encrypted.ivBase64)
    } catch (failure: IllegalArgumentException) {
        key.fill(0)
        throw SecurityException("Matrix media IV is invalid.", failure)
    }
    try {
        require(key.size == 32 && iv.size == 16)
        return Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }
    } finally {
        key.fill(0)
        iv.fill(0)
    }
}
