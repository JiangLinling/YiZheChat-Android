/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.data.bytes
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiSyncService
import io.element.android.libraries.matrix.impl.room.FakeTimelineEventFilterFactory
import io.element.android.libraries.matrix.api.exception.AccountDeactivationException
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.libraries.workmanager.test.FakeWorkManagerScheduler
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountReactivationAttempt
import io.element.android.security.dataprotection.api.AccountReactivationCheckpoint
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.HistoryRecoveryAttempt
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.AccountDeletionReceipt
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingMatrixException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionRecoveryReport
import io.element.android.security.dataprotection.test.FakeDataDeletionCoordinator
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.test.FakePermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.StoreSizes
import org.matrix.rustcomponents.sdk.UserProfile
import org.matrix.rustcomponents.sdk.ClientException as RustClientException
import java.io.File

private const val TEST_INSTALLATION_ID = "matrix-client-installation-test"

class RustMatrixClientTest {
    @Test
    fun `session construction does not wait for the maintenance database query`() = runTest {
        var checks = 0
        var submissions = 0
        createRustMatrixClient(workManagerScheduler = FakeWorkManagerScheduler(
            hasPendingWorkLambda = { _, _ -> checks++; false },
            submitLambda = { submissions++ },
        ))
        assertThat(checks).isEqualTo(0)
        runCurrent()
        assertThat(checks).isEqualTo(1)
        assertThat(submissions).isEqualTo(1)
    }

    @Test
    fun `existing periodic maintenance is not registered twice`() = runTest {
        var submissions = 0
        createRustMatrixClient(workManagerScheduler = FakeWorkManagerScheduler(
            hasPendingWorkLambda = { _, _ -> true },
            submitLambda = { submissions++ },
        ))
        runCurrent()
        assertThat(submissions).isEqualTo(0)
    }

    @Test
    fun `session cancellation during the maintenance query prevents registration`() = runTest {
        lateinit var client: RustMatrixClient
        var submissions = 0
        client = createRustMatrixClient(workManagerScheduler = FakeWorkManagerScheduler(
            hasPendingWorkLambda = { _, _ -> client.sessionCoroutineScope.cancel(); false },
            submitLambda = { submissions++ },
        ))
        runCurrent()
        assertThat(submissions).isEqualTo(0)
    }

    @Test
    fun `a maintenance query failure does not fail the restored client`() = runTest {
        val client = createRustMatrixClient(workManagerScheduler = FakeWorkManagerScheduler(
            hasPendingWorkLambda = { _, _ -> error("Maintenance database unavailable") },
            submitLambda = { error("Must not enqueue after a failed query") },
        ))
        runCurrent()
        assertThat(client.sessionId).isEqualTo(A_USER_ID)
    }

    @Test
    fun `ensure that sessionId and deviceId can be retrieved from the client`() = runTest {
        createRustMatrixClient().run {
            assertThat(sessionId).isEqualTo(A_USER_ID)
            assertThat(deviceId).isEqualTo(A_DEVICE_ID)
            destroy()
        }
    }

    @Test
    fun `clear cache invokes the method clearCaches from the client and close it`() = runTest {
        val clearCachesResult = lambdaRecorder<Unit> { }
        val closeResult = lambdaRecorder<Unit> { }
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                clearCachesResult = clearCachesResult,
                closeResult = closeResult,
            )
        )
        client.clearCache()
        clearCachesResult.assertions().isCalledOnce()
        closeResult.assertions().isCalledOnce()
        client.destroy()
    }

    @Test
    fun `retrieving the UserProfile updates the database`() = runTest {
        val updateUserProfileResult = lambdaRecorder<String, String?, String?, Unit> { _, _, _ -> }
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    userDisplayName = null,
                    userAvatarUrl = null,
                )
            ),
            updateUserProfileResult = updateUserProfileResult,
        )
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                getProfileResult = { userId ->
                    UserProfile(
                        userId = userId,
                        displayName = A_USER_NAME,
                        avatarUrl = AN_AVATAR_URL,
                    )
                },
            ),
            sessionStore = sessionStore,
        )
        advanceUntilIdle()
        updateUserProfileResult.assertions().isCalledOnce()
            .with(
                value(A_USER_ID.value),
                value(A_USER_NAME),
                value(AN_AVATAR_URL),
            )
        client.destroy()
    }

    @Test
    fun `getDatabaseSizes returns the database sizes`() = runTest {
        val client = createRustMatrixClient(
            client = FakeFfiClient(getStoreSizesResult = { StoreSizes(null, 10uL, 11uL, 12uL) })
        )

        client.getDatabaseSizes().getOrThrow().run {
            assertThat(cryptoStore).isNull()
            assertThat(stateStore).isEqualTo(10.bytes)
            assertThat(eventCacheStore).isEqualTo(11.bytes)
            assertThat(mediaStore).isEqualTo(12.bytes)
        }
    }

    @Test
    fun `createDM overrides room history visibility to invited`() = runTest {
        var createParameters: CreateRoomParameters? = null
        val createRoomLambda = lambdaRecorder<CreateRoomParameters, String> {
            createParameters = it
            A_ROOM_ID.value
        }
        val client = createRustMatrixClient(
            client = FakeFfiClient(createRoomResult = createRoomLambda)
        )

        client.createDM(A_USER_ID)

        createRoomLambda.assertions().isCalledOnce()
        assertThat(createParameters?.historyVisibilityOverride).isEqualTo(RoomHistoryVisibility.Invited)
    }

    @Test
    fun `account cleanup requests cryptographic deletion for the device vault`() = runTest {
        val deletionCoordinator = FakeDataDeletionCoordinator()
        val client = createRustMatrixClient(
            sessionStore = InMemorySessionStore(initialList = listOf(aSessionData(sessionId = A_USER_ID.value))),
            dataDeletionCoordinator = deletionCoordinator,
        )

        client.deleteProtectedAccountData(DeletionReason.ACCOUNT_LOGOUT)

        val request = deletionCoordinator.requests.single()
        val scope = request.scope as DeletionScope.Account
        assertThat(request.reason).isEqualTo(DeletionReason.ACCOUNT_LOGOUT)
        assertThat(scope.vaultId).startsWith("matrix-device-v1:")
        assertThat(scope.vaultId).hasLength(81)
        assertThat(scope.accountIdHash).hasLength(64)
        assertThat(scope.localSessionId).isEqualTo(A_USER_ID.value)
        assertThat(scope.sessionFilePath).isNotEmpty()
        assertThat(scope.sessionCachePath).isNotEmpty()
        assertThat(deletionCoordinator.preparedRequests).hasSize(1)
        assertThat(request.confirmedPreconditions).containsExactly(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
        client.destroy()
    }

    @Test
    fun `permanent deletion is durably prepared before Matrix deactivation`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        val matrixAuthAttempts = mutableListOf<AuthData?>()
        val ffiClient = FakeFfiClient(
            deactivateAccountResult = { authData, _ ->
                assertThat(permanentDeletion.preparedRequests).hasSize(1)
                matrixAuthAttempts += authData
                if (authData == null) error("interactive auth required")
            },
        )
        val client = createRustMatrixClient(
            client = ffiClient,
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("correct-password", eraseData = true)

        assertThat(result.isSuccess).isTrue()
        assertThat(matrixAuthAttempts).hasSize(2)
        assertThat(matrixAuthAttempts.first()).isNull()
        assertThat(matrixAuthAttempts.last()).isInstanceOf(AuthData.Password::class.java)
        assertThat(permanentDeletion.commitCalls).hasSize(1)
        assertThat(permanentDeletion.completeCalls).hasSize(1)
        assertThat(permanentDeletion.pendingHandle).isNull()
    }

    @Test
    fun `failed Matrix password preserves prepared intent and restores delegate`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        val delegateTransitions = mutableListOf<Boolean>()
        val ffiClient = FakeFfiClient(
            deactivateAccountResult = { _, _ -> error("invalid password") },
            setDelegateResult = { delegate ->
                delegateTransitions += delegate != null
                io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTaskHandle()
            },
        )
        val client = createRustMatrixClient(
            client = ffiClient,
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("wrong-password", eraseData = true)

        assertThat(result.isFailure).isTrue()
        assertThat(permanentDeletion.abandonCalls).isEmpty()
        assertThat(permanentDeletion.pendingHandle).isNotNull()
        assertThat(permanentDeletion.commitCalls).isEmpty()
        assertThat(permanentDeletion.completeCalls).isEmpty()
        assertThat(delegateTransitions).containsExactly(true, true).inOrder()
        client.destroy()
    }

    @Test
    fun `delegate restoration failure never hides the Matrix failure`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        var delegateCallCount = 0
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                deactivateAccountResult = { _, _ -> error("invalid password") },
                setDelegateResult = {
                    delegateCallCount += 1
                    if (delegateCallCount == 2) error("Delegate already initialized")
                    io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTaskHandle()
                },
            ),
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("wrong-password", eraseData = true)

        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("invalid password")
        assertThat(result.exceptionOrNull()?.suppressed?.single()).hasMessageThat().isEqualTo("Delegate already initialized")
        assertThat(permanentDeletion.abandonCalls).isEmpty()
        assertThat(permanentDeletion.pendingHandle).isNotNull()
        client.destroy()
    }

    @Test
    fun `invalid Matrix credentials use the account deactivation error contract`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                deactivateAccountResult = { _, _ ->
                    throw RustClientException.Generic(
                        msg = "Forbidden, message: \"Invalid username or password\"",
                        details = null,
                    )
                },
            ),
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("wrong-password", eraseData = true)

        assertThat(result.exceptionOrNull()).isInstanceOf(AccountDeactivationException.InvalidCredentials::class.java)
        assertThat(permanentDeletion.abandonCalls).isEmpty()
        assertThat(permanentDeletion.pendingHandle).isNotNull()
        client.destroy()
    }

    @Test
    fun `invalid Matrix credentials reported in UIAA details use the account deactivation error contract`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                deactivateAccountResult = { _, _ ->
                    throw RustClientException.Generic(
                        msg = "the server returned an error: User-Interactive Authentication required.",
                        details = "auth_error: Forbidden, message: Invalid username or password",
                    )
                },
            ),
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("wrong-password", eraseData = true)

        assertThat(result.exceptionOrNull()).isInstanceOf(AccountDeactivationException.InvalidCredentials::class.java)
        assertThat(permanentDeletion.abandonCalls).isEmpty()
        assertThat(permanentDeletion.pendingHandle).isNotNull()
        client.destroy()
    }

    @Test
    fun `pre freeze release gate rejection preserves operation for explicit retry`() = runTest {
        val handle = PermanentAccountDeletionHandle("blocked-operation-000000000001")
        val permanentDeletion = RecordingPermanentDeletionCoordinator().apply {
            pendingHandle = handle
            commitFailure = io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException()
        }
        var matrixDeactivateCalls = 0
        val client = createRustMatrixClient(
            client = FakeFfiClient(deactivateAccountResult = { _, _ -> matrixDeactivateCalls += 1 }),
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val result = client.deactivateAccount("unused", eraseData = true)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException::class.java)
        assertThat(permanentDeletion.abandonCalls).isEmpty()
        assertThat(permanentDeletion.pendingHandle).isEqualTo(handle)
        assertThat(matrixDeactivateCalls).isEqualTo(0)
        client.destroy()
    }

    @Test
    fun `Matrix terminal success survives delayed control plane observation without a second Matrix delete`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator().apply {
            waitingCommitCount = 60
        }
        var matrixDeactivateCalls = 0
        val client = createRustMatrixClient(
            client = FakeFfiClient(
                deactivateAccountResult = { authData, _ ->
                    matrixDeactivateCalls += 1
                    if (authData == null) error("interactive auth required")
                },
            ),
            sessionStore = deletionSessionStore(),
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        val first = client.deactivateAccount("correct-password", eraseData = false)
        val second = client.deactivateAccount("ignored-on-resume", eraseData = false)

        assertThat(first.exceptionOrNull()).isInstanceOf(PermanentAccountDeletionAwaitingMatrixException::class.java)
        assertThat(second.isSuccess).isTrue()
        assertThat(permanentDeletion.preparedRequests).hasSize(1)
        assertThat(matrixDeactivateCalls).isEqualTo(2)
        assertThat(permanentDeletion.commitCalls).hasSize(61)
        assertThat(permanentDeletion.completeCalls).hasSize(1)
    }

    @Test
    fun `account logout cleanup path never enters permanent account deletion protocol`() = runTest {
        val permanentDeletion = RecordingPermanentDeletionCoordinator()
        val localDeletion = FakeDataDeletionCoordinator()
        val client = createRustMatrixClient(
            sessionStore = deletionSessionStore(),
            dataDeletionCoordinator = localDeletion,
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        client.deleteProtectedAccountData(DeletionReason.ACCOUNT_LOGOUT)

        assertThat(permanentDeletion.preparedRequests).isEmpty()
        assertThat(permanentDeletion.commitCalls).isEmpty()
        assertThat(localDeletion.requests.single().reason).isEqualTo(DeletionReason.ACCOUNT_LOGOUT)
        client.destroy()
    }

    @Test
    fun `user logout durably marks account reactivation before remote session removal`() = runTest {
        val lifecycleStore = RecordingAccountReactivationScopeStore()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ffiClient = FakeFfiClient(
            logoutResult = {
                assertThat(lifecycleStore.removedScopes).hasSize(1)
            },
        )
        val client = createRustMatrixClient(
            client = ffiClient,
            sessionStore = deletionSessionStore(),
            accountReactivationScopeStore = lifecycleStore,
            appCoroutineScope = appScope,
            sessionDelegateAppCoroutineScope = appScope,
            dispatchers = productionLikeCoroutineDispatchers(),
        )

        client.logout(userInitiated = true, ignoreSdkError = false)

        assertThat(lifecycleStore.removedScopes).hasSize(1)
        assertThat(lifecycleStore.restoredScopes).isEmpty()
        appScope.cancel()
    }

    @Test
    fun `failed remote logout rolls back the reactivation marker for the same session`() = runTest {
        val lifecycleStore = RecordingAccountReactivationScopeStore()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = createRustMatrixClient(
            client = FakeFfiClient(logoutResult = { error("network failure") }),
            sessionStore = deletionSessionStore(),
            accountReactivationScopeStore = lifecycleStore,
            appCoroutineScope = appScope,
            sessionDelegateAppCoroutineScope = appScope,
            dispatchers = productionLikeCoroutineDispatchers(),
        )

        val result = runCatching { client.logout(userInitiated = true, ignoreSdkError = false) }

        assertThat(result.exceptionOrNull()).hasMessageThat().contains("network failure")
        assertThat(lifecycleStore.removedScopes).hasSize(1)
        assertThat(lifecycleStore.restoredScopes).containsExactly(lifecycleStore.removedScopes.single())
        appScope.cancel()
    }

    @Test
    fun `LC08 proof uses current SDK token without exposing stored vault envelope`() = runTest {
        val runtime = io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession().copy(
            accessToken = "dedicated-runtime-token-123456", homeserverUrl = "https://test.invalid/",
        )
        val stored = aSessionData(sessionId = A_USER_ID.value, deviceId = A_DEVICE_ID.value,
            isTokenValid = true, accessToken = "yzvault1:sealed-not-a-token").copy(homeserverUrl = "https://test.invalid")
        val store = InMemorySessionStore(initialList = listOf(stored), updateUserProfileResult = { _, _, _ -> })
        val sut = createRustMatrixClient(client = FakeFfiClient(session = runtime), sessionStore = store)
        assertThat(sut.recoverySessionAccessToken().getOrThrow()).isEqualTo(runtime.accessToken)
        assertThat(store.getSession(A_USER_ID.value)?.accessToken).isEqualTo(stored.accessToken)
    }

    @Test
    fun `LC08 proof rejects invalid stored token state and missing session`() = runTest {
        for (sessions in listOf(emptyList(), listOf(aSessionData(sessionId = A_USER_ID.value,
            deviceId = A_DEVICE_ID.value, isTokenValid = false)))) {
            val sut = createRustMatrixClient(sessionStore = InMemorySessionStore(initialList = sessions, updateUserProfileResult = { _, _, _ -> }))
            assertThat(sut.recoverySessionAccessToken().isFailure).isTrue()
        }
    }

    @Test
    fun `LC08 proof rejects SDK account device homeserver and credential mismatches`() = runTest {
        val runtime = io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession().copy(
            accessToken = "dedicated-runtime-token-123456", homeserverUrl = "https://test.invalid",
        )
        val stored = aSessionData(sessionId = A_USER_ID.value, deviceId = A_DEVICE_ID.value,
            isTokenValid = true, accessToken = "yzvault1:sealed-not-a-token").copy(homeserverUrl = runtime.homeserverUrl)
        val variants = listOf(runtime.copy(userId = "@other:test.invalid"), runtime.copy(deviceId = "other-device"),
            runtime.copy(homeserverUrl = "https://other.invalid"), runtime.copy(accessToken = "yzvault1:sealed-not-a-token"),
            runtime.copy(accessToken = ""), runtime.copy(accessToken = "invalid-credential\n12345"))
        for (variant in variants) {
            val sut = createRustMatrixClient(client = FakeFfiClient(session = variant),
                sessionStore = InMemorySessionStore(initialList = listOf(stored), updateUserProfileResult = { _, _, _ -> }))
            assertThat(sut.recoverySessionAccessToken().isFailure).isTrue()
        }
    }

    private fun deletionSessionStore(): SessionStore = InMemorySessionStore(
        initialList = listOf(aSessionData(sessionId = A_USER_ID.value)),
        updateUserProfileResult = { _, _, _ -> },
    )

    private fun productionLikeCoroutineDispatchers() = CoroutineDispatchers(
        io = Dispatchers.Default,
        computation = Dispatchers.Default,
        main = Dispatchers.Default,
    )

    private fun TestScope.createRustMatrixClient(
        client: Client = FakeFfiClient(),
        sessionStore: SessionStore = InMemorySessionStore(
            updateUserProfileResult = { _, _, _ -> },
        ),
        dataDeletionCoordinator: FakeDataDeletionCoordinator = FakeDataDeletionCoordinator(),
        permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator = FakePermanentAccountDeletionCoordinator(),
        accountReactivationScopeStore: AccountReactivationScopeStore = RecordingAccountReactivationScopeStore(),
        appCoroutineScope: CoroutineScope = backgroundScope,
        sessionDelegateAppCoroutineScope: CoroutineScope = this,
        dispatchers: CoroutineDispatchers = testCoroutineDispatchers(),
        workManagerScheduler: WorkManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
    ) = RustMatrixClient(
        innerClient = client,
        sessionStore = sessionStore,
        appCoroutineScope = appCoroutineScope,
        sessionDelegate = aRustClientSessionDelegate(
            sessionStore = sessionStore,
            appCoroutineScope = sessionDelegateAppCoroutineScope,
        ),
        innerSyncService = FakeFfiSyncService(),
        dispatchers = dispatchers,
        baseCacheDirectory = File(""),
        clock = FakeSystemClock(),
        timelineEventFilterFactory = FakeTimelineEventFilterFactory(),
        featureFlagService = FakeFeatureFlagService(),
        analyticsService = FakeAnalyticsService(),
        workManagerScheduler = workManagerScheduler,
        sensitiveFileStore = InMemorySensitiveFileStore(),
        secureMediaLeaseManager = DisabledSecureMediaLeaseManager,
        secureFileStoreEnabled = false,
        dataDeletionCoordinator = dataDeletionCoordinator,
        permanentAccountDeletionCoordinator = permanentAccountDeletionCoordinator,
        accountReactivationScopeStore = accountReactivationScopeStore,
        dataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(),
        deviceRootKeyService = FakeDeviceRootKeyService(TEST_INSTALLATION_ID),
        appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    )
}

private class RecordingAccountReactivationScopeStore : AccountReactivationScopeStore {
    data class Scope(val accountIdHash: String, val installationId: String, val matrixDeviceIdHash: String)

    val removedScopes = mutableListOf<Scope>()
    val restoredScopes = mutableListOf<Scope>()

    override suspend fun current(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationCheckpoint? = null

    override suspend fun requiresCoreReadyEnforcement(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = false

    override suspend fun markAccountRemoved(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean {
        removedScopes += Scope(accountIdHash, installationId, matrixDeviceIdHash)
        return true
    }

    override suspend fun restoreAccountActive(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean {
        restoredScopes += Scope(accountIdHash, installationId, matrixDeviceIdHash)
        return true
    }

    override suspend fun markExplicitIdentityReset(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = error("Not used")

    override suspend fun nextAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationAttempt = error("Not used")

    override suspend fun markPrepared(
        attempt: AccountReactivationAttempt,
        prepared: PreparedAccountReactivation,
    ): AccountReactivationCheckpoint = error("Not used")

    override suspend fun markCommitted(
        attempt: AccountReactivationAttempt,
        receipt: AccountActivationReceipt,
    ): AccountReactivationCheckpoint = error("Not used")

    override suspend fun nextHistoryRecoveryAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        roomScopeIds: Set<String>,
    ): HistoryRecoveryAttempt = error("Not used")

    override suspend fun clearPermanentlyDeletedAccount(accountIdHash: String, installationId: String): Boolean = true
}

private class RecordingPermanentDeletionCoordinator : PermanentAccountDeletionCoordinator {
    val preparedRequests = mutableListOf<Pair<DeletionRequest, Boolean>>()
    val commitCalls = mutableListOf<PermanentAccountDeletionHandle>()
    val completeCalls = mutableListOf<Pair<PermanentAccountDeletionHandle, Set<DeletionStep>>>()
    val abandonCalls = mutableListOf<PermanentAccountDeletionHandle>()
    var pendingHandle: PermanentAccountDeletionHandle? = null
    var waitingCommitCount = 0
    var commitFailure: Throwable? = null

    override suspend fun prepare(
        localDeletionRequest: DeletionRequest,
        eraseData: Boolean,
    ): Result<PermanentAccountDeletionHandle> {
        preparedRequests += localDeletionRequest to eraseData
        return Result.success(
            PermanentAccountDeletionHandle(localDeletionRequest.operationId.value).also { pendingHandle = it }
        )
    }

    override suspend fun pending(accountIdHash: String): PermanentAccountDeletionHandle? = pendingHandle

    override suspend fun commitRemote(handle: PermanentAccountDeletionHandle): Result<AccountDeletionReceipt> {
        commitCalls += handle
        commitFailure?.let { return Result.failure(it) }
        if (waitingCommitCount > 0) {
            waitingCommitCount -= 1
            return Result.failure(PermanentAccountDeletionAwaitingMatrixException())
        }
        return Result.success(
            AccountDeletionReceipt(
                deletionIntentId = "deletion-intent-00000000000001",
                accountIdHash = "0".repeat(64),
                installationId = "installation-000000000000001",
                globalDestroyJobId = "global-destroy-000000000000001",
                eraseData = true,
                committedAtEpochMillis = 1,
            )
        )
    }

    override suspend fun completeLocal(
        handle: PermanentAccountDeletionHandle,
        confirmedPreconditions: Set<DeletionStep>,
    ): DeletionReport {
        completeCalls += handle to confirmedPreconditions
        pendingHandle = null
        return DeletionReport(
            operationId = DeletionOperationId(handle.value),
            completedSteps = DeletionStep.entries.toSet(),
            retryRequired = false,
        )
    }

    override suspend fun abandon(handle: PermanentAccountDeletionHandle): Boolean {
        abandonCalls += handle
        pendingHandle = null
        return true
    }

    override suspend fun resumePending(coldStart: Boolean): PermanentAccountDeletionRecoveryReport =
        PermanentAccountDeletionRecoveryReport(0, 0, 0, 0)
}

internal object DisabledSecureMediaLeaseManager : SecureMediaLeaseManager {
    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Secure media is disabled in this test.")
}
