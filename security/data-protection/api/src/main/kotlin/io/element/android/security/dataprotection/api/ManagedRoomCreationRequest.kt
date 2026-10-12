/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** User intent only. Matrix remains authoritative for the returned room and membership. */
data class ManagedRoomCreationRequest(
    val creatorUserId: String,
    val name: String,
    val topic: String?,
    val invitees: List<String>,
    val profileKind: String,
    val visibility: String,
    val joinRule: String,
    val parentRoomId: String?,
    val roomAliasName: String?,
    val avatarUrl: String?,
)
