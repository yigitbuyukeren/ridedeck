package com.ridedeck.app.service

import android.app.KeyguardManager
import android.app.Notification
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import android.service.notification.StatusBarNotification
import com.ridedeck.app.R
import com.ridedeck.app.data.MessagePrivacy
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
)

/** Latest chat message seen in the notification shade. Kept in memory only, never persisted. */
object MessageBridge {
    val latest = MutableStateFlow<IncomingMessage?>(null)
    private val counter = AtomicLong()
    fun nextId(): Long = counter.incrementAndGet()
}

class CarNotificationListener : NotificationListenerService() {
    private val callSource = com.ridedeck.app.data.CallerNotificationSource()
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
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

        // Remember the app so Settings can offer to silence it, and drop apps the driver muted.
        val seen = PinnedApps.fromJson(Prefs.messageAppsSeen(this))
        if (sbn.packageName !in seen) Prefs.setMessageAppsSeen(this, PinnedApps.toJson(PinnedApps.add(seen, sbn.packageName)))
        if (sbn.packageName in PinnedApps.fromJson(Prefs.messageAppsMuted(this))) return

        // Privacy gate: a secret notification is never read; locked private content keeps only the
        // app name. The content is not parsed in the app-only path, so sender/body never leave the
        // notification shade. Keyguard state is read per notification, not cached.
        val surface = MessagePrivacy.surface(
            visibility = n.visibility,
            // An unreadable KeyguardManager must not be treated as "unlocked": fail closed.
            keyguardLocked = keyguard?.isKeyguardLocked ?: true,
        )
        if (surface == MessagePrivacy.Surface.SKIP) return
        val (sender, text) = if (surface == MessagePrivacy.Surface.APP_ONLY) {
            "" to getString(R.string.data_message_redacted)
        } else {
            parse(n) ?: return
        }
        // Chat apps re-post the same notification when other things change; ignore repeats. A
        // redacted body is identical for every locked message, so include the post time there to
        // avoid dropping the second message in the same conversation.
        val dedupeText = if (surface == MessagePrivacy.Surface.APP_ONLY) "app-only:${sbn.postTime}" else text
        if (sbn.key == lastKey && dedupeText == lastText) return
        lastKey = sbn.key
        lastText = dedupeText
        MessageBridge.latest.value = IncomingMessage(
            id = MessageBridge.nextId(),
            app = appLabel(sbn.packageName),
            sender = sender,
            text = text,
            postedAtMs = sbn.postTime,
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
