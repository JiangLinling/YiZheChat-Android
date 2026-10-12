/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import android.content.Context
import com.huawei.hms.common.ApiException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext

private const val DIAGNOSTICS_PREFERENCES = "huawei_push_diagnostics"
private const val KEY_MANUFACTURER = "manufacturer"
private const val KEY_BRAND = "brand"
private const val KEY_MODEL = "model"
private const val KEY_IS_TARGET = "is_target"
private const val KEY_STAGE = "stage"
private const val KEY_ERROR = "error"
private const val KEY_UPDATED_AT = "updated_at"

@SingleIn(AppScope::class)
@Inject
class HuaweiPushDiagnostics(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(DIAGNOSTICS_PREFERENCES, Context.MODE_PRIVATE)

    fun recordTarget(manufacturer: String, brand: String, model: String, isTarget: Boolean) {
        val previousTarget = preferences.getBoolean(KEY_IS_TARGET, false)
        val previousStage = preferences.getString(KEY_STAGE, "NOT_STARTED").orEmpty()
        val editor = preferences.edit()
            .putString(KEY_MANUFACTURER, manufacturer.trim().take(40))
            .putString(KEY_BRAND, brand.trim().take(40))
            .putString(KEY_MODEL, model.trim().take(60))
            .putBoolean(KEY_IS_TARGET, isTarget)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        when {
            !isTarget -> editor.putString(KEY_STAGE, "NOT_HUAWEI").remove(KEY_ERROR)
            !previousTarget || previousStage == "NOT_STARTED" || previousStage == "NOT_HUAWEI" -> {
                editor.putString(KEY_STAGE, "TARGET_DETECTED").remove(KEY_ERROR)
            }
            // Capability checks must not erase REGISTERED, FAILED, or FALLBACK_ACTIVE.
            else -> Unit
        }
        editor.apply()
    }

    fun recordAttempt() = recordStage("REGISTERING")

    fun recordSuccess() = recordStage("REGISTERED")

    fun recordStage(stage: String) {
        preferences.edit()
            .putString(KEY_STAGE, stage.take(80))
            .remove(KEY_ERROR)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordFailure(error: Throwable?) {
        preferences.edit()
            .putString(KEY_STAGE, "FAILED")
            .putString(KEY_ERROR, errorName(error))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordFallbackActive(error: Throwable?) {
        preferences.edit()
            .putString(KEY_STAGE, "FALLBACK_ACTIVE")
            .putString(KEY_ERROR, errorName(error))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun errorName(error: Throwable?): String =
        when (error) {
            is HuaweiPushAppIdUnavailableException -> "APP_ID_UNAVAILABLE"
            is HuaweiPushProjectIdUnavailableException -> "PROJECT_ID_UNAVAILABLE"
            is HuaweiPushTokenPendingException -> "TOKEN_ASYNC_PENDING"
            is HuaweiPushTokenUnavailableException -> "TOKEN_UNAVAILABLE"
            is HuaweiPusherRegistrationException -> "PUSHER_REGISTRATION_FAILED"
            is ApiException -> "HMS_API_${error.statusCode}"
            is IllegalArgumentException -> "INVALID_TOKEN"
            is IllegalStateException -> when (error.message) {
                "No valid Matrix session is available for Huawei Push registration" -> "NO_VALID_SESSION"
                else -> "ILLEGAL_STATE"
            }
            null -> "UNKNOWN"
            else -> error.javaClass.simpleName.take(80)
        }

    fun snapshot(): HuaweiPushDiagnosticSnapshot = HuaweiPushDiagnosticSnapshot(
        manufacturer = preferences.getString(KEY_MANUFACTURER, "").orEmpty(),
        brand = preferences.getString(KEY_BRAND, "").orEmpty(),
        model = preferences.getString(KEY_MODEL, "").orEmpty(),
        isTarget = preferences.getBoolean(KEY_IS_TARGET, false),
        stage = preferences.getString(KEY_STAGE, "NOT_STARTED").orEmpty(),
        error = preferences.getString(KEY_ERROR, null),
    )

}

data class HuaweiPushDiagnosticSnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val isTarget: Boolean,
    val stage: String,
    val error: String?,
)
