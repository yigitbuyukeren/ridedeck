package com.ridedeck.app.data

/** Only the default dialer's current notification may supply the unmanaged call's name. */
class CallerNotificationSource {
    private var key: String? = null
    fun posted(notificationKey: String, pkg: String, dialer: String?, managed: Boolean): Boolean {
        if (managed || pkg != dialer) return false
        key = notificationKey
        return true
    }
    fun removed(notificationKey: String, managed: Boolean): Boolean {
        if (notificationKey != key) return false
        key = null
        return !managed
    }
    fun disconnected(managed: Boolean): Boolean { key = null; return !managed }
}
