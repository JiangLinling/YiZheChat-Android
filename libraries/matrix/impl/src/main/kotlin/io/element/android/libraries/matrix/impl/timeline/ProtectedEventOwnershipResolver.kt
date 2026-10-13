/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext

/** Routing metadata only. This resolver never grants plaintext, a KMS lease or authority receipts. */
object ProtectedEventOwnershipResolver {
    enum class Ownership { OWN_LOCAL_SEND, OWN_OTHER_DEVICE, TRUE_REMOTE_RECEIVE, UNKNOWN }
    enum class Reason { LOCAL_ORIGIN_BOUND, OTHER_INSTALLATION, REMOTE_SENDER, IDENTITY_UNRESOLVED, ORIGIN_CONFLICT }

    data class Input(
        val accountIdHash: String,
        val matrixDeviceIdHash: String,
        val installationId: String,
        val senderAccountIdHash: String,
        val senderInstallationId: String?,
        val roomIdHash: String,
        val eventId: String?,
        val transactionId: String?,
        val contentId: String,
        val contentKind: ContentKind,
        val carrierSha256: String,
        val generation: Long? = null,
    )

    /** Loaded from the encrypted local producer journal or a verified server origin response. */
    data class Origin(
        val operationId: String,
        val lifecycleId: FullAppLifecycleId,
        val accountIdHash: String,
        val matrixDeviceIdHash: String,
        val installationId: String,
        val roomIdHash: String,
        val eventId: String?,
        val transactionId: String?,
        val contentId: String,
        val contentKind: ContentKind,
        val carrierSha256: String,
        val generation: Long? = null,
    )

    data class Result(val ownership: Ownership, val reason: Reason, val origin: Origin? = null) {
        val sendContext: LifecycleOperationContext?
            get() = origin?.takeIf { ownership == Ownership.OWN_LOCAL_SEND }
                ?.let { LifecycleOperationContext(it.operationId, it.lifecycleId) }
    }

    fun resolve(input: Input, origin: Origin?): Result {
        val canonicalEvent = input.eventId?.takeIf(::isCanonicalEvent)
        if (canonicalEvent == null && input.transactionId.isNullOrBlank()) {
            return Result(Ownership.UNKNOWN, Reason.IDENTITY_UNRESOLVED)
        }
        if (input.senderAccountIdHash != input.accountIdHash) {
            return if (canonicalEvent != null && origin == null) Result(Ownership.TRUE_REMOTE_RECEIVE, Reason.REMOTE_SENDER)
            else Result(Ownership.UNKNOWN, Reason.ORIGIN_CONFLICT)
        }
        if (origin != null) {
            val matchingEvent = canonicalEvent != null && origin.eventId == canonicalEvent
            // A server event must have its exact authoritative event binding; a transaction
            // identity alone cannot turn a replayed carrier into the originating send.
            val matchingTransaction = input.eventId == null && !input.transactionId.isNullOrBlank() &&
                input.transactionId == origin.transactionId
            if ((matchingEvent || matchingTransaction) && origin.operationId.length in 16..256 &&
                acceptsSendLifecycle(origin.lifecycleId, origin.contentKind) &&
                origin.accountIdHash == input.accountIdHash && origin.matrixDeviceIdHash == input.matrixDeviceIdHash &&
                origin.installationId == input.installationId && input.senderInstallationId == input.installationId &&
                origin.roomIdHash == input.roomIdHash && origin.contentId == input.contentId &&
                origin.contentKind == input.contentKind && origin.carrierSha256 == input.carrierSha256 &&
                (origin.generation == null || input.generation == null || origin.generation == input.generation)
            ) return Result(Ownership.OWN_LOCAL_SEND, Reason.LOCAL_ORIGIN_BOUND, origin)
            return Result(Ownership.UNKNOWN, Reason.ORIGIN_CONFLICT)
        }
        // The caller must still authenticate the carrier and current device admission at KMS.
        // An installation hint selects a real read path; it is never a local plaintext grant.
        if (canonicalEvent != null && !input.senderInstallationId.isNullOrBlank() &&
            input.senderInstallationId != input.installationId
        ) return Result(Ownership.OWN_OTHER_DEVICE, Reason.OTHER_INSTALLATION)
        return Result(Ownership.UNKNOWN, Reason.IDENTITY_UNRESOLVED)
    }

    // LC-17 owns location carriers; joined call-state writes retain their original LC-20
    // producer. Ringing-only call metadata retains LC-15. All exact identity checks apply.
    internal fun acceptsSendLifecycle(lifecycleId: FullAppLifecycleId, kind: ContentKind): Boolean =
        lifecycleId in setOf(FullAppLifecycleId.LC_13, FullAppLifecycleId.LC_15) ||
            lifecycleId == FullAppLifecycleId.LC_20 && kind == ContentKind.CALL_OPERATION ||
            lifecycleId == FullAppLifecycleId.LC_17 && kind in setOf(
                ContentKind.LOCATION, ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION,
            )

    /** Scheduling hint only: an unbound producer record still grants no SEND context. */
    fun isAwaitingEventBinding(input: Input, origin: Origin?): Boolean =
        origin != null && origin.eventId == null && input.eventId?.let(::isCanonicalEvent) == true &&
            resolve(input, origin.copy(eventId = input.eventId)).ownership == Ownership.OWN_LOCAL_SEND

    fun isCanonicalEvent(value: String): Boolean = value.length in 2..512 && value.startsWith('$') && value.none(Char::isWhitespace)

    /** Scheduling only. A restored, bound journal retains its owner but is historical work. */
    internal fun needsImmediateEventBinding(origin: Origin?, current: LifecycleOperationContext?): Boolean =
        if (origin == null) current != null
        else origin.eventId == null || current?.let {
            it.operationId == origin.operationId && it.lifecycleId == origin.lifecycleId
        } == true

    /** A previously acknowledged text binding needs no replay. This never authorizes a read. */
    internal fun hasRecordedTextBinding(origin: Origin?, current: LifecycleOperationContext?, eventId: String, pendingLineage: Boolean): Boolean =
        current == null && !pendingLineage && isCanonicalEvent(eventId) && origin != null && origin.eventId == eventId &&
            origin.lifecycleId == FullAppLifecycleId.LC_13 && origin.contentKind == ContentKind.TEXT
}
