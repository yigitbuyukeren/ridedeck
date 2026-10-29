package com.ridedeck.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.atomic.AtomicInteger

class MapViewLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun mapsCanBeOpenedBackgroundedResumedAndDisposedRepeatedly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val visible = mutableStateOf(false)
        val ready = AtomicInteger()
        val views = mutableListOf<MapView>()
        lateinit var owner: Owner
        compose.runOnUiThread {
            owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            MapLibre.getInstance(context)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    val view = remember { MapView(context).also(views::add) }
                    MapLifecycleEffect(view)
                    AndroidView(factory = {
                        view.apply {
                            getMapAsync { map ->
                                // Local style: native map lifecycle verification does not depend on a tile server.
                                map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[]}""")) {
                                    ready.incrementAndGet()
                                }
                            }
                        }
                    })
                }
            }
        }
        repeat(3) { index ->
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(20_000) { ready.get() == index + 1 }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(views.last().isDestroyed) }
        }
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            assertEquals(3, views.size)
            assertTrue(views.all { it.isDestroyed })
        }
    }
}
