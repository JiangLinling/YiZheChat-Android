package io.element.android.appconfig

import java.net.URI

/** Build-time deployment identity; Next values are supplied only by the external Next config gate. */
object NextDeploymentConfig {
    val isNextDeployment: Boolean = BuildConfig.IS_NEXT_DEPLOYMENT
    val matrixHomeserverUrl: String = BuildConfig.YIZHECHAT_HOMESERVER_URL
    val rtcBaseUrl: String = BuildConfig.NEXT_RTC_BASE_URL
    val pushRegistrarBaseUrl: String = BuildConfig.NEXT_PUSH_REGISTRAR_BASE_URL

    /**
     * Synapse uses the registrar's private Docker name, while Android diagnostics must use
     * the public Next reverse proxy. Only rewrite the exact Next-owned internal endpoint;
     * every other deployment and gateway keeps its configured URL.
     */
    fun clientReachablePushGatewayUrl(configuredUrl: String): String {
        return resolveClientReachablePushGatewayUrl(
            configuredUrl = configuredUrl,
            isNextDeployment = isNextDeployment,
            pushRegistrarBaseUrl = pushRegistrarBaseUrl,
        )
    }
}

internal fun resolveClientReachablePushGatewayUrl(
    configuredUrl: String,
    isNextDeployment: Boolean,
    pushRegistrarBaseUrl: String,
): String {
    if (!isNextDeployment || pushRegistrarBaseUrl.isBlank()) return configuredUrl
    val uri = runCatching { URI(configuredUrl) }.getOrNull() ?: return configuredUrl
    if (
        uri.scheme != "http" ||
        uri.host != NEXT_INTERNAL_REGISTRAR_HOST ||
        uri.port != NEXT_INTERNAL_REGISTRAR_PORT ||
        uri.rawPath != MATRIX_PUSH_PATH ||
        uri.rawUserInfo != null ||
        uri.rawFragment != null ||
        uri.rawQuery !in setOf(null, COORDINATOR_QUERY)
    ) {
        return configuredUrl
    }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "${pushRegistrarBaseUrl.trimEnd('/')}$MATRIX_PUSH_PATH$query"
}

private const val NEXT_INTERNAL_REGISTRAR_HOST = "next-push-registrar"
private const val NEXT_INTERNAL_REGISTRAR_PORT = 8080
private const val MATRIX_PUSH_PATH = "/_matrix/push/v1/notify"
private const val COORDINATOR_QUERY = "provider=coordinator"
