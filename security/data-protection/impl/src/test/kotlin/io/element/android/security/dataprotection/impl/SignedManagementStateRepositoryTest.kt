/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.util.AtomicFile
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SignedManagementStateRepositoryTest {
    @Test
    fun `signed offline entry survives process recreation without resetting its deadline`() {
        val fixture = Fixture(temporaryFolder.root)
        val now = System.currentTimeMillis()
        android.provider.Settings.Global.putInt(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, "boot_count", 7)
        fixture.repository.acceptPolicy(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = now + 120_000, maximumOfflineMillis = 60_000, contentRetentionMillis = 120_000),
            now
        )
        fixture.repository.acceptOfflineEntry(fixture.offlineDecision(now))
        val initial = checkNotNull(fixture.repository.cachedOfflineEntry()).second
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10))
        val remaining = checkNotNull(fixture.newRepository().cachedOfflineEntry()).second
        assertThat(remaining).isAtMost(50_000L)
        assertThat(remaining).isLessThan(initial)
        assertThat(fixture.stateFile.readText()).doesNotContain("offline_entry_anchor")
        android.provider.Settings.Global.putInt(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, "boot_count", 8)
        assertThat(fixture.newRepository().cachedOfflineEntry()).isNull()
    }

    @Test
    fun `offline deadline or tampered signature cannot open the cache`() {
        val fixture = Fixture(temporaryFolder.root)
        val now = System.currentTimeMillis()
        android.provider.Settings.Global.putInt(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, "boot_count", 7)
        fixture.repository.acceptPolicy(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = now + 120_000, maximumOfflineMillis = 60_000, contentRetentionMillis = 120_000),
            now
        )
        fixture.repository.acceptOfflineEntry(fixture.offlineDecision(now))
        val entryFile = fixture.stateFile.parentFile.resolve("offline-entry-v1.json")
        entryFile.writeText(entryFile.readText().replace("\"signature_b64\":\"", "\"signature_b64\":\"invalid"))
        assertThat(fixture.newRepository().cachedOfflineEntry()).isNull()
        fixture.repository.acceptOfflineEntry(fixture.offlineDecision(now))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(60))
        assertThat(fixture.newRepository().cachedOfflineEntry()).isNull()
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `signed account tombstone survives repository restart and identical replay`() {
        val fixture = Fixture(temporaryFolder.root)
        val envelope = fixture.tombstoneEnvelope(sequence = 1)

        val first = fixture.repository.acceptAccountTombstoneChanges(envelope, INSTALLATION_ID)
        assertThat(first.idempotentReplay).isFalse()
        assertThat(fixture.repository.isDeleted(ACCOUNT_HASH)).isTrue()

        val restarted = fixture.newRepository()
        assertThat(restarted.record(ACCOUNT_HASH)?.generation).isEqualTo(2)
        assertThat(restarted.acceptAccountTombstoneChanges(envelope, INSTALLATION_ID).idempotentReplay).isTrue()
    }

    @Test
    fun `tombstone sequence gap and tampered record signature fail closed`() {
        val fixture = Fixture(temporaryFolder.root)

        assertThrows(IllegalStateException::class.java) {
            fixture.repository.acceptAccountTombstoneChanges(
                fixture.tombstoneEnvelope(sequence = 2, fromSequenceExclusive = 1),
                INSTALLATION_ID,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.repository.acceptAccountTombstoneChanges(
                fixture.tombstoneEnvelope(sequence = 1, recordSignatureOverride = "dGFtcGVyZWQ="),
                INSTALLATION_ID,
            )
        }
    }

    @Test
    fun `corrupt persisted authority state is not treated as an empty tombstone store`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.repository.acceptAccountTombstoneChanges(fixture.tombstoneEnvelope(sequence = 1), INSTALLATION_ID)
        fixture.stateFile.writeText("{not-valid-json", Charsets.UTF_8)

        assertThat(fixture.newRepository().currentPolicyState())
            .isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = null))
        assertThrows(IllegalStateException::class.java) {
            fixture.newRepository().isDeleted(ACCOUNT_HASH)
        }
    }

    @Test
    fun `incomplete upgraded publication blocks refresh without rewriting original authority bytes`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.repository.updateTrustedTime(1_000)
        val publication = """{"publication":{"nonce":"synthetic"},"state":"APPLIED"}"""
        for (serialized in listOf(
            """{"highest_policy_version":7,"highest_tombstone_sequence":12,"latest_policy_application":$publication}""",
            """{"highest_policy_version":7,"highest_tombstone_sequence":12,"policy_application_history":[$publication]}""",
        )) {
            val original = serialized.encodeToByteArray()
            fixture.stateFile.writeBytes(original)
            val restarted = fixture.newRepository()
            assertThat(restarted.currentPolicyState()).isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = null))
            assertThrows(IllegalStateException::class.java) { restarted.updateTrustedTime(2_000) }
            assertThat(fixture.stateFile.readBytes()).isEqualTo(original)
        }
    }

    @Test
    fun `repeated management state commits replace the prior file and survive restart`() {
        val fixture = Fixture(temporaryFolder.root)

        fixture.repository.updateTrustedTime(1_000)
        fixture.repository.updateTrustedTime(1_001)
        fixture.newRepository().updateTrustedTime(1_002)

        val persisted = Json.decodeFromString<PersistedManagementState>(fixture.stateFile.readText(Charsets.UTF_8))
        assertThat(persisted.trustedTimeEpochMillis).isEqualTo(1_002)
        assertThat(fixture.newRepository().currentPolicyState()).isEqualTo(CurrentPolicyState.NoAcceptedPolicy)
    }

    @Test
    fun `interrupted atomic writes recover signed policy cleanup receipt and tombstone state`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val tightened = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )
        val accepted = fixture.repository.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        fixture.repository.stagePolicyCleanup(
            version = 2,
            applyHandshakeAtEpochMillis = 1_003,
            operations = accepted.cleanupOperations,
        )
        val receipt = policyReceipt(
            publication = tightened,
            result = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING,
            appliedAtEpochMillis = 1_004,
            cleanupOperationIds = accepted.cleanupOperations.map { it.operationId },
            nonce = "nonce-policy-atomic-recovery-v2",
        )
        fixture.repository.persistPolicyApplicationReceipt(receipt)
        fixture.repository.acceptAccountTombstoneChanges(fixture.tombstoneEnvelope(sequence = 1), INSTALLATION_ID)
        val expectedApplication = fixture.repository.latestPolicyApplication()
        val expectedTombstone = fixture.repository.record(ACCOUNT_HASH)

        val backup = java.io.File("${fixture.stateFile.path}.bak")
        check(fixture.stateFile.renameTo(backup))
        fixture.stateFile.writeText("{truncated", Charsets.UTF_8)

        val recovered = fixture.newRepository()
        assertThat(recovered.activePolicy(nowEpochMillis = 1_005)?.version).isEqualTo(2)
        assertThat(recovered.latestPolicyApplication()).isEqualTo(expectedApplication)
        assertThat(recovered.pendingPolicyApplicationReceipt()).isEqualTo(receipt)
        assertThat(recovered.record(ACCOUNT_HASH)).isEqualTo(expectedTombstone)
        assertThat(recovered.isDeleted(ACCOUNT_HASH)).isTrue()
        val recoveredState = Json.decodeFromString<PersistedManagementState>(
            AtomicFile(fixture.stateFile).openRead().use { input -> input.readBytes().decodeToString() }
        )
        assertThat(recoveredState.highestPolicyVersion).isEqualTo(2)
        assertThat(recoveredState.highestTombstoneSequence).isEqualTo(1)
        assertThat(recoveredState.trustedTimeEpochMillis).isAtLeast(1_002)
        assertThat(recoveredState.latestPolicyApplication?.cleanupOperations?.single()?.state).isEqualTo("PENDING")

        val atomicFile = AtomicFile(fixture.stateFile)
        val failedOutput = atomicFile.startWrite()
        failedOutput.write("{failed-write".encodeToByteArray())
        failedOutput.fd.sync()
        atomicFile.failWrite(failedOutput)

        val afterFailedWrite = fixture.newRepository()
        assertThat(afterFailedWrite.latestPolicyApplication()).isEqualTo(expectedApplication)
        assertThat(afterFailedWrite.pendingPolicyApplicationReceipt()).isEqualTo(receipt)
        assertThat(afterFailedWrite.record(ACCOUNT_HASH)).isEqualTo(expectedTombstone)
    }

    @Test
    fun `expired highest accepted policy does not fall back to an older policy`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = null, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, expiresAtEpochMillis = 2_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.activePolicy(nowEpochMillis = 2_001)).isNull()
    }

    @Test
    fun `offline expired highest accepted policy does not fall back to an older wider lease`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = null, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, expiresAtEpochMillis = null, maximumOfflineMillis = 100),
        )

        assertThat(fixture.repository.activePolicy(nowEpochMillis = 1_101)).isNull()
    }

    @Test
    fun `online required policy stays selected while the separate offline gate blocks cached use`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = null, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(
                version = 2,
                expiresAtEpochMillis = null,
                maximumOfflineMillis = 0,
                offlineAccessMode = "ONLINE_REQUIRED",
            ),
        )

        assertThat(fixture.repository.activePolicy(nowEpochMillis = 1_000)?.version).isEqualTo(2)
        assertThat(fixture.repository.activePolicy(nowEpochMillis = 1_001)?.version).isEqualTo(2)
        assertThat(fixture.repository.isOfflineDeadlineExceeded(nowEpochMillis = 1_001)).isTrue()
    }

    @Test
    fun `future highest accepted policy keeps previous policy until its effective time`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, effectiveAtEpochMillis = 1_000, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, effectiveAtEpochMillis = 5_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.activePolicy(nowEpochMillis = 4_999)?.version).isEqualTo(1)
        assertThat(fixture.repository.activePolicy(nowEpochMillis = 5_000)?.version).isEqualTo(2)
    }

    @Test
    fun `exact historical policy remains available after a newer policy expires`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = null, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, expiresAtEpochMillis = 2_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.activePolicy(nowEpochMillis = 2_001)).isNull()
        assertThat(fixture.repository.verifiedPolicy(POLICY_ID, version = 1)?.version).isEqualTo(1)
    }

    @Test
    fun `future highest policy without an effective predecessor blocks access`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, effectiveAtEpochMillis = 5_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.currentPolicyState(nowEpochMillis = 1_000))
            .isInstanceOf(CurrentPolicyState.AccessBlocked::class.java)
    }

    @Test
    fun `future highest policy does not skip a corrupted effective predecessor`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistState(
            highestPolicyVersion = 3,
            fixture.policyEnvelope(version = 1, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, maximumOfflineMillis = 10_000)
                .copy(signatureBase64 = "dGFtcGVyZWQ="),
            fixture.policyEnvelope(version = 3, effectiveAtEpochMillis = 5_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.currentPolicyState(nowEpochMillis = 2_000))
            .isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = 3))
    }

    @Test
    fun `future highest policy does not skip an expired immediate predecessor`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistState(
            highestPolicyVersion = 3,
            fixture.policyEnvelope(version = 1, expiresAtEpochMillis = null, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 2, expiresAtEpochMillis = 1_500, maximumOfflineMillis = 10_000),
            fixture.policyEnvelope(version = 3, effectiveAtEpochMillis = 5_000, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.currentPolicyState(nowEpochMillis = 2_000))
            .isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = 3))
    }

    @Test
    fun `recorded highest version missing from signed cache blocks access`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistState(
            highestPolicyVersion = 2,
            fixture.policyEnvelope(version = 1, maximumOfflineMillis = 10_000),
        )

        assertThat(fixture.repository.currentPolicyState(nowEpochMillis = 1_000))
            .isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = 2))
    }

    @Test
    fun `managed provider uses baseline only before acceptance then denies when accepted policy expires`() {
        val fixture = Fixture(temporaryFolder.root)
        val provider = ManagedDataProtectionPolicyProvider(fixture.repository)

        assertThat(provider.current()).isEqualTo(BASELINE_POLICY)
        val onlineRequiredPolicy = fixture.policyEnvelope(
            version = 1,
            expiresAtEpochMillis = null,
            maximumOfflineMillis = 0,
            offlineAccessMode = "ONLINE_REQUIRED",
        )
        fixture.persistPolicies(onlineRequiredPolicy)

        assertThat(provider.current().source).isEqualTo(DataProtectionPolicySource.VERIFIED_REMOTE)
        assertThat(fixture.repository.isOfflineDeadlineExceeded(nowEpochMillis = 1_001)).isTrue()

        fixture.persistPolicies(
            onlineRequiredPolicy,
            fixture.policyEnvelope(version = 2, expiresAtEpochMillis = 2_000, maximumOfflineMillis = 10_000),
        )

        val active = provider.current()

        assertThat(active.source).isEqualTo(DataProtectionPolicySource.FAIL_CLOSED)
        assertThat(active.policy.notificationExposure).isEqualTo(NotificationExposure.HIDDEN)
        assertThat(active.policy.offlineAccessMode).isEqualTo(OfflineAccessMode.ONLINE_REQUIRED)
        assertThat(active.policy.maximumOfflineMillis).isEqualTo(0)
        assertThat(active.policy.allowContentExport).isFalse()
        assertThat(active.policy.allowClipboard).isFalse()
        assertThat(active.policy.allowExternalShare).isFalse()
        assertThat(active.policy.allowGroupReadReceipts).isFalse()
        assertThat(active.policy.allowRtc).isFalse()
        assertThat(provider.resolve(POLICY_ID, 1)?.source)
            .isEqualTo(DataProtectionPolicySource.VERIFIED_REMOTE)

        fixture.stateFile.writeText("{not-valid-json", Charsets.UTF_8)
        assertThat(provider.current().source).isEqualTo(DataProtectionPolicySource.FAIL_CLOSED)
    }

    @Test
    fun `unsupported publication advances durable watermark without exposing broader predecessor`() {
        val fixture = Fixture(temporaryFolder.root)
        fixture.persistPolicies(
            fixture.policyEnvelope(version = 1, maximumOfflineMillis = 10_000),
        )
        val publication = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 4,
            capabilities = listOf("CAPTURE_BLOCK"),
        )

        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK"),
        )

        assertThat(application.state).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION.name)
        assertThat(fixture.repository.currentPolicyState(nowEpochMillis = 1_001))
            .isEqualTo(CurrentPolicyState.AccessBlocked(highestAcceptedVersion = 2))
        assertThat(fixture.newRepository().latestPolicyApplication()?.state)
            .isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION.name)
    }

    @Test
    fun `eligible publication and pending apply state commit together`() {
        val fixture = Fixture(temporaryFolder.root)
        val publication = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
        )

        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )

        assertThat(application.state).isEqualTo(LocalPolicyApplicationState.PENDING_APPLY.name)
        assertThat(fixture.repository.activePolicy(nowEpochMillis = 1_001)?.version).isEqualTo(1)
        assertThat(fixture.newRepository().latestPolicyApplication()?.state)
            .isEqualTo(LocalPolicyApplicationState.PENDING_APPLY.name)
    }

    @Test
    fun `cleanup obligations survive crash after durable sync and before coordinator handshake`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            contentRetentionMillis = 20_000,
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val tightened = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            contentRetentionMillis = 10_000,
            allowClipboard = false,
        )
        val accepted = fixture.repository.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val expectedOperationIds = listOf(
            "r05-policy-2-clipboard-clear",
            "r05-policy-2-content-retention",
        )

        val restarted = fixture.newRepository()
        assertThat(accepted.applyHandshakeAtEpochMillis).isNull()
        assertThat(accepted.cleanupOperations.map { it.operationId }).containsExactlyElementsIn(expectedOperationIds).inOrder()
        assertThat(accepted.cleanupOperations.map { it.state }).containsExactly("PENDING", "PENDING").inOrder()
        assertThat(restarted.latestPolicyApplication()?.cleanupOperations).isEqualTo(accepted.cleanupOperations)
        val duplicate = restarted.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_003,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        assertThat(duplicate.applyHandshakeAtEpochMillis).isNull()
        assertThat(duplicate.cleanupOperations).isEqualTo(accepted.cleanupOperations)
        assertThat(restarted.pendingPolicyApplicationReceipt()).isNull()
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            tightened.publication.publication,
        )
        assertThrows(IllegalStateException::class.java) {
            restarted.persistPolicyApplicationReceipt(
                PolicyApplicationReceiptWire(
                    operationId = statement.operationId,
                    installationId = INSTALLATION_ID,
                    bootId = "android-boot-7",
                    policyDomain = statement.domain,
                    version = 2,
                    digest = statement.payloadDigest,
                    appliedAt = 1_004,
                    enforcedCapabilities = emptyList(),
                    refusedCapabilities = emptyList(),
                    cleanupOperationIds = emptyList(),
                    result = LocalPolicyApplicationState.APPLIED.name,
                    nonce = "nonce-policy-premature-applied-0001",
                    deviceSignature = "device-signature-premature",
                )
            )
        }
        val staged = restarted.stagePolicyCleanup(
            version = 2,
            applyHandshakeAtEpochMillis = 1_004,
            operations = duplicate.cleanupOperations,
        )
        assertThat(staged.cleanupOperations.map { it.operationId }).containsExactlyElementsIn(expectedOperationIds).inOrder()
        var completed = staged
        expectedOperationIds.forEach { operationId ->
            completed = restarted.completePolicyCleanup(2, operationId)
        }
        assertThat(completed.cleanupOperations.map { it.state }).containsExactly("COMPLETE", "COMPLETE").inOrder()
    }

    @Test
    fun `higher version cannot replace pending apply obligations after restart`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val tightened = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )
        val unresolved = fixture.repository.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val higher = fixture.policyPublication(
            version = 3,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )

        val restarted = fixture.newRepository()
        assertThrows(IllegalStateException::class.java) {
            restarted.acceptPolicyPublication(
                policyEnvelope = higher.policy,
                publicationEnvelope = higher.publication,
                serverTimeEpochMillis = 1_003,
                localSecurityEpoch = 3,
                supportedCapabilities = emptySet(),
            )
        }

        assertThat(restarted.latestPolicyApplication()).isEqualTo(unresolved)
        assertThat(restarted.activePolicy(nowEpochMillis = 1_003)?.version).isEqualTo(2)
        assertThat(restarted.latestPolicyApplication()?.cleanupOperations?.single()?.state).isEqualTo("PENDING")
    }

    @Test
    fun `higher version preserves cleanup pending receipt and operation after restart`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val tightened = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )
        val accepted = fixture.repository.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        fixture.repository.stagePolicyCleanup(
            version = 2,
            applyHandshakeAtEpochMillis = 1_003,
            operations = accepted.cleanupOperations,
        )
        val receipt = policyReceipt(
            publication = tightened,
            result = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING,
            appliedAtEpochMillis = 1_004,
            cleanupOperationIds = accepted.cleanupOperations.map { it.operationId },
            nonce = "nonce-policy-cleanup-pending-v2",
        )
        fixture.repository.persistPolicyApplicationReceipt(receipt)
        val higher = fixture.policyPublication(
            version = 3,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )

        var restarted = fixture.newRepository()
        assertHigherPublicationRejected(restarted, higher, serverTimeEpochMillis = 1_005)
        assertThat(restarted.pendingPolicyApplicationReceipt()).isEqualTo(receipt)
        restarted.markPolicyApplicationReceiptDelivered(receipt.nonce)
        restarted = fixture.newRepository()
        assertHigherPublicationRejected(restarted, higher, serverTimeEpochMillis = 1_006)

        val preserved = restarted.latestPolicyApplication()
        assertThat(preserved?.state).isEqualTo(LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name)
        assertThat(preserved?.deliveredReceipt).isEqualTo(receipt)
        assertThat(preserved?.cleanupOperations?.single()?.state).isEqualTo("PENDING")
        assertThat(restarted.activePolicy(nowEpochMillis = 1_006)?.version).isEqualTo(2)
    }

    @Test
    fun `higher version cannot replace unresolved blocked or failed evidence`() {
        val blockedFixture = Fixture(temporaryFolder.newFolder("blocked-supersession"))
        val blocked = blockedFixture.policyPublication(
            version = 1,
            minSecurityEpoch = 4,
            capabilities = listOf("CAPTURE_BLOCK"),
        )
        val blockedApplication = blockedFixture.repository.acceptPolicyPublication(
            policyEnvelope = blocked.policy,
            publicationEnvelope = blocked.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK"),
        )
        val blockedReceipt = policyReceipt(
            publication = blocked,
            result = LocalPolicyApplicationState.BLOCKED_VERSION,
            refusedCapabilities = listOf("CAPTURE_BLOCK"),
            nonce = "nonce-policy-blocked-v1",
        )
        blockedFixture.repository.persistPolicyApplicationReceipt(blockedReceipt)
        val blockedHigher = blockedFixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
        )
        val blockedRestarted = blockedFixture.newRepository()
        assertHigherPublicationRejected(blockedRestarted, blockedHigher, serverTimeEpochMillis = 1_002)
        assertThat(blockedRestarted.latestPolicyApplication()?.state).isEqualTo(blockedApplication.state)
        assertThat(blockedRestarted.pendingPolicyApplicationReceipt()).isEqualTo(blockedReceipt)

        val failedFixture = Fixture(temporaryFolder.newFolder("failed-supersession"))
        val failed = failedFixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            expiresAtEpochMillis = 1_500,
        )
        val failedApplication = failedFixture.repository.acceptPolicyPublication(
            policyEnvelope = failed.policy,
            publicationEnvelope = failed.publication,
            serverTimeEpochMillis = 2_000,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val failedReceipt = policyReceipt(
            publication = failed,
            result = LocalPolicyApplicationState.FAILED,
            nonce = "nonce-policy-failed-v1",
        )
        failedFixture.repository.persistPolicyApplicationReceipt(failedReceipt)
        val failedHigher = failedFixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
        )
        val failedRestarted = failedFixture.newRepository()
        assertHigherPublicationRejected(failedRestarted, failedHigher, serverTimeEpochMillis = 2_001)
        assertThat(failedRestarted.latestPolicyApplication()?.state).isEqualTo(failedApplication.state)
        assertThat(failedRestarted.pendingPolicyApplicationReceipt()).isEqualTo(failedReceipt)
    }

    @Test
    fun `higher blocked or failed revision cannot erase prior pending cleanup`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val tightened = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )
        val unresolved = fixture.repository.acceptPolicyPublication(
            policyEnvelope = tightened.policy,
            publicationEnvelope = tightened.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val blockedHigher = fixture.policyPublication(
            version = 3,
            minSecurityEpoch = 4,
            capabilities = listOf("CAPTURE_BLOCK"),
            allowClipboard = false,
        )
        val failedHigher = fixture.policyPublication(
            version = 4,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            expiresAtEpochMillis = 1_500,
            allowClipboard = false,
        )

        val restarted = fixture.newRepository()
        assertHigherPublicationRejected(restarted, blockedHigher, serverTimeEpochMillis = 1_003)
        assertHigherPublicationRejected(restarted, failedHigher, serverTimeEpochMillis = 2_000)

        assertThat(restarted.latestPolicyApplication()).isEqualTo(unresolved)
        assertThat(restarted.activePolicy(nowEpochMillis = 2_000)?.version).isEqualTo(2)
        assertThat(restarted.latestPolicyApplication()?.cleanupOperations?.single()?.state).isEqualTo("PENDING")
    }

    @Test
    fun `higher version may replace a terminal application only after its evidence is delivered`() {
        val fixture = Fixture(temporaryFolder.root)
        val blocked = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 4,
            capabilities = listOf("CAPTURE_BLOCK"),
        )
        fixture.repository.acceptPolicyPublication(
            policyEnvelope = blocked.policy,
            publicationEnvelope = blocked.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK"),
        )
        val blockedReceipt = policyReceipt(
            publication = blocked,
            result = LocalPolicyApplicationState.BLOCKED_VERSION,
            refusedCapabilities = listOf("CAPTURE_BLOCK"),
            nonce = "nonce-policy-blocked-delivered-v1",
        )
        fixture.repository.persistPolicyApplicationReceipt(blockedReceipt)
        fixture.repository.markPolicyApplicationReceiptDelivered(blockedReceipt.nonce)
        val higher = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
        )

        val accepted = fixture.newRepository().acceptPolicyPublication(
            policyEnvelope = higher.policy,
            publicationEnvelope = higher.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )

        assertThat(accepted.state).isEqualTo(LocalPolicyApplicationState.PENDING_APPLY.name)
        val restarted = fixture.newRepository()
        assertThat(restarted.activePolicy(nowEpochMillis = 1_002)?.version).isEqualTo(2)
        val archived = restarted.policyApplicationHistory().single()
        assertThat(archived.state).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION.name)
        assertThat(archived.deliveredReceipt).isEqualTo(blockedReceipt)
        assertThat(archived.deliveredReceiptIdentities.map { it.nonce }).containsExactly(blockedReceipt.nonce)
    }

    @Test
    fun `cleanup pending delivery can advance to a distinct final applied receipt`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val publication = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = false,
        )
        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val cleanupId = "r05-policy-2-clipboard-clear"
        fixture.repository.stagePolicyCleanup(
            version = 2,
            applyHandshakeAtEpochMillis = 1_003,
            operations = application.cleanupOperations,
        )
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            publication.publication.publication,
        )
        val cleanupPending = PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = INSTALLATION_ID,
            bootId = "android-boot-7",
            policyDomain = statement.domain,
            version = 2,
            digest = statement.payloadDigest,
            appliedAt = 1_004,
            enforcedCapabilities = emptyList(),
            refusedCapabilities = emptyList(),
            cleanupOperationIds = listOf(cleanupId),
            result = LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name,
            nonce = "nonce-policy-cleanup-pending-0001",
            deviceSignature = "device-signature-cleanup",
        )
        fixture.repository.persistPolicyApplicationReceipt(cleanupPending)
        assertThrows(IllegalStateException::class.java) {
            fixture.repository.completePolicyCleanup(2, cleanupId)
        }
        assertThat(fixture.repository.latestPolicyApplication()?.cleanupOperations?.single()?.state)
            .isEqualTo("PENDING")
        fixture.repository.markPolicyApplicationReceiptDelivered(cleanupPending.nonce)
        fixture.repository.completePolicyCleanup(2, cleanupId)
        val applied = cleanupPending.copy(
            appliedAt = 1_005,
            cleanupOperationIds = emptyList(),
            result = LocalPolicyApplicationState.APPLIED.name,
            nonce = "nonce-policy-applied-final-0001",
            deviceSignature = "device-signature-applied",
        )

        fixture.repository.persistPolicyApplicationReceipt(applied)
        fixture.repository.markPolicyApplicationReceiptDelivered(applied.nonce)

        val final = fixture.newRepository().latestPolicyApplication()
        assertThat(final?.state).isEqualTo(LocalPolicyApplicationState.APPLIED.name)
        assertThat(final?.deliveredReceipt).isEqualTo(applied)
        assertThat(final?.deliveredReceiptIdentities?.map { it.nonce })
            .containsExactly(cleanupPending.nonce, applied.nonce).inOrder()
    }

    @Test
    fun `pending signed receipt survives restart and cannot replace its nonce`() {
        val fixture = Fixture(temporaryFolder.root)
        val publication = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 4,
            capabilities = listOf("CAPTURE_BLOCK"),
        )
        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK"),
        )
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        val receipt = PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = INSTALLATION_ID,
            bootId = "android-boot-7",
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            appliedAt = null,
            enforcedCapabilities = emptyList(),
            refusedCapabilities = statement.requiredCapabilities,
            cleanupOperationIds = emptyList(),
            result = LocalPolicyApplicationState.BLOCKED_VERSION.name,
            nonce = "nonce-policy-receipt-0001",
            deviceSignature = "device-signature",
        )

        fixture.repository.persistPolicyApplicationReceipt(receipt)

        val restarted = fixture.newRepository()
        assertThat(restarted.pendingPolicyApplicationReceipt()).isEqualTo(receipt)
        assertThat(restarted.persistPolicyApplicationReceipt(receipt)).isEqualTo(receipt)
        assertThrows(IllegalStateException::class.java) {
            restarted.persistPolicyApplicationReceipt(receipt.copy(nonce = "nonce-policy-receipt-0002"))
        }
        restarted.markPolicyApplicationReceiptDelivered(receipt.nonce)
        assertThat(restarted.pendingPolicyApplicationReceipt()).isNull()
        assertThat(restarted.latestPolicyApplication()?.deliveredReceipt).isEqualTo(receipt)
        val duplicate = restarted.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = setOf("CAPTURE_BLOCK"),
        )
        assertThat(duplicate.deliveredReceipt).isEqualTo(receipt)
        assertThat(duplicate.pendingReceipt).isNull()
    }

    @Test
    fun `tampered persisted publication is never exposed as application state`() {
        val fixture = Fixture(temporaryFolder.root)
        val publication = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 4,
            capabilities = emptyList(),
        )
        fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val state = Json.decodeFromString<PersistedManagementState>(fixture.stateFile.readText(Charsets.UTF_8))
        val application = checkNotNull(state.latestPolicyApplication)
        fixture.stateFile.writeText(
            Json.encodeToString(
                state.copy(
                    latestPolicyApplication = application.copy(
                        publication = application.publication.copy(signatureBase64 = "dGFtcGVyZWQ=")
                    )
                )
            ),
            Charsets.UTF_8,
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.newRepository().latestPolicyApplication()
        }
    }

    @Test
    fun `future publication cannot persist applied evidence before effective time`() {
        val fixture = Fixture(temporaryFolder.root)
        val publication = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            effectiveAtEpochMillis = 2_000,
        )
        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = 1_001,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            application.publication.publication,
        )
        val premature = PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = INSTALLATION_ID,
            bootId = "android-boot-7",
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            appliedAt = 1_002,
            enforcedCapabilities = emptyList(),
            refusedCapabilities = emptyList(),
            cleanupOperationIds = emptyList(),
            result = LocalPolicyApplicationState.APPLIED.name,
            nonce = "nonce-policy-receipt-early",
            deviceSignature = "device-signature",
        )

        assertThat(application.state).isEqualTo(LocalPolicyApplicationState.PENDING_EFFECTIVE.name)
        assertThrows(IllegalStateException::class.java) {
            fixture.repository.persistPolicyApplicationReceipt(premature)
        }
    }

    @Test
    fun `future publication preserves its accepted cleanup plan when it becomes effective`() {
        val fixture = Fixture(temporaryFolder.root)
        val previous = fixture.policyPublication(
            version = 1,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            allowClipboard = true,
        )
        acceptAndDeliverApplied(fixture, previous, serverTimeEpochMillis = 1_001)
        val future = fixture.policyPublication(
            version = 2,
            minSecurityEpoch = 3,
            capabilities = emptyList(),
            effectiveAtEpochMillis = 2_000,
            allowClipboard = false,
        )
        val accepted = fixture.repository.acceptPolicyPublication(
            policyEnvelope = future.policy,
            publicationEnvelope = future.publication,
            serverTimeEpochMillis = 1_002,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )

        val effective = fixture.newRepository().acceptPolicyPublication(
            policyEnvelope = future.policy,
            publicationEnvelope = future.publication,
            serverTimeEpochMillis = 2_000,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )

        assertThat(accepted.state).isEqualTo(LocalPolicyApplicationState.PENDING_EFFECTIVE.name)
        assertThat(accepted.cleanupOperations.map { it.operationId })
            .containsExactly("r05-policy-2-clipboard-clear")
        assertThat(effective.state).isEqualTo(LocalPolicyApplicationState.PENDING_APPLY.name)
        assertThat(effective.cleanupOperations).isEqualTo(accepted.cleanupOperations)
        assertThat(effective.applyHandshakeAtEpochMillis).isNull()
    }

    private fun acceptAndDeliverApplied(
        fixture: Fixture,
        publication: PublicationPair,
        serverTimeEpochMillis: Long,
    ) {
        val application = fixture.repository.acceptPolicyPublication(
            policyEnvelope = publication.policy,
            publicationEnvelope = publication.publication,
            serverTimeEpochMillis = serverTimeEpochMillis,
            localSecurityEpoch = 3,
            supportedCapabilities = emptySet(),
        )
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            publication.publication.publication,
        )
        fixture.repository.stagePolicyCleanup(
            version = statement.version,
            applyHandshakeAtEpochMillis = serverTimeEpochMillis,
            operations = application.cleanupOperations,
        )
        val receipt = policyReceipt(
            publication = publication,
            result = LocalPolicyApplicationState.APPLIED,
            appliedAtEpochMillis = serverTimeEpochMillis,
            nonce = "nonce-policy-applied-${serverTimeEpochMillis.toString().padStart(8, '0')}",
        )
        fixture.repository.persistPolicyApplicationReceipt(receipt)
        fixture.repository.markPolicyApplicationReceiptDelivered(receipt.nonce)
    }

    private fun assertHigherPublicationRejected(
        repository: SignedManagementStateRepository,
        publication: PublicationPair,
        serverTimeEpochMillis: Long,
    ) {
        assertThrows(IllegalStateException::class.java) {
            repository.acceptPolicyPublication(
                policyEnvelope = publication.policy,
                publicationEnvelope = publication.publication,
                serverTimeEpochMillis = serverTimeEpochMillis,
                localSecurityEpoch = 3,
                supportedCapabilities = setOf("CAPTURE_BLOCK"),
            )
        }
    }

    private fun policyReceipt(
        publication: PublicationPair,
        result: LocalPolicyApplicationState,
        appliedAtEpochMillis: Long? = null,
        refusedCapabilities: List<String> = emptyList(),
        cleanupOperationIds: List<String> = emptyList(),
        nonce: String,
    ): PolicyApplicationReceiptWire {
        val statement = Json.decodeFromJsonElement(
            PolicyPublicationWire.serializer(),
            publication.publication.publication,
        )
        return PolicyApplicationReceiptWire(
            operationId = statement.operationId,
            installationId = INSTALLATION_ID,
            bootId = "android-boot-7",
            policyDomain = statement.domain,
            version = statement.version,
            digest = statement.payloadDigest,
            appliedAt = appliedAtEpochMillis,
            enforcedCapabilities = emptyList(),
            refusedCapabilities = refusedCapabilities,
            cleanupOperationIds = cleanupOperationIds,
            result = result.name,
            nonce = nonce,
            deviceSignature = "device-signature-$nonce",
        )
    }

    private data class PublicationPair(
        val policy: SignedJsonEnvelopeWire,
        val publication: SignedPolicyPublicationWire,
    )

    private class Fixture(root: java.io.File) {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyId = MessageDigest.getInstance("SHA-256")
            .digest(keyPair.public.encoded)
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        private val context = mockk<Context> {
            every { noBackupFilesDir } returns root
            every { contentResolver } returns org.robolectric.RuntimeEnvironment.getApplication().contentResolver
        }
        private val configuration = mockk<ManagementControlConfiguration> {
            every { isConfigured } returns true
            every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(keyPair.public.encoded)
            every { signingKeyId } returns keyId
        }
        private val verifier = ManagementSignatureVerifier(configuration)
        val stateFile = root.resolve("security/management/control-state-v1.json")
        val repository = newRepository()

        fun newRepository() = SignedManagementStateRepository(context, verifier)

        fun offlineDecision(now: Long): SignedJsonEnvelopeWire {
            val payload = Json.encodeToJsonElement(TrustDecisionWire.serializer(), TrustDecisionWire(
                installationId = INSTALLATION_ID,
                attestationVersion = 1,
                trustState = "TRUSTED",
                locationEvidenceState = "ACCEPTED",
                issuedAtEpochMillis = now,
                expiresAtEpochMillis = now + 120_000,
                schemaVersion = 3,
                offlineEntry = OfflineEntryGrantWire(
                    1,
                    now,
                    now + 60_000,
                    "a".repeat(64),
                    "b".repeat(64),
                    listOf(OfflineEntryAccountBindingWire(ACCOUNT_HASH, "c".repeat(64), 1, "activation-test-0001")),
                    listOf("LOCAL_CHAT_LIST")
                ),
            )).jsonObject
            return SignedJsonEnvelopeWire(decision = payload, keyId = keyId, signatureBase64 = sign(verifier.canonicalJson(payload)))
        }

        fun tombstoneEnvelope(
            sequence: Long,
            fromSequenceExclusive: Long = 0,
            recordSignatureOverride: String? = null,
        ): SignedJsonEnvelopeWire {
            val destroyedAt = 1_000L
            val core = buildJsonObject {
                put("schema_version", 1)
                put("sequence", sequence)
                put("previous_hash", "0".repeat(64))
                put("kind", "ACCOUNT")
                put("target_id", ACCOUNT_HASH)
                put("account_id_hash", ACCOUNT_HASH)
                put("reason", "ACCOUNT_DELETED")
                put("destroyed_at", destroyedAt)
            }
            val recordHash = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(core)).toHex()
            val record = AccountTombstoneRecordWire(
                schemaVersion = 1,
                sequence = sequence,
                previousHash = "0".repeat(64),
                kind = "ACCOUNT",
                targetId = ACCOUNT_HASH,
                accountIdHash = ACCOUNT_HASH,
                reason = "ACCOUNT_DELETED",
                destroyedAtEpochMillis = destroyedAt,
                recordHash = recordHash,
                signatureBase64 = recordSignatureOverride ?: sign(recordHash.hexBytes()),
                generation = 2,
            )
            val batch = AccountTombstoneChangesWire(
                schemaVersion = 1,
                purpose = "ACCOUNT_TOMBSTONE_CHANGES",
                installationId = INSTALLATION_ID,
                fromSequenceExclusive = fromSequenceExclusive,
                toSequenceInclusive = sequence,
                ledgerHeadHash = recordHash,
                records = listOf(record),
                issuedAtEpochMillis = 1_001,
            )
            val payload = Json.encodeToJsonElement(AccountTombstoneChangesWire.serializer(), batch).jsonObject
            return SignedJsonEnvelopeWire(
                tombstones = payload,
                keyId = keyId,
                signatureBase64 = sign(verifier.canonicalJson(payload)),
            )
        }

        fun policyEnvelope(
            version: Long,
            effectiveAtEpochMillis: Long = 1_000,
            expiresAtEpochMillis: Long? = null,
            maximumOfflineMillis: Long,
            offlineAccessMode: String = "BOUNDED_OFFLINE",
            contentRetentionMillis: Long = 20_000,
            allowClipboard: Boolean = false,
        ): SignedJsonEnvelopeWire {
            val policy = PolicyWire(
                policyId = POLICY_ID,
                version = version,
                issuedAtEpochMillis = 1_000,
                effectiveAtEpochMillis = effectiveAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
                contentRetentionMillis = contentRetentionMillis,
                maximumOfflineMillis = maximumOfflineMillis,
                offlineAccessMode = offlineAccessMode,
                notificationExposure = "HIDDEN",
                hardwareTrustRequirement = "TEE_OR_STRONGBOX",
                allowContentExport = false,
                allowClipboard = allowClipboard,
                allowExternalShare = false,
                allowGroupReadReceipts = false,
                screenCapturePolicy = "BLOCK",
                requireContentEnvelope = true,
                allowRtc = false,
                schemaVersion = 2,
            )
            val payload = Json.encodeToJsonElement(PolicyWire.serializer(), policy).jsonObject
            return SignedJsonEnvelopeWire(
                policy = payload,
                keyId = keyId,
                signatureBase64 = sign(verifier.canonicalJson(payload)),
            )
        }

        fun policyPublication(
            version: Long,
            minSecurityEpoch: Int,
            capabilities: List<String>,
            effectiveAtEpochMillis: Long = 1_000,
            expiresAtEpochMillis: Long = 10_000,
            contentRetentionMillis: Long = 20_000,
            allowClipboard: Boolean = false,
        ): PublicationPair {
            val policy = policyEnvelope(
                version = version,
                effectiveAtEpochMillis = effectiveAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
                maximumOfflineMillis = 10_000,
                contentRetentionMillis = contentRetentionMillis,
                allowClipboard = allowClipboard,
            )
            val policyPayload = checkNotNull(policy.policy)
            val contentPayload = kotlinx.serialization.json.JsonObject(
                policyPayload.filterKeys { it !in setOf("version", "issued_at_epoch_millis", "schema_version") }
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(contentPayload)).toHex()
            val statement = PolicyPublicationWire(
                contract = "CP-POLICY",
                signatureSchemaVersion = 1,
                operationId = "operation-policy-$version",
                revisionId = "revision-policy-$version",
                domain = "GLOBAL_POLICY",
                version = version,
                scope = "GLOBAL",
                issuedAt = 1_000,
                effectiveAt = effectiveAtEpochMillis,
                expiresAt = expiresAtEpochMillis,
                payloadDigest = digest,
                minSecurityEpoch = minSecurityEpoch,
                requiredCapabilities = capabilities,
                targetSnapshotDigest = "b".repeat(64),
            )
            val statementJson = Json.encodeToJsonElement(PolicyPublicationWire.serializer(), statement).jsonObject
            return PublicationPair(
                policy = policy,
                publication = SignedPolicyPublicationWire(
                    publication = statementJson,
                    keyId = keyId,
                    signatureBase64 = sign(verifier.canonicalJson(statementJson)),
                ),
            )
        }

        fun persistPolicies(vararg policies: SignedJsonEnvelopeWire) {
            val highestVersion = policies.maxOf { envelope ->
                Json.decodeFromJsonElement(PolicyWire.serializer(), envelope.policy!!).version
            }
            persistState(highestVersion, *policies)
        }

        fun persistState(highestPolicyVersion: Long, vararg policies: SignedJsonEnvelopeWire) {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(
                Json.encodeToString(
                    PersistedManagementState(
                        highestPolicyVersion = highestPolicyVersion,
                        trustedTimeEpochMillis = 1_000,
                        policies = policies.sortedByDescending { envelope ->
                            Json.decodeFromJsonElement(PolicyWire.serializer(), envelope.policy!!).version
                        },
                    )
                ),
                Charsets.UTF_8,
            )
        }

        private fun sign(payload: ByteArray): String = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        )

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

        private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val INSTALLATION_ID = "installation-lc22-test-0001"
        const val ACCOUNT_HASH = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val POLICY_ID = "policy-test"
    }
}
