package com.ridedeck.app

import com.ridedeck.app.data.ConnectivityClassifier
import com.ridedeck.app.data.ConnectivityEvents
import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.ConnectivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityStatusTest {
    @Test
    fun noDefaultNetworkIsOffline() {
        assertEquals(ConnectivityStatus.OFFLINE, ConnectivityClassifier.classify(false, true, false))
        assertEquals(ConnectivityStatus.OFFLINE, ConnectivityClassifier.classify(false, true, true))
    }

    @Test
    fun unvalidatedDefaultNetworkIsNotOnline() {
        val status = ConnectivityClassifier.classify(hasDefaultNetwork = true, capabilitiesKnown = true, validated = false)
        assertEquals(ConnectivityStatus.UNVALIDATED, status)
        assertFalse(status.isOnline)
    }

    @Test
    fun onlyValidatedDefaultNetworkIsOnline() {
        val status = ConnectivityClassifier.classify(hasDefaultNetwork = true, capabilitiesKnown = true, validated = true)
        assertEquals(ConnectivityStatus.VALIDATED, status)
        assertTrue(status.isOnline)
    }

    @Test
    fun failedCapabilityReadIsUnknownNotOffline() {
        val status = ConnectivityClassifier.classify(hasDefaultNetwork = true, capabilitiesKnown = false, validated = false)
        assertEquals(ConnectivityStatus.UNKNOWN, status)
        assertFalse(status.isOnline)
    }

    @Test
    fun activeCapabilitiesUpdateImmediatelyInBothDirections() {
        assertEquals(
            ConnectivityStatus.VALIDATED,
            ConnectivityEvents.fromCapabilities(true, true, ConnectivityStatus.UNVALIDATED),
        )
        assertEquals(
            ConnectivityStatus.UNVALIDATED,
            ConnectivityEvents.fromCapabilities(true, false, ConnectivityStatus.VALIDATED),
        )
    }

    @Test
    fun staleOldCapabilitiesCannotOverwriteAValidatedNewDefault() {
        val status = ConnectivityEvents.fromCapabilities(
            eventIsActiveNetwork = false,
            eventValidated = false,
            activeStatus = ConnectivityStatus.VALIDATED,
        )
        assertEquals(ConnectivityStatus.VALIDATED, status)
        assertTrue(status.isOnline)
    }

    @Test
    fun staleOldCapabilitiesCannotOverwriteAnUnvalidatedNewDefault() {
        val status = ConnectivityEvents.fromCapabilities(
            eventIsActiveNetwork = false,
            eventValidated = true,
            activeStatus = ConnectivityStatus.UNVALIDATED,
        )
        assertEquals(ConnectivityStatus.UNVALIDATED, status)
        assertFalse(status.isOnline)
    }

    @Test
    fun unknownInitialStateIsNotOnline() {
        val tracker = ConnectivityTracker()
        assertEquals(ConnectivityStatus.UNKNOWN, tracker.status)
        assertFalse(tracker.status.isOnline)
    }

    @Test
    fun trackerStoresTheLatestSnapshot() {
        val tracker = ConnectivityTracker(ConnectivityStatus.UNKNOWN)
        assertEquals(ConnectivityStatus.OFFLINE, tracker.update(ConnectivityStatus.OFFLINE))
        assertEquals(ConnectivityStatus.VALIDATED, tracker.update(ConnectivityStatus.VALIDATED))
        assertTrue(tracker.status.isOnline)
    }
}
