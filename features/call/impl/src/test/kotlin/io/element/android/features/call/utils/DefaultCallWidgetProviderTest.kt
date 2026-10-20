/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.call.impl.utils.DefaultCallWidgetProvider
import io.element.android.features.call.impl.utils.CallOwnedMatrixWidgetDriver
import io.element.android.features.call.impl.utils.bindRtcOperation
import io.element.android.features.call.impl.utils.groupCallExperienceUrl
import io.element.android.features.call.impl.utils.selectCallRoomForDeployment
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.widget.FakeCallWidgetSettingsProvider
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import org.junit.Test

private const val A_OPERATION_ID = "00000000-0000-4000-8000-000000000000"

class DefaultCallWidgetProviderTest {
    @Test
    fun `contact monitor retries transport errors but emits explicit revocation once`() = runTest {
        check(NextDeploymentConfig.isNextDeployment)
        val control = io.mockk.mockk<io.element.android.security.dataprotection.api.ManagementControlService>()
        var reads = 0
        io.mockk.coEvery { control.contactCallTerminationReason(any(), any(), A_ROOM_ID.value, A_OPERATION_ID) } coAnswers {
            reads++
            if (reads == 1) throw java.io.IOException("network unavailable")
            "RTC_CALL_ENDED"
        }
        val provider = createProvider(managementControlService = control)
        val events = mutableListOf<Throwable>()
        val job = backgroundScope.launch { provider.observeContactRevocation(A_SESSION_ID, A_ROOM_ID, A_OPERATION_ID).collect { events += it } }
        runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertThat(events).isEmpty()
        advanceTimeBy(5_001); runCurrent()
        assertThat(events.map { it.message }).containsExactly("RTC_CALL_ENDED")
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun `joining from a live group banner uses its projection while retaining a fresh driver room`() = runTest {
        if (!NextDeploymentConfig.isNextDeployment) return@runTest
        val active = FakeJoinedRoom(baseRoom = FakeBaseRoom(initialRoomInfo =
            io.element.android.libraries.matrix.test.room.aRoomInfo().copy(hasRoomCall = true, isDm = false)))
        val holder = DefaultActiveRoomsHolder().apply { addRoom(active) }
        val fresh = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(initialRoomInfo = io.element.android.libraries.matrix.test.room.aRoomInfo().copy(isDm = false)),
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, fresh) }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, activeRoomsHolder = holder)
        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "zh", "dark", A_OPERATION_ID).getOrThrow()
        assertThat(result.url).contains("intent=join_existing")
        assertThat(result.url).contains("sendNotificationType=notification")
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(1)
        result.driver.close()
    }

    @Test
    fun `new group calls ring and skip lobby without one to one decline termination`() {
        val identity = "widgetId=w&parentUrl=https%3A%2F%2Fappassets.androidplatform.net&roomId=%21g%3Amatrix.example"
        val base = "https://appassets.androidplatform.net/element-call/index.html?keep=1#/?$identity&intent=start_call&skipLobby=false"
        val video = groupCallExperienceUrl(base, isAudioCall = false, joining = false)
        assertThat(video).contains(identity)
        assertThat(video).contains("?keep=1#/")
        assertThat(video).contains("skipLobby=true")
        assertThat(video).doesNotContain("skipLobby=false")
        assertThat(video).contains("sendNotificationType=ring")
        assertThat(video).contains("waitForCallPickup=false")
        assertThat(video).contains("autoLeave=false")
        assertThat(video).contains("intent=start_call")
        val audio = groupCallExperienceUrl(base, isAudioCall = true, joining = false)
        assertThat(audio).contains("intent=start_call_voice")
    }

    @Test
    fun `answering or joining a group does not ring its members again`() {
        val url = groupCallExperienceUrl("https://appassets.androidplatform.net/element-call/index.html#/?intent=start_call",
            isAudioCall = true, joining = true)
        assertThat(url).contains("sendNotificationType=notification")
        assertThat(url).doesNotContain("sendNotificationType=ring")
        assertThat(url).contains("intent=join_existing_voice")
        assertThat(url).contains("skipLobby=true")
        assertThat(groupCallExperienceUrl(url, true, true)).isEqualTo(url)
        assertThat(bindRtcOperation(url, A_OPERATION_ID)).contains("yizhe_rtc_operation_id=$A_OPERATION_ID#/")
    }

    @Test
    fun `validated Next incoming call joins even before room call projection arrives`() = runTest {
        if (!NextDeploymentConfig.isNextDeployment) return@runTest
        val intents = mutableListOf<Pair<Boolean, Boolean>>()
        val settings = FakeCallWidgetSettingsProvider { _, _, _, _, audio, active ->
            intents += audio to active
            io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings("id", true, "url")
        }
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            callWidgetSettingsProvider = settings)
        for (audio in listOf(false, true)) {
            assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, audio, "clientId", "zh", "dark", A_OPERATION_ID,
                isIncoming = true).isSuccess).isTrue()
        }
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "zh", "dark", A_OPERATION_ID).isSuccess).isTrue()
        assertThat(intents).containsExactly(false to true, true to true, false to false).inOrder()
    }

    @Test
    fun `owned room driver forwards departure before closing the room`() = runTest {
        var departures = 0
        val delegate = FakeMatrixWidgetDriver(departure = { departures++ })
        val baseRoom = FakeBaseRoom(roomId = A_ROOM_ID)
        val wrapper = CallOwnedMatrixWidgetDriver(delegate, FakeJoinedRoom(baseRoom = baseRoom))
        wrapper.stopCallMembership()
        assertThat(departures).isEqualTo(1)
        assertThat(delegate.closeCalledCount).isEqualTo(0)
        wrapper.close()
        assertThat(delegate.closeCalledCount).isEqualTo(1)
        baseRoom.assertDestroyed()
    }

    @Test
    fun `binding lifecycle operation preserves encoded Matrix widget fragment`() {
        val matrixWidgetUrl = "https://appassets.androidplatform.net/element-call/index.html" +
            "#/?widgetId=widget-1" +
            "&parentUrl=https%3A%2F%2Fappassets.androidplatform.net%2Felement-call%2Findex.html" +
            "&roomId=%21room%3Amatrix.example" +
            "&userId=%40alice%3Amatrix.example" +
            "&intent=start_call_dm_voice"

        val bound = bindRtcOperation(matrixWidgetUrl, A_OPERATION_ID)

        assertThat(bound).isEqualTo(
            "https://appassets.androidplatform.net/element-call/index.html" +
                "?yizhe_rtc_operation_id=$A_OPERATION_ID" +
                "#/?widgetId=widget-1" +
                "&parentUrl=https%3A%2F%2Fappassets.androidplatform.net%2Felement-call%2Findex.html" +
                "&roomId=%21room%3Amatrix.example" +
                "&userId=%40alice%3Amatrix.example" +
                "&intent=start_call_dm_voice"
        )
    }

    @Test
    fun `binding lifecycle operation handles an existing empty query`() {
        val matrixWidgetUrl = "https://appassets.androidplatform.net/element-call/index.html?#/?widgetId=widget-1"

        val bound = bindRtcOperation(matrixWidgetUrl, A_OPERATION_ID)

        assertThat(bound).isEqualTo(
            "https://appassets.androidplatform.net/element-call/index.html" +
                "?yizhe_rtc_operation_id=$A_OPERATION_ID" +
                "#/?widgetId=widget-1"
        )
    }

    @Test
    fun `getWidget - fails if the session does not exist`() = runTest {
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.failure(Exception("Session not found")) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme", A_OPERATION_ID).isFailure).isTrue()
    }

    @Test
    fun `Next room selection fails closed before room lookup if homeserver capabilities cannot be discovered`() = runTest {
        val client = FakeMatrixClient(
            ensureServerVersionsReadyLambda = { Result.failure(Exception("versions unavailable")) },
        ).apply {
            givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom())
        }

        val result = runCatching {
            selectCallRoomForDeployment(
                isNextDeployment = true,
                matrixClient = client,
                activeRoomsHolder = DefaultActiveRoomsHolder(),
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
            )
        }

        assertThat(result.isFailure).isTrue()
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
    }

    @Test
    fun `getWidget - fails if the room does not exist`() = runTest {
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, null)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, true, "clientId", "languageTag", "theme", A_OPERATION_ID).isFailure).isTrue()
    }

    @Test
    fun `getWidget - fails if it can't generate the URL for the widget`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.failure(Exception("Can't generate URL for widget")) }
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme", A_OPERATION_ID).isFailure).isTrue()
    }

    @Test
    fun `getWidget - fails if it can't get the widget driver`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html?room=one#call") },
            getWidgetDriverResult = { Result.failure(Exception("Can't get a widget driver")) }
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme", A_OPERATION_ID).isFailure).isTrue()
    }

    @Test
    fun `getWidget - returns a widget driver when all steps are successful`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html?room=one#call") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme", A_OPERATION_ID).getOrThrow()
        assertThat(result.url).isEqualTo(
            "https://appassets.androidplatform.net/element-call/index.html?room=one&yizhe_rtc_operation_id=$A_OPERATION_ID#call" +
                if (NextDeploymentConfig.isNextDeployment) "?skipLobby=true&returnToLobby=false&sendNotificationType=ring" +
                    "&waitForCallPickup=false&autoLeave=false&intent=start_call" else ""
        )
    }

    @Test
    fun `Next room selection never falls back to an active room when the restored client has no room`() = runTest {
        val client = FakeMatrixClient().apply {
            // No room from the client
            givenGetRoomResult(A_ROOM_ID, null)
        }
        val activeRoomsHolder = DefaultActiveRoomsHolder().apply {
            // A current active room with the same room id
            addRoom(
                FakeJoinedRoom(
                    baseRoom = FakeBaseRoom(roomId = A_ROOM_ID),
                    generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html") },
                    getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
                )
            )
        }
        val result = selectCallRoomForDeployment(
            isNextDeployment = true,
            matrixClient = client,
            activeRoomsHolder = activeRoomsHolder,
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `Next room selection returns an owned room from the restored client`() = runTest {
        val clientDriver = FakeMatrixWidgetDriver()
        val clientRoomBase = FakeBaseRoom(roomId = A_ROOM_ID)
        val clientRoom = FakeJoinedRoom(
            baseRoom = clientRoomBase,
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html") },
            getWidgetDriverResult = { Result.success(clientDriver) },
        )
        val activeDriver = FakeMatrixWidgetDriver()
        val activeRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(roomId = A_ROOM_ID),
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://active.example.invalid") },
            getWidgetDriverResult = { Result.success(activeDriver) },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, clientRoom) }
        val activeRoomsHolder = DefaultActiveRoomsHolder().apply { addRoom(activeRoom) }
        val result = selectCallRoomForDeployment(
            isNextDeployment = true,
            matrixClient = client,
            activeRoomsHolder = activeRoomsHolder,
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
        )

        assertThat(result?.room).isSameInstanceAs(clientRoom)
        assertThat(result?.ownedByCall).isTrue()
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(1)
    }

    @Test
    fun `getWidget - destroys an acquired client room when initialization fails`() = runTest {
        if (!NextDeploymentConfig.isNextDeployment) return@runTest
        val roomBase = FakeBaseRoom(roomId = A_ROOM_ID)
        val room = FakeJoinedRoom(
            baseRoom = roomBase,
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.failure(Exception("URL failure")) },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })

        val result = provider.getWidget(
            A_SESSION_ID,
            A_ROOM_ID,
            false,
            "clientId",
            "languageTag",
            "theme",
            A_OPERATION_ID,
        )

        assertThat(result.isFailure).isTrue()
        roomBase.assertDestroyed()
    }

    @Test
    fun `getWidget - rejects a custom base url when protected calls are required`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("https://appassets.androidplatform.net/element-call/index.html") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val preferencesStore = InMemoryAppPreferencesStore().apply {
            setCustomElementCallBaseUrl("https://custom.element.io")
        }
        val settingsProvider = FakeCallWidgetSettingsProvider()
        val provider = createProvider(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            callWidgetSettingsProvider = settingsProvider,
            appPreferencesStore = preferencesStore,
            callProtectionService = FakeCallProtectionService { request ->
                if (request.widgetBaseUrl != "https://appassets.androidplatform.net/element-call/index.html") {
                    throw SecurityException("Untrusted call origin")
                }
                error("Unexpected trusted origin")
            },
        )
        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme", A_OPERATION_ID)

        assertThat(result.isFailure).isTrue()
        assertThat(settingsProvider.providedBaseUrls).isEmpty()
    }

    private fun createProvider(
        matrixClientProvider: MatrixClientProvider = FakeMatrixClientProvider(),
        appPreferencesStore: AppPreferencesStore = InMemoryAppPreferencesStore(),
        callWidgetSettingsProvider: CallWidgetSettingsProvider = FakeCallWidgetSettingsProvider(),
        activeRoomsHolder: ActiveRoomsHolder = DefaultActiveRoomsHolder(),
        callProtectionService: FakeCallProtectionService = FakeCallProtectionService(),
        managementControlService: io.element.android.security.dataprotection.api.ManagementControlService = io.mockk.mockk(relaxed = true),
    ) = DefaultCallWidgetProvider(
        matrixClientsProvider = matrixClientProvider,
        appPreferencesStore = appPreferencesStore,
        callWidgetSettingsProvider = callWidgetSettingsProvider,
        activeRoomsHolder = activeRoomsHolder,
        callProtectionService = callProtectionService,
        managementControlService = managementControlService,
    )

    @Test
    fun `blocked contact never prepares widget or room`() = runTest {
        check(NextDeploymentConfig.isNextDeployment)
        val client = FakeMatrixClient()
        val control = io.mockk.mockk<io.element.android.security.dataprotection.api.ManagementControlService>()
        io.mockk.coEvery { control.requireContactRoomAllowed(any(), any(), A_ROOM_ID.value) } throws IllegalStateException("CONTACT_UNAVAILABLE")
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, managementControlService = control)
        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "zh", "dark", A_OPERATION_ID)
        assertThat(result.exceptionOrNull()?.message).isEqualTo("CONTACT_UNAVAILABLE")
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
    }
}
