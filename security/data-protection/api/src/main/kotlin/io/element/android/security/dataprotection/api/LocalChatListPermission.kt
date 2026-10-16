/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

data class LocalChatListPermission(
    val policyVersion: Long,
    val entryPolicyVersion: Long,
    val remainingMillis: Long,
    val accountBindings: List<OfflineEntryAccountBinding>,
)
