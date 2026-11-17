package com.ridedeck.app

import com.ridedeck.app.data.CallCapability
import com.ridedeck.app.data.CallCapability.HangUpMode
import com.ridedeck.app.data.CallerNamePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version/permission matrix for ending a call, plus the pure caller-name decisions. */
class CallCapabilityTest {
    @Test
    fun api26And27HaveNoPlatformHangUpWithoutTheInCallRole() {
        for (sdk in listOf(26, 27)) {
            val support = CallCapability.hangUp(sdk, managedCallPresent = false, hasAnswerPhoneCallsPermission = true)
            assertFalse("API $sdk must not pretend to hang up", support.supported)
            assertEquals(HangUpMode.NEEDS_DEFAULT_PHONE_APP, support.mode)
        }
    }

    @Test
    fun theInCallServiceStillEndsCallsOnEverySupportedApi() {
        for (sdk in listOf(26, 27, 28, 30, 34, 36)) {
            val support = CallCapability.hangUp(sdk, managedCallPresent = true, hasAnswerPhoneCallsPermission = false)
            assertTrue("managed call on API $sdk", support.supported)
            assertEquals(HangUpMode.MANAGED_CALL, support.mode)
        }
    }

    @Test
    fun api28AndLaterUseThePlatformApiOnlyWithPermission() {
        assertTrue(CallCapability.hangUp(28, false, true).supported)
        assertEquals(HangUpMode.PLATFORM_API, CallCapability.hangUp(28, false, true).mode)
        assertTrue(CallCapability.hangUp(36, false, true).supported)

        val denied = CallCapability.hangUp(28, false, false)
        assertFalse(denied.supported)
        assertEquals(HangUpMode.MISSING_PERMISSION, denied.mode)
    }

    @Test
    fun callerNameFallsBackFromContactToCarrierToNumber() {
        assertEquals("Ayşe", CallerNamePolicy.resolved("Ayşe", "Carrier", "+90555"))
        assertEquals("Carrier", CallerNamePolicy.resolved(null, "Carrier", "+90555"))
        assertEquals("+90555", CallerNamePolicy.resolved(" ", null, "+90555"))
        assertNull(CallerNamePolicy.resolved(null, "", null))
    }

    @Test
    fun contactsAreOnlyQueriedOffThePlatformDetailsPath() {
        assertTrue(CallerNamePolicy.needsContactLookup(29, null, "+90555", true))
        assertFalse("API 30+ details already carry the contact", CallerNamePolicy.needsContactLookup(30, null, "+90555", true))
        assertFalse("no READ_CONTACTS", CallerNamePolicy.needsContactLookup(29, null, "+90555", false))
        assertFalse("no number", CallerNamePolicy.needsContactLookup(29, null, " ", true))
        assertFalse("already named", CallerNamePolicy.needsContactLookup(29, "Ayşe", "+90555", true))
    }
}
