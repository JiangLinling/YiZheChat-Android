package io.element.android.libraries.matrix.api.permalink

import android.net.Uri
import io.element.android.libraries.matrix.api.core.MatrixPatterns

/** A bounded text carrier, encrypted by the ordinary message pipeline; older clients retain a readable link. */
data class SharedIdentityCard(val isGroup: Boolean, val name: String, val url: String) {
    fun body(): String = "${if (isGroup) GROUP else CONTACT}\n${name.replace('\n', ' ').replace('\r', ' ').take(255)}\n$url"

    companion object {
        private const val CONTACT = "[乙者联系人名片]"
        private const val GROUP = "[乙者群名片]"
        fun userLink(userId: String): String {
            require(MatrixPatterns.isUserId(userId))
            return Uri.Builder().scheme("yizhe").authority("friend").appendPath("v1").appendQueryParameter("user_id", userId).build().toString()
        }
        fun parse(body: String): SharedIdentityCard? = runCatching {
            if (body.length > 8192) return null
            val lines = body.split('\n')
            if (lines.size != 3 || lines[0] !in setOf(CONTACT, GROUP) || lines[1].length !in 1..255) return null
            val uri = Uri.parse(lines[2])
            val group = lines[0] == GROUP
            if (uri.scheme != "yizhe" || uri.host != (if (group) "group" else "friend") || uri.path != "/v1") return null
            if (group) {
                val room = uri.getQueryParameter("room_id") ?: return null
                if (!MatrixPatterns.isRoomId(room) || uri.getQueryParameter("token").isNullOrBlank()) return null
            } else if (!MatrixPatterns.isUserId(uri.getQueryParameter("user_id").orEmpty())) return null
            SharedIdentityCard(group, lines[1], lines[2])
        }.getOrNull()
    }
}
