/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutViewTest {
    @Test
    fun `clicking on back invokes back callback`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnce { callback ->
            setAboutView(
                anAboutState(),
                onBackClick = callback,
            )
            pressBack()
        }
    }

    @Test
    fun `privacy opens local text and back returns to about`() = runAndroidComposeUiTest<ComponentActivity> {
        setAboutView(anAboutState())
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_privacy_title))
            .performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_legal_scope)).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_notices)).assertDoesNotExist()
        pressBack()
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_about_title)).assertExists()
    }

    @Test
    fun `about has three notices and no standalone licence entry`() = runAndroidComposeUiTest<ComponentActivity> {
        setAboutView(anAboutState())
        getAllLegals().forEach { onNodeWithText(activity!!.getString(it.titleRes)).assertExists() }
        onNodeWithText(activity!!.getString(CommonStrings.common_open_source_licenses)).assertDoesNotExist()
    }

    @Test
    fun `download progress is shown and survives a notice round trip`() = runAndroidComposeUiTest<ComponentActivity> {
        setAboutView(anAboutState().copy(
            preparedUpdate = AsyncData.Loading(),
            downloadProgress = AppUpdateDownloadProgress(68L, 100L),
        ))
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.68f, 0f..1f))).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_usage_title))
            .performScrollTo().assertIsDisplayed().performClick()
        pressBack()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.68f, 0f..1f))).assertExists()
    }

    @Test
    fun `unknown size is downloading not verification`() = runAndroidComposeUiTest<ComponentActivity> {
        setAboutView(anAboutState().copy(
            preparedUpdate = AsyncData.Loading(),
            downloadProgress = AppUpdateDownloadProgress(128L, -1L),
        ))
        onNodeWithText(activity!!.getString(R.string.screen_yizhe_downloading)).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_about_verifying_update)).assertDoesNotExist()
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setAboutView(
    state: AboutState,
    onInstallUpdate: () -> Unit = EnsureNeverCalled(),
    onBackClick: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        AboutView(
            state = state,
            onInstallUpdate = onInstallUpdate,
            onBackClick = onBackClick,
        )
    }
}
