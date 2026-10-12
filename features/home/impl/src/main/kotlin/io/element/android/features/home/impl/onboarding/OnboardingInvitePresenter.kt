/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.OnboardingInvitation
import kotlinx.coroutines.launch
import java.util.UUID

@Inject
class OnboardingInvitePresenter(
    private val matrixClient: MatrixClient,
    private val managementControlService: ManagementControlService,
) : Presenter<OnboardingInviteState> {
    @Composable
    override fun present(): OnboardingInviteState {
        val scope = rememberCoroutineScope()
        val invitation = remember { mutableStateOf<AsyncData<OnboardingInvitation>>(AsyncData.Uninitialized) }

        fun generate() {
            scope.launch {
                invitation.value = AsyncData.Loading()
                runCatching {
                    managementControlService.createOnboardingInvitation(
                        inviterUserId = matrixClient.sessionId.value,
                        generationRequestId = UUID.randomUUID().toString(),
                    )
                }.onSuccess { invitation.value = AsyncData.Success(it) }
                    .onFailure { invitation.value = AsyncData.Failure(it) }
            }
        }

        fun handleEvent(event: OnboardingInviteEvent) {
            when (event) {
                OnboardingInviteEvent.Generate -> generate()
                OnboardingInviteEvent.Revoke -> {
                    val current = invitation.value.dataOrNull() ?: return
                    scope.launch {
                        runCatching {
                            managementControlService.revokeOnboardingInvitation(
                                publicId = current.publicId,
                                inviterUserId = matrixClient.sessionId.value,
                            )
                        }.onSuccess { invitation.value = AsyncData.Success(it) }
                            .onFailure { invitation.value = AsyncData.Failure(it, current) }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            if (invitation.value is AsyncData.Uninitialized) generate()
        }
        return OnboardingInviteState(invitation = invitation.value, eventSink = ::handleEvent)
    }
}
