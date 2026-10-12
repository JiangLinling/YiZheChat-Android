/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.impl.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.RemoteInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.push.api.notifications.ForegroundNotificationHost
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.factories.DefaultNotificationCreator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/** Uses only the already-sanitized notification. No second message fetch or stored plaintext. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultForegroundNotificationHost(
    @ApplicationContext private val context: Context,
) : ForegroundNotificationHost {
    internal val current = MutableStateFlow<ForegroundMessage?>(null)
    internal val currentCall = MutableStateFlow<ForegroundCall?>(null)
    private var resumed = false
    private var mounted = false
    private var sequence = 0L

    @Synchronized
    override fun setResumed(resumed: Boolean) {
        this.resumed = resumed
        if (!resumed) {
            current.value = null
            currentCall.value = null
        }
    }

    @Synchronized
    internal fun setMounted(mounted: Boolean) {
        this.mounted = mounted
        if (!mounted) {
            current.value = null
            currentCall.value = null
        }
    }

    /** Returns a silent history copy only when a visible host can present the actual message. */
    @Synchronized
    fun prepare(tag: String?, id: Int, notification: Notification): Notification? {
        if (!isHuaweiNotificationDevice(Build.MANUFACTURER, Build.BRAND) || !resumed || !mounted) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.category != Notification.CATEGORY_MESSAGE ||
            !notification.extras.getBoolean(DefaultNotificationCreator.EXTRA_NTFY_DELIVERY)
        ) return null
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId) ?: return null
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH ||
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return null
        val latest = style.messages.lastOrNull() ?: return null
        val eventKey = latest.extras.getString(DefaultNotificationCreator.MESSAGE_EVENT_ID)
        if (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 ||
            current.value?.let { it.id == id && it.tag == tag && it.eventKey == eventKey } == true
        ) return historyCopy(notification, manager)

        val history = historyCopy(notification, manager)
        current.value = ForegroundMessage(++sequence, tag, id, eventKey, notification)
        return history
    }

    private fun historyCopy(notification: Notification, manager: NotificationManager): Notification {
        manager.createNotificationChannel(NotificationChannel(
            HISTORY_CHANNEL,
            context.getString(R.string.notification_foreground_history_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false) })
        // Keep the same ID/tag so existing room, session, redaction and logout cleanup also
        // removes this record. The original message channel still controls alert eligibility.
        return NotificationCompat.Builder(context, notification)
            .setChannelId(HISTORY_CHANNEL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    @Synchronized
    fun cancel(tag: String?, id: Int) {
        if (current.value?.let { it.id == id && (tag == null || it.tag == tag) } == true) current.value = null
    }

    @Synchronized
    override fun showIncomingCall(id: Int, notification: Notification): Boolean {
        if (!isHuaweiNotificationDevice(Build.MANUFACTURER, Build.BRAND) || !resumed || !mounted) return false
        if (notification.category != Notification.CATEGORY_CALL) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId) ?: return false
        val interruptionAllowed = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL || channel.canBypassDnd()
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH || !interruptionAllowed) return false
        currentCall.value = ForegroundCall(++sequence, id, notification)
        return true
    }

    @Synchronized
    override fun clearIncomingCall(id: Int) {
        if (currentCall.value?.id == id) currentCall.value = null
    }

    @Synchronized
    internal fun dismiss(sequence: Long) {
        if (current.value?.sequence == sequence) current.value = null
    }

    @Composable
    override fun Render(modifier: Modifier) {
        if (!isHuaweiNotificationDevice(Build.MANUFACTURER, Build.BRAND)) return
        DisposableEffect(this) {
            setMounted(true)
            onDispose { setMounted(false) }
        }
        val message by current.collectAsState()
        val call by currentCall.collectAsState()
        when {
            call != null -> ForegroundCallCard(call!!, { clearIncomingCall(call!!.id) }, modifier)
            message != null -> ForegroundMessageCard(message!!, { dismiss(message!!.sequence) }, modifier)
        }
    }

    companion object {
        const val HISTORY_CHANNEL = "ntfy_foreground_history"
    }
}

internal data class ForegroundMessage(
    val sequence: Long,
    val tag: String?,
    val id: Int,
    val eventKey: String?,
    val notification: Notification,
)

internal data class ForegroundCall(
    val sequence: Long,
    val id: Int,
    val notification: Notification,
)

internal fun isHuaweiNotificationDevice(manufacturer: String?, brand: String?): Boolean =
    sequenceOf(manufacturer, brand).any { it.orEmpty().trim().equals("huawei", ignoreCase = true) }

@Composable
private fun ForegroundMessageCard(message: ForegroundMessage, dismiss: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val style = remember(message) { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(message.notification) }
    val latest = style?.messages?.lastOrNull()
    val title = style?.conversationTitle?.takeIf { it.isNotBlank() }
        ?: latest?.person?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.notification_new_messages)
    val avatar = remember(message) {
        runCatching { latest?.person?.icon?.loadDrawable(context)?.toBitmap()?.asImageBitmap() }.getOrNull()
    }
    var replyAction by remember(message.sequence) { mutableStateOf<Notification.Action?>(null) }
    var reply by remember(message.sequence) { mutableStateOf("") }
    var failed by remember(message.sequence) { mutableStateOf(false) }
    LaunchedEffect(message.sequence, replyAction) {
        if (replyAction == null) { delay(8_000); dismiss() }
    }
    fun send(intent: PendingIntent?, fillIn: Intent? = null) {
        try {
            if (intent == null) return
            intent.send(context, 0, fillIn)
            dismiss()
        } catch (error: PendingIntent.CanceledException) {
            Timber.w("Notification action is no longer available")
            failed = true
        }
    }
    Surface(
        modifier = modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = ElementTheme.colors.bgCanvasDefault,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                avatar?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp)) }
                Column(Modifier.weight(1f).clickable { send(message.notification.contentIntent) }) {
                    Text(title.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(latest?.text?.toString().orEmpty(), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.notification_foreground_close)) }
            }
            Row {
                message.notification.actions.orEmpty().forEach { action ->
                    TextButton(onClick = {
                        if (action.remoteInputs.isNullOrEmpty()) send(action.actionIntent) else replyAction = action
                    }) { Text(action.title.toString()) }
                }
            }
            replyAction?.let { action ->
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    label = { Text(action.title.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                TextButton(enabled = reply.isNotBlank(), onClick = {
                    val intent = Intent()
                    val results = Bundle().apply { action.remoteInputs.forEach { putCharSequence(it.resultKey, reply) } }
                    RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    }
                    send(action.actionIntent, intent)
                }) { Text(stringResource(R.string.notification_foreground_send)) }
            }
            if (failed) Text(stringResource(R.string.notification_foreground_action_unavailable))
        }
    }
}

@Composable
private fun ForegroundCallCard(call: ForegroundCall, dismiss: () -> Unit, modifier: Modifier) {
    val title = call.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.notification_foreground_incoming_call)
    val body = call.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.notification_foreground_incoming_call)
    var failed by remember(call.sequence) { mutableStateOf(false) }
    LaunchedEffect(call.sequence) {
        delay(call.notification.timeoutAfter.takeIf { it > 0 }?.coerceAtMost(60_000L) ?: 30_000L)
        dismiss()
    }
    fun send(intent: PendingIntent?) {
        try {
            if (intent == null) return
            intent.send()
            dismiss()
        } catch (error: PendingIntent.CanceledException) {
            Timber.w("Incoming call action is no longer available")
            failed = true
        }
    }
    Surface(
        modifier = modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = ElementTheme.colors.bgCanvasDefault,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).clickable { send(call.notification.contentIntent) }) {
                    Text(title.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(body.toString(), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.notification_foreground_close)) }
            }
            Row {
                call.notification.actions.orEmpty().forEach { action ->
                    TextButton(onClick = { send(action.actionIntent) }) { Text(action.title.toString()) }
                }
            }
            if (failed) Text(stringResource(R.string.notification_foreground_action_unavailable))
        }
    }
}

@PreviewsDayNight
@Composable
private fun ForegroundMessageCardPreview() = ElementPreview {
    val context = LocalContext.current
    val notification = remember {
        NotificationCompat.Builder(context, "preview")
            .setStyle(NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("You").build())
                .addMessage("You have a new message.", 1L, androidx.core.app.Person.Builder().setName("New message").build()))
            .build()
    }
    ForegroundMessageCard(ForegroundMessage(1, "preview", 1, "preview", notification), {}, Modifier)
}

@PreviewsDayNight
@Composable
private fun ForegroundCallCardPreview() = ElementPreview {
    val context = LocalContext.current
    val notification = remember {
        NotificationCompat.Builder(context, "preview")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentTitle("Incoming call")
            .setContentText("Private caller")
            .build()
    }
    ForegroundCallCard(ForegroundCall(1, 1, notification), {}, Modifier)
}
