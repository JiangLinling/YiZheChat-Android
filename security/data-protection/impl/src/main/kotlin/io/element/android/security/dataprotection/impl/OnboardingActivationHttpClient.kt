/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun createOnboardingActivationClient(bootstrapClient: OkHttpClient): OkHttpClient = bootstrapClient.newBuilder()
    // Activation waits for real Matrix membership fences and invitation consumption.
    // Preserve TLS, pins and interceptors while allowing this durable mutation to finish.
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(75, TimeUnit.SECONDS)
    .build()

internal fun onboardingPublicPostClient(
    path: String,
    bootstrapClient: OkHttpClient,
    activationClient: OkHttpClient,
): OkHttpClient = if (path == "/v1/onboarding/activate") activationClient else bootstrapClient
