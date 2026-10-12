/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.security

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.security.CallJoinAttempt
import org.junit.Test

class CallJoinAttemptTest {
    @Test
    fun `a new join attempt is active`() {
        assertThat(CallJoinAttempt().isActive()).isTrue()
    }

    @Test
    fun `cancelling an attempt is idempotent and fences late completion`() {
        val attempt = CallJoinAttempt()

        assertThat(attempt.cancel()).isTrue()
        assertThat(attempt.cancel()).isFalse()
        assertThat(attempt.isActive()).isFalse()
    }
}
