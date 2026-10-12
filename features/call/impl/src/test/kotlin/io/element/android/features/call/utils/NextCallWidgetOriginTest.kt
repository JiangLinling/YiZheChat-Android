package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.security.EMBEDDED_CALL_WIDGET_BASE_URL
import io.element.android.features.call.impl.utils.protectedCallWidgetBaseUrl
import org.junit.Assert.assertThrows
import org.junit.Test

class NextCallWidgetOriginTest {
    @Test
    fun `Next default uses the origin accepted by call protection`() {
        assertThat(protectedCallWidgetBaseUrl(true, null)).isEqualTo(EMBEDDED_CALL_WIDGET_BASE_URL)
        assertThat(protectedCallWidgetBaseUrl(true, EMBEDDED_CALL_WIDGET_BASE_URL)).isEqualTo(EMBEDDED_CALL_WIDGET_BASE_URL)
    }

    @Test
    fun `Next refuses remote custom UI including a Next service URL`() {
        listOf("https://matrix-next.yizhesy.cn:18443/element-call", "https://matrix.yizhesy.cn/element-call").forEach { url ->
            assertThrows(IllegalStateException::class.java) { protectedCallWidgetBaseUrl(true, url) }
        }
    }

    @Test
    fun `other deployments retain their existing origin resolution`() {
        assertThat(protectedCallWidgetBaseUrl(false, null)).isEqualTo(EMBEDDED_CALL_WIDGET_BASE_URL)
        assertThat(protectedCallWidgetBaseUrl(false, "https://custom.example/ui")).isEqualTo("https://custom.example/ui")
    }
}
