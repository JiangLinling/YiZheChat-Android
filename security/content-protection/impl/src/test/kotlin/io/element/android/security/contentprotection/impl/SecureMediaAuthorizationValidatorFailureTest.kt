/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthority
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDecision
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDescription
import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SecureMediaAuthorizationValidatorFailureTest {
    @Test
    fun `forged non-protected authority is rejected before issuer validation`() {
        val service = mockk<ContentProtectionService>(relaxed = true)
        val validator = validator(service, Gate())
        val forged = object : SecureMediaReleaseAuthority {
            override fun close() = Unit
        }

        val result = validator.validate(forged, CONTENT_ID, CONTEXT_SHA256)

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE))
        verify(exactly = 0) { service.validateProtectedMediaReleaseAuthority(any(), any(), any()) }
    }

    @Test
    fun `cross issuer decision is mapped fail closed`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        every {
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        } returns ProtectedMediaReleaseAuthorityDecision.OWNER_MISMATCH
        val validator = validator(service, Gate())

        val result = validator.validate(authority, CONTENT_ID, CONTEXT_SHA256)

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.OWNER_MISMATCH))
    }

    @Test
    fun `validated issuer still rejects expected content and context mismatch`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        every {
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        } returns ProtectedMediaReleaseAuthorityDecision.VALIDATED
        val validator = validator(service, Gate())

        assertThat(validator.validate(authority, "wrong-content", CONTEXT_SHA256))
            .isEqualTo(rejected(SecureMediaAuthorizationRejection.RELEASE_MISMATCH))
        assertThat(validator.validate(authority, CONTENT_ID, "f".repeat(64)))
            .isEqualTo(rejected(SecureMediaAuthorizationRejection.RELEASE_MISMATCH))
    }

    @Test
    fun `preview denial and revision mismatch reject before issuer validation`() {
        val service = mockk<ContentProtectionService>(relaxed = true)
        val authority = TestProtectedAuthority(description())

        assertThat(validator(service, Gate(allowsPreview = false)).validate(
            authority,
            CONTENT_ID,
            CONTEXT_SHA256,
        )).isEqualTo(rejected(SecureMediaAuthorizationRejection.PREVIEW_DENIED))

        assertThat(validator(service, Gate(revision = 3)).validate(
            authority,
            CONTENT_ID,
            CONTEXT_SHA256,
            expectedAuthorizationRevision = 2,
        )).isEqualTo(rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH))

        verify(exactly = 0) { service.validateProtectedMediaReleaseAuthority(any(), any(), any()) }
    }

    @Test
    fun `monotonic clock failure is authority unavailable before issuer validation`() {
        val service = mockk<ContentProtectionService>(relaxed = true)
        val authority = TestProtectedAuthority(description())
        val failingClock = object : OfflineLicenseClock {
            override fun wallClockEpochMillis(): Long = 10_000
            override fun elapsedRealtimeMillis(): Long = error("clock unavailable")
        }

        val result = validator(service, Gate(), clock = failingClock).validate(
            authority,
            CONTENT_ID,
            CONTEXT_SHA256,
        )

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE))
        verify(exactly = 0) { service.validateProtectedMediaReleaseAuthority(any(), any(), any()) }
    }

    @Test
    fun `boot transition during issuer validation is binding invalid`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        every {
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        } returns ProtectedMediaReleaseAuthorityDecision.VALIDATED
        val calls = AtomicInteger()
        val runtime = DeviceRuntimeBindingProvider { if (calls.getAndIncrement() == 0) 1 else 2 }

        val result = validator(service, Gate(), runtimeBindingProvider = runtime).validate(
            authority,
            CONTENT_ID,
            CONTEXT_SHA256,
        )

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.BINDING_INVALID))
    }

    @Test
    fun `preview denial during issuer validation is rejected`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        val gate = Gate()
        every {
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        } answers {
            gate.denyPreview()
            ProtectedMediaReleaseAuthorityDecision.VALIDATED
        }
        val validator = validator(service, gate)

        val result = validator.validate(authority, CONTENT_ID, CONTEXT_SHA256)

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.PREVIEW_DENIED))
    }

    @Test
    fun `authorization revision change during issuer validation is rejected`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        val gate = Gate()
        every {
            service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L)
        } answers {
            gate.advanceAuthorizationRevision()
            ProtectedMediaReleaseAuthorityDecision.VALIDATED
        }
        val validator = validator(service, gate)

        val result = validator.validate(authority, CONTENT_ID, CONTEXT_SHA256)

        assertThat(result).isEqualTo(rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH))
    }

    @Test
    fun `every issuer rejection preserves its precise reason including expiry and closure`() {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description())
        val mappings = mapOf(
            ProtectedMediaReleaseAuthorityDecision.EXPIRED to SecureMediaAuthorizationRejection.EXPIRED,
            ProtectedMediaReleaseAuthorityDecision.CLOSED to SecureMediaAuthorizationRejection.CLOSED,
            ProtectedMediaReleaseAuthorityDecision.BINDING_INVALID to SecureMediaAuthorizationRejection.BINDING_INVALID,
            ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE to SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE,
            ProtectedMediaReleaseAuthorityDecision.RELEASE_MISMATCH to SecureMediaAuthorizationRejection.RELEASE_MISMATCH,
            ProtectedMediaReleaseAuthorityDecision.OWNER_MISMATCH to SecureMediaAuthorizationRejection.OWNER_MISMATCH,
        )
        mappings.forEach { (decision, expected) ->
            every { service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0L) } returns decision
            assertThat(validator(service, Gate()).validate(authority, CONTENT_ID, CONTEXT_SHA256)).isEqualTo(rejected(expected))
        }
    }

    private fun admissionOperation(request: LifecycleOperationRequest, status: LifecycleOperationStatus = LifecycleOperationStatus.RUNNING) =
        LifecycleOperation(operationId = request.operationId, lifecycleId = request.lifecycleId, idempotencyKey = request.idempotencyKey,
            subjects = LifecycleSubjectHashes(request.accountIdHash, "test-installation", request.matrixDeviceIdHash, request.roomIdHash, request.eventIdHash),
            status = status, currentPhase = LifecyclePhase.INTENT_PERSISTED, steps = setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE,
                LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE, LifecycleComponent.KMS).map {
                LifecycleStep("read-${it.name}", it,
                    status = if (status == LifecycleOperationStatus.SUCCEEDED) LifecycleStepStatus.COMPLETE else LifecycleStepStatus.PENDING, attemptCount = 0,
                    receiptReference = if (status.isTerminal) "original-${it.name}-receipt" else null,
                    updatedAtEpochMillis = System.currentTimeMillis())
            }, deadlineEpochMillis = request.deadlineEpochMillis,
            terminalReceiptId = if (status.isTerminal) "original-transfer-receipt" else null, createdAtEpochMillis = System.currentTimeMillis(),
            updatedAtEpochMillis = System.currentTimeMillis())

    @Test fun `fresh read persists distinct LC16 identity before installing and keeps terminal download untouched`() = runTest {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description().copy(mode = OfflineLeaseMode.ONLINE_REQUIRED))
        every { service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0) } returns ProtectedMediaReleaseAuthorityDecision.VALIDATED
        every { service.protectedMediaDeadlineElapsedRealtimeMillis(authority) } returns 21_000
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val owner = SecureTransientMediaOwner("original-download-operation", "a".repeat(64), "b".repeat(64), "test-installation", "c".repeat(64), null)
        val originalRequest = lifecycleOperationRequest(FullAppLifecycleId.LC_16, "download", owner.accountIdHash,
            owner.matrixDeviceIdHash, owner.roomIdHash, owner.eventIdHash, owner.operationId)
        val original = admissionOperation(originalRequest, LifecycleOperationStatus.SUCCEEDED).copy(terminalReceiptId = "original-transfer-receipt")
        val operations = mutableMapOf(owner.operationId to original)
        val starts = mutableListOf<LifecycleOperationRequest>()
        val receipts = mutableListOf<LifecycleClientReceipt>()
        coEvery { coordinator.operation(any()) } answers { checkNotNull(operations[firstArg()]) }
        coEvery { coordinator.start(any()) } answers {
            val request = firstArg<LifecycleOperationRequest>()
            starts += request
            admissionOperation(request).also { operations[request.operationId] = it }
        }
        coEvery { coordinator.persistClientReceipts(any()) } coAnswers { receipts += firstArg<List<LifecycleClientReceipt>>() }
        coEvery { coordinator.recordClientReceipt(any()) } answers {
            checkNotNull(operations[firstArg<LifecycleClientReceipt>().operationId])
        }
        val validator = DefaultSecureMediaAuthorizationValidator(service, Gate(), object : OfflineLicenseClock {
            override fun wallClockEpochMillis() = 10_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }, DeviceRuntimeBindingProvider { 1 }, readAdmissionCoordinator = coordinator)
        repeat(2) {
            withContext(LifecycleOperationContext(owner.operationId, FullAppLifecycleId.LC_16)) {
                assertThat(validator.withReadAdmission(authority, owner) {
                    val context = checkNotNull(currentLifecycleOperationContext())
                    assertThat(kotlinx.coroutines.currentCoroutineContext()[
                        io.element.android.security.contentprotection.api.KmsTransportSchedulingContext]?.priority)
                        .isEqualTo(io.element.android.security.contentprotection.api.KmsTransportPriority.USER_MEDIA)
                    assertThat(context.operationId).isNotEqualTo(owner.operationId)
                    assertThat(starts.last().operationId).isEqualTo(context.operationId)
                    assertThat(operations[owner.operationId]).isSameInstanceAs(original)
                    true
                }).isTrue()
            }
        }
        assertThat(starts.map { it.operationId }.distinct()).hasSize(2)
        assertThat(starts.map { it.trigger }.toSet()).containsExactly("download")
        assertThat(receipts.map { it.operationId }.toSet()).containsExactlyElementsIn(starts.map { it.operationId })
        assertThat(receipts.map { it.component }.toSet()).containsExactly(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
        assertThat(operations[owner.operationId]).isSameInstanceAs(original)
    }

    @Test fun `missing original owner or unavailable lifecycle admission fails before install`() = runTest {
        val service = mockk<ContentProtectionService>()
        val authority = TestProtectedAuthority(description().copy(mode = OfflineLeaseMode.ONLINE_REQUIRED))
        every { service.validateProtectedMediaReleaseAuthority(authority, authority.description, 0) } returns ProtectedMediaReleaseAuthorityDecision.VALIDATED
        every { service.protectedMediaDeadlineElapsedRealtimeMillis(authority) } returns 21_000
        var installed = false
        assertThat(runCatching { validator(service, Gate()).withReadAdmission(authority, null) { installed = true; true } }.isFailure).isTrue()
        val owner = SecureTransientMediaOwner("original-download-operation", "a".repeat(64), "b".repeat(64), "test-installation", null, null)
        assertThat(runCatching { validator(service, Gate()).withReadAdmission(authority, owner) { installed = true; true } }.isFailure).isTrue()
        assertThat(installed).isFalse()
    }

    private fun description() = ProtectedMediaReleaseAuthorityDescription(
        authorityId = "authority-id",
        reference = KmsShareReference("kms-reference"),
        contentId = ContentId(CONTENT_ID),
        policyVersion = 7,
        accountIdHash = "a".repeat(64),
        installationId = "test-installation",
        immutableContextSha256Hex = CONTEXT_SHA256,
        requestNonceSha256Hex = "3".repeat(64),
        mode = OfflineLeaseMode.BOUNDED_OFFLINE,
        issuedAtEpochMillis = 1,
        releaseExpiresAtEpochMillis = Long.MAX_VALUE,
        authorizationDeadlineEpochMillis = Long.MAX_VALUE,
        serverTimeEpochMillis = 1,
        bootCount = 1,
    )

    private fun rejected(reason: SecureMediaAuthorizationRejection) =
        SecureMediaAuthorizationValidation.Rejected(reason)

    private fun validator(
        service: ContentProtectionService,
        gate: ProtectedPreviewAuthorizationGate,
        clock: OfflineLicenseClock = object : OfflineLicenseClock {
            override fun wallClockEpochMillis(): Long = 10_000
            override fun elapsedRealtimeMillis(): Long = 1_000
        },
        runtimeBindingProvider: DeviceRuntimeBindingProvider = DeviceRuntimeBindingProvider { 1 },
    ) = DefaultSecureMediaAuthorizationValidator(
        contentProtectionService = service,
        previewAuthorizationGate = gate,
        clock = clock,
        runtimeBindingProvider = runtimeBindingProvider,
    )

    private class TestProtectedAuthority(
        override val description: ProtectedMediaReleaseAuthorityDescription,
    ) : ProtectedMediaReleaseAuthority {
        override fun close() = Unit
    }

    private class Gate(
        allowsPreview: Boolean = true,
        revision: Long = 0,
    ) : ProtectedPreviewAuthorizationGate {
        private val mutableAccessState = MutableStateFlow(
            ProtectedPreviewAccessState(allowsPreview, allowsPreview, allowsPreview)
        )
        override val accessState: StateFlow<ProtectedPreviewAccessState> = mutableAccessState
        private val mutableAuthorizationRevision = MutableStateFlow(revision)
        override val authorizationRevision: StateFlow<Long> = mutableAuthorizationRevision
        override fun canAttemptPreview(): Boolean = accessState.value.allowsPreviewAttempt
        override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean): Boolean =
            contentProtectionAuthorized && canAttemptPreview()

        fun denyPreview() {
            mutableAccessState.value = ProtectedPreviewAccessState(false, false, false)
        }

        fun advanceAuthorizationRevision() {
            mutableAuthorizationRevision.value += 1L
        }
    }

    private companion object {
        const val CONTENT_ID = "content-123"
        val CONTEXT_SHA256 = "2".repeat(64)
    }
}
