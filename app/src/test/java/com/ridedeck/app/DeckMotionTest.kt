package com.ridedeck.app

import com.ridedeck.app.ui.DeckMotion
import com.ridedeck.app.ui.DeckScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckMotionTest {
    @Test
    fun directionFollowsTheDockOrder() {
        assertEquals(1, DeckMotion.direction(DeckScreen.HOME, DeckScreen.MUSIC))
        assertEquals(1, DeckMotion.direction(DeckScreen.MAP, DeckScreen.PHONE))
        assertEquals(-1, DeckMotion.direction(DeckScreen.PHONE, DeckScreen.HOME))
        assertEquals(0, DeckMotion.direction(DeckScreen.MAP, DeckScreen.MAP))
    }
}
