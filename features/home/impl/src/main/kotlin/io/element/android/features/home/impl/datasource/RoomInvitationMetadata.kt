package io.element.android.features.home.impl.datasource

import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomInfo

/** Matrix can expose the direct invite marker before the joined-room DM projection. */
internal val RoomInfo.isDirectInvitation: Boolean
    get() = !isSpace && currentUserMembership == CurrentUserMembership.INVITED && (isDm || isDirect)

/** Shared by the chat list and metadata-only contacts/notification directory. */
internal fun RoomInfo.invitationDisplayName(): String? {
    if (!isDm && !isDirectInvitation) return name
    val peer = inviter?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.userId.value }
        ?: heroes.singleOrNull()?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.userId.value }
    return if (currentUserMembership == CurrentUserMembership.INVITED || name.isNullOrBlank() || name == id.value) {
        peer ?: name
    } else name
}
