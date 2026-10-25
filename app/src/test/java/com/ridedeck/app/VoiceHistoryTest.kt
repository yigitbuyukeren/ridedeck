package com.ridedeck.app

import com.ridedeck.app.data.VoiceHistory
import com.ridedeck.app.data.VoiceHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHistoryTest {
    @Test
    fun keepsTheLastTenNewestFirst() {
        var list = emptyList<VoiceHistoryEntry>()
        for (i in 1..12) list = VoiceHistory.add(list, VoiceHistoryEntry("komut $i", i % 2 == 0, i.toLong()))
        assertEquals(10, list.size)
        assertEquals("komut 12", list.first().heard)
        assertEquals("komut 3", list.last().heard)
    }

    @Test
    fun jsonRoundTripAndBadJson() {
        val list = listOf(VoiceHistoryEntry("eve git", true, 5L), VoiceHistoryEntry("asdf", false, 4L))
        assertEquals(list, VoiceHistory.fromJson(VoiceHistory.toJson(list)))
        assertTrue(VoiceHistory.fromJson("not json").isEmpty())
        assertTrue(VoiceHistory.fromJson(null).isEmpty())
    }

}
