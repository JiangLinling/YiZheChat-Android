/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.api.MigrationEntryPoint
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.rageshake.api.reporter.BugReporter
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.identifiers.SentrySdkDsn
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.matrix.api.platform.InitPlatformService
import io.element.android.libraries.matrix.api.tracing.TracingService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentEnvelopeValidator
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.MatrixContentProtectionBinding
import io.element.android.security.contentprotection.api.OfflineLicenseService
import io.element.android.security.contentprotection.api.RemoteRevocationService
import io.element.android.security.contentprotection.api.StageThreeSecurityGate
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.NextInternalCutoverMigration
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PlaintextMigrationCoordinator
import io.element.android.security.dataprotection.api.SensitiveAssetCatalog
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.x.security.AccountVaultLifecycleCoordinator
import io.element.android.x.security.ManagementControlCoordinator
import kotlinx.coroutines.CoroutineScope

@ContributesTo(AppScope::class)
interface AppBindings {
    fun retainedSessionPreparation(): io.element.android.x.RetainedSessionPreparation

    fun foregroundNotificationHost(): io.element.android.libraries.push.api.notifications.ForegroundNotificationHost

    @AppCoroutineScope
    fun appCoroutineScope(): CoroutineScope

    fun snackbarDispatcher(): SnackbarDispatcher

    fun tracingService(): TracingService

    fun platformService(): InitPlatformService

    fun bugReporter(): BugReporter

    fun lockScreenService(): LockScreenService

    fun preferencesStore(): AppPreferencesStore

    fun migrationEntryPoint(): MigrationEntryPoint

    fun lockScreenEntryPoint(): LockScreenEntryPoint

    fun analyticsService(): AnalyticsService

    fun enterpriseService(): EnterpriseService

    fun featureFlagService(): FeatureFlagService

    fun contentEnvelopeValidator(): ContentEnvelopeValidator

    fun contentEnvelopeCodec(): ContentEnvelopeCodec

    fun contentProtectionService(): ContentProtectionService

    fun deviceShareKeyService(): DeviceShareKeyService

    fun kmsShareClient(): KmsShareClient

    fun offlineLicenseService(): OfflineLicenseService

    fun remoteRevocationService(): RemoteRevocationService

    fun stageThreeSecurityGate(): StageThreeSecurityGate

    fun matrixContentProtectionBinding(): MatrixContentProtectionBinding

    fun dataProtectionPolicyProvider(): DataProtectionPolicyProvider

    fun dataDeletionCoordinator(): DataDeletionCoordinator

    fun accountReactivationScopeStore(): AccountReactivationScopeStore

    fun permanentAccountDeletionCoordinator(): PermanentAccountDeletionCoordinator

    fun networkMonitor(): io.element.android.features.networkmonitor.api.NetworkMonitor

    fun managementControlService(): ManagementControlService

    fun nextInternalCutoverMigration(): NextInternalCutoverMigration

    fun appOperationalStateCoordinator(): AppOperationalStateCoordinator

    fun plaintextMigrationCoordinator(): PlaintextMigrationCoordinator

    fun sensitiveAssetCatalog(): SensitiveAssetCatalog

    fun secureStorageRoots(): SecureStorageRoots

    fun deviceRootKeyService(): DeviceRootKeyService

    fun deviceTrustService(): DeviceTrustService

    fun vaultKeyService(): VaultKeyService

    fun sensitiveFileStore(): SensitiveFileStore

    fun accountVaultLifecycleCoordinator(): AccountVaultLifecycleCoordinator

    fun callRecoveryService(): io.element.android.features.call.api.CallRecoveryService

    fun managementControlCoordinator(): ManagementControlCoordinator

    fun buildMeta(): BuildMeta

    fun sentrySdkDsn(): SentrySdkDsn?
}
