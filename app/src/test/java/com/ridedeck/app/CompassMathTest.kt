package com.ridedeck.app

import com.ridedeck.app.data.CompassMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompassMathTest {
    // Identity matrix: device x = east, y = north, z = up (phone flat, top of the screen to the north).
    private val flatNorth = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test fun flatPhoneTopPointsNorth() = assertEquals(0f, CompassMath.heading(flatNorth, 0)!!, 0.1f)

    @Test fun flatPhoneRotatedLeftHeadsWest() {
        // Top of the phone now points west: device y = -east, device x = north.
        val r = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(270f, CompassMath.heading(r, 0)!!, 0.1f)
    }

    @Test fun flatLandscapeRotation90TopOfScreenIsDeviceX() {
        // ROTATION_90: the phone is turned counter-clockwise, so the top of the screen is device +x, here north.
        val r = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(0f, CompassMath.heading(r, 1)!!, 0.1f)
    }

    @Test fun flatLandscapeRotation270TopOfScreenIsDeviceMinusX() {
        // ROTATION_270: the phone is turned clockwise, so the top of the screen is device -x, here north.
        val r = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(0f, CompassMath.heading(r, 3)!!, 0.1f)
    }

    @Test fun uprightPhoneBackFacingEastHeadsEast() {
        // Device z (out of the screen) points west, so the back faces east; device y is up.
        val r = floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(90f, CompassMath.heading(r, 1)!!, 0.1f)
    }

    @Test fun shortMatrixGivesNull() = assertNull(CompassMath.heading(floatArrayOf(1f), 0))

    @Test fun easeTakesShortWayAroundNorth() {
        val v = CompassMath.ease(350f, 10f, 10f)
        assertEquals(10f, v, 0.5f)
        val half = CompassMath.ease(350f, 10f, 0.1f)
        assert(half > 350f || half < 10f)
    }
}
