package com.ridedeck.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ridedeck.app.ui.MapLifecycleBinding
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLifecycleTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun mapOpenedAfterResumeReceivesStartupEventsExactlyOnce() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val events = mutableListOf<Lifecycle.Event>()
        val binding = MapLifecycleBinding(owner.lifecycle, events::add)
        assertEquals(listOf(ON_CREATE, ON_START, ON_RESUME), events)
        binding.close()
        binding.close()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(listOf(ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY), events)
    }

    @Test fun backgroundDisposalDoesNotPauseAndStopTwice() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val events = mutableListOf<Lifecycle.Event>()
        val binding = MapLifecycleBinding(owner.lifecycle, events::add)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        binding.close()
        assertEquals(listOf(ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY), events)
    }

    @Test fun activityDestroyAndCompositionDisposalDestroyMapOnlyOnce() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val events = mutableListOf<Lifecycle.Event>()
        val binding = MapLifecycleBinding(owner.lifecycle, events::add)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        binding.close()
        assertEquals(1, events.count { it == ON_DESTROY })
    }
}
