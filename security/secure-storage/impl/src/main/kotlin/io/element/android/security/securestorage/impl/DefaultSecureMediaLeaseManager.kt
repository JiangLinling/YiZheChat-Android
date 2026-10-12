/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLeaseManager
import io.element.android.security.securestorage.api.SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupItem
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupReport
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<SecureMediaLeaseManager>())
@ContributesBinding(AppScope::class, binding = binding<AuthorizedSecureMediaLeaseManager>())
@ContributesBinding(AppScope::class, binding = binding<io.element.android.security.securestorage.api.ExternalSecureMediaLeaseManager>())
class DefaultSecureMediaLeaseManager internal constructor(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val sensitiveFileStore: SensitiveFileStore,
    private val authorizationValidator: SecureMediaAuthorizationValidator = SecureMediaAuthorizationValidator.RejectAll,
    private val stagingStore: SecureObjectStagingStore? = null,
) : SecureMediaLeaseManager, AuthorizedSecureMediaLeaseManager, SecureObjectStagingStore,
    io.element.android.security.securestorage.api.ExternalSecureMediaLeaseManager {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @AppCoroutineScope appCoroutineScope: CoroutineScope,
        sensitiveFileStore: SensitiveFileStore,
        authorizationValidator: SecureMediaAuthorizationValidator,
        stagingStore: DefaultSecureObjectStagingStore,
    ) : this(
        context = context,
        appCoroutineScope = appCoroutineScope,
        sensitiveFileStore = sensitiveFileStore,
        authorizationValidator = authorizationValidator,
        stagingStore = stagingStore as SecureObjectStagingStore,
    )

    internal var leaseUriFactory: (String) -> Uri = { token ->
        Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.$SECURE_MEDIA_AUTHORITY_SUFFIX")
            .appendPath(SECURE_MEDIA_PATH_VERSION)
            .appendPath(token)
            .build()
    }
    private val scopeTokens = ConcurrentHashMap<String, MutableSet<String>>()
    private val cleanupFailureReceipts = CopyOnWriteArrayList<SecureObjectStagingCleanupReport>()
    private val cleanupFailureExceptions = CopyOnWriteArrayList<SecureMediaCleanupException>()
    private val externalIssuer by lazy {
        SecureMediaExternalReadIssuer(
            appCoroutineScope,
            sensitiveFileStore,
            this,
            authorizationValidator,
            context.applicationInfo.uid,
            sourceEntry = { uri ->
                check(readAuthorizationRejection(uri) == null) { "External source admission failed." }
                requireNotNull(SecureMediaLeaseRegistry.acquire(uri.pathSegments.last()))
            },
            issue = { request, observer -> issueAuthorizedInternal(request, observer) }
        )
    }

    override fun inspectExternalSource(uri: Uri) = externalIssuer.inspect(uri)

    override suspend fun prepareExternalRead(sourceUri: Uri, sourceOperationId: String,
        owner: io.element.android.security.securestorage.api.SecureTransientMediaOwner,
        recipient: io.element.android.security.securestorage.api.SecureMediaExternalRecipient,
    ) = externalIssuer.prepare(sourceUri, sourceOperationId, owner, recipient)

    override suspend fun issueExternalRead(plan: io.element.android.security.securestorage.api.SecureMediaExternalReadPlan) = externalIssuer.issue(plan)

    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = issueRegistered(
        request = request,
        authorizationGuard = request.isReadAuthorized,
        onCapabilityRevoked = {},
        cleanupTransactionId = "legacy-${request.reference.keyEnvelopeId.value}",
    )

    override suspend fun stage(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (java.io.OutputStream) -> Unit,
    ): SecureObjectStagingTransaction = requireNotNull(stagingStore) {
        "Secure encrypted staging is unavailable."
    }.stage(request, writePlaintext)

    override suspend fun issueAuthorized(request: SecureMediaAuthorizedLeaseRequest): AuthorizedSecureMediaLease =
        issueAuthorizedInternal(request, null)

    private suspend fun issueAuthorizedInternal(request: SecureMediaAuthorizedLeaseRequest,
        externalObserver: ExternalReadObserver?,
    ): AuthorizedSecureMediaLease {
        var committed: SecureObjectReference? = null
        var authorityConsumed = false
        try {
            require(request.timeToLive.isPositive())
            require(request.allowedReadPurposes.size == 1)
            requireLogicalBinding(request)
            val first = authorizationValidator.validate(
                request.releaseAuthority,
                request.logicalResource.contentId,
                request.logicalResource.immutableContextSha256Hex,
            ).requireVerified()
            val requestedDeadline = Math.addExact(
                first.time.elapsedRealtimeMillis,
                request.timeToLive.inWholeMilliseconds,
            )
            val processTtlDeadlineElapsedRealtimeMillis = minOf(
                requestedDeadline,
                request.maximumProcessDeadlineElapsedRealtimeMillis ?: requestedDeadline,
                first.authorization.authorizationDeadlineElapsedRealtimeMillis ?: requestedDeadline
            )
            require(processTtlDeadlineElapsedRealtimeMillis > first.time.elapsedRealtimeMillis)
            val externalBinding = first.authorization.externalReadBinding
            require((request.allowedReadPurposes.single() == SecureMediaReadPurpose.EXTERNAL_OPEN) == (externalBinding != null))
            if (externalBinding != null) {
                require(externalBinding.targetResourceSha256 == request.logicalResource.digestSha256Hex())
                require(externalBinding.owner == request.staging.request.transientMediaOwner)
                require(externalObserver != null && !first.authorization.localAuthorityId.isNullOrBlank())
                requireNotNull(first.authorization.authorizationDeadlineElapsedRealtimeMillis)
            }
            val second = authorizationValidator.validate(
                request.releaseAuthority,
                request.logicalResource.contentId,
                request.logicalResource.immutableContextSha256Hex,
                first.authorization.authorizationRevision,
            ).requireVerified()
            requireContinuingValidation(first, second, processTtlDeadlineElapsedRealtimeMillis)
            committed = request.staging.commit()
            requireFinalBinding(request, committed)
            val finalValidation = authorizationValidator.validate(
                request.releaseAuthority,
                request.logicalResource.contentId,
                request.logicalResource.immutableContextSha256Hex,
                first.authorization.authorizationRevision,
            ).requireVerified()
            requireContinuingValidation(first, finalValidation, processTtlDeadlineElapsedRealtimeMillis)
            val legacyRequest = SecureMediaLeaseRequest(
                reference = committed,
                mimeType = request.mimeType,
                displayName = request.displayName,
                timeToLive = if (externalBinding == null) {
                    request.timeToLive.inWholeMilliseconds.milliseconds
                } else {
                    (processTtlDeadlineElapsedRealtimeMillis - finalValidation.time.elapsedRealtimeMillis).milliseconds
                },
                revocationScope = first.authorization.revocationScope,
                requireSeekableDescriptor = request.requireSeekableDescriptor,
                deleteObjectOnRevoke = request.deleteObjectOnRevoke,
            )
            val lease = issueRegistered(
                request = legacyRequest,
                authorizationGuard = null,
                authorizedState = SecureMediaLeaseRegistry.AuthorizedState(
                    authority = request.releaseAuthority,
                    validator = authorizationValidator,
                    expectedContentId = request.logicalResource.contentId,
                    expectedImmutableContextSha256Hex = request.logicalResource.immutableContextSha256Hex,
                    authorization = first.authorization,
                    bootCount = first.time.bootCount,
                    processTtlStartElapsedRealtimeMillis = first.time.elapsedRealtimeMillis,
                    processTtlDeadlineElapsedRealtimeMillis = processTtlDeadlineElapsedRealtimeMillis,
                    logicalResourceDigestSha256Hex = request.logicalResource.digestSha256Hex(),
                    allowedPurpose = request.allowedReadPurposes.single(),
                    logicalResource = request.logicalResource,
                    transientOwner = request.staging.request.transientMediaOwner,
                ),
                onCapabilityRevoked = request.releaseAuthority::close,
                cleanupTransactionId = request.staging.transactionId,
                externalObserver = externalObserver,
            )
            authorityConsumed = true
            return lease as AuthorizedSecureMediaLease
        } catch (failure: Throwable) {
            if (!authorityConsumed) {
                try {
                    request.releaseAuthority.close()
                } catch (closeFailure: Throwable) {
                    if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                }
            }
            if (committed == null) {
                val report = withContext(NonCancellable) { request.staging.abort() }
                if (!report.complete) {
                    cleanupFailureReceipts += report
                    throw SecureMediaCleanupException(report, failure).also(cleanupFailureExceptions::add)
                }
            } else {
                var deletionFailure: Throwable? = null
                val removed = withContext(NonCancellable) {
                    try {
                        sensitiveFileStore.delete(committed)
                    } catch (deleteFailure: Throwable) {
                        deletionFailure = deleteFailure
                        false
                    }
                }
                if (!removed) {
                    val report = SecureObjectStagingCleanupReport(
                        request.staging.transactionId,
                        listOf(SecureObjectStagingCleanupItem("committed-object", false)),
                    )
                    cleanupFailureReceipts += report
                    throw SecureMediaCleanupException(report, failure).also { cleanupFailure ->
                        deletionFailure?.let(cleanupFailure::addSuppressed)
                        cleanupFailureExceptions += cleanupFailure
                    }
                }
            }
            throw failure
        }
    }

    override fun cleanupFailures(): List<SecureObjectStagingCleanupReport> = cleanupFailureReceipts.toList()

    override fun cleanupFailureExceptions(): List<SecureMediaCleanupException> = cleanupFailureExceptions.toList()

    private fun issueRegistered(
        request: SecureMediaLeaseRequest,
        authorizationGuard: (() -> Boolean)?,
        authorizedState: SecureMediaLeaseRegistry.AuthorizedState? = null,
        onCapabilityRevoked: () -> Unit,
        cleanupTransactionId: String,
        externalObserver: ExternalReadObserver? = null,
    ): SecureMediaLease {
        require(request.timeToLive.isPositive()) { "Secure media lease TTL must be positive." }
        SecureMediaLeaseRegistry.pruneExpired()
        val token = randomToken()
        val uri = leaseUriFactory(token)
        val expiresAtMillis = if (authorizedState == null) {
            System.currentTimeMillis() + request.timeToLive.inWholeMilliseconds
        } else {
            // Authorized entries never derive validity from the mutable wall clock.
            Long.MAX_VALUE
        }
        lateinit var entry: SecureMediaLeaseRegistry.Entry
        entry = SecureMediaLeaseRegistry.Entry(
            reference = request.reference,
            mimeType = request.mimeType,
            displayName = request.displayName,
            expiresAtMillis = expiresAtMillis,
            requireSeekableDescriptor = request.requireSeekableDescriptor,
            sensitiveFileStore = sensitiveFileStore,
            isReadAuthorized = authorizationGuard,
            authorizedState = authorizedState,
            externalObserver = externalObserver,
            onRevokedWithCause = { primaryCause ->
                externalObserver?.quiescent()
                var permissionFailure: Throwable? = null
                if (externalObserver != null) {
                    val identity = android.os.Binder.clearCallingIdentity()
                    try {
                        context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (failure: Throwable) {
                        permissionFailure = failure
                    } finally {
                        android.os.Binder.restoreCallingIdentity(identity)
                    }
                }
                var authorityCloseFailure: Throwable? = entry.renewedAuthorityCloseFailure
                try {
                    onCapabilityRevoked()
                } catch (failure: Throwable) {
                    if (authorityCloseFailure == null) authorityCloseFailure = failure else authorityCloseFailure.addSuppressed(failure)
                }
                request.revocationScope?.let { scope ->
                    scopeTokens[scope]?.let { tokens ->
                        tokens.remove(token)
                        if (tokens.isEmpty()) scopeTokens.remove(scope, tokens)
                    }
                }
                if (request.deleteObjectOnRevoke) {
                    appCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) {
                            var quiescenceJournalFailure: Throwable? = null
                            if (authorityCloseFailure == null) {
                                try {
                                    sensitiveFileStore.recordTransientMediaLeaseQuiescent(request.reference)
                                } catch (failure: Throwable) {
                                    quiescenceJournalFailure = failure
                                }
                            }
                            var deletionFailure: Throwable? = null
                            val removed = try {
                                sensitiveFileStore.delete(request.reference)
                            } catch (failure: Throwable) {
                                deletionFailure = failure
                                false
                            }
                            recordCleanupFailure(
                                transactionId = cleanupTransactionId,
                                primaryCause = primaryCause,
                                authorityCloseFailure = authorityCloseFailure,
                                objectRemoved = removed,
                                deletionFailure = deletionFailure,
                                quiescenceJournalFailure = quiescenceJournalFailure,
                                permissionFailure = permissionFailure,
                            )
                            val fact = if (externalObserver == null) {
                                null
                            } else {
                                runCatching {
                                sensitiveFileStore.transientMediaObjectDeletion(
                                    io.element.android.security.securestorage.api.SecureObjectIdentity(
                                        request.reference.objectId,
                                        request.reference.vaultId,
                                        request.reference.purpose
                                    )
                                )
                            }.getOrNull()
                            }
                            externalObserver?.cleanup(removed && authorityCloseFailure == null && quiescenceJournalFailure == null &&
                                permissionFailure == null && fact?.leaseQuiescentAtEpochMillis != null)
                        }
                    }
                } else if (authorityCloseFailure != null) {
                    recordCleanupFailure(
                        transactionId = cleanupTransactionId,
                        primaryCause = primaryCause,
                        authorityCloseFailure = authorityCloseFailure,
                        objectRemoved = null,
                        deletionFailure = null,
                    )
                }
            },
            onRevoked = {},
        )
        SecureMediaLeaseRegistry.register(token, entry)
        request.revocationScope?.let { scope ->
            scopeTokens.computeIfAbsent(scope) { ConcurrentHashMap.newKeySet() }.add(token)
        }
        appCoroutineScope.launch {
            delay(request.timeToLive.inWholeMilliseconds)
            SecureMediaLeaseRegistry.revoke(token, IllegalStateException("Secure media process TTL expired."))
        }
        authorizedState?.authorization?.externalReadBinding?.recipient?.uid?.let { recipientUid ->
            appCoroutineScope.launch {
                // Idle descriptors also lose access on real issuer expiry, policy or lock changes.
                while (SecureMediaLeaseRegistry.acquireForCaller(token, recipientUid) != null) delay(250)
            }
        }
        return if (authorizedState == null) {
            DefaultSecureMediaLease(uri, context, token)
        } else {
            DefaultAuthorizedSecureMediaLease(uri, context, token)
        }
    }

    private fun recordCleanupFailure(
        transactionId: String,
        primaryCause: Throwable,
        authorityCloseFailure: Throwable?,
        objectRemoved: Boolean?,
        deletionFailure: Throwable?,
        quiescenceJournalFailure: Throwable? = null,
        permissionFailure: Throwable? = null,
    ) {
        val items = buildList {
            if (permissionFailure != null) add(SecureObjectStagingCleanupItem("uri-access", false))
            if (authorityCloseFailure != null) add(SecureObjectStagingCleanupItem("release-authority", false))
            if (objectRemoved == false) add(SecureObjectStagingCleanupItem("revoked-object", false))
            if (quiescenceJournalFailure != null) add(SecureObjectStagingCleanupItem("lease-quiescence-journal", false))
        }
        if (items.isEmpty()) return
        val report = SecureObjectStagingCleanupReport(transactionId, items)
        val exception = SecureMediaCleanupException(report, primaryCause)
        authorityCloseFailure?.let(exception::addSuppressed)
        deletionFailure?.let(exception::addSuppressed)
        quiescenceJournalFailure?.let(exception::addSuppressed)
        cleanupFailureReceipts += report
        cleanupFailureExceptions += exception
    }

    override fun revokeScope(scope: String) {
        scopeTokens.remove(scope)?.toList().orEmpty().forEach { token ->
            SecureMediaLeaseRegistry.revoke(token, IllegalStateException("Secure media revocation scope was revoked."))
        }
    }

    override fun isReadAuthorized(uri: Uri): Boolean = readAuthorizationRejection(uri) == null

    override fun readAuthorizationRejection(uri: Uri): SecureMediaAuthenticatedReadRejection? {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.$SECURE_MEDIA_AUTHORITY_SUFFIX" ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != SECURE_MEDIA_PATH_VERSION ||
            uri.query != null || uri.fragment != null || !TOKEN_REGEX.matches(uri.pathSegments[1])) {
            return SecureMediaAuthenticatedReadRejection.BINDING_INVALID
        }
        var rejection: SecureMediaAuthenticatedReadRejection? = null
        SecureMediaLeaseRegistry.acquire(uri.pathSegments[1]) { rejection = it }
        return rejection
    }

    override fun revokeVault(vaultId: AccountVaultId) = SecureMediaLeaseRegistry.revokeVault(vaultId)

    private fun requireLogicalBinding(request: SecureMediaAuthorizedLeaseRequest) {
        val descriptor = request.logicalResource
        val write = request.staging.request
        require(write.vaultId == descriptor.vaultId)
        require(write.objectId == descriptor.objectId)
        require(write.purpose == descriptor.purpose)
        require(write.expectedPlaintextLength == descriptor.expectedPlaintextLength)
        require(request.staging.plaintextLength == descriptor.expectedPlaintextLength)
        require(sha256Hex(write.associatedData) == descriptor.digestSha256Hex())
    }

    private fun requireFinalBinding(request: SecureMediaAuthorizedLeaseRequest, reference: SecureObjectReference) {
        val descriptor = request.logicalResource
        require(reference.vaultId == descriptor.vaultId)
        require(reference.objectId == descriptor.objectId)
        require(reference.purpose == descriptor.purpose)
        require(reference.plaintextLength == descriptor.expectedPlaintextLength)
        require(reference.associatedDataSha256 == descriptor.digestSha256Hex())
    }

    private fun SecureMediaAuthorizationValidation.requireVerified(): SecureMediaAuthorizationValidation.Verified = when (this) {
        is SecureMediaAuthorizationValidation.Verified -> this
        is SecureMediaAuthorizationValidation.Rejected -> {
        timber.log.Timber.tag("YiZheSecureMedia").w("authority_rejected reason=%s", reason.name)
        throw SecurityException("Secure media authority rejected: $reason")
    }
    }

    private fun requireContinuingValidation(
        initial: SecureMediaAuthorizationValidation.Verified,
        continuing: SecureMediaAuthorizationValidation.Verified,
        processTtlDeadlineElapsedRealtimeMillis: Long,
    ) {
        require(continuing.authorization == initial.authorization) { "Secure media authority snapshot changed." }
        require(continuing.time.bootCount == initial.time.bootCount) { "Secure media boot binding changed." }
        require(continuing.time.elapsedRealtimeMillis >= initial.time.elapsedRealtimeMillis) {
            "Secure media monotonic clock moved backwards."
        }
        require(continuing.time.elapsedRealtimeMillis < processTtlDeadlineElapsedRealtimeMillis) {
            "Secure media process TTL expired."
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun randomToken(): String = ByteArray(TOKEN_LENGTH_BYTES)
        .also(SECURE_RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN_LENGTH_BYTES = 24
        val TOKEN_REGEX = Regex("^[0-9a-f]{${TOKEN_LENGTH_BYTES * 2}}$")
        val SECURE_RANDOM = SecureRandom()
    }
}

private class DefaultSecureMediaLease(
    override val uri: Uri,
    private val context: Context,
    private val token: String,
) : SecureMediaLease {
    private val closed = AtomicBoolean(false)

    override suspend fun maintainReadAuthorization(): Boolean =
        !closed.get() && SecureMediaLeaseRegistry.maintainReadAuthorization(token)

    override fun openInputStream(): InputStream {
        check(!closed.get()) { "Secure media lease is closed." }
        return context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Secure media is unavailable.")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SecureMediaLeaseRegistry.revoke(token, IllegalStateException("Secure media lease was closed."))
        }
    }
}

private class DefaultAuthorizedSecureMediaLease(
    override val uri: Uri,
    private val context: Context,
    private val token: String,
) : AuthorizedSecureMediaLease {
    private val closed = AtomicBoolean(false)

    override suspend fun maintainReadAuthorization(): Boolean =
        !closed.get() && SecureMediaLeaseRegistry.maintainReadAuthorization(token)

    override fun openInputStream(): InputStream {
        check(!closed.get()) { "Secure media lease is closed." }
        return context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Secure media is unavailable.")
    }

    override suspend fun copyAuthenticatedRangeInto(
        request: SecureMediaAuthenticatedReadRequest,
        destination: ByteArray,
        destinationOffset: Int,
    ): SecureMediaAuthenticatedReadResult {
        if (closed.get()) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.CLOSED)
        }
        return SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(token, request, destination, destinationOffset)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SecureMediaLeaseRegistry.revoke(token, IllegalStateException("Secure media authorized lease was closed."))
        }
    }
}

internal object SecureMediaLeaseRegistry {
    data class AuthorizedState(
        val authority: SecureMediaReleaseAuthority,
        val validator: SecureMediaAuthorizationValidator,
        val expectedContentId: String,
        val expectedImmutableContextSha256Hex: String,
        val authorization: SecureMediaVerifiedAuthorization,
        val bootCount: Long,
        val processTtlStartElapsedRealtimeMillis: Long,
        val processTtlDeadlineElapsedRealtimeMillis: Long,
        val logicalResourceDigestSha256Hex: String,
        val allowedPurpose: SecureMediaReadPurpose,
        val logicalResource: io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor? = null,
        val transientOwner: io.element.android.security.securestorage.api.SecureTransientMediaOwner? = null,
    )

    data class Entry(
        val reference: SecureObjectReference,
        val mimeType: String?,
        val displayName: String?,
        val expiresAtMillis: Long,
        val requireSeekableDescriptor: Boolean,
        val sensitiveFileStore: SensitiveFileStore,
        val isReadAuthorized: (() -> Boolean)? = null,
        val onRevoked: () -> Unit,
        @Volatile var authorizedState: AuthorizedState? = null,
        val onRevokedWithCause: ((Throwable) -> Unit)? = null,
        val externalObserver: ExternalReadObserver? = null,
    ) {
        internal val originalAuthority = authorizedState?.authority
        internal val renewalMutex = Mutex()
        internal val stateLock = ReentrantLock()
        internal var revoked: Boolean = false
        internal var revision: Long = 0L
        internal var readInFlight: Boolean = false
        internal var cleanupClaimed: Boolean = false
        internal var revocationCause: Throwable? = null
        internal var revocationSignalsSent: Boolean = false
        internal var renewedAuthorityCloseFailure: Throwable? = null
        internal val providerReads = mutableSetOf<ProviderRead>()
    }

    private data class CleanupClaim(
        val entry: Entry,
        val cause: Throwable,
    )

    private data class AuthorizationFailure(
        val reason: SecureMediaAuthenticatedReadRejection,
        val cause: Throwable? = null,
    )

    private sealed interface Admission {
        data class Admitted(val entry: Entry, val revision: Long) : Admission
        data class Rejected(
            val reason: SecureMediaAuthenticatedReadRejection,
            val cleanupClaim: CleanupClaim? = null,
        ) : Admission
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Keeps cleanup behind provider-owned buffers; closing a guard means its buffers were wiped. */
    internal class ProviderRead internal constructor(val entry: Entry, internal val cancel: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val claim = entry.stateLock.withLock {
                entry.providerReads.remove(this)
                entry.revocationCause?.let { CleanupClaim(entry, it) }
            }
            claim?.let(::runCleanup)
        }
    }

    fun beginProviderRead(token: String, callerUid: Int? = null, onRevoke: () -> Unit = {}): ProviderRead? {
        val entry = if (callerUid == null) acquire(token) else acquireForCaller(token, callerUid)
        entry ?: return null
        if (entry.authorizedState?.authorization?.externalReadBinding?.recipient?.uid?.let { it != callerUid } == true) return null
        return entry.stateLock.withLock {
            if (entry.revoked || entries[token] !== entry) {
                null
            } else {
                ProviderRead(entry, onRevoke).also { entry.providerReads += it }
            }
        }
    }

    fun register(token: String, entry: Entry) {
        check(entries.putIfAbsent(token, entry) == null) { "Secure media token collision." }
    }

    suspend fun maintainReadAuthorization(token: String): Boolean {
        val entry = acquire(token) ?: return false
        return entry.renewalMutex.withLock {
            val current = acquire(token) ?: return@withLock false
            if (current !== entry) return@withLock false
            val original = entry.stateLock.withLock { entry.authorizedState } ?: return@withLock true
            if (original.allowedPurpose != SecureMediaReadPurpose.LEASE_STREAM ||
                original.logicalResource?.kind != "ATTACHMENT" || original.authorization.externalReadBinding != null) return@withLock true
            try {
                original.validator.withReadAdmission(original.authority, original.transientOwner) {
                    renewAndInstallReadAuthority(token, entry, original)
                }
            } catch (cancelled: CancellationException) {
                revoke(token, cancelled)
                throw cancelled
            } catch (failure: Exception) {
                revoke(token, failure)
                false
            }
        }
    }

    private suspend fun renewAndInstallReadAuthority(token: String, entry: Entry, original: AuthorizedState): Boolean {
        var candidate: SecureMediaReleaseAuthority? = null
        var installed = false
        return try {
            candidate = original.validator.renewReadAuthority(original.authority)
            val freshAuthority = candidate ?: return acquire(token) === entry
            check(freshAuthority !== original.authority)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (acquire(token) !== entry) return false
                val replacement = original.validator.validate(freshAuthority, original.expectedContentId,
                    original.expectedImmutableContextSha256Hex, original.authorization.authorizationRevision)
                check(replacement is SecureMediaAuthorizationValidation.Verified)
                val authorization = replacement.authorization
                check(authorization.copy(authorityId = original.authorization.authorityId,
                    authorizationDeadlineEpochMillis = original.authorization.authorizationDeadlineEpochMillis) == original.authorization)
                check(authorization.authorityId != original.authorization.authorityId &&
                    authorization.authorizationDeadlineEpochMillis > original.authorization.authorizationDeadlineEpochMillis)
                val freshState = original.copy(authority = freshAuthority, authorization = authorization)
                val changed = entry.stateLock.withLock {
                    check(!entry.revoked && entries[token] === entry && entry.authorizedState === original)
                    check(validateAuthorizedState(original) == null && validateAuthorizedState(freshState) == null)
                    if (entry.readInFlight) false else {
                        // Keep the original process TTL, resource, URI, purpose and provider revision.
                        entry.authorizedState = freshState
                        installed = true
                        true
                    }
                }
                if (changed) {
                    original.authority.close()
                    return acquire(token) === entry
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (cancelled: CancellationException) {
            revoke(token, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            Timber.w("Secure media fresh admission failed; source retired.")
            revoke(token, failure)
            false
        } finally {
            if (!installed) candidate?.close()
        }
    }

    fun acquireForCaller(token: String, callerUid: Int,
        onRejected: (SecureMediaAuthenticatedReadRejection) -> Unit = {},
    ): Entry? {
        val target = entries[token]?.authorizedState?.authorization?.externalReadBinding?.recipient
        if (target != null && target.uid != callerUid) {
            onRejected(SecureMediaAuthenticatedReadRejection.OWNER_MISMATCH)
            return null
        }
        return acquire(token, onRejected)
    }

    fun acquire(
        token: String,
        onRejected: (SecureMediaAuthenticatedReadRejection) -> Unit = {},
    ): Entry? {
        val entry = entries[token] ?: run {
            onRejected(SecureMediaAuthenticatedReadRejection.REVOKED)
            return null
        }
        val legacyAuthorized = if (entry.authorizedState == null) {
            try {
                entry.isReadAuthorized?.invoke() != false
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
        if (!legacyAuthorized) {
            try {
                onRejected(SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE)
            } finally {
                revoke(token, SecurityException("Secure media authorization guard rejected the lease."))
            }
            return null
        }
        var cleanupClaim: CleanupClaim? = null
        var rejection: SecureMediaAuthenticatedReadRejection? = null
        val acquired = entry.stateLock.withLock {
            if (entry.revoked) {
                rejection = SecureMediaAuthenticatedReadRejection.REVOKED
                false
            } else if (entry.authorizedState == null && entry.expiresAtMillis <= System.currentTimeMillis()) {
                rejection = SecureMediaAuthenticatedReadRejection.EXPIRED
                cleanupClaim = markRevokedLocked(token, entry, IllegalStateException("Secure media lease expired."))
                false
            } else if (entry.authorizedState != null) {
                val failure = validateAuthorizedState(requireNotNull(entry.authorizedState))
                if (failure != null) {
                    rejection = failure.reason
                    cleanupClaim = markRevokedLocked(
                        token,
                        entry,
                        failure.cause ?: SecurityException("Secure media authority rejected acquire: ${failure.reason}"),
                    )
                    false
                } else {
                    true
                }
            } else {
                true
            }
        }
        try {
            rejection?.let(onRejected)
        } finally {
            cleanupClaim?.let(::runCleanup)
        }
        return entry.takeIf { acquired }
    }

    fun revoke(token: String, cause: Throwable = IllegalStateException("Secure media lease was revoked.")) {
        val entry = entries[token] ?: return
        val cleanupClaim = entry.stateLock.withLock { markRevokedLocked(token, entry, cause) }
        cleanupClaim?.let(::runCleanup)
    }

    fun pruneExpired() {
        val now = System.currentTimeMillis()
        entries.entries
            .filter { it.value.authorizedState == null && it.value.expiresAtMillis <= now }
            .forEach { revoke(it.key, IllegalStateException("Secure media lease expired.")) }
    }

    fun revokeVault(vaultId: AccountVaultId) {
        entries.filterValues { it.reference.vaultId == vaultId }.keys.toList().forEach { token ->
            revoke(token, IllegalStateException("Secure media vault was revoked."))
        }
    }

    suspend fun copyAuthenticatedRangeInto(
        token: String,
        request: SecureMediaAuthenticatedReadRequest,
        destination: ByteArray,
        destinationOffset: Int,
    ): SecureMediaAuthenticatedReadResult {
        if (!validRangeArguments(request, destination, destinationOffset)) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RANGE_INVALID)
        }
        val admission = beginAuthenticatedRead(token, request)
        if (admission is Admission.Rejected) {
            admission.cleanupClaim?.let(::runCleanup)
            return SecureMediaAuthenticatedReadResult.Rejected(admission.reason)
        }
        admission as Admission.Admitted
        if (request.byteCount == 0) {
            return try {
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                finishAuthenticatedRead(
                    token = token,
                    entry = admission.entry,
                    admittedRevision = admission.revision,
                    request = request,
                    authenticatedByteCount = 0,
                    source = EMPTY_BYTES,
                    destination = destination,
                    destinationOffset = destinationOffset,
                    job = coroutineContext[Job],
                )
            } finally {
                releaseReadSlot(admission.entry)
            }
        }

        val source = try {
            ByteArray(request.byteCount)
        } catch (failure: Throwable) {
            releaseReadSlot(admission.entry)
            throw failure
        }
        try {
            val copied = admission.entry.sensitiveFileStore.readRangeInto(
                reference = admission.entry.reference,
                offset = request.offset,
                byteCount = request.byteCount,
                destination = source,
                destinationOffset = 0,
            )
            if (copied !in 0..request.byteCount) {
                return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.STORAGE_UNAVAILABLE)
            }
            val coroutineContext = currentCoroutineContext()
            coroutineContext.ensureActive()
            val job = coroutineContext[Job]
            return finishAuthenticatedRead(
                token = token,
                entry = admission.entry,
                admittedRevision = admission.revision,
                request = request,
                authenticatedByteCount = copied,
                source = source,
                destination = destination,
                destinationOffset = destinationOffset,
                job = job,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecureObjectNotFoundException) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.STORAGE_NOT_FOUND)
        } catch (_: SecureObjectAuthenticationException) {
            return SecureMediaAuthenticatedReadResult.Rejected(
                SecureMediaAuthenticatedReadRejection.STORAGE_AUTHENTICATION_FAILED
            )
        } catch (_: SecureStorageKeyUnavailableException) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.STORAGE_KEY_UNAVAILABLE)
        } catch (_: SecureVaultDestroyedException) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.VAULT_DESTROYED)
        } catch (_: SecureStorageException) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            return SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.STORAGE_UNAVAILABLE)
        } finally {
            source.fill(0)
            releaseReadSlot(admission.entry)
        }
    }

    private fun beginAuthenticatedRead(
        token: String,
        request: SecureMediaAuthenticatedReadRequest,
    ): Admission {
        val entry = entries[token]
            ?: return Admission.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
        return entry.stateLock.withLock {
            val authorized = entry.authorizedState
                ?: return@withLock Admission.Rejected(SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE)
            when {
                entry.revoked -> Admission.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
                entry.readInFlight -> Admission.Rejected(SecureMediaAuthenticatedReadRejection.BUSY)
                request.purpose != authorized.allowedPurpose -> {
                    Admission.Rejected(SecureMediaAuthenticatedReadRejection.PURPOSE_MISMATCH)
                }
                authorized.authorization.externalReadBinding?.recipient?.uid?.let { it != request.callerUid } == true -> {
                    Admission.Rejected(SecureMediaAuthenticatedReadRejection.OWNER_MISMATCH)
                }
                authorized.logicalResourceDigestSha256Hex != entry.reference.associatedDataSha256 -> {
                    val claim = markRevokedLocked(
                        token,
                        entry,
                        SecurityException("Secure media logical resource binding changed."),
                    )
                    Admission.Rejected(SecureMediaAuthenticatedReadRejection.RESOURCE_MISMATCH, claim)
                }
                !validObjectRange(entry.reference.plaintextLength, request.offset, request.byteCount) -> {
                    Admission.Rejected(SecureMediaAuthenticatedReadRejection.RANGE_INVALID)
                }
                else -> {
                    val failure = validateAuthorizedState(authorized)
                    if (failure != null) {
                        val claim = markRevokedLocked(
                            token,
                            entry,
                            failure.cause ?: SecurityException("Secure media authority rejected read: ${failure.reason}"),
                        )
                        Admission.Rejected(failure.reason, claim)
                    } else {
                        entry.readInFlight = true
                        Admission.Admitted(entry, entry.revision)
                    }
                }
            }
        }
    }

    private fun finishAuthenticatedRead(
        token: String,
        entry: Entry,
        admittedRevision: Long,
        request: SecureMediaAuthenticatedReadRequest,
        authenticatedByteCount: Int,
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int,
        job: Job?,
    ): SecureMediaAuthenticatedReadResult {
        var cleanupClaim: CleanupClaim? = null
        val result = entry.stateLock.withLock {
            val authorized = entry.authorizedState
                ?: return@withLock SecureMediaAuthenticatedReadResult.Rejected(
                    SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE
                )
            when {
                entry.revoked || entries[token] !== entry -> {
                    SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
                }
                entry.revision != admittedRevision -> {
                    SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.REVOKED)
                }
                request.purpose != authorized.allowedPurpose -> {
                    SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.PURPOSE_MISMATCH)
                }
                authorized.authorization.externalReadBinding?.recipient?.uid?.let { it != request.callerUid } == true -> {
                    SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.OWNER_MISMATCH)
                }
                authorized.logicalResourceDigestSha256Hex != entry.reference.associatedDataSha256 -> {
                    cleanupClaim = markRevokedLocked(
                        token,
                        entry,
                        SecurityException("Secure media logical resource binding changed before delivery."),
                    )
                    SecureMediaAuthenticatedReadResult.Rejected(SecureMediaAuthenticatedReadRejection.RESOURCE_MISMATCH)
                }
                job?.isActive == false -> throw CancellationException("Secure media read was cancelled before delivery.")
                else -> {
                    val failure = validateAuthorizedState(authorized)
                    if (failure != null) {
                        cleanupClaim = markRevokedLocked(
                            token,
                            entry,
                            failure.cause ?: SecurityException(
                                "Secure media authority rejected final delivery: ${failure.reason}"
                            ),
                        )
                        SecureMediaAuthenticatedReadResult.Rejected(failure.reason)
                    } else {
                        // This synchronous copy while holding the entry lock is the delivery linearization point.
                        source.copyInto(destination, destinationOffset, 0, authenticatedByteCount)
                        entry.externalObserver?.copied(authenticatedByteCount)
                        SecureMediaAuthenticatedReadResult.Copied(authenticatedByteCount)
                    }
                }
            }
        }
        cleanupClaim?.let(::runCleanup)
        return result
    }

    private fun validateAuthorizedState(state: AuthorizedState): AuthorizationFailure? {
        val validation = try {
            state.validator.validate(
                authority = state.authority,
                expectedContentId = state.expectedContentId,
                expectedImmutableContextSha256Hex = state.expectedImmutableContextSha256Hex,
                expectedAuthorizationRevision = state.authorization.authorizationRevision,
            )
        } catch (failure: Throwable) {
            return AuthorizationFailure(SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE, failure)
        }
        return when (validation) {
            is SecureMediaAuthorizationValidation.Rejected -> {
                Timber.w("Secure media lease rejected stage=authorization_validator reason=%s", validation.reason)
                AuthorizationFailure(validation.reason.toReadRejection())
            }
            is SecureMediaAuthorizationValidation.Verified -> when {
                validation.authorization != state.authorization -> {
                    if (validation.authorization.authorizationRevision != state.authorization.authorizationRevision) {
                        AuthorizationFailure(SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH)
                    } else {
                        AuthorizationFailure(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
                    }
                }
                validation.time.bootCount != state.bootCount -> {
                    AuthorizationFailure(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
                }
                validation.time.elapsedRealtimeMillis < state.processTtlStartElapsedRealtimeMillis -> {
                    AuthorizationFailure(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
                }
                validation.time.elapsedRealtimeMillis >= state.processTtlDeadlineElapsedRealtimeMillis -> {
                    Timber.w("Secure media lease rejected stage=process_ttl reason=EXPIRED")
                    AuthorizationFailure(SecureMediaAuthenticatedReadRejection.EXPIRED)
                }
                else -> null
            }
        }
    }

    private fun SecureMediaAuthorizationRejection.toReadRejection(): SecureMediaAuthenticatedReadRejection = when (this) {
        SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE -> SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE
        SecureMediaAuthorizationRejection.OWNER_MISMATCH -> SecureMediaAuthenticatedReadRejection.OWNER_MISMATCH
        SecureMediaAuthorizationRejection.RELEASE_MISMATCH -> SecureMediaAuthenticatedReadRejection.RELEASE_MISMATCH
        SecureMediaAuthorizationRejection.EXPIRED -> SecureMediaAuthenticatedReadRejection.EXPIRED
        SecureMediaAuthorizationRejection.BINDING_INVALID -> SecureMediaAuthenticatedReadRejection.BINDING_INVALID
        SecureMediaAuthorizationRejection.CLOSED -> SecureMediaAuthenticatedReadRejection.CLOSED
        SecureMediaAuthorizationRejection.PREVIEW_DENIED -> SecureMediaAuthenticatedReadRejection.PREVIEW_DENIED
        SecureMediaAuthorizationRejection.REVISION_MISMATCH -> SecureMediaAuthenticatedReadRejection.REVISION_MISMATCH
    }

    private fun markRevokedLocked(token: String, entry: Entry, cause: Throwable): CleanupClaim? {
        if (entry.revoked) return null
        entry.revoked = true
        entry.revocationCause = cause
        if (entry.revision != Long.MAX_VALUE) entry.revision += 1L
        entries.remove(token, entry)
        return CleanupClaim(entry, cause)
    }

    private fun runCleanup(claim: CleanupClaim) {
        val stopReaders = claim.entry.stateLock.withLock {
            if (claim.entry.revocationSignalsSent) {
                emptyList()
            } else {
                claim.entry.revocationSignalsSent = true
                claim.entry.providerReads.map { it.cancel }
            }
        }
        stopReaders.forEach { stop ->
            try {
                stop()
            } catch (failure: Throwable) {
                if (failure !== claim.cause) claim.cause.addSuppressed(failure)
            }
        }
        val ready = claim.entry.stateLock.withLock {
            if (claim.entry.cleanupClaimed || claim.entry.readInFlight || claim.entry.providerReads.isNotEmpty()) {
                false
            } else {
                claim.entry.cleanupClaimed = true
                true
            }
        }
        if (!ready) return
        try {
            claim.entry.authorizedState?.authority?.takeUnless { it === claim.entry.originalAuthority }?.close()
        } catch (failure: Throwable) {
            claim.entry.renewedAuthorityCloseFailure = failure
            if (failure !== claim.cause) claim.cause.addSuppressed(failure)
        }
        val callback = claim.entry.onRevokedWithCause
        if (callback != null) {
            callback(claim.cause)
        } else {
            claim.entry.onRevoked()
        }
    }

    private fun releaseReadSlot(entry: Entry) {
        val claim = entry.stateLock.withLock {
            entry.readInFlight = false
            entry.revocationCause?.let { CleanupClaim(entry, it) }
        }
        claim?.let(::runCleanup)
    }

    private fun validRangeArguments(
        request: SecureMediaAuthenticatedReadRequest,
        destination: ByteArray,
        destinationOffset: Int,
    ): Boolean = request.offset >= 0L &&
        request.byteCount in 0..SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES &&
        destinationOffset >= 0 &&
        destinationOffset <= destination.size - request.byteCount

    private fun validObjectRange(plaintextLength: Long, offset: Long, byteCount: Int): Boolean =
        offset <= plaintextLength && byteCount.toLong() <= plaintextLength - offset

    private val EMPTY_BYTES = ByteArray(0)
}

internal const val SECURE_MEDIA_AUTHORITY_SUFFIX = "securemedia"
internal const val SECURE_MEDIA_PATH_VERSION = "v1"
