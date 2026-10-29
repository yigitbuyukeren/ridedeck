package com.ridedeck.app.ui

import android.content.ComponentCallbacks
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/** Lifecycle.addObserver replays missed events; disposal only unwinds states that are still active. */
internal class MapLifecycleBinding(private val lifecycle: Lifecycle, private val forward: (Lifecycle.Event) -> Unit) : LifecycleEventObserver {
    private var resumed = false
    private var started = false
    private var destroyed = false

    init { lifecycle.addObserver(this) }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_RESUME -> resumed = true
            Lifecycle.Event.ON_START -> started = true
            Lifecycle.Event.ON_PAUSE -> resumed = false
            Lifecycle.Event.ON_STOP -> started = false
            Lifecycle.Event.ON_DESTROY -> destroyed = true
            else -> {}
        }
        forward(event)
    }

    fun close() {
        lifecycle.removeObserver(this)
        if (destroyed) return
        if (resumed) forward(Lifecycle.Event.ON_PAUSE)
        if (started) forward(Lifecycle.Event.ON_STOP)
        destroyed = true
        forward(Lifecycle.Event.ON_DESTROY)
    }
}

@Composable
internal fun MapLifecycleEffect(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    DisposableEffect(lifecycle, mapView, context) {
        val binding = MapLifecycleBinding(lifecycle) { event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        val memory = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() { if (!mapView.isDestroyed) mapView.onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        onDispose {
            context.unregisterComponentCallbacks(memory)
            binding.close()
        }
    }
}
