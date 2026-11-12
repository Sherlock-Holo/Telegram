package org.telegram.messenger

/** Production timer registry, also exercised with a virtual queue in the timing tests. */
internal class NotificationStickerTimeouts(private val queue: Queue) {
    interface Queue {
        fun post(runnable: Runnable, delayMs: Long)
        fun cancel(runnable: Runnable)
    }
    private class Entry(val token: Long, val retry: Boolean, val runnable: Runnable)
    private val entries = HashMap<NotificationStickerMediaPolicy.Key, Entry>()

    /** Keep an existing deadline only if it belongs to the same generation AND phase. */
    fun ensure(key: NotificationStickerMediaPolicy.Key, token: Long, retry: Boolean, delayMs: Long, action: Runnable) {
        if (delayMs <= 0) return
        val previous = entries[key]
        if (previous != null && previous.token == token && previous.retry == retry) return
        cancel(key)
        lateinit var entry: Entry
        val runnable = Runnable {
            // Even an already dequeued old phase callback may not remove a replacement timer.
            if (entries[key] === entry) {
                entries.remove(key)
                action.run()
            }
        }
        entry = Entry(token, retry, runnable)
        entries[key] = entry
        queue.post(runnable, delayMs)
    }

    fun cancel(key: NotificationStickerMediaPolicy.Key) {
        entries.remove(key)?.let { queue.cancel(it.runnable) }
    }
}
