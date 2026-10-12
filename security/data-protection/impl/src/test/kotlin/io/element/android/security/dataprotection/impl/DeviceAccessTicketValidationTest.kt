/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ManagementHeartbeatStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class DeviceAccessTicketValidationTest {
    @Test
    fun `sync protocol v2 binds the current attestation version into the signed body`() {
        val json = Json {
            explicitNulls = true
            encodeDefaults = true
        }
        val encoded = json.encodeToString(
            HeartbeatWire(
                appVersionCode = 1,
                attestationVersion = ATTESTATION_VERSION,
                deviceCorrelationIdSha256 = "a".repeat(64),
                accountIdHashes = emptyList(),
                matrixDeviceBindings = emptyList(),
                matrixIdentityState = "UNKNOWN",
                kmsState = "PINNED_AND_SIGNED",
                attestationState = "TRUSTED",
                policyVersion = null,
            )
        )
        val payload = json.parseToJsonElement(encoded).jsonObject

        assertThat(payload.getValue("access_session_protocol_version").jsonPrimitive.long).isEqualTo(2)
        assertThat(payload.getValue("attestation_version").jsonPrimitive.long).isEqualTo(ATTESTATION_VERSION)
        assertThat(payload.getValue("device_correlation_id_sha256").jsonPrimitive.content).isEqualTo("a".repeat(64))
    }

    @Test
    fun `active heartbeat preserves the signed ticket schedule`() {
        val report = HttpManagementControlService.Validators.validateHeartbeatReceipt(
            ticket = ticket(),
            receipt = activeReceipt(),
            expectedInstallationId = INSTALLATION_ID,
        )

        assertThat(report.status).isEqualTo(ManagementHeartbeatStatus.ACTIVE)
        assertThat(report.nextHeartbeatDelayMillis).isEqualTo(HEARTBEAT_INTERVAL_MILLIS)
        assertThat(report.serverPolicyVersion).isEqualTo(MANAGEMENT_POLICY_VERSION)
    }

    @Test
    fun `newer signed management policy requests full refresh and rollback is rejected`() {
        val report = HttpManagementControlService.Validators.validateHeartbeatReceipt(
            ticket = ticket(),
            receipt = activeReceipt(),
            expectedInstallationId = INSTALLATION_ID,
        )

        assertThat(report.withPolicyRefreshStatus(MANAGEMENT_POLICY_VERSION - 1).status)
            .isEqualTo(ManagementHeartbeatStatus.POLICY_REFRESH_REQUIRED)
        assertThat(report.withPolicyRefreshStatus(MANAGEMENT_POLICY_VERSION).status)
            .isEqualTo(ManagementHeartbeatStatus.ACTIVE)
        val rollback = runCatching {
            report.withPolicyRefreshStatus(MANAGEMENT_POLICY_VERSION + 1)
        }.exceptionOrNull()
        assertThat(rollback).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `active heartbeat cannot silently stretch the signed interval`() {
        val failure = runCatching {
            HttpManagementControlService.Validators.validateHeartbeatReceipt(
                ticket = ticket(),
                receipt = activeReceipt().copy(
                    nextHeartbeatAtEpochMillis = NOW + 2 * HEARTBEAT_INTERVAL_MILLIS,
                    heartbeatGraceExpiresAtEpochMillis = NOW + 2 * HEARTBEAT_INTERVAL_MILLIS + HEARTBEAT_GRACE_MILLIS,
                ),
                expectedInstallationId = INSTALLATION_ID,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `heartbeat receipt is bound to the current installation and ticket`() {
        val wrongInstallation = runCatching {
            HttpManagementControlService.Validators.validateHeartbeatReceipt(
                ticket = ticket(),
                receipt = activeReceipt().copy(installationId = "another-installation"),
                expectedInstallationId = INSTALLATION_ID,
            )
        }.exceptionOrNull()
        val wrongTicket = runCatching {
            HttpManagementControlService.Validators.validateHeartbeatReceipt(
                ticket = ticket(),
                receipt = activeReceipt().copy(ticketId = "another-ticket-id-0000000000"),
                expectedInstallationId = INSTALLATION_ID,
            )
        }.exceptionOrNull()

        assertThat(wrongInstallation).isInstanceOf(IllegalStateException::class.java)
        assertThat(wrongTicket).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `reauth receipt can use the current policy schedule but remains bounded`() {
        val report = HttpManagementControlService.Validators.validateHeartbeatReceipt(
            ticket = ticket(),
            receipt = activeReceipt().copy(
                accessState = "REAUTH_REQUIRED",
                nextHeartbeatAtEpochMillis = NOW + 1_000L,
                heartbeatGraceExpiresAtEpochMillis = NOW + 61_000L,
                ticketExpiresAtEpochMillis = NOW,
            ),
            expectedInstallationId = INSTALLATION_ID,
        )

        assertThat(report.status).isEqualTo(ManagementHeartbeatStatus.REAUTH_REQUIRED)
        assertThat(report.nextHeartbeatDelayMillis).isEqualTo(30_000L)
        assertThat(report.withPolicyRefreshStatus(MANAGEMENT_POLICY_VERSION - 1).status)
            .isEqualTo(ManagementHeartbeatStatus.REAUTH_REQUIRED)
    }

    @Test
    fun `newer policy never downgrades a signed revoked access state`() {
        val report = HttpManagementControlService.Validators.validateHeartbeatReceipt(
            ticket = ticket(),
            receipt = activeReceipt().copy(
                accessState = "REVOKED",
                nextHeartbeatAtEpochMillis = NOW + 1_000L,
                heartbeatGraceExpiresAtEpochMillis = NOW + 61_000L,
                ticketExpiresAtEpochMillis = NOW,
            ),
            expectedInstallationId = INSTALLATION_ID,
        )

        assertThat(report.withPolicyRefreshStatus(MANAGEMENT_POLICY_VERSION - 1).status)
            .isEqualTo(ManagementHeartbeatStatus.REVOKED)
    }

    @Test
    fun `ticket is bound to installation attestation key and positive policy version`() {
        val accepted = HttpManagementControlService.Validators.validateAccessTicket(
            ticket = ticket(),
            serverTimeEpochMillis = NOW,
            expectedInstallationId = INSTALLATION_ID,
            expectedAttestationVersion = ATTESTATION_VERSION,
            expectedDevicePublicKeySha256 = DEVICE_PUBLIC_KEY_SHA256,
        )
        val wrongKey = runCatching {
            HttpManagementControlService.Validators.validateAccessTicket(
                ticket = ticket(),
                serverTimeEpochMillis = NOW,
                expectedInstallationId = INSTALLATION_ID,
                expectedAttestationVersion = ATTESTATION_VERSION,
                expectedDevicePublicKeySha256 = "b".repeat(64),
            )
        }.exceptionOrNull()
        val zeroPolicy = runCatching {
            HttpManagementControlService.Validators.validateAccessTicket(
                ticket = ticket().copy(accessPolicyVersion = 0),
                serverTimeEpochMillis = NOW,
                expectedInstallationId = INSTALLATION_ID,
                expectedAttestationVersion = ATTESTATION_VERSION,
                expectedDevicePublicKeySha256 = DEVICE_PUBLIC_KEY_SHA256,
            )
        }.exceptionOrNull()

        assertThat(accepted.ticketId).isEqualTo(TICKET_ID)
        assertThat(wrongKey).isInstanceOf(IllegalStateException::class.java)
        assertThat(zeroPolicy).isInstanceOf(IllegalStateException::class.java)
    }

    private fun ticket() = DeviceAccessTicketWire(
        schemaVersion = 1,
        ticketId = TICKET_ID,
        installationId = INSTALLATION_ID,
        devicePublicKeySha256 = DEVICE_PUBLIC_KEY_SHA256,
        attestationVersion = ATTESTATION_VERSION,
        accessPolicyVersion = 7,
        issuedAtEpochMillis = NOW - 1_000L,
        expiresAtEpochMillis = TICKET_EXPIRES_AT,
        heartbeatIntervalMillis = HEARTBEAT_INTERVAL_MILLIS,
        heartbeatGraceMillis = HEARTBEAT_GRACE_MILLIS,
    )

    private fun activeReceipt() = DeviceHeartbeatReceiptWire(
        schemaVersion = 1,
        ticketId = TICKET_ID,
        installationId = INSTALLATION_ID,
        accessState = "ACTIVE",
        serverTimeEpochMillis = NOW,
        nextHeartbeatAtEpochMillis = NOW + HEARTBEAT_INTERVAL_MILLIS,
        heartbeatGraceExpiresAtEpochMillis = NOW + HEARTBEAT_INTERVAL_MILLIS + HEARTBEAT_GRACE_MILLIS,
        ticketExpiresAtEpochMillis = TICKET_EXPIRES_AT,
        managementPolicyVersion = MANAGEMENT_POLICY_VERSION,
    )

    private companion object {
        const val NOW = 2_000_000L
        const val TICKET_EXPIRES_AT = NOW + 59 * 60_000L
        const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val HEARTBEAT_GRACE_MILLIS = 120_000L
        const val ATTESTATION_VERSION = 9L
        const val MANAGEMENT_POLICY_VERSION = 14L
        const val INSTALLATION_ID = "installation-0000000000000001"
        const val TICKET_ID = "ticket-000000000000000000000001"
        val DEVICE_PUBLIC_KEY_SHA256 = "a".repeat(64)
    }
}
