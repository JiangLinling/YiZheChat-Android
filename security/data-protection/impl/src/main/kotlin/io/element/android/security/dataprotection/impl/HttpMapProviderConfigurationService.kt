/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.dataprotection.api.MapProviderConfiguration
import io.element.android.security.dataprotection.api.MapProviderConfigurationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpMapProviderConfigurationService(
    private val configuration: ManagementControlConfiguration,
    private val verifier: ManagementSignatureVerifier,
) : MapProviderConfigurationService {
    override suspend fun fetch(): Result<MapProviderConfiguration> = withContext(Dispatchers.IO) {
        runCatchingExceptions {
            if (!configuration.isConfigured) return@runCatchingExceptions MapProviderConfiguration()
            val base = URI(configuration.baseUrl)
            val client = OkHttpClient.Builder()
                .certificatePinner(CertificatePinner.Builder().add(requireNotNull(base.host), configuration.certificatePin).build())
                .followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(configuration.baseUrl + "/v1/public/map-provider").build()).execute().use { response ->
                if (response.code == 404) return@runCatchingExceptions MapProviderConfiguration()
                check(response.isSuccessful)
                val envelope = Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
                val payload = requireNotNull(envelope["config"]).jsonObject
                check(verifier.verifyDetached(
                    payload,
                    requireNotNull(envelope["key_id"]).jsonPrimitive.content,
                    requireNotNull(envelope["signature_b64"]).jsonPrimitive.content
                ))
                val now = System.currentTimeMillis()
                check(requireNotNull(payload["schema_version"]).jsonPrimitive.long == 1L)
                check(requireNotNull(payload["issued_at_ms"]).jsonPrimitive.long <= now + 60_000)
                check(requireNotNull(payload["expires_at_ms"]).jsonPrimitive.long in (now + 1)..(now + 6 * 60_000))
                val provider = requireNotNull(payload["provider"]).jsonPrimitive.content
                check(provider in listOf("legacy", "amap"))
                val key = requireNotNull(payload["js_key"]).jsonPrimitive.content
                val host = requireNotNull(payload["service_host"]).jsonPrimitive.content
                if (provider == "amap") {
                    check(key.matches(Regex("[a-zA-Z0-9]{16,128}")))
                    check(host == configuration.baseUrl + "/v1/location/_AMapService")
                }
                MapProviderConfiguration(provider, key, host)
            }
        }
    }
}
