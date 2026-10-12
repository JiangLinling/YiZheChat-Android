/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.timeline.item.event

/**
 * Security boundary shared by every user-facing message surface.
 *
 * An encrypted event is invisible until every decryption and authorization layer has completed.
 * Failure shells never carry the original message body. Only actionable, content-free security
 * outcomes may reach the timeline; decrypting, expired and revoked events remain invisible.
 */
fun EventContent.isAuthorizedForUserDisplay(requireContentProtection: Boolean = false): Boolean = when (this) {
    is UnableToDecryptContent -> false
    is MessageContent -> when (protectedMessageStatus) {
        ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED -> isBurnAfterRead && body.isEmpty()
        ProtectedMessageStatus.ATTACHMENT_DECRYPTING,
        ProtectedMessageStatus.LEGACY_BLOCKED,
        ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE,
        ProtectedMessageStatus.SEND_BINDING_PENDING,
        ProtectedMessageStatus.LICENSE_AUTHORIZATION_FAILED,
        ProtectedMessageStatus.VAULT_LOCKED,
        ProtectedMessageStatus.DEVICE_SECURITY_UNAVAILABLE,
        ProtectedMessageStatus.ACCESS_DENIED,
        ProtectedMessageStatus.INTEGRITY_REJECTED ->
            body.isEmpty() && (type as? TextMessageType)?.body?.isEmpty() == true
        ProtectedMessageStatus.EXPIRED,
        ProtectedMessageStatus.REVOKED -> false
        ProtectedMessageStatus.DECRYPTING -> false
        null -> (!requireContentProtection || isContentProtectionAuthorized) &&
            !body.startsWith(PROTECTED_TEXT_CARRIER_PREFIX) &&
            !(type as? MessageTypeWithAttachment).isProtectedMediaTransport()
    }
    else -> true
}

private fun MessageTypeWithAttachment?.isProtectedMediaTransport(): Boolean =
    this != null && filename.startsWith(PROTECTED_MEDIA_PREFIX) && filename.endsWith(PROTECTED_MEDIA_SUFFIX)

private const val PROTECTED_TEXT_CARRIER_PREFIX = "yizhe-secure-v1:"
private const val PROTECTED_MEDIA_PREFIX = ".yizhe-"
private const val PROTECTED_MEDIA_SUFFIX = ".yzm"
