package com.ridedeck.app.data

/** Chooses a launchable music target without trusting a stale saved package name. */
object MusicResumePolicy {
    fun select(lastPackage: String?, candidates: List<String>, isInstalled: (String) -> Boolean): String? =
        lastPackage?.takeIf(isInstalled) ?: candidates.firstOrNull(isInstalled)
}

/** Checks the action bitmask advertised by a media session before promising a voice action. */
object MediaSessionActionPolicy {
    fun supports(availableActions: Long, requiredAction: Long): Boolean =
        requiredAction != 0L && (availableActions and requiredAction) == requiredAction
}
