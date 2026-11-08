package com.ridedeck.app

import com.ridedeck.app.util.ExternalNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalNavTest {
    @Test
    fun wazeAndMapsGetDeepLinks() {
        assertEquals("waze://?ll=41.008200,28.978400&navigate=yes", ExternalNav.uri("com.waze", 41.0082, 28.9784))
        assertEquals("google.navigation:q=41.008200,28.978400&mode=l", ExternalNav.uri("com.google.android.apps.maps", 41.0082, 28.9784))
    }

    @Test
    fun unknownAppsHaveNoLink() {
        assertNull(ExternalNav.uri("com.example.maps", 1.0, 2.0))
    }
}
