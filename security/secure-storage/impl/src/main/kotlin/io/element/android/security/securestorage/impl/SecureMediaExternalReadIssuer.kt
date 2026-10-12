/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import android.net.Uri
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaExternalReadBinding
import io.element.android.security.securestorage.api.SecureMediaExternalReadLease
import io.element.android.security.securestorage.api.SecureMediaExternalReadPlan
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import io.element.android.security.securestorage.api.SecureMediaExternalSource
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureTransientMediaObjectRecord
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** Helper of the existing lease manager, not a second capability registry or durable coordinator. */
internal class SecureMediaExternalReadIssuer(
    private val scope: CoroutineScope,
    private val store: SensitiveFileStore,
    private val stagingStore: SecureObjectStagingStore,
    private val validator: SecureMediaAuthorizationValidator,
    private val hostUid: Int,
    private val sourceEntry: (Uri) -> SecureMediaLeaseRegistry.Entry,
    private val issue: suspend (SecureMediaAuthorizedLeaseRequest, ExternalReadObserver) -> AuthorizedSecureMediaLease,
) {
    private val issuer = Any()

    fun inspect(uri: Uri): SecureMediaExternalSource {
        val source = sourceEntry(uri)
        val state = requireSource(source)
        return SecureMediaExternalSource(
            requireNotNull(state.transientOwner),
            requireNotNull(state.logicalResource),
            state.authorization.authorizationDeadlineEpochMillis,
            state.processTtlDeadlineElapsedRealtimeMillis
        )
    }

    suspend fun prepare(uri: Uri, sourceOperationId: String, owner: SecureTransientMediaOwner,
        recipient: SecureMediaExternalRecipient): SecureMediaExternalReadPlan {
        require(recipient.uid != hostUid) { "External recipient must be outside the host UID." }
        val source = sourceEntry(uri)
        val state = requireSource(source)
        val originalOwner = requireNotNull(state.transientOwner)
        require(sourceOperationId == originalOwner.operationId && owner.copy(operationId = originalOwner.operationId) == originalOwner)
        val original = requireNotNull(state.logicalResource)
        val target = original.copy(
            objectId = SecureObjectId("external-${UUID.randomUUID()}"),
            resourceId = "external-${owner.operationId}-${recipient.uid}",
            purpose = SecureObjectPurpose.SHARE_STAGING
        )
        val binding = SecureMediaExternalReadBinding(
            owner,
            sourceOperationId,
            original.contentContractId,
            original.mediaVersion,
            original.digestSha256Hex(),
            target.digestSha256Hex(),
            recipient
        )
        val child = validator.deriveExternalReadAuthority(state.authority, binding)
            ?: throw SecurityException("Issuer/export evidence for external read is unavailable.")
        val request = SecureObjectWriteRequest(
            target.objectId,
            target.vaultId,
            target.purpose,
            target.canonicalBytes(),
            target.expectedPlaintextLength,
            owner
        )
        try {
            store.registerTransientMediaObject(request)
            val record = requireNotNull(store.transientMediaObjectRecord(SecureObjectIdentity(target.objectId, target.vaultId, target.purpose)))
            require(record.owner == owner)
            return Plan(issuer, uri, source, state, target, child, request, binding, record)
        } catch (failure: Throwable) {
            runCatching { child.close() }.onFailure { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }

    suspend fun issue(supplied: SecureMediaExternalReadPlan): SecureMediaExternalReadLease {
        val plan = supplied as? Plan ?: throw SecurityException("External read plan was not issued here.")
        require(plan.issuer === issuer && plan.consumed.compareAndSet(false, true)) { "External read plan is already consumed." }
        var transaction: SecureObjectStagingTransaction? = null
        try {
            check(!plan.cancelled.get())
            val parent = sourceEntry(plan.sourceUri)
            require(parent === plan.source)
            validateChild(plan)
            val reader = requireNotNull(SecureMediaLeaseRegistry.beginProviderRead(plan.sourceUri.pathSegments.last()))
            transaction = reader.use {
                stagingStore.stage(plan.request) { output ->
                    // Source authentication completes before an encrypted target can be committed or published.
                    store.read(parent.reference) { }
                    store.read(parent.reference) { input ->
                        val buffer = ByteArray(SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES)
                        try {
                            while (true) {
                                check(!plan.cancelled.get())
                                require(sourceEntry(plan.sourceUri) === parent)
                                validateChild(plan)
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(sourceEntry(plan.sourceUri) === parent)
                                validateChild(plan)
                                output.write(buffer, 0, count)
                                buffer.fill(0, toIndex = count)
                            }
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
            }
            val validation = validateChild(plan)
            val remaining = plan.sourceState.processTtlDeadlineElapsedRealtimeMillis - validation.time.elapsedRealtimeMillis
            check(remaining > 0 && !plan.cancelled.get())
            val secure = issue(SecureMediaAuthorizedLeaseRequest(
                transaction,
                plan.target,
                plan.child,
                plan.source.mimeType,
                plan.source.displayName,
                remaining.milliseconds,
                allowedReadPurposes = setOf(SecureMediaReadPurpose.EXTERNAL_OPEN),
                requireSeekableDescriptor = true,
                maximumProcessDeadlineElapsedRealtimeMillis = plan.sourceState.processTtlDeadlineElapsedRealtimeMillis
            ), plan.observer)
            transaction = null
            val lease = Lease(secure, plan)
            plan.lease = lease
            if (plan.cancelled.get()) lease.close()
            return lease
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val report = runCatching { transaction?.abort() }.onFailure { if (it !== failure) failure.addSuppressed(it) }.getOrNull()
                val authorityClosed = runCatching { plan.child.close() }.onFailure { if (it !== failure) failure.addSuppressed(it) }.isSuccess
                plan.observer.quiescent()
                val deleted = runCatching { store.discardUnissuedTransientMediaObject(plan.objectRecord) }
                    .onFailure { if (it !== failure) failure.addSuppressed(it) }.getOrNull()
                plan.observer.cleanup(authorityClosed && report?.complete != false &&
                    (failure as? SecureMediaCleanupException)?.cleanupReport?.complete != false &&
                    failure.suppressed.isEmpty() && deleted?.leaseQuiescentAtEpochMillis != null)
            }
            throw failure
        }
    }

    private fun validateChild(plan: Plan): SecureMediaAuthorizationValidation.Verified {
        val validation = validator.validate(
            plan.child,
            plan.target.contentId,
            plan.target.immutableContextSha256Hex,
            plan.sourceState.authorization.authorizationRevision
        )
        require(validation is SecureMediaAuthorizationValidation.Verified && validation.authorization.externalReadBinding == plan.binding)
        require(
            validation.time.bootCount == plan.sourceState.bootCount &&
            validation.time.elapsedRealtimeMillis >= plan.sourceState.processTtlStartElapsedRealtimeMillis &&
            validation.time.elapsedRealtimeMillis < plan.sourceState.processTtlDeadlineElapsedRealtimeMillis
        )
        return validation
    }

    private fun requireSource(entry: SecureMediaLeaseRegistry.Entry): SecureMediaLeaseRegistry.AuthorizedState {
        val state = requireNotNull(entry.authorizedState) { "Legacy source has no issuer-owned release authority." }
        require(state.allowedPurpose == SecureMediaReadPurpose.LEASE_STREAM && state.authorization.externalReadBinding == null)
        requireNotNull(state.logicalResource)
        requireNotNull(state.transientOwner)
        return state
    }

    private inner class Plan(
        val issuer: Any, val sourceUri: Uri, val source: SecureMediaLeaseRegistry.Entry,
        val sourceState: SecureMediaLeaseRegistry.AuthorizedState, val target: SecureMediaLogicalResourceDescriptor,
        val child: SecureMediaReleaseAuthority, val request: SecureObjectWriteRequest,
        override val binding: SecureMediaExternalReadBinding, override val objectRecord: SecureTransientMediaObjectRecord,
    ) : SecureMediaExternalReadPlan {
        val observer = ExternalReadObserver()
        override val observation = observer.state
        val consumed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        @Volatile var lease: Lease? = null
        override fun close() {
            cancelled.set(true)
            lease?.let {
                it.close()
                return
            }
            if (consumed.compareAndSet(false, true)) {
                scope.launch {
                withContext(NonCancellable) {
                    val authorityClosed = runCatching { child.close() }.isSuccess
                    observer.quiescent()
                    val deleted = runCatching { store.discardUnissuedTransientMediaObject(objectRecord) }.getOrNull()
                    observer.cleanup(authorityClosed && deleted?.leaseQuiescentAtEpochMillis != null)
                }
            }
            }
        }
    }

    private class Lease(private val secure: AuthorizedSecureMediaLease, private val plan: Plan) : SecureMediaExternalReadLease {
        override val uri = secure.uri
        override val binding = plan.binding
        override val objectRecord = plan.objectRecord
        override val observation = plan.observation
        override fun openInputStream() = secure.openInputStream()
        override fun close() = secure.close()
    }
}
