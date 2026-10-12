/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtectedReceiveIdentityTest {
    @Test
    fun `transaction local echo and summary surrogates are never Matrix receive identities`() {
        listOf("", "txn-qa", "room-summary:1000:123", "unique-id", "$", "\$event with space", "\$" + "x".repeat(512)).forEach {
            assertThat(isCanonicalProtectedEventIdentity(it)).isFalse()
        }
        assertThat(isCanonicalProtectedEventIdentity("\$opaque-matrix-event")).isTrue()
    }
}
