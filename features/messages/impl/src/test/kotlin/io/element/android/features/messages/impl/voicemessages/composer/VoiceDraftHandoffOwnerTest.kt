/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceDraftHandoffOwnerTest {
    @Test
    fun `replacing a handoff cancels the old deferred and preserves the new owner`() {
        val owner = VoiceDraftHandoffOwner()
        val old = owner.replace("voice-operation-old")

        val current = owner.replace("voice-operation-new")

        assertThat(old.deferred.isCancelled).isTrue()
        assertThat(owner.isCurrent(old)).isFalse()
        assertThat(owner.isCurrent(current)).isTrue()
        assertThat(current.generation).isGreaterThan(old.generation)
    }

    @Test
    fun `stale clear cannot clear a replacement handoff`() {
        val owner = VoiceDraftHandoffOwner()
        val old = owner.replace("voice-operation-old")
        val current = owner.replace("voice-operation-new")

        assertThat(owner.clear(old)).isFalse()
        assertThat(owner.current()).isSameInstanceAs(current)
        assertThat(owner.clear(current)).isTrue()
        assertThat(owner.current()).isNull()
    }
}
