/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.chatsecurity

import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.PendingDeviceRecovery

enum class PublicDeviceSecurityState {
    NOT_STARTED,
    PROCESSING,
    PASSED,
    NOT_PASSED,
    ACTION_REQUIRED,
    SERVICE_UNAVAILABLE,
}

sealed interface PublicHistoryRecoveryState {
    data object CHECKING : PublicHistoryRecoveryState
    data class AVAILABLE(val messageCount: Int) : PublicHistoryRecoveryState
    data class RECOVERING(val messageCount: Int) : PublicHistoryRecoveryState
    data class WAITING_FOR_OLD_DEVICE(val messageCount: Int) : PublicHistoryRecoveryState
    data class WAITING_FOR_ADMIN_APPROVAL(val messageCount: Int) : PublicHistoryRecoveryState
    data class FAILED_CLOSED(val messageCount: Int) : PublicHistoryRecoveryState
    data class IDENTITY_RESET_UNRECOVERABLE(val messageCount: Int) : PublicHistoryRecoveryState
    data object NO_MATRIX_BACKUP : PublicHistoryRecoveryState
    data object DEVICE_VERIFICATION_REQUIRED : PublicHistoryRecoveryState
    data object NONE : PublicHistoryRecoveryState
    data object SERVICE_UNAVAILABLE : PublicHistoryRecoveryState
}

enum class PublicRecoveryActionFailure {
    SERVICE_UNAVAILABLE,
    REQUEST_EXPIRED,
    APPROVAL_REQUIRED,
    DEVICE_NOT_ELIGIBLE,
    SECURITY_REJECTED,
    UNKNOWN,
}

data class ChatSecurityState(
    val deviceSecurityState: PublicDeviceSecurityState,
    val historyRecoveryState: PublicHistoryRecoveryState,
    val currentDeviceCode: String?,
    val deviceRecoveryStatus: DeviceRecoveryStatus?,
    val pendingDeviceRecoveries: List<PendingDeviceRecovery>,
    val isRefreshing: Boolean,
    val isRecoveryActionRunning: Boolean,
    val recoveryActionFailure: PublicRecoveryActionFailure?,
    val unavailableHistoryCount: Int = 0,
    val isLocalHistoryCleanupRunning: Boolean = false,
    val localHistoryCleanupFailed: Boolean = false,
    val eventSink: (ChatSecurityEvent) -> Unit,
)

sealed interface ChatSecurityEvent {
    data object Refresh : ChatSecurityEvent
    data object RequestDeviceRecovery : ChatSecurityEvent
    data class ConfirmDeviceRecovery(val recoveryId: String) : ChatSecurityEvent
    data object ClearUnavailableHistoryLocally : ChatSecurityEvent
    data object DismissLocalHistoryCleanupError : ChatSecurityEvent
    data object DismissRecoveryError : ChatSecurityEvent
}
