package com.ridedeck.app.data

/**
 * Pure naming decisions for the in-call screen. Kept out of [com.ridedeck.app.service.MotoInCallService]
 * so the Android service only has to supply the platform values.
 */
object CallerNamePolicy {
    /** First non-blank candidate: contacts/platform name, carrier name, then the bare number. */
    fun resolved(contactName: String?, carrierName: String?, number: String?): String? =
        listOf(contactName, carrierName, number).firstOrNull { !it.isNullOrBlank() }

    /**
     * Contacts may only be queried on API < 30; from API 30 the call details already carry
     * [android.telecom.Call.Details.getContactDisplayName]. Never query without READ_CONTACTS.
     */
    fun needsContactLookup(
        sdkInt: Int,
        platformContactName: String?,
        number: String?,
        hasReadContactsPermission: Boolean,
    ): Boolean = sdkInt < 30 &&
        hasReadContactsPermission &&
        !number.isNullOrBlank() &&
        platformContactName.isNullOrBlank()
}
