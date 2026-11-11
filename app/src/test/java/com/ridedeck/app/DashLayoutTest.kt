package com.ridedeck.app

import com.ridedeck.app.data.DashCard
import com.ridedeck.app.data.DashLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class DashLayoutTest {
    @Test
    fun badInputFallsBackToDefault() {
        assertEquals(DashLayout.DEFAULT, DashLayout.fromString(null))
        assertEquals(DashLayout.DEFAULT, DashLayout.fromString("junk"))
        assertEquals(DashLayout.DEFAULT, DashLayout.fromString("NAV"))
    }

    @Test
    fun roundTrip() {
        val slots = listOf(DashCard.APPS, DashCard.NAV)
        assertEquals(slots, DashLayout.fromString(DashLayout.toString(slots)))
        val sunset = listOf(DashCard.SUNSET, DashCard.MEDIA)
        assertEquals(sunset, DashLayout.fromString(DashLayout.toString(sunset)))
    }

    @Test
    fun duplicatesAreDropped() {
        assertEquals(listOf(DashCard.NAV, DashCard.NONE), DashLayout.fromString("NAV,NAV"))
    }

    @Test
    fun anOldWidgetSettingLeavesThatSlotEmpty() {
        assertEquals(listOf(DashCard.NONE, DashCard.NAV), DashLayout.fromString("WIDGET,NAV"))
        assertEquals(listOf(DashCard.MEDIA, DashCard.NONE), DashLayout.fromString("MEDIA,WIDGET"))
    }

    @Test
    fun settingACardAlreadyInTheOtherSlotSwaps() {
        assertEquals(listOf(DashCard.MEDIA, DashCard.NAV), DashLayout.set(listOf(DashCard.NAV, DashCard.MEDIA), 0, DashCard.MEDIA))
        assertEquals(listOf(DashCard.NAV, DashCard.APPS), DashLayout.set(listOf(DashCard.NAV, DashCard.MEDIA), 1, DashCard.APPS))
        assertEquals(listOf(DashCard.NONE, DashCard.MEDIA), DashLayout.set(listOf(DashCard.NAV, DashCard.MEDIA), 0, DashCard.NONE))
    }
}
