package com.ridedeck.app.data

/**
 * Whether RideDeck can end the current call without pretending it did.
 *
 * Two supported paths:
 *  - a [android.telecom.Call] handed to [com.ridedeck.app.service.DeckInCallService] while the app
 *    is the default phone app, which works on every supported API; and
 *  - `TelecomManager.endCall()`, which exists from API 28 and also needs ANSWER_PHONE_CALLS.
 *
 * On API 26/27 without the in-call role there is no public hang-up API. The UI must disable or
 * explain the action instead of showing a button that silently does nothing. This object is pure
 * so the version/permission matrix can be unit tested without Telecom.
 */
object CallCapability {
    const val PLATFORM_END_CALL_MIN_SDK = 28

    enum class HangUpMode {
        /** A managed [android.telecom.Call] can be disconnected/rejected directly. */
        MANAGED_CALL,

        /** The platform `TelecomManager.endCall()` API is available and permitted. */
        PLATFORM_API,

        /** API 26/27 without the default-phone role: no public API can end the call. */
        NEEDS_DEFAULT_PHONE_APP,

        /** API 28+ without ANSWER_PHONE_CALLS. */
        MISSING_PERMISSION,
    }

    data class HangUpSupport(val supported: Boolean, val mode: HangUpMode)

    fun hangUp(sdkInt: Int, managedCallPresent: Boolean, hasAnswerPhoneCallsPermission: Boolean): HangUpSupport = when {
        managedCallPresent -> HangUpSupport(true, HangUpMode.MANAGED_CALL)
        sdkInt >= PLATFORM_END_CALL_MIN_SDK && hasAnswerPhoneCallsPermission ->
            HangUpSupport(true, HangUpMode.PLATFORM_API)
        sdkInt < PLATFORM_END_CALL_MIN_SDK ->
            HangUpSupport(false, HangUpMode.NEEDS_DEFAULT_PHONE_APP)
        else -> HangUpSupport(false, HangUpMode.MISSING_PERMISSION)
    }
}
