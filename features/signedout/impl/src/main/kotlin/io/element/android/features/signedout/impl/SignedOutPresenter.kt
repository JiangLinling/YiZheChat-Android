/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.signedout.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.signedOutCleanupOperationId
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.dataprotection.api.ManagementControlService
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AssistedInject
class SignedOutPresenter(
    @Assisted private val sessionId: SessionId,
    private val sessionStore: SessionStore,
    private val buildMeta: BuildMeta,
    private val managementControlService: ManagementControlService,
    private val dataDeletionCoordinator: DataDeletionCoordinator,
    private val vaultKeyService: VaultKeyService,
) : Presenter<SignedOutState> {
    @AssistedFactory
    fun interface Factory {
        fun create(sessionId: SessionId): SignedOutPresenter
    }

    @Composable
    override fun present(): SignedOutState {
        val signedOutSession by remember {
            sessionStore.sessionsFlow().map { sessions ->
                sessions.firstOrNull { it.userId == sessionId.value }
            }
        }.collectAsState(initial = null)
        val coroutineScope = rememberCoroutineScope()
        var cleanupError by remember { mutableStateOf(false) }
        var preparingSignIn by remember { mutableStateOf(false) }
        val mobileSessionSuperseded by produceState(false, signedOutSession?.deviceId) {
            val session = signedOutSession ?: return@produceState
            fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            while (!value) {
                value = runCatchingExceptions {
                    managementControlService.isMobileSessionSuperseded(hash(session.userId), hash(session.deviceId))
                }.getOrDefault(false)
                if (!value) delay(2_000)
            }
        }

        fun handleEvent(event: SignedOutEvents) {
            when (event) {
                SignedOutEvents.SignInAgain -> coroutineScope.launch {
                    if (preparingSignIn) return@launch
                    val session = signedOutSession ?: return@launch
                    preparingSignIn = true
                    cleanupError = false
                    try {
                        runCatchingExceptions { prepareSignInAgain(session) }
                            .onFailure { cleanupError = true }
                    } finally {
                        preparingSignIn = false
                    }
                }
            }
        }

        return SignedOutState(
            appName = buildMeta.applicationName,
            signedOutSession = signedOutSession,
            eventSink = ::handleEvent,
            mobileSessionSuperseded = mobileSessionSuperseded,
            cleanupError = cleanupError,
            preparingSignIn = preparingSignIn,
        )
    }

    internal suspend fun prepareSignInAgain(session: SessionData) {
        val vaultId = deriveAccountVaultId(session.userId, session.deviceId)
        val accountHash = accountIdHash(session.userId)
        val matrixHash = MessageDigest.getInstance("SHA-256").digest(session.deviceId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val report = dataDeletionCoordinator.prepare(
            DeletionRequest(
                operationId = signedOutCleanupOperationId(accountHash, matrixHash),
                scope = DeletionScope.Account(accountHash, vaultId.value, session.userId, session.sessionPath, session.cachePath),
                reason = DeletionReason.ACCOUNT_LOGOUT,
                // Explicit local sign-out is unconditional, independent of retention policy changes.
                policyVersion = 0,
                requestedAtEpochMillis = session.loginTimestamp?.time ?: 0,
            )
        )
        check(report.errorCode == null && DeletionStep.REQUESTED in report.completedSteps) { "Unable to prepare signed-out cleanup." }
        check(vaultKeyService.destroy(vaultId, VaultDestroyReason.ACCOUNT_LOGOUT)) { "Unable to retire signed-out key access." }
        sessionStore.removeSessionIfPathsMatch(session.userId, session.sessionPath, session.cachePath)
    }
}
