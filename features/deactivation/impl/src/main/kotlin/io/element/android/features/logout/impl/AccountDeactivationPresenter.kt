/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

@Inject
class AccountDeactivationPresenter(
    private val matrixClient: MatrixClient,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator,
) : Presenter<AccountDeactivationState> {
    @Composable
    override fun present(): AccountDeactivationState {
        val localCoroutineScope = rememberCoroutineScope()
        val action: MutableState<AsyncAction<Unit>> = remember {
            mutableStateOf(AsyncAction.Uninitialized)
        }

        val formState = remember { mutableStateOf(DeactivateFormState.Default) }

        fun handleEvent(event: AccountDeactivationEvents) {
            when (event) {
                is AccountDeactivationEvents.SetEraseData -> {
                    updateFormState(formState) {
                        // Permanent deletion is always a complete data erasure. Keep the event for
                        // binary/source compatibility with callers, but never allow it to weaken LC-22.
                        copy(eraseData = true)
                    }
                }
                is AccountDeactivationEvents.SetPassword -> {
                    updateFormState(formState) {
                        copy(password = event.password)
                    }
                }
                is AccountDeactivationEvents.DeactivateAccount ->
                    if (action.value.isConfirming() || event.isRetry) {
                        localCoroutineScope.deactivateAccount(
                            formState = formState.value,
                            action
                        )
                    } else {
                        action.value = AsyncAction.ConfirmingNoParams
                    }
                AccountDeactivationEvents.CloseDialogs -> {
                    action.value = AsyncAction.Uninitialized
                }
            }
        }

        return AccountDeactivationState(
            deactivateFormState = formState.value,
            accountDeactivationAction = action.value,
            eventSink = ::handleEvent,
        )
    }

    private fun updateFormState(formState: MutableState<DeactivateFormState>, updateLambda: DeactivateFormState.() -> DeactivateFormState) {
        formState.value = updateLambda(formState.value)
    }

    private fun CoroutineScope.deactivateAccount(
        formState: DeactivateFormState,
        action: MutableState<AsyncAction<Unit>>,
    ) = launch {
        suspend {
            val accountIdHash = matrixClient.sessionId.value.sha256Hex()
            val pendingDeletion = permanentAccountDeletionCoordinator.pendingRecovery(accountIdHash)
            if (pendingDeletion != null) {
                // A successful prepare has already frozen the account. Seed the generic lifecycle
                // coordinator from the exact durable LC-22 identity so it uses /resume; attempting
                // to create a replacement operation is correctly rejected by the frozen boundary.
                appOperationalStateCoordinator.operation(pendingDeletion.value)
            }
            val request = lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_22,
                trigger = "permanent_delete",
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                operationId = pendingDeletion?.value ?: java.util.UUID.randomUUID().toString(),
            )
            val delete: suspend () -> Unit = {
                // The permanent-deletion coordinator first freezes the LC-22 resource manifest.
                // Push revocation is then performed by the server-owned PUSH_ACCOUNT task so its
                // account-terminal authority receipt can be validated against that frozen scope.
                matrixClient.deactivateAccount(
                    password = formState.password,
                    eraseData = true,
                ).getOrThrow()
            }
            if (pendingDeletion == null) {
                // A new permanent deletion must never resume an unrelated LC-22 logout or local
                // account-removal operation that happens to be non-terminal.
                appOperationalStateCoordinator.perform(request) { delete() }
            } else {
                // Resume the exact durable deletion identity. The lifecycle context prevents the
                // generic coordinator from selecting another LC-22 operation on this installation.
                withContext(LifecycleOperationContext(pendingDeletion.value, FullAppLifecycleId.LC_22)) {
                    appOperationalStateCoordinator.perform(request) { delete() }
                }
            }
        }.runCatchingUpdatingState(action)
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
