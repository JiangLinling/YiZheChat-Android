/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.utils

import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiTest {
    @Test
    fun validEmojis() {
        // Simple single/multiple single-codepoint emojis per string
        assertTrue("👍".containsOnlyEmojisInternal())
        assertTrue("😀".containsOnlyEmojisInternal())
        assertTrue("🙂🙁".containsOnlyEmojisInternal())
        assertTrue("👁❤️🍝".containsOnlyEmojisInternal()) // 👁 is a pictographic
        assertTrue("👨‍👩‍👦1️⃣🚀👳🏾‍♂️🪩".containsOnlyEmojisInternal())
        assertTrue("🌍🌎🌏".containsOnlyEmojisInternal())

        // Awkward multi-codepoint graphemes
        assertTrue("🧑‍🧑‍🧒‍🧒".containsOnlyEmojisInternal())
        assertTrue("🏴‍☠".containsOnlyEmojisInternal())
        assertTrue("👩🏿‍🔧".containsOnlyEmojisInternal())

        Assert.assertFalse("".containsOnlyEmojisInternal())
        Assert.assertFalse(" ".containsOnlyEmojisInternal())
        Assert.assertFalse("🙂 🙁".containsOnlyEmojisInternal())
        Assert.assertFalse(" 🙂 🙁 ".containsOnlyEmojisInternal())
        Assert.assertFalse("Hello".containsOnlyEmojisInternal())
        Assert.assertFalse("Hello 👋".containsOnlyEmojisInternal())
    }

    @Test
    fun countsEmojiGraphemes() {
        Assert.assertEquals(1, "👍".emojiGraphemeCountInternal())
        Assert.assertEquals(2, "🙂🙁".emojiGraphemeCountInternal())
        Assert.assertEquals(5, "👨‍👩‍👦1️⃣🚀👳🏾‍♂️🪩".emojiGraphemeCountInternal())
        Assert.assertEquals(0, "plain text".emojiGraphemeCountInternal())
    }
}
