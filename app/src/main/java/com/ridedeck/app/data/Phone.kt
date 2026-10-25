package com.ridedeck.app.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CallKind { INCOMING, OUTGOING, MISSED }
enum class DialOutcome { REQUESTED, DIALER_OPENED, FAILED }

/** One line of the recents list; [count] is how many back-to-back calls with the same number it stands for. */
data class RecentCall(
    val number: String,
    val name: String?,
    val kind: CallKind,
    val atMs: Long,
    val count: Int = 1,
)

data class Contact(val name: String, val number: String, val starred: Boolean = false)

object PhoneMath {
    const val MAX_DIGITS = 20

    /** Android call log type codes: 1 incoming, 2 outgoing, 3 missed, 5 rejected, 6 blocked; voicemail (4) is skipped. */
    fun kindOf(type: Int): CallKind? = when (type) {
        1 -> CallKind.INCOMING
        2 -> CallKind.OUTGOING
        3, 5, 6 -> CallKind.MISSED
        else -> null
    }

    /** Merges runs of consecutive entries for the same number, like the system dialer; input is newest first. */
    fun collapse(calls: List<RecentCall>): List<RecentCall> {
        val out = ArrayList<RecentCall>()
        for (call in calls) {
            val last = out.lastOrNull()
            if (last != null && last.number == call.number) out[out.lastIndex] = last.copy(count = last.count + call.count)
            else out += call
        }
        return out
    }

    /** What a key press adds to the number being dialed; anything past [MAX_DIGITS] is ignored. */
    fun append(current: String, key: Char): String =
        if (key in "0123456789*#+" && current.length < MAX_DIGITS) current + key else current

    fun backspace(current: String): String = current.dropLast(1)

    /** Keeps only characters a dial string may carry; empty means there is nothing to call. */
    fun dialable(raw: String): String = raw.filter { it in "0123456789*#+" }

    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale("tr", "TR"))

    /** One row per (name, number); starred contacts first, then the whole book in Turkish alphabetical order. */
    fun contacts(rows: List<Contact>): List<Contact> =
        rows.filter { dialable(it.number).isNotEmpty() && it.name.isNotBlank() }
            .distinctBy { it.name to dialable(it.number) }
            .sortedWith(compareByDescending<Contact> { it.starred }.thenComparator { x, y -> collator.compare(x.name, y.name) })

    /** Digits with the country prefix and the national leading zero dropped, so 0532 and +90 532 compare equal. */
    fun key(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.startsWith("0090")) d = d.drop(4) else if (d.startsWith("90") && d.length >= 12) d = d.drop(2)
        return d.trimStart('0')
    }

    /**
     * People whose number contains what has been typed so far (at least three digits), best matches
     * first: those that start with it, then the rest. Names known from recent calls count too.
     */
    fun matches(typed: String, contacts: List<Contact>, recents: List<RecentCall>, limit: Int = 2): List<Contact> {
        val q = key(typed)
        if (q.length < 3) return emptyList()
        val pool = contacts + recents.mapNotNull { r -> r.name?.let { Contact(it, r.number) } }
        return pool.filter { key(it.number).contains(q) }
            .distinctBy { key(it.number) }
            .sortedByDescending { key(it.number).startsWith(q) }
            .take(limit)
    }

    /** The letter a contact is filed under in the list; anything that is not a letter goes under '#'. */
    fun initialOf(name: String): Char = name.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar() ?: '#'
}

/** Recent calls and starred contacts from the system, plus placing a call; empty until the permissions are granted. */
class PhoneRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _recents = MutableStateFlow<List<RecentCall>>(emptyList())
    val recents: StateFlow<List<RecentCall>> = _recents
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    fun canReadLog() = context.hasPermission(Manifest.permission.READ_CALL_LOG)
    fun canReadContacts() = context.hasPermission(Manifest.permission.READ_CONTACTS)

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (canReadLog()) _recents.value = safely(emptyList()) { PhoneMath.collapse(readLog()) }
                if (canReadContacts()) _contacts.value = safely(emptyList()) { PhoneMath.contacts(readContacts()) }
            }
        }
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T =
        try { block() } catch (_: SecurityException) { fallback } catch (_: RuntimeException) { fallback }

    private fun readLog(): List<RecentCall> {
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, LOG_LIMIT)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val cols = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        val out = ArrayList<RecentCall>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, args, null)?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0).orEmpty()
                val kind = PhoneMath.kindOf(c.getInt(2))
                if (number.isBlank() || kind == null) continue
                out += RecentCall(number, c.getString(1)?.takeIf { it.isNotBlank() }, kind, c.getLong(3))
            }
        }
        return out
    }

    private fun readContacts(): List<Contact> {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val out = ArrayList<Contact>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Contact(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2) == 1)
        }
        return out
    }

    /** Places the call when allowed; otherwise reports that only the system dialer was opened. */
    fun dial(raw: String): DialOutcome {
        val number = PhoneMath.dialable(raw)
        if (number.isEmpty()) return DialOutcome.FAILED
        val uri = Uri.fromParts("tel", number, null)
        if (context.hasPermission(Manifest.permission.CALL_PHONE)) {
            try {
                context.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return DialOutcome.REQUESTED
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DialOutcome.DIALER_OPENED
        } catch (_: ActivityNotFoundException) {
            DialOutcome.FAILED
        } catch (_: RuntimeException) {
            DialOutcome.FAILED
        }
    }

    private companion object {
        const val LOG_LIMIT = 60
    }
}
