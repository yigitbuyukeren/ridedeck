package com.ridedeck.app

import com.ridedeck.app.data.CameraPlan
import com.ridedeck.app.data.CameraPlan.VerticalPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowPaddingTest {
    @Test fun clearViewportKeepsTheUsualFollowTarget() {
        assertEquals(VerticalPadding(450.0, 0.0), CameraPlan.followPadding(1_000, 0.45, 200))
        assertEquals(VerticalPadding(300.0, 0.0), CameraPlan.followPadding(1_000, 0.30, 0))
    }

    @Test fun largeTripBarDoesNotCoverFollowTarget() {
        val height = 540
        val clearance = 180
        val padding = CameraPlan.followPadding(height, 0.45, clearance)
        assertTrue((height + padding.top - padding.bottom) / 2 <= height - clearance)
        assertEquals(VerticalPadding(180.0, 0.0), padding)
    }

    @Test fun shortViewportMayUseBottomPaddingAndUnmeasuredViewportIsSafe() {
        val padding = CameraPlan.followPadding(320, 0.45, 180)
        assertEquals(VerticalPadding(0.0, 40.0), padding)
        assertEquals(140.0, (320 + padding.top - padding.bottom) / 2, 0.0)
        assertEquals(VerticalPadding(0.0, 0.0), CameraPlan.followPadding(0, 0.45, 180))
        assertEquals(VerticalPadding(0.0, 160.0), CameraPlan.followPadding(320, 0.45, 320))
    }
}
