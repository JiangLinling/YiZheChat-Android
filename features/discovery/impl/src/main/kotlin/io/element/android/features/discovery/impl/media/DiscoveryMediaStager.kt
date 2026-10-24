/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.securestorage.api.*
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Uses existing encrypted staging and its durable cleanup owner. Does not mint a preview authority or a URI. */
@Inject
internal class DiscoveryMediaStager(
    private val reader: DiscoveryMediaReader,
    private val gate: AndroidDiscoveryIdentityGate,
    private val staging: SecureObjectStagingStore,
    private val previewBoundary: DiscoveryMediaPreviewBoundary,
    client: MatrixClient,
) {
    private val vaultId = deriveAccountVaultId(client.sessionId.value, client.deviceId.value)

    /** Owner must come from the caller's already-started LC-16 operation; metadata alone never authorizes preview. */
    suspend fun withVerifiedStage(reference: DiscoveryMediaReference, owner: SecureTransientMediaOwner,
        consume: suspend (SecureObjectStagingTransaction) -> Unit) = previewBoundary.whileCurrent { checkPreview ->
        val account = gate.current() ?: throw DiscoveryFailure(401)
        require(owner.accountIdHash == account.accountHash && owner.matrixDeviceIdHash == account.deviceHash &&
            owner.installationId == account.installationId && owner.roomIdHash == null && owner.eventIdHash == null)
        val aad = buildJsonObject {
            put("domain", "discovery-media-read-v1"); put("operation_id", owner.operationId)
            put("account", account.accountHash); put("device", account.deviceHash); put("installation", account.installationId)
            put("attestation", account.attestationVersion); put("login", account.loginTimestamp)
            put("asset_id", reference.assetId); put("binding_type", reference.binding.wire); put("binding_id", reference.objectId)
            put("access", reference.access.name); put("kind", reference.kind.wire); put("mime_type", reference.mimeType)
            put("byte_length", reference.byteLength); put("sha256", reference.sha256)
        }.toString().encodeToByteArray()
        val request = SecureObjectWriteRequest(SecureObjectId("discovery-media-${UUID.randomUUID()}"), vaultId,
            if (reference.kind == DiscoveryMediaKind.IMAGE) SecureObjectPurpose.IMAGE else SecureObjectPurpose.VIDEO,
            aad, reference.byteLength, transientMediaOwner = owner)
        try {
            withDiscoveryMediaStaging(staging, request,
                download = { output ->
                    if (gate.current() != account) throw CancellationException("Discovery media identity changed")
                    checkPreview()
                    reader.copyVerifiedToEncryptedStaging(reference, output)
                }, consume = { transaction ->
                    if (gate.current() != account) throw CancellationException("Discovery media identity changed")
                    checkPreview()
                    require(transaction.plaintextLength == reference.byteLength)
                    consume(transaction)
                })
        } finally { aad.fill(0) }
    }
}

/** Protect only ownership publication from cancellation. The download still runs with the original cancellable job. */
internal suspend fun withDiscoveryMediaStaging(
    store: SecureObjectStagingStore,
    request: SecureObjectWriteRequest,
    download: suspend (OutputStream) -> Unit,
    consume: suspend (SecureObjectStagingTransaction) -> Unit,
) {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    var transaction: SecureObjectStagingTransaction? = null
    var failure: Throwable? = null
    try {
        withContext(NonCancellable) {
            transaction = store.stage(request) { output -> withContext(caller + Dispatchers.IO) { download(output) } }
        }
        caller.ensureActive()
        consume(requireNotNull(transaction))
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        withContext(NonCancellable) {
            transaction?.let {
                val report = it.abort()
                if (!report.complete) throw SecureMediaCleanupException(report, failure)
            }
        }
    }
}
