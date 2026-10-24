/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

data class DiscoveryHomeCard(val id: String, val title: String, val subtitle: String, val kind: String,
    val targetArticleId: Long? = null, val targetCourseId: Long? = null, val targetSessionId: Long? = null,
    val linkType: String = "", val linkValue: String = "")
data class DiscoveryHomeContent(
    val banners: List<DiscoveryHomeCard>,
    val academy: List<DiscoveryHomeCard>,
    val sessions: List<DiscoveryHomeCard>,
    val news: List<DiscoveryHomeCard>,
)
data class DiscoveryHomeState(val loading: Boolean = true, val failed: Boolean = false, val content: DiscoveryHomeContent? = null,
    val eventSink: (DiscoveryHomeEvent) -> Unit)
sealed interface DiscoveryHomeEvent { data object Reload : DiscoveryHomeEvent }
