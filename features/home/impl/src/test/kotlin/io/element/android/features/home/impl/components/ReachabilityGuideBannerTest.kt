/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReachabilityGuideBannerTest {
    @Test
    fun `requested domestic brands use the matching guide`() {
        assertThat(deviceFamily("Xiaomi", "Xiaomi")).isEqualTo(DeviceFamily.XIAOMI)
        assertThat(deviceFamily("Xiaomi", "REDMI")).isEqualTo(DeviceFamily.XIAOMI)
        assertThat(deviceFamily("POCO", "POCO")).isEqualTo(DeviceFamily.XIAOMI)
        assertThat(deviceFamily("vivo", "vivo")).isEqualTo(DeviceFamily.VIVO)
        assertThat(deviceFamily("vivo", "iQOO")).isEqualTo(DeviceFamily.VIVO)
        assertThat(deviceFamily("OPPO", "OPPO")).isEqualTo(DeviceFamily.OPPO)
        assertThat(deviceFamily("OnePlus", "OnePlus")).isEqualTo(DeviceFamily.OPPO)
        assertThat(deviceFamily("realme", "realme")).isEqualTo(DeviceFamily.REALME)
        assertThat(deviceFamily("OPPO", "realme")).isEqualTo(DeviceFamily.REALME)
        assertThat(deviceFamily("samsung", "samsung")).isEqualTo(DeviceFamily.SAMSUNG)
        assertThat(deviceFamily("motorola", "moto")).isEqualTo(DeviceFamily.MOTOROLA)
        assertThat(deviceFamily("HONOR", "HONOR")).isEqualTo(DeviceFamily.HONOR)
        assertThat(deviceFamily("HUAWEI", "HUAWEI")).isEqualTo(DeviceFamily.HUAWEI_ANDROID)
    }

    @Test
    fun `other Android brands use the common guide`() {
        assertThat(deviceFamily("Google", "Pixel")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("Sony", "Sony")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("Nothing", "Nothing")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("ASUS", "ASUS")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("meizu", "meizu")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("TECNO", "TECNO")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("Infinix", "Infinix")).isEqualTo(DeviceFamily.GENERIC)
        assertThat(deviceFamily("ZTE", "nubia")).isEqualTo(DeviceFamily.GENERIC)
    }
}
