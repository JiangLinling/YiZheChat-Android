/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetEventFilter

class ElementCallWidgetCapabilitiesTest {
    @Test
    fun `Next capabilities add the MSC4143 RTC membership type and sticky delivery`() {
        val existingRead = WidgetEventFilter.StateWithType("m.room.encryption")
        val existingSend = WidgetEventFilter.MessageLikeWithType("m.call.notify")
        val base = WidgetCapabilities(
            read = listOf(existingRead),
            send = listOf(existingSend),
            requiresClient = true,
            updateDelayedEvent = true,
            sendDelayedEvent = true,
            downloadFiles = true,
            sendStickyEvent = false,
            receiveStickyEvent = false,
        )
        val actual = addNextRtcMembershipCapability(base)
        val rtcMember = WidgetEventFilter.MessageLikeWithType(NEXT_RTC_MEMBER_EVENT_TYPE)

        assertThat(actual.read).containsExactlyElementsIn(base.read + rtcMember)
        assertThat(actual.send).containsExactlyElementsIn(base.send + rtcMember)
        assertThat(actual.requiresClient).isEqualTo(base.requiresClient)
        assertThat(actual.updateDelayedEvent).isEqualTo(base.updateDelayedEvent)
        assertThat(actual.sendDelayedEvent).isEqualTo(base.sendDelayedEvent)
        assertThat(actual.downloadFiles).isEqualTo(base.downloadFiles)
        assertThat(actual.sendStickyEvent).isTrue()
        assertThat(actual.receiveStickyEvent).isTrue()
    }

    @Test
    fun `Stable capabilities remain exactly the SDK capabilities`() {
        val base = capabilitiesWithAllFeatureGates(false)

        val actual = capabilitiesForDeployment(base, isNextDeployment = false)

        assertThat(actual).isSameInstanceAs(base)
    }

    @Test
    fun `Next capabilities preserve unrelated disabled feature gates and enable sticky delivery`() {
        val base = capabilitiesWithAllFeatureGates(false)

        val actual = capabilitiesForDeployment(base, isNextDeployment = true)

        assertThat(actual.requiresClient).isFalse()
        assertThat(actual.updateDelayedEvent).isFalse()
        assertThat(actual.sendDelayedEvent).isFalse()
        assertThat(actual.downloadFiles).isFalse()
        assertThat(actual.sendStickyEvent).isTrue()
        assertThat(actual.receiveStickyEvent).isTrue()
    }

    @Test
    fun `Next capability extension is idempotent`() {
        val base = capabilitiesWithAllFeatureGates(true)

        val actual = addNextRtcMembershipCapability(addNextRtcMembershipCapability(base))
        val rtcMember = WidgetEventFilter.MessageLikeWithType(NEXT_RTC_MEMBER_EVENT_TYPE)

        assertThat(actual.read.count { it == rtcMember }).isEqualTo(1)
        assertThat(actual.send.count { it == rtcMember }).isEqualTo(1)
    }

    private fun capabilitiesWithAllFeatureGates(enabled: Boolean): WidgetCapabilities = WidgetCapabilities(
        read = listOf(WidgetEventFilter.StateWithType("m.room.encryption")),
        send = listOf(WidgetEventFilter.MessageLikeWithType("m.call.notify")),
        requiresClient = enabled,
        updateDelayedEvent = enabled,
        sendDelayedEvent = enabled,
        downloadFiles = enabled,
        sendStickyEvent = enabled,
        receiveStickyEvent = enabled,
    )
}
