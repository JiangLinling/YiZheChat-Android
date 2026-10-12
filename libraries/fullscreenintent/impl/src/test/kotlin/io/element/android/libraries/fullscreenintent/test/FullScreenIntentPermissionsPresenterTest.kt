/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.fullscreenintent.test

import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsEvents
import io.element.android.libraries.fullscreenintent.api.MissingCallPermission
import io.element.android.libraries.fullscreenintent.impl.BackgroundActivityPermissionService
import io.element.android.libraries.fullscreenintent.impl.FullScreenIntentPermissionsPresenter
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.push.api.notifications.CallNotificationPolicyService
import io.element.android.services.toolbox.api.intent.ExternalIntentLauncher
import io.element.android.services.toolbox.test.intent.FakeExternalIntentLauncher
import io.element.android.services.toolbox.test.sdk.FakeBuildVersionSdkIntProvider
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.datastore.preferences.core.PreferenceDataStoreFactory as AndroidPreferenceDataStoreFactory

@OptIn(ExperimentalCoroutinesApi::class)
class FullScreenIntentPermissionsPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `shouldDisplay - is true when permission is not granted and banner is not dismissed`() = runTest {
        val presenter = createPresenter(
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns false
            }
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val initialItem = awaitItem()
            assertThat(initialItem.shouldDisplayBanner).isTrue()
        }
    }

    @Test
    fun `shouldDisplay - is false if full screen and Do Not Disturb access are granted`() = runTest {
        val presenter = createPresenter(
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns true
            },
            dndAccessGranted = true,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialItem = awaitItem()
            assertThat(initialItem.shouldDisplayBanner).isFalse()
        }
    }

    @Test
    fun `shouldDisplay - requests Do Not Disturb access after full screen permission is granted`() = runTest {
        val presenter = createPresenter(
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns true
            },
            dndAccessGranted = false,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val item = awaitItem()
            assertThat(item.shouldDisplayBanner).isTrue()
            assertThat(item.missingPermission).isEqualTo(MissingCallPermission.DoNotDisturbAccess)
        }
    }

    @Test
    fun `shouldDisplay - requests background activity access on affected devices`() = runTest {
        val presenter = createPresenter(
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns true
            },
            backgroundActivityPermissionService = mockk {
                every { isPermissionGranted() } returns false
            },
            dndAccessGranted = true,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val item = awaitItem()
            assertThat(item.permissionGranted).isFalse()
            assertThat(item.shouldDisplayBanner).isTrue()
            assertThat(item.missingPermission).isEqualTo(MissingCallPermission.BackgroundActivity)
        }
    }

    @Test
    fun `dismissFullScreenIntentBanner - makes shouldDisplay false`() = runTest {
        val presenter = createPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val loadedItem = awaitItem()
            loadedItem.eventSink(FullScreenIntentPermissionsEvents.Dismiss)
            runCurrent()
            assertThat(awaitItem().shouldDisplayBanner).isFalse()
        }
    }

    @Test
    fun `openFullScreenIntentSettings - opens external screen using intent`() = runTest {
        val launchLambda = lambdaRecorder<Intent, Unit> { _ -> }
        val externalIntentLauncher = FakeExternalIntentLauncher(launchLambda)
        val presenter = createPresenter(externalIntentLauncher = externalIntentLauncher)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val loadedItem = awaitItem()
            loadedItem.eventSink(FullScreenIntentPermissionsEvents.OpenSettings)
            launchLambda.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `open Do Not Disturb settings - opens notification policy access screen`() = runTest {
        val launchLambda = lambdaRecorder<Intent, Unit> { _ -> }
        val externalIntentLauncher = FakeExternalIntentLauncher(launchLambda)
        val presenter = createPresenter(
            externalIntentLauncher = externalIntentLauncher,
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns true
            },
            dndAccessGranted = false,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val loadedItem = awaitItem()
            loadedItem.eventSink(FullScreenIntentPermissionsEvents.OpenSettings)
            launchLambda.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `open background activity settings - opens manufacturer permission screen`() = runTest {
        val launchLambda = lambdaRecorder<Intent, Unit> { _ -> }
        val permissionSettingsIntent = Intent("test.background.activity.permission")
        val presenter = createPresenter(
            externalIntentLauncher = FakeExternalIntentLauncher(launchLambda),
            notificationManagerCompat = mockk {
                every { canUseFullScreenIntent() } returns true
            },
            backgroundActivityPermissionService = mockk {
                every { isPermissionGranted() } returns false
                every { createPermissionSettingsIntent() } returns permissionSettingsIntent
            },
            dndAccessGranted = true,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val loadedItem = awaitItem()
            loadedItem.eventSink(FullScreenIntentPermissionsEvents.OpenSettings)
            launchLambda.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openFullScreenIntentSettings - does nothing in old APIs`() = runTest {
        val launchLambda = lambdaRecorder<Intent, Unit> { _ -> }
        val externalIntentLauncher = FakeExternalIntentLauncher(launchLambda)
        val presenter = createPresenter(
            buildVersionSdkIntProvider = FakeBuildVersionSdkIntProvider(Build.VERSION_CODES.Q),
            externalIntentLauncher = externalIntentLauncher,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val loadedItem = awaitItem()
            loadedItem.eventSink(FullScreenIntentPermissionsEvents.OpenSettings)
            launchLambda.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createPresenter(
        buildVersionSdkIntProvider: FakeBuildVersionSdkIntProvider = FakeBuildVersionSdkIntProvider(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        dataStoreFactory: PreferenceDataStoreFactory = fixedFilePreferenceDataStoreFactory(),
        externalIntentLauncher: ExternalIntentLauncher = FakeExternalIntentLauncher(),
        buildMeta: BuildMeta = aBuildMeta(),
        notificationManagerCompat: NotificationManagerCompat = mockk(relaxed = true),
        dndAccessGranted: Boolean = false,
        backgroundActivityPermissionService: BackgroundActivityPermissionService = mockk {
            every { isPermissionGranted() } returns true
        },
        callNotificationPolicyService: CallNotificationPolicyService = mockk(relaxed = true) {
            every { isDoNotDisturbAccessGranted() } returns dndAccessGranted
        },
    ): FullScreenIntentPermissionsPresenter {
        return FullScreenIntentPermissionsPresenter(
            buildVersionSdkIntProvider = buildVersionSdkIntProvider,
            externalIntentLauncher = externalIntentLauncher,
            buildMeta = buildMeta,
            preferencesDataStoreFactory = dataStoreFactory,
            notificationManagerCompat = notificationManagerCompat,
            callNotificationPolicyService = callNotificationPolicyService,
            backgroundActivityPermissionService = backgroundActivityPermissionService,
        )
    }

    private fun fixedFilePreferenceDataStoreFactory(): PreferenceDataStoreFactory {
        val file = File.createTempFile("full-screen-intent-test", ".preferences_pb").apply { delete() }
        val dataStore = AndroidPreferenceDataStoreFactory.create { file }
        return object : PreferenceDataStoreFactory {
            override fun create(name: String): DataStore<Preferences> = dataStore
        }
    }
}
