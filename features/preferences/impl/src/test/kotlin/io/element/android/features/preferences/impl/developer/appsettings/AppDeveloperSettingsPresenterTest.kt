/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.preferences.impl.developer.appsettings

import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.preferences.impl.developer.tracing.LogLevelItem
import io.element.android.features.rageshake.api.preferences.aRageshakePreferencesState
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.featureflag.api.Feature
import io.element.android.libraries.featureflag.test.FakeFeature
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AppDeveloperSettingsPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - ensures initial states are correct`() = runTest {
        val getAvailableFeaturesResult = lambdaRecorder<Boolean, Boolean, List<Feature>> { _, _ ->
            listOf(
                FakeFeature(
                    key = "feature_1",
                    title = "Feature 1",
                    isInLabs = false,
                )
            )
        }
        val presenter = createAppDeveloperSettingsPresenter(
            featureFlagService = FakeFeatureFlagService(getAvailableFeaturesResult = getAvailableFeaturesResult),
        )
        presenter.test {
            awaitItem().also { state ->
                assertThat(state.features).isEmpty()
                assertThat(state.customElementCallBaseUrlState).isNotNull()
                assertThat(state.customElementCallBaseUrlState.baseUrl).isNull()
                assertThat(state.rageshakeState.isEnabled).isFalse()
                assertThat(state.rageshakeState.isSupported).isTrue()
                assertThat(state.rageshakeState.sensitivity).isEqualTo(0.3f)
                assertThat(state.tracingLogLevel).isEqualTo(AsyncData.Uninitialized)
            }
            awaitItem().also { state ->
                assertThat(state.features).isNotEmpty()
                assertThat(state.features).hasSize(1)
                assertThat(state.tracingLogLevel.dataOrNull()).isEqualTo(LogLevelItem.INFO)
            }
            getAvailableFeaturesResult.assertions().isCalledOnce()
                .with(value(false), value(false))
        }
    }

    @Test
    fun `present - ensures state is updated when enabled feature event is triggered`() = runTest {
        val presenter = createAppDeveloperSettingsPresenter()
        presenter.test {
            skipItems(1)
            awaitItem().also { state ->
                val feature = state.features.first { !it.isEnabled }
                state.eventSink(AppDeveloperSettingsEvent.UpdateEnabledFeature(feature, !feature.isEnabled))
            }
            awaitItem().also { state ->
                val feature = state.features.first()
                assertThat(feature.isEnabled).isTrue()
                assertThat(feature.key).isEqualTo(feature.key)
            }
        }
    }

    @Test
    fun `present - custom element call base url accepts only the current deployment endpoint`() = runTest {
        val allowedUrl = if (NextDeploymentConfig.isNextDeployment) NextDeploymentConfig.rtcBaseUrl else "https://call.element.ahoy"
        val preferencesStore = InMemoryAppPreferencesStore()
        val presenter = createAppDeveloperSettingsPresenter(preferencesStore = preferencesStore)
        presenter.test {
            skipItems(1)
            awaitItem().also { state ->
                assertThat(state.customElementCallBaseUrlState.baseUrl).isNull()
                state.eventSink(AppDeveloperSettingsEvent.SetCustomElementCallBaseUrl(allowedUrl))
            }
            awaitItem().also { state ->
                assertThat(state.customElementCallBaseUrlState.baseUrl).isEqualTo(allowedUrl)
            }
        }
    }

    @Test
    fun `present - custom element call base url validator preserves scheme and deployment isolation`() = runTest {
        val presenter = createAppDeveloperSettingsPresenter()
        presenter.test {
            skipItems(1)
            val urlValidator = awaitItem().customElementCallBaseUrlState.validator
            assertThat(urlValidator("")).isTrue() // We allow empty string to clear the value and use the default one
            assertThat(urlValidator("test")).isFalse()
            assertThat(urlValidator("http://")).isFalse()
            assertThat(urlValidator("geo://test")).isFalse()
            assertThat(urlValidator("https://call.element.io")).isEqualTo(!NextDeploymentConfig.isNextDeployment)
            if (NextDeploymentConfig.isNextDeployment) {
                assertThat(urlValidator(NextDeploymentConfig.rtcBaseUrl)).isTrue()
                assertThat(urlValidator(NextDeploymentConfig.rtcBaseUrl + "../outside")).isFalse()
            }
        }
    }

    @Test
    fun `present - changing tracing log level`() = runTest {
        val preferences = InMemoryAppPreferencesStore()
        val presenter = createAppDeveloperSettingsPresenter(preferencesStore = preferences)
        presenter.test {
            skipItems(1)
            awaitItem().also { state ->
                assertThat(state.tracingLogLevel.dataOrNull()).isEqualTo(LogLevelItem.INFO)
                state.eventSink(AppDeveloperSettingsEvent.SetTracingLogLevel(LogLevelItem.TRACE))
            }
            awaitItem().also { state ->
                assertThat(state.tracingLogLevel.dataOrNull()).isEqualTo(LogLevelItem.TRACE)
            }
        }
    }

    private fun createAppDeveloperSettingsPresenter(
        featureFlagService: FakeFeatureFlagService = FakeFeatureFlagService(
            getAvailableFeaturesResult = { _, _ ->
                listOf(
                    FakeFeature(
                        key = "feature_1",
                        title = "Feature 1",
                        isInLabs = false,
                    )
                )
            }
        ),
        preferencesStore: InMemoryAppPreferencesStore = InMemoryAppPreferencesStore(),
    ): AppDeveloperSettingsPresenter {
        return AppDeveloperSettingsPresenter(
            featureFlagService = featureFlagService,
            rageshakePresenter = { aRageshakePreferencesState() },
            appPreferencesStore = preferencesStore,
        )
    }
}
