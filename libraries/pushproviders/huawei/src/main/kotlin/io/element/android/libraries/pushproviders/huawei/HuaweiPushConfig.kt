/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

internal object HuaweiPushConfig {
    const val NAME = "Huawei Push Kit"
    const val PUSHER_APP_ID = "com.yizhe.chat.huawei"
    const val PUSHER_PROFILE_TAG = "huawei_mobile"
    // Synapse requires this URL path to match the Matrix push-gateway path exactly.
    // The non-secret provider query selects the vendor adapter at the reverse proxy.
    const val GATEWAY = "https://push.yizhesy.cn/_matrix/push/v1/notify?provider=huawei"
}

internal fun isHuaweiPushTarget(manufacturer: String, brand: String = ""): Boolean =
    sequenceOf(manufacturer, brand).any { value ->
        value.trim().equals("huawei", ignoreCase = true)
    }
