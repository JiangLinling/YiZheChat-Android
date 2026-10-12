/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OnboardingActivationHttpClientTest {
    private fun bootstrap() = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).callTimeout(100, TimeUnit.MILLISECONDS).build()

    @Test
    fun `activation can receive a result after the fast bootstrap deadline`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("completed").setBodyDelay(250, TimeUnit.MILLISECONDS))
            server.start()
            val original = bootstrap()
            val activation = createOnboardingActivationClient(original)
            val selected = onboardingPublicPostClient("/v1/onboarding/activate", original, activation)
            selected.newCall(Request.Builder().url(server.url("/v1/onboarding/activate")).build()).execute().use {
                assertThat(it.body.string()).isEqualTo("completed")
            }
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `verification keeps its existing fast timeout`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late").setBodyDelay(250, TimeUnit.MILLISECONDS))
            server.start()
            val original = bootstrap()
            val selected = onboardingPublicPostClient("/v1/onboarding/invitations/verify", original, createOnboardingActivationClient(original))
            assertThat(selected).isSameInstanceAs(original)
            assertThrows(IOException::class.java) {
                selected.newCall(Request.Builder().url(server.url("/v1/onboarding/invitations/verify")).build()).execute().use { it.body.string() }
            }
        }
    }

    @Test
    fun `extended activation client preserves transport security and the original client`() {
        val pin = CertificatePinner.Builder().add("control-plane.test", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build()
        val original = bootstrap().newBuilder().certificatePinner(pin).build()
        val activation = createOnboardingActivationClient(original)
        assertThat(activation.certificatePinner).isSameInstanceAs(original.certificatePinner)
        assertThat(activation.sslSocketFactory).isSameInstanceAs(original.sslSocketFactory)
        assertThat(activation.hostnameVerifier).isSameInstanceAs(original.hostnameVerifier)
        assertThat(activation.interceptors).containsExactlyElementsIn(original.interceptors)
        assertThat(activation.connectTimeoutMillis).isEqualTo(original.connectTimeoutMillis)
        assertThat(activation.writeTimeoutMillis).isEqualTo(original.writeTimeoutMillis)
        assertThat(original.callTimeoutMillis).isEqualTo(100)
        listOf("/v1/onboarding/activate/", "/v1/onboarding/activate?retry=1", "/v1/device/policy", "/v1/onboarding/invitations").forEach {
            assertThat(onboardingPublicPostClient(it, original, activation)).isSameInstanceAs(original)
        }
    }
}
