/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.ui

import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.features.networkmonitor.test.FakeNetworkMonitor
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import im.vector.app.features.analytics.plan.MobileScreen
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.data.WidgetMessage
import io.element.android.features.call.impl.security.CallProtectionSession
import io.element.android.features.call.impl.ui.CallScreenEvent
import io.element.android.features.call.impl.ui.CallScreenNavigator
import io.element.android.features.call.impl.ui.CallScreenPresenter
import io.element.android.features.call.impl.ui.CallScreenState
import io.element.android.features.call.impl.ui.renderCallUrl
import io.element.android.features.call.impl.ui.isPassiveCallStartupMessage
import io.element.android.features.call.impl.utils.ActiveCall
import io.element.android.features.call.impl.utils.CallState
import io.element.android.features.call.impl.utils.CallWidgetProvider
import io.element.android.features.call.impl.utils.WidgetMessageSerializer
import io.element.android.features.call.utils.FakeActiveCallManager
import io.element.android.features.call.utils.FakeCallProtectionService
import io.element.android.features.call.utils.FakeCallWidgetProvider
import io.element.android.features.call.utils.FakeWidgetMessageInterceptor
import io.element.android.libraries.androidutils.json.DefaultJsonProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.sync.FakeSyncService
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.services.analytics.api.ScreenTracker
import io.element.android.services.analytics.test.FakeScreenTracker
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallScreenPresenterTest {
    @Test
    fun `Next native signaling runs independently of the UI dispatcher`() = runTest {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler, name = "rtc-native-io")
        val dispatchers = testCoroutineDispatchers().copy(io = io)
        val fake = FakeMatrixWidgetDriver()
        var ranOnIo = false
        val driver = object : io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver by fake {
            override suspend fun run() {
                ranOnIo = kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor] === io
                fake.run()
            }
        }
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetProvider = FakeCallWidgetProvider(driver),
            dispatchers = dispatchers,
            screenTracker = FakeScreenTracker {},
        )
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(fake.runCalledCount).isEqualTo(1)
            assertThat(ranOnIo).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Next lock closes media immediately and retains departure after screen disposal`() = runTest(UnconfinedTestDispatcher()) {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val navigator = FakeCallScreenNavigator()
        val gate = CompletableDeferred<Unit>()
        var departureStarted = false
        val driver = FakeMatrixWidgetDriver(departure = { departureStarted = true; gate.await() })
        val protection = FakeCallProtectionService()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = driver,
            widgetProvider = FakeCallWidgetProvider(
                io.element.android.features.call.impl.utils.CallOwnedMatrixWidgetDriver(
                    driver, io.element.android.libraries.matrix.test.room.FakeJoinedRoom(),
                ), protectionSession = CallProtectionSession(
                AccountVaultId("departure-test"), "https://appassets.androidplatform.net", true,
            )),
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            callProtectionService = protection,
        )
        presenter.test {
            advanceTimeBy(1.seconds)
            protection.deviceAccess.value = false
            runCurrent()
            assertThat(navigator.closeCalled).isTrue()
            assertThat(departureStarted).isTrue()
            assertThat(driver.closeCalledCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
        gate.complete(Unit)
        runCurrent()
        assertThat(driver.closeCalledCount).isEqualTo(1)
    }

    @Test
    fun `Next unresponsive departure closes its native driver within 900 ms`() = runTest(UnconfinedTestDispatcher()) {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val gate = CompletableDeferred<Unit>()
        val driver = FakeMatrixWidgetDriver(departure = { gate.await() })
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false), widgetDriver = driver,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true), screenTracker = FakeScreenTracker {},
        )
        presenter.test {
            advanceTimeBy(1.seconds)
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()
        assertThat(driver.closeCalledCount).isEqualTo(0)
        advanceTimeBy(900)
        runCurrent()
        assertThat(driver.closeCalledCount).isEqualTo(1)
    }

    @Test
    fun `Next slow media record does not block renewal and is cancelled on hangup`() = runTest {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val driver = FakeMatrixWidgetDriver()
        val coordinator = FakeAppOperationalStateCoordinator()
        val recordGate = CompletableDeferred<Unit>()
        var started = 0
        var completed = false
        var cancelled = false
        val navigator = FakeCallScreenNavigator()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            navigator = navigator,
            widgetDriver = driver,
            appOperationalStateCoordinator = coordinator,
            activeCallManager = FakeActiveCallManager(mediaConnectedResult = {
                started++
                try {
                    recordGate.await()
                    completed = true
                } finally {
                    cancelled = !completed
                }
            }),
            screenTracker = FakeScreenTracker {},
        )
        val interceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            expectMostRecentItem().eventSink(CallScreenEvent.SetupMessageChannels(interceptor))
            runCurrent()
            val operation = coordinator.startedRequests.first().operationId
            val ready = """{"action":"org.yizhe.rtc.media_ready","api":"fromWidget","widgetId":"${driver.id}",
                "requestId":"ready","data":{"protocol":"next.rtc-media-ready.v1","operationId":"$operation"}}"""
            interceptor.givenInterceptedMessage(ready)
            runCurrent()
            assertThat(started).isEqualTo(1)
            val renewal = """{"action":"org.matrix.msc4157.update_delayed_event","api":"fromWidget",
                "widgetId":"${driver.id}","requestId":"renew","data":{"delay_id":"test-delay","action":"restart"}}"""
            interceptor.givenInterceptedMessage(renewal)
            runCurrent()
            assertThat(driver.sentMessages).contains(renewal)
            interceptor.givenInterceptedMessage(ready)
            runCurrent()
            assertThat(started).isEqualTo(1)
            interceptor.givenInterceptedMessage(
                """{"action":"im.vector.hangup","api":"fromWidget","widgetId":"${driver.id}","requestId":"hangup","data":{}}"""
            )
            runCurrent()
            assertThat(cancelled).isTrue()
            recordGate.complete(Unit)
            runCurrent()
            assertThat(completed).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Next accepts media readiness only from the current widget and admission`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val driver = FakeMatrixWidgetDriver()
        val coordinator = FakeAppOperationalStateCoordinator()
        var mediaReadyCount = 0
        var mediaOperation: io.element.android.security.dataprotection.api.LifecycleOperationContext? = null
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = driver,
            clock = SystemClock { testScheduler.currentTime },
            appOperationalStateCoordinator = coordinator,
            activeCallManager = FakeActiveCallManager(mediaConnectedResult = {
                mediaReadyCount++
                mediaOperation = io.element.android.security.dataprotection.api.currentLifecycleOperationContext()
            }),
            screenTracker = FakeScreenTracker {},
        )
        val interceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            expectMostRecentItem().eventSink(CallScreenEvent.SetupMessageChannels(interceptor))
            runCurrent()
            val operationId = coordinator.startedRequests.first().operationId
            interceptor.givenInterceptedMessage(
                """{"action":"content_loaded","api":"fromWidget","widgetId":"${driver.id}","requestId":"loaded","data":{}}"""
            )
            runCurrent()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isCallActive).isTrue()
            assertThat(loaded.isMediaConnected).isFalse()
            suspend fun report(widgetId: String, operation: String) {
                interceptor.givenInterceptedMessage(
                    """{"action":"org.yizhe.rtc.media_ready","api":"fromWidget","widgetId":"$widgetId",
                        "requestId":"media","data":{"protocol":"next.rtc-media-ready.v1","operationId":"$operation"}}"""
                )
                runCurrent()
            }
            report("stale-widget", operationId)
            report(driver.id, "stale-operation")
            assertThat(mediaReadyCount).isEqualTo(0)
            report(driver.id, operationId)
            assertThat(mediaReadyCount).isEqualTo(1)
            assertThat(expectMostRecentItem().isMediaConnected).isTrue()
            advanceTimeBy(5.seconds); runCurrent()
            assertThat(expectMostRecentItem().connectedSeconds).isEqualTo(5)
            report(driver.id, operationId)
            assertThat(mediaReadyCount).isEqualTo(1)
            assertThat(mediaOperation?.operationId).isEqualTo(operationId)
            assertThat(mediaOperation?.lifecycleId).isEqualTo(io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_20)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `Next URL stays unpublished until native join receipts are accepted`() = runTest(UnconfinedTestDispatcher()) {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        `pending admission permits only current widget passive negotiation`()
        val serviceGate = CompletableDeferred<Unit>()
        val frontendGate = CompletableDeferred<Unit>()
        val submissions = mutableListOf<String>()
        val admitted = kotlinx.coroutines.flow.MutableStateFlow<Map<String, LifecycleOperation>>(emptyMap())
        val backing = FakeAppOperationalStateCoordinator()
        val coordinator = object : AppOperationalStateCoordinator by backing {
            override val operations = admitted
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest): LifecycleOperation {
                val operation = backing.start(request).copy(steps = listOf(
                    io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE,
                    io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_FRONTEND,
                ).map { component -> io.element.android.security.dataprotection.api.LifecycleStep(
                    stepId = "native-${component.name}", component = component,
                    status = io.element.android.security.dataprotection.api.LifecycleStepStatus.RUNNING,
                    attemptCount = 1, updatedAtEpochMillis = 1,
                ) })
                admitted.value = mapOf(operation.operationId to operation)
                return operation
            }
            override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation {
                submissions += receipt.component.name
                if (receipt.component.name == "ANDROID_SERVICE") serviceGate.await() else frontendGate.await()
                return backing.recordClientReceipt(receipt)
            }
        }
        val driver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = driver,
            appOperationalStateCoordinator = coordinator,
            screenTracker = FakeScreenTracker {},
        )
        presenter.test {
          try {
            runCurrent()
            val waiting = expectMostRecentItem()
            assertThat(waiting.urlState).isNotInstanceOf(AsyncData.Success::class.java)
            assertThat(waiting.preparationUrl).isNotNull()
            assertThat(waiting.renderCallUrl(true)).isEqualTo(waiting.preparationUrl)
            assertThat(waiting.renderCallUrl(false)).isNull()
            assertThat(waiting.copy(isTerminating = true).renderCallUrl(true)).isNull()
            assertThat(waiting.copy(urlState = AsyncData.Failure(Exception("admission denied"))).renderCallUrl(true)).isNull()
            val interceptor = FakeWidgetMessageInterceptor()
            waiting.eventSink(CallScreenEvent.SetupMessageChannels(interceptor))
            runCurrent()
            val message = """{"api":"fromWidget","widgetId":"${driver.id}","requestId":"startup-1","action":"supported_api_versions","data":{}}"""
            interceptor.givenInterceptedMessage(message)
            runCurrent()
            assertThat(driver.sentMessages).containsExactly(message)
            assertThat(backing.clientReceipts).isEmpty()
            val loaded = """{"api":"fromWidget","widgetId":"${driver.id}","requestId":"loaded","action":"content_loaded","data":{}}"""
            interceptor.givenInterceptedMessage(loaded)
            runCurrent()
            assertThat(expectMostRecentItem().isCallActive).isFalse()
            val membership = """{"api":"fromWidget","widgetId":"${driver.id}","requestId":"membership","action":"send_event","data":{}}"""
            interceptor.givenInterceptedMessage(membership)
            runCurrent()
            assertThat(driver.sentMessages).containsExactly(message, loaded).inOrder()
            // The fake native join simulates work; reach the actual confirmation waits first.
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(submissions).containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")
            // Receipt bookkeeping does not emit a new screen state.
            expectNoEvents()
            serviceGate.complete(Unit)
            runCurrent()
            expectNoEvents()
            assertThat(driver.sentMessages).containsExactly(message, loaded).inOrder()
            frontendGate.complete(Unit)
            runCurrent()
            var ready = awaitItem()
            while (ready.urlState !is AsyncData.Success) ready = awaitItem()
            assertThat(backing.clientReceipts.map { it.component.name })
                .containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")
            assertThat(backing.startedRequests.single().trigger).isEqualTo("join")
            assertThat(driver.sentMessages).containsExactly(message, loaded, membership).inOrder()
            cancelAndIgnoreRemainingEvents()
          } finally {
            // A failed assertion must not strand the presenter's non-cancellable receipt work.
            serviceGate.complete(Unit)
            frontendGate.complete(Unit)
          }
        }
    }

    @Test
    fun `pending admission permits only current widget passive negotiation`() {
        fun request(api: String, action: String, response: String = "", id: String = "current") =
            """{"api":"$api","widgetId":"$id","action":"$action"$response}"""
        assertThat(isPassiveCallStartupMessage(request("fromWidget", "supported_api_versions"), "current")).isTrue()
        assertThat(isPassiveCallStartupMessage(request("toWidget", "capabilities", ",\"response\":{}"), "current")).isTrue()
        assertThat(isPassiveCallStartupMessage(request("fromWidget", "supported_api_versions", id = "old"), "current")).isFalse()
        assertThat(isPassiveCallStartupMessage(request("toWidget", "send_event"), "current")).isFalse()
        for (action in listOf("send_event", "get_openid", "send_to_device", "org.yizhe.rtc.media_ready", "unknown")) {
            assertThat(isPassiveCallStartupMessage(request("fromWidget", action), "current")).isFalse()
        }
        assertThat(isPassiveCallStartupMessage("not-json", "current")).isFalse()
    }

    @Test
    fun `present - with CallData sets call as active, loads URL and runs WidgetDriver`() = runTest {
        val widgetDriver = FakeMatrixWidgetDriver()
        val widgetProvider = FakeCallWidgetProvider(widgetDriver)
        val analyticsLambda = lambdaRecorder<MobileScreen.ScreenName, Unit> {}
        val joinedCallLambda = lambdaRecorder<CallData, Unit> {}
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            widgetProvider = widgetProvider,
            screenTracker = FakeScreenTracker(analyticsLambda),
            activeCallManager = FakeActiveCallManager(joinedCallResult = joinedCallLambda),
        )
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            var initialState = awaitItem()
            while (initialState.urlState !is AsyncData.Loading) initialState = awaitItem()
            assertThat(initialState.urlState).isInstanceOf(AsyncData.Loading::class.java)
            assertThat(initialState.isCallActive).isFalse()
            assertThat(widgetProvider.getWidgetCalled).isTrue()
            assertThat(widgetDriver.runCalledCount).isEqualTo(1)
            analyticsLambda.assertions().isCalledOnce().with(value(MobileScreen.ScreenName.RoomCall))

            var loadedState = awaitItem()
            while (loadedState.urlState !is AsyncData.Success) loadedState = awaitItem()
            assertThat(loadedState.urlState).isInstanceOf(AsyncData.Success::class.java)
            joinedCallLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - failed admission does not project a joined call`() = runTest(UnconfinedTestDispatcher()) {
        var joinedCount = 0
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetProvider = ControllableCallWidgetProvider {
                Result.failure(SecurityException("RTC admission denied"))
            },
            activeCallManager = FakeActiveCallManager(joinedCallResult = { joinedCount++ }),
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )

        presenter.test {
            awaitItem()
            runCurrent()

            assertThat(joinedCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - disposing suspended admission closes late driver without attaching or joining`() = runTest(UnconfinedTestDispatcher()) {
        val admissionStarted = CompletableDeferred<Unit>()
        val admissionResult = CompletableDeferred<Result<CallWidgetProvider.GetWidgetResult>>()
        val widgetDriver = FakeMatrixWidgetDriver()
        var joinedCount = 0
        var hangupCount = 0
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetProvider = ControllableCallWidgetProvider {
                admissionStarted.complete(Unit)
                withContext(NonCancellable) { admissionResult.await() }
            },
            activeCallManager = FakeActiveCallManager(
                joinedCallResult = { joinedCount++ },
                hangUpCallResult = { _, _ -> hangupCount++ },
            ),
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )
        val composition = launch {
            moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.collect {}
        }

        admissionStarted.await()
        composition.cancel()
        admissionResult.complete(
            Result.success(
                CallWidgetProvider.GetWidgetResult(
                    driver = widgetDriver,
                    url = "https://call.element.io",
                )
            )
        )
        composition.join()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertThat(joinedCount).isEqualTo(0)
        assertThat(hangupCount).isEqualTo(1)
        assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
    }

    @Test
    fun `present - successful admission joins once and explicit exit cleans up once`() = runTest(UnconfinedTestDispatcher()) {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        var joinedCount = 0
        var hangupCount = 0
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetProvider = FakeCallWidgetProvider(widgetDriver),
            widgetDriver = widgetDriver,
            navigator = navigator,
            activeCallManager = FakeActiveCallManager(
                joinedCallResult = { joinedCount++ },
                hangUpCallResult = { _, _ -> hangupCount++ },
            ),
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )

        presenter.test {
            val state = awaitItem()
            advanceTimeBy(1.seconds)
            runCurrent()
            state.eventSink(CallScreenEvent.Hangup)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(joinedCount).isEqualTo(1)
            assertThat(hangupCount).isEqualTo(1)
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            assertThat(navigator.closeCalled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - set message interceptor, send and receive messages`() = runTest {
        val widgetDriver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            screenTracker = FakeScreenTracker {},
        )
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            // Give it time to load the URL and WidgetDriver
            advanceTimeBy(1.seconds)

            val initialState = awaitItem()
            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))

            // And incoming message from the Widget Driver is passed to the WebView
            widgetDriver.givenIncomingMessage("A message")
            assertThat(messageInterceptor.sentMessages).containsExactly("A message")

            // And incoming message from the WebView is passed to the Widget Driver
            messageInterceptor.givenInterceptedMessage("A reply")
            assertThat(widgetDriver.sentMessages).containsExactly("A reply")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - host widget actions are acknowledged locally and not forwarded to Rust`() = runTest(UnconfinedTestDispatcher()) {
        val widgetDriver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )
        val serializer = WidgetMessageSerializer(DefaultJsonProvider())
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            val initialState = awaitItem()
            advanceTimeBy(1.seconds)
            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))

            listOf(
                "io.element.join",
                "set_always_on_screen",
                "io.element.device_mute",
            ).forEachIndexed { index, action ->
                messageInterceptor.givenInterceptedMessage(
                    """{"action":"$action","api":"fromWidget","widgetId":"widget","requestId":"request-$index","data":{"value":true}}"""
                )
                runCurrent()
            }

            assertThat(widgetDriver.sentMessages).isEmpty()
            assertThat(messageInterceptor.sentMessages).hasSize(3)
            messageInterceptor.sentMessages.forEachIndexed { index, rawResponse ->
                val response = serializer.deserialize(rawResponse).getOrThrow()
                assertThat(response.direction).isEqualTo(WidgetMessage.Direction.FromWidget)
                assertThat(response.widgetId).isEqualTo("widget")
                assertThat(response.requestId).isEqualTo("request-$index")
                assertThat(response.data.toString()).isEqualTo("{\"value\":true}")
                assertThat(response.response.toString()).isEqualTo("{}")
            }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - hang up event closes the screen and stops the widget driver`() = runTest(UnconfinedTestDispatcher()) {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            appOperationalStateCoordinator = lifecycleCoordinator,
        )
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            val initialState = awaitItem()

            // Give it time to load the URL and WidgetDriver
            advanceTimeBy(1.seconds)

            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))

            initialState.eventSink(CallScreenEvent.Hangup)

            // The lifecycle-wrapped hangup also waits for ActiveCallManager's asynchronous cleanup.
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            assertThat(lifecycleCoordinator.startedRequests).hasSize(2)
            if (NextDeploymentConfig.isNextDeployment) {
                assertThat(lifecycleCoordinator.startedRequests[1].operationId)
                    .isEqualTo(lifecycleCoordinator.startedRequests[0].operationId + "-hangup")
                assertThat(lifecycleCoordinator.startedRequests[1].trigger).isEqualTo("hangup")
            } else {
                assertThat(lifecycleCoordinator.startedRequests[1].operationId)
                    .isEqualTo(lifecycleCoordinator.startedRequests[0].operationId)
            }
            assertThat(lifecycleCoordinator.startedRequests[1])
                .isNotSameInstanceAs(lifecycleCoordinator.startedRequests[0])
            val hangupOperationId = lifecycleCoordinator.startedRequests[1].operationId
            assertThat(lifecycleCoordinator.clientReceipts.filter { it.operationId == hangupOperationId }.map { it.component.name })
                .containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - widget close failure still releases call and records no success receipt`() = runTest(UnconfinedTestDispatcher()) {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver(closeFailure = IllegalStateException("close failed"))
        val lifecycleCoordinator = FakeAppOperationalStateCoordinator()
        var hangupCount = 0
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            activeCallManager = FakeActiveCallManager(hangUpCallResult = { _, _ -> hangupCount++ }),
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            appOperationalStateCoordinator = lifecycleCoordinator,
        )

        presenter.test {
            val state = awaitItem()
            advanceTimeBy(1.seconds)
            runCurrent()
            state.eventSink(CallScreenEvent.Hangup)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            assertThat(hangupCount).isEqualTo(1)
            val teardownReceipts = if (NextDeploymentConfig.isNextDeployment) {
                lifecycleCoordinator.clientReceipts.filter { it.operationId.endsWith("-hangup") }
            } else {
                lifecycleCoordinator.clientReceipts
            }
            assertThat(teardownReceipts).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Next failed local cleanup cannot be receipted as an empty preparation cancellation`() = runTest(UnconfinedTestDispatcher()) {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val coordinator = FakeAppOperationalStateCoordinator()
        val manager = io.mockk.spyk(FakeActiveCallManager())
        io.mockk.coEvery { manager.prepareHangUpCall(any(), any()) } throws IllegalStateException("local cleanup failed")
        val navigator = FakeCallScreenNavigator()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            activeCallManager = manager,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            appOperationalStateCoordinator = coordinator,
        )
        presenter.test {
            val state = awaitItem()
            advanceTimeBy(1.seconds)
            runCurrent()
            state.eventSink(CallScreenEvent.Hangup)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(navigator.closeCalled).isTrue()
            assertThat(coordinator.startedRequests.any { it.trigger == "hangup" }).isTrue()
            assertThat(coordinator.clientReceipts.filter { it.operationId.endsWith("-hangup") }).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Next Vault recovery completes the same hangup without premature receipts`() = runTest(UnconfinedTestDispatcher()) {
        assumeTrue(NextDeploymentConfig.isNextDeployment)
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val protectionService = FakeCallProtectionService()
        val coordinator = FakeAppOperationalStateCoordinator()
        var writes = 0
        val session = CallProtectionSession(AccountVaultId("vault-recovery-test"), "https://appassets.androidplatform.net", true)
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            widgetProvider = FakeCallWidgetProvider(widgetDriver, protectionSession = session),
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            callProtectionService = protectionService,
            activeCallManager = FakeActiveCallManager(hangUpCallResult = { _, _ -> writes++ }),
            appOperationalStateCoordinator = coordinator,
        )
        presenter.test {
            advanceTimeBy(1.seconds)
            protectionService.deviceAccess.value = false
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            val hangup = coordinator.startedRequests.single { it.trigger == "hangup" }
            assertThat(writes).isEqualTo(0)
            assertThat(coordinator.clientReceipts.filter { it.operationId == hangup.operationId }).isEmpty()
            protectionService.deviceAccess.value = true
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(writes).isEqualTo(1)
            assertThat(coordinator.startedRequests.filter { it.trigger == "hangup" }).hasSize(1)
            assertThat(coordinator.clientReceipts.filter { it.operationId == hangup.operationId }.map { it.component.name })
                .containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - remote device restriction closes the protected call`() = runTest(UnconfinedTestDispatcher()) {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val protectionService = FakeCallProtectionService()
        val protectionSession = CallProtectionSession(
            vaultId = AccountVaultId("protected-call-vault"),
            allowedOrigin = "https://appassets.androidplatform.net",
            blockScreenCapture = true,
        )
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            widgetProvider = FakeCallWidgetProvider(widgetDriver, protectionSession = protectionSession),
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
            callProtectionService = protectionService,
        )

        presenter.test {
            advanceTimeBy(1.seconds)
            protectionService.deviceAccess.value = false
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a received close message closes the screen and stops the widget driver`() = runTest(UnconfinedTestDispatcher()) {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            val initialState = awaitItem()

            // Give it time to load the URL and WidgetDriver
            advanceTimeBy(1.seconds)

            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))

            messageInterceptor.givenInterceptedMessage("""{"action":"io.element.close","api":"fromWidget","widgetId":"1","requestId":"1"}""")

            // Let background coroutines run
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            assertThat(widgetDriver.sentMessages).isEmpty()
            assertThat(messageInterceptor.sentMessages).hasSize(1)
            val response = WidgetMessageSerializer(DefaultJsonProvider())
                .deserialize(messageInterceptor.sentMessages.single())
                .getOrThrow()
            assertThat(response.direction).isEqualTo(WidgetMessage.Direction.FromWidget)
            assertThat(response.action).isEqualTo(WidgetMessage.Action.Close)
            assertThat(response.data).isNull()
            assertThat(response.response.toString()).isEqualTo("{}")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - active DM ending remotely closes the screen and widget`() = runTest(UnconfinedTestDispatcher()) {
        val callData = CallData(A_SESSION_ID, A_ROOM_ID, true)
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val activeCallManager = FakeActiveCallManager()
        val presenter = createCallScreenPresenter(
            callData = callData,
            widgetDriver = widgetDriver,
            navigator = navigator,
            activeCallManager = activeCallManager,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )

        presenter.test {
            awaitItem()
            advanceTimeBy(1.seconds)
            activeCallManager.setActiveCall(ActiveCall(callData, CallState.InCall))
            runCurrent()
            activeCallManager.setActiveCall(null)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(widgetDriver.closeCalledCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a received 'content loaded' action makes the call to be active`() = runTest {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            var initialState = awaitItem()
            while (initialState.urlState !is AsyncData.Success) initialState = awaitItem()
            assertThat(initialState.isCallActive).isFalse()
            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))
            messageInterceptor.givenInterceptedMessage(
                """
                    {
                        "action":"content_loaded",
                        "api":"fromWidget",
                        "widgetId":"1",
                        "requestId":"1"
                    }
                """.trimIndent()
            )
            var finalState = awaitItem()
            while (!finalState.isCallActive) finalState = awaitItem()
            assertThat(finalState.isCallActive).isTrue()
        }
    }

    @Test
    fun `present - if in room mode and no join action is received an error is displayed`() = runTest {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            screenTracker = FakeScreenTracker {},
        )
        val messageInterceptor = FakeWidgetMessageInterceptor()
        presenter.test {
            advanceTimeBy(1.seconds)
            runCurrent()
            var initialState = awaitItem()
            while (initialState.urlState !is AsyncData.Success) initialState = awaitItem()
            assertThat(initialState.isCallActive).isFalse()
            initialState.eventSink(CallScreenEvent.SetupMessageChannels(messageInterceptor))

            // Wait for the timeout to trigger
            advanceTimeBy(10.seconds)
            runCurrent()

            var finalState = awaitItem()
            while (finalState.webViewError == null) finalState = awaitItem()
            assertThat(finalState.isCallActive).isFalse()
            // The error dialog that will force the user to leave the call is displayed
            assertThat(finalState.webViewError).isNotNull()
            assertThat(finalState.webViewError).isEmpty()
        }
    }

    @Test
    fun `present - automatically sets the isInCall state when starting the call and disposing the screen`() = runTest {
        val navigator = FakeCallScreenNavigator()
        val widgetDriver = FakeMatrixWidgetDriver()
        val startSyncLambda = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val syncService = FakeSyncService(SyncState.Idle).apply {
            this.startSyncLambda = startSyncLambda
        }
        val matrixClient = FakeMatrixClient(syncService = syncService)
        val appForegroundStateService = FakeAppForegroundStateService()
        val presenter = createCallScreenPresenter(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, false),
            widgetDriver = widgetDriver,
            navigator = navigator,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            matrixClientsProvider = FakeMatrixClientProvider(getClient = { Result.success(matrixClient) }),
            screenTracker = FakeScreenTracker {},
            appForegroundStateService = appForegroundStateService,
        )
        val hasRun = Mutex(true)
        val job = launch {
            moleculeFlow(RecompositionMode.Immediate) {
                presenter.present()
            }.collect {
                hasRun.unlock()
            }
        }

        appForegroundStateService.isInCall.test {
            // The initial isInCall state will always be false
            assertThat(awaitItem()).isFalse()

            // Wait until the call starts
            hasRun.lock()

            // Then it'll be true once the call is active
            assertThat(awaitItem()).isTrue()

            // If we dispose the screen
            job.cancelAndJoin()

            // The isInCall state is now false
            assertThat(awaitItem()).isFalse()

            // And there are no more events
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `offline loading waits and recovery restarts the loading timeout`() = runTest {
        val network = FakeNetworkMonitor(NetworkStatus.Disconnected)
        val presenter = createCallScreenPresenter(CallData(A_SESSION_ID, A_ROOM_ID, true),
            networkMonitor = network, screenTracker = FakeScreenTracker {})
        lateinit var latest: CallScreenState
        backgroundScope.launch {
            moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.collect { latest = it }
        }
        runCurrent()
        run {
            advanceTimeBy(1.seconds); runCurrent()
            val ready = latest
            assertThat(ready.isNetworkDisconnected).isTrue()
            ready.eventSink(CallScreenEvent.SetupMessageChannels(FakeWidgetMessageInterceptor()))
            runCurrent(); advanceTimeBy(15.seconds); runCurrent()
            assertThat(latest.webViewError).isNull()
            ready.eventSink(CallScreenEvent.OnWebViewError("offline"))
            runCurrent(); assertThat(latest.webViewError).isNull()
            network.connectivity.value = NetworkStatus.Connected
            runCurrent()
            assertThat(latest.isNetworkDisconnected).isFalse()
            advanceTimeBy(9.seconds); runCurrent(); assertThat(latest.webViewError).isNull()
            advanceTimeBy(1.seconds); runCurrent()
            assertThat(latest.webViewError).isEqualTo("")
        }
    }

    @Test
    fun `offline hangup closes once and late content loaded cannot reopen the call`() = runTest {
        val network = FakeNetworkMonitor(NetworkStatus.Disconnected)
        var closeCount = 0
        val navigator = object : CallScreenNavigator { override fun close() { closeCount++ } }
        val interceptor = FakeWidgetMessageInterceptor()
        val driver = FakeMatrixWidgetDriver()
        val presenter = createCallScreenPresenter(CallData(A_SESSION_ID, A_ROOM_ID, true),
            navigator = navigator, widgetDriver = driver, networkMonitor = network, screenTracker = FakeScreenTracker {})
        presenter.test {
            advanceTimeBy(1.seconds); runCurrent()
            val ready = expectMostRecentItem()
            ready.eventSink(CallScreenEvent.SetupMessageChannels(interceptor)); runCurrent()
            ready.eventSink(CallScreenEvent.Hangup); ready.eventSink(CallScreenEvent.Hangup); runCurrent()
            interceptor.givenInterceptedMessage(
                """{"action":"content_loaded","api":"fromWidget","widgetId":"${driver.id}","requestId":"late","data":{}}"""
            )
            runCurrent(); advanceTimeBy(15.seconds); runCurrent()
            assertThat(closeCount).isEqualTo(1)
            network.connectivity.value = NetworkStatus.Connected
            runCurrent()
            val finalState = expectMostRecentItem()
            assertThat(finalState.isCallActive).isFalse()
            assertThat(finalState.webViewError).isNull()
            // A late widget event does not publish an active call after terminal cleanup.
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.createCallScreenPresenter(
        callData: CallData,
        navigator: CallScreenNavigator = FakeCallScreenNavigator(),
        widgetDriver: FakeMatrixWidgetDriver = FakeMatrixWidgetDriver(),
        widgetProvider: CallWidgetProvider = FakeCallWidgetProvider(widgetDriver),
        dispatchers: CoroutineDispatchers = testCoroutineDispatchers(),
        matrixClientsProvider: FakeMatrixClientProvider = FakeMatrixClientProvider(),
        activeCallManager: FakeActiveCallManager = FakeActiveCallManager(),
        screenTracker: ScreenTracker = FakeScreenTracker(),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        callProtectionService: FakeCallProtectionService = FakeCallProtectionService(),
        appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor(),
        clock: SystemClock = SystemClock { 0 },
    ): CallScreenPresenter {
        val userAgentProvider = object : UserAgentProvider {
            override fun provide(): String {
                return "Test"
            }
        }
        return CallScreenPresenter(
            callData = callData,
            navigator = navigator,
            callWidgetProvider = widgetProvider,
            userAgentProvider = userAgentProvider,
            clock = clock,
            dispatchers = dispatchers,
            matrixClientsProvider = matrixClientsProvider,
            activeCallManager = activeCallManager,
            screenTracker = screenTracker,
            languageTagProvider = FakeLanguageTagProvider("en-US"),
            appForegroundStateService = appForegroundStateService,
            appCoroutineScope = backgroundScope,
            widgetMessageSerializer = WidgetMessageSerializer(DefaultJsonProvider()),
            callProtectionService = callProtectionService,
            previewAuthorization = io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate(),
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            networkMonitor = networkMonitor,
        )
    }
}

private class ControllableCallWidgetProvider(
    private val result: suspend () -> Result<CallWidgetProvider.GetWidgetResult>,
) : CallWidgetProvider {
    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        isAudioCall: Boolean,
        clientId: String,
        languageTag: String?,
        theme: String?,
        operationId: String,
        isIncoming: Boolean,
    ): Result<CallWidgetProvider.GetWidgetResult> = result()
}
