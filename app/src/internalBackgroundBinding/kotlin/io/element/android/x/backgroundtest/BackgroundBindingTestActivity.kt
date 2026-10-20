/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.x.backgroundtest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.element.android.x.R

/** User-visible consent information and actual connection state; no permission or account writes. */
class BackgroundBindingTestActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshStatus = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(resource: Int, size: Float) = TextView(this).apply {
            setText(resource)
            textSize = size
            setPadding(0, 0, 0, padding)
        }
        column.addView(text(R.string.background_binding_test_label, 22f))
        column.addView(text(R.string.background_binding_test_disclosure, 16f))
        status = text(R.string.background_binding_unknown, 18f)
        column.addView(status)
        column.addView(text(R.string.background_binding_test_settings_help, 16f))
        column.addView(Button(this).apply {
            setText(R.string.background_binding_test_settings)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    status.setText(R.string.background_binding_unknown)
                } catch (_: SecurityException) {
                    status.setText(R.string.background_binding_unknown)
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        BackgroundBindingTestService.instance?.reconcile()
        refresh()
        // Refresh the visible page through the existing finite service retry window.
        listOf(1_000L, 4_000L, 14_000L).forEach { handler.postDelayed(refreshStatus, it) }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private fun refresh() {
        val authorized = runCatching {
            val own = ComponentName(this, BackgroundBindingTestService::class.java)
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == own } == true
        }.getOrNull()
        val service = BackgroundBindingTestService.instance
        status.setText(when {
            authorized == null -> R.string.background_binding_unknown
            !authorized -> R.string.background_binding_disabled
            service == null -> R.string.background_binding_waiting
            service.foreground -> R.string.background_binding_ready
            else -> R.string.background_binding_recovering
        })
    }
}
