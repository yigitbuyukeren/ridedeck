package com.ridedeck.app

import com.ridedeck.app.data.WakeWordTriggerPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTriggerPolicyTest {
    @Test
    fun acceptsFirstMatchAndEnforcesCooldown() {
        assertTrue(WakeWordTriggerPolicy.accept(nowMs = 10_000, previousMs = 0))
        assertFalse(WakeWordTriggerPolicy.accept(nowMs = 12_499, previousMs = 10_000))
        assertTrue(WakeWordTriggerPolicy.accept(nowMs = 12_500, previousMs = 10_000))
    }
}
