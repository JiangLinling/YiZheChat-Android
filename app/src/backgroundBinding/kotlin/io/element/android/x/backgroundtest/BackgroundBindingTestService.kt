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
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.element.android.x.R
import io.element.android.x.BuildConfig
import timber.log.Timber

/** Shared by explicitly opted-in Next variants. No screen retrieval, gesture, key or account access. */
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
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val foregroundRecovery = ConnectedForegroundRetry(
        postDelayed = { task, delay -> recoveryHandler.postDelayed(task, delay) },
        remove = { recoveryHandler.removeCallbacks(it) },
        canRetry = { instance === this && wantsForeground() && hasSystemAuthorization() },
        retry = {
            reconcile(force = true, scheduleRecovery = false)
            foreground
        },
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!callbacksRegistered) {
            application.registerActivityLifecycleCallbacks(this)
            callbacksRegistered = true
        }
        Timber.tag(TAG).i("connected pid=%d", android.os.Process.myPid())
        reconcile(force = true)
    }

    /** Event-driven reconciliation with a finite foreground retry window; no wake lock or permission writes. */
    fun reconcile(force: Boolean = false) = reconcile(force, scheduleRecovery = true)

    private fun reconcile(force: Boolean, scheduleRecovery: Boolean) {
        if (instance !== this) return
        // Diagnostic A/B only: public metadata classification, never a permission or OEM API.
        // Keep generic as default; a production declaration must match actual accessibility feedback.
        val feedback = if (BuildConfig.DEBUG && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ALL_FEEDBACK, false)) {
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        } else AccessibilityServiceInfo.FEEDBACK_GENERIC
        // getServiceInfo is a Binder query and may return null while the system disconnects.
        // A stale Activity callback must not crash the notification process or invent a connection.
        try {
            serviceInfo?.let { info ->
                // Protect initial connection as well as later callbacks from Binder failures.
                // Only own-window events can reach the no-op callback. No cross-app observation.
                if (info.feedbackType != feedback || !info.packageNames.contentEquals(arrayOf(packageName))) {
                    info.packageNames = arrayOf(packageName)
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
        if (foreground || !wantsForeground()) foregroundRecovery.cancel()
        else if (scheduleRecovery) foregroundRecovery.start()
    }

    private fun wantsForeground(): Boolean = !BuildConfig.DEBUG || getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(FOREGROUND, true)

    private fun hasSystemAuthorization(): Boolean = try {
        val ownComponent = ComponentName(this, javaClass)
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == ownComponent } == true
    } catch (failure: RuntimeException) {
        lastFailure = "authorization: ${failure.javaClass.simpleName}"
        false
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
        if (!wantsForeground()) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.background_binding_test_label),
                    NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false); enableVibration(false); setSound(null, null) })
            }
            val pending = PendingIntent.getActivity(this, NOTIFICATION_ID,
                Intent(this, BackgroundBindingTestActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
            val notification = builder
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
        if (!BuildConfig.DEBUG || !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(OVERLAY, false)) {
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
        foregroundRecovery.cancel()
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
