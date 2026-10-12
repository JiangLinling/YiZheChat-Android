/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.mediapickers.api.RecentImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class RecentImagesRowTest {
    @Test
    fun `chat bottom sheet can query intrinsic height before measuring recent images`() = runComposeUiTest {
        setContent {
            ElementPreview {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    Layout(content = {
                        RecentImagesRow(persistentListOf(RecentImage(Uri.parse("content://media/external/images/media/1"), 0)), {})
                    }) { children, constraints ->
                        // Match ExpandableBottomSheetLayout's actual measurement sequence.
                        val height = children.single().minIntrinsicHeight(constraints.maxWidth)
                        val child = children.single().measure(Constraints.fixed(constraints.maxWidth, height))
                        layout(child.width, child.height) { child.place(0, 0) }
                    }
                }
            }
        }
        onNodeWithContentDescription("Preview recent photo 1").assertIsDisplayed()
    }

    @Test fun `horizontal strip scrolls to older photos and requests the next page`() = runComposeUiTest {
        val events = mutableListOf<MessageComposerEvent>()
        val images = (1..40).map { RecentImage(Uri.parse("content://media/external/images/media/$it"), 0) }.toImmutableList()
        setContent {
            ElementPreview {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    RecentImagesRow(images, { events += it })
                }
            }
        }
        onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Preview recent photo 40"))
        onNodeWithContentDescription("Preview recent photo 40").assertIsDisplayed()
        runOnIdle { assertThat(events).contains(MessageComposerEvent.LoadMoreRecentImages(images.last().uri)) }
    }
}
