/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.crypto.ClientSecret
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.data.ByteUnit
import io.element.android.libraries.core.data.megaBytes
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.impl.analytics.UtdTracker
import io.element.android.libraries.matrix.impl.certificates.UserCertificatesProvider
import io.element.android.libraries.matrix.impl.media.DisabledMatrixProtectedMediaService
import io.element.android.libraries.matrix.impl.media.MatrixProtectedMediaService
import io.element.android.libraries.matrix.impl.paths.SessionPaths
import io.element.android.libraries.matrix.impl.paths.getSessionPaths
import io.element.android.libraries.matrix.impl.proxy.ProxyProvider
import io.element.android.libraries.matrix.impl.room.TimelineEventFilterFactory
import io.element.android.libraries.matrix.impl.storage.SqliteStoreBuilderProvider
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixContentProtectionRuntime
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.util.anonymizedTokens
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CrossProcessLockConfig
import org.matrix.rustcomponents.sdk.RequestConfig
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import timber.log.Timber
import uniffi.matrix_sdk_base.DmRoomDefinition
import uniffi.matrix_sdk_base.MediaRetentionPolicy
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

@Inject
class RustMatrixClientFactory(
    @CacheDirectory private val cacheDirectory: File,
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val sessionStore: SessionStore,
    private val userAgentProvider: UserAgentProvider,
    private val proxyProvider: ProxyProvider,
    private val userCertificatesProvider: UserCertificatesProvider,
    private val clock: SystemClock,
    private val analyticsService: AnalyticsService,
    private val featureFlagService: FeatureFlagService,
    private val timelineEventFilterFactory: TimelineEventFilterFactory,
    private val clientBuilderProvider: ClientBuilderProvider,
    private val sqliteStoreBuilderProvider: SqliteStoreBuilderProvider,
    private val workManagerScheduler: WorkManagerScheduler,
    private val sensitiveFileStore: SensitiveFileStore,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val dataDeletionCoordinator: DataDeletionCoordinator,
    private val permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val deviceRootKeyService: DeviceRootKeyService,
    private val managementControlService: io.element.android.security.dataprotection.api.ManagementControlService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val vaultKeyService: VaultKeyService,
    private val accountSessionSecretVault: AccountSessionSecretVault,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime = DisabledMatrixContentProtectionRuntime,
    private val matrixProtectedContentAdapterFactory: MatrixProtectedContentAdapterFactory = DisabledMatrixProtectedContentAdapterFactory,
    private val matrixProtectedMediaService: MatrixProtectedMediaService = DisabledMatrixProtectedMediaService,
) {
    // A ClientSessionDelegate receives callbacks without identifying which Rust Client emitted
    // them. Sharing one delegate lets a late auth error from an old client log out a newly
    // authenticated client. Keep the delegate paired with the exact Client built with it.
    private val pendingSessionDelegates = Collections.synchronizedMap(
        IdentityHashMap<Client, RustClientSessionDelegate>()
    )

    suspend fun create(sessionData: SessionData): RustMatrixClient = withContext(coroutineDispatchers.io) {
        val protectedSessionData = accountSessionSecretVault.protectForStorage(sessionData)
        if (protectedSessionData != sessionData) {
            sessionStore.updateData(protectedSessionData)
        }
        val runtimeSessionData = accountSessionSecretVault.exposeForRuntime(protectedSessionData)
        // This secret is called 'passphrase' for historical reasons, but it can be a raw key or an actual passphrase
        val databaseKey = requireNotNull(runtimeSessionData.passphrase?.takeIf(String::isNotBlank)) {
            "Refusing to open an unencrypted Matrix SDK database."
        }
        val clientSecret = ClientSecret.fromString(databaseKey)
        val client = try {
            buildClient(
                sessionPaths = runtimeSessionData.getSessionPaths(),
                clientSecret = clientSecret,
                slidingSyncType = ClientBuilderSlidingSync.Restored,
            ) {
                homeserverUrl(runtimeSessionData.homeserverUrl)
                    .username(runtimeSessionData.userId)
            }
        } finally {
            (clientSecret as? ClientSecret.RawKey)?.bytes?.fill(0)
        }

        try {
            client.restoreSession(runtimeSessionData.toSession())
            create(client)
        } catch (failure: Throwable) {
            discardPendingClient(client)
            client.close()
            throw failure
        }
    }

    suspend fun protectSessionDataForStorage(sessionData: SessionData): SessionData =
        accountSessionSecretVault.protectForStorage(sessionData)

    suspend fun create(client: Client): RustMatrixClient {
        val sessionDelegate = checkNotNull(pendingSessionDelegates.remove(client)) {
            "Missing client-scoped session delegate."
        }
        val accountVaultId = deriveAccountVaultId(client.userId(), client.deviceId())
        vaultKeyService.provision(
            VaultProvisioningRequest(
                vaultId = accountVaultId,
                accountIdHash = accountIdHash(client.userId()),
                requireUserAuthentication = false,
                requireTrustedHardware = featureFlagService.isFeatureEnabled(FeatureFlags.HardwareBackedVault),
            )
        )
        check(vaultKeyService.unlock(accountVaultId)) { "Unable to unlock the account Vault." }
        val secureFileStoreEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.SecureFileStore)
        if (secureFileStoreEnabled) {
            cleanupLegacyPlaintextMediaCaches()
        }
        client.setMediaRetentionPolicy(
            MediaRetentionPolicy(
                // The Rust SDK cache contains decrypted Matrix media. Disable it when the encrypted media pipeline is active.
                maxCacheSize = if (secureFileStoreEnabled) 0uL else 500.megaBytes.into(ByteUnit.BYTES).toULong(),
                maxFileSize = 20.megaBytes.into(ByteUnit.BYTES).toULong(),
                lastAccessExpiry = 30.days.toJavaDuration(),
                cleanupFrequency = 1.days.toJavaDuration(),
            )
        )
        val (anonymizedAccessToken, anonymizedRefreshToken) = client.session().anonymizedTokens()

        // Must be called before creating the sync service, timelines etc.
        if (featureFlagService.isFeatureEnabled(FeatureFlags.AutomaticBackPagination)) {
            client.enableAutomaticBackpagination()
        }

        client.setUtdDelegate(UtdTracker(analyticsService))

        val syncService = client.syncService()
            .withSharePos(true)
            .withOfflineMode()
            .finish()

        return RustMatrixClient(
            innerClient = client,
            sessionStore = sessionStore,
            appCoroutineScope = appCoroutineScope,
            sessionDelegate = sessionDelegate,
            innerSyncService = syncService,
            dispatchers = coroutineDispatchers,
            baseCacheDirectory = cacheDirectory,
            clock = clock,
            timelineEventFilterFactory = timelineEventFilterFactory,
            featureFlagService = featureFlagService,
            analyticsService = analyticsService,
            workManagerScheduler = workManagerScheduler,
            sensitiveFileStore = sensitiveFileStore,
            secureMediaLeaseManager = secureMediaLeaseManager,
            secureFileStoreEnabled = secureFileStoreEnabled,
            dataDeletionCoordinator = dataDeletionCoordinator,
            permanentAccountDeletionCoordinator = permanentAccountDeletionCoordinator,
            accountReactivationScopeStore = accountReactivationScopeStore,
            dataProtectionPolicyProvider = dataProtectionPolicyProvider,
            deviceRootKeyService = deviceRootKeyService,
            managementControlService = managementControlService,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            matrixContentProtectionRuntime = matrixContentProtectionRuntime,
            matrixProtectedContentAdapterFactory = matrixProtectedContentAdapterFactory,
            matrixProtectedMediaService = matrixProtectedMediaService,
        ).also {
            Timber.tag("RustMatrixClient").i("Creating Client with access token '$anonymizedAccessToken' and refresh token '$anonymizedRefreshToken'")
        }
    }

    /**
     * Removes only the old inbound-media cache locations. Upload staging is deliberately excluded until its SDK
     * file-path contract is replaced, otherwise an in-flight upload could be destroyed.
     */
    private fun cleanupLegacyPlaintextMediaCaches() {
        val cacheRoot = cacheDirectory.canonicalFile
        LEGACY_PLAINTEXT_MEDIA_CACHE_PATHS.forEach { relativePath ->
            val target = File(cacheRoot, relativePath).canonicalFile
            check(target.path.startsWith(cacheRoot.path + File.separator)) {
                "Legacy media cache escaped the app cache root."
            }
            if (target.exists() && !target.deleteRecursively()) {
                Timber.tag("RustMatrixClient").w("Unable to remove a legacy plaintext media cache.")
            }
        }
    }

    private companion object {
        val LEGACY_PLAINTEXT_MEDIA_CACHE_PATHS = listOf(
            "temp/media",
            "temp/voice",
            "temp/notif",
        )
    }

    internal suspend fun buildClient(
        sessionPaths: SessionPaths,
        clientSecret: ClientSecret?,
        slidingSyncType: ClientBuilderSlidingSync,
        config: suspend ClientBuilder.() -> ClientBuilder = { this },
    ): Client {
        val sessionDelegate = RustClientSessionDelegate(
            sessionStore = sessionStore,
            appCoroutineScope = appCoroutineScope,
            analyticsService = analyticsService,
            accountSessionSecretVault = accountSessionSecretVault,
        )
        val client = clientBuilderProvider.provide()
            .run {
                sqliteStoreBuilderProvider.provide(sessionPaths)
                    .secret(clientSecret)
                    .setupClientBuilder(this)
            }
            .setSessionDelegate(sessionDelegate)
            .userAgent(userAgentProvider.provide())
            .addRootCertificates(userCertificatesProvider.provides())
            .autoEnableBackups(true)
            .autoEnableCrossSigning(true)
            .roomKeyRecipientStrategy(
                strategy = if (featureFlagService.isFeatureEnabled(FeatureFlags.OnlySignedDeviceIsolationMode)) {
                    CollectStrategy.IDENTITY_BASED_STRATEGY
                } else {
                    CollectStrategy.ERROR_ON_VERIFIED_USER_PROBLEM
                }
            )
            .decryptionSettings(
                DecryptionSettings(
                    senderDeviceTrustRequirement = if (featureFlagService.isFeatureEnabled(FeatureFlags.OnlySignedDeviceIsolationMode)) {
                        TrustRequirement.CROSS_SIGNED_OR_LEGACY
                    } else {
                        TrustRequirement.UNTRUSTED
                    }
                )
            )
            .enableShareHistoryOnInvite(true)
            .threadsEnabled(featureFlagService.isFeatureEnabled(FeatureFlags.Threads), threadSubscriptions = false)
            .dmRoomDefinition(DmRoomDefinition.TWO_MEMBERS)
            .requestConfig(
                RequestConfig(
                    timeout = 30_000uL,
                    // retryLimit must be non-zero for the SDK to retry API calls in case of error (including 429 Too Many Requests error).
                    retryLimit = 3u,
                    // Use default values for the rest
                    maxConcurrentRequests = null,
                    maxRetryTime = null,
                )
            )
            // Make sure all built clients use the single process cross-process lock config
            .crossProcessLockConfig(CrossProcessLockConfig.SingleProcess)
            .run {
                // Apply sliding sync version settings
                when (slidingSyncType) {
                    ClientBuilderSlidingSync.Restored -> this
                    ClientBuilderSlidingSync.Discovered -> slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
                    ClientBuilderSlidingSync.Native -> slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                }
            }
            .run {
                // Workaround for non-nullable proxy parameter in the SDK, since each call to the ClientBuilder returns a new reference we need to keep
                proxyProvider.provides()?.let { proxy(it) } ?: this
            }
            .config()
            .build()
        // The Rust SDK exclusively owns its SQLite connections, WAL and schema migrations.
        // Opening this database with Android SQLite while the SDK is alive can unlink
        // the WAL still held by native connections and cause SQLITE_IOERR_SHORT_READ.
        pendingSessionDelegates[client] = sessionDelegate
        return client
    }

    internal fun discardPendingClient(client: Client) {
        pendingSessionDelegates.remove(client)
    }
}

sealed interface ClientBuilderSlidingSync {
    // The proxy will be supplied when restoring the Session.
    data object Restored : ClientBuilderSlidingSync

    // A Native Sliding Sync instance must be discovered whilst building the session.
    data object Discovered : ClientBuilderSlidingSync

    // Force using Native Sliding Sync.
    data object Native : ClientBuilderSlidingSync
}

fun SessionData.toSession() = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    userId = userId,
    deviceId = deviceId,
    homeserverUrl = homeserverUrl,
    slidingSyncVersion = SlidingSyncVersion.NATIVE,
    oauthData = oAuthData,
)
