/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.api

data class OfflineTextOutboxMessage(
    val transactionId: String,
    val accountId: String,
    val roomId: String,
    val body: String,
    val htmlBody: String?,
    val mentionedUserIds: List<String>,
    val mentionsRoom: Boolean,
    val repliedToEventId: String?,
    val createdAtEpochMillis: Long,
)

/**
 * Device-vault encrypted queue used before KMS and Matrix accept an outbound message.
 * Entries are local to this installation and are never restored to new hardware.
 */
interface OfflineOutboxService {
    suspend fun stage(message: OfflineTextOutboxMessage): Boolean

    suspend fun messages(accountId: String, roomId: String): List<OfflineTextOutboxMessage>

    suspend fun remove(accountId: String, roomId: String, transactionId: String): Boolean
}

data object DisabledOfflineOutboxService : OfflineOutboxService {
    override suspend fun stage(message: OfflineTextOutboxMessage): Boolean = true

    override suspend fun messages(accountId: String, roomId: String): List<OfflineTextOutboxMessage> = emptyList()

    override suspend fun remove(accountId: String, roomId: String, transactionId: String): Boolean = true
}
