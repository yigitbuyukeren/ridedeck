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
        val apps = listOf(DashCard.APPS, DashCard.MEDIA)
        assertEquals(apps, DashLayout.fromString(DashLayout.toString(apps)))
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
}
