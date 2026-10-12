/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.api.local

/** Parser admission failure only; selection/upload authorization remains with its existing owner. */
class LocalAttachmentPreviewLimitException(val maxInputBytes: Long) : IllegalArgumentException("Local preview size limit exceeded.")
