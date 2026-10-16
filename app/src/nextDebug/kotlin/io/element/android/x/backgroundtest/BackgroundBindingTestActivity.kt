/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.x.backgroundtest

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.element.android.x.MainActivity
import io.element.android.x.R

/** Visible controls for the isolated Next Debug experiment; no account/content access. */
class BackgroundBindingTestActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        column.addView(TextView(this).apply { text = getString(R.string.background_binding_test_label); textSize = 22f; setTextColor(Color.BLACK) })
        column.addView(TextView(this).apply { text = getString(R.string.background_binding_test_disclosure); textSize = 17f; setTextColor(Color.BLACK) })
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.BLACK) }
        column.addView(status)
        column.addView(CheckBox(this).apply {
            setText(R.string.background_binding_test_foreground)
            isChecked = getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE)
                .getBoolean(BackgroundBindingTestService.FOREGROUND, true)
            setOnCheckedChangeListener { _, value ->
                getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(BackgroundBindingTestService.FOREGROUND, value).apply()
                BackgroundBindingTestService.instance?.reconcile(force = true)
                refresh()
            }
        })
        column.addView(CheckBox(this).apply {
            setText(R.string.background_binding_test_overlay)
            isChecked = getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE)
                .getBoolean(BackgroundBindingTestService.OVERLAY, false)
            setOnCheckedChangeListener { _, value ->
                getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(BackgroundBindingTestService.OVERLAY, value).apply()
                BackgroundBindingTestService.instance?.reconcile(force = true)
                refresh()
            }
        })
        column.addView(CheckBox(this).apply {
            setText(R.string.background_binding_test_feedback)
            isChecked = getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE)
                .getBoolean(BackgroundBindingTestService.ALL_FEEDBACK, false)
            setOnCheckedChangeListener { _, value ->
                getSharedPreferences(BackgroundBindingTestService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(BackgroundBindingTestService.ALL_FEEDBACK, value).apply()
                BackgroundBindingTestService.instance?.reconcile(force = true)
                refresh()
            }
        })
        column.addView(Button(this).apply {
            setText(R.string.background_binding_test_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        column.addView(Button(this).apply { setText(R.string.background_binding_test_refresh); setOnClickListener {
            BackgroundBindingTestService.instance?.reconcile(force = true)
            refresh()
        } })
        column.addView(Button(this).apply {
            setText(R.string.background_binding_test_open_app)
            setOnClickListener { startActivity(Intent(this@BackgroundBindingTestActivity, MainActivity::class.java).setAction(Intent.ACTION_MAIN)) }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() { super.onResume(); BackgroundBindingTestService.instance?.reconcile(); refresh() }
    private fun refresh() {
        val service = BackgroundBindingTestService.instance
        status.text = getString(R.string.background_binding_test_status, android.os.Process.myPid(), service != null,
            service?.foreground == true, service?.overlayAttached == true, service?.lastFailure ?: "—")
    }
}
