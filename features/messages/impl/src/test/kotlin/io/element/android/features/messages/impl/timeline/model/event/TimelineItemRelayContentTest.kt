/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineItemRelayContentTest {
    @Test
    fun `protected relay projection is parsed into card content`() {
        val result = parseProtectedRelayBody(
            body = "接龙：草莓采摘\n回复本消息即可参与；发起人回复“结束接龙”可结束\n1. 哎哎\n2. 邻家小伙\n（已结束）",
            isEdited = true,
        )

        assertThat(result?.title).isEqualTo("草莓采摘")
        assertThat(result?.entries).containsExactly("哎哎", "邻家小伙").inOrder()
        assertThat(result?.closed).isTrue()
    }

    @Test
    fun `ordinary text is not parsed as relay content`() {
        assertThat(parseProtectedRelayBody("一起去采摘", isEdited = false)).isNull()
    }
}
