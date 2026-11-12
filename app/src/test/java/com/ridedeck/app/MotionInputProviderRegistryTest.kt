package com.ridedeck.app

import com.ridedeck.app.data.MotionInputProviderRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

class MotionInputProviderRegistryTest {
    @Test
    fun registrationUsesTokenAndUnregisterFailsClosed() {
        val registry = MotionInputProviderRegistry()
        assertTrue(registry.isLockedOrFailClosed())

        val provider: () -> Boolean = { false }
        val token = registry.register(provider)
        assertFalse(registry.isLockedOrFailClosed())

        registry.unregister(token)
        assertTrue(registry.isLockedOrFailClosed())
    }

    @Test
    fun staleTokenCannotUnregisterNewProviderAndNewStrongProviderSurvives() {
        val registry = MotionInputProviderRegistry()
        val oldProvider: () -> Boolean = { true }
        val newProvider: () -> Boolean = { false }
        val old = registry.register(oldProvider)
        val new = registry.register(newProvider)

        registry.unregister(old)
        assertFalse(registry.isLockedOrFailClosed())
        registry.unregister(new)
        assertTrue(registry.isLockedOrFailClosed())
    }

    @Test
    fun collectedProviderFailsClosedDeterministically() {
        lateinit var reference: WeakReference<() -> Boolean>
        val registry = MotionInputProviderRegistry { provider ->
            WeakReference(provider).also { reference = it }
        }
        val provider: () -> Boolean = { false }
        registry.register(provider)
        assertFalse(registry.isLockedOrFailClosed())

        reference.clear()
        assertTrue(registry.isLockedOrFailClosed())
    }
}
