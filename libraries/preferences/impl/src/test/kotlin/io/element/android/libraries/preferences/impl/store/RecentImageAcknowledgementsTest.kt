/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.preferences.impl.store

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.preferences.api.store.RecentImageAcknowledgements as Acknowledgements
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecentImageAcknowledgementsTest {
    @Test fun `same batch is suppressed and older images never cascade`() {
        val batch = listOf("newest", "older")
        val records = Acknowledgements.acknowledge(emptySet(), batch, 1_000L)
        assertThat(Acknowledgements.claimable(batch, records)).isEmpty()
        assertThat(Acknowledgements.claimable(listOf("newest", "unseen-old"), records)).isEmpty()
        assertThat(Acknowledgements.claimable(listOf("next", "newest"), records)).containsExactly("next")
    }

    @Test fun `expired and future records are removed and metadata is bounded`() {
        assertThat(Acknowledgements.retain(setOf("100:a", "500000:b", "120101:c"), 120101L)).containsExactly("120101:c")
        val records = Acknowledgements.acknowledge(emptySet(), (1..100).map { "image-$it" }, 1_000L)
        assertThat(records).hasSize(64)
        assertThat(records.joinToString()).doesNotContain("image-")
    }

    @Test fun `selection suppresses recommendation and another account remains independent`() = runTest {
        val first = InMemorySessionPreferencesStore()
        val second = InMemorySessionPreferencesStore()
        first.acknowledgeSelectedImages(listOf("uri"), 1_000L)
        assertThat(first.claimRecentImageSuggestions(listOf("uri"), 1_001L)).isEmpty()
        assertThat(second.claimRecentImageSuggestions(listOf("uri"), 1_001L)).containsExactly("uri")
        first.clear()
        assertThat(first.claimRecentImageSuggestions(listOf("uri"), 1_002L)).containsExactly("uri")
    }

    @Test fun `one suggestion batch contains at most ten distinct images`() {
        assertThat(Acknowledgements.claimable((1..20).map { "image-$it" }, emptySet())).hasSize(10)
        assertThat(Acknowledgements.claimable(listOf("same", "same"), emptySet())).containsExactly("same")
    }
}
