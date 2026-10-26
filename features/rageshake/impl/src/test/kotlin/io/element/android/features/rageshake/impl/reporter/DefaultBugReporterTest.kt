/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.reporter

import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.RageshakeConfig
import io.element.android.features.rageshake.api.reporter.BugReporterListener
import io.element.android.features.rageshake.impl.crash.CrashDataStore
import io.element.android.features.rageshake.impl.crash.FakeCrashDataStore
import io.element.android.features.rageshake.impl.screenshot.FakeScreenshotHolder
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.tracing.TracingService
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.FakeSdkMetadata
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.notificationsettings.FakeNotificationSettingsService
import io.element.android.libraries.matrix.test.tracing.FakeTracingService
import io.element.android.libraries.network.useragent.DefaultUserAgentProvider
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.buffer
import okio.source
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultBugReporterTest {
    @Test
    fun `both debug and release export actual uploader multipart for encrypted backend round trip`() = runTest {
        for (debug in listOf(true, false)) {
            val folder = java.nio.file.Files.createTempDirectory("actual-uploader").toFile()
            val roots = object : io.element.android.security.securestorage.api.SecureStorageRoots {
                override fun persistentRoot() = java.io.File(folder, "persistent")
                override fun runtimeRoot() = java.io.File(folder, "runtime")
            }
            val store = io.element.android.security.securestorage.impl.DefaultSecureDiagnosticLogStore(
                roots,
                io.element.android.security.securestorage.test.FakeDeviceRootKeyService()
            )
            store.append(
                6,
                "RegistrationClient",
                "HTTP 503 CERT_DATE_INVALID password=DO_NOT_UPLOAD",
                java.net.SocketTimeoutException("access_token=DO_NOT_UPLOAD")
            )
            assertThat(store.flushBlocking(5000)).isTrue()
            val provider = DiagnosticSnapshotProvider(store)
            val bundle = provider.get()
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"report_id":"bug-1234567890abcdef12345678","diagnostics":${bundle.manifest}}"""))
            server.start()
            var success = false
            createDefaultBugReporter(server = server, buildMeta = aBuildMeta(
                isDebuggable = debug,
                applicationId = "com.yizhe.chat.internal",
                versionName = "diagnostic-chain-fixture"
            ), diagnosticProvider = provider)
                .sendBugReport(true, true, false, "专项诊断链路验证：注册连接超时与证书错误", listener = object : BugReporterListener {
                    override fun onUploadCancelled() = Unit
                    override fun onUploadFailed(reason: String?) {
                        error(reason.orEmpty())
                    }
                    override fun onProgress(progress: Int) = Unit
                    override fun onUploadSucceed() {
                        success = true
                    }
                })
            assertThat(success).isTrue()
            val request = server.takeRequest()
            assertThat(bundle.text).doesNotContain("DO_NOT_UPLOAD")
            System.getProperty("diagnostic.fixture.dir")?.let { path ->
                java.io.File(path, if (debug) "debug" else "internal-release").apply { mkdirs() }.also {
                    java.io.File(it, "android-diagnostics.jsonl.gz").writeBytes(bundle.compressed)
                    java.io.File(it, "android-manifest.json").writeText(bundle.manifest)
                    java.io.File(it, "android-upload-request.bin").writeBytes(request.body.readByteArray())
                    java.io.File(it, "android-upload-content-type.txt").writeText(request.getHeader("Content-Type")!!)
                }
            }
            server.shutdown()
        }
    }
    @Test
    fun `release upload never reports success on HTTP200 without matching stored log receipt`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        var failure: String? = null
        var success = false
        createDefaultBugReporter(
            server = server,
            buildMeta = aBuildMeta(isDebuggable = false)
        ).sendBugReport(true, true, false, "diagnostic receipt failure test", listener = object : BugReporterListener {
                override fun onUploadCancelled() = Unit
                override fun onUploadFailed(reason: String?) {
                    failure = reason
                }
                override fun onProgress(progress: Int) = Unit
                override fun onUploadSucceed() {
                    success = true
                }
            })
        assertThat(server.takeRequest().method).isEqualTo("POST")
        assertThat(success).isFalse()
        assertThat(failure).contains("服务器未确认")
        server.shutdown()
    }

    @Test
    fun `selected empty logs are rejected before any transport and never claim upload success`() = runTest {
        val server = MockWebServer()
        server.start()
        val logs = FakeDiagnosticLogStore().apply {
            result = result.copy(
                events = emptyList(),
            state = io.element.android.security.securestorage.api.DiagnosticCollectionState.EMPTY
            )
        }
        var failure: String? = null
        var success = false
        createDefaultBugReporter(
            server = server,
            logs = logs
        ).sendBugReport(true, false, false, "empty log failure test", listener = object : BugReporterListener {
                override fun onUploadCancelled() = Unit
                override fun onUploadFailed(reason: String?) {
                    failure = reason
                }
                override fun onProgress(progress: Int) = Unit
                override fun onUploadSucceed() {
                    success = true
                }
            })
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(success).isFalse()
        assertThat(failure).contains("尚无运行日志")
        server.shutdown()
    }

    @Test
    fun `backend detail error and status fallbacks stay clear Chinese`() {
        assertThat(parseBugReportErrorMessage("""{"detail":"运行日志没有有效记录，请重新采集"}""", 422)).contains("没有有效记录")
        assertThat(parseBugReportErrorMessage("""{"error":{"message":"请稍后重试"}}""", 503)).isEqualTo("请稍后重试")
        assertThat(parseBugReportErrorMessage("""{"error":"service unavailable"}""", 503)).contains("暂时不可用")
        assertThat(bugReportFallback(429)).contains("过于频繁")
        assertThat(bugReportFallback(413)).contains("过大")
    }
    @Test
    fun `test sendBugReport success`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200).setBody(receipt())
        )
        server.start()
        val sut = createDefaultBugReporter(server = server)
        var onUploadCancelledCalled = false
        var onUploadFailedCalled = false
        val progressValues = mutableListOf<Int>()
        var onUploadSucceedCalled = false
        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            sendPushRules = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = object : BugReporterListener {
                override fun onUploadCancelled() {
                    onUploadCancelledCalled = true
                }

                override fun onUploadFailed(reason: String?) {
                    onUploadFailedCalled = true
                }

                override fun onProgress(progress: Int) {
                    progressValues.add(progress)
                }

                override fun onUploadSucceed() {
                    onUploadSucceedCalled = true
                }
            },
        )
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/")
        assertThat(request.method).isEqualTo("POST")
        server.shutdown()
        assertThat(onUploadCancelledCalled).isFalse()
        assertThat(onUploadFailedCalled).isFalse()
        assertThat(progressValues.last()).isEqualTo(100)
        assertThat(progressValues.zipWithNext().all { (before, after) -> after >= before }).isTrue()
        assertThat(onUploadSucceedCalled).isTrue()
    }

    @Test
    fun `test sendBugReport form data`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200).setBody(receipt())
        )
        server.start()

        val mockSessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData(sessionId = "@foo:example.com", deviceId = "ABCDEFGH"))
        )

        val fakeEncryptionService = FakeEncryptionService()

        val fakePushRules = "{ content: ... }"
        val fakeNotificationSettingsService = FakeNotificationSettingsService(
            getRawPushRulesResult = { Result.success(fakePushRules) }
        )
        val matrixClient = FakeMatrixClient(encryptionService = fakeEncryptionService, notificationSettingsService = fakeNotificationSettingsService)

        fakeEncryptionService.givenDeviceKeys("CURVECURVECURVE", "EDKEYEDKEYEDKY")
        val sut = createDefaultBugReporter(
            server = server,
            crashDataStore = FakeCrashDataStore(),
            sessionStore = mockSessionStore,
            matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.success(matrixClient) })
        )

        val progressValues = mutableListOf<Int>()
        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            sendPushRules = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = object : BugReporterListener {
                override fun onUploadCancelled() {}

                override fun onUploadFailed(reason: String?) {}

                override fun onProgress(progress: Int) {
                    progressValues.add(progress)
                }

                override fun onUploadSucceed() {}
            },
        )
        val request = server.takeRequest()

        val foundValues = collectValuesFromFormData(request)

        assertThat(foundValues["app"]).isEqualTo(RageshakeConfig.BUG_REPORT_APP_NAME)
        assertThat(foundValues["can_contact"]).isEqualTo("true")
        assertThat(foundValues["device_id"]).isEqualTo("ABCDEFGH")
        assertThat(foundValues["sdk_sha"]).isEqualTo("123456789")
        assertThat(foundValues["user_id"]).isEqualTo("@foo:example.com")
        assertThat(foundValues["number_of_accounts"]).isEqualTo("1")
        assertThat(foundValues["text"]).isEqualTo("a bug occurred")
        assertThat(foundValues["device_keys"]).isEqualTo("curve25519:CURVECURVECURVE, ed25519:EDKEYEDKEYEDKY")
        assertThat(foundValues["notification_diagnostics"]).contains("rule_count")
        assertThat(foundValues["file"]).isNull()

        // device_key now added given they are not null
        // so is the file value for the included push_rules
        assertThat(progressValues.last()).isEqualTo(100)
        assertThat(progressValues.zipWithNext().all { (before, after) -> after >= before }).isTrue()

        server.shutdown()
    }

    @Test
    fun `test sendBugReport multi accounts`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200).setBody(receipt())
        )
        server.start()

        val mockSessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(sessionId = "@foo:example.com", deviceId = "ABCDEFGH"),
                aSessionData(sessionId = A_USER_ID.value, deviceId = A_DEVICE_ID.value),
            )
        )

        val fakeEncryptionService = FakeEncryptionService()
        val matrixClient = FakeMatrixClient(encryptionService = fakeEncryptionService)

        fakeEncryptionService.givenDeviceKeys("CURVECURVECURVE", "EDKEYEDKEYEDKY")
        val sut = createDefaultBugReporter(
            server = server,
            crashDataStore = FakeCrashDataStore(),
            sessionStore = mockSessionStore,
            matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.success(matrixClient) })
        )

        val progressValues = mutableListOf<Int>()
        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = object : BugReporterListener {
                override fun onUploadCancelled() {}

                override fun onUploadFailed(reason: String?) {}

                override fun onProgress(progress: Int) {
                    progressValues.add(progress)
                }

                override fun onUploadSucceed() {}
            },
        )
        val request = server.takeRequest()

        val foundValues = collectValuesFromFormData(request)

        assertThat(foundValues["app"]).isEqualTo(RageshakeConfig.BUG_REPORT_APP_NAME)
        assertThat(foundValues["can_contact"]).isEqualTo("true")
        assertThat(foundValues["device_id"]).isEqualTo("ABCDEFGH")
        assertThat(foundValues["sdk_sha"]).isEqualTo("123456789")
        assertThat(foundValues["user_id"]).isEqualTo("@foo:example.com")
        assertThat(foundValues["number_of_accounts"]).isEqualTo("2")
        assertThat(foundValues["text"]).isEqualTo("a bug occurred")
        assertThat(foundValues["device_keys"]).isEqualTo("curve25519:CURVECURVECURVE, ed25519:EDKEYEDKEYEDKY")

        // device_key now added given they are not null
        assertThat(progressValues.last()).isEqualTo(100)
        assertThat(progressValues.zipWithNext().all { (before, after) -> after >= before }).isTrue()

        server.shutdown()
    }

    @Test
    fun `test sendBugReport should not report device_keys if not known`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200).setBody(receipt())
        )
        server.start()

        val mockSessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData("@foo:example.com", "ABCDEFGH"))
        )

        val fakeEncryptionService = FakeEncryptionService()
        val matrixClient = FakeMatrixClient(encryptionService = fakeEncryptionService)

        fakeEncryptionService.givenDeviceKeys(null, null)
        val sut = createDefaultBugReporter(
            server = server,
            sessionStore = mockSessionStore,
            matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.success(matrixClient) })
        )

        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = NoopBugReporterListener(),
        )
        val request = server.takeRequest()

        val foundValues = collectValuesFromFormData(request)
        assertThat(foundValues["device_keys"]).isNull()
        server.shutdown()
    }

    @Test
    fun `test sendBugReport no client provider no session data`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200).setBody(receipt())
        )
        server.start()

        val fakeEncryptionService = FakeEncryptionService()

        fakeEncryptionService.givenDeviceKeys(null, null)
        val sut = createDefaultBugReporter(
            server = server,
            crashDataStore = FakeCrashDataStore("I did crash", true),
            sessionStore = InMemorySessionStore(),
            matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.failure(Exception("Mock no client")) })
        )

        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = NoopBugReporterListener(),
        )
        val request = server.takeRequest()

        val foundValues = collectValuesFromFormData(request)
        assertThat(foundValues["device_keys"]).isNull()
        assertThat(foundValues["device_id"]).isEqualTo("undefined")
        assertThat(foundValues["user_id"]).isEqualTo("undefined")
        assertThat(foundValues["number_of_accounts"]).isEqualTo("0")
        assertThat(foundValues["label"]).isEqualTo("crash")
    }

    private fun collectValuesFromFormData(request: RecordedRequest): HashMap<String, String> {
        val boundary = request.headers["Content-Type"]!!.split("=").last()
        val foundValues = HashMap<String, String>()
        request.body.inputStream().source().buffer().use {
            val multipartReader = MultipartReader(it, boundary)
            // Just use simple parsing to detect basic properties
            val regex = "form-data; name=\"(\\w*)\".*".toRegex()
            multipartReader.use {
                var part = multipartReader.nextPart()
                while (part != null) {
                    part.headers["Content-Disposition"]?.let { contentDisposition ->
                        regex.find(contentDisposition)?.groupValues?.get(1)?.let { name ->
                            foundValues.put(name, part.body.readUtf8())
                        }
                    }
                    part = multipartReader.nextPart()
                }
            }
        }
        return foundValues
    }

    @Test
    fun `test sendBugReport error`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error": "An error body"}""")
        )
        server.start()
        val sut = createDefaultBugReporter(server = server)
        var onUploadCancelledCalled = false
        var onUploadFailedCalled = false
        var onUploadFailedReason: String? = null
        val progressValues = mutableListOf<Int>()
        var onUploadSucceedCalled = false
        sut.sendBugReport(
            withDevicesLogs = true,
            withCrashLogs = true,
            withScreenshot = true,
            sendPushRules = true,
            problemDescription = "a bug occurred",
            canContact = true,
            listener = object : BugReporterListener {
                override fun onUploadCancelled() {
                    onUploadCancelledCalled = true
                }

                override fun onUploadFailed(reason: String?) {
                    onUploadFailedCalled = true
                    onUploadFailedReason = reason
                }

                override fun onProgress(progress: Int) {
                    progressValues.add(progress)
                }

                override fun onUploadSucceed() {
                    onUploadSucceedCalled = true
                }
            },
        )
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/")
        assertThat(request.method).isEqualTo("POST")
        server.shutdown()
        assertThat(onUploadCancelledCalled).isFalse()
        assertThat(onUploadFailedCalled).isTrue()
        assertThat(onUploadFailedReason).isEqualTo("反馈提交失败，请稍后重试")
        assertThat(progressValues.last()).isEqualTo(100)
        assertThat(progressValues.zipWithNext().all { (before, after) -> after >= before }).isTrue()
        assertThat(onUploadSucceedCalled).isFalse()
    }

    @Test
    fun `the log directory is initialized using the last session store data`() = runTest {
        val sut = createDefaultBugReporter(
            buildMeta = aBuildMeta(isEnterpriseBuild = true),
            sessionStore = InMemorySessionStore(
                initialList = listOf(aSessionData(sessionId = "@alice:domain.com"))
            )
        )
        assertThat(sut.logDirectory().absolutePath.replace('\\', '/')).endsWith("/cache/logs/domain.com")
    }

    @Test
    fun `foss build - the log directory is initialized to the root log directory`() = runTest {
        val sut = createDefaultBugReporter(
            sessionStore = InMemorySessionStore(
                initialList = listOf(aSessionData(sessionId = "@alice:domain.com"))
            )
        )
        assertThat(sut.logDirectory().absolutePath.replace('\\', '/')).endsWith("/cache/logs")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when a session is added, the tracing service is invoked`() = runTest {
        var param: WriteToFilesConfiguration? = null
        val updateWriteToFilesConfigurationResult = lambdaRecorder<WriteToFilesConfiguration, Unit> {
            param = it
        }
        val sessionStore = InMemorySessionStore()
        createDefaultBugReporter(
            buildMeta = aBuildMeta(isEnterpriseBuild = true),
            sessionStore = sessionStore,
            tracingService = FakeTracingService(
                updateWriteToFilesConfigurationResult = updateWriteToFilesConfigurationResult,
            ),
        )
        sessionStore.addSession(aSessionData(sessionId = "@alice:server.org"))
        runCurrent()
        updateWriteToFilesConfigurationResult.assertions().isCalledOnce()
        assertThat(param).isNotNull()
        assertThat(param).isInstanceOf(WriteToFilesConfiguration.Disabled::class.java)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when another session is added on same domain, the tracing service is not invoked`() = runTest {
        val updateWriteToFilesConfigurationResult = lambdaRecorder<WriteToFilesConfiguration, Unit> {}
        val sessionStore = InMemorySessionStore()
        createDefaultBugReporter(
            buildMeta = aBuildMeta(isEnterpriseBuild = true),
            sessionStore = sessionStore,
            tracingService = FakeTracingService(
                updateWriteToFilesConfigurationResult = updateWriteToFilesConfigurationResult,
            ),
        )
        sessionStore.addSession(aSessionData(sessionId = "@alice:server.org"))
        runCurrent()
        updateWriteToFilesConfigurationResult.assertions().isCalledOnce()
        sessionStore.addSession(aSessionData(sessionId = "@bob:server.org"))
        runCurrent()
        updateWriteToFilesConfigurationResult.assertions().isCalledOnce()
    }

    @Test
    fun `foss build - when a session is added, the tracing service is not invoked`() = runTest {
        val updateWriteToFilesConfigurationResult = lambdaRecorder<WriteToFilesConfiguration, Unit> {}
        val sessionStore = InMemorySessionStore()
        createDefaultBugReporter(
            tracingService = FakeTracingService(
                updateWriteToFilesConfigurationResult = updateWriteToFilesConfigurationResult,
            ),
            sessionStore = sessionStore,
        )
        sessionStore.addSession(aSessionData(sessionId = "@alice:server.org"))
        updateWriteToFilesConfigurationResult.assertions().isNeverCalled()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when the user signs out, the tracing service is invoked`() = runTest {
        var param: WriteToFilesConfiguration? = null
        val updateWriteToFilesConfigurationResult = lambdaRecorder<WriteToFilesConfiguration, Unit> {
            param = it
        }
        val sessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData(sessionId = "@alice:server.org")),
        )
        createDefaultBugReporter(
            buildMeta = aBuildMeta(isEnterpriseBuild = true),
            tracingService = FakeTracingService(
                updateWriteToFilesConfigurationResult = updateWriteToFilesConfigurationResult,
            ),
            sessionStore = sessionStore,
        )
        sessionStore.removeSession("@alice:server.org")
        runCurrent()
        updateWriteToFilesConfigurationResult.assertions().isCalledOnce()
        assertThat(param).isNotNull()
        assertThat(param).isInstanceOf(WriteToFilesConfiguration.Disabled::class.java)
    }

    @Test
    fun `foss build - when the log directory is reset, the tracing service is not invoked`() = runTest {
        val updateWriteToFilesConfigurationResult = lambdaRecorder<WriteToFilesConfiguration, Unit> {}
        val sessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData(sessionId = "@alice:server.org")),
        )
        createDefaultBugReporter(
            tracingService = FakeTracingService(
                updateWriteToFilesConfigurationResult = updateWriteToFilesConfigurationResult,
            ),
            sessionStore = sessionStore,
        )
        sessionStore.removeSession("@alice:server.org")
        updateWriteToFilesConfigurationResult.assertions().isNeverCalled()
    }

    private fun TestScope.createDefaultBugReporter(
        buildMeta: BuildMeta = aBuildMeta(),
        sessionStore: SessionStore = InMemorySessionStore(),
        matrixClientProvider: MatrixClientProvider = FakeMatrixClientProvider(),
        crashDataStore: CrashDataStore = FakeCrashDataStore(),
        server: MockWebServer = MockWebServer(),
        tracingService: TracingService = FakeTracingService(),
        logs: FakeDiagnosticLogStore = FakeDiagnosticLogStore(),
        diagnosticProvider: DiagnosticSnapshotProvider = DiagnosticSnapshotProvider(logs),
    ): DefaultBugReporter {
        return DefaultBugReporter(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = backgroundScope,
            screenshotHolder = FakeScreenshotHolder(),
            crashDataStore = crashDataStore,
            coroutineDispatchers = testCoroutineDispatchers(),
            okHttpClient = { OkHttpClient.Builder().build() },
            userAgentProvider = DefaultUserAgentProvider(buildMeta, FakeSdkMetadata("123456789")),
            sessionStore = sessionStore,
            buildMeta = buildMeta,
            bugReporterUrlProvider = { flowOf(server.url("/")) },
            sdkMetadata = FakeSdkMetadata("123456789"),
            matrixClientProvider = matrixClientProvider,
            tracingService = tracingService,
            diagnosticSnapshots = diagnosticProvider,
        )
    }

    private suspend fun receipt(): String {
        val bundle = DiagnosticSnapshotProvider(FakeDiagnosticLogStore()).get()
        return """{"report_id":"bug-1234567890abcdef12345678","diagnostics":${bundle.manifest}}"""
    }
}
