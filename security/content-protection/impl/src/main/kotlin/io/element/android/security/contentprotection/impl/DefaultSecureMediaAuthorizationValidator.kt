/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.KmsTransportPriority
import io.element.android.security.contentprotection.api.KmsTransportSchedulingContext
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthority
import io.element.android.security.contentprotection.api.ProtectedMediaReleaseAuthorityDecision
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.withContext
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultSecureMediaAuthorizationValidator internal constructor(
    private val contentProtectionService: ContentProtectionService,
    private val previewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val clock: OfflineLicenseClock,
    private val runtimeBindingProvider: DeviceRuntimeBindingProvider,
    private val exportPolicyProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyProvider? = null,
    @Suppress("UNUSED_PARAMETER") externalReadConstructorMarker: Unit = Unit,
    private val readAdmissionCoordinator: AppOperationalStateCoordinator? = null,
) : SecureMediaAuthorizationValidator {
    @dev.zacsweers.metro.Inject
    constructor(
        contentProtectionService: ContentProtectionService,
        previewAuthorizationGate: ProtectedPreviewAuthorizationGate,
        clock: OfflineLicenseClock,
        runtimeBindingProvider: DeviceRuntimeBindingProvider,
        exportPolicyProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyProvider,
        readAdmissionCoordinator: AppOperationalStateCoordinator,
    ) : this(
        contentProtectionService,
        previewAuthorizationGate,
        clock,
        runtimeBindingProvider,
        exportPolicyProvider as io.element.android.security.dataprotection.api.DataProtectionPolicyProvider?,
        readAdmissionCoordinator = readAdmissionCoordinator,
    )

    override suspend fun withReadAdmission(
        authority: SecureMediaReleaseAuthority,
        sourceOwner: io.element.android.security.securestorage.api.SecureTransientMediaOwner?,
        install: suspend () -> Boolean,
    ): Boolean {
        val parent = authority as? ProtectedMediaReleaseAuthority ?: throw SecurityException("Unknown read authority issuer.")
        val admitted = validate(parent, parent.description.contentId.value, parent.description.immutableContextSha256Hex)
            as? SecureMediaAuthorizationValidation.Verified ?: throw SecurityException("Read authority is unavailable.")
        if (parent.description.mode != io.element.android.security.contentprotection.api.OfflineLeaseMode.ONLINE_REQUIRED ||
            parent.externalReadBinding != null || parent.localAuthorityId != null) return install()
        val deadline = contentProtectionService.protectedMediaDeadlineElapsedRealtimeMillis(parent)
            ?: throw SecurityException("Trusted read deadline is unavailable.")
        val remaining = deadline - admitted.time.elapsedRealtimeMillis
        if (remaining > 20_000L) return install()
        val owner = requireNotNull(sourceOwner) { "Original protected source owner is unavailable." }
        val coordinator = requireNotNull(readAdmissionCoordinator) { "Read admission lifecycle owner is unavailable." }
        check(owner.accountIdHash == parent.description.accountIdHash && owner.installationId == parent.description.installationId)
        // Read the original immutable ownership only. Its terminal transfer receipt is never reopened or edited.
        val source = coordinator.operation(owner.operationId)
        check(source.operationId == owner.operationId && source.lifecycleId == FullAppLifecycleId.LC_16)
        check(source.status in NON_TERMINAL_RESUMABLE_STATUSES ||
            source.status == io.element.android.security.dataprotection.api.LifecycleOperationStatus.SUCCEEDED)
        check(source.subjects.accountIdHash == owner.accountIdHash && source.subjects.matrixDeviceIdHash == owner.matrixDeviceIdHash &&
            source.subjects.installationId == owner.installationId && source.subjects.roomIdHash == owner.roomIdHash &&
            source.subjects.eventIdHash == owner.eventIdHash)
        check(validate(parent, parent.description.contentId.value, parent.description.immutableContextSha256Hex,
            admitted.authorization.authorizationRevision) is SecureMediaAuthorizationValidation.Verified)
        val remainingBeforeStart = deadline - clock.elapsedRealtimeMillis()
        check(remainingBeforeStart > 0)
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_16, "download", owner.accountIdHash, owner.matrixDeviceIdHash,
            owner.roomIdHash, owner.eventIdHash, deadlineEpochMillis = Math.addExact(System.currentTimeMillis(), remainingBeforeStart))
        val operation = coordinator.start(request)
        check(operation.operationId == request.operationId && operation.lifecycleId == request.lifecycleId &&
            operation.status in NON_TERMINAL_RESUMABLE_STATUSES && operation.terminalReceiptId == null)
        check(operation.deadlineEpochMillis == request.deadlineEpochMillis && operation.deadlineEpochMillis > System.currentTimeMillis())
        check(operation.subjects.accountIdHash == owner.accountIdHash && operation.subjects.matrixDeviceIdHash == owner.matrixDeviceIdHash &&
            operation.subjects.installationId == owner.installationId && operation.subjects.roomIdHash == owner.roomIdHash &&
            operation.subjects.eventIdHash == owner.eventIdHash)
        val required = operation.steps.filter { it.required }
        check(required.size == 6 && required.map { it.component }.toSet() == setOf(LifecycleComponent.ANDROID_FRONTEND,
            LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE,
            LifecycleComponent.DATABASE, LifecycleComponent.KMS))
        return withContext(LifecycleOperationContext(operation.operationId, operation.lifecycleId) +
            KmsTransportSchedulingContext(KmsTransportPriority.USER_MEDIA)) {
            coordinator.perform(request) {
                check(install()) { "Fresh source admission failed before client application." }
                true
            }
        }
    }

    override suspend fun renewReadAuthority(authority: SecureMediaReleaseAuthority): SecureMediaReleaseAuthority? {
        val parent = authority as? ProtectedMediaReleaseAuthority ?: return null
        if (parent.externalReadBinding != null || parent.localAuthorityId != null) return null
        val contentId = parent.description.contentId.value
        val context = parent.description.immutableContextSha256Hex
        val admitted = validate(parent, contentId, context) as? SecureMediaAuthorizationValidation.Verified
            ?: throw SecurityException("Read authority is unavailable before fresh admission.")
        val fresh = contentProtectionService.renewProtectedMediaReadAuthority(parent) ?: return null
        try {
            check(validate(parent, contentId, context, admitted.authorization.authorizationRevision) is SecureMediaAuthorizationValidation.Verified)
            check(validate(fresh, contentId, context, admitted.authorization.authorizationRevision) is SecureMediaAuthorizationValidation.Verified)
            return fresh
        } catch (failure: Throwable) {
            fresh.close()
            throw failure
        }
    }

    override fun deriveExternalReadAuthority(
        authority: SecureMediaReleaseAuthority,
        binding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding,
    ): SecureMediaReleaseAuthority? {
        val parent = authority as? ProtectedMediaReleaseAuthority ?: return null
        if (!exportAllowed(binding) || !previewAuthorizationGate.canAttemptPreview()) return null
        val admitted = validate(parent, parent.description.contentId.value, parent.description.immutableContextSha256Hex)
        if (admitted !is SecureMediaAuthorizationValidation.Verified || admitted.authorization.externalReadBinding != null) return null
        val child = contentProtectionService.deriveProtectedMediaExternalReadAuthority(parent, binding) ?: return null
        val validated = validate(
            child,
            parent.description.contentId.value,
            parent.description.immutableContextSha256Hex,
            admitted.authorization.authorizationRevision
        )
        if (validated !is SecureMediaAuthorizationValidation.Verified || validated.authorization.externalReadBinding != binding) {
            child.close()
            return null
        }
        return child
    }

    override fun validate(
        authority: SecureMediaReleaseAuthority,
        expectedContentId: String,
        expectedImmutableContextSha256Hex: String,
        expectedAuthorizationRevision: Long?,
    ): SecureMediaAuthorizationValidation {
        val bootBefore = runCatching(runtimeBindingProvider::bootCount)
            .getOrElse { return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE) }
        val elapsedBefore = runCatching(clock::elapsedRealtimeMillis)
            .getOrElse { return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE) }
        if (bootBefore < 0L || elapsedBefore < 0L) {
            return rejected(SecureMediaAuthorizationRejection.BINDING_INVALID)
        }
        if (!previewAuthorizationGate.canAttemptPreview()) {
            Timber.w("Secure media authorization rejected stage=preview_gate reason=PREVIEW_DENIED")
            return rejected(SecureMediaAuthorizationRejection.PREVIEW_DENIED)
        }
        val revision = previewAuthorizationGate.authorizationRevision.value
        if (expectedAuthorizationRevision != null && revision != expectedAuthorizationRevision) {
            Timber.w("Secure media authorization rejected stage=preview_revision reason=REVISION_MISMATCH")
            return rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH)
        }
        val protectedAuthority = authority as? ProtectedMediaReleaseAuthority
            ?: return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE)
        val untrustedDescription = protectedAuthority.description
        val decision = contentProtectionService.validateProtectedMediaReleaseAuthority(
            authority = protectedAuthority,
            expected = untrustedDescription,
            effectiveNowEpochMillis = 0L,
        )
        if (decision != ProtectedMediaReleaseAuthorityDecision.VALIDATED) {
            Timber.w("Secure media authorization rejected stage=release_authority decision=%s", decision)
            return rejected(decision.toRejection())
        }
        if (protectedAuthority.externalReadBinding?.let { !exportAllowed(it) } == true) {
            return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE)
        }
        val bootAfter = runCatching(runtimeBindingProvider::bootCount)
            .getOrElse { return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE) }
        val elapsedAfter = runCatching(clock::elapsedRealtimeMillis)
            .getOrElse { return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE) }
        if (bootBefore != bootAfter || bootAfter != untrustedDescription.bootCount || elapsedAfter < elapsedBefore) {
            return rejected(SecureMediaAuthorizationRejection.BINDING_INVALID)
        }
        // Description fields become trusted only after exact issuer validation above.
        if (untrustedDescription.contentId.value != expectedContentId ||
            untrustedDescription.immutableContextSha256Hex != expectedImmutableContextSha256Hex
        ) {
            return rejected(SecureMediaAuthorizationRejection.RELEASE_MISMATCH)
        }
        if (!previewAuthorizationGate.canAttemptPreview()) {
            return rejected(SecureMediaAuthorizationRejection.PREVIEW_DENIED)
        }
        if (previewAuthorizationGate.authorizationRevision.value != revision) {
            Timber.w("Secure media authorization rejected stage=preview_revision reason=REVISION_MISMATCH")
            return rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH)
        }
        val externalDeadline = if (protectedAuthority.externalReadBinding == null) {
            null
        } else {
            contentProtectionService.protectedMediaDeadlineElapsedRealtimeMillis(protectedAuthority)
                ?: return rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE)
        }
        if (externalDeadline != null && externalDeadline <= elapsedAfter) return rejected(SecureMediaAuthorizationRejection.EXPIRED)
        return SecureMediaAuthorizationValidation.Verified(
            authorization = SecureMediaVerifiedAuthorization(
                authorityId = untrustedDescription.authorityId,
                contentId = untrustedDescription.contentId.value,
                immutableContextSha256Hex = untrustedDescription.immutableContextSha256Hex,
                authorizationDeadlineEpochMillis = untrustedDescription.authorizationDeadlineEpochMillis,
                authorizationRevision = revision,
                revocationScope = untrustedDescription.burnSessionId ?: "content:${untrustedDescription.contentId.value}",
                externalReadBinding = protectedAuthority.externalReadBinding,
                localAuthorityId = protectedAuthority.localAuthorityId,
                authorizationDeadlineElapsedRealtimeMillis = externalDeadline,
            ),
            time = SecureMediaValidationTime(
                bootCount = bootAfter,
                elapsedRealtimeMillis = elapsedAfter,
            ),
        )
    }

    private fun exportAllowed(binding: io.element.android.security.securestorage.api.SecureMediaExternalReadBinding): Boolean =
        runCatching {
            val provider = exportPolicyProvider ?: return@runCatching false
            val current = provider.current()
            val original = provider.resolve(binding.sourcePolicyId, binding.sourcePolicyVersion) ?: return@runCatching false
            current.source == io.element.android.security.dataprotection.api.DataProtectionPolicySource.VERIFIED_REMOTE &&
                original.source == io.element.android.security.dataprotection.api.DataProtectionPolicySource.VERIFIED_REMOTE &&
                current.policy.allowContentExport && original.policy.allowContentExport
        }.getOrDefault(false)

    private fun rejected(reason: SecureMediaAuthorizationRejection) =
        SecureMediaAuthorizationValidation.Rejected(reason)

    private fun ProtectedMediaReleaseAuthorityDecision.toRejection(): SecureMediaAuthorizationRejection = when (this) {
        ProtectedMediaReleaseAuthorityDecision.VALIDATED -> error("Validated authority cannot map to rejection.")
        ProtectedMediaReleaseAuthorityDecision.OWNER_MISMATCH -> SecureMediaAuthorizationRejection.OWNER_MISMATCH
        ProtectedMediaReleaseAuthorityDecision.RELEASE_MISMATCH -> SecureMediaAuthorizationRejection.RELEASE_MISMATCH
        ProtectedMediaReleaseAuthorityDecision.EXPIRED -> SecureMediaAuthorizationRejection.EXPIRED
        ProtectedMediaReleaseAuthorityDecision.BINDING_INVALID -> SecureMediaAuthorizationRejection.BINDING_INVALID
        ProtectedMediaReleaseAuthorityDecision.CLOSED -> SecureMediaAuthorizationRejection.CLOSED
        ProtectedMediaReleaseAuthorityDecision.AUTHORITY_UNAVAILABLE -> SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE
    }
}
