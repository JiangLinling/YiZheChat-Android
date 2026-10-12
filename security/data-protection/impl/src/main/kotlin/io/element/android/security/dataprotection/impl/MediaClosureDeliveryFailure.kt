/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

/** Safe transport diagnostics: no response body, URL, subject, key or credential. */
internal class MediaClosureDeliveryFailure(
    val purpose: String,
    val reasonCode: String,
    val httpStatusCode: Int? = null,
) : Exception("Media closure delivery pending: $purpose/$reasonCode") {
    init {
        require(purpose in setOf("MEDIA_RESOURCE_RESERVATION_ACK", "MEDIA_RESOURCE_SEAL_ACK", "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK"))
        require(reasonCode.matches(Regex("[A-Z0-9_]{1,64}")))
        require(httpStatusCode == null || httpStatusCode in 100..599)
    }
}
