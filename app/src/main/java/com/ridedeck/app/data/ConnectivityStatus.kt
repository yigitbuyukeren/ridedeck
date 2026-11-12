package com.ridedeck.app.data

/** What the default network can currently be trusted to do for an online service. */
enum class ConnectivityStatus {
    /** No usable manager or capability snapshot yet. */
    UNKNOWN,

    /** There is no default network. */
    OFFLINE,

    /** A default network exists but has not passed Android's internet validation. */
    UNVALIDATED,

    /** The default network has validated internet access. */
    VALIDATED;

    val isOnline: Boolean get() = this == VALIDATED
}

/** Pure mapping from the facts the platform can give us. A failed capability read is not "offline". */
object ConnectivityClassifier {
    fun classify(hasDefaultNetwork: Boolean, capabilitiesKnown: Boolean, validated: Boolean): ConnectivityStatus = when {
        !hasDefaultNetwork -> ConnectivityStatus.OFFLINE
        !capabilitiesKnown -> ConnectivityStatus.UNKNOWN
        validated -> ConnectivityStatus.VALIDATED
        else -> ConnectivityStatus.UNVALIDATED
    }
}

/** Chooses what a capability event may update; an event from an old default network changes nothing. */
object ConnectivityEvents {
    fun fromCapabilities(
        eventIsActiveNetwork: Boolean,
        eventValidated: Boolean,
        activeStatus: ConnectivityStatus,
    ): ConnectivityStatus = if (eventIsActiveNetwork) {
        ConnectivityClassifier.classify(hasDefaultNetwork = true, capabilitiesKnown = true, validated = eventValidated)
    } else {
        activeStatus
    }
}

/** Holds the latest pure snapshot for the UI wrapper. */
class ConnectivityTracker(initial: ConnectivityStatus = ConnectivityStatus.UNKNOWN) {
    var status: ConnectivityStatus = initial
        private set

    fun update(next: ConnectivityStatus): ConnectivityStatus {
        status = next
        return status
    }
}
