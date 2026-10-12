/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResource
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.MEDIA_RESOURCE_CLOSURE_PROTOCOL
import io.element.android.security.dataprotection.api.ManagementControlService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableMediaClosureOwnerTest {
    @Test fun `departed external process aborts original operation without restoring capability or renewing deadline`() = runTest {
        coEvery { service.reserveMediaResources(request) } returns Unit
        val original = owner()
        original.reserve(request)
        val stored = checkNotNull(store.read(request.operationId))
        check(store.compareAndSet(request.operationId, stored.storeRevision, stored.copy(
            descriptor = stored.descriptor.copy(
                role = io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole.EXTERNAL_READ,
                phase = io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase.OWNED,
                generation = 1
            ),
            storeRevision = stored.storeRevision + 1,
            currentCapabilityIdSha256 = "e".repeat(64)
        )))
        val replacement = object : ProcessInstanceIdProvider {
            override val rawId = "replacement-process".encodeToByteArray()
            override val sha256 = sha256Hex(rawId)
        }
        var retired = 0
        val recovery = DurableMediaClosureOwner(
            service,
            store,
            Mutex(),
            replacement,
            { 1500 },
            retireRecoveredExternalOwner = { id ->
                check(id == request.operationId)
                retired++
            },
            operation = { id ->
                LifecycleOperation(
                    operationId = id,
                    lifecycleId = FullAppLifecycleId.LC_16,
                    idempotencyKey = id,
                    subjects = subjects,
                    status = LifecycleOperationStatus.RUNNING,
                    currentPhase = LifecyclePhase.INTENT_PERSISTED,
                    authorityRevisions = mapOf("media_cleanup_protocol" to MEDIA_RESOURCE_CLOSURE_PROTOCOL, "external_read_handoff" to "uid-bound-v1"),
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                    deadlineEpochMillis = 1000
                )
            }
        )
        coEvery { service.recordMediaRuntimeCleanup(any()) } throws java.io.IOException("offline")
        coEvery { service.sealMediaResources(any()) } throws java.io.IOException("offline")
        assertThat(recovery.flushBatch().needsRetry).isTrue()
        val retained = retained()
        assertThat(retained.seal?.outcome).isEqualTo(LifecycleMediaOutcome.CANCELLED)
        assertThat(retained.seal?.ownerProcessSha256).isEqualTo(stored.processInstanceIdSha256)
        assertThat(retained.runtimeFacts.values.single().releasedAtEpochMillis).isEqualTo(1500)
        coEvery { service.recordMediaRuntimeCleanup(any()) } returns "android-media-runtime-cleanup:v1:" + "d".repeat(64)
        coEvery { service.sealMediaResources(any()) } returns "android-media-manifest:v1:original-abort"
        assertThat(recovery.flushBatch().needsRetry).isFalse()
        assertThat(retained().runtimeFacts).isEqualTo(retained.runtimeFacts)
        assertThat(retained().seal).isEqualTo(retained.seal)
        assertThat(retired).isEqualTo(2)
        coVerify(exactly = 1) { service.reserveMediaResources(request) }
    }

    private class Backend : LifecycleOperationOwnerRecordBackend {
        val records = linkedMapOf<String, String>()
        var failWrite = false
        override fun get(key: String) = records[key]
        override fun put(key: String, value: String): Boolean {
            if (failWrite) throw java.io.IOException("simulated storage failure")
            records[key] = value
            return true
        }
        override fun keys() = records.keys.toList()
    }
    private val backend = Backend()
    private val store = AtomicFileLifecycleOperationOwnerStateStore(backend)
    private val service = mockk<ManagementControlService>()
    private val subjects = LifecycleSubjectHashes(
        installationId = "original-installation",
        accountIdHash = "a".repeat(64),
        matrixDeviceIdHash = "b".repeat(64)
    )
    private val request = LifecycleMediaReservation(
        "original-media-operation",
        subjects,
        listOf(LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, "c".repeat(64)))
    )
    private val fact = LifecycleMediaRuntimeCleanup(request.operationId, subjects, "c".repeat(64), 50, true, true, true)
    private fun owner(processName: String = "first-process", lifecycleId: FullAppLifecycleId = FullAppLifecycleId.LC_16) = DurableMediaClosureOwner(
        service,
        store,
        Mutex(),
        object : ProcessInstanceIdProvider {
            override val rawId = processName.encodeToByteArray()
            override val sha256 = sha256Hex(rawId)
        },
        { 100 }
    ) { id ->
            LifecycleOperation(
                operationId = id,
                lifecycleId = lifecycleId,
                idempotencyKey = id,
            subjects = subjects,
                status = LifecycleOperationStatus.RUNNING,
                currentPhase = LifecyclePhase.INTENT_PERSISTED,
            authorityRevisions = mapOf("media_cleanup_protocol" to MEDIA_RESOURCE_CLOSURE_PROTOCOL),
            createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
                deadlineEpochMillis = 1000
            )
        }
    private fun retained(id: String = request.operationId) = StoredMediaClosure.decode(checkNotNull(checkNotNull(store.read(id)).mediaClosure))

    @Test fun `coordinator cannot report seal success before original acknowledgements and retry keeps every fact`() = runTest {
        val original = LifecycleOperation(
            operationId = request.operationId,
            lifecycleId = FullAppLifecycleId.LC_17,
            idempotencyKey = request.operationId,
            subjects = subjects,
            status = LifecycleOperationStatus.RUNNING,
            currentPhase = LifecyclePhase.CLIENT_APPLIED,
            authorityRevisions = mapOf("media_cleanup_protocol" to MEDIA_RESOURCE_CLOSURE_PROTOCOL),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            deadlineEpochMillis = 1000
        )
        var now = 100L
        val process = object : ProcessInstanceIdProvider {
            override val rawId = "original-process".encodeToByteArray()
            override val sha256 = sha256Hex(rawId)
        }
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            NoopLifecycleOperationIdStore,
            store,
            process,
            object : LifecycleOperationOwnerNonceSource {
                override fun next() = ByteArray(32)
            },
            pendingStartRetryClock = { now }
        )
        coEvery { service.lifecycleOperation(request.operationId) } returns original
        coEvery { service.reserveMediaResources(request) } returns Unit
        coEvery { service.recordMediaRuntimeCleanup(fact) } returns "android-media-runtime-cleanup:v1:" + "d".repeat(64)
        coEvery { service.sealMediaResources(any()) } throws MediaClosureDeliveryFailure("MEDIA_RESOURCE_SEAL_ACK", "SERVER_REJECTED", 503)
        coordinator.reserveMediaResources(request)
        coordinator.retainMediaRuntimeCleanup(fact)
        assertThat(runCatching { coordinator.sealMediaResources(request.operationId, LifecycleMediaOutcome.SUCCEEDED) }.isFailure).isTrue()
        val first = retained()
        assertThat(first.runtimeAcknowledgements).hasSize(1)
        assertThat(first.sealAcknowledgement).isNull()
        val originalSeal = checkNotNull(first.seal)
        now = 1500 // Original deadline has passed; deliver a retained fact, never renew admission.
        coEvery { service.sealMediaResources(originalSeal) } returns "android-media-manifest:v1:retained-test-seal"
        coordinator.sealMediaResources(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        assertThat(retained().seal).isEqualTo(originalSeal)
        assertThat(retained().runtimeFacts.values).containsExactly(fact)
        assertThat(retained().sealAcknowledgement).isNotNull()
        coVerify(exactly = 1) { service.reserveMediaResources(request) }
        coVerify(exactly = 1) { service.lifecycleOperation(request.operationId) }
        coVerify(exactly = 1) { service.recordMediaRuntimeCleanup(fact) }
        coVerify(exactly = 2) { service.sealMediaResources(originalSeal) }
    }

    @Test fun `seal arriving during runtime delivery is included without another scan or wakeup`() = runTest {
        val owner = owner(lifecycleId = FullAppLifecycleId.LC_17)
        coEvery { service.reserveMediaResources(request) } returns Unit
        owner.reserve(request)
        owner.retain(fact)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { service.recordMediaRuntimeCleanup(fact) } coAnswers {
            entered.complete(Unit)
            release.await()
            "android-media-runtime-cleanup:v1:" + "d".repeat(64)
        }
        coEvery { service.sealMediaResources(any()) } returns "android-media-manifest:v1:retained-test-seal"
        val flushing = async { owner.flushBatch() }
        entered.await()
        owner.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        release.complete(Unit)
        assertThat(flushing.await().needsRetry).isFalse()
        assertThat(retained().sealAcknowledgement).isNotNull()
        coVerify(exactly = 1) { service.sealMediaResources(checkNotNull(retained().seal)) }
    }

    @Test fun `background delivery and direct stop share one operation lock without blocking another operation`() = runTest {
        val owner = owner()
        val second = request.copy(
            operationId = "another-live-operation-0001",
            resources = listOf(LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, "e".repeat(64)))
        )
        coEvery { service.reserveMediaResources(any()) } returns Unit
        owner.reserve(request)
        owner.reserve(second)
        owner.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        owner.seal(second.operationId, LifecycleMediaOutcome.SUCCEEDED)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = checkNotNull(retained().seal)
        coEvery { service.sealMediaResources(original) } coAnswers {
            entered.complete(Unit)
            release.await()
            "android-media-manifest:v1:original"
        }
        coEvery { service.sealMediaResources(checkNotNull(retained(second.operationId).seal)) } returns "android-media-manifest:v1:second"
        val first = async { owner.awaitSealAcknowledgement(request.operationId) }
        entered.await()
        val duplicate = async { owner.awaitSealAcknowledgement(request.operationId) }
        owner.awaitSealAcknowledgement(second.operationId)
        runCurrent()
        assertThat(first.isCompleted).isFalse()
        assertThat(duplicate.isCompleted).isFalse()
        assertThat(retained(second.operationId).sealAcknowledgement).isNotNull()
        release.complete(Unit)
        first.await()
        duplicate.await()
        coVerify(exactly = 1) { service.sealMediaResources(original) }
    }

    @Test fun `LC17 stop shares the same durable resource closure owner and retains its lifecycle`() = runTest {
        val stopOwner = owner(lifecycleId = FullAppLifecycleId.LC_17)
        coEvery { service.reserveMediaResources(request) } returns Unit
        stopOwner.reserve(request)
        assertThat(store.read(request.operationId)?.descriptor?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_17)
        stopOwner.retain(fact)
        stopOwner.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        assertThat(retained().runtimeFacts.values).containsExactly(fact)
    }

    @Test fun `intent precedes transport and lost admission response grants no local success`() = runTest {
        val owner = owner()
        coEvery { service.reserveMediaResources(request) } coAnswers {
            assertThat(retained().reservation).isEqualTo(request)
            throw java.io.IOException("simulated response loss")
        }
        assertThat(runCatching { owner.reserve(request) }.isFailure).isTrue()
        assertThat(retained().admitted).isEmpty()
        coEvery { service.reserveMediaResources(request) } returns Unit
        owner.reserve(request)
        assertThat(retained().admitted).containsExactlyElementsIn(request.resources)
    }

    @Test fun `failed local persistence prevents resource admission`() = runTest {
        backend.failWrite = true
        assertThat(runCatching { owner().reserve(request) }.isFailure).isTrue()
        coVerify(exactly = 0) { service.reserveMediaResources(any()) }
        assertThat(backend.records).isEmpty()
    }

    @Test fun `restart retransmits exact retained facts and keeps failed acknowledgements pending`() = runTest {
        val first = owner()
        coEvery { service.reserveMediaResources(request) } returns Unit
        first.reserve(request)
        first.retain(fact)
        first.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        val seal = checkNotNull(retained().seal)
        coEvery { service.recordMediaRuntimeCleanup(fact) } throws java.io.IOException("lost response")
        coEvery { service.sealMediaResources(seal) } throws java.io.IOException("offline")
        assertThat(first.flushBatch().needsRetry).isTrue()
        assertThat(retained().runtimeAcknowledgements).isEmpty()
        assertThat(retained().sealAcknowledgement).isNull()
        val restarted = owner("replacement-process")
        coEvery { service.recordMediaRuntimeCleanup(fact) } returns "android-media-runtime-cleanup:v1:" + "d".repeat(64)
        coEvery { service.sealMediaResources(seal) } returns "android-media-manifest:v1:retained-test-seal"
        assertThat(restarted.flushBatch().needsRetry).isFalse()
        assertThat(retained().runtimeFacts[fact.recordSha256]).isEqualTo(fact)
        assertThat(retained().seal).isEqualTo(seal)
        coVerify(exactly = 1) { service.reserveMediaResources(request) }
        coVerify(exactly = 2) { service.recordMediaRuntimeCleanup(fact) }
    }

    @Test fun `sealing producer does not manufacture runtime cleanup or permit another resource`() = runTest {
        val owner = owner()
        coEvery { service.reserveMediaResources(request) } returns Unit
        owner.reserve(request)
        owner.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED)
        coEvery { service.sealMediaResources(any()) } returns "android-media-manifest:v1:retained-test-seal"
        owner.flushBatch()
        coVerify(exactly = 0) { service.recordMediaRuntimeCleanup(any()) }
        assertThat(retained().runtimeFacts).isEmpty()
        assertThat(runCatching { owner.reserve(request) }.isFailure).isTrue()
        coVerify(exactly = 1) { service.reserveMediaResources(any()) }
    }

    @Test fun `replacement process cannot infer success or change original release time`() = runTest {
        coEvery { service.reserveMediaResources(request) } returns Unit
        owner().reserve(request)
        val recovery = owner("replacement-process")
        assertThat(runCatching { recovery.seal(request.operationId, LifecycleMediaOutcome.SUCCEEDED) }.isFailure).isTrue()
        recovery.retain(fact)
        assertThat(runCatching { recovery.retain(fact.copy(releasedAtEpochMillis = 60)) }.isFailure).isTrue()
        recovery.seal(request.operationId, LifecycleMediaOutcome.FAILED_FINAL)
        assertThat(retained().seal?.outcome).isEqualTo(LifecycleMediaOutcome.FAILED_FINAL)
    }

    @Test fun `media records reject old binary schema and scan preserves corrupt independent evidence`() = runTest {
        coEvery { service.reserveMediaResources(request) } returns Unit
        owner().reserve(request)
        assertThat(backend.records.values.single()).contains("\"schema\":2")
        backend.records["owner_" + "0".repeat(64)] = "invalid retained evidence"
        val scan = store.scanMediaOwners(null, 32)
        assertThat(scan.records.map { it.descriptor.operationId }).containsExactly(request.operationId)
        assertThat(scan.unreadableRecords).isEqualTo(1)
        assertThat(backend.records["owner_" + "0".repeat(64)]).isEqualTo("invalid retained evidence")
    }
}
