/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.PolicyApplicationEvidence
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import io.element.android.security.dataprotection.api.toStartupSecurityReadiness
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.mockk.EqMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class HttpManagementControlServicePolicyReceiptTest {
    @Test
    fun `only explicit pending recheck obtains a fresh challenge for the same identity`() = runTest {
        withFixture { f ->
            f.probeEnrollment(DeviceTrustState.PENDING)
            f.service.ensureDeviceEnrollment()
            assertThat(f.requests).isEmpty()
            repeat(2) {
                val result = runCatching { f.service.recheckPendingDeviceEnrollment() }
                assertThat(result.exceptionOrNull()?.message).isEqualTo("QA_CHALLENGE_PROBE")
            }
            assertThat(f.enrollmentPaths).containsExactly(
                "/v1/device/challenge/$INSTALLATION_ID",
                "/v1/device/challenge/$INSTALLATION_ID",
            ).inOrder()
            assertThat(f.enrollmentChallenges.map { it.decodeToString() }).containsExactly("fresh-1", "fresh-2").inOrder()
            assertThat(f.currentTrustState()).isEqualTo(DeviceTrustState.PENDING)
        }
    }

    @Test
    fun `pending retry does not force enrollment after concurrent admission at the same version`() = runTest {
        withFixture { f ->
            f.probeEnrollment(DeviceTrustState.PENDING)
            f.admitOnNextEnrollmentLockRead()
            f.service.recheckPendingDeviceEnrollment()
            assertThat(f.enrollmentPaths).isEmpty()
            assertThat(f.enrollmentChallenges).isEmpty()
            assertThat(f.currentTrustState()).isEqualTo(DeviceTrustState.TRUSTED)
        }
    }

    @Test
    fun `explicit retry does not re-enroll an admitted or security restricted device`() = runTest {
        for (
            state in listOf(
                DeviceTrustState.TRUSTED,
                DeviceTrustState.MANAGED,
                DeviceTrustState.REVOKED,
                DeviceTrustState.LOST,
                DeviceTrustState.INTEGRITY_WARNING,
            )
        ) {
            withFixture { f ->
                f.probeEnrollment(state)
                val result = runCatching { f.service.recheckPendingDeviceEnrollment() }
                if (state in setOf(DeviceTrustState.TRUSTED, DeviceTrustState.MANAGED)) {
                    assertThat(result.isSuccess).isTrue()
                } else {
                    assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
                }
                assertThat(f.requests).isEmpty()
                assertThat(f.enrollmentChallenges).isEmpty()
                assertThat(f.currentTrustState()).isEqualTo(state)
            }
        }
    }

    @Test
    fun `verified global child reports original device signed facts without ambient heartbeat or a new root`() = runTest {
        for (ambientHeartbeat in listOf(false, true)) {
            withFixture { f ->
                val command = f.acceptReportCommand()
                f.serveCommandReports()
                val pending = io.element.android.security.dataprotection.api.ManagementCommandExecutionReport(
                    command.commandId,
                    command.type,
                    io.element.android.security.dataprotection.api.ManagementCommandState.WAITING_RESTART,
                    70,
                )
                suspend fun send(): Boolean = f.service.reportCommand(pending)
                val accepted = if (ambientHeartbeat) {
                    withContext(LifecycleOperationContext("unrelated-heartbeat-0001", FullAppLifecycleId.LC_07)) { send() }
                } else {
                    send()
                }
                assertThat(accepted).isTrue()
                assertThat(f.repository.pendingCommands().single().commandId).isEqualTo(command.commandId)
                assertThat(f.service.reportCommand(pending.copy(
                    state = io.element.android.security.dataprotection.api.ManagementCommandState.COMPLETE,
                    progress = 100,
                ))).isTrue()
                assertThat(f.repository.pendingCommands()).isEmpty()
                assertThat(f.requests.map { it.path }).containsExactly("/v1/device/commands/report", "/v1/device/commands/report")
                f.requests.forEach { request ->
                    f.assertSignedPost(request, null)
                    assertThat(JSON.parseToJsonElement(request.body).jsonObject.getValue("command_id").jsonPrimitive.content)
                        .isEqualTo(command.commandId)
                }
            }
        }
    }

    @Test
    fun `global child rejected or lost report remains pending and retries the same signed command`() = runTest {
        for (failureStatus in listOf(409, 503)) {
            withFixture { f ->
                val command = f.acceptReportCommand()
                val report = io.element.android.security.dataprotection.api.ManagementCommandExecutionReport(
                    command.commandId,
                    command.type,
                    io.element.android.security.dataprotection.api.ManagementCommandState.COMPLETE,
                    100,
                )
                f.serveCommandReports(failureStatus)
                assertThat(f.service.reportCommand(report)).isFalse()
                assertThat(f.repository.pendingCommands().single().commandId).isEqualTo(command.commandId)
                f.serveCommandReports()
                assertThat(f.service.reportCommand(report)).isTrue()
                assertThat(f.requests).hasSize(2)
                assertThat(f.requests[0].body).isEqualTo(f.requests[1].body)
                f.requests.forEach { f.assertSignedPost(it, null) }
            }
        }
    }

    @Test
    fun `ordinary local report still requires its lifecycle and unknown global command never sends`() = runTest {
        withFixture { f ->
            val command = f.acceptReportCommand(global = false)
            val report = io.element.android.security.dataprotection.api.ManagementCommandExecutionReport(
                command.commandId,
                command.type,
                io.element.android.security.dataprotection.api.ManagementCommandState.WAITING_RESTART,
                70,
            )
            for (candidate in listOf(report, report.copy(commandId = "unknown-global-command"))) {
                val failure = runCatching { f.service.reportCommand(candidate) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            }
            val wrongContext = runCatching {
                withContext(LifecycleOperationContext("wrong-heartbeat-0001", FullAppLifecycleId.LC_07)) { f.service.reportCommand(report) }
            }.exceptionOrNull()
            assertThat(wrongContext).isInstanceOf(IllegalStateException::class.java)
            assertThat(f.requests).isEmpty()
            f.serveCommandReports()
            assertThat(withContext(LifecycleOperationContext("original-local-clear-0001", FullAppLifecycleId.LC_22)) {
                f.service.reportCommand(report)
            }).isTrue()
            f.assertSignedPost(f.requests.single(), "original-local-clear-0001")
        }
    }

    @Test
    fun `wrong installation and tampered global parent cannot authorize a report`() = runTest {
        for (fault in listOf("installation", "parent", "signature")) {
            withFixture { f ->
                val command = f.acceptReportCommand(installation = if (fault == "installation") "foreign-installation-0001" else INSTALLATION_ID)
                if (fault != "installation") f.tamperReportCommand(fault)
                val report = io.element.android.security.dataprotection.api.ManagementCommandExecutionReport(
                    command.commandId,
                    command.type,
                    io.element.android.security.dataprotection.api.ManagementCommandState.COMPLETE,
                    100,
                )
                assertThat(runCatching { f.service.reportCommand(report) }.exceptionOrNull()).isNotNull()
                assertThat(f.requests).isEmpty()
            }
        }
    }

    @Test
    fun `fresh client gets signed administrative lock reason without replaying completed command`() = runTest {
        withFixture { f ->
            f.serveSignedRestriction("STALE", "DEVICE_ADMINISTRATIVELY_LOCKED")
            val result = withContext(LifecycleOperationContext("original-locked-sync", FullAppLifecycleId.LC_07)) {
                f.service.synchronize()
            }
            assertThat(result.status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
            assertThat(result.errorCode).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
            assertThat(result.pendingCommandCount).isEqualTo(0)
            val startup = result.toStartupSecurityReadiness(installationId = "test-locked-installation")
            assertThat(startup.state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
            assertThat(startup.reasonCode).isEqualTo("DEVICE_ADMINISTRATIVELY_LOCKED")
            assertThat(f.currentTrustState()).isEqualTo(DeviceTrustState.STALE)
            assertThat(JSON.parseToJsonElement(f.requests.single().body).jsonObject
                .getValue("restriction_reason_protocol_version").jsonPrimitive.content).isEqualTo("2")
            f.assertSignedPost(f.requests.single(), "original-locked-sync")
        }
    }

    @Test
    fun `signed administrative lock cannot contradict trusted state`() = runTest {
        withFixture { f ->
            f.serveSignedRestriction("TRUSTED", "DEVICE_ADMINISTRATIVELY_LOCKED")
            val result = withContext(LifecycleOperationContext("original-contradictory-lock", FullAppLifecycleId.LC_07)) {
                f.service.synchronize()
            }
            assertThat(result.status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
            assertThat(result.errorCode).isEqualTo("CONTROL_SYNC_TRUST_DECISION_REJECTED")
        }
    }

    @Test
    fun `unknown signed restriction is rejected rather than mislabeled as administrative lock`() = runTest {
        withFixture { f ->
            f.serveSignedRestriction("STALE", "UNRECOGNIZED_RESTRICTION")
            val result = withContext(LifecycleOperationContext("original-unknown-restriction", FullAppLifecycleId.LC_07)) {
                f.service.synchronize()
            }
            assertThat(result.status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
            assertThat(result.errorCode).isEqualTo("CONTROL_SYNC_TRUST_DECISION_REJECTED")
        }
    }

    @Test
    fun `restored trust confirms server eligibility in the same lifecycle before returning success`() = runTest {
        withFixture { f ->
            f.beginStaleAccess()
            f.serveAccessRestoration(failConfirmation = false)
            val result = withContext(LifecycleOperationContext("original-unlock-sync", FullAppLifecycleId.LC_07)) {
                f.service.synchronize()
            }
            assertThat(result.errorCode).isNull()
            assertThat(result.status).isEqualTo(ManagementSyncStatus.SYNCHRONIZED)
            assertThat(f.requests).hasSize(2)
            assertThat(f.requests.map { JSON.parseToJsonElement(it.body).jsonObject.getValue("attestation_state").jsonPrimitive.content })
                .containsExactly("STALE", "TRUSTED").inOrder()
            f.requests.forEach { f.assertSignedPost(it, "original-unlock-sync") }
            assertThat(f.currentTrustState()).isEqualTo(DeviceTrustState.TRUSTED)
        }
    }

    @Test
    fun `lost restoration confirmation fails closed and preserves retry rather than declaring chat ready`() = runTest {
        withFixture { f ->
            f.beginStaleAccess()
            f.serveAccessRestoration(failConfirmation = true)
            val result = withContext(LifecycleOperationContext("original-unlock-sync", FullAppLifecycleId.LC_07)) {
                f.service.synchronize()
            }
            assertThat(result.status).isEqualTo(ManagementSyncStatus.RETRY_REQUIRED)
            assertThat(f.requests).hasSize(2)
            assertThat(f.currentTrustState()).isEqualTo(DeviceTrustState.STALE)
        }
    }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `live closure HTTP signs original facts without inheriting start and verifies scoped acknowledgements`() = runTest {
        withFixture { fixture ->
            fixture.mediaResourceDispatcher()
            val reservation = mediaReservation()
            val seal = io.element.android.security.dataprotection.api.LifecycleMediaSeal(
                reservation,
                "f".repeat(64),
                1791551188185,
                io.element.android.security.dataprotection.api.LifecycleMediaOutcome.SUCCEEDED
            )
            val fact = io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup(
                reservation.operationId,
                reservation.subjects,
                "c".repeat(64),
                1791551188184,
                true,
                true,
                true
            )
            withContext(LifecycleOperationContext("unrelated-original-start", FullAppLifecycleId.LC_17)) {
                fixture.service.reserveMediaResources(reservation)
                assertThat(fixture.service.recordMediaRuntimeCleanup(fact)).startsWith("android-media-runtime-cleanup:v1:")
                assertThat(fixture.service.sealMediaResources(seal)).startsWith("android-media-manifest:v1:")
            }
            val expected = listOf(mediaResourceRequest(reservation), mediaRuntimeRequest(fact), mediaSealRequest(seal))
            for (body in expected) {
                val request = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
                assertThat(request.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
                assertThat(request.getHeader("X-YiZhe-Installation")).isEqualTo(INSTALLATION_ID)
                assertThat(request.getHeader("X-YiZhe-Signature")).isNotEmpty()
                assertThat(JSON.parseToJsonElement(request.body.readUtf8())).isEqualTo(body)
            }
            assertThat(fixture.server.requestCount).isEqualTo(3)
        }
    }

    @Test
    fun `live seal server rejection retains phase and status without its response body`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(403).setBody("{\"detail\":\"SECRET_BODY_TOKEN\"}"))
            val seal = io.element.android.security.dataprotection.api.LifecycleMediaSeal(
                mediaReservation(),
                "f".repeat(64),
                1791551188185,
                io.element.android.security.dataprotection.api.LifecycleMediaOutcome.SUCCEEDED
            )
            val failure = runCatching { fixture.service.sealMediaResources(seal) }.exceptionOrNull() as MediaClosureDeliveryFailure
            assertThat(failure.purpose).isEqualTo("MEDIA_RESOURCE_SEAL_ACK")
            assertThat(failure.reasonCode).isEqualTo("SERVER_REJECTED")
            assertThat(failure.httpStatusCode).isEqualTo(403)
            assertThat(failure.message).doesNotContain("SECRET_BODY_TOKEN")
            assertThat(failure.cause).isNull()
        }
    }

    @Test
    fun `live seal rejects a signed replacement operation instead of accepting cleanup success`() = runTest {
        withFixture { fixture ->
            fixture.mediaResourceDispatcher("operation")
            val seal = io.element.android.security.dataprotection.api.LifecycleMediaSeal(
                mediaReservation(),
                "f".repeat(64),
                1791551188185,
                io.element.android.security.dataprotection.api.LifecycleMediaOutcome.SUCCEEDED
            )
            val failure = runCatching { fixture.service.sealMediaResources(seal) }.exceptionOrNull() as MediaClosureDeliveryFailure
            assertThat(failure.purpose).isEqualTo("MEDIA_RESOURCE_SEAL_ACK")
            assertThat(failure.reasonCode).isEqualTo("ACK_VERIFICATION_REJECTED")
            assertThat(failure.httpStatusCode).isNull()
        }
    }

    private fun mediaReservation() = io.element.android.security.dataprotection.api.LifecycleMediaReservation(
        "original-live-stop-operation",
        io.element.android.security.dataprotection.api.LifecycleSubjectHashes(
            installationId = INSTALLATION_ID,
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64),
            roomIdHash = "d".repeat(64)
        ),
        listOf(
            io.element.android.security.dataprotection.api.LifecycleMediaResource(
                io.element.android.security.dataprotection.api.LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME,
                "c".repeat(64)
            )
        )
    )

    @Test
    fun `relay ciphertext retry checks fresh exact signed authority without new operation or key request`() = runTest {
        withFixture { fixture ->
            fixture.relayRetryDispatcher()
            withContext(LifecycleOperationContext("relay-edit-original-0001", FullAppLifecycleId.LC_15)) {
                fixture.service.authorizePreparedRelayRetry(originQuery(), "relay-edit-original-0001", "!relay:test")
            }
            val sent = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(sent.path).isEqualTo("/v1/lifecycle/protected-relay-retry")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
            assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
            val body = JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(body.getValue("operation_id").jsonPrimitive.content).isEqualTo("relay-edit-original-0001")
            assertThat(body.getValue("request_nonce").jsonPrimitive.content.length).isEqualTo(64)
            assertThat(fixture.server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `relay ciphertext retry rejects replay wrong scope purpose expiry denial and signature`() = runTest {
        for (fault in listOf("nonce", "installation", "expired", "future", "duration", "purpose", "denied", "signature")) {
            withFixture { fixture ->
                fixture.relayRetryDispatcher(fault)
                var rejected = false
                withContext(LifecycleOperationContext("relay-edit-original-0001", FullAppLifecycleId.LC_15)) {
                    try {
                        fixture.service.authorizePreparedRelayRetry(originQuery(), "relay-edit-original-0001", "!relay:test")
                    } catch (_: IllegalStateException) {
                        rejected = true
                    }
                }
                org.junit.Assert.assertTrue(fault, rejected)
                assertThat(fixture.server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `relay ciphertext retry cannot inherit another lifecycle or operation`() = runTest {
        withFixture { fixture ->
            for (context in listOf(
                    LifecycleOperationContext("another-operation-0001", FullAppLifecycleId.LC_15),
                LifecycleOperationContext("relay-edit-original-0001", FullAppLifecycleId.LC_13)
                )) {
                var rejected = false
                withContext(context) {
                    try {
                        fixture.service.authorizePreparedRelayRetry(originQuery(), "relay-edit-original-0001", "!relay:test")
                    } catch (_: IllegalArgumentException) {
                        rejected = true
                    }
                }
                assertThat(rejected).isTrue()
            }
            assertThat(fixture.server.requestCount).isEqualTo(0)
        }
    }

    private fun preparationQuery(): io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery {
        val room = "!relay:test"
        val operation = "relay-edit-original-0001"
        return io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery(
            "a".repeat(64),
            "b".repeat(64),
            sha256(room.encodeToByteArray()).toHex(),
            "d".repeat(64),
            operation,
            room,
            java.util.UUID.nameUUIDFromBytes("relay-edit-v1\u0000$room\u0000$operation".encodeToByteArray()).toString(),
        )
    }

    @Test fun `original relay erasure probe retains signed failure or unknown without admission key or Android receipts`() = runTest {
        for (terminal in listOf(false, true)) {
            withFixture { fixture ->
            fixture.relayPreparationDispatcher(terminal = terminal)
            val result = withContext(LifecycleOperationContext(preparationQuery().operationId, FullAppLifecycleId.LC_15)) {
                fixture.service.reconcileRelayPreparation(preparationQuery())
            }
            assertThat(result.status.name).isEqualTo(if (terminal) "FAILED_FINAL" else "RUNNING")
            assertThat(result.compensationStatus.name).isEqualTo(if (terminal) "PENDING" else "NOT_REQUIRED")
            assertThat(result.steps.all { it.status.name == "PENDING" }).isTrue()
            val sent = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(sent.path).isEqualTo("/v1/lifecycle/protected-relay-preparation")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
            assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
            assertThat(fixture.server.requestCount).isEqualTo(1)
        }
        }
    }

    @Test fun `original relay erasure probe rejects signature replay freshness terminal and original scope faults`() = runTest {
        for (fault in listOf(
                "nonce",
                "installation",
                "expired",
                "future",
                "duration",
                "purpose",
                "signature",
            "operation",
                "lifecycle",
                "account",
                "device",
                "room",
                "event",
                "owner",
                "receipt",
                "steps"
            )) {
            withFixture { fixture ->
                fixture.relayPreparationDispatcher(fault)
                var rejected = false
                withContext(LifecycleOperationContext(preparationQuery().operationId, FullAppLifecycleId.LC_15)) {
                    try {
                        fixture.service.reconcileRelayPreparation(preparationQuery())
                    } catch (_: IllegalStateException) {
                        rejected = true
                    } catch (_: IllegalArgumentException) {
                        rejected = true
                    }
                }
                org.junit.Assert.assertTrue(fault, rejected)
                assertThat(fixture.server.requestCount).isEqualTo(1)
            }
        }
    }

    private fun originQuery() = io.element.android.security.dataprotection.api.ProtectedEventOriginQuery(
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        "e".repeat(64),
        "f".repeat(64),
        "origin-content-0001",
        "RELAY_OPERATION",
        "origin-kms-reference-0001",
        4,
    )

    @Test
    fun `signed historical ordinary location recovers original LC17 without a new operation`() = runTest {
        withFixture { fixture ->
            val query = originQuery().copy(contentKind = "LOCATION")
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(legacy = true, kind = "LOCATION", lifecycle = "LC-17")))
            val result = withContext(LifecycleOperationContext("unrelated-receive-operation", FullAppLifecycleId.LC_13)) {
                fixture.service.protectedEventOrigin(query)
            }
            assertThat(result?.operationId).isEqualTo("original-send-operation-0001")
            assertThat(result?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_17)
            val sent = fixture.server.takeRequest()
            assertThat(sent.path).isEqualTo("/v1/lifecycle/protected-event-origin")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
            assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
            assertThat(JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
                .getValue("content_kind").jsonPrimitive.content).isEqualTo("LOCATION")
            assertThat(fixture.server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `signed historical live carriers retain original LC17 without admission`() = runTest {
        for (kind in listOf("LIVE_LOCATION", "LIVE_LOCATION_OPERATION")) {
            withFixture { fixture ->
                val query = originQuery().copy(contentKind = kind)
                fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(legacy = true, kind = kind, lifecycle = "LC-17")))
                val result = withContext(LifecycleOperationContext("unrelated-receive-operation", FullAppLifecycleId.LC_13)) {
                    fixture.service.protectedEventOrigin(query)
                }
                assertThat(result?.operationId).isEqualTo("original-send-operation-0001")
                assertThat(result?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_17)
                val sent = fixture.server.takeRequest()
                assertThat(sent.path).isEqualTo("/v1/lifecycle/protected-event-origin")
                assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
                assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
                assertThat(JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
                    .getValue("content_kind").jsonPrimitive.content).isEqualTo(kind)
                assertThat(fixture.server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `LC17 original location lookup cannot authorize another protected kind`() = runTest {
        for (kind in listOf("TEXT", "POLL", "REACTION", "RELAY_OPERATION")) {
            withFixture { fixture ->
                fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(kind = kind, lifecycle = "LC-17")))
                var rejected = false
                try {
                    fixture.service.protectedEventOrigin(originQuery().copy(contentKind = kind))
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                org.junit.Assert.assertTrue(kind, rejected)
            }
        }
    }

    @Test
    fun `historical location lookup retains signature freshness and exact request checks`() = runTest {
        for (fault in listOf("query", "installation", "expired", "future", "duration", "purpose", "lifecycle", "signature", "resolution")) {
            withFixture { fixture ->
                fixture.server.enqueue(
                    MockResponse().setResponseCode(200).setBody(fixture.originResponse(fault, legacy = true, kind = "LOCATION", lifecycle = "LC-17"))
                )
                var rejected = false
                try {
                    fixture.service.protectedEventOrigin(originQuery().copy(contentKind = "LOCATION"))
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                org.junit.Assert.assertTrue(fault, rejected)
                assertThat(fixture.server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `signed original send lookup cannot inherit or admit a new lifecycle operation`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse()))
            val result = withContext(LifecycleOperationContext("unrelated-receive-operation", FullAppLifecycleId.LC_13)) {
                fixture.service.protectedEventOrigin(originQuery())
            }
            assertThat(result?.operationId).isEqualTo("original-send-operation-0001")
            assertThat(result?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_15)
            val sent = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(sent.path).isEqualTo("/v1/lifecycle/protected-event-origin")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
            assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
            assertThat(fixture.server.requestCount).isEqualTo(1)
            val body = JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(body.getValue("content_id").jsonPrimitive.content).isEqualTo(originQuery().contentId)
            assertThat(body.getValue("generation").jsonPrimitive.content).isEqualTo("4")
        }
    }

    @Test
    fun `signed historical original send retains the original operation without rewriting its scope`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(legacy = true)))
            val result = fixture.service.protectedEventOrigin(originQuery())
            assertThat(result?.operationId).isEqualTo("original-send-operation-0001")
            assertThat(result?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_15)
            assertThat(fixture.server.requestCount).isEqualTo(1)
            assertThat(fixture.server.takeRequest().path).isEqualTo("/v1/lifecycle/protected-event-origin")
        }
    }

    @Test
    fun `signed retired producer is metadata for receive and never a SEND context`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(previous = true)))
            val result = fixture.service.protectedEventOrigin(originQuery())
            assertThat(result?.previousMatrixDeviceIdHash).isEqualTo("9".repeat(64))
            assertThat(result?.operationId).isEqualTo("original-send-operation-0001")
            assertThat(fixture.server.takeRequest().getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
        }
    }

    @Test
    fun `retired producer evidence rejects current session missing identity and unsigned edits`() = runTest {
        for (fault in listOf("same-session", "missing-session", "signature", "query", "expired", "installation")) {
            withFixture { fixture ->
                fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(fault, previous = true)))
                val failure = runCatching { fixture.service.protectedEventOrigin(originQuery()) }.exceptionOrNull()
                org.junit.Assert.assertNotNull(fault, failure)
            }
        }
    }

    @Test
    fun `signed unknown origin returns no context`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(unknown = true)))
            assertThat(fixture.service.protectedEventOrigin(originQuery())).isNull()
        }
    }

    @Test
    fun `signed origin response rejects wrong query installation expiry purpose and lifecycle`() = runTest {
        for (fault in listOf("query", "installation", "expired", "future", "duration", "purpose", "lifecycle", "signature", "resolution")) {
            withFixture { fixture ->
                fixture.server.enqueue(MockResponse().setResponseCode(200).setBody(fixture.originResponse(fault)))
                var rejected = false
                try {
                    fixture.service.protectedEventOrigin(originQuery())
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                org.junit.Assert.assertTrue(fault, rejected)
                assertThat(fixture.server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `new recovery admission does not inherit failed parent operation header`() = runTest {
        withFixture { fixture ->
            fixture.recoverySessions()
            fixture.server.enqueue(MockResponse().setResponseCode(403).setBody("admission test stop"))
            val request = io.element.android.security.dataprotection.api.lifecycleOperationRequest(
                io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_08,
                "recovery_key_entered",
                accountIdHash = sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                matrixDeviceIdHash = sha256("test-device".encodeToByteArray()).toHex()
            )
            withContext(
                io.element.android.security.dataprotection.api.LifecycleOperationContext(
                    "previous-failed-recovery-0001",
                    io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_08
                )
            ) {
                try {
                    fixture.service.startLifecycleOperation(request)
                } catch (_: Exception) {
                    }
            }
            val sent = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(sent.path).isEqualTo("/v1/lifecycle/operations")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
            val body = JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(body.getValue("operation_id").jsonPrimitive.content).isEqualTo(request.operationId)
            assertThat(body.getValue("account_id_hash").jsonPrimitive.content).isEqualTo(request.accountIdHash)
            assertThat(body.getValue("matrix_access_token").jsonPrimitive.content).isEqualTo("dedicated-runtime-token-123456")
        }
    }

    @Test
    fun `managed room request signs original lifecycle and selected friends`() = runTest {
        withFixture { fixture ->
            val operationId = "managed-group-http-operation-0001"
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"operation_id":"$operationId","room_id":"!managed:test.invalid"}"""))
            val room = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_12)) {
                fixture.service.createManagedRoom(io.element.android.security.dataprotection.api.ManagedRoomCreationRequest(
                    creatorUserId = "@creator:test.invalid",
                    name = "Team",
                    topic = null,
                    invitees = listOf("@friend:test.invalid"),
                    profileKind = "CONFIDENTIAL",
                    visibility = "private",
                    joinRule = "invite",
                    parentRoomId = null,
                    roomAliasName = null,
                    avatarUrl = null,
                ))
            }
            assertThat(room).isEqualTo("!managed:test.invalid")
            val sent = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(sent.path).isEqualTo("/v1/message-lifecycle/rooms/create")
            assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isEqualTo(operationId)
            assertThat(sent.getHeader("X-YiZhe-Installation")).isEqualTo(INSTALLATION_ID)
            val body = JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(body.getValue("operation_id").jsonPrimitive.content).isEqualTo(operationId)
            assertThat(body.getValue("profile_kind").jsonPrimitive.content).isEqualTo("CONFIDENTIAL")
            assertThat(body.getValue("invitees").toString()).isEqualTo("[\"@friend:test.invalid\"]")
            assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
        }
    }

    @Test
    fun `managed room response from another operation cannot be accepted`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"operation_id":"another-operation-0001","room_id":"!managed:test.invalid"}""")
            )
            var rejected = false
            withContext(LifecycleOperationContext("managed-group-http-operation-0002", FullAppLifecycleId.LC_12)) {
                try {
                    fixture.service.createManagedRoom(io.element.android.security.dataprotection.api.ManagedRoomCreationRequest(
                        creatorUserId = "@creator:test.invalid",
                        name = "Team",
                        topic = null,
                        invitees = emptyList(),
                        profileKind = "CONFIDENTIAL",
                        visibility = "private",
                        joinRule = "invite",
                        parentRoomId = null,
                        roomAliasName = null,
                        avatarUrl = null,
                    ))
                } catch (_: IllegalStateException) {
                    rejected = true
                }
            }
            assertThat(rejected).isTrue()
        }
    }

    @Test
    fun `expired recovery recheck leaves caller UI thread and signs original owned operation`() = runTest {
        withFixture { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val scope = io.element.android.security.dataprotection.api.RecoveryIntentScope("a".repeat(64), INSTALLATION_ID, "b".repeat(64))
            val owner = io.element.android.security.dataprotection.api.RecoveryIntentRecord(
                scope,
                "expired-recovery-operation-0001",
                "original-intent-0001",
                NOW - 1,
                1
            )
            java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "QA-caller-UI") }
                .asCoroutineDispatcher().use { caller ->
                    withContext(caller) { fixture.service.expireRecoveryOperation(owner) }
                }
            val request = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(request.path).isEqualTo("/v1/lifecycle/operations/${owner.operationId}/expire-recovery")
            assertThat(request.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isEqualTo(owner.operationId)
            val body = JSON.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertThat(body.keys).containsExactly("operation_id", "account_id_hash", "matrix_device_id_hash")
            assertThat(fixture.controlSigningThreads).isNotEmpty()
            assertThat(fixture.controlSigningThreads).doesNotContain("QA-caller-UI")
        }
    }

    @Test
    fun `real service signs receipt posts validates acknowledgements and flushes before and after sync`() = runTest {
        withFixture { fixture ->
            val firstEnvelope = fixture.revisionEnvelope(version = 1)
            val firstApplication = fixture.acceptBlocked(firstEnvelope)
            val firstReceipt = fixture.persistBlockedReceipt(
                application = firstApplication,
                nonce = "pending-before-sync-receipt-0001",
                deviceSignature = "persisted-device-signature-v1",
            )
            val secondEnvelope = fixture.revisionEnvelope(version = 2)
            fixture.serveSuccessfulSync(secondEnvelope)

            val report = withContext(LifecycleOperationContext(SYNC_OPERATION_ID, FullAppLifecycleId.LC_06)) {
                fixture.service.synchronize()
            }

            assertThat(report.errorCode).isEqualTo("POLICY_APPLICATION_BLOCKED_VERSION")
            assertThat(report.status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
            assertThat(report.acceptedPolicyVersion).isEqualTo(2L)
            assertThat(fixture.requests.map(CapturedRequest::path)).containsExactly(
                RECEIPT_PATH,
                SYNC_PATH,
                RECEIPT_PATH,
            ).inOrder()

            val firstPost = fixture.requests[0]
            val syncPost = fixture.requests[1]
            val secondPost = fixture.requests[2]
            assertThat(firstPost.body).isEqualTo(JSON.encodeToString(firstReceipt))
            fixture.assertSignedPost(firstPost, lifecycleOperationId = null)
            fixture.assertSignedPost(syncPost, lifecycleOperationId = SYNC_OPERATION_ID)
            fixture.assertSignedPost(secondPost, lifecycleOperationId = null)

            val secondReceipt = JSON.decodeFromString(PolicyApplicationReceiptWire.serializer(), secondPost.body)
            assertThat(secondReceipt.version).isEqualTo(2L)
            assertThat(secondReceipt.result).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION.name)
            assertThat(secondReceipt.refusedCapabilities).containsExactly("CAPTURE_BLOCK")
            assertThat(secondReceipt.deviceSignature).isEqualTo(
                fixture.deviceSignature(canonicalPolicyApplicationReceiptPayload(secondReceipt, fixture.verifier))
            )

            val latest = fixture.repository.latestPolicyApplication()
            assertThat(latest?.pendingReceipt).isNull()
            assertThat(latest?.deliveredReceipt).isEqualTo(secondReceipt)
            val archivedFirst = fixture.repository.policyApplicationHistory().single()
            assertThat(archivedFirst.pendingReceipt).isNull()
            assertThat(archivedFirst.deliveredReceipt).isEqualTo(firstReceipt)
        }
    }

    @Test
    fun `pending receipt rejected after device signing key rotation remains immutable and blocks sync`() = runTest {
        withFixture(attestationVersion = 2) { fixture ->
            val application = fixture.acceptBlocked(fixture.revisionEnvelope(version = 1))
            val oldKeyReceipt = fixture.persistBlockedReceipt(
                application = application,
                nonce = "pending-before-dak-rotation-0001",
                deviceSignature = "signature-from-retired-device-key",
            )
            fixture.serveRotatedKeyRejection()

            val report = withContext(LifecycleOperationContext(SYNC_OPERATION_ID, FullAppLifecycleId.LC_06)) {
                fixture.service.synchronize()
            }

            assertThat(report.status).isEqualTo(ManagementSyncStatus.SECURITY_REJECTED)
            assertThat(fixture.requests.map(CapturedRequest::path)).containsExactly(RECEIPT_PATH)
            assertThat(fixture.requests.single().body).isEqualTo(JSON.encodeToString(oldKeyReceipt))
            fixture.assertSignedPost(fixture.requests.single(), lifecycleOperationId = null)
            assertThat(fixture.repository.pendingPolicyApplicationReceipt()).isEqualTo(oldKeyReceipt)
            assertThat(fixture.repository.latestPolicyApplication()?.deliveredReceipt).isNull()
            assertThat(fixture.server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `failed atomic delivery commit preserves pending receipt across repository reopen`() = runTest {
        withFixture { fixture ->
            val application = fixture.acceptBlocked(fixture.revisionEnvelope(version = 1))
            val receipt = fixture.persistBlockedReceipt(
                application = application,
                nonce = "pending-before-atomic-failure-0001",
                deviceSignature = "persisted-device-signature-before-atomic-failure",
            )

            fixture.failNextStatePublish()
            assertThrows(IllegalStateException::class.java) {
                fixture.repository.markPolicyApplicationReceiptDelivered(receipt.nonce)
            }

            val reopened = fixture.reopenRepository()
            assertThat(reopened.pendingPolicyApplicationReceipt()).isEqualTo(receipt)
            assertThat(reopened.latestPolicyApplication()?.deliveredReceipt).isNull()
        }
    }

    @Test
    fun `LC08 selects live SDK token instead of persisted credential envelope`() = runTest {
        withFixture { f ->
            f.recoverySessions()
            assertThat(f.service.recoveryMatrixToken(
                sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                sha256("test-device".encodeToByteArray()).toHex()
            )).isEqualTo("dedicated-runtime-token-123456")
        }
    }

    @Test
    fun `LC08 rejects swapped SDK device and encrypted envelope as runtime proof`() = runTest {
        withFixture { f ->
            for ((device, token) in listOf(
                    "other-device" to "dedicated-runtime-token-123456",
                "test-device" to "yzvault1:sealed-not-a-token"
                )) {
                f.recoverySessions(runtimeDevice = device, runtimeToken = token)
                assertThat(runCatching {
                    f.service.recoveryMatrixToken(
                        sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                        sha256("test-device".encodeToByteArray()).toHex()
                    )
                }.isFailure).isTrue()
            }
        }
    }

    @Test
    fun `LC08 rejects absent invalid and ambiguous stored session scopes`() = runTest {
        withFixture { f ->
            for ((valid, copies) in listOf(true to 0, false to 1, true to 2)) {
                f.recoverySessions(valid = valid, copies = copies)
                assertThat(runCatching {
                    f.service.recoveryMatrixToken(
                        sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                        sha256("test-device".encodeToByteArray()).toHex()
                    )
                }.isFailure).isTrue()
            }
        }
    }

    @Test
    fun `membership prepare preserves signed runtime identity and safe HTTP failure without implicit operation`() = runTest {
        withFixture { f ->
            f.recoverySessions()
            for ((status, detail) in listOf(404 to "secret-token private-user-content", 409 to "LC12_MEMBERSHIP_PROTOCOL_UNAVAILABLE")) {
                f.server.enqueue(MockResponse().setResponseCode(status).setBody("{\"detail\":\"$detail\"}"))
                val failure = runCatching {
                    f.service.prepareRoomMembership(
                        sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                        sha256("test-device".encodeToByteArray()).toHex(),
                        "!dm:test.invalid",
                        "@alice:test.invalid",
                        "leave",
                        null
                    )
                }.exceptionOrNull() as io.element.android.security.dataprotection.api.RoomMembershipFailure
                assertThat(failure.diagnostic).isEqualTo(
                    "LC12_PREPARE:HTTP_$status" +
                    if (status == 409) "_LC12_MEMBERSHIP_PROTOCOL_UNAVAILABLE" else ""
                )
                val sent = f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
                assertThat(sent.path).isEqualTo("/v1/lifecycle/membership/prepare")
                assertThat(sent.getHeader("X-YiZhe-Lifecycle-Operation-Id")).isNull()
                assertThat(sent.getHeader("X-YiZhe-Signature")).isNotEmpty()
                val body = JSON.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertThat(body.getValue("matrix_access_token").jsonPrimitive.content).isEqualTo("dedicated-runtime-token-123456")
                assertThat(body.getValue("target_user_id").jsonPrimitive.content).isEqualTo("@alice:test.invalid")
                assertThat(body.getValue("action").jsonPrimitive.content).isEqualTo("leave")
            }
            assertThat(f.server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `membership prepare rejects mismatched device before HTTP without exposing token`() = runTest {
        withFixture { f ->
            f.recoverySessions(runtimeDevice = "OTHER")
            val failure = runCatching {
                f.service.prepareRoomMembership(
                    sha256("@alice:test.invalid".encodeToByteArray()).toHex(),
                    sha256("test-device".encodeToByteArray()).toHex(),
                    "!dm:test.invalid",
                    "@alice:test.invalid",
                    "leave",
                    null
                )
            }.exceptionOrNull() as io.element.android.security.dataprotection.api.RoomMembershipFailure
            assertThat(failure.diagnostic).isEqualTo("MATRIX_SESSION:STATE_REJECTED")
            assertThat(f.server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `membership HTTP diagnostic omits unknown details including uppercase token shaped values`() {
        for (detail in listOf("SECRET_TOKEN_VALUE", "Bearer private-token", "@alice:test", "LC12_MEMBERSHIP_SECRET_TOKEN")) {
            assertThat(membershipHttpCode(500, detail)).isEqualTo("HTTP_500")
        }
    }

    private suspend fun withFixture(
        attestationVersion: Long = 1,
        block: suspend (Fixture) -> Unit,
    ) {
        mockkStatic(Settings.Secure::class)
        mockkStatic(android.util.Base64::class)
        every { Settings.Secure.getString(any(), any()) } returns null
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            val bytes = firstArg<ByteArray>()
            val flags = secondArg<Int>()
            if (flags and ANDROID_BASE64_URL_SAFE != 0) {
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } else {
                Base64.getEncoder().encodeToString(bytes)
            }
        }
        val root = temporaryFolder.newFolder()
        val stateFile = root.resolve("security/management/control-state-v1.json")
        val atomicFiles = listOf(
            stateFile,
            root.resolve("security/management/offline-entry-v1.json"),
            root.resolve("security/management/local-list-entry-v1.json"),
        ).associateWith(::AtomicFileHarness)
        val atomicFileHarness = atomicFiles.getValue(stateFile)
        mockkConstructor(AtomicFile::class)
        atomicFiles.forEach { (file, harness) ->
            every { constructedWith<AtomicFile>(EqMatcher(file)).baseFile } returns file
            every { constructedWith<AtomicFile>(EqMatcher(file)).openRead() } answers { harness.openRead() }
            every { constructedWith<AtomicFile>(EqMatcher(file)).startWrite() } answers { harness.startWrite() }
            every { constructedWith<AtomicFile>(EqMatcher(file)).finishWrite(any()) } answers { harness.finishWrite(firstArg()) }
            every { constructedWith<AtomicFile>(EqMatcher(file)).failWrite(any()) } answers { harness.failWrite(firstArg()) }
            every { constructedWith<AtomicFile>(EqMatcher(file)).delete() } answers {
                file.delete()
                Unit
            }
        }
        var fixture: Fixture? = null
        try {
            val initializedFixture = Fixture(root, attestationVersion, atomicFileHarness)
            fixture = initializedFixture
            block(initializedFixture)
        } finally {
            try {
                fixture?.close()
            } finally {
                try {
                    atomicFiles.values.forEach(AtomicFileHarness::close)
                } finally {
                    try {
                        unmockkConstructor(AtomicFile::class)
                    } finally {
                        try {
                            unmockkStatic(android.util.Base64::class)
                        } finally {
                            unmockkStatic(Settings.Secure::class)
                        }
                    }
                }
            }
        }
    }

    private class Fixture(
        root: java.io.File,
        attestationVersion: Long,
        private val atomicFileHarness: AtomicFileHarness,
    ) : Closeable {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyId = sha256(keyPair.public.encoded)
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val server = MockWebServer().apply { start() }
        val requests = mutableListOf<CapturedRequest>()
        private val configuration = mockk<ManagementControlConfiguration> {
            every { isConfigured } returns true
            every { isNextDeployment } returns true
            every { baseUrl } returns server.url("/").toString().removeSuffix("/")
            every { certificatePin } returns "sha256/${Base64.getEncoder().encodeToString(ByteArray(32))}"
            every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(keyPair.public.encoded)
            every { signingKeyId } returns keyId
        }
        val verifier = ManagementSignatureVerifier(configuration)
        private val context = mockk<Context> {
            every { noBackupFilesDir } returns root
            every { contentResolver } returns mockk(relaxed = true)
            every { packageName } returns "cn.yizhesy.chat"
            every { applicationInfo } returns mockk(relaxed = true)
        }
        val repository = SignedManagementStateRepository(context, verifier)

        fun acceptReportCommand(global: Boolean = true, installation: String = INSTALLATION_ID):
            io.element.android.security.dataprotection.api.VerifiedManagementCommand {
            val now = System.currentTimeMillis()
            val payload = JSON.encodeToJsonElement(CommandWire.serializer(), CommandWire(
                commandId = "original-global-child-command-0001",
                installationId = installation,
                commandType = "CLEAR_ACCOUNT",
                accountIdHash = "a".repeat(64),
                contentId = null,
                version = 1,
                issuedAtEpochMillis = now,
                notBeforeEpochMillis = now,
                expiresAtEpochMillis = now + 600_000,
                schemaVersion = 2,
                reasonCode = if (global) "GLOBAL_ACCOUNT_DESTROY" else "ADMIN_LOCAL_ACCOUNT_CLEAR",
                globalDestroyOperationId = if (global) "original-server-global-root-0001" else null,
                targetMatrixDeviceIdHash = if (global) null else "b".repeat(64),
                mobileGeneration = if (global) null else 1,
            )).jsonObject
            return repository.acceptCommand(SignedJsonEnvelopeWire(
                command = payload,
                keyId = keyId,
                signatureBase64 = authoritySignature(verifier.canonicalJson(payload))
            ), now)
        }

        fun tamperReportCommand(fault: String) {
            val file = File(context.noBackupFilesDir, "security/management/control-state-v1.json")
            val state = JSON.decodeFromString<PersistedManagementState>(file.readText())
            val old = state.commands.single()
            val changed = if (fault == "signature") {
                old.envelope.copy(signatureBase64 = "dGFtcGVyZWQ=")
            } else {
                old.envelope.copy(command = JsonObject(old.envelope.payload() + mapOf(
                    "global_destroy_operation_id" to kotlinx.serialization.json.JsonPrimitive("foreign-global-root-0001"),
                )))
            }
            file.writeText(JSON.encodeToString(state.copy(commands = listOf(old.copy(envelope = changed)))))
        }

        fun serveCommandReports(status: Int = 200) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += capture(request)
                    return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody("{}")
                }
            }
        }
        private var trustSnapshot = DeviceTrustSnapshot(
            installationId = INSTALLATION_ID,
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = attestationVersion,
            lastVerifiedAtEpochMillis = NOW - 10,
            attestationPublicKeySha256 = "d".repeat(64),
        )
        val controlSigningThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val deviceTrustService = mockk<DeviceTrustService> {
            coEvery { current() } answers { trustSnapshot }
            coEvery { applyRemoteTrust(any(), any()) } answers {
                trustSnapshot = trustSnapshot.copy(trustState = DeviceTrustState.TRUSTED)
                trustSnapshot
            }
            coEvery { applyRemoteRestriction(any(), any()) } answers {
                trustSnapshot = trustSnapshot.copy(trustState = firstArg())
                trustSnapshot
            }
            coEvery { signControlStatement(any()) } answers {
                controlSigningThreads.add(Thread.currentThread().name)
                DeviceControlRequestSignature(
                    installationId = INSTALLATION_ID,
                    attestationVersion = attestationVersion,
                    signature = sha256(firstArg<ByteArray>()),
                )
            }
        }
        private val sessionStore = mockk<SessionStore> {
            coEvery { getAllSessions() } returns emptyList()
        }
        private val buildMeta = mockk<BuildMeta> {
            every { versionCode } returns 100L
        }
        private val kmsConfigurationProvider = mockk<KmsClientConfigurationProvider> {
            every { configuration() } returns null
        }
        private val deviceRuntimeBindingProvider = mockk<DeviceRuntimeBindingProvider> {
            every { bootCount() } returns 77L
        }
        private val recoveryProvider = mockk<MatrixClientProvider>()

        fun recoverySessions(runtimeDevice: String = "test-device", runtimeToken: String = "dedicated-runtime-token-123456",
            valid: Boolean = true, copies: Int = 1) {
            val stored = io.element.android.libraries.sessionstorage.test.aSessionData(
                sessionId = "@alice:test.invalid",
                deviceId = "test-device",
                isTokenValid = valid,
                accessToken = "yzvault1:sealed-not-a-token"
            )
            coEvery { sessionStore.getAllSessions() } returns List(copies) { stored }
            val client = mockk<io.element.android.libraries.matrix.api.MatrixClient>()
            every { client.sessionId } returns io.element.android.libraries.matrix.api.core.UserId(stored.userId)
            every { client.deviceId } returns io.element.android.libraries.matrix.api.core.DeviceId(runtimeDevice)
            coEvery { client.recoverySessionAccessToken() } returns Result.success(runtimeToken)
            every { recoveryProvider.getOrNull(io.element.android.libraries.matrix.api.core.UserId(stored.userId)) } returns client
        }

        val service = HttpManagementControlService(
            context = context,
            configuration = configuration,
            signatureVerifier = verifier,
            repository = repository,
            deviceTrustService = deviceTrustService,
            sessionStore = sessionStore,
            matrixClientProvider = { recoveryProvider },
            buildMeta = buildMeta,
            kmsConfigurationProvider = kmsConfigurationProvider,
            deviceShareKeyService = mockk<DeviceShareKeyService>(),
            deviceRuntimeBindingProvider = deviceRuntimeBindingProvider,
        )

        fun mediaResourceDispatcher(fault: String? = null) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val original = JSON.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                    val purpose = when (request.path) {
                        "/v1/lifecycle/media-resources/reserve" -> "MEDIA_RESOURCE_RESERVATION_ACK"
                        "/v1/lifecycle/media-resources/seal" -> "MEDIA_RESOURCE_SEAL_ACK"
                        "/v1/lifecycle/media-runtime-cleanup" -> "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    val fact = if (fault == "operation") {
                        JsonObject(
                            original +
                        ("operation_id" to kotlinx.serialization.json.JsonPrimitive("another-original-operation"))
                        )
                    } else {
                        original
                    }
                    val digest = sha256Hex(verifier.canonicalJson(fact))
                    val reference = when (purpose) {
                        "MEDIA_RESOURCE_SEAL_ACK" -> "android-media-manifest:v1:$digest:" +
                            "${fact.getValue("outcome").jsonPrimitive.content}:" +
                            "${fact.getValue("owner_process_sha256").jsonPrimitive.content}:${fact.getValue("sealed_at_ms").jsonPrimitive.content}"
                        "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK" -> "android-media-runtime-cleanup:v1:$digest"
                        else -> null
                    }
                    val payload = buildJsonObject {
                        put("schema_version", kotlinx.serialization.json.JsonPrimitive(1))
                        put("purpose", kotlinx.serialization.json.JsonPrimitive(purpose))
                        put(if (purpose == "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK") "observation" else "request", fact)
                        put("signing_key_id", kotlinx.serialization.json.JsonPrimitive(keyId))
                        if (reference != null) {
                            put("receipt_reference", kotlinx.serialization.json.JsonPrimitive(reference))
                            put("accepted_at_ms", kotlinx.serialization.json.JsonPrimitive(1791551189433L))
                        }
                    }
                    return jsonResponse(
                        JsonObject(
                            payload + ("signature_b64" to kotlinx.serialization.json.JsonPrimitive(authoritySignature(verifier.canonicalJson(payload))))
                        ).toString()
                    )
                }
            }
        }

        fun failNextStatePublish() = atomicFileHarness.failNextPublish()

        fun beginStaleAccess() {
            trustSnapshot = trustSnapshot.copy(trustState = DeviceTrustState.STALE)
        }
        fun currentTrustState() = trustSnapshot.trustState

        val enrollmentChallenges = mutableListOf<ByteArray>()
        val enrollmentPaths = mutableListOf<String>()
        fun admitOnNextEnrollmentLockRead() {
            var reads = 0
            coEvery { deviceTrustService.current() } answers {
                reads++
                if (reads == 2) trustSnapshot = trustSnapshot.copy(trustState = DeviceTrustState.TRUSTED)
                trustSnapshot
            }
        }
        fun probeEnrollment(state: DeviceTrustState) {
            trustSnapshot = trustSnapshot.copy(trustState = state)
            every { android.util.Base64.decode(any<String>(), any()) } answers {
                Base64.getDecoder().decode(firstArg<String>())
            }
            coEvery { deviceTrustService.initialize() } answers { trustSnapshot }
            coEvery { deviceTrustService.refresh(any()) } answers {
                enrollmentChallenges += firstArg<ByteArray>()
                // Stop before signing/upload: this probe proves the real HTTP challenge path only.
                throw IllegalStateException("QA_CHALLENGE_PROBE")
            }
            var next = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    enrollmentPaths += checkNotNull(request.path)
                    next++
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"challenge_id":"challenge-$next",
                            "challenge_b64":"${Base64.getEncoder().encodeToString("fresh-$next".encodeToByteArray())}",
                            "expires_at_epoch_millis":${System.currentTimeMillis() + 60000}}"""
                    )
                }
            }
        }

        fun serveSignedRestriction(trustState: String, reason: String) {
            val publication = revisionEnvelope(1, minimumSecurityEpoch = 3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val captured = capture(request)
                    requests += captured
                    check(captured.path == SYNC_PATH)
                    val response = JSON.parseToJsonElement(syncResponse(publication)).jsonObject.toMutableMap()
                    val original = response.getValue("device_trust").jsonObject.getValue("decision").jsonObject
                    val decision = JsonObject(original + mapOf(
                        "trust_state" to kotlinx.serialization.json.JsonPrimitive(trustState),
                        "restriction_reason_code" to kotlinx.serialization.json.JsonPrimitive(reason),
                    ))
                    response["device_trust"] = JSON.encodeToJsonElement(SignedJsonEnvelopeWire.serializer(), SignedJsonEnvelopeWire(
                        decision = decision,
                        keyId = keyId,
                        signatureBase64 = authoritySignature(verifier.canonicalJson(decision)),
                    ))
                    return jsonResponse(JsonObject(response).toString())
                }
            }
        }

        fun serveAccessRestoration(failConfirmation: Boolean) {
            // A real publication is persisted once. Replays retain its exact signed bytes.
            val publication = revisionEnvelope(1, minimumSecurityEpoch = 3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val captured = capture(request)
                    requests += captured
                    check(captured.path == SYNC_PATH)
                    if (requests.size == 2 && failConfirmation) return MockResponse().setResponseCode(503)
                    val response = JSON.parseToJsonElement(syncResponse(publication)).jsonObject.toMutableMap()
                    if (requests.size == 2) {
                        val ticket = DeviceAccessTicketWire(
                            1,
                            "restored-access-ticket-original-01",
                            INSTALLATION_ID,
                            requireNotNull(trustSnapshot.attestationPublicKeySha256),
                            trustSnapshot.attestationVersion,
                            1,
                            NOW - 5,
                            NOW + 120_000,
                            30_000,
                            60_000
                        )
                        val payload = JSON.encodeToJsonElement(DeviceAccessTicketWire.serializer(), ticket).jsonObject
                        response["access_ticket"] = JSON.encodeToJsonElement(SignedJsonEnvelopeWire.serializer(), SignedJsonEnvelopeWire(
                            ticket = payload,
                            keyId = keyId,
                            signatureBase64 = authoritySignature(verifier.canonicalJson(payload)),
                        ))
                    }
                    return jsonResponse(JsonObject(response).toString())
                }
            }
        }

        fun reopenRepository(): SignedManagementStateRepository = SignedManagementStateRepository(context, verifier)

        fun revisionEnvelope(version: Long, minimumSecurityEpoch: Int = 4): SignedPolicyRevisionEnvelopeWire {
            val policy = PolicyWire(
                policyId = "global-policy",
                version = version,
                issuedAtEpochMillis = NOW - 10,
                effectiveAtEpochMillis = NOW - 1,
                expiresAtEpochMillis = NOW + 60_000,
                contentRetentionMillis = 60_000,
                maximumOfflineMillis = 30_000,
                offlineAccessMode = "BOUNDED_OFFLINE",
                notificationExposure = "HIDDEN",
                hardwareTrustRequirement = "TEE_OR_STRONGBOX",
                allowContentExport = false,
                allowClipboard = false,
                allowExternalShare = false,
                allowGroupReadReceipts = false,
                screenCapturePolicy = "BLOCK",
                requireContentEnvelope = true,
                allowRtc = true,
                schemaVersion = 2,
            )
            val legacyPolicy = JSON.encodeToJsonElement(PolicyWire.serializer(), policy).jsonObject
            val content = JsonObject(legacyPolicy.filterKeys { it !in POLICY_METADATA_FIELDS })
            val digest = sha256(verifier.canonicalJson(content)).toHex()
            val publication = PolicyPublicationWire(
                contract = "CP-POLICY",
                signatureSchemaVersion = 1,
                operationId = "operation-policy-$version",
                revisionId = "revision-policy-$version",
                domain = "GLOBAL_POLICY",
                version = version,
                scope = "GLOBAL",
                issuedAt = policy.issuedAtEpochMillis,
                effectiveAt = policy.effectiveAtEpochMillis,
                expiresAt = policy.expiresAtEpochMillis,
                payloadDigest = digest,
                minSecurityEpoch = minimumSecurityEpoch,
                requiredCapabilities = listOf("CAPTURE_BLOCK"),
                targetSnapshotDigest = "b".repeat(64),
            )
            val publicationJson = JSON.encodeToJsonElement(PolicyPublicationWire.serializer(), publication).jsonObject
            return SignedPolicyRevisionEnvelopeWire(
                contract = publication.contract,
                signatureSchemaVersion = publication.signatureSchemaVersion,
                revisionId = publication.revisionId,
                operationId = publication.operationId,
                domain = publication.domain,
                version = publication.version,
                payloadDigest = publication.payloadDigest,
                policy = content,
                publicationCanonical = publicationJson,
                publicationSignatureB64 = authoritySignature(verifier.canonicalJson(publicationJson)),
                legacyPolicy = legacyPolicy,
                legacyPolicySignatureB64 = authoritySignature(verifier.canonicalJson(legacyPolicy)),
                keyId = keyId,
                effectiveAt = publication.effectiveAt,
                expiresAt = publication.expiresAt,
                serverFenceEvidenceDigest = "c".repeat(64),
            )
        }

        fun acceptBlocked(envelope: SignedPolicyRevisionEnvelopeWire): PersistedPolicyApplicationWire =
            repository.acceptPolicyRevisionEnvelope(
                envelope = envelope,
                serverTimeEpochMillis = NOW,
                localSecurityEpoch = 3,
                supportedCapabilities = setOf("CAPTURE_BLOCK"),
            ).also { application ->
                assertThat(application.state).isEqualTo(LocalPolicyApplicationState.BLOCKED_VERSION.name)
            }

        fun persistBlockedReceipt(
            application: PersistedPolicyApplicationWire,
            nonce: String,
            deviceSignature: String,
        ): PolicyApplicationReceiptWire {
            val publication = JSON.decodeFromJsonElement(
                PolicyPublicationWire.serializer(),
                application.publication.publication,
            )
            val unsigned = buildUnsignedPolicyApplicationReceipt(
                application = application,
                evidence = PolicyApplicationEvidence(
                    version = publication.version,
                    appliedAtEpochMillis = null,
                    enforcedCapabilities = emptyList(),
                    refusedCapabilities = publication.requiredCapabilities,
                    result = ManagementPolicyApplicationState.BLOCKED_VERSION,
                ),
                installationId = INSTALLATION_ID,
                bootCount = 76,
                nonce = nonce,
            )
            return repository.persistPolicyApplicationReceipt(unsigned.copy(deviceSignature = deviceSignature))
        }

        fun serveSuccessfulSync(secondEnvelope: SignedPolicyRevisionEnvelopeWire) {
            val revisionIds = mapOf(1L to "revision-policy-1", 2L to secondEnvelope.revisionId)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val captured = capture(request)
                    requests += captured
                    return when (captured.path) {
                        RECEIPT_PATH -> {
                            val receipt = JSON.decodeFromString(
                                PolicyApplicationReceiptWire.serializer(),
                                captured.body,
                            )
                            val acknowledgement = PolicyApplicationReceiptAcknowledgementWire(
                                revisionId = checkNotNull(revisionIds[receipt.version]),
                                domain = receipt.policyDomain,
                                version = receipt.version,
                                installationId = receipt.installationId,
                                state = receipt.result,
                                bootId = receipt.bootId,
                                digest = receipt.digest,
                                appliedAt = receipt.appliedAt,
                                enforcedCapabilities = receipt.enforcedCapabilities,
                                refusedCapabilities = receipt.refusedCapabilities,
                                cleanupOperationIds = receipt.cleanupOperationIds,
                                lastEvidenceDigest = policyApplicationReceiptEvidenceDigest(receipt, verifier),
                            )
                            jsonResponse(JSON.encodeToString(acknowledgement))
                        }
                        SYNC_PATH -> jsonResponse(syncResponse(secondEnvelope))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        fun serveRotatedKeyRejection() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += capture(request)
                    return MockResponse()
                        .setResponseCode(403)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"detail\":\"signature verification failed after device key rotation\"}")
                }
            }
        }

        fun assertSignedPost(request: CapturedRequest, lifecycleOperationId: String?) {
            assertThat(request.installationId).isEqualTo(INSTALLATION_ID)
            val parts = mutableListOf(
                "POST",
                request.path,
                request.timestamp,
                request.nonce,
                sha256(request.body.encodeToByteArray()).toHex(),
            )
            lifecycleOperationId?.let(parts::add)
            assertThat(request.lifecycleOperationId).isEqualTo(lifecycleOperationId)
            assertThat(request.signature).isEqualTo(deviceSignature(parts.joinToString("\n").encodeToByteArray()))
        }

        fun deviceSignature(statement: ByteArray): String = Base64.getEncoder().encodeToString(sha256(statement))

        fun relayPreparationDispatcher(fault: String? = null, terminal: Boolean = true) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val query = JSON.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                    val now = System.currentTimeMillis()
                    val operation = JSON.parseToJsonElement("""{
                        "schema_version":1,"catalog_id":"yizhechat-full-app-lifecycle-v1",
                        "operation_id":"${if (fault == "operation") "another-relay-operation-0001" else query.getValue("operation_id").jsonPrimitive.content}",
                        "lifecycle_id":"${if (fault == "lifecycle") "LC-13" else "LC-15"}",
                        "idempotency_key":"relay-edit-original-0001",
                        "subjects":{"installation_id":"${if (fault == "owner") "another-installation" else INSTALLATION_ID}",
                            "account_id_hash":"${if (fault == "account") "e".repeat(64) else query.getValue("account_id_hash").jsonPrimitive.content}",
                            "matrix_device_id_hash":"${if (fault == "device") {
                        "e".repeat(
                            64
                        )
                    } else {
                        query.getValue("matrix_device_id_hash").jsonPrimitive.content
                    }}",
                            "room_id_hash":"${if (fault == "room") "e".repeat(64) else query.getValue("room_id_hash").jsonPrimitive.content}",
                            "event_id_hash":"${if (fault == "event") "e".repeat(64) else query.getValue("event_id_hash").jsonPrimitive.content}"},
                        "state":"${if (terminal) "FAILED_FINAL" else "RUNNING"}","current_phase":"RECEIPTED",
                        "authority_revisions":{},"steps":[],"deadline_ms":${now - 60_000},
                        "compensation_state":"${if (terminal) "PENDING" else "NOT_REQUIRED"}",
                        "terminal_receipt_id":${if (terminal && fault != "receipt") "\"original-terminal-erasure-receipt\"" else "null"},
                        "created_at_ms":${now - 120_000},"updated_at_ms":$now
                    }""").jsonObject
                    val steps = kotlinx.serialization.json.JsonArray(
                        (if (fault == "steps") emptyList() else listOf("ANDROID_FRONTEND", "ANDROID_SERVICE", "CONTROL_PLANE", "DATABASE", "MATRIX", "KMS"))
                            .map { component ->
                                JSON.parseToJsonElement("""{"step_id":"original-step-$component",
                                "component":"$component","required":true,"state":"PENDING","attempt_count":0,"updated_at_ms":$now}""")
                            },
                    )
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("schema_version", kotlinx.serialization.json.JsonPrimitive(1))
                        put("purpose", kotlinx.serialization.json.JsonPrimitive(if (fault ==
                            "purpose") {
                                "OTHER_PURPOSE"
                            } else {
                                "PROTECTED_RELAY_PREPARATION_RECONCILIATION"
                            }))
                        put("query", if (fault ==
                            "nonce") {
                                JsonObject(query + ("request_nonce" to kotlinx.serialization.json.JsonPrimitive("0".repeat(64))))
                            } else {
                                query
                            })
                        put("installation_id", kotlinx.serialization.json.JsonPrimitive(if (fault ==
                            "installation") {
                                "another-installation"
                            } else {
                                INSTALLATION_ID
                            }))
                        put("operation", JsonObject(operation + ("steps" to steps)))
                        put("issued_at_ms", kotlinx.serialization.json.JsonPrimitive(if (fault == "future") now + 10_000 else now - 1))
                        put("expires_at_ms", kotlinx.serialization.json.JsonPrimitive(if (fault ==
                            "expired") {
                                now - 1
                            } else if (fault == "duration") {
                                now + 30_000
                            } else {
                                now + 14_000
                            }))
                        put("signing_key_id", kotlinx.serialization.json.JsonPrimitive(keyId))
                    }
                    val signature = if (fault ==
                        "signature") {
                            Base64.getEncoder().encodeToString(ByteArray(72))
                        } else {
                            authoritySignature(verifier.canonicalJson(payload))
                        }
                    return jsonResponse(JsonObject(payload + ("signature_b64" to kotlinx.serialization.json.JsonPrimitive(signature))).toString())
                }
            }
        }

        fun relayRetryDispatcher(fault: String? = null) {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val query = JSON.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                    val now = System.currentTimeMillis()
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("schema_version", kotlinx.serialization.json.JsonPrimitive(1))
                        put(
                            "purpose",
                            kotlinx.serialization.json.JsonPrimitive(if (fault == "purpose") "OTHER_PURPOSE" else "PROTECTED_RELAY_RETRY_AUTHORIZATION")
                        )
                        put("query", if (fault == "nonce") {
                            JsonObject(query + ("request_nonce" to
                            kotlinx.serialization.json.JsonPrimitive("0".repeat(64))))
                        } else {
                            query
                        })
                        put(
                            "installation_id",
                            kotlinx.serialization.json.JsonPrimitive(if (fault == "installation") "another-installation" else INSTALLATION_ID)
                        )
                        put("authorized", kotlinx.serialization.json.JsonPrimitive(fault != "denied"))
                        put("issued_at_ms", kotlinx.serialization.json.JsonPrimitive(if (fault == "future") now + 10_000 else now - 1))
                        put("expires_at_ms", kotlinx.serialization.json.JsonPrimitive(if (fault == "expired") {
                                now - 1
                            } else if (fault == "duration") {
                                now + 30_000
                            } else {
                                now + 14_000
                            }))
                        put("signing_key_id", kotlinx.serialization.json.JsonPrimitive(keyId))
                    }
                    val signature = if (fault == "signature") {
                        Base64.getEncoder().encodeToString(ByteArray(72))
                    } else {
                        authoritySignature(verifier.canonicalJson(payload))
                    }
                    return jsonResponse(JsonObject(payload + ("signature_b64" to
                        kotlinx.serialization.json.JsonPrimitive(signature))).toString())
                }
            }
        }

        fun originResponse(
            fault: String? = null, unknown: Boolean = false, legacy: Boolean = false,
            kind: String = "RELAY_OPERATION", lifecycle: String = "LC-15", previous: Boolean = false,
        ): String {
            val now = System.currentTimeMillis()
            val query = JSON.parseToJsonElement("""{
                "account_id_hash":"${"a".repeat(64)}","matrix_device_id_hash":"${"b".repeat(64)}",
                "room_id_hash":"${"c".repeat(64)}","event_id_hash":"${"d".repeat(64)}",
                "carrier_sha256":"${"e".repeat(64)}","immutable_context_sha256":"${"f".repeat(64)}",
                "content_id":"${if (fault == "query") "other-content" else "origin-content-0001"}",
                "content_kind":"$kind","kms_reference":"origin-kms-reference-0001","generation":4
            }""").jsonObject
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(1))
                put("purpose", kotlinx.serialization.json.JsonPrimitive(if (fault == "purpose") "OTHER_PURPOSE" else "PROTECTED_SEND_ORIGIN_LOOKUP"))
                put("installation_id", kotlinx.serialization.json.JsonPrimitive(if (fault == "installation") "other-installation" else INSTALLATION_ID))
                put("query", query)
                put("origin", if (unknown) {
                    kotlinx.serialization.json.JsonNull
                } else {
                    kotlinx.serialization.json.buildJsonObject {
                    put("operation_id", kotlinx.serialization.json.JsonPrimitive("original-send-operation-0001"))
                    put("lifecycle_id", kotlinx.serialization.json.JsonPrimitive(if (fault == "lifecycle") "LC-14" else lifecycle))
                    if (previous) {
                        put("scope_resolution", kotlinx.serialization.json.JsonPrimitive("PREVIOUS_MATRIX_SESSION"))
                        if (fault != "missing-session") {
                            put("producer_matrix_device_id_hash", kotlinx.serialization.json.JsonPrimitive(
                                if (fault == "same-session") "b".repeat(64) else "9".repeat(64)
                            ))
                        }
                    }
                    if (legacy || fault == "resolution") {
                        put(
                            "scope_resolution",
                            kotlinx.serialization.json.JsonPrimitive(
                                if (fault == "resolution") "UNVERIFIED_PROJECTION" else "LEGACY_INSTALLATION_AND_AUTHORITY_FACTS"
                            )
                        )
                    }
                }
                })
                val issued = when (fault) {
                    "expired" -> now - 120_000
                    "future" -> now + 30_000
                    else -> now - 1_000
                }
                put("issued_at_ms", kotlinx.serialization.json.JsonPrimitive(issued))
                put("expires_at_ms", kotlinx.serialization.json.JsonPrimitive(issued + if (fault == "duration") 120_000 else 60_000))
                put("signing_key_id", kotlinx.serialization.json.JsonPrimitive(keyId))
            }
            val signature = authoritySignature(verifier.canonicalJson(payload))
            return JsonObject(
                payload + ("signature_b64" to kotlinx.serialization.json.JsonPrimitive(
                    if (fault == "signature") Base64.getEncoder().encodeToString(ByteArray(72)) else signature
                ))
            ).toString()
        }

        override fun close() {
            server.shutdown()
        }

        private fun syncResponse(policy: SignedPolicyRevisionEnvelopeWire): String {
            val decision = TrustDecisionWire(
                installationId = INSTALLATION_ID,
                attestationVersion = trustSnapshot.attestationVersion,
                trustState = "TRUSTED",
                locationEvidenceState = "ACCEPTED",
                issuedAtEpochMillis = NOW - 5,
                expiresAtEpochMillis = NOW + 60_000,
                schemaVersion = 1,
            )
            val decisionJson = JSON.encodeToJsonElement(TrustDecisionWire.serializer(), decision).jsonObject
            val response = SyncResponseWire(
                serverTimeEpochMillis = NOW,
                policy = JSON.encodeToJsonElement(SignedPolicyRevisionEnvelopeWire.serializer(), policy).jsonObject,
                deviceTrust = SignedJsonEnvelopeWire(
                    decision = decisionJson,
                    keyId = keyId,
                    signatureBase64 = authoritySignature(verifier.canonicalJson(decisionJson)),
                ),
                commands = emptyList(),
            )
            return JSON.encodeToString(SyncResponseWire.serializer(), response)
        }

        private fun capture(request: RecordedRequest): CapturedRequest = CapturedRequest(
            path = checkNotNull(request.path),
            body = request.body.readUtf8(),
            installationId = checkNotNull(request.getHeader("X-YiZhe-Installation")),
            timestamp = checkNotNull(request.getHeader("X-YiZhe-Timestamp")),
            nonce = checkNotNull(request.getHeader("X-YiZhe-Nonce")),
            signature = checkNotNull(request.getHeader("X-YiZhe-Signature")),
            lifecycleOperationId = request.getHeader("X-YiZhe-Lifecycle-Operation-Id"),
        )

        private fun jsonResponse(body: String): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

        private fun authoritySignature(payload: ByteArray): String = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        )
    }

    private class AtomicFileHarness(
        private val stateFile: File,
    ) : Closeable {
        private val stagedFile = File(stateFile.parentFile, "${stateFile.name}.stage")
        private var activeOutput: FileOutputStream? = null
        private var failNextPublish = false

        fun openRead(): FileInputStream = FileInputStream(stateFile)

        fun startWrite(): FileOutputStream {
            check(activeOutput == null) { "An atomic state write is already active." }
            stateFile.parentFile?.let { directory ->
                check(directory.isDirectory || directory.mkdirs()) { "Unable to create test state directory." }
            }
            return FileOutputStream(stagedFile).also { activeOutput = it }
        }

        fun finishWrite(output: FileOutputStream) {
            check(activeOutput === output) { "Atomic state output identity changed." }
            output.close()
            if (failNextPublish) {
                failNextPublish = false
                throw IOException("Injected atomic state publish failure.")
            }
            Files.move(
                stagedFile.toPath(),
                stateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            activeOutput = null
        }

        fun failWrite(output: FileOutputStream) {
            check(activeOutput === output) { "Atomic rollback output identity changed." }
            runCatching { output.close() }
            check(!stagedFile.exists() || stagedFile.delete()) { "Unable to roll back staged test state." }
            activeOutput = null
        }

        fun failNextPublish() {
            check(!failNextPublish) { "An atomic publish failure is already armed." }
            failNextPublish = true
        }

        override fun close() {
            activeOutput?.let { runCatching { it.close() } }
            activeOutput = null
            if (stagedFile.exists()) check(stagedFile.delete()) { "Unable to remove staged test state." }
        }
    }

    private data class CapturedRequest(
        val path: String,
        val body: String,
        val installationId: String,
        val timestamp: String,
        val nonce: String,
        val signature: String,
        val lifecycleOperationId: String?,
    )

    private companion object {
        const val NOW = 2_000_000L
        const val INSTALLATION_ID = "installation-policy-device-0001"
        const val RECEIPT_PATH = "/v2/policy-application-receipts"
        const val SYNC_PATH = "/v1/device/sync"
        const val SYNC_OPERATION_ID = "operation-device-sync-0001"
        const val ANDROID_BASE64_URL_SAFE = 8
        val POLICY_METADATA_FIELDS = setOf("version", "issued_at_epoch_millis", "schema_version")
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
