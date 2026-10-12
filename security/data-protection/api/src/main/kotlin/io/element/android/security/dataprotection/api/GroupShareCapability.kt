package io.element.android.security.dataprotection.api

class GroupSharePermissionException : Exception("GROUP_SHARE_INVITE_FORBIDDEN")

data class GroupShareCapability(val roomId: String, val token: String, val expiresAtMillis: Long) {
    override fun toString(): String = "GroupShareCapability(roomId=$roomId, expiresAtMillis=$expiresAtMillis)"
    fun link(): String = android.net.Uri.Builder().scheme("yizhe").authority("group").appendPath("v1")
        .appendQueryParameter("room_id", roomId).appendQueryParameter("token", token).build().toString()
}
