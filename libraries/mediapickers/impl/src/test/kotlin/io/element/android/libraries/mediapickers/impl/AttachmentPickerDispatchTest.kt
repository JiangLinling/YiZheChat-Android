/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediapickers.api.PickerProvider
import org.junit.Test

class AttachmentPickerDispatchTest {
    @Test
    fun `production provider overrides compiled composable contract instead of inheriting the system picker fallback`() {
        // Compose adds synthetic parameters; a Kotlin source override alone does not prove JVM dispatch matches.
        val contract = PickerProvider::class.java.methods.single { it.name == "registerAttachmentPicker" }
        val implementation = DefaultPickerProvider::class.java.getMethod(contract.name, *contract.parameterTypes)
        assertThat(implementation.declaringClass).isEqualTo(DefaultPickerProvider::class.java)
    }
}
