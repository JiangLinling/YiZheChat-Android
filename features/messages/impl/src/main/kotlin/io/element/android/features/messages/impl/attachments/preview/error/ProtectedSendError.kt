/* Copyright (c) 2026 YiZheChat contributors. */
package io.element.android.features.messages.impl.attachments.preview.error

import io.element.android.libraries.ui.strings.R
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.libraries.matrix.ui.components.contactFailureMessageResource

/** A transport failure cannot establish the recipient's presence or logout state. */
internal fun protectedSendErrorResource(failure: Throwable): Int {
    failure.contactFailureMessageResource()?.let { return it }
    var current: Throwable? = failure
    repeat(8) {
        when (current) {
            is ContentProtectionException.RecipientDeviceUnavailable -> return R.string.error_share_recipient_device_unavailable
            is ContentProtectionException.AccessDenied -> return R.string.error_share_security_access_denied
            is ContentProtectionException.KmsUnavailable -> return R.string.error_share_security_service_unavailable
            is ContentProtectionException.RoomEpochConflict -> return io.element.android.features.messages.impl.R.string.error_room_epoch_conflict
            is ContentProtectionException.SendBindingPending -> return R.string.error_send_confirmation_pending
            is ContentProtectionException.VersionLineageUnavailable -> return R.string.error_text_version_unavailable
        }
        current = current?.cause
    }
    return R.string.error_share_send_failed
}
