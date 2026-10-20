/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.impl

import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.core.RoomId
import java.util.Base64

internal object InviteNotificationCodec {
    const val MAX_RECORDS = 200
    // Joining can briefly clear the SDK DM flags. Preserve an already observed invitation type.
    fun refinedKind(previous: InviteNotification.Kind, observed: InviteNotification.Kind): InviteNotification.Kind =
        if (previous == InviteNotification.Kind.GROUP) observed else previous
    // This payload is encrypted by the account SensitiveFileStore, never written to preferences.
    fun encode(value: InviteNotification): String = listOf(
        value.roomId.value, value.kind.name, value.senderId.orEmpty(), value.timestamp.toString(),
        value.outgoing.toString(), value.outcome.name, value.handledAt.toString(), value.seen.toString(),
        value.sequence.toString(), value.active.toString(), value.receivedAt.toString(),
        Base64.getEncoder().encodeToString(value.displayName.orEmpty().encodeToByteArray()),
        Base64.getEncoder().encodeToString(value.avatarUrl.orEmpty().encodeToByteArray()),
    ).joinToString("\t")

    fun decode(value: String): InviteNotification? = runCatching {
        val fields = value.split('\t')
        require(fields.size in listOf(8, 13) && fields[0].startsWith("!") && fields[0].contains(':'))
        InviteNotification(
            RoomId(fields[0]), InviteNotification.Kind.valueOf(fields[1]), fields[2].ifEmpty { null },
            fields[3].toLong(), fields[4].toBooleanStrict(), InviteNotification.Outcome.valueOf(fields[5]),
            fields[6].toLong(), fields[7].toBooleanStrict(),
            sequence = fields.getOrNull(8)?.toLong() ?: 0,
            active = fields.getOrNull(9)?.toBooleanStrict() ?: true,
            receivedAt = fields.getOrNull(10)?.toLong() ?: 0,
            displayName = fields.getOrNull(11)?.let { Base64.getDecoder().decode(it).decodeToString().ifEmpty { null } },
            avatarUrl = fields.getOrNull(12)?.let { Base64.getDecoder().decode(it).decodeToString().ifEmpty { null } },
        )
    }.getOrNull()

    fun confirmOutcome(
        previous: InviteNotification,
        outcome: InviteNotification.Outcome,
        handledAt: Long,
        seen: Boolean,
    ): InviteNotification = when {
        previous.outcome == InviteNotification.Outcome.PENDING ||
            (previous.outcome == InviteNotification.Outcome.UNAVAILABLE && outcome == InviteNotification.Outcome.ACCEPTED) ||
            (seen && previous.outcome == InviteNotification.Outcome.UNAVAILABLE) ->
            previous.copy(outcome = outcome, handledAt = handledAt, seen = seen)
        seen && previous.outcome == outcome -> previous.copy(seen = true)
        else -> previous
    }

    fun ordered(records: List<InviteNotification>): List<InviteNotification> = records.sortedWith(
        compareByDescending<InviteNotification> { it.sequence }.thenByDescending { it.notificationTime }.thenBy { it.roomId.value }
    )

    fun merge(
        old: List<InviteNotification>,
        observed: List<InviteNotification>,
        completeSnapshot: Boolean = false,
        receivedAt: Long = 0,
    ): List<InviteNotification> {
        // Preserve the legacy order once, then order new arrivals independently of server/device clocks.
        var sequence = old.maxOfOrNull { it.sequence } ?: 0L
        val result = old.sortedWith(compareBy<InviteNotification> { it.notificationTime }.thenBy { it.roomId.value })
            .associate { record -> record.roomId to if (record.sequence == 0L) record.copy(sequence = ++sequence) else record }.toMutableMap()
        observed.sortedWith(compareBy<InviteNotification> { it.timestamp }.thenBy { it.roomId.value }).forEach { next ->
            val previous = result[next.roomId]
            val fresh = previous == null || !previous.active ||
                (previous.outcome != InviteNotification.Outcome.PENDING && next.timestamp > 0 && next.timestamp > previous.timestamp)
            if (fresh) {
                result[next.roomId] = next.copy(sequence = ++sequence, receivedAt = receivedAt)
            } else {
                result[next.roomId] = previous.copy(
                    kind = refinedKind(previous.kind, next.kind),
                    senderId = next.senderId ?: previous.senderId,
                    displayName = next.displayName ?: previous.displayName,
                    avatarUrl = next.avatarUrl ?: previous.avatarUrl,
                )
            }
        }
        if (completeSnapshot) {
            val activeRooms = observed.map { it.roomId }.toSet()
            result.replaceAll { id, record -> if (id !in activeRooms) record.copy(active = false) else record }
        }
        val ordered = ordered(result.values.toList())
        // A history limit must never delete outstanding requests or unseen results.
        val protected = ordered.filter { it.outcome == InviteNotification.Outcome.PENDING || !it.seen }.map { it.roomId }.toSet()
        val retainedHistory = ordered.filter { it.roomId !in protected }.take((MAX_RECORDS - protected.size).coerceAtLeast(0))
            .map { it.roomId }.toSet()
        return ordered.filter { it.roomId in protected || it.roomId in retainedHistory }
    }
}
