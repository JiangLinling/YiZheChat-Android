package io.element.android.appconfig

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PushGatewayUrlTest {
    @Test
    fun `formal private registrar maps to the normal public authority`() {
        assertThat(resolveClientReachablePushGatewayUrl(
            "http://push-registrar:8080/_matrix/push/v1/notify?provider=coordinator",
            true,
            "https://security.yizhesy.cn/push/registrar",
        )).isEqualTo("https://security.yizhesy.cn/push/registrar/_matrix/push/v1/notify?provider=coordinator")
    }

    @Test
    fun `Next maps only its exact server-internal coordinator endpoint`() {
        val result = resolveClientReachablePushGatewayUrl(
            configuredUrl = "http://next-push-registrar:8080/_matrix/push/v1/notify?provider=coordinator",
            isNextDeployment = true,
            pushRegistrarBaseUrl = "https://next.example.org/push/registrar",
        )

        assertThat(result).isEqualTo(
            "https://next.example.org/push/registrar/_matrix/push/v1/notify?provider=coordinator"
        )
    }

    @Test
    fun `Stable never maps an internal-looking endpoint`() {
        val configured = "http://next-push-registrar:8080/_matrix/push/v1/notify?provider=coordinator"

        val result = resolveClientReachablePushGatewayUrl(
            configuredUrl = configured,
            isNextDeployment = false,
            pushRegistrarBaseUrl = "https://next.example.org/push/registrar",
        )

        assertThat(result).isEqualTo(configured)
    }

    @Test
    fun `Next refuses ambiguous internal endpoints`() {
        val endpoints = listOf(
            "http://user@next-push-registrar:8080/_matrix/push/v1/notify?provider=coordinator",
            "http://next-push-registrar:8080/_matrix/push/v1/notify?provider=other",
            "http://next-push-registrar:8080/_matrix/push/v1/notify?provider=coordinator#fragment",
            "http://next-push-registrar:8080/%5fmatrix/push/v1/notify?provider=coordinator",
        )

        endpoints.forEach { configured ->
            assertThat(
                resolveClientReachablePushGatewayUrl(
                    configuredUrl = configured,
                    isNextDeployment = true,
                    pushRegistrarBaseUrl = "https://next.example.org/push/registrar",
                )
            ).isEqualTo(configured)
        }
    }
}
