/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.impl.notifications

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.push.impl.R
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import java.text.DateFormat
import java.util.Date
import timber.log.Timber

/** A bounded projection of the already-sanitized notification, never another message transport. */
@SingleIn(AppScope::class)
@Inject
class FloatingMessageBanner(
    @ApplicationContext private val context: Context,
    private val lockScreenService: LockScreenService,
    private val policyProvider: DataProtectionPolicyProvider,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    @Volatile private var latest: Request? = null

    private class Request(val tag: String?, val id: Int) { @Volatile var cancelled = false }

    fun enqueue(tag: String?, id: Int, notification: Notification, shown: () -> Unit, failed: () -> Unit): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!Settings.canDrawOverlays(context) || !unlocked() ||
            notification.category != Notification.CATEGORY_MESSAGE ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 ||
            notification.extras.getParcelable<PendingIntent>(FLOATING_INTENT) == null ||
            (manager.getNotificationChannel(notification.channelId)?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH ||
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) return false
        val request = Request(tag, id)
        latest = request
        val policy = policyProvider.current()
        handler.post {
            if (request.cancelled) return@post
            if (latest !== request) { failed(); return@post }
            dismiss()
            if (!unlocked() || !Settings.canDrawOverlays(context) || policyProvider.current() != policy) {
                failed(); return@post
            }
            try {
                val card = createCard(notification)
                val secure = if (policy.policy.screenCapturePolicy == ScreenCapturePolicy.BLOCK) WindowManager.LayoutParams.FLAG_SECURE else 0
                val params = WindowManager.LayoutParams(
                    windows.maximumWindowMetrics.bounds.width() - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or secure, PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(8) }
                windows.addView(card, params)
                view = card
                shown()
            } catch (_: Exception) {
                Timber.w("Floating notification unavailable; retaining system notification")
                dismiss(); failed(); return@post
            }
            val deadline = SystemClock.elapsedRealtime() + 8_000
            val guard = object : Runnable {
                override fun run() {
                    if (latest !== request) return
                    val authorizationChanged = !unlocked() || policyProvider.current() != policy
                    if (request.cancelled || SystemClock.elapsedRealtime() >= deadline || authorizationChanged ||
                        !Settings.canDrawOverlays(context)
                    ) {
                        dismiss(); latest = null
                        if (authorizationChanged) manager.cancel(request.tag, request.id)
                    } else handler.postDelayed(this, 200)
                }
            }
            handler.postDelayed(guard, 200)
        }
        return true
    }

    fun cancel(tag: String?, id: Int, afterCancel: () -> Unit) {
        val request = latest?.takeIf { it.id == id && (tag == null || it.tag == tag) }
        request?.cancelled = true
        handler.post {
            if (request != null && latest === request) { dismiss(); latest = null }
            afterCancel()
        }
    }

    private fun unlocked() = lockScreenService.lockState.value == LockScreenLockState.Unlocked &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun dismiss() {
        view?.let { card ->
            runCatching { windows.removeViewImmediate(card) }
            // Release every plaintext-bearing child and touch closure when the projection ends.
            card.setOnTouchListener(null); card.setOnClickListener(null); card.removeAllViews()
        }
        view = null
    }

    private fun createCard(notification: Notification): LinearLayout {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val message = style?.messages?.lastOrNull()
        val title = style?.conversationTitle ?: message?.person?.name ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val body = message?.text ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(4))
            background = GradientDrawable().apply { setColor(Color.rgb(250, 248, 241)); cornerRadius = dp(28).toFloat() }
            elevation = dp(10).toFloat()
        }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(context).apply {
            setImageDrawable(context.applicationInfo.loadIcon(context.packageManager))
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Color.BLACK); maxLines = 1 })
            addView(TextView(context).apply { text = body; textSize = 15f; setTextColor(Color.DKGRAY); maxLines = 1 })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply {
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(notification.`when`)); textSize = 12f; setTextColor(Color.GRAY)
        })
        card.addView(row)
        val grip = View(context).apply {
            background = GradientDrawable().apply { setColor(Color.LTGRAY); cornerRadius = dp(3).toFloat() }
            contentDescription = context.getString(R.string.a11y_notification_open_chat_window)
        }
        card.addView(grip, LinearLayout.LayoutParams(dp(48), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(5) })
        fun open(floating: Boolean) {
            if (!unlocked()) { dismiss(); return }
            val pendingIntent = if (floating) notification.extras.getParcelable<PendingIntent>(FLOATING_INTENT) else notification.contentIntent
            try {
                // The visible overlay is the user gesture source; Android still checks launch eligibility.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pendingIntent?.send(context, 0, null, null, null, null, options.toBundle())
                } else pendingIntent?.send()
                dismiss()
            } catch (_: Exception) {
                dismiss(); Timber.w("Notification target is no longer available")
            }
        }
        var startY = 0f
        card.setOnClickListener { open(false) }
        val touch = View.OnTouchListener { source, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = event.rawY
                MotionEvent.ACTION_UP -> when {
                    event.rawY - startY >= dp(28) -> open(true)
                    startY - event.rawY >= dp(28) -> dismiss()
                    else -> source.performClick()
                }
            }
            true
        }
        card.setOnTouchListener(touch)
        grip.setOnTouchListener(touch)
        grip.setOnClickListener { open(true) }
        return card
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object { const val FLOATING_INTENT = "io.yizhechat.notification.FLOATING_INTENT" }
}
