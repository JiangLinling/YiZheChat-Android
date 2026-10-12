/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.exception

sealed class AccountDeactivationException(message: String, cause: Throwable) : Exception(message, cause) {
    class InvalidCredentials(cause: Throwable) : AccountDeactivationException("Invalid account password.", cause)
}
