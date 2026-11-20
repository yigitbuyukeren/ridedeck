package com.ridedeck.app

import com.ridedeck.app.data.CameraPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowPaddingTest {
    @Test fun clearViewportKeepsTheUsualFollowTarget() {
        assertEquals(450.0, CameraPlan.followTopPadding(1_000, 0.45, 200), 0.0)
        assertEquals(300.0, CameraPlan.followTopPadding(1_000, 0.30, 0), 0.0)
    }

    @Test fun largeTripBarDoesNotCoverFollowTarget() {
        val height = 540
        val clearance = 180
        val padding = CameraPlan.followTopPadding(height, 0.45, clearance)
        assertTrue((height + padding) / 2 <= height - clearance)
        assertEquals(180.0, padding, 0.0)
    }

    @Test fun shortViewportMayUseBottomPaddingAndUnmeasuredViewportIsSafe() {
        assertEquals(-40.0, CameraPlan.followTopPadding(320, 0.45, 180), 0.0)
        assertEquals(0.0, CameraPlan.followTopPadding(0, 0.45, 180), 0.0)
    }
}
