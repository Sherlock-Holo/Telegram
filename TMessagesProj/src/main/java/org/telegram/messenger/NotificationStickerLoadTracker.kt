package org.telegram.messenger

/** Notification-only requests: one initial attempt and one retry, never duplicate an active load. */
internal class NotificationStickerLoadTracker {
    private val requested = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val retried = HashSet<String>()

    fun shouldStart(fileName: String?, retryPhase: Boolean): Boolean {
        if (fileName.isNullOrEmpty() || fileName in inFlight) return false
        return if (fileName in requested) retryPhase && retried.add(fileName) else true
    }

    fun onStarted(fileName: String?) {
        if (fileName.isNullOrEmpty()) return
        requested.add(fileName)
        inFlight.add(fileName)
    }

    fun onFinished(fileName: String?) { inFlight.remove(fileName) }
    fun isInFlight(fileName: String?): Boolean = fileName != null && fileName in inFlight
}
