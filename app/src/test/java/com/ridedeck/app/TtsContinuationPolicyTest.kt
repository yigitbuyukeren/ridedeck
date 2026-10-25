package com.ridedeck.app

import com.ridedeck.app.data.TtsContinuationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsContinuationPolicyTest {
    @Test fun aFlushingPriorityUtteranceInheritsTheNewestPendingContinuation() {
        val first = { "first" }
        val newest = { "newest" }
        val inherited = TtsContinuationPolicy.transferAfterFlush(listOf(2L to first, 5L to newest))

        assertEquals("newest", inherited?.invoke())
    }

    @Test fun prioritySpeechWithoutPendingFollowUpInheritsNothing() {
        assertNull(TtsContinuationPolicy.transferAfterFlush(emptyList<Pair<Long, () -> String>>()))
    }
}
