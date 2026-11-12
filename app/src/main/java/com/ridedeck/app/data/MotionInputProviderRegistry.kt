package com.ridedeck.app.data

import java.lang.ref.Reference
import java.lang.ref.WeakReference

/**
 * Holds only a weak provider so an app-lifetime RideNavigation cannot retain a ViewModel.
 * Missing/collected provider fails closed (treated as input-locked).
 */
class MotionInputProviderRegistry(
    /** Test seam: production always creates a WeakReference. */
    private val referenceFactory: (() -> Boolean) -> Reference<() -> Boolean> = { WeakReference(it) },
) {
    private var token: Any? = null
    private var provider: Reference<() -> Boolean>? = null

    fun register(provider: () -> Boolean): Any {
        val nextToken = Any()
        token = nextToken
        this.provider = referenceFactory(provider)
        return nextToken
    }

    fun unregister(candidate: Any) {
        if (token === candidate) {
            token = null
            provider = null
        }
    }

    fun isLockedOrFailClosed(): Boolean = provider?.get()?.invoke() ?: true
}
