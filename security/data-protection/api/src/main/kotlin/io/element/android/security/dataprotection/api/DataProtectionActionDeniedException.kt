/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

sealed class DataProtectionActionDeniedException(message: String) : SecurityException(message) {
    class ContentExport : DataProtectionActionDeniedException("Content export is disabled by signed policy.")
    class ExternalShare : DataProtectionActionDeniedException("External sharing is disabled by signed policy.")
}
