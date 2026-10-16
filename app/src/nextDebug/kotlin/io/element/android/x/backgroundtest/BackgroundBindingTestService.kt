/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.x.backgroundtest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.element.android.x.R
import timber.log.Timber

/** Next Debug only. No screen retrieval, gesture, synchronization, key or account access. */
class BackgroundBindingTestService : AccessibilityService(), Application.ActivityLifecycleCallbacks {
    var foreground = false
        private set
    var overlayAttached = false
        private set
    var lastFailure: String? = null
        private set
    private var overlayView: View? = null
    private var callbacksRegistered = false
    private var lastForegroundAttempt = -1_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Only own-window events can reach the no-op callback. No cross-app observation.
        serviceInfo?.let { info ->
            info.packageNames = arrayOf(packageName)
            serviceInfo = info
        }
        if (!callbacksRegistered) {
            application.registerActivityLifecycleCallbacks(this)
            callbacksRegistered = true
        }
        Timber.tag(TAG).i("connected pid=%d", android.os.Process.myPid())
        reconcile(force = true)
    }

    /** Event-driven reconciliation; no timer, wake lock, shell or forced permission repair. */
    fun reconcile(force: Boolean = false) {
        if (instance !== this) return
        // Diagnostic A/B only: public metadata classification, never a permission or OEM API.
        // Keep generic as default; a production declaration must match actual accessibility feedback.
        val feedback = if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ALL_FEEDBACK, false)) {
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        } else AccessibilityServiceInfo.FEEDBACK_GENERIC
        // getServiceInfo is a Binder query and may return null while the system disconnects.
        // A stale Activity callback must not crash the notification process or invent a connection.
        try {
            serviceInfo?.let { info ->
                if (info.feedbackType != feedback) {
                    info.feedbackType = feedback
                    serviceInfo = info
                }
            }
        } catch (failure: RuntimeException) {
            lastFailure = "feedback: ${failure.javaClass.simpleName}"
        }
        refreshForegroundState()
        applyForeground(force)
        applyOverlay()
    }

    @Suppress("DEPRECATION")
    private fun refreshForegroundState() {
        try {
            foreground = getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .firstOrNull { it.service.className == javaClass.name }?.foreground == true
        } catch (failure: RuntimeException) {
            foreground = false
            lastFailure = "state: ${failure.javaClass.simpleName}"
        }
    }

    private fun applyForeground(force: Boolean) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(FOREGROUND, true)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            return
        }
        if (foreground) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastForegroundAttempt < 1_000L) return
        lastForegroundAttempt = now
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.background_binding_test_label),
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false); enableVibration(false); setSound(null, null) })
            val pending = PendingIntent.getActivity(this, NOTIFICATION_ID,
                Intent(this, BackgroundBindingTestActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.background_binding_test_notification))
                .setContentText(getString(R.string.background_binding_test_notification_body))
                .setContentIntent(pending).setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
            lastFailure = null
            Timber.tag(TAG).i("foreground success pid=%d", android.os.Process.myPid())
        } catch (failure: RuntimeException) {
            foreground = false
            lastFailure = "foreground: ${failure.javaClass.simpleName}"
            Timber.tag(TAG).w("foreground rejected: %s", failure.javaClass.simpleName)
        }
    }

    private fun applyOverlay() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(OVERLAY, false)) {
            removeOverlay()
            return
        }
        if (overlayAttached) return
        val view = View(this)
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            width = 1
            height = 1
            packageName = this@BackgroundBindingTestService.packageName
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            overlayView = view
            overlayAttached = true
            Timber.tag(TAG).i("overlay attached pid=%d", android.os.Process.myPid())
        } catch (failure: RuntimeException) {
            lastFailure = "overlay: ${failure.javaClass.simpleName}"
            Timber.tag(TAG).w("overlay rejected: %s", failure.javaClass.simpleName)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        overlayAttached = false
        try {
            getSystemService(WindowManager::class.java).removeViewImmediate(view)
        } catch (failure: RuntimeException) {
            Timber.tag(TAG).w("overlay removal: %s", failure.javaClass.simpleName)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { Timber.tag(TAG).i("interrupted") }

    private fun disconnected() {
        if (callbacksRegistered) application.unregisterActivityLifecycleCallbacks(this)
        callbacksRegistered = false
        removeOverlay()
        if (instance === this) instance = null
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.tag(TAG).i("disconnected pid=%d", android.os.Process.myPid())
    }

    override fun onUnbind(intent: Intent?): Boolean { disconnected(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnected(); super.onDestroy() }
    // AutoSkip-style page retry: only a system-connected instance can be promoted.
    override fun onActivityResumed(activity: Activity) { reconcile() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        const val TAG = "YiZheBindingTest"
        const val PREFS = "background_binding_test"
        const val FOREGROUND = "foreground"
        const val OVERLAY = "overlay"
        const val ALL_FEEDBACK = "all_feedback_diagnostic"
        private const val CHANNEL = "background_binding_test"
        private const val NOTIFICATION_ID = 73091
        var instance: BackgroundBindingTestService? = null
            private set
    }
}
