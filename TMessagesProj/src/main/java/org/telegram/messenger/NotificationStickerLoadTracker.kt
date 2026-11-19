package org.telegram.messenger

/** Per-notification download budget; only the active file load is shared between notifications. */
internal class NotificationStickerLoadTracker {
    private data class Request(val fileName: String, val key: NotificationStickerMediaPolicy.Key, val token: Long)
    private val requested = HashSet<Request>()
    private val inFlight = HashSet<String>()
    private val retried = HashSet<Request>()

    fun shouldStart(fileName: String?, key: NotificationStickerMediaPolicy.Key, token: Long, retryPhase: Boolean): Boolean {
        if (fileName.isNullOrEmpty() || fileName in inFlight) return false
        val request = Request(fileName, key, token)
        return if (request in requested) retryPhase && retried.add(request) else true
    }

    fun onStarted(fileName: String?, key: NotificationStickerMediaPolicy.Key, token: Long) {
        if (fileName.isNullOrEmpty()) return
        requested.add(Request(fileName, key, token))
        inFlight.add(fileName)
    }

    // A file callback belongs to shared FileLoader work, never to another message's attempt budget.
    fun onFinished(fileName: String?) { inFlight.remove(fileName) }
    fun isInFlight(fileName: String?): Boolean = fileName != null && fileName in inFlight

    fun forgetSession(key: NotificationStickerMediaPolicy.Key) {
        requested.removeAll { it.key == key }
        retried.removeAll { it.key == key }
        // Do not clear inFlight: another message or the chat UI may still need that load.
    }

    fun retainSessions(activeTokens: Map<NotificationStickerMediaPolicy.Key, Long>) {
        requested.removeAll { activeTokens[it.key] != it.token }
        retried.removeAll { activeTokens[it.key] != it.token }
    }
}
