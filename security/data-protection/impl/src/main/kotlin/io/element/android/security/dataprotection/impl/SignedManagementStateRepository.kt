/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.util.AtomicFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.DeletedIdentityApplyResult
import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.DeletedIdentityRecord
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.ManagementCommandExecutionReport
import io.element.android.security.dataprotection.api.ManagementCommandState
import io.element.android.security.dataprotection.api.ManagementCommandType
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<DeletedIdentityProjection>())
class SignedManagementStateRepository(
    @ApplicationContext private val context: Context,
    private val verifier: ManagementSignatureVerifier,
) : DeletedIdentityProjection {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }
    private val stateFile = File(context.noBackupFilesDir, "security/management/control-state-v1.json")
    private val offlineEntryFile = AtomicFile(File(context.noBackupFilesDir, "security/management/offline-entry-v1.json"))
    private val lock = ReentrantLock()

    // One bounded decoded snapshot, not an authorization cache. Every reader still opens
    // AtomicFile and compares its complete bytes before reusing this private immutable model.
    private data class ParsedState(val bytes: ByteArray, val state: PersistedManagementState)
    private var parsedState: ParsedState? = null
    private val mutableDeletedIdentityRevision = MutableStateFlow(0L)

    override val revision: StateFlow<Long> = mutableDeletedIdentityRevision

    internal fun acceptPolicy(envelope: SignedJsonEnvelopeWire, serverTimeEpochMillis: Long): Long = lock.withLock {
        check(verifier.verify(envelope)) { "Management policy signature verification failed." }
        val policy = decodePolicy(envelope)
        val current = readVerifiedState()
        val existing = current.policies.firstOrNull { decodePolicyOrNull(it)?.version == policy.version }
        if (existing != null) {
            check(MessageDigest.isEqual(verifier.canonicalJson(existing.payload()), verifier.canonicalJson(envelope.payload()))) {
                "A policy version was reused with different content."
            }
            return policy.version
        }
        check(policy.version > current.highestPolicyVersion) { "Management policy replay or rollback was rejected." }
        check(policy.issuedAtEpochMillis <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS) { "Policy issue time is in the future." }
        val retained = current.policies
            .filter { decodePolicyOrNull(it) != null }
            .plus(envelope)
            .sortedByDescending { decodePolicyOrNull(it)?.version ?: Long.MIN_VALUE }
            .take(MAX_RETAINED_POLICIES)
        writeState(
            current.copy(
                highestPolicyVersion = policy.version,
                trustedTimeEpochMillis = maxOf(current.trustedTimeEpochMillis, serverTimeEpochMillis),
                policies = retained,
            )
        )
        policy.version
    }

    /**
     * Atomically records the independently signed CP-POLICY statement and its compatibility policy.
     *
     * This method atomically stages eligible policy work and its exact old-to-new cleanup obligations.
     * It never manufactures APPLIED evidence; the app coordinator must first enforce the complete
     * baseline policy and acknowledge only this durable cleanup plan.
     */
    internal fun acceptPolicyPublication(
        policyEnvelope: SignedJsonEnvelopeWire,
        publicationEnvelope: SignedPolicyPublicationWire,
        serverTimeEpochMillis: Long,
        localSecurityEpoch: Int,
        supportedCapabilities: Set<String>,
    ): PersistedPolicyApplicationWire = lock.withLock {
        val current = readVerifiedState()
        val previousPolicy = (currentPolicyState(current, serverTimeEpochMillis) as? CurrentPolicyState.Active)?.policy
        val verified = verifyPolicyPublication(
            policyEnvelope = policyEnvelope,
            publicationEnvelope = publicationEnvelope,
            serverTimeEpochMillis = serverTimeEpochMillis,
            highestAcceptedVersion = current.highestPolicyVersion,
            localSecurityEpoch = localSecurityEpoch,
            supportedCapabilities = supportedCapabilities,
            verifier = verifier,
        )
        val existingApplication = verifiedPolicyApplication(current)
        if (verified.publication.version == current.highestPolicyVersion) {
            checkNotNull(existingApplication) {
                "A legacy policy version cannot be rebound to a CP-POLICY publication."
            }
            check(samePublication(existingApplication.publication, publicationEnvelope)) {
                "A policy publication version was reused with different signed metadata."
            }
            val existingPolicy = current.policies.firstOrNull {
                decodePolicyOrNull(it)?.version == verified.publication.version
            }
            if (existingPolicy != null) {
                check(samePolicy(existingPolicy, policyEnvelope)) {
                    "A policy publication version was reused with different policy content."
                }
            }
            // An already prepared receipt must be retried byte-for-byte. Do not replace its state or nonce
            // merely because another sync delivered the same signed publication.
            existingApplication.pendingReceipt?.let { return existingApplication }
            // Preserve the terminal device evidence after successful transport as well. A duplicate sync
            // must not generate a second nonce for the same policy/application result.
            existingApplication.deliveredReceipt?.let { return existingApplication }
            existingApplication.applyHandshakeAtEpochMillis?.let { return existingApplication }
            val updated = existingApplication.copy(
                state = verified.localState.name,
                refusedCapabilities = verified.refusedCapabilities,
                errorCode = verified.errorCode,
            )
            if (updated != existingApplication || serverTimeEpochMillis > current.trustedTimeEpochMillis) {
                writeState(
                    current.copy(
                        trustedTimeEpochMillis = maxOf(current.trustedTimeEpochMillis, serverTimeEpochMillis),
                        latestPolicyApplication = updated,
                    )
                )
            }
            return updated
        }
        existingApplication?.let { application ->
            check(application.isResolvedForHigherPolicyAcceptance()) {
                "A higher policy version cannot replace unresolved application, cleanup, or receipt evidence."
            }
        }
        val applicationHistory = existingApplication
            ?.let { application -> (current.policyApplicationHistory + application).takeLast(MAX_RETAINED_POLICY_APPLICATIONS) }
            ?: current.policyApplicationHistory

        val retainedPolicies = current.policies
            .filterNot { decodePolicyOrNull(it)?.version == verified.publication.version }
            .let { policies -> if (verified.mayStagePolicy) policies + policyEnvelope else policies }
            .filter { decodePolicyOrNull(it) != null }
            .sortedByDescending { decodePolicyOrNull(it)?.version ?: Long.MIN_VALUE }
            .take(MAX_RETAINED_POLICIES)
        val application = PersistedPolicyApplicationWire(
            publication = publicationEnvelope,
            state = verified.localState.name,
            refusedCapabilities = verified.refusedCapabilities,
            errorCode = verified.errorCode,
            cleanupOperations = if (verified.mayStagePolicy) {
                policyCleanupPlan(
                    before = previousPolicy,
                    after = decodePolicy(policyEnvelope),
                    version = verified.publication.version,
                )
            } else {
                emptyList()
            },
        )
        writeState(
            current.copy(
                highestPolicyVersion = maxOf(current.highestPolicyVersion, verified.publication.version),
                trustedTimeEpochMillis = maxOf(current.trustedTimeEpochMillis, serverTimeEpochMillis),
                policies = retainedPolicies,
                policyApplicationHistory = applicationHistory,
                latestPolicyApplication = application,
            )
        )
        application
    }

    /** Accepts the exact R05 full-sync wire object without treating transport delivery as apply evidence. */
    internal fun acceptPolicyRevisionEnvelope(
        envelope: SignedPolicyRevisionEnvelopeWire,
        serverTimeEpochMillis: Long,
        localSecurityEpoch: Int,
        supportedCapabilities: Set<String>,
    ): PersistedPolicyApplicationWire = lock.withLock {
        val current = readVerifiedState()
        verifyPolicyRevisionEnvelope(
            envelope = envelope,
            serverTimeEpochMillis = serverTimeEpochMillis,
            highestAcceptedVersion = current.highestPolicyVersion,
            localSecurityEpoch = localSecurityEpoch,
            supportedCapabilities = supportedCapabilities,
            verifier = verifier,
        )
        acceptPolicyPublication(
            policyEnvelope = SignedJsonEnvelopeWire(
                policy = envelope.legacyPolicy,
                keyId = envelope.keyId,
                signatureBase64 = envelope.legacyPolicySignatureB64,
            ),
            publicationEnvelope = SignedPolicyPublicationWire(
                publication = envelope.publicationCanonical,
                keyId = envelope.keyId,
                signatureBase64 = envelope.publicationSignatureB64,
            ),
            serverTimeEpochMillis = serverTimeEpochMillis,
            localSecurityEpoch = localSecurityEpoch,
            supportedCapabilities = supportedCapabilities,
        )
    }

    internal fun latestPolicyApplication(): PersistedPolicyApplicationWire? = lock.withLock {
        verifiedPolicyApplication(readVerifiedState())
    }

    internal fun policyApplicationHistory(): List<PersistedPolicyApplicationWire> = lock.withLock {
        val state = readVerifiedState()
        verifiedPolicyApplication(state)
        state.policyApplicationHistory
    }

    internal fun stagePolicyCleanup(
        version: Long,
        applyHandshakeAtEpochMillis: Long,
        operations: List<PersistedPolicyCleanupOperationWire>,
    ): PersistedPolicyApplicationWire = lock.withLock {
        val current = readVerifiedState()
        val application = checkNotNull(verifiedPolicyApplication(current)) { "No R05 policy application is active." }
        val publication = json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        check(publication.version == version) { "Cleanup plan targets another policy version." }
        check(LocalPolicyApplicationState.valueOf(application.state) == LocalPolicyApplicationState.PENDING_APPLY) {
            "Cleanup may only be staged for an effective policy awaiting local application."
        }
        check(application.pendingReceipt == null && application.deliveredReceipt == null) {
            "Cleanup plan cannot change after receipt evidence exists."
        }
        check(applyHandshakeAtEpochMillis > 0)
        val canonical = operations.sortedBy(PersistedPolicyCleanupOperationWire::operationId)
        check(canonical == operations && canonical.map { it.operationId }.distinct().size == canonical.size) {
            "Policy cleanup operations must be sorted and unique."
        }
        canonical.forEach { operation ->
            check(operation.operationId.matches(CLEANUP_OPERATION_ID_PATTERN))
            check(operation.kind.matches(CLEANUP_KIND_PATTERN))
            check(operation.state == CLEANUP_PENDING)
        }
        application.applyHandshakeAtEpochMillis?.let { existing ->
            check(existing > 0) { "Persisted exact-version apply handshake is invalid." }
            check(application.cleanupOperations == canonical) { "A staged cleanup plan cannot be replaced." }
            return application
        }
        check(application.cleanupOperations == canonical) {
            "Exact-version apply handshake must reuse the cleanup plan persisted with policy acceptance."
        }
        val updated = application.copy(
            applyHandshakeAtEpochMillis = applyHandshakeAtEpochMillis,
        )
        writeState(current.copy(latestPolicyApplication = updated))
        updated
    }

    internal fun completePolicyCleanup(version: Long, operationId: String): PersistedPolicyApplicationWire = lock.withLock {
        val current = readVerifiedState()
        val application = checkNotNull(verifiedPolicyApplication(current)) { "No R05 policy application is active." }
        val publication = json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        check(publication.version == version) { "Cleanup completion targets another policy version." }
        val existing = application.cleanupOperations.firstOrNull { it.operationId == operationId }
            ?: error("Unknown policy cleanup operation.")
        if (existing.state == CLEANUP_COMPLETE) return application
        check(existing.state == CLEANUP_PENDING)
        val completesLastPendingOperation = application.cleanupOperations.none { operation ->
            operation.operationId != operationId && operation.state == CLEANUP_PENDING
        }
        check(
            !completesLastPendingOperation ||
                application.pendingReceipt?.result != LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name
        ) {
            "Cleanup-pending evidence must be delivered before the final cleanup operation can complete."
        }
        val updated = application.copy(
            cleanupOperations = application.cleanupOperations.map { operation ->
                if (operation.operationId == operationId) operation.copy(state = CLEANUP_COMPLETE) else operation
            }
        )
        writeState(current.copy(latestPolicyApplication = updated))
        updated
    }

    internal fun pendingPolicyApplicationReceipt(): PolicyApplicationReceiptWire? = lock.withLock {
        verifiedPolicyApplication(readVerifiedState())?.pendingReceipt
    }

    /** Persists an already device-signed receipt before transport so every retry retains its nonce. */
    internal fun persistPolicyApplicationReceipt(receipt: PolicyApplicationReceiptWire): PolicyApplicationReceiptWire = lock.withLock {
        val current = readVerifiedState()
        val application = checkNotNull(verifiedPolicyApplication(current)) { "No policy application is awaiting evidence." }
        application.pendingReceipt?.let { pending ->
            check(pending == receipt) { "A pending policy receipt cannot be replaced before delivery." }
            return pending
        }
        val receiptEvidenceDigest = policyReceiptEvidenceDigest(receipt)
        application.deliveredReceipt?.takeIf { it.nonce == receipt.nonce }?.let { delivered ->
            check(delivered == receipt) { "A delivered policy receipt nonce is bound to different evidence." }
            return delivered
        }
        application.deliveredReceiptIdentities.firstOrNull { it.nonce == receipt.nonce }?.let { delivered ->
            check(delivered.evidenceDigest == receiptEvidenceDigest) {
                "A delivered policy receipt nonce is bound to different evidence."
            }
            return receipt
        }
        val nextState = validatePolicyApplicationReceipt(receipt, application)
        val currentState = LocalPolicyApplicationState.valueOf(application.state)
        check(isAllowedPolicyApplicationTransition(currentState, nextState)) {
            "Invalid local policy application evidence transition."
        }
        writeState(
            current.copy(
                latestPolicyApplication = application.copy(
                    state = nextState.name,
                    enforcedCapabilities = receipt.enforcedCapabilities,
                    refusedCapabilities = receipt.refusedCapabilities,
                    cleanupOperationIds = receipt.cleanupOperationIds,
                    errorCode = if (nextState == LocalPolicyApplicationState.APPLIED) null else application.errorCode,
                    pendingReceipt = receipt,
                )
            )
        )
        receipt
    }

    internal fun markPolicyApplicationReceiptDelivered(nonce: String) = lock.withLock {
        val current = readVerifiedState()
        val application = checkNotNull(verifiedPolicyApplication(current))
        val pending = checkNotNull(application.pendingReceipt)
        check(pending.nonce == nonce) { "Delivered policy receipt nonce does not match pending evidence." }
        val identity = DeliveredPolicyReceiptIdentityWire(
            nonce = pending.nonce,
            evidenceDigest = policyReceiptEvidenceDigest(pending),
        )
        val identities = (
            application.deliveredReceiptIdentities +
                listOfNotNull(application.deliveredReceipt?.let { delivered ->
                    DeliveredPolicyReceiptIdentityWire(
                        nonce = delivered.nonce,
                        evidenceDigest = policyReceiptEvidenceDigest(delivered),
                    )
                })
            ).distinctBy { it.nonce }
        identities.firstOrNull { it.nonce == identity.nonce }?.let { existing ->
            check(existing == identity) { "Delivered policy receipt nonce conflicts with retained evidence." }
        }
        writeState(
            current.copy(
                latestPolicyApplication = application.copy(
                    pendingReceipt = null,
                    deliveredReceipt = pending,
                    deliveredReceiptIdentities = (identities + identity)
                        .distinctBy { it.nonce }
                        .takeLast(MAX_RETAINED_POLICY_RECEIPTS),
                )
            )
        )
    }

    internal fun acceptCommand(envelope: SignedJsonEnvelopeWire, serverTimeEpochMillis: Long): VerifiedManagementCommand = lock.withLock {
        check(verifier.verify(envelope)) { "Management command signature verification failed." }
        val command = decodeCommand(envelope)
        val current = readVerifiedState()
        val existing = current.commands.firstOrNull { decodeCommand(it.envelope).commandId == command.commandId }
        if (existing != null) {
            check(MessageDigest.isEqual(verifier.canonicalJson(existing.envelope.payload()), verifier.canonicalJson(envelope.payload()))) {
                "A command id was reused with different content."
            }
            return command
        }
        check(command.version > current.highestCommandVersion) { "Management command replay or rollback was rejected." }
        check(command.issuedAtEpochMillis <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS)
        check(command.expiresAtEpochMillis > serverTimeEpochMillis)
        val superseded = command.supersedesCommandId?.let { supersededCommandId ->
            check(supersededCommandId != command.commandId) { "A command cannot supersede itself." }
            current.commands.firstOrNull { decodeCommand(it.envelope).commandId == supersededCommandId }
        }
        if (superseded != null) {
            val supersededCommand = decodeCommand(superseded.envelope)
            check(supersededCommand.installationId == command.installationId)
            check(supersededCommand.type == command.type)
            check(supersededCommand.accountIdHash == command.accountIdHash)
            check(supersededCommand.contentId == command.contentId)
            check(supersededCommand.version < command.version)
        }
        // Only a verified, newer command carrying the signed supersession id may retire an older
        // local command. This lets an administrator retry a failed command without weakening the
        // anti-replay version boundary or trusting an unsigned HTTP conflict response.
        val retained = (current.commands.filterNot { persisted ->
            decodeCommand(persisted.envelope).commandId == command.supersedesCommandId
        } + PersistedCommandWire(envelope = envelope))
            .takeLast(MAX_RETAINED_COMMANDS)
        writeState(
            current.copy(
                highestCommandVersion = command.version,
                trustedTimeEpochMillis = maxOf(current.trustedTimeEpochMillis, serverTimeEpochMillis),
                commands = retained,
            )
        )
        command
    }

    internal fun acceptRelease(envelope: SignedJsonEnvelopeWire, nowEpochMillis: Long): AppReleaseWire = lock.withLock {
        check(verifier.verify(envelope)) { "App release signature verification failed." }
        val release = decodeRelease(envelope)
        val current = readVerifiedState()
        val existingEnvelope = current.latestRelease
        val existing = existingEnvelope?.let(::decodeReleaseOrNull)
        if (existing?.versionCode == release.versionCode) {
            check(MessageDigest.isEqual(verifier.canonicalJson(checkNotNull(existingEnvelope).payload()), verifier.canonicalJson(envelope.payload()))) {
                "An app release version was reused with different content."
            }
            return release
        }
        check(release.versionCode > current.highestReleaseVersion) { "App release replay or rollback was rejected." }
        check(release.issuedAtEpochMillis <= nowEpochMillis + MAX_CLOCK_SKEW_MILLIS)
        check(release.expiresAtEpochMillis > nowEpochMillis)
        writeState(
            current.copy(
                highestReleaseVersion = release.versionCode,
                latestRelease = envelope,
            )
        )
        release
    }

    internal fun cachedRelease(nowEpochMillis: Long = System.currentTimeMillis()): AppReleaseWire? = lock.withLock {
        val state = readVerifiedState()
        val envelope = state.latestRelease ?: return null
        if (!verifier.verify(envelope)) return null
        decodeReleaseOrNull(envelope)?.takeIf { it.expiresAtEpochMillis > nowEpochMillis }
    }

    /** Raw current signed release, including same-version manifests; this is not an update decision. */
    internal fun currentNextReleaseEnvelope(trustedNowMs: Long): JsonObject? = lock.withLock {
        val state = readVerifiedState()
        check(trustedNowMs > 0 && trustedNowMs >= state.trustedTimeEpochMillis)
        val envelope = state.latestRelease ?: return null
        val payload = checkNotNull(envelope.release)
        val exact = SignedJsonEnvelopeWire(release = payload, keyId = envelope.keyId, signatureBase64 = envelope.signatureBase64)
        check(verifier.verify(exact))
        val release = decodeRelease(exact)
        check(release.schemaVersion == 2 && release.versionCode == state.highestReleaseVersion)
        check(release.issuedAtEpochMillis in 1..trustedNowMs && release.expiresAtEpochMillis > trustedNowMs)
        buildJsonObject {
            put("release", payload)
            put("key_id", exact.keyId)
            put("signature_b64", exact.signatureBase64)
        }
    }

    /** Revalidates current policy and tombstones before selecting signed schema3 account provenance. */
    internal fun currentNextAccountAuthority(
        expected: NextCurrentAccountScope,
        trustedNowMs: Long,
    ): VerifiedNextCurrentAccountAuthority? = lock.withLock {
        val state = readVerifiedState()
        check(trustedNowMs > 0 && trustedNowMs >= state.trustedTimeEpochMillis)
        val policy = (currentPolicyState(state, trustedNowMs) as? CurrentPolicyState.Active)?.policy
            ?: error("Current signed policy authority is unavailable.")
        check(policy.version == state.highestPolicyVersion && policy.version == expected.currentPolicyVersion)
        VerifiedNextCurrentAccountAuthority.select(
            state.activationReceipts,
            expected,
            trustedNowMs,
            verifiedDeletedIdentityRecords(state).any { it.accountIdHash == expected.accountIdHash },
            verifier,
        )
    }

    internal fun acceptOfflineEntry(envelope: SignedJsonEnvelopeWire) = lock.withLock {
        check(verifier.verify(envelope))
        val decision = json.decodeFromJsonElement(TrustDecisionWire.serializer(), envelope.payload())
        val grant = decision.offlineEntry
        val current = readVerifiedState()
        val anchor = grant?.takeIf {
            decision.trustState in setOf("TRUSTED", "MANAGED") &&
                it.policyVersion == current.highestPolicyVersion &&
                it.issuedAtEpochMillis == decision.issuedAtEpochMillis &&
                it.expiresAtEpochMillis > it.issuedAtEpochMillis &&
                it.accountBindings.isNotEmpty()
        }?.let {
            OfflineEntryAnchorWire(
                decision = envelope,
                bootCount = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1),
                elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
            )
        }
        writeOfflineEntry(anchor)
    }

    internal fun clearOfflineEntry() = lock.withLock {
        writeOfflineEntry(null)
        readLocalListAnchor()?.let { writeLocalListAnchor(it.copy(permitted = false)) }
    }

    private val localListEntryFile = AtomicFile(File(context.noBackupFilesDir, "security/management/local-list-entry-v1.json"))

    internal fun acceptLocalListEntry(envelope: SignedJsonEnvelopeWire, permitted: Boolean = true) = lock.withLock {
        check(verifier.verify(envelope))
        val incoming = json.decodeFromJsonElement(TrustDecisionWire.serializer(), envelope.payload())
        val previousAnchor = readLocalListAnchor()
        val previous = previousAnchor?.decision?.let {
            if (!verifier.verify(it)) null else json.decodeFromJsonElement(TrustDecisionWire.serializer(), it.payload())
        }
        // Keep a signed denial/absence as a watermark too: an older success must not reopen it.
        if (previous != null &&
            (incoming.issuedAtEpochMillis < previous.issuedAtEpochMillis ||
                incoming.issuedAtEpochMillis == previous.issuedAtEpochMillis && (previous.localListEntry == null || previousAnchor?.permitted != true))) {
                    return@withLock
                }
        if (previous != null && incoming.issuedAtEpochMillis == previous.issuedAtEpochMillis && permitted &&
            incoming.localListEntry != null && incoming.localListEntry == previous.localListEntry) {
                return@withLock
            }
        val anchor = LocalListAnchorWire(
            envelope,
            android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1),
            android.os.SystemClock.elapsedRealtime(),
            permitted
        )
        writeLocalListAnchor(anchor)
    }

    private fun writeLocalListAnchor(anchor: LocalListAnchorWire) {
        localListEntryFile.baseFile.parentFile?.mkdirs()
        val output = localListEntryFile.startWrite()
        try {
            output.write(json.encodeToString(LocalListAnchorWire.serializer(), anchor).encodeToByteArray())
            localListEntryFile.finishWrite(output)
        } catch (failure: Exception) {
            localListEntryFile.failWrite(output)
            throw failure
        }
    }

    private fun readLocalListAnchor(): LocalListAnchorWire? = runCatching {
        localListEntryFile.openRead().use { json.decodeFromString(LocalListAnchorWire.serializer(), it.readBytes().decodeToString()) }
    }.getOrNull()

    private val localListDiagnosticBudget = java.util.concurrent.atomic.AtomicInteger(8)

    private fun localListUnavailable(reason: String): Nothing? {
        if (localListDiagnosticBudget.getAndDecrement() > 0) {
            timber.log.Timber.tag("YiZheStartup").i("[Startup] Startup.localList.repository.unavailable reason=%s", reason)
        }
        return null
    }

    internal fun cachedLocalListEntry(): Pair<TrustDecisionWire, Long>? = lock.withLock {
        val anchor = readLocalListAnchor() ?: return localListUnavailable("ANCHOR_MISSING")
        if (!anchor.permitted || !verifier.verify(anchor.decision)) return localListUnavailable("ANCHOR_DENIED_OR_BAD_SIGNATURE")
        // The repository lock excludes policy/command writes for this entire decision.
        // Reuse this call's snapshot only; the next admission must reread durable state.
        val state = readVerifiedState()
        val application = verifiedPolicyApplication(state)
        if (application != null && application.state != "APPLIED") return localListUnavailable("POLICY_NOT_APPLIED")
        val decision = json.decodeFromJsonElement(TrustDecisionWire.serializer(), anchor.decision.payload())
        val grant = decision.localListEntry ?: return localListUnavailable("GRANT_ABSENT")
        val now = boundedOfflineNow(
            grant.issuedAtEpochMillis,
            anchor.elapsedRealtimeMillis,
            android.os.SystemClock.elapsedRealtime(),
            anchor.bootCount,
            android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1),
            System.currentTimeMillis()
        )
                ?: return localListUnavailable("CLOCK_CONTINUITY")
        val policy = (currentPolicyState(state, now) as? CurrentPolicyState.Active)?.policy ?: return localListUnavailable("POLICY_INACTIVE")
        if (grant.protocolVersion != 1 || grant.entryPolicyVersion <= 0 || grant.policyVersion != policy.version ||
            grant.allowedScopes != listOf("LOCAL_CHAT_LIST") || grant.accountBindings.isEmpty() ||
            decision.trustState !in setOf("TRUSTED", "MANAGED") || grant.issuedAtEpochMillis != decision.issuedAtEpochMillis ||
            grant.expiresAtEpochMillis <= grant.issuedAtEpochMillis ||
            grant.expiresAtEpochMillis - grant.issuedAtEpochMillis > 3_600_000L) {
                return localListUnavailable("GRANT_BINDING_INVALID")
            }
        val deadline = minOf(grant.expiresAtEpochMillis, decision.expiresAtEpochMillis, policy.expiresAtEpochMillis ?: Long.MAX_VALUE)
        if (now >= deadline || pendingCommands(state, System.currentTimeMillis()).isNotEmpty()) return localListUnavailable("EXPIRED_OR_PENDING_COMMAND")
        decision to (deadline - now)
    }

    internal fun cachedOfflineEntry(): Pair<TrustDecisionWire, Long>? = lock.withLock {
        val state = readVerifiedState()
        val anchor = runCatching {
            offlineEntryFile.openRead().use { json.decodeFromString(OfflineEntryAnchorWire.serializer(), it.readBytes().decodeToString()) }
        }.getOrNull() ?: return null
        if (!verifier.verify(anchor.decision)) return null
        val decision = json.decodeFromJsonElement(TrustDecisionWire.serializer(), anchor.decision.payload())
        val grant = decision.offlineEntry ?: return null
        val trustedNow = boundedOfflineNow(
            serverTime = grant.issuedAtEpochMillis,
            anchoredElapsed = anchor.elapsedRealtimeMillis,
            elapsed = android.os.SystemClock.elapsedRealtime(),
            anchoredBoot = anchor.bootCount,
            boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1),
            wallTime = System.currentTimeMillis(),
        ) ?: return null
        val policy = (currentPolicyState(state, trustedNow) as? CurrentPolicyState.Active)?.policy ?: return null
        if (policy.version != grant.policyVersion || policy.offlineAccessMode != OfflineAccessMode.BOUNDED_OFFLINE ||
            policy.maximumOfflineMillis <= 0 || decision.trustState !in setOf("TRUSTED", "MANAGED") ||
            grant.issuedAtEpochMillis != decision.issuedAtEpochMillis || grant.accountBindings.isEmpty() ||
            grant.allowedScopes != listOf("LOCAL_CHAT_LIST")
        ) {
            return null
        }
        val policyDeadline = runCatching { Math.addExact(grant.issuedAtEpochMillis, policy.maximumOfflineMillis) }.getOrNull() ?: return null
        val deadline = minOf(grant.expiresAtEpochMillis, policyDeadline, policy.expiresAtEpochMillis ?: Long.MAX_VALUE)
        if (trustedNow >= deadline) return null
        decision to (deadline - trustedNow)
    }

    private fun writeOfflineEntry(anchor: OfflineEntryAnchorWire?) {
        if (anchor == null) {
            offlineEntryFile.delete()
            return
        }
        offlineEntryFile.baseFile.parentFile?.mkdirs()
        val output = offlineEntryFile.startWrite()
        try {
            output.write(json.encodeToString(OfflineEntryAnchorWire.serializer(), anchor).encodeToByteArray())
            offlineEntryFile.finishWrite(output)
        } catch (failure: Exception) {
            offlineEntryFile.failWrite(output)
            throw failure
        }
    }

    internal fun currentPolicyState(nowEpochMillis: Long = System.currentTimeMillis()): CurrentPolicyState = lock.withLock {
        val state = runCatchingExceptions { readVerifiedState() }
            .getOrElse { return CurrentPolicyState.AccessBlocked(highestAcceptedVersion = null) }
        currentPolicyState(state, nowEpochMillis)
    }

    private fun currentPolicyState(
        state: PersistedManagementState,
        nowEpochMillis: Long,
    ): CurrentPolicyState {
        if (state.highestPolicyVersion == 0L && state.policies.isEmpty()) {
            return CurrentPolicyState.NoAcceptedPolicy
        }
        if (state.highestPolicyVersion <= 0L) {
            return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
        }
        val trustedNow = maxOf(nowEpochMillis, state.trustedTimeEpochMillis)
        val highestEnvelope = state.policies.firstOrNull()
            ?: return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
        val highestAccepted = decodePolicyOrNull(highestEnvelope)
            ?.takeIf { policy -> policy.version == state.highestPolicyVersion && verifier.verify(highestEnvelope) }
            ?: return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
        val candidate = if (highestAccepted.effectiveAtEpochMillis <= trustedNow) {
            highestAccepted
        } else {
            // Retention order is descending accepted version. Validate every not-yet-effective
            // predecessor before selecting the first effective one so a corrupt or expired newer
            // predecessor cannot be skipped in favour of an older, wider policy.
            var previousVersion = highestAccepted.version
            var effectivePredecessor: DataProtectionPolicyV1? = null
            for (envelope in state.policies.drop(1)) {
                val policy = decodePolicyOrNull(envelope)
                    ?: return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
                if (policy.version >= previousVersion || !verifier.verify(envelope)) {
                    return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
                }
                previousVersion = policy.version
                if (policy.effectiveAtEpochMillis <= trustedNow) {
                    effectivePredecessor = policy
                    break
                }
            }
            effectivePredecessor ?: return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
        }
        if (candidate.expiresAtEpochMillis?.let { trustedNow >= it } == true ||
            candidate.offlineAccessMode == OfflineAccessMode.BOUNDED_OFFLINE &&
            isOfflineDeadlineExceeded(candidate, state.trustedTimeEpochMillis, trustedNow)
        ) {
            return CurrentPolicyState.AccessBlocked(state.highestPolicyVersion)
        }
        return CurrentPolicyState.Active(candidate)
    }

    internal fun activePolicy(nowEpochMillis: Long = System.currentTimeMillis()): DataProtectionPolicyV1? =
        (currentPolicyState(nowEpochMillis) as? CurrentPolicyState.Active)?.policy

    internal fun currentPolicyVersion(): Long? = activePolicy()?.version

    /**
     * Returns an exact retained policy only after its persisted signature has been verified again.
     * Historical lookup intentionally does not make the policy current; callers must still enforce
     * the content creation time and content expiry recorded in the protected envelope.
     */
    internal fun verifiedPolicy(policyId: String, version: Long): DataProtectionPolicyV1? = lock.withLock {
        readVerifiedState().policies.asSequence()
            .filter(verifier::verify)
            .mapNotNull(::decodePolicyOrNull)
            .firstOrNull { policy -> policy.policyId == policyId && policy.version == version }
    }

    internal fun isOfflineDeadlineExceeded(nowEpochMillis: Long = System.currentTimeMillis()): Boolean = lock.withLock {
        val state = readVerifiedState()
        val latest = state.policies.asSequence()
            .filter(verifier::verify)
            .mapNotNull(::decodePolicyOrNull)
            .maxByOrNull(DataProtectionPolicyV1::version)
            ?: return true
        isOfflineDeadlineExceeded(
            policy = latest,
            lastTrustedServerTimeEpochMillis = state.trustedTimeEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }

    internal fun updateTrustedTime(serverTimeEpochMillis: Long) = lock.withLock {
        val current = readVerifiedState()
        if (serverTimeEpochMillis > current.trustedTimeEpochMillis) {
            writeState(current.copy(trustedTimeEpochMillis = serverTimeEpochMillis))
        }
    }

    internal fun pendingCommands(nowEpochMillis: Long = System.currentTimeMillis()): List<VerifiedManagementCommand> = lock.withLock {
        pendingCommands(readVerifiedState(), nowEpochMillis)
    }

    internal fun isMobileSessionSuperseded(accountIdHash: String, matrixDeviceIdHash: String): Boolean = lock.withLock {
        readVerifiedState().commands.any { persisted ->
            verifier.verify(persisted.envelope) && decodeCommand(persisted.envelope).let { command ->
                command.reasonCode == "ACCOUNT_MOBILE_SUPERSEDED" && command.accountIdHash == accountIdHash &&
                    command.targetMatrixDeviceIdHash == matrixDeviceIdHash
            }
        }
    }

    private fun pendingCommands(state: PersistedManagementState, nowEpochMillis: Long): List<VerifiedManagementCommand> {
        val trustedNow = maxOf(nowEpochMillis, state.trustedTimeEpochMillis)
        return state.commands.asSequence()
            .filter { it.state != ManagementCommandState.COMPLETE.name }
            .filter { it.state != ManagementCommandState.FAILED.name }
            .filter { verifier.verify(it.envelope) }
            .map { decodeCommand(it.envelope) }
            .filter { it.notBeforeEpochMillis <= trustedNow && it.expiresAtEpochMillis > trustedNow }
            .sortedBy(VerifiedManagementCommand::version)
            .toList()
    }

    internal fun recordCommandReport(report: ManagementCommandExecutionReport, removeWhenComplete: Boolean) = lock.withLock {
        val current = readVerifiedState()
        val updated = current.commands.mapNotNull { persisted ->
            if (decodeCommand(persisted.envelope).commandId != report.commandId) return@mapNotNull persisted
            if (removeWhenComplete && report.state == ManagementCommandState.COMPLETE &&
                decodeCommand(persisted.envelope).reasonCode != "ACCOUNT_MOBILE_SUPERSEDED"
            ) {
                return@mapNotNull null
            }
            persisted.copy(state = report.state.name, progress = report.progress, errorCode = report.errorCode)
        }
        writeState(current.copy(commands = updated))
    }

    internal fun acceptActivationReceipt(envelope: SignedJsonEnvelopeWire): AccountActivationReceiptWire = lock.withLock {
        check(verifier.verify(envelope)) { "Account activation receipt signature verification failed." }
        val receipt = json.decodeFromJsonElement(AccountActivationReceiptWire.serializer(), envelope.payload())
        check(receipt.schemaVersion in 1..3 && receipt.purpose == "ACCOUNT_CORE_ACTIVATION" && receipt.state == "CORE_READY")
        if (receipt.schemaVersion == 1) {
            check(receipt.identityContinuity == "FIRST_ACTIVATION")
        } else {
            check(receipt.identityContinuity in setOf("FIRST_ACTIVATION", "CONTINUOUS", "RESET_CONFIRMED"))
        }
        if (receipt.schemaVersion == 3) {
            check((receipt.accountMobileGeneration ?: 0) > 0)
        } else {
            check(receipt.accountMobileGeneration == null)
        }
        val current = readVerifiedState()
        val newestGeneration = current.activationReceipts.mapNotNull { persisted ->
            runCatchingExceptions {
                json.decodeFromJsonElement(AccountActivationReceiptWire.serializer(), persisted.payload())
            }.getOrNull()?.takeIf { it.accountIdHash == receipt.accountIdHash }?.accountMobileGeneration
        }.maxOrNull() ?: 0
        check((receipt.accountMobileGeneration ?: 0) >= newestGeneration) {
            "Account activation receipt mobile generation rolled back."
        }
        val existing = current.activationReceipts.firstOrNull { persisted ->
            runCatchingExceptions {
                json.decodeFromJsonElement(AccountActivationReceiptWire.serializer(), persisted.payload()).activationId == receipt.activationId
            }.getOrDefault(false)
        }
        if (existing != null) {
            check(MessageDigest.isEqual(verifier.canonicalJson(existing.payload()), verifier.canonicalJson(envelope.payload()))) {
                "An activation id was reused with different content."
            }
            return receipt
        }
        val retained = current.activationReceipts.filterNot { persisted ->
            runCatchingExceptions {
                val value = json.decodeFromJsonElement(AccountActivationReceiptWire.serializer(), persisted.payload())
                value.accountIdHash == receipt.accountIdHash && value.installationId == receipt.installationId
            }.getOrDefault(false)
        }.plus(envelope).takeLast(MAX_RETAINED_ACTIVATIONS)
        writeState(current.copy(activationReceipts = retained))
        receipt
    }

    internal fun currentTombstoneSequence(): Long = lock.withLock {
        readVerifiedState().highestTombstoneSequence
    }

    /** Only an exact, known old-test signer can mark state for the internal cutover. */
    internal fun hasSignedStateFromAuthority(keyId: String): Boolean = lock.withLock {
        val state = readVerifiedState()
        sequenceOf(state.latestRelease, state.latestTombstoneBatch)
            .filterNotNull()
            .plus(state.policies.asSequence())
            .plus(state.commands.asSequence().map { it.envelope })
            .plus(state.activationReceipts.asSequence())
            .plus(state.tombstoneBatches.asSequence())
            .any { it.keyId == keyId }
    }

    /** Called only after the old internal-test account cleanup is durably complete. */
    internal fun resetForNextInternalCutover() = lock.withLock {
        writeState(PersistedManagementState())
        writeOfflineEntry(null)
        localListEntryFile.delete()
        check(!localListEntryFile.baseFile.exists()) {
            "Unable to clear old internal local list entry."
        }
        mutableDeletedIdentityRevision.value = 0L
    }

    internal fun acceptAccountTombstoneChanges(
        envelope: SignedJsonEnvelopeWire,
        expectedInstallationId: String,
    ): DeletedIdentityApplyResult = lock.withLock {
        check(verifier.verify(envelope)) { "Account tombstone batch signature verification failed." }
        val batch = decodeAccountTombstoneChanges(envelope)
        check(batch.installationId == expectedInstallationId) { "Account tombstone batch targets another installation." }
        val current = readVerifiedState()
        val existing = current.latestTombstoneBatch
        if (
            existing != null &&
            MessageDigest.isEqual(verifier.canonicalJson(existing.payload()), verifier.canonicalJson(envelope.payload()))
        ) {
            return DeletedIdentityApplyResult(
                fromSequenceExclusive = batch.fromSequenceExclusive,
                toSequenceInclusive = batch.toSequenceInclusive,
                appliedAccountHashes = batch.records.mapTo(linkedSetOf(), AccountTombstoneRecordWire::accountIdHash),
                idempotentReplay = true,
            )
        }
        check(batch.fromSequenceExclusive == current.highestTombstoneSequence) {
            "Account tombstone delivery sequence has a gap or rollback."
        }
        check(batch.toSequenceInclusive >= batch.fromSequenceExclusive)
        validateAccountTombstoneRecords(batch, envelope.keyId)
        val retainedBatches = if (batch.records.isEmpty()) {
            current.tombstoneBatches
        } else {
            current.tombstoneBatches + envelope
        }
        writeState(
            current.copy(
                highestTombstoneSequence = batch.toSequenceInclusive,
                latestTombstoneBatch = envelope,
                tombstoneBatches = retainedBatches,
            )
        )
        mutableDeletedIdentityRevision.value = batch.toSequenceInclusive
        DeletedIdentityApplyResult(
            fromSequenceExclusive = batch.fromSequenceExclusive,
            toSequenceInclusive = batch.toSequenceInclusive,
            appliedAccountHashes = batch.records.mapTo(linkedSetOf(), AccountTombstoneRecordWire::accountIdHash),
            idempotentReplay = false,
        )
    }

    override fun isDeleted(accountIdHash: String): Boolean = record(accountIdHash) != null

    override fun record(accountIdHash: String): DeletedIdentityRecord? = lock.withLock {
        if (!accountIdHash.matches(ACCOUNT_HASH_PATTERN)) return null
        verifiedDeletedIdentityRecords(readVerifiedState())
            .filter { it.accountIdHash == accountIdHash }
            .maxWithOrNull(compareBy<AccountTombstoneRecordWire> { it.generation }.thenBy { it.sequence })
            ?.let {
                DeletedIdentityRecord(
                    accountIdHash = it.accountIdHash,
                    generation = it.generation,
                    tombstoneSequence = it.sequence,
                    destroyedAtEpochMillis = it.destroyedAtEpochMillis,
                )
            }
    }

    private fun verifiedDeletedIdentityRecords(state: PersistedManagementState): List<AccountTombstoneRecordWire> {
        if (state.highestTombstoneSequence == 0L) return emptyList()
        val latest = checkNotNull(state.latestTombstoneBatch) { "Account tombstone cursor has no signed checkpoint." }
        check(verifier.verify(latest)) { "Account tombstone checkpoint signature verification failed." }
        val latestBatch = decodeAccountTombstoneChanges(latest)
        check(latestBatch.toSequenceInclusive == state.highestTombstoneSequence) {
            "Account tombstone checkpoint cursor is inconsistent."
        }
        return state.tombstoneBatches.flatMap { envelope ->
            check(verifier.verify(envelope)) { "Persisted account tombstone batch signature verification failed." }
            decodeAccountTombstoneChanges(envelope).also { validateAccountTombstoneRecords(it, envelope.keyId) }.records
        }
    }

    private fun decodeAccountTombstoneChanges(envelope: SignedJsonEnvelopeWire): AccountTombstoneChangesWire {
        val batch = json.decodeFromJsonElement(AccountTombstoneChangesWire.serializer(), envelope.payload())
        check(batch.schemaVersion == 1 && batch.purpose == "ACCOUNT_TOMBSTONE_CHANGES")
        check(batch.installationId.isNotBlank() && batch.issuedAtEpochMillis > 0)
        check(batch.ledgerHeadHash.matches(ACCOUNT_HASH_PATTERN))
        return batch
    }

    private fun validateAccountTombstoneRecords(batch: AccountTombstoneChangesWire, signingKeyId: String) {
        var previousSequence = batch.fromSequenceExclusive
        val targets = mutableSetOf<String>()
        batch.records.forEach { record ->
            check(record.schemaVersion == 1 && record.kind == "ACCOUNT")
            check(record.sequence > previousSequence && record.sequence <= batch.toSequenceInclusive)
            check(record.targetId == record.accountIdHash && record.accountIdHash.matches(ACCOUNT_HASH_PATTERN))
            check(record.previousHash.matches(ACCOUNT_HASH_PATTERN) && record.recordHash.matches(ACCOUNT_HASH_PATTERN))
            check(record.generation > 0 && record.destroyedAtEpochMillis >= 0)
            check(record.reason.matches(TOMBSTONE_REASON_PATTERN))
            check(targets.add(record.accountIdHash)) { "Account tombstone batch repeats an identity." }
            val core = buildJsonObject {
                put("schema_version", record.schemaVersion)
                put("sequence", record.sequence)
                put("previous_hash", record.previousHash)
                put("kind", record.kind)
                put("target_id", record.targetId)
                put("account_id_hash", record.accountIdHash)
                put("reason", record.reason)
                put("destroyed_at", record.destroyedAtEpochMillis)
            }
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.canonicalJson(core))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(MessageDigest.isEqual(expectedHash.encodeToByteArray(), record.recordHash.encodeToByteArray()))
            check(verifier.verifyBytes(record.recordHash.hexBytes(), signingKeyId, record.signatureBase64)) {
                "Account tombstone record signature verification failed."
            }
            previousSequence = record.sequence
        }
    }

    private fun String.hexBytes(): ByteArray {
        check(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun decodePolicy(envelope: SignedJsonEnvelopeWire): DataProtectionPolicyV1 {
        val wire = json.decodeFromJsonElement(PolicyWire.serializer(), envelope.payload())
        return DataProtectionPolicyV1(
            policyId = wire.policyId,
            version = wire.version,
            issuedAtEpochMillis = wire.issuedAtEpochMillis,
            effectiveAtEpochMillis = wire.effectiveAtEpochMillis,
            expiresAtEpochMillis = wire.expiresAtEpochMillis,
            contentRetentionMillis = wire.contentRetentionMillis,
            maximumOfflineMillis = wire.maximumOfflineMillis,
            offlineAccessMode = OfflineAccessMode.valueOf(wire.offlineAccessMode),
            notificationExposure = NotificationExposure.valueOf(wire.notificationExposure),
            hardwareTrustRequirement = HardwareTrustRequirement.valueOf(wire.hardwareTrustRequirement),
            allowContentExport = wire.allowContentExport,
            allowClipboard = wire.allowClipboard,
            allowExternalShare = wire.allowExternalShare,
            allowGroupReadReceipts = wire.allowGroupReadReceipts,
            screenCapturePolicy = ScreenCapturePolicy.valueOf(wire.screenCapturePolicy),
            requireContentEnvelope = wire.requireContentEnvelope,
            allowRtc = wire.allowRtc,
            schemaVersion = wire.schemaVersion,
        )
    }

    private fun decodePolicyOrNull(envelope: SignedJsonEnvelopeWire): DataProtectionPolicyV1? =
        runCatchingExceptions { decodePolicy(envelope) }.getOrNull()

    private fun policyCleanupPlan(
        before: DataProtectionPolicyV1?,
        after: DataProtectionPolicyV1,
        version: Long,
    ): List<PersistedPolicyCleanupOperationWire> {
        require(version == after.version)
        if (before == null) return emptyList()
        val kinds = buildSet {
            if (after.contentRetentionMillis < before.contentRetentionMillis) add(CLEANUP_CONTENT_RETENTION)
            if (before.hasProjectionAuthorizationChange(after)) add(CLEANUP_AUTHORIZATION_CACHE)
            if (before.notificationExposure != after.notificationExposure) add(CLEANUP_NOTIFICATION_REDACTION)
            if (before.allowContentExport && !after.allowContentExport) add(CLEANUP_ACTIVE_EXPORT)
            if (before.allowExternalShare && !after.allowExternalShare) add(CLEANUP_ACTIVE_SHARE)
            if (before.allowClipboard && !after.allowClipboard) add(CLEANUP_CLIPBOARD)
            if (before.screenCapturePolicy != after.screenCapturePolicy) add(CLEANUP_CAPTURE_STATE)
            if (before.allowGroupReadReceipts && !after.allowGroupReadReceipts) add(CLEANUP_GROUP_RECEIPTS)
        }
        return kinds.sorted().map { kind ->
            PersistedPolicyCleanupOperationWire(
                operationId = "r05-policy-$version-${kind.lowercase().replace('_', '-')}",
                kind = kind,
                state = CLEANUP_PENDING,
            )
        }
    }

    private fun DataProtectionPolicyV1.hasProjectionAuthorizationChange(after: DataProtectionPolicyV1): Boolean =
        policyId != after.policyId ||
            maximumOfflineMillis != after.maximumOfflineMillis ||
            offlineAccessMode != after.offlineAccessMode ||
            hardwareTrustRequirement != after.hardwareTrustRequirement ||
            requireContentEnvelope != after.requireContentEnvelope

    private fun PersistedPolicyApplicationWire.isResolvedForHigherPolicyAcceptance(): Boolean {
        if (pendingReceipt != null || cleanupOperations.any { it.state == CLEANUP_PENDING }) return false
        val localState = LocalPolicyApplicationState.valueOf(state)
        if (localState !in POLICY_APPLICATION_SUPERSESSION_TERMINAL_STATES) return false
        return deliveredReceipt?.result == localState.name
    }

    private fun samePublication(
        first: SignedPolicyPublicationWire,
        second: SignedPolicyPublicationWire,
    ): Boolean = first.keyId == second.keyId &&
        first.signatureBase64 == second.signatureBase64 &&
        MessageDigest.isEqual(verifier.canonicalJson(first.publication), verifier.canonicalJson(second.publication))

    private fun samePolicy(first: SignedJsonEnvelopeWire, second: SignedJsonEnvelopeWire): Boolean =
        first.keyId == second.keyId &&
            first.signatureBase64 == second.signatureBase64 &&
            MessageDigest.isEqual(verifier.canonicalJson(first.payload()), verifier.canonicalJson(second.payload()))

    private fun verifiedPolicyApplicationHistory(state: PersistedManagementState) {
        check(state.policyApplicationHistory.size <= MAX_RETAINED_POLICY_APPLICATIONS)
        var previousVersion = 0L
        state.policyApplicationHistory.forEach { application ->
            check(
                verifier.verifyDetached(
                    application.publication.publication,
                    application.publication.keyId,
                    application.publication.signatureBase64,
                )
            ) { "Archived CP-POLICY publication signature verification failed." }
            val publication = json.decodeFromJsonElement(
                PolicyPublicationWire.serializer(),
                application.publication.publication,
            )
            check(publication.version > previousVersion && publication.version < state.highestPolicyVersion) {
                "Archived policy applications must be unique, ordered predecessors."
            }
            previousVersion = publication.version
            check(application.pendingReceipt == null)
            check(application.cleanupOperations.all { operation ->
                operation.operationId.matches(CLEANUP_OPERATION_ID_PATTERN) &&
                    operation.kind.matches(CLEANUP_KIND_PATTERN) &&
                    operation.state == CLEANUP_COMPLETE
            })
            check(application.isResolvedForHigherPolicyAcceptance())
            val localState = LocalPolicyApplicationState.valueOf(application.state)
            val delivered = checkNotNull(application.deliveredReceipt)
            check(validatePolicyApplicationReceipt(delivered, application, allowCompletedCleanupEvidence = true) == localState)
            check(application.deliveredReceiptIdentities.map { it.nonce }.distinct().size == application.deliveredReceiptIdentities.size)
            application.deliveredReceiptIdentities.forEach { identity ->
                check(identity.nonce.length in 16..128)
                check(identity.evidenceDigest.matches(ACCOUNT_HASH_PATTERN))
            }
        }
    }

    private fun verifiedPolicyApplication(state: PersistedManagementState): PersistedPolicyApplicationWire? {
        verifiedPolicyApplicationHistory(state)
        val application = state.latestPolicyApplication ?: return null
        check(
            verifier.verifyDetached(
                application.publication.publication,
                application.publication.keyId,
                application.publication.signatureBase64,
            )
        ) { "Persisted CP-POLICY publication signature verification failed." }
        val publication = json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        check(publication.version == state.highestPolicyVersion) {
            "Persisted policy application does not match the anti-rollback watermark."
        }
        val localState = LocalPolicyApplicationState.valueOf(application.state)
        check(application.applyHandshakeAtEpochMillis == null || application.applyHandshakeAtEpochMillis > 0)
        check(application.cleanupOperations.map { it.operationId }.distinct().size == application.cleanupOperations.size)
        application.cleanupOperations.forEach { operation ->
            check(operation.operationId.matches(CLEANUP_OPERATION_ID_PATTERN))
            check(operation.kind.matches(CLEANUP_KIND_PATTERN))
            check(operation.state in setOf(CLEANUP_PENDING, CLEANUP_COMPLETE))
        }
        application.pendingReceipt?.let { receipt ->
            check(validatePolicyApplicationReceipt(receipt, application) == localState) {
                "Persisted policy receipt does not match the local application state."
            }
        }
        application.deliveredReceipt?.let { receipt ->
            val deliveredState = validatePolicyApplicationReceipt(
                receipt,
                application,
                allowCompletedCleanupEvidence = true,
            )
            check(
                deliveredState == localState ||
                    (deliveredState == LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING &&
                        localState == LocalPolicyApplicationState.APPLIED)
            ) {
                "Delivered policy receipt does not match the local application state."
            }
        }
        check(application.deliveredReceiptIdentities.map { it.nonce }.distinct().size == application.deliveredReceiptIdentities.size)
        application.deliveredReceiptIdentities.forEach { identity ->
            check(identity.nonce.length in 16..128)
            check(identity.evidenceDigest.matches(ACCOUNT_HASH_PATTERN))
        }
        return application
    }

    private fun policyReceiptEvidenceDigest(receipt: PolicyApplicationReceiptWire): String {
        val payload = canonicalPolicyApplicationReceiptPayload(receipt, verifier)
        return MessageDigest.getInstance("SHA-256")
            .digest(payload + byteArrayOf(0) + receipt.deviceSignature.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun isAllowedPolicyApplicationTransition(
        current: LocalPolicyApplicationState,
        next: LocalPolicyApplicationState,
    ): Boolean = when (current) {
        LocalPolicyApplicationState.PENDING_EFFECTIVE ->
            next == LocalPolicyApplicationState.BLOCKED_VERSION || next == LocalPolicyApplicationState.FAILED
        LocalPolicyApplicationState.PENDING_APPLY -> true
        LocalPolicyApplicationState.BLOCKED_VERSION,
        LocalPolicyApplicationState.FAILED -> next == current
        LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING ->
            next == LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING || next == LocalPolicyApplicationState.APPLIED
        LocalPolicyApplicationState.APPLIED -> next == LocalPolicyApplicationState.APPLIED
    }

    private fun decodeCommand(envelope: SignedJsonEnvelopeWire): VerifiedManagementCommand {
        val wire = json.decodeFromJsonElement(CommandWire.serializer(), envelope.payload())
        // Management commands are currently issued as schema 1 by the control plane.
        // Keep this check scoped to commands; app-release schema support is handled
        // independently in decodeRelease below.
        check(wire.schemaVersion in 1..2)
        check(wire.version > 0 && wire.commandId.isNotBlank() && wire.installationId.isNotBlank())
        check(wire.expiresAtEpochMillis > wire.notBeforeEpochMillis)
        val type = ManagementCommandType.valueOf(wire.commandType)
        if (wire.schemaVersion == 2) {
            check(type == ManagementCommandType.CLEAR_ACCOUNT)
            check(wire.targetMatrixDeviceIdHash?.matches(ACCOUNT_HASH_PATTERN) == true)
            check((wire.mobileGeneration ?: 0) > 0)
            check(wire.reasonCode == "ACCOUNT_MOBILE_SUPERSEDED")
        } else {
            check(wire.targetMatrixDeviceIdHash == null && wire.mobileGeneration == null && wire.reasonCode == null)
        }
        check(type != ManagementCommandType.CLEAR_ACCOUNT || wire.accountIdHash?.matches(ACCOUNT_HASH_PATTERN) == true)
        check(type == ManagementCommandType.CLEAR_ACCOUNT || wire.accountIdHash == null)
        check(type != ManagementCommandType.CLEAR_CONTENT || wire.contentId?.matches(CONTENT_ID_PATTERN) == true)
        check(type == ManagementCommandType.CLEAR_CONTENT || wire.contentId == null)
        return VerifiedManagementCommand(
            commandId = wire.commandId,
            supersedesCommandId = wire.supersedesCommandId,
            installationId = wire.installationId,
            type = type,
            accountIdHash = wire.accountIdHash,
            contentId = wire.contentId,
            version = wire.version,
            issuedAtEpochMillis = wire.issuedAtEpochMillis,
            notBeforeEpochMillis = wire.notBeforeEpochMillis,
            expiresAtEpochMillis = wire.expiresAtEpochMillis,
            targetMatrixDeviceIdHash = wire.targetMatrixDeviceIdHash,
            mobileGeneration = wire.mobileGeneration,
            reasonCode = wire.reasonCode,
        )
    }

    private fun decodeRelease(envelope: SignedJsonEnvelopeWire): AppReleaseWire {
        val wire = json.decodeFromJsonElement(AppReleaseWire.serializer(), envelope.payload())
        // Schema 2 binds the fallback URL and exact artifact identity. Older
        // manifests omit mandatory identity fields and must fail closed.
        check(wire.schemaVersion == 2)
        check(wire.versionCode > 0 && wire.versionName.isNotBlank())
        check(wire.packageName.isNotBlank() && wire.apkUrl.startsWith("https://") && wire.apkFallbackUrl.startsWith("https://"))
        check(wire.apkSha256.matches(ACCOUNT_HASH_PATTERN))
        check(wire.buildId.matches(BUILD_ID_PATTERN))
        check(wire.signingCertificateSha256.matches(ACCOUNT_HASH_PATTERN))
        check(wire.expiresAtEpochMillis > wire.issuedAtEpochMillis)
        return wire
    }

    private fun decodeReleaseOrNull(envelope: SignedJsonEnvelopeWire): AppReleaseWire? =
        runCatchingExceptions { decodeRelease(envelope) }.getOrNull()

    private fun readVerifiedState(): PersistedManagementState {
        val bytes = try {
            AtomicFile(stateFile).openRead().use { input -> input.readBytes() }
        } catch (_: FileNotFoundException) {
            parsedState = null
            return PersistedManagementState()
        } catch (failure: Exception) {
            parsedState = null
            throw IllegalStateException("Persisted management state is unreadable.", failure)
        }
        parsedState?.takeIf { it.bytes.contentEquals(bytes) }?.let { return it.state }
        parsedState = null
        return runCatchingExceptions { json.decodeFromString<PersistedManagementState>(bytes.decodeToString()) }
            .getOrElse { failure ->
                throw IllegalStateException("Persisted management state is unreadable.", failure)
            }.also { state ->
                if (bytes.size <= MAX_PARSED_STATE_BYTES) parsedState = ParsedState(bytes, state)
            }
    }

    private fun writeState(state: PersistedManagementState) {
        parsedState = null
        stateFile.parentFile?.let { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create management control state directory." }
        }
        val atomicFile = AtomicFile(stateFile)
        var output: FileOutputStream? = null
        try {
            val startedOutput = atomicFile.startWrite()
            output = startedOutput
            startedOutput.write(json.encodeToString(state).encodeToByteArray())
            startedOutput.fd.sync()
            atomicFile.finishWrite(startedOutput)
            output = null
        } catch (failure: Exception) {
            output?.let(atomicFile::failWrite)
            throw IllegalStateException("Unable to commit management control state.", failure)
        }
    }

    private companion object {
        const val MAX_PARSED_STATE_BYTES = 1024 * 1024
        const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L

        // Retain enough signed generations for the longest supported message lifetime. This is a
        // bounded cache, not permission to accept rollback: highestPolicyVersion remains monotonic.
        const val MAX_RETAINED_POLICIES = 512
        const val MAX_RETAINED_COMMANDS = 100
        const val MAX_RETAINED_ACTIVATIONS = 8
        const val MAX_RETAINED_POLICY_RECEIPTS = 16
        const val MAX_RETAINED_POLICY_APPLICATIONS = 64
        val ACCOUNT_HASH_PATTERN = Regex("[0-9a-f]{64}")
        val BUILD_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val CONTENT_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,96}")
        val TOMBSTONE_REASON_PATTERN = Regex("[A-Z0-9_]{1,128}")
        val CLEANUP_OPERATION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{8,256}")
        val CLEANUP_KIND_PATTERN = Regex("[A-Z0-9_]{1,128}")
        const val CLEANUP_PENDING = "PENDING"
        const val CLEANUP_COMPLETE = "COMPLETE"
        const val CLEANUP_CONTENT_RETENTION = "CONTENT_RETENTION"
        const val CLEANUP_AUTHORIZATION_CACHE = "AUTHORIZATION_CACHE"
        const val CLEANUP_NOTIFICATION_REDACTION = "NOTIFICATION_REDACTION"
        const val CLEANUP_ACTIVE_EXPORT = "ACTIVE_EXPORT_CANCEL"
        const val CLEANUP_ACTIVE_SHARE = "ACTIVE_SHARE_CANCEL"
        const val CLEANUP_CLIPBOARD = "CLIPBOARD_CLEAR"
        const val CLEANUP_CAPTURE_STATE = "CAPTURE_STATE_RECONCILE"
        const val CLEANUP_GROUP_RECEIPTS = "GROUP_RECEIPT_RECONCILE"
        val POLICY_APPLICATION_SUPERSESSION_TERMINAL_STATES = setOf(
            LocalPolicyApplicationState.APPLIED,
            LocalPolicyApplicationState.BLOCKED_VERSION,
            LocalPolicyApplicationState.FAILED,
        )
    }
}

internal sealed interface CurrentPolicyState {
    data object NoAcceptedPolicy : CurrentPolicyState

    data class Active(val policy: DataProtectionPolicyV1) : CurrentPolicyState

    data class AccessBlocked(val highestAcceptedVersion: Long?) : CurrentPolicyState
}

internal fun boundedOfflineNow(
    serverTime: Long,
    anchoredElapsed: Long,
    elapsed: Long,
    anchoredBoot: Int,
    boot: Int,
    wallTime: Long,
): Long? {
    if (serverTime <= 0 || anchoredBoot < 0 || boot != anchoredBoot || anchoredElapsed < 0 || elapsed < anchoredElapsed) return null
    val advanced = runCatching { Math.addExact(serverTime, elapsed - anchoredElapsed) }.getOrNull() ?: return null
    if (wallTime < advanced - 5 * 60_000L) return null
    return maxOf(advanced, wallTime)
}

internal fun isOfflineDeadlineExceeded(
    policy: DataProtectionPolicyV1,
    lastTrustedServerTimeEpochMillis: Long,
    nowEpochMillis: Long,
): Boolean {
    if (lastTrustedServerTimeEpochMillis <= 0) return true
    val trustedNow = maxOf(nowEpochMillis, lastTrustedServerTimeEpochMillis)
    if (policy.expiresAtEpochMillis?.let { trustedNow >= it } == true) return true
    if (policy.offlineAccessMode == OfflineAccessMode.ONLINE_REQUIRED) {
        return trustedNow > lastTrustedServerTimeEpochMillis
    }
    val deadline = try {
        Math.addExact(lastTrustedServerTimeEpochMillis, policy.maximumOfflineMillis)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    return trustedNow > deadline
}

@kotlinx.serialization.Serializable
internal data class LocalListAnchorWire(
    val decision: SignedJsonEnvelopeWire,
    val bootCount: Int,
    val elapsedRealtimeMillis: Long,
    val permitted: Boolean,
)
