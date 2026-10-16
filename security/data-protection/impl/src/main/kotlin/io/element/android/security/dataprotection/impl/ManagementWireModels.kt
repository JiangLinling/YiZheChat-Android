/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ChallengeWire(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("challenge_b64") val challengeBase64: String,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
)

@Serializable
internal data class EnrollmentWire(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("attestation_version") val attestationVersion: Long,
    @SerialName("challenge_sha256_b64") val challengeSha256Base64: String,
    @SerialName("statement_b64") val statementBase64: String,
    @SerialName("signed_statement_b64") val signedStatementBase64: String,
    @SerialName("certificate_chain_der_b64") val certificateChainDerBase64: List<String>,
    @SerialName("hardware_security_level") val hardwareSecurityLevel: String,
    @SerialName("app_package_name") val appPackageName: String,
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("app_signing_certificate_sha256_b64") val appSigningCertificateSha256Base64: List<String>,
    @SerialName("risk_signals") val riskSignals: List<String>,
    @SerialName("content_share_key_id") val contentShareKeyId: String,
    @SerialName("content_share_algorithm") val contentShareAlgorithm: String,
    @SerialName("content_share_public_key_der_b64") val contentSharePublicKeyDerBase64: String,
    @SerialName("device_manufacturer") val deviceManufacturer: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("api_level") val apiLevel: Int,
)

@Serializable
internal data class HeartbeatWire(
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("attestation_version") val attestationVersion: Long,
    @SerialName("device_correlation_id_sha256") val deviceCorrelationIdSha256: String? = null,
    @SerialName("account_id_hashes") val accountIdHashes: List<String>,
    @SerialName("account_user_ids") val accountUserIds: List<String> = emptyList(),
    @SerialName("matrix_device_bindings") val matrixDeviceBindings: List<MatrixDeviceBindingWire>,
    @SerialName("matrix_identity_state") val matrixIdentityState: String,
    @SerialName("matrix_cross_signing_key_hash") val matrixCrossSigningKeyHash: String? = null,
    @SerialName("kms_state") val kmsState: String,
    @SerialName("attestation_state") val attestationState: String,
    @SerialName("policy_version") val policyVersion: Long?,
    @SerialName("access_session_protocol_version") val accessSessionProtocolVersion: Int = 2,
    @SerialName("managed_license_protocol_version") val managedLicenseProtocolVersion: Int = 3,
    @SerialName("offline_entry_protocol_version") val offlineEntryProtocolVersion: Int = 1,
    @SerialName("local_list_entry_protocol_version") val localListEntryProtocolVersion: Int = 1,
    @SerialName("tombstone_sequence") val tombstoneSequence: Long = 0,
)

@Serializable
internal data class MatrixDeviceBindingWire(
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String,
)

@Serializable
internal data class DeviceLocationEvidenceWire(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("captured_at_epoch_millis") val capturedAtEpochMillis: Long,
    val provider: String,
    @SerialName("is_mock") val isMock: Boolean,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Int,
    @SerialName("consent_version") val consentVersion: Int = 1,
)

@Serializable
internal data class DeviceLocationEvidenceResponseWire(
    @SerialName("location_evidence_state") val locationEvidenceState: String,
)

@Serializable
internal data class SignedJsonEnvelopeWire(
    val policy: JsonObject? = null,
    val command: JsonObject? = null,
    val decision: JsonObject? = null,
    val license: JsonObject? = null,
    val release: JsonObject? = null,
    val ticket: JsonObject? = null,
    val receipt: JsonObject? = null,
    val grant: JsonObject? = null,
    val milestone: JsonObject? = null,
    val tombstones: JsonObject? = null,
    @SerialName("key_id") val keyId: String,
    @SerialName("signature_b64") val signatureBase64: String,
) {
    fun payload(): JsonObject = policy ?: command ?: decision ?: license ?: release ?: ticket ?: receipt ?: grant ?: milestone ?: tombstones
        ?: error("Signed envelope contains no payload.")
}

/**
 * CP-POLICY signature schema v1. This is deliberately separate from the legacy
 * compatibility policy envelope: both signatures must verify before the policy
 * can enter the local application state machine.
 */
@Serializable
internal data class SignedPolicyPublicationWire(
    val publication: JsonObject,
    @SerialName("key_id") val keyId: String,
    @SerialName("signature_b64") val signatureBase64: String,
)

/**
 * Exact R05 object carried by the existing authenticated full-sync `policy` field.
 *
 * Field names intentionally match the control-plane `SignedPolicyRevisionEnvelope.as_dict()`
 * contract. This transport object is not itself application evidence.
 */
@Serializable
internal data class SignedPolicyRevisionEnvelopeWire(
    val contract: String,
    val signatureSchemaVersion: Int,
    val revisionId: String,
    val operationId: String,
    val domain: String,
    val version: Long,
    val payloadDigest: String,
    val policy: JsonObject,
    val publicationCanonical: JsonObject,
    val publicationSignatureB64: String,
    val legacyPolicy: JsonObject,
    val legacyPolicySignatureB64: String,
    val keyId: String,
    val effectiveAt: Long,
    val expiresAt: Long?,
    val serverFenceEvidenceDigest: String,
)

@Serializable
internal data class PolicyPublicationWire(
    val contract: String,
    val signatureSchemaVersion: Int,
    val operationId: String,
    val revisionId: String,
    val domain: String,
    val version: Long,
    val scope: String,
    val issuedAt: Long,
    val effectiveAt: Long,
    val expiresAt: Long?,
    val payloadDigest: String,
    val minSecurityEpoch: Int,
    val requiredCapabilities: List<String>,
    val targetSnapshotDigest: String,
)

@Serializable
internal data class PolicyApplicationReceiptWire(
    val operationId: String,
    val installationId: String,
    val bootId: String,
    val policyDomain: String,
    val version: Long,
    val digest: String,
    val appliedAt: Long?,
    val enforcedCapabilities: List<String>,
    val refusedCapabilities: List<String>,
    val cleanupOperationIds: List<String>,
    val result: String,
    val nonce: String,
    val deviceSignature: String,
)

@Serializable
internal data class PolicyApplicationReceiptAcknowledgementWire(
    val revisionId: String,
    val domain: String,
    val version: Long,
    val installationId: String,
    val state: String,
    val bootId: String,
    val digest: String,
    val appliedAt: Long?,
    val enforcedCapabilities: List<String>,
    val refusedCapabilities: List<String>,
    val cleanupOperationIds: List<String>,
    val lastEvidenceDigest: String,
)

@Serializable
internal data class OfficePreviewGrantRequestWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("file_sha256") val fileSha256: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
    @SerialName("file_extension") val fileExtension: String,
    @SerialName("content_scope") val contentScope: String = "ORDINARY_CHAT_ATTACHMENT",
)

@Serializable
internal data class OfficePreviewGrantWire(
    @SerialName("schema_version") val schemaVersion: Int,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("grant_id") val grantId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("file_sha256") val fileSha256: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
    @SerialName("file_extension") val fileExtension: String,
    @SerialName("content_scope") val contentScope: String,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("preview_expires_at_epoch_millis") val previewExpiresAtEpochMillis: Long,
)

@Serializable
internal data class AppReleaseRequestWire(
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("package_name") val packageName: String,
)

@Serializable
internal data class AppReleaseWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("apk_url") val apkUrl: String,
    @SerialName("apk_fallback_url") val apkFallbackUrl: String,
    @SerialName("apk_sha256") val apkSha256: String,
    @SerialName("build_id") val buildId: String,
    @SerialName("signing_certificate_sha256") val signingCertificateSha256: String,
    @SerialName("release_notes") val releaseNotes: String,
    val mandatory: Boolean,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
)

@Serializable
internal data class SyncResponseWire(
    @SerialName("server_time_epoch_millis") val serverTimeEpochMillis: Long,
    // Kept as raw JSON so old signed policy envelopes and the R05 revision envelope can coexist
    // on the same backward-compatible full-sync channel.
    val policy: JsonObject? = null,
    @SerialName("device_trust") val deviceTrust: SignedJsonEnvelopeWire,
    val commands: List<SignedJsonEnvelopeWire>,
    @SerialName("access_ticket") val accessTicket: SignedJsonEnvelopeWire? = null,
    val tombstones: SignedJsonEnvelopeWire? = null,
)

@Serializable
internal data class AccountTombstoneRecordWire(
    @SerialName("schema_version") val schemaVersion: Int,
    val sequence: Long,
    @SerialName("previous_hash") val previousHash: String,
    val kind: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    val reason: String,
    @SerialName("destroyed_at") val destroyedAtEpochMillis: Long,
    @SerialName("record_hash") val recordHash: String,
    @SerialName("signature_b64") val signatureBase64: String,
    val generation: Long,
)

@Serializable
internal data class AccountTombstoneChangesWire(
    @SerialName("schema_version") val schemaVersion: Int,
    val purpose: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("from_sequence_exclusive") val fromSequenceExclusive: Long,
    @SerialName("to_sequence_inclusive") val toSequenceInclusive: Long,
    @SerialName("ledger_head_hash") val ledgerHeadHash: String,
    val records: List<AccountTombstoneRecordWire>,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
)

@Serializable
internal data class DeviceAccessTicketWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("ticket_id") val ticketId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("device_public_key_sha256") val devicePublicKeySha256: String,
    @SerialName("attestation_version") val attestationVersion: Long,
    @SerialName("access_policy_version") val accessPolicyVersion: Long,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("heartbeat_interval_millis") val heartbeatIntervalMillis: Long,
    @SerialName("heartbeat_grace_millis") val heartbeatGraceMillis: Long,
)

@Serializable
internal data class DeviceHeartbeatPulseWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("ticket_id") val ticketId: String,
)

@Serializable
internal data class DeviceHeartbeatReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("ticket_id") val ticketId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("access_state") val accessState: String,
    @SerialName("server_time_epoch_millis") val serverTimeEpochMillis: Long,
    @SerialName("next_heartbeat_at_epoch_millis") val nextHeartbeatAtEpochMillis: Long,
    @SerialName("heartbeat_grace_expires_at_epoch_millis") val heartbeatGraceExpiresAtEpochMillis: Long,
    @SerialName("ticket_expires_at_epoch_millis") val ticketExpiresAtEpochMillis: Long,
    @SerialName("management_policy_version") val managementPolicyVersion: Long? = null,
)

@Serializable
internal data class PolicyWire(
    @SerialName("policy_id") val policyId: String,
    val version: Long,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("effective_at_epoch_millis") val effectiveAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long?,
    @SerialName("content_retention_millis") val contentRetentionMillis: Long,
    @SerialName("maximum_offline_millis") val maximumOfflineMillis: Long,
    @SerialName("offline_access_mode") val offlineAccessMode: String,
    @SerialName("notification_exposure") val notificationExposure: String,
    @SerialName("hardware_trust_requirement") val hardwareTrustRequirement: String,
    @SerialName("allow_content_export") val allowContentExport: Boolean,
    @SerialName("allow_clipboard") val allowClipboard: Boolean,
    @SerialName("allow_external_share") val allowExternalShare: Boolean,
    @SerialName("allow_group_read_receipts") val allowGroupReadReceipts: Boolean = false,
    @SerialName("screen_capture_policy") val screenCapturePolicy: String,
    @SerialName("require_content_envelope") val requireContentEnvelope: Boolean,
    @SerialName("allow_rtc") val allowRtc: Boolean,
    @SerialName("schema_version") val schemaVersion: Int,
)

@Serializable
internal data class TrustDecisionWire(
    @SerialName("installation_id") val installationId: String,
    @SerialName("attestation_version") val attestationVersion: Long,
    @SerialName("trust_state") val trustState: String,
    @SerialName("location_evidence_state") val locationEvidenceState: String,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("managed_license_id") val managedLicenseId: String? = null,
    @SerialName("managed_license_state") val managedLicenseState: String? = null,
    @SerialName("managed_license_revision") val managedLicenseRevision: Long? = null,
    @SerialName("managed_license_device_public_key_sha256") val managedLicenseDevicePublicKeySha256: String? = null,
    @SerialName("managed_license_content_share_key_id") val managedLicenseContentShareKeyId: String? = null,
    @SerialName("managed_license_app_package") val managedLicenseAppPackage: String? = null,
    @SerialName("managed_license_app_version") val managedLicenseAppVersion: Long? = null,
    @SerialName("managed_license_issued_at_epoch_millis") val managedLicenseIssuedAtEpochMillis: Long? = null,
    @SerialName("managed_license_expires_at_epoch_millis") val managedLicenseExpiresAtEpochMillis: Long? = null,
    @SerialName("managed_license_scopes") val managedLicenseScopes: List<String> = emptyList(),
    @SerialName("managed_license_account_hashes") val managedLicenseAccountHashes: List<String> = emptyList(),
    @SerialName("managed_license_offline_allowed") val managedLicenseOfflineAllowed: Boolean? = null,
    @SerialName("managed_license_envelope") val managedLicenseEnvelope: SignedJsonEnvelopeWire? = null,
    @SerialName("account_bindings") val accountBindings: List<MatrixDeviceBindingWire> = emptyList(),
    @SerialName("offline_entry") val offlineEntry: OfflineEntryGrantWire? = null,
    @SerialName("local_list_entry") val localListEntry: OfflineEntryGrantWire? = null,
    @SerialName("automatic_license_error_code") val automaticLicenseErrorCode: String? = null,
)

@Serializable
internal data class OfflineEntryGrantWire(
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("device_public_key_sha256") val devicePublicKeySha256: String,
    @SerialName("content_share_key_id") val contentShareKeyId: String,
    @SerialName("account_bindings") val accountBindings: List<OfflineEntryAccountBindingWire>,
    @SerialName("allowed_scopes") val allowedScopes: List<String>,
    @SerialName("protocol_version") val protocolVersion: Int = 0,
    @SerialName("entry_policy_version") val entryPolicyVersion: Long = 0,
)

@Serializable
internal data class OfflineEntryAccountBindingWire(
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String,
    @SerialName("account_mobile_generation") val accountMobileGeneration: Long,
    @SerialName("activation_id") val activationId: String,
)

@Serializable
internal data class ManagedDeviceLicenseWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("license_id") val licenseId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("device_public_key_sha256") val devicePublicKeySha256: String,
    @SerialName("content_share_key_id") val contentShareKeyId: String,
    @SerialName("app_package") val appPackage: String,
    @SerialName("app_version") val legacyAppVersion: Long? = null,
    @SerialName("app_version_at_issue") val appVersionAtIssue: Long? = null,
    @SerialName("minimum_app_version") val minimumAppVersion: Long? = null,
    @SerialName("app_signing_certificate_sha256") val appSigningCertificateSha256: List<String> = emptyList(),
    @SerialName("security_protocol_epoch_min") val securityProtocolEpochMin: Int? = null,
    @SerialName("security_protocol_epoch_max") val securityProtocolEpochMax: Int? = null,
    @SerialName("license_revision") val licenseRevision: Long,
    @SerialName("assurance_class") val assuranceClass: String,
    @SerialName("allowed_scopes") val allowedScopes: List<String>,
    @SerialName("account_id_hashes") val legacyAccountIdHashes: List<String> = emptyList(),
    @SerialName("offline_allowed") val offlineAllowed: Boolean,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
)

@Serializable
internal data class CommandWire(
    @SerialName("command_id") val commandId: String,
    @SerialName("supersedes_command_id") val supersedesCommandId: String? = null,
    @SerialName("installation_id") val installationId: String,
    @SerialName("command_type") val commandType: String,
    @SerialName("account_id_hash") val accountIdHash: String?,
    @SerialName("content_id") val contentId: String?,
    val version: Long,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("not_before_epoch_millis") val notBeforeEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("schema_version") val schemaVersion: Int,
)

@Serializable
internal data class CommandReportWire(
    @SerialName("command_id") val commandId: String,
    val state: String,
    val progress: Int,
    @SerialName("error_code") val errorCode: String?,
)

@Serializable
internal data class CreateOnboardingInvitationWire(
    @SerialName("inviter_user_id") val inviterUserId: String,
    @SerialName("generation_request_id") val generationRequestId: String? = null,
)

@Serializable
internal data class OnboardingInvitationWire(
    @SerialName("public_id") val publicId: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("inviter_user_id") val inviterUserId: String,
    @SerialName("inviter_display_name") val inviterDisplayName: String,
    val status: String,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("invitation_url") val invitationUrl: String,
)

@Serializable
internal data class VerifyOnboardingInvitationWire(
    @SerialName("public_id") val publicId: String? = null,
    val code: String? = null,
    @SerialName("installation_id") val installationId: String,
)

@Serializable
internal data class VerifiedOnboardingInvitationWire(
    @SerialName("activation_session") val activationSession: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("inviter_user_id") val inviterUserId: String,
    @SerialName("inviter_display_name") val inviterDisplayName: String,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
)

@Serializable
internal data class OnboardingUsernameRequestWire(
    @SerialName("activation_session") val activationSession: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("preferred_stem") val preferredStem: String,
    @SerialName("installation_id") val installationId: String,
)

@Serializable
internal data class OnboardingUsernameSuggestionWire(
    @SerialName("display_name") val displayName: String,
    @SerialName("login_account") val loginAccount: String,
    @SerialName("matrix_user_id") val matrixUserId: String,
)

@Serializable
internal data class ActivateOnboardingInvitationWire(
    @SerialName("activation_session") val activationSession: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("preferred_stem") val preferredStem: String,
    @SerialName("installation_id") val installationId: String,
    val password: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
internal data class OnboardingFriendshipWire(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("matrix_room_id") val matrixRoomId: String,
)

@Serializable
internal data class OnboardingActivationWire(
    @SerialName("login_account") val loginAccount: String,
    @SerialName("matrix_user_id") val matrixUserId: String,
    @SerialName("display_name") val displayName: String,
    val friendship: OnboardingFriendshipWire,
)

@Serializable
internal data class RevokeOnboardingInvitationWire(
    @SerialName("inviter_user_id") val inviterUserId: String,
)

@Serializable
internal data class AccountReactivationPrepareWire(
    @SerialName("protocol_version") val protocolVersion: Int = 2,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String,
    @SerialName("client_scope_id") val clientScopeId: String,
    @SerialName("client_generation") val clientGeneration: Long,
    @SerialName("scope_started_at_epoch_millis") val scopeStartedAtEpochMillis: Long,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("explicit_identity_reset_confirmed") val explicitIdentityResetConfirmed: Boolean = false,
)

@Serializable
internal data class AccountReactivationCommitWire(
    @SerialName("protocol_version") val protocolVersion: Int = 2,
    @SerialName("activation_id") val activationId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
)

@Serializable
internal data class AccountReactivationResponseWire(
    @SerialName("activation_id") val activationId: String,
    val state: String,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    val receipt: JsonObject? = null,
    @SerialName("key_id") val keyId: String? = null,
    @SerialName("signature_b64") val signatureBase64: String? = null,
)

@Serializable
internal data class AccountActivationReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    val purpose: String,
    @SerialName("activation_id") val activationId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String,
    @SerialName("client_scope_id") val clientScopeId: String,
    @SerialName("client_generation") val clientGeneration: Long,
    @SerialName("attestation_version") val attestationVersion: Long,
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("matrix_cross_signing_key_hash") val matrixCrossSigningKeyHash: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("identity_continuity") val identityContinuity: String = "FIRST_ACTIVATION",
    @SerialName("account_mobile_generation") val accountMobileGeneration: Long? = null,
    val state: String,
    @SerialName("committed_at_epoch_millis") val committedAtEpochMillis: Long,
)

@Serializable
internal data class AccountDeletionPrepareWire(
    @SerialName("protocol_version") val protocolVersion: Int = 2,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("confirmation_token_sha256") val confirmationTokenSha256: String,
    @SerialName("erase_data") val eraseData: Boolean,
)

@Serializable
internal data class AccountDeletionPendingWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("account_id_hash") val accountIdHash: String,
)

@Serializable
internal data class AccountDeletionRecoverWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("operation_id") val operationId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("confirmation_token_sha256") val confirmationTokenSha256: String,
    @SerialName("erase_data") val eraseData: Boolean,
)

@Serializable
internal data class AccountDeletionCommitWire(
    @SerialName("protocol_version") val protocolVersion: Int = 2,
    @SerialName("deletion_intent_id") val deletionIntentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("confirmation_token") val confirmationToken: String,
)

@Serializable
internal data class AccountDeletionResponseWire(
    val state: String,
    @SerialName("account_state") val accountState: String? = null,
    val irreversible: Boolean? = null,
    val receipt: JsonObject,
    @SerialName("key_id") val keyId: String,
    @SerialName("signature_b64") val signatureBase64: String,
)

@Serializable
internal data class AccountDeletionPrepareReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("contract_revision") val contractRevision: String,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("deletion_intent_id") val deletionIntentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("prepared_at_epoch_millis") val preparedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("erase_data") val eraseData: Boolean,
    @SerialName("freeze_at_epoch_millis") val freezeAtEpochMillis: Long,
)

@Serializable
internal data class AccountDeletionPendingReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("contract_revision") val contractRevision: String,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("deletion_intent_id") val deletionIntentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("freeze_at_epoch_millis") val freezeAtEpochMillis: Long,
    @SerialName("erase_data") val eraseData: Boolean,
)

@Serializable
internal data class AccountDeletionRecoveryReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("contract_revision") val contractRevision: String,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("deletion_intent_id") val deletionIntentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("freeze_at_epoch_millis") val freezeAtEpochMillis: Long,
    @SerialName("recovered_at_epoch_millis") val recoveredAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @SerialName("erase_data") val eraseData: Boolean,
)

@Serializable
internal data class AccountDeletionCommitReceiptWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("contract_revision") val contractRevision: String,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("deletion_intent_id") val deletionIntentId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("global_destroy_job_id") val globalDestroyJobId: String,
    val state: String,
    @SerialName("erase_data") val eraseData: Boolean,
    @SerialName("committed_at_epoch_millis") val committedAtEpochMillis: Long,
)

@Serializable
internal data class SecurityErasureMilestoneWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("contract_revision") val contractRevision: String,
    val purpose: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    val generation: Long,
    @SerialName("policy_version") val policyVersion: String,
    @SerialName("completed_at_ms") val completedAtEpochMillis: Long,
    @SerialName("postcondition_hash") val postconditionHash: String,
)

@Serializable
internal data class SecurityErasureEnvelopeWire(
    val milestone: JsonObject,
    @SerialName("key_id") val keyId: String,
    @SerialName("signature_b64") val signatureBase64: String,
)

@Serializable
internal data class DeletionScopedClientCapabilityWire(
    val token: String,
    @SerialName("expiresAt") val expiresAtEpochMillis: Long,
    @SerialName("resourceScopeHash") val resourceScopeHash: String,
    val capability: String,
)

@Serializable
internal data class AccountDeletionStatusWire(
    @SerialName("operationId") val operationId: String,
    @SerialName("accountState") val accountState: String,
    @SerialName("deletionState") val deletionState: String,
    @SerialName("securityErasureComplete") val securityErasureComplete: Boolean,
    @SerialName("securityErasureReceipt") val securityErasureReceipt: SecurityErasureEnvelopeWire? = null,
    @SerialName("policyVersion") val policyVersion: String,
    val generation: Long,
    @SerialName("requiredResults") val requiredResults: List<JsonObject>,
    @SerialName("unresolvedContradictions") val unresolvedContradictions: List<String>,
    val retryable: Boolean,
    @SerialName("finalReceipt") val finalReceipt: JsonObject? = null,
    @SerialName("clientReceiptCapability") val clientReceiptCapability: DeletionScopedClientCapabilityWire? = null,
)

@Serializable
internal data class AccountDeletionClientReceiptWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("operation_id") val operationId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("resource_scope_hash") val resourceScopeHash: String,
    @SerialName("manifest_digest_sha256") val manifestDigestSha256: String,
    @SerialName("attestation_evidence_hash") val attestationEvidenceHash: String,
    @SerialName("occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @SerialName("client_signature_b64") val clientSignatureBase64: String,
)

@Serializable
internal data class AccountDeletionClientReceiptResponseWire(
    val accepted: Boolean,
    @SerialName("idempotentReplay") val idempotentReplay: Boolean,
    val status: AccountDeletionStatusWire,
)

@Serializable
internal data class PersistedManagementState(
    @SerialName("highest_policy_version") val highestPolicyVersion: Long = 0,
    @SerialName("highest_command_version") val highestCommandVersion: Long = 0,
    @SerialName("highest_release_version") val highestReleaseVersion: Long = 0,
    @SerialName("trusted_time_epoch_millis") val trustedTimeEpochMillis: Long = 0,
    val policies: List<SignedJsonEnvelopeWire> = emptyList(),
    val commands: List<PersistedCommandWire> = emptyList(),
    @SerialName("latest_release") val latestRelease: SignedJsonEnvelopeWire? = null,
    @SerialName("activation_receipts") val activationReceipts: List<SignedJsonEnvelopeWire> = emptyList(),
    @SerialName("highest_tombstone_sequence") val highestTombstoneSequence: Long = 0,
    @SerialName("latest_tombstone_batch") val latestTombstoneBatch: SignedJsonEnvelopeWire? = null,
    @SerialName("tombstone_batches") val tombstoneBatches: List<SignedJsonEnvelopeWire> = emptyList(),
    @SerialName("policy_application_history")
    val policyApplicationHistory: List<PersistedPolicyApplicationWire> = emptyList(),
    @SerialName("latest_policy_application") val latestPolicyApplication: PersistedPolicyApplicationWire? = null,
)

@Serializable
internal data class OfflineEntryAnchorWire(
    val decision: SignedJsonEnvelopeWire,
    @SerialName("boot_count") val bootCount: Int,
    @SerialName("elapsed_realtime_millis") val elapsedRealtimeMillis: Long,
)

@Serializable
internal data class PersistedPolicyApplicationWire(
    val publication: SignedPolicyPublicationWire,
    val state: String,
    @SerialName("enforced_capabilities") val enforcedCapabilities: List<String> = emptyList(),
    @SerialName("refused_capabilities") val refusedCapabilities: List<String> = emptyList(),
    @SerialName("cleanup_operation_ids") val cleanupOperationIds: List<String> = emptyList(),
    @SerialName("cleanup_operations") val cleanupOperations: List<PersistedPolicyCleanupOperationWire> = emptyList(),
    @SerialName("apply_handshake_at_epoch_millis") val applyHandshakeAtEpochMillis: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("pending_receipt") val pendingReceipt: PolicyApplicationReceiptWire? = null,
    @SerialName("delivered_receipt") val deliveredReceipt: PolicyApplicationReceiptWire? = null,
    @SerialName("delivered_receipt_identities")
    val deliveredReceiptIdentities: List<DeliveredPolicyReceiptIdentityWire> = emptyList(),
)

@Serializable
internal data class PersistedPolicyCleanupOperationWire(
    @SerialName("operation_id") val operationId: String,
    val kind: String,
    val state: String,
)

@Serializable
internal data class DeliveredPolicyReceiptIdentityWire(
    val nonce: String,
    @SerialName("evidence_digest") val evidenceDigest: String,
)

@Serializable
internal data class PersistedCommandWire(
    val envelope: SignedJsonEnvelopeWire,
    val state: String = "RECEIVED",
    val progress: Int = 0,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
internal data class LifecycleSubjectHashesWire(
    @SerialName("account_id_hash") val accountIdHash: String? = null,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String? = null,
    @SerialName("room_id_hash") val roomIdHash: String? = null,
    @SerialName("event_id_hash") val eventIdHash: String? = null,
)

@Serializable
internal data class LifecycleOperationStartWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("operation_id") val operationId: String,
    @SerialName("lifecycle_id") val lifecycleId: String,
    val trigger: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("account_id_hash") val accountIdHash: String? = null,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String? = null,
    @SerialName("room_id_hash") val roomIdHash: String? = null,
    @SerialName("event_id_hash") val eventIdHash: String? = null,
    @SerialName("deadline_epoch_millis") val deadlineEpochMillis: Long,
    @SerialName("media_cleanup_protocol") val mediaCleanupProtocol: String? = null,
)

@Serializable
internal data class LifecycleOperationResumeWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("operation_id") val operationId: String,
)

@Serializable
internal data class LifecycleClientReceiptWire(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("operation_id") val operationId: String,
    val component: String,
    val phase: String,
    @SerialName("result_code") val resultCode: String,
    @SerialName("receipt_reference") val receiptReference: String,
    @SerialName("occurred_at_ms") val occurredAtEpochMillis: Long,
)

@Serializable
internal data class LifecycleStepWire(
    @SerialName("step_id") val stepId: String,
    val component: String,
    val required: Boolean,
    val state: String,
    @SerialName("attempt_count") val attemptCount: Int,
    @SerialName("authority_revision") val authorityRevision: String? = null,
    @SerialName("result_code") val resultCode: String? = null,
    @SerialName("result_reference") val resultReference: String? = null,
    @SerialName("receipt_reference") val receiptReference: String? = null,
    @SerialName("updated_at_ms") val updatedAtEpochMillis: Long,
)

@Serializable
internal data class LifecycleOperationWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("catalog_id") val catalogId: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("lifecycle_id") val lifecycleId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val subjects: LifecycleSubjectHashesWire,
    val state: String,
    @SerialName("current_phase") val currentPhase: String,
    @SerialName("authority_revisions") val authorityRevisions: Map<String, String>,
    val steps: List<LifecycleStepWire>,
    @SerialName("deadline_ms") val deadlineEpochMillis: Long,
    @SerialName("next_reconcile_at_ms") val nextReconcileAtEpochMillis: Long? = null,
    @SerialName("compensation_state") val compensationState: String,
    @SerialName("terminal_receipt_id") val terminalReceiptId: String? = null,
    @SerialName("created_at_ms") val createdAtEpochMillis: Long,
    @SerialName("updated_at_ms") val updatedAtEpochMillis: Long,
)

@Serializable
internal data class ComponentOperationalStateWire(
    val state: String,
    val authority: String,
    @SerialName("authority_revision") val authorityRevision: String,
    @SerialName("observed_at_ms") val observedAtEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("receipt_reference") val receiptReference: String? = null,
)

@Serializable
internal data class AppOperationalSnapshotWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("catalog_id") val catalogId: String,
    @SerialName("account_id_hash") val accountIdHash: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("matrix_device_id_hash") val matrixDeviceIdHash: String,
    @SerialName("issued_at_ms") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("overall_state") val overallState: String,
    val components: Map<String, ComponentOperationalStateWire>,
    @SerialName("active_operations") val activeOperationIds: List<String>,
    val blockers: List<String>,
    @SerialName("signing_key_id") val signingKeyId: String,
    @SerialName("signature_b64") val signatureBase64: String,
)
