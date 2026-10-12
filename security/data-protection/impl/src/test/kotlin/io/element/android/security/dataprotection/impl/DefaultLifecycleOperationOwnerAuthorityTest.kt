/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import android.util.AtomicFile
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAcquireResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCapability
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerDescriptor
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerMutationResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRejection
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class DefaultLifecycleOperationOwnerAuthorityTest {
    @Test fun `external read role requires server admitted protocol and cannot transfer to upload`() = runTest {
        val legacy = fixture()
        assertThat(legacy.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.EXTERNAL_READ, ACQUIRE_COMMAND))
            .isInstanceOf(LifecycleOperationOwnerAcquireResult.Rejected::class.java)
        val admitted = fixture(operation = operation(OPERATION).copy(authorityRevisions = mapOf("external_read_handoff" to "uid-bound-v1")))
        val acquired = admitted.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.EXTERNAL_READ, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Granted
        assertThat(admitted.coordinator.transferOwner(acquired.capability, LifecycleOperationOwnerRole.UPLOAD, TRANSFER_COMMAND))
            .isInstanceOf(LifecycleOperationOwnerMutationResult.Rejected::class.java)
        assertThat(admitted.coordinator.completeOwner(acquired.capability, COMPLETE_COMMAND))
            .isInstanceOf(LifecycleOperationOwnerMutationResult.Released::class.java)
        assertThat(admitted.coordinator.ownerState(OPERATION)?.role).isEqualTo(LifecycleOperationOwnerRole.EXTERNAL_READ)
    }

    @Test
    fun `acquire transfer replay late cancel and complete preserve one owner and emit no lifecycle receipt`() = runTest {
        val fixture = fixture()
        val acquired = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Granted
        val acquireReplay = fixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        assertThat(acquireReplay.capability).isSameInstanceAs(acquired.capability)
        assertThat(acquireReplay.transition).isEqualTo(acquired.transition)
        val transferred = fixture.coordinator.transferOwner(
            acquired.capability,
            LifecycleOperationOwnerRole.UPLOAD,
            TRANSFER_COMMAND,
        ) as LifecycleOperationOwnerMutationResult.Transferred

        val replay = fixture.coordinator.transferOwner(
            acquired.capability,
            LifecycleOperationOwnerRole.UPLOAD,
            TRANSFER_COMMAND,
        ) as LifecycleOperationOwnerMutationResult.Transferred
        assertThat(replay.capability).isSameInstanceAs(transferred.capability)

        val lateCancel = fixture.coordinator.cancelOwner(
            acquired.capability,
            NEW_CANCEL_COMMAND,
            io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(lateCancel.reason).isEqualTo(LifecycleOperationOwnerRejection.STALE_GENERATION)

        val conflictingReplay = fixture.coordinator.cancelOwner(
            acquired.capability,
            TRANSFER_COMMAND,
            io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(conflictingReplay.reason).isEqualTo(LifecycleOperationOwnerRejection.COMMAND_CONFLICT)

        val completed = fixture.coordinator.completeOwner(transferred.capability, COMPLETE_COMMAND)
            as LifecycleOperationOwnerMutationResult.Released
        val completionReplay = fixture.coordinator.completeOwner(transferred.capability, COMPLETE_COMMAND)
            as LifecycleOperationOwnerMutationResult.Released
        assertThat(completionReplay.transition).isEqualTo(completed.transition)
        assertThat(completed.transition.requestFingerprintSha256).hasLength(64)
        assertThat(completed.transition.fromCapabilityIdSha256).hasLength(64)
        assertThat(completed.transition.toCapabilityIdSha256).isNull()
        coVerify(exactly = 0) { fixture.service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `concurrent acquire and owner mutation races have one winner`() = runTest {
        val fixture = fixture()
        val acquireResults = listOf(
            async { fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND) },
            async { fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ALT_ACQUIRE_COMMAND) },
        ).awaitAll()
        assertThat(acquireResults.filterIsInstance<LifecycleOperationOwnerAcquireResult.Granted>()).hasSize(1)
        assertThat(acquireResults.filterIsInstance<LifecycleOperationOwnerAcquireResult.Rejected>().single().reason)
            .isEqualTo(LifecycleOperationOwnerRejection.OWNER_ALREADY_HELD)
        val capability = acquireResults.filterIsInstance<LifecycleOperationOwnerAcquireResult.Granted>().single().capability

        val mutationResults = listOf(
            async { fixture.coordinator.transferOwner(capability, LifecycleOperationOwnerRole.UPLOAD, TRANSFER_COMMAND) },
            async {
                fixture.coordinator.cancelOwner(
                    capability,
                    NEW_CANCEL_COMMAND,
                    io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
                )
            },
        ).awaitAll()
        assertThat(mutationResults.count { it !is LifecycleOperationOwnerMutationResult.Rejected }).isEqualTo(1)
        assertThat(mutationResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Rejected>().single().reason)
            .isEqualTo(LifecycleOperationOwnerRejection.STALE_GENERATION)
    }

    @Test
    fun `transfer transfer and complete cancel races have one durable winner`() = runTest {
        val transferFixture = fixture()
        val preview = transferFixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        val transferResults = listOf(
            async {
                transferFixture.coordinator.transferOwner(
                    preview.capability,
                    LifecycleOperationOwnerRole.UPLOAD,
                    TRANSFER_COMMAND,
                )
            },
            async {
                transferFixture.coordinator.transferOwner(
                    preview.capability,
                    LifecycleOperationOwnerRole.UPLOAD,
                    ALT_TRANSFER_COMMAND,
                )
            },
        ).awaitAll()
        assertThat(transferResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Transferred>()).hasSize(1)
        assertThat(transferResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Rejected>().single().reason)
            .isEqualTo(LifecycleOperationOwnerRejection.STALE_GENERATION)

        val upload = transferResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Transferred>()
            .single()
            .capability
        val terminalResults = listOf(
            async { transferFixture.coordinator.completeOwner(upload, COMPLETE_COMMAND) },
            async {
                transferFixture.coordinator.cancelOwner(
                    upload,
                    NEW_CANCEL_COMMAND,
                    io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
                )
            },
        ).awaitAll()
        assertThat(terminalResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Released>()).hasSize(1)
        assertThat(terminalResults.filterIsInstance<LifecycleOperationOwnerMutationResult.Rejected>().single().reason)
            .isEqualTo(LifecycleOperationOwnerRejection.STALE_GENERATION)
        assertThat(transferFixture.coordinator.ownerState(OPERATION)?.phase)
            .isAnyOf(LifecycleOperationOwnerPhase.COMPLETED, LifecycleOperationOwnerPhase.AVAILABLE)
    }

    @Test
    fun `preview cannot complete and recovery rejects wrong generation or role`() = runTest {
        val previewFixture = fixture()
        val preview = previewFixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        val prematureComplete = previewFixture.coordinator.completeOwner(preview.capability, COMPLETE_COMMAND)
            as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(prematureComplete.reason).isEqualTo(LifecycleOperationOwnerRejection.TRANSITION_NOT_ALLOWED)

        val directory = freshDirectory()
        val first = fixture(
            store = AtomicFileLifecycleOperationOwnerStateStore(directory),
            process = FixedProcessInstanceIdProvider("recovery-first"),
        )
        first.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
        val second = fixture(
            store = AtomicFileLifecycleOperationOwnerStateStore(directory),
            process = FixedProcessInstanceIdProvider("recovery-second"),
        )
        val recovery = checkNotNull(second.coordinator.ownerState(OPERATION))
        val wrongGeneration = second.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            RECOVERY_COMMAND,
            recovery.generation + 1,
        ) as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(wrongGeneration.reason).isEqualTo(LifecycleOperationOwnerRejection.RECOVERY_MISMATCH)
        val wrongRole = second.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.UPLOAD,
            ALT_RECOVERY_COMMAND,
            recovery.generation,
        ) as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(wrongRole.reason).isEqualTo(LifecycleOperationOwnerRejection.RECOVERY_MISMATCH)
    }

    @Test
    fun `authority gates lifecycle status subjects and caller-created capability`() = runTest {
        val unsupported = fixture(operation = operation(OPERATION, FullAppLifecycleId.LC_13))
            .coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(unsupported.reason).isEqualTo(LifecycleOperationOwnerRejection.UNSUPPORTED_LIFECYCLE)

        val blocked = fixture(operation = operation(OPERATION, status = LifecycleOperationStatus.BLOCKED))
            .coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(blocked.reason).isEqualTo(LifecycleOperationOwnerRejection.OPERATION_NOT_OWNABLE)

        val terminal = fixture(operation = operation(OPERATION, status = LifecycleOperationStatus.SUCCEEDED))
            .coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(terminal.reason).isEqualTo(LifecycleOperationOwnerRejection.OPERATION_TERMINAL)

        val missingRoom = fixture(operation = operation(OPERATION).copy(subjects = SUBJECTS.copy(roomIdHash = null)))
            .coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(missingRoom.reason).isEqualTo(LifecycleOperationOwnerRejection.OPERATION_SUBJECTS_INCOMPLETE)

        val valid = fixture()
        val forged = valid.coordinator.cancelOwner(
            ExternalCapability(descriptor()),
            NEW_CANCEL_COMMAND,
            io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(forged.reason).isEqualTo(LifecycleOperationOwnerRejection.CAPABILITY_INVALID)
    }

    @Test
    fun `publication failure is durable recovery-required and exact recovery mints a new generation`() = runTest {
        val fixture = fixture()
        fixture.coordinator.beforeOwnerCapabilityPublish = { error("injected publish failure") }
        val failed = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(failed.reason).isEqualTo(LifecycleOperationOwnerRejection.CAPABILITY_PUBLICATION_FAILED)
        assertThat(failed.current?.phase).isEqualTo(LifecycleOperationOwnerPhase.RECOVERY_REQUIRED)

        fixture.coordinator.beforeOwnerCapabilityPublish = null
        val sameCommand = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(sameCommand.reason).isEqualTo(LifecycleOperationOwnerRejection.RECOVERY_REQUIRED)
        val recovered = fixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            RECOVERY_COMMAND,
            expectedRecoveryGeneration = checkNotNull(failed.current).generation,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        assertThat(recovered.capability.descriptor.generation).isGreaterThan(failed.current!!.generation)
    }

    @Test
    fun `cancellation after CAS cannot interrupt synchronous capability publication`() = runTest {
        val fixture = fixture()
        lateinit var ownerJob: Job
        fixture.coordinator.beforeOwnerCapabilityPublish = { ownerJob.cancel() }
        var first: LifecycleOperationOwnerAcquireResult? = null
        ownerJob = launch {
            first = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
        }
        advanceUntilIdle()
        assertThat(first).isInstanceOf(LifecycleOperationOwnerAcquireResult.Granted::class.java)
        fixture.coordinator.beforeOwnerCapabilityPublish = null
        val replay = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Granted
        assertThat(replay.capability).isSameInstanceAs((first as LifecycleOperationOwnerAcquireResult.Granted).capability)
    }

    @Test
    fun `real durable store forces process recovery and rejects old process capability`() = runTest {
        val directory = freshDirectory()
        val operation = operation(OPERATION)
        val first = fixture(
            operation = operation,
            store = AtomicFileLifecycleOperationOwnerStateStore(directory),
            process = FixedProcessInstanceIdProvider("process-one"),
        )
        val acquired = first.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Granted

        val second = fixture(
            operation = operation,
            store = AtomicFileLifecycleOperationOwnerStateStore(directory),
            process = FixedProcessInstanceIdProvider("process-two"),
        )
        val recoveredState = checkNotNull(second.coordinator.ownerState(OPERATION))
        assertThat(recoveredState.phase).isEqualTo(LifecycleOperationOwnerPhase.RECOVERY_REQUIRED)
        val oldCapability = second.coordinator.cancelOwner(
            acquired.capability,
            NEW_CANCEL_COMMAND,
            io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.PROCESS_RECOVERY_ABORTED,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(oldCapability.reason).isEqualTo(LifecycleOperationOwnerRejection.PROCESS_INSTANCE_MISMATCH)
        val reacquired = second.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            RECOVERY_COMMAND,
            recoveredState.generation,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        assertThat(reacquired.capability.descriptor.generation).isGreaterThan(recoveredState.generation)
    }

    @Test
    fun `real durable store survives reconstruction and corrupt record fails closed`() {
        val directory = freshDirectory()
        val store = AtomicFileLifecycleOperationOwnerStateStore(directory)
        val state = StoredLifecycleOperationOwnerState(
            descriptor = descriptor(),
            processInstanceIdSha256 = "d".repeat(64),
            currentCapabilityIdSha256 = null,
            storeRevision = 1,
            transitions = emptyList(),
        )
        assertThat(store.compareAndSet(OPERATION, null, state)).isTrue()
        assertThat(AtomicFileLifecycleOperationOwnerStateStore(directory).read(OPERATION)).isEqualTo(state)

        val recordFile = File(directory, "owner_${sha256Hex(OPERATION)}.json")
        AtomicFile(recordFile).startWrite().use {
            it.write("{interrupted-before-rename".encodeToByteArray())
            it.fd.sync()
        }
        assertThat(AtomicFileLifecycleOperationOwnerStateStore(directory).read(OPERATION)).isEqualTo(state)

        recordFile.outputStream().use {
            it.write("{corrupt".encodeToByteArray())
            it.fd.sync()
        }
        assertThrows(SerializationException::class.java) {
            AtomicFileLifecycleOperationOwnerStateStore(directory).read(OPERATION)
        }
    }

    @Test
    fun `atomic file interruption before and after rename has deterministic restart state`() = runTest {
        val beforeDirectory = freshDirectory()
        val beforeFault = ControlledAtomicFileFaultInjector(failBeforeWrites = mutableSetOf(1))
        val beforeFixture = fixture(
            store = AtomicFileLifecycleOperationOwnerStateStore(beforeDirectory, beforeFault),
            process = FixedProcessInstanceIdProvider("before-rename"),
        )
        val beforeResult = beforeFixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(beforeResult.reason).isEqualTo(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE)
        assertThat(AtomicFileLifecycleOperationOwnerStateStore(beforeDirectory).read(OPERATION)).isNull()

        val afterDirectory = freshDirectory()
        val afterFault = ControlledAtomicFileFaultInjector(failAfterWrites = mutableSetOf(1))
        val afterFixture = fixture(
            store = AtomicFileLifecycleOperationOwnerStateStore(afterDirectory, afterFault),
            process = FixedProcessInstanceIdProvider("after-rename-first"),
        )
        val afterResult = afterFixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(afterResult.reason).isEqualTo(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE)
        val reconstructed = fixture(
            store = AtomicFileLifecycleOperationOwnerStateStore(afterDirectory),
            process = FixedProcessInstanceIdProvider("after-rename-second"),
        )
        val recovered = checkNotNull(reconstructed.coordinator.ownerState(OPERATION))
        assertThat(recovered.phase).isEqualTo(LifecycleOperationOwnerPhase.RECOVERY_REQUIRED)
        assertThat(recovered.generation).isEqualTo(2)
    }

    @Test
    fun `recovery write failure keeps every owner entry point fail closed until durable recovery succeeds`() = runTest {
        val directory = freshDirectory()
        val fault = ControlledAtomicFileFaultInjector(
            failBeforeWrites = mutableSetOf(3, 4, 5, 6, 7, 8),
        )
        val fixture = fixture(store = AtomicFileLifecycleOperationOwnerStateStore(directory, fault))
        val preview = fixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        fixture.coordinator.beforeOwnerCapabilityPublish = { error("injected transfer publication failure") }
        val publicationFailure = fixture.coordinator.transferOwner(
            preview.capability,
            LifecycleOperationOwnerRole.UPLOAD,
            TRANSFER_COMMAND,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(publicationFailure.reason)
            .isEqualTo(LifecycleOperationOwnerRejection.CAPABILITY_PUBLICATION_FAILED)
        assertThat(publicationFailure.current?.phase).isEqualTo(LifecycleOperationOwnerPhase.OWNED)

        val ownerStateFailure = runCatching { fixture.coordinator.ownerState(OPERATION) }.exceptionOrNull()
        assertThat(ownerStateFailure).isInstanceOf(IllegalStateException::class.java)
        assertStateStoreUnavailable(
            fixture.coordinator.acquireOwner(
                OPERATION,
                LifecycleOperationOwnerRole.UPLOAD,
                ALT_ACQUIRE_COMMAND,
            ),
        )
        assertStateStoreUnavailable(
            fixture.coordinator.transferOwner(
                preview.capability,
                LifecycleOperationOwnerRole.UPLOAD,
                ALT_TRANSFER_COMMAND,
            ),
        )
        assertStateStoreUnavailable(fixture.coordinator.completeOwner(preview.capability, COMPLETE_COMMAND))
        assertStateStoreUnavailable(
            fixture.coordinator.cancelOwner(
                preview.capability,
                NEW_CANCEL_COMMAND,
                io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.USER_CANCELLED,
            ),
        )

        fault.failBeforeWrites.clear()
        val recovered = checkNotNull(fixture.coordinator.ownerState(OPERATION))
        assertThat(recovered.phase).isEqualTo(LifecycleOperationOwnerPhase.RECOVERY_REQUIRED)
        assertThat(recovered.role).isEqualTo(LifecycleOperationOwnerRole.UPLOAD)
    }

    @Test
    fun `mutation CAS failure retains old capability and authoritative terminal re-read blocks transition`() = runTest {
        val directory = freshDirectory()
        val fault = ControlledAtomicFileFaultInjector(failBeforeWrites = mutableSetOf(2))
        val fixture = fixture(store = AtomicFileLifecycleOperationOwnerStateStore(directory, fault))
        val preview = fixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            ACQUIRE_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Granted
        val failedTransfer = fixture.coordinator.transferOwner(
            preview.capability,
            LifecycleOperationOwnerRole.UPLOAD,
            TRANSFER_COMMAND,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(failedTransfer.reason).isEqualTo(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE)
        assertThat(fixture.coordinator.ownerState(OPERATION)?.role).isEqualTo(LifecycleOperationOwnerRole.PREVIEW)

        fault.failBeforeWrites.clear()
        coEvery { fixture.service.lifecycleOperation(OPERATION) } returns operation(
            OPERATION,
            status = LifecycleOperationStatus.SUCCEEDED,
        )
        val terminal = fixture.coordinator.transferOwner(
            preview.capability,
            LifecycleOperationOwnerRole.UPLOAD,
            ALT_TRANSFER_COMMAND,
        ) as LifecycleOperationOwnerMutationResult.Rejected
        assertThat(terminal.reason).isEqualTo(LifecycleOperationOwnerRejection.OPERATION_TERMINAL)
        assertThat(fixture.coordinator.ownerState(OPERATION)?.role).isEqualTo(LifecycleOperationOwnerRole.PREVIEW)
    }

    @Test
    fun `cancellation before durable CAS leaves real file store empty`() = runTest {
        val directory = freshDirectory()
        val fixture = fixture(store = AtomicFileLifecycleOperationOwnerStateStore(directory))
        coEvery { fixture.service.lifecycleOperation(OPERATION) } throws CancellationException("before CAS")
        val failure = runCatching {
            fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(AtomicFileLifecycleOperationOwnerStateStore(directory).read(OPERATION)).isNull()
    }

    @Test
    fun `durable commit failure publishes neither state nor capability`() = runTest {
        val backend = object : LifecycleOperationOwnerRecordBackend {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String): Boolean = false
        }
        val fixture = fixture(store = AtomicFileLifecycleOperationOwnerStateStore(backend))
        val result = fixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(result.reason).isEqualTo(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE)
        assertThat(fixture.coordinator.ownerState(OPERATION)).isNull()
    }

    @Test
    fun `cancel permits explicit upload retry and exhausted generation fails closed`() = runTest {
        val retryFixture = fixture()
        val preview = retryFixture.coordinator.acquireOwner(OPERATION, LifecycleOperationOwnerRole.PREVIEW, ACQUIRE_COMMAND)
            as LifecycleOperationOwnerAcquireResult.Granted
        retryFixture.coordinator.cancelOwner(
            preview.capability,
            NEW_CANCEL_COMMAND,
            io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason.UPLOAD_FAILED,
        )
        val upload = retryFixture.coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.UPLOAD,
            ALT_ACQUIRE_COMMAND,
        )
        assertThat(upload).isInstanceOf(LifecycleOperationOwnerAcquireResult.Granted::class.java)

        val store = AtomicFileLifecycleOperationOwnerStateStore(freshDirectory())
        store.compareAndSet(
            OPERATION,
            null,
            StoredLifecycleOperationOwnerState(
                descriptor = descriptor().copy(generation = Long.MAX_VALUE),
                processInstanceIdSha256 = "e".repeat(64),
                currentCapabilityIdSha256 = null,
                storeRevision = 1,
                transitions = emptyList(),
            ),
        )
        val exhausted = fixture(store = store).coordinator.acquireOwner(
            OPERATION,
            LifecycleOperationOwnerRole.PREVIEW,
            RECOVERY_COMMAND,
        ) as LifecycleOperationOwnerAcquireResult.Rejected
        assertThat(exhausted.reason).isEqualTo(LifecycleOperationOwnerRejection.GENERATION_EXHAUSTED)
    }

    private fun fixture(
        operation: LifecycleOperation = operation(OPERATION),
        store: LifecycleOperationOwnerStateStore = AtomicFileLifecycleOperationOwnerStateStore(freshDirectory()),
        process: ProcessInstanceIdProvider = FixedProcessInstanceIdProvider("process-default"),
    ): Fixture {
        val service = mockk<ManagementControlService>()
        coEvery { service.lifecycleOperation(operation.operationId) } returns operation
        return Fixture(
            service = service,
            coordinator = DefaultAppOperationalStateCoordinator(
                managementControlService = service,
                operationIdStore = NoopLifecycleOperationIdStore,
                ownerStateStore = store,
                processInstanceIdProvider = process,
                ownerNonceSource = SequenceNonceSource(),
            ),
        )
    }

    private fun freshDirectory() = File(
        RuntimeEnvironment.getApplication().cacheDir,
        "owner-test-${UUID.randomUUID()}",
    ).also { check(it.mkdirs()) }

    private fun assertStateStoreUnavailable(result: Any) {
        val reason = when (result) {
            is LifecycleOperationOwnerAcquireResult.Rejected -> result.reason
            is LifecycleOperationOwnerMutationResult.Rejected -> result.reason
            else -> error("Expected rejected owner result, got $result")
        }
        assertThat(reason).isEqualTo(LifecycleOperationOwnerRejection.STATE_STORE_UNAVAILABLE)
    }

    private data class Fixture(
        val service: ManagementControlService,
        val coordinator: DefaultAppOperationalStateCoordinator,
    )

    private class FixedProcessInstanceIdProvider(value: String) : ProcessInstanceIdProvider {
        override val rawId: ByteArray = value.encodeToByteArray()
        override val sha256: String = sha256Hex(rawId)
    }

    private class SequenceNonceSource : LifecycleOperationOwnerNonceSource {
        private val sequence = AtomicLong()
        override fun next(): ByteArray = "nonce-${sequence.incrementAndGet()}".encodeToByteArray()
    }

    private class ControlledAtomicFileFaultInjector(
        val failBeforeWrites: MutableSet<Int> = mutableSetOf(),
        val failAfterWrites: MutableSet<Int> = mutableSetOf(),
    ) : LifecycleOperationOwnerAtomicFileFaultInjector {
        private var writeNumber = 0
        private var currentWriteNumber = 0

        override fun beforeFinishWrite() {
            currentWriteNumber = ++writeNumber
            if (currentWriteNumber in failBeforeWrites) throw IOException("injected before atomic rename")
        }

        override fun afterFinishWrite() {
            if (currentWriteNumber in failAfterWrites) throw IOException("injected after atomic rename")
        }
    }

    private class ExternalCapability(
        override val descriptor: LifecycleOperationOwnerDescriptor,
    ) : LifecycleOperationOwnerCapability

    private companion object {
        const val OPERATION = "owner-operation-0001"
        const val ACQUIRE_COMMAND = "owner-acquire-0001"
        const val ALT_ACQUIRE_COMMAND = "owner-acquire-0002"
        const val TRANSFER_COMMAND = "owner-transfer-0001"
        const val ALT_TRANSFER_COMMAND = "owner-transfer-0002"
        const val COMPLETE_COMMAND = "owner-complete-0001"
        const val NEW_CANCEL_COMMAND = "owner-cancel-0001"
        const val RECOVERY_COMMAND = "owner-recovery-0001"
        const val ALT_RECOVERY_COMMAND = "owner-recovery-0002"
        val SUBJECTS = LifecycleSubjectHashes(
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64),
            roomIdHash = "c".repeat(64),
        )

        fun descriptor() = LifecycleOperationOwnerDescriptor(
            operationId = OPERATION,
            lifecycleId = FullAppLifecycleId.LC_16,
            role = null,
            phase = LifecycleOperationOwnerPhase.AVAILABLE,
            generation = 0,
            authoritativeOperationUpdatedAtEpochMillis = 2,
        )

        fun operation(
            operationId: String,
            lifecycleId: FullAppLifecycleId = FullAppLifecycleId.LC_16,
            status: LifecycleOperationStatus = LifecycleOperationStatus.RUNNING,
        ) = LifecycleOperation(
            operationId = operationId,
            lifecycleId = lifecycleId,
            idempotencyKey = operationId,
            subjects = SUBJECTS,
            status = status,
            currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = 10_000,
            terminalReceiptId = if (status.isTerminal) "terminal-receipt-0001" else null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
    }
}
