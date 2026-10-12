/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.share

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.bumble.appyx.core.modality.BuildContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.impl.common.actions.FakeLocationActions
import io.element.android.features.location.impl.common.permissions.FakePermissionsPresenter
import io.element.android.features.location.impl.live.LiveLocationStore
import io.element.android.features.location.test.FakeActiveLiveLocationShareManager
import io.element.android.features.messages.test.FakeMessageComposerContext
import io.element.android.libraries.dateformatter.test.FakeDurationFormatter
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.preferences.test.FakePreferenceDataStoreFactory
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.node.TestParentNode
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DefaultShareLocationEntryPointTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `test node builder`() = runTest {
        val entryPoint = DefaultShareLocationEntryPoint()
        val parentNode = TestParentNode.create { buildContext, plugins ->
            val room = FakeJoinedRoom()
            ShareLocationNode(
                buildContext = buildContext,
                plugins = plugins,
                presenterFactory = { timelineMode: Timeline.Mode, burnAfterRead: Boolean ->
                    ShareLocationPresenter(
                        permissionsPresenterFactory = { FakePermissionsPresenter() },
                        room = room,
                        timelineMode = timelineMode,
                        burnAfterRead = burnAfterRead,
                        analyticsService = FakeAnalyticsService(),
                        messageComposerContext = FakeMessageComposerContext(),
                        locationActions = FakeLocationActions(),
                        buildMeta = aBuildMeta(),
                        client = FakeMatrixClient(),
                        durationFormatter = FakeDurationFormatter(),
                        liveLocationShareManager = FakeActiveLiveLocationShareManager(),
                        liveLocationStore = LiveLocationStore(
                            preferenceDataStoreFactory = FakePreferenceDataStoreFactory(),
                            sessionId = room.sessionId,
                        ),
                        appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        mapProviderConfigurationService = object : io.element.android.security.dataprotection.api.MapProviderConfigurationService {
            override suspend fun fetch() = Result.success(io.element.android.security.dataprotection.api.MapProviderConfiguration())
        },
                    )
                },
                analyticsService = FakeAnalyticsService(),
            )
        }
        val timelineMode = Timeline.Mode.Live
        val result = entryPoint.createNode(
            parentNode = parentNode,
            buildContext = BuildContext.root(null),
            timelineMode = timelineMode,
            burnAfterRead = true,
        )
        assertThat(result).isInstanceOf(ShareLocationNode::class.java)
        assertThat(result.plugins).contains(ShareLocationNode.Inputs(timelineMode, burnAfterRead = true))
    }
}
