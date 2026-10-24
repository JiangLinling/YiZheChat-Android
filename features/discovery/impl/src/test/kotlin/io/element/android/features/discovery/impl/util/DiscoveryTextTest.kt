/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.util

import io.element.android.features.discovery.impl.screens.member.*
import io.element.android.features.discovery.impl.screens.services.feedback.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryTextTest {
    private val supplementary = String(Character.toChars(0x20000))

    @Test fun `profile accepts 120 supplementary characters and rejects 121 without truncation`() {
        val target = PersonEditTarget(PersonEditKind.NEW_CHILD, null)
        val today = LocalDate.of(2026, 10, 24)
        val draft = MemberPersonDraft(fullName = supplementary.repeat(120))
        assertTrue(draft.issues(target, today).isEmpty())
        assertEquals(draft, parsePersonForm(personForm(draft, true), true, today))
        assertTrue(PersonEditIssue.LENGTH in draft.copy(fullName = supplementary.repeat(121)).issues(target, today))
    }

    @Test fun `feedback allows 300 scalar values including recommendation zero`() {
        val draft = FeedbackDraft(5, 5, 5, 5, 0, supplementary.repeat(300))
        assertTrue(draft.issues().isEmpty())
        assertTrue(FeedbackIssue.GAIN in draft.copy(gain = supplementary.repeat(301)).issues())
        assertTrue(FeedbackIssue.IMPROVEMENT in draft.copy(improvement = supplementary.repeat(301)).issues())
    }

    @Test fun `unpaired surrogates cannot enter profile feedback or wire text`() {
        for (invalid in listOf("\uD800", "\uDC00", "ok\uD800x", "\uD800\uD800")) {
            assertNull(invalid.discoveryCodePointCount())
            assertFalse(invalid.discoveryTextFits(300))
            assertTrue(FeedbackIssue.GAIN in FeedbackDraft(gain = invalid).issues())
        }
        assertEquals(1, supplementary.discoveryCodePointCount())
    }
}
