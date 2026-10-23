/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.update

/** User-safe projection of startup/release failures. Raw exceptions are never part of this API. */
internal enum class StartupGateUserState {
    CHECKING,
    RETRYABLE,
    BLOCKED,
    UPDATE,
    DIAGNOSTIC,
}

internal enum class StartupGatePrimaryAction {
    NONE,
    RETRY_SECURITY_CHECK,
    TRANSFER_MANAGED_AUTHORIZATION,
    RETRY_RELEASE_CHECK,
    DOWNLOAD_UPDATE,
    RETRY_DOWNLOAD,
    INSTALL_UPDATE,
}

internal enum class StartupGateRetrySemantics {
    NONE,
    RESUME_SAME_READINESS_OPERATION,
    FRESH_VERIFIED_READINESS,
    FRESH_VERIFIED_IDENTITY,
    RESUME_SAME_LC01_OPERATION,
    RESUME_VERIFIED_DOWNLOAD,
    REVERIFY_NO_DOWNGRADE,
    RECONCILE_AFTER_INSTALLER_RETURN,
}

internal enum class StartupGateAnnouncement {
    NONE,
    POLITE_STATUS,
    ASSERTIVE_BLOCKED,
    ASSERTIVE_DIAGNOSTIC,
    POLITE_UPDATE,
}

internal data class StartupGateAccessibilityContract(
    val reasonCode: String,
    val userState: StartupGateUserState,
    val primaryAction: StartupGatePrimaryAction,
    val retrySemantics: StartupGateRetrySemantics,
    val announcement: StartupGateAnnouncement,
    val focusOrder: List<String>,
    val errorNotColorOnly: Boolean,
) {
    init {
        require(reasonCode.matches(Regex("[A-Z0-9_]{1,128}")))
        require(focusOrder == listOf("status", "primary_action"))
    }
}

internal fun startupGateAccessibilityContract(reasonCode: String?): StartupGateAccessibilityContract {
    val stableReason = reasonCode?.takeIf { it in STARTUP_GATE_REASON_CODES } ?: "STARTUP_UNKNOWN"
    return when (stableReason) {
        "STARTUP_READINESS_BOOTSTRAPPING" -> contract(
            stableReason,
            StartupGateUserState.CHECKING,
            StartupGatePrimaryAction.NONE,
            StartupGateRetrySemantics.NONE,
            StartupGateAnnouncement.POLITE_STATUS,
        )
        "STARTUP_READINESS_TIMEOUT",
        "CONTROL_PLANE_UNAVAILABLE",
        "CONTROL_DNS_UNAVAILABLE",
        "CONTROL_CONNECTION_FAILED",
        "CONTROL_REQUEST_TIMEOUT",
        "CONTROL_NETWORK_IO",
        "CONTROL_SERVER_UNAVAILABLE",
        "CONTROL_RATE_LIMITED",
        "OFFLINE_ENTRY_EXPIRED",
        "REMOTE_POLICY_APPLICATION_PENDING",
        "REMOTE_COMMAND_EXECUTION_PENDING",
        "REMOTE_COMMAND_REPORT_PENDING",
        "SIGNED_POLICY_UNAVAILABLE" -> contract(
            stableReason,
            StartupGateUserState.RETRYABLE,
            StartupGatePrimaryAction.RETRY_SECURITY_CHECK,
            StartupGateRetrySemantics.RESUME_SAME_READINESS_OPERATION,
            StartupGateAnnouncement.POLITE_STATUS,
        )
        "COMPATIBILITY_BINDING_CONFLICT" -> contract(
            stableReason,
            StartupGateUserState.BLOCKED,
            StartupGatePrimaryAction.TRANSFER_MANAGED_AUTHORIZATION,
            StartupGateRetrySemantics.FRESH_VERIFIED_IDENTITY,
            StartupGateAnnouncement.ASSERTIVE_BLOCKED,
        )
        "COMPATIBILITY_POLICY_DISABLED",
        "COMPATIBILITY_EVIDENCE_REJECTED",
        "DEVICE_TRUST_NOT_READY",
        "DEVICE_ADMINISTRATIVELY_LOCKED",
        "DEVICE_SIGNING_KEY_UNAVAILABLE",
        "CONTROL_TLS_REJECTED",
        "CONTROL_SERVER_REJECTED",
        "REMOTE_COMMAND_EXECUTION_FAILED",
        "SIGNED_POLICY_EXPIRED",
        "SECURITY_POLICY_REJECTED",
        "ACCESS_TICKET_EXPIRED" -> contract(
            stableReason,
            StartupGateUserState.BLOCKED,
            StartupGatePrimaryAction.RETRY_SECURITY_CHECK,
            StartupGateRetrySemantics.FRESH_VERIFIED_READINESS,
            StartupGateAnnouncement.ASSERTIVE_BLOCKED,
        )
        "INSTALLATION_IDENTITY_MISMATCH" -> contract(
            stableReason,
            StartupGateUserState.BLOCKED,
            StartupGatePrimaryAction.RETRY_SECURITY_CHECK,
            StartupGateRetrySemantics.FRESH_VERIFIED_IDENTITY,
            StartupGateAnnouncement.ASSERTIVE_BLOCKED,
        )
        "STARTUP_CONFIGURATION_DISABLED",
        "STARTUP_SNAPSHOT_STALE",
        "STARTUP_AUTHORITY_CONTRADICTORY",
        "STARTUP_UNKNOWN" -> contract(
            stableReason,
            StartupGateUserState.DIAGNOSTIC,
            StartupGatePrimaryAction.RETRY_SECURITY_CHECK,
            StartupGateRetrySemantics.FRESH_VERIFIED_READINESS,
            StartupGateAnnouncement.ASSERTIVE_DIAGNOSTIC,
        )
        "STARTUP_READINESS_EXPIRED" -> contract(
            stableReason,
            StartupGateUserState.RETRYABLE,
            StartupGatePrimaryAction.RETRY_SECURITY_CHECK,
            StartupGateRetrySemantics.FRESH_VERIFIED_READINESS,
            StartupGateAnnouncement.POLITE_STATUS,
        )
        "RELEASE_MANIFEST_UNAVAILABLE" -> contract(
            stableReason,
            StartupGateUserState.RETRYABLE,
            StartupGatePrimaryAction.RETRY_RELEASE_CHECK,
            StartupGateRetrySemantics.RESUME_SAME_LC01_OPERATION,
            StartupGateAnnouncement.POLITE_STATUS,
        )
        "RELEASE_ARTIFACT_MISMATCH" -> contract(
            stableReason,
            StartupGateUserState.BLOCKED,
            StartupGatePrimaryAction.RETRY_RELEASE_CHECK,
            StartupGateRetrySemantics.REVERIFY_NO_DOWNGRADE,
            StartupGateAnnouncement.ASSERTIVE_BLOCKED,
        )
        "MANDATORY_UPDATE_REQUIRED" -> contract(
            stableReason,
            StartupGateUserState.UPDATE,
            StartupGatePrimaryAction.DOWNLOAD_UPDATE,
            StartupGateRetrySemantics.NONE,
            StartupGateAnnouncement.POLITE_UPDATE,
        )
        "UPDATE_DOWNLOAD_FAILED" -> contract(
            stableReason,
            StartupGateUserState.UPDATE,
            StartupGatePrimaryAction.RETRY_DOWNLOAD,
            StartupGateRetrySemantics.RESUME_VERIFIED_DOWNLOAD,
            StartupGateAnnouncement.POLITE_UPDATE,
        )
        "INSTALLER_RETURN" -> contract(
            stableReason,
            StartupGateUserState.UPDATE,
            StartupGatePrimaryAction.RETRY_RELEASE_CHECK,
            StartupGateRetrySemantics.RECONCILE_AFTER_INSTALLER_RETURN,
            StartupGateAnnouncement.POLITE_UPDATE,
        )
        else -> error("Unhandled stable startup gate reason: $stableReason")
    }
}

private fun contract(
    reasonCode: String,
    userState: StartupGateUserState,
    primaryAction: StartupGatePrimaryAction,
    retrySemantics: StartupGateRetrySemantics,
    announcement: StartupGateAnnouncement,
) = StartupGateAccessibilityContract(
    reasonCode = reasonCode,
    userState = userState,
    primaryAction = primaryAction,
    retrySemantics = retrySemantics,
    announcement = announcement,
    focusOrder = listOf("status", "primary_action"),
    errorNotColorOnly = true,
)

internal val STARTUP_GATE_REASON_CODES: Set<String> = setOf(
    "CONTROL_SERVER_UNAVAILABLE",
    "CONTROL_RATE_LIMITED",
    "OFFLINE_ENTRY_EXPIRED",
    "REMOTE_POLICY_APPLICATION_PENDING",
    "STARTUP_READINESS_BOOTSTRAPPING",
    "STARTUP_READINESS_TIMEOUT",
    "CONTROL_PLANE_UNAVAILABLE",
    "CONTROL_DNS_UNAVAILABLE",
    "CONTROL_CONNECTION_FAILED",
    "CONTROL_REQUEST_TIMEOUT",
    "CONTROL_NETWORK_IO",
    "CONTROL_TLS_REJECTED",
    "CONTROL_SERVER_REJECTED",
    "REMOTE_COMMAND_EXECUTION_PENDING",
    "REMOTE_COMMAND_REPORT_PENDING",
    "REMOTE_COMMAND_EXECUTION_FAILED",
    "DEVICE_SIGNING_KEY_UNAVAILABLE",
    "SIGNED_POLICY_UNAVAILABLE",
    "COMPATIBILITY_POLICY_DISABLED",
    "COMPATIBILITY_EVIDENCE_REJECTED",
    "COMPATIBILITY_BINDING_CONFLICT",
    "DEVICE_TRUST_NOT_READY",
    "DEVICE_ADMINISTRATIVELY_LOCKED",
    "SIGNED_POLICY_EXPIRED",
    "SECURITY_POLICY_REJECTED",
    "ACCESS_TICKET_EXPIRED",
    "INSTALLATION_IDENTITY_MISMATCH",
    "STARTUP_CONFIGURATION_DISABLED",
    "STARTUP_SNAPSHOT_STALE",
    "STARTUP_AUTHORITY_CONTRADICTORY",
    "STARTUP_READINESS_EXPIRED",
    "RELEASE_MANIFEST_UNAVAILABLE",
    "RELEASE_ARTIFACT_MISMATCH",
    "MANDATORY_UPDATE_REQUIRED",
    "UPDATE_DOWNLOAD_FAILED",
    "INSTALLER_RETURN",
    "STARTUP_UNKNOWN",
)
