/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.draft

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposerDraftMapperTest {
    @Test
    fun `caption draft fails closed before reaching Matrix SDK`() {
        val draft = ComposerDraft("caption", null, ComposerDraftType.EditCaption(EventId("\$event")))

        val failure = assertThrows(IllegalStateException::class.java) { draft.into() }

        assertThat(failure).hasMessageThat().contains("protected local draft store")
    }

    @Test
    fun `legacy message edit mapping remains compatible`() {
        val target = EventId("\$message-event")
        val draft = ComposerDraft("plain", null, ComposerDraftType.Edit(target))

        assertThat(draft.into().into()).isEqualTo(draft)
    }
}
