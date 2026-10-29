package com.ridedeck.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import android.service.notification.StatusBarNotification
import com.ridedeck.app.data.PinnedApps
import com.ridedeck.app.util.Prefs
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

/** Caller name taken from the dialer's ongoing-call notification (the phone-state API does not expose it). */
object CallBridge {
    val callerName = MutableStateFlow<String?>(null)
}

data class IncomingMessage(
    val id: Long,
    val app: String,
    val sender: String,
    val text: String,
    val postedAtMs: Long,
    /** The notification's "reply" button, kept in memory only so a spoken answer can be sent through it. */
    val replyAction: Notification.Action? = null,
) {
    val canReply: Boolean get() = replyAction?.remoteInputs?.isNotEmpty() == true

    /** Sends [body] as the answer through the notification's own reply action; false when that is not possible. */
    fun sendReply(context: android.content.Context, body: String): Boolean {
        val action = replyAction ?: return false
        val input = action.remoteInputs?.firstOrNull() ?: return false
        val results = android.os.Bundle().apply { putCharSequence(input.resultKey, body) }
        val intent = android.content.Intent()
        android.app.RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: android.app.PendingIntent.CanceledException) {
            false
        }
    }
}

/** Latest chat message seen in the notification shade. Kept in memory only, never persisted. */
object MessageBridge {
    val latest = MutableStateFlow<IncomingMessage?>(null)
    private val counter = AtomicLong()
    fun nextId(): Long = counter.incrementAndGet()
}

class CarNotificationListener : NotificationListenerService() {
    private val callSource = com.ridedeck.app.data.CallerNotificationSource()
    private var lastKey: String? = null
    private var lastText: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Our own incoming-call notification says "incoming call": never take that for the caller's name.
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) {
            val dialer = runCatching { getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()
            if (callSource.posted(sbn.key, sbn.packageName, dialer, InCallHub.call != null)) {
                CallBridge.callerName.value = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            }
            return
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        if (!isMessage) return

        // Remember the app so Settings can offer to silence it, and drop apps the rider muted.
        val seen = PinnedApps.fromJson(Prefs.messageAppsSeen(this))
        if (sbn.packageName !in seen) Prefs.setMessageAppsSeen(this, PinnedApps.toJson(PinnedApps.add(seen, sbn.packageName)))
        if (sbn.packageName in PinnedApps.fromJson(Prefs.messageAppsMuted(this))) return

        val (sender, text) = parse(n) ?: return
        // Chat apps re-post the same notification when other things change; ignore repeats.
        if (sbn.key == lastKey && text == lastText) return
        lastKey = sbn.key
        lastText = text
        MessageBridge.latest.value = IncomingMessage(
            id = MessageBridge.nextId(),
            app = appLabel(sbn.packageName),
            sender = sender,
            text = text,
            postedAtMs = sbn.postTime,
            replyAction = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true },
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.notification.category == Notification.CATEGORY_CALL && callSource.removed(sbn.key, InCallHub.call != null)) CallBridge.callerName.value = null
    }

    override fun onListenerDisconnected() {
        if (callSource.disconnected(InCallHub.call != null)) CallBridge.callerName.value = null
        MessageBridge.latest.value = null
        lastKey = null; lastText = null
        super.onListenerDisconnected()
    }

    /**
     * Sender and text of a chat notification. The androidx compat style reads the messages on every
     * Android version this app supports; the platform calls need API 28 and 30.
     */
    private fun parse(n: Notification): Pair<String, String>? {
        val extras = n.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val lastText = last?.text?.toString()
        if (style != null && last != null && !lastText.isNullOrBlank()) {
            val sender = last.person?.name?.toString()
                ?: style.conversationTitle?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: ""
            return sender to lastText
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()
        return if (text.isNullOrBlank()) null else title to text
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
