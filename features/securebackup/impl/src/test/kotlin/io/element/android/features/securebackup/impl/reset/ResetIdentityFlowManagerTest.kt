/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.reset

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.encryption.BackupState
import io.element.android.libraries.matrix.api.encryption.IdentityResetCompletionState
import io.element.android.libraries.matrix.api.encryption.IdentityResetHandle
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.encryption.FakeIdentityPasswordResetHandle
import io.element.android.security.dataprotection.api.AccountIdentityResetLifecycleRecorder
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResetIdentityFlowManagerTest {
    @Test
    fun `existing verified identity cannot satisfy a new reset`() = runTest {
        val encryption = FakeEncryptionService().apply {
            givenIdentityResetCompletionStateResult(Result.success(IdentityResetCompletionState(
                BackupState.ENABLED, RecoveryState.ENABLED, true, true, "a".repeat(64),
            )))
        }
        assertThat(createFlowManager(encryption).completeReset { awaitCancellation() }.isFailure).isTrue()
    }

    @Test
    fun `null handle records completion before reporting success`() = runTest {
        val calls = mutableListOf<String>()
        val encryption = FakeEncryptionService(startIdentityResetLambda = {
            calls += "sdk"
            Result.success(null)
        })
        val manager = createFlowManager(encryption, object : AccountIdentityResetLifecycleRecorder {
            override suspend fun beginExplicitReset(): Result<String> {
                calls += "intent"
                return Result.success("a".repeat(64))
            }
            override suspend fun recordExplicitReset(): Result<Unit> {
                calls += "complete"
                return Result.success(Unit)
            }
        })
        manager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
        }
        assertThat(calls).containsExactly("intent", "sdk", "complete").inOrder()
    }

    @Test
    fun `getResetHandle - emits a reset handle`() = runTest {
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> { Result.success(FakeIdentityPasswordResetHandle()) }
        val encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda)
        val flowManager = createFlowManager(encryptionService = encryptionService)

        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
            startResetLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `getResetHandle - om successful handle retrieval returns that same handle`() = runTest {
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> { Result.success(FakeIdentityPasswordResetHandle()) }
        val encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda)
        val flowManager = createFlowManager(encryptionService = encryptionService)

        var result: AsyncData.Success<IdentityResetHandle>? = null
        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            @Suppress("UNCHECKED_CAST")
            result = awaitItem() as? AsyncData.Success<IdentityResetHandle>
            assertThat(result).isNotNull()
        }

        flowManager.getResetHandle().test {
            assertThat(awaitItem()).isSameInstanceAs(result)
        }
    }

    @Test
    fun `getResetHandle - will success if it receives a null reset handle`() = runTest {
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> { Result.success(null) }
        val encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda)
        val flowManager = createFlowManager(encryptionService = encryptionService)

        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            val finalItem = awaitItem()
            assertThat(finalItem.isSuccess()).isTrue()
            assertThat(finalItem.dataOrNull()).isNull()
            startResetLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `getResetHandle - fails gracefully when receiving an exception from the encryption service`() = runTest {
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> { Result.failure(IllegalStateException("Failure")) }
        val encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda)
        val flowManager = createFlowManager(encryptionService = encryptionService)

        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isFailure()).isTrue()
            startResetLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `cancel - resets the state and calls cancel on the reset handle`() = runTest {
        val cancelLambda = lambdaRecorder<Unit> { }
        val resetHandle = FakeIdentityPasswordResetHandle(cancelLambda = cancelLambda)
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> { Result.success(resetHandle) }
        val encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda)
        val flowManager = createFlowManager(encryptionService = encryptionService)

        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()

            flowManager.cancel()
            cancelLambda.assertions().isCalledOnce()
            assertThat(awaitItem().isUninitialized()).isTrue()
        }
    }

    @Test
    fun `completeReset - returns when the SDK reset call succeeds`() = runTest {
        val encryptionService = FakeEncryptionService()
        val flowManager = createFlowManager(encryptionService = encryptionService)

        val result = flowManager.completeReset { Result.success(Unit) }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `cancel - unknown authority outcome is visible and retry reconciles without repeating reset`() = runTest {
        var pending = true
        var starts = 0
        val unknown = IllegalStateException("Reset outcome is still unknown.")
        val manager = createFlowManager(
            encryptionService = FakeEncryptionService(startIdentityResetLambda = {
                starts++
                Result.success(FakeIdentityPasswordResetHandle(cancelLambda = {}))
            }),
            lifecycleRecorder = object : AccountIdentityResetLifecycleRecorder {
                override suspend fun beginExplicitReset() = Result.success("a".repeat(64))
                override suspend fun recordExplicitReset(): Result<Unit> = error("Must reconcile before completion")
                override suspend fun resumeCompletedReset() = Result.success(!pending)
                override suspend fun cancelExplicitReset(): Result<Unit> = Result.failure(unknown)
            },
        )
        manager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
            manager.cancel()
            assertThat((awaitItem() as AsyncData.Failure).error).isSameInstanceAs(unknown)
            manager.dismissError()
            assertThat(awaitItem().isUninitialized()).isTrue()
        }
        pending = false
        manager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            val recovered = awaitItem()
            assertThat(recovered.isSuccess()).isTrue()
            assertThat(recovered.dataOrNull()).isNull()
        }
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `cancel - SDK cleanup failure does not discard durable intent`() = runTest {
        val failed = IllegalStateException("SDK unavailable")
        val manager = createFlowManager(
            encryptionService = FakeEncryptionService(startIdentityResetLambda = {
                Result.success(FakeIdentityPasswordResetHandle(cancelLambda = { throw failed }))
            }),
            lifecycleRecorder = object : AccountIdentityResetLifecycleRecorder {
                override suspend fun beginExplicitReset() = Result.success("a".repeat(64))
                override suspend fun recordExplicitReset(): Result<Unit> = error("Not complete")
                override suspend fun cancelExplicitReset(): Result<Unit> = error("SDK cleanup did not finish")
            },
        )
        manager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
            manager.cancel()
            assertThat((awaitItem() as AsyncData.Failure).error).isSameInstanceAs(failed)
        }
    }

    @Test
    fun `completeReset - consumes the successful handle so a later reset starts a new attempt`() = runTest {
        val startResetLambda = lambdaRecorder<Result<IdentityResetHandle?>> {
            Result.success(FakeIdentityPasswordResetHandle())
        }
        val flowManager = createFlowManager(
            encryptionService = FakeEncryptionService(startIdentityResetLambda = startResetLambda),
        )
        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
        }

        assertThat(flowManager.completeReset { Result.success(Unit) }.isSuccess).isTrue()
        assertThat(flowManager.currentHandleFlow.value.isUninitialized()).isTrue()

        flowManager.getResetHandle().test {
            assertThat(awaitItem().isLoading()).isTrue()
            assertThat(awaitItem().isSuccess()).isTrue()
        }
        startResetLambda.assertions().isCalledExactly(2)
    }

    @Test
    fun `completeReset - fails closed when the reset lifecycle checkpoint cannot be persisted`() = runTest {
        val encryptionService = FakeEncryptionService()
        val flowManager = createFlowManager(
            encryptionService = encryptionService,
            lifecycleRecorder = object : AccountIdentityResetLifecycleRecorder {
                override suspend fun beginExplicitReset(): Result<String> = Result.success("a".repeat(64))
                override suspend fun recordExplicitReset(): Result<Unit> =
                    Result.failure(IllegalStateException("checkpoint unavailable"))
            },
        )

        val result = flowManager.completeReset { Result.success(Unit) }

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `completeReset - returns when an independently refreshed completion proof succeeds`() = runTest {
        val encryptionService = FakeEncryptionService().apply {
            givenIdentityResetCompletionStateResult(
                Result.success(
                    IdentityResetCompletionState(
                        backupState = BackupState.ENABLED,
                        recoveryState = RecoveryState.ENABLED,
                        isCurrentSessionVerified = true,
                        backupExistsOnServer = true,
                        identityKeyHash = "b".repeat(64),
                    )
                )
            )
        }
        val flowManager = createFlowManager(encryptionService = encryptionService)

        val result = flowManager.completeReset { awaitCancellation() }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `completeReset - does not accept a server backup without local activation and device verification`() = runTest {
        val encryptionService = FakeEncryptionService().apply {
            givenIdentityResetCompletionStateResult(
                Result.success(
                    IdentityResetCompletionState(
                        backupState = BackupState.UNKNOWN,
                        recoveryState = RecoveryState.UNKNOWN,
                        isCurrentSessionVerified = false,
                        backupExistsOnServer = true,
                    )
                )
            )
        }
        val flowManager = createFlowManager(encryptionService = encryptionService)

        val result = flowManager.completeReset { awaitCancellation() }

        assertThat(result.isFailure).isTrue()
    }

    private fun TestScope.createFlowManager(
        encryptionService: FakeEncryptionService = FakeEncryptionService(),
        lifecycleRecorder: AccountIdentityResetLifecycleRecorder = object : AccountIdentityResetLifecycleRecorder {
            override suspend fun beginExplicitReset(): Result<String> = Result.success("a".repeat(64))
            override suspend fun recordExplicitReset(): Result<Unit> = Result.success(Unit)
        },
    ) = ResetIdentityFlowManager(
        encryptionService = encryptionService,
        identityResetLifecycleRecorder = lifecycleRecorder,
        sessionCoroutineScope = this,
    )
}
