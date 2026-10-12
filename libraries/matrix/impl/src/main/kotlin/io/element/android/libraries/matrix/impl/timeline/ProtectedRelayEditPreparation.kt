/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import java.util.UUID

internal fun relayEditPreparedContentId(roomId: String, operationId: String) = ContentId(
    UUID.nameUUIDFromBytes("relay-edit-v1\u0000$roomId\u0000$operationId".encodeToByteArray()).toString(),
)

internal fun PreparedProtectedSendOrigin.requireRelayEditIntent(
    expected: ProtectedEventOwnershipResolver.Origin,
    intentSha256: String,
) {
    require(expected.lifecycleId == FullAppLifecycleId.LC_15 && expected.contentKind == ContentKind.RELAY_OPERATION)
    require(origin.operationId == expected.operationId && origin.lifecycleId == expected.lifecycleId &&
        origin.accountIdHash == expected.accountIdHash && origin.matrixDeviceIdHash == expected.matrixDeviceIdHash &&
        origin.installationId == expected.installationId && origin.roomIdHash == expected.roomIdHash &&
        origin.contentId == expected.contentId && origin.contentKind == expected.contentKind && this.intentSha256 == intentSha256)
}

/** The existing encrypted producer journal owns preparation; Matrix owns durable enqueue and deduplication. */
internal suspend fun prepareRelayEdit(
    store: ProtectedSendOriginStore,
    scope: ContentVersionLineageScope,
    expected: ProtectedEventOwnershipResolver.Origin,
    intentSha256: String,
    authorizeExisting: suspend (String) -> Unit,
    protectFresh: suspend (ContentId, suspend () -> Unit) -> PreparedProtectedContent,
    reconcileOriginal: suspend () -> Unit = {},
): PreparedProtectedContent = store.withPreparation(scope, expected.contentId) {
    store.prepared(scope, expected.contentId)?.let { retained ->
        retained.requireRelayEditIntent(expected, intentSha256)
        reconcileOriginal()
        // The authenticated producer journal binds the exact sealed carrier and intent.
        // Current signed KMS retransmission authority grants no content key or new preparation.
        authorizeExisting(retained.encodedEnvelope)
        return@withPreparation PreparedProtectedContent(retained.encodedEnvelope, ContentId(expected.contentId))
    }
    val previous = store.find(scope, expected.contentId)
    if (previous != null) {
        val reservation = checkNotNull(store.reserved(scope, expected.contentId)) { "Original relay edit preparation is incomplete." }
        require(reservation.origin == expected && reservation.intentSha256 == intentSha256)
        if (reservation.preparationStarted != false) reconcileOriginal()
        // Only a durable false boundary under the same journal writer lock proves
        // that the interrupted owner never dispatched a KMS store.
        check(reservation.preparationStarted == false) { "Original relay edit KMS result requires reconciliation." }
    }
    // Persist the exact original intent before KMS can create a share. If a crash
    // interrupts protection, later attempts retain this uncertain original intent
    // for reconciliation and cannot create a second share.
    if (previous == null) store.reservePreparation(scope, expected, intentSha256)
    val prepared = protectFresh(ContentId(expected.contentId)) {
        store.markPreparationStarted(scope, expected, intentSha256)
    }
    require(prepared.contentId.value == expected.contentId)
    val committed = checkNotNull(store.prepared(scope, expected.contentId)) { "Relay edit preparation was not persisted." }
    committed.requireRelayEditIntent(expected, intentSha256)
    require(committed.encodedEnvelope == prepared.encodedEnvelope)
    prepared
}

/** Reuses the existing encrypted journal's exact scope and immutable intent checks for LC17. */
internal fun PreparedProtectedSendOrigin.requireLiveLocationIntent(
    expected: ProtectedEventOwnershipResolver.Origin,
    intentSha256: String,
) {
    require(expected.lifecycleId == FullAppLifecycleId.LC_17 &&
        expected.contentKind in setOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION))
    require(origin.operationId == expected.operationId && origin.lifecycleId == expected.lifecycleId &&
        origin.accountIdHash == expected.accountIdHash && origin.matrixDeviceIdHash == expected.matrixDeviceIdHash &&
        origin.installationId == expected.installationId && origin.roomIdHash == expected.roomIdHash &&
        origin.contentId == expected.contentId && origin.contentKind == expected.contentKind && this.intentSha256 == intentSha256)
}
