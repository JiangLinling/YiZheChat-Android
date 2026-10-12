/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.RedactionReasonResolver
import io.element.android.security.contentprotection.api.TrustedRedactionReason

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultRedactionReasonResolver(
    private val kmsShareClient: KmsShareClient,
) : RedactionReasonResolver {
    override suspend fun resolve(eventIdSha256: Set<String>): Map<String, TrustedRedactionReason> =
        kmsShareClient.resolveRedactionReasons(eventIdSha256)
}
