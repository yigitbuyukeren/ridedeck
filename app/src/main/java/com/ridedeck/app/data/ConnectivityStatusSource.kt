package com.ridedeck.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Application-owned, callback-free snapshot source. It reuses [ConnectivityClassifier]; no second
 * NetworkCallback is registered here and no periodic polling happens.
 */
class ConnectivityStatusSource(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun current(): ConnectivityStatus {
        val cm = manager ?: return ConnectivityStatus.UNKNOWN
        val network = runCatching { cm.activeNetwork }.getOrNull() ?: return ConnectivityStatus.OFFLINE
        val capabilities = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            ?: return ConnectivityStatus.UNKNOWN
        return ConnectivityClassifier.classify(
            hasDefaultNetwork = true,
            capabilitiesKnown = true,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }
}
