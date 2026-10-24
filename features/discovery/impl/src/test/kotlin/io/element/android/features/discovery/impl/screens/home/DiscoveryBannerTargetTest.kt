/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryBannerTargetTest {
    private fun card(type: String, value: String) = DiscoveryHomeCard("1", "Banner", "", "banners", linkType = type, linkValue = value)

    @Test fun `course custom path preserves both course and session identity`() {
        assertEquals(DiscoveryBannerTarget.Course(12, 34), card("custom_path", "/pages/courses/detail/detail?id=12&session_id=34").bannerTarget())
        assertEquals(DiscoveryBannerTarget.Course(12, null), card("course_detail", "12").bannerTarget())
    }

    @Test fun `supported content paths do not silently fall back to courses`() {
        assertEquals(DiscoveryBannerTarget.Article(7), card("path", "/pages/articles/detail/detail?id=7").bannerTarget())
        assertEquals(DiscoveryBannerTarget.Academy(8), card("path", "/pages/academy/index/index?article_id=8").bannerTarget())
        assertEquals(DiscoveryBannerTarget.Articles(true), card("courses", "/pages/articles/search/search").bannerTarget())
        assertEquals(DiscoveryBannerTarget.Personal(DiscoveryPersonalKind.HISTORY), card("path", "/pages/articles/history/history").bannerTarget())
        assertNull(card("share_event_signup", "12").bannerTarget())
    }

    @Test fun `external ambiguous and invalid identifiers cannot become native navigation`() {
        listOf(
            "https://example.test/pages/courses/index/index", "//example.test/pages/courses/index/index",
            "/pages/articles/detail/detail?id=1&id=2", "/pages/articles/detail/detail?id=0",
            "/pages/articles/detail/detail?id=-1", "/pages/articles/detail/detail?id=2147483648",
            "/pages/articles/detail/detail?id=%31", "/pages/articles/detail/detail?id=1#fragment",
            "/pages/articles/detail/detail?id=1&event_id=2", "/pages/courses/detail/detail?id=1&session_id=invalid",
            "/pages/courses/%69ndex/index", "/pages/courses/index/index?redirect=https://example.test",
        ).forEach { assertNull(it, card("path", it).bannerTarget()) }
        assertNull(card("none", "/pages/courses/index/index").bannerTarget())
    }
}
