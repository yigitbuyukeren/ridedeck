package com.ridedeck.app

import com.ridedeck.app.ui.HapticPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three switches that must all say yes before a pulse is sent (Haptics.kt). */
class HapticPolicyTest {
    @Test
    fun allowsOnlyWhenAllThreeSayYes() {
        assertTrue(HapticPolicy.allowed(userEnabled = true, viewEnabled = true, systemEnabled = true))
    }

    @Test
    fun blocksWhenAnySwitchIsOff() {
        assertFalse(HapticPolicy.allowed(userEnabled = false, viewEnabled = true, systemEnabled = true))
        assertFalse(HapticPolicy.allowed(userEnabled = true, viewEnabled = false, systemEnabled = true))
        assertFalse(HapticPolicy.allowed(userEnabled = true, viewEnabled = true, systemEnabled = false))
    }

    @Test
    fun blocksWhenEverythingIsOff() {
        assertFalse(HapticPolicy.allowed(userEnabled = false, viewEnabled = false, systemEnabled = false))
    }
}
