/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class MembershipStage { CONFIRM_TARGETS, ROOM_IDENTITY, MATRIX_SESSION, LC12_PREPARE, LC12_INTENT, LC12_CONVERGENCE, NATIVE_LEAVE, LOCAL_LEFT, LC11_PREVIEW, LC11_BEGIN, LC11_COMMIT }

/** Only locally generated categories and enumerated protocol codes may reach the UI. */
class RoomMembershipFailure(val stage: MembershipStage, val code: String, cause: Throwable) : Exception("${stage.name}:$code", cause) {
    init { require(code.matches(Regex("[A-Z0-9_]{1,160}"))) }
    val diagnostic: String get() = "${stage.name}:$code"
}

suspend fun <T> membershipStage(stage: MembershipStage, block: suspend () -> T): T = try {
    block()
} catch (failure: RoomMembershipFailure) {
    throw failure
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    val code = when (failure) {
        is IOException -> "TRANSPORT"
        is SecurityException -> "SECURITY"
        is IllegalArgumentException -> "INVALID_INPUT"
        is IllegalStateException -> "STATE_REJECTED"
        else -> "UNEXPECTED"
    }
    throw RoomMembershipFailure(stage, code, failure)
}
