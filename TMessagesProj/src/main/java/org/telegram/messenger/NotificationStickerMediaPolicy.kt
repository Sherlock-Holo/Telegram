package org.telegram.messenger

/** Pure notification media state. All calls are confined to notificationsQueue by the owner. */
internal class NotificationStickerMediaPolicy {
    companion object {
        const val PREP_TIMEOUT_MS = 3000L
        const val RETRY_TIMEOUT_MS = 3000L
    }
    enum class State { NONE, WAITING, POSTED_TEXT_RETRYING, COMPLETED, CANCELLED }
    enum class ActionType { NONE, DEFER, POST_WITH_MEDIA, POST_TEXT_AND_RETRY, SILENT_FILL_MEDIA, RELEASE_PROTECT, SKIP }
    data class Key(@JvmField val account: Int, @JvmField val dialogId: Long, @JvmField val messageId: Int)
    class Decision(
        @JvmField val type: ActionType,
        @JvmField val token: Long = 0,
        @JvmField val notifyAboutLast: Boolean = false,
        @JvmField val protectOnlyAlertOnce: Boolean = false,
        @JvmField val releaseProtectFirst: Boolean = false,
        @JvmField val scheduleTimeoutMs: Long = 0,
    )
    class FlagPlan(
        @JvmField val onlyAlertOnce: Boolean,
        @JvmField val releaseProtectFirst: Boolean,
        @JvmField val armProtectAfterPost: Boolean,
    )
    /**
     * Preserve each conversation's messages and their alert intents. Same-dialog
     * records may merge, but a cancelled original message cannot donate its alert
     * to a different surviving id.
     */
    class RebuildTargets {
        @JvmField val messages = LinkedHashMap<Long, LinkedHashMap<Int, Boolean>>()
        fun record(dialogId: Long, messageId: Int, alert: Boolean) {
            val byMessage = messages.getOrPut(dialogId) { LinkedHashMap() }
            byMessage[messageId] = byMessage[messageId] == true || alert
        }
        fun containsDialog(dialogId: Long): Boolean = messages.containsKey(dialogId)
        fun dialogAlert(dialogId: Long): Boolean? {
            val byMessage = messages[dialogId] ?: return null
            return byMessage.values.any { it }
        }
        fun removeDialog(dialogId: Long): LinkedHashMap<Int, Boolean>? = messages.remove(dialogId)
    }
    private class Session(val key: Key, var token: Long) {
        var state = State.NONE
        var notifyAboutLast = false
        var protectDialog = false
    }
    private val sessions = HashMap<Key, Session>()
    private val keepFlagOffDialogs = HashSet<Pair<Int, Long>>()
    private var nextToken = 1L

    fun onBuild(key: Key?, mediaComplete: Boolean, notifyAboutLast: Boolean): Decision {
        if (key == null) return Decision(ActionType.NONE)
        val session = sessions[key]
        if (session?.state == State.CANCELLED) return Decision(ActionType.SKIP, session.token)
        if (mediaComplete) {
            if (session?.state == State.POSTED_TEXT_RETRYING || session?.state == State.WAITING) {
                return onMediaReady(key, session.token)
            }
            if (session?.state == State.COMPLETED) {
                return Decision(ActionType.POST_WITH_MEDIA, session.token, notifyAboutLast,
                    releaseProtectFirst = shouldReleaseProtect(key.account, key.dialogId, session))
            }
            val fresh = Session(key, nextToken++)
            fresh.state = State.COMPLETED
            fresh.notifyAboutLast = notifyAboutLast
            sessions[key] = fresh
            return Decision(ActionType.POST_WITH_MEDIA, fresh.token, notifyAboutLast,
                releaseProtectFirst = shouldReleaseProtect(key.account, key.dialogId, fresh))
        }
        if (session?.state == State.WAITING) return Decision(ActionType.DEFER, session.token, session.notifyAboutLast)
        if (session?.state == State.POSTED_TEXT_RETRYING) return Decision(ActionType.NONE, session.token, protectOnlyAlertOnce = true)
        if (notifyAboutLast || session == null || session.state == State.NONE) {
            val wait = session ?: Session(key, 0)
            wait.token = nextToken++
            wait.state = State.WAITING
            wait.notifyAboutLast = notifyAboutLast
            wait.protectDialog = false
            sessions[key] = wait
            return Decision(ActionType.DEFER, wait.token, notifyAboutLast, scheduleTimeoutMs = PREP_TIMEOUT_MS)
        }
        return Decision(ActionType.NONE)
    }

    fun onTimeout(key: Key, token: Long): Decision {
        val session = sessions[key]
        if (session == null || session.token != token || session.state != State.WAITING) return Decision(ActionType.SKIP, token)
        session.state = State.POSTED_TEXT_RETRYING
        session.protectDialog = true
        return Decision(ActionType.POST_TEXT_AND_RETRY, token, session.notifyAboutLast, true, scheduleTimeoutMs = RETRY_TIMEOUT_MS)
    }

    fun onMediaReady(key: Key, token: Long): Decision {
        val session = sessions[key]
        if (session == null || session.token != token) return Decision(ActionType.SKIP, token)
        if (session.state == State.WAITING) {
            session.state = State.COMPLETED
            session.protectDialog = false
            return Decision(ActionType.POST_WITH_MEDIA, token, session.notifyAboutLast)
        }
        if (session.state == State.POSTED_TEXT_RETRYING) {
            session.state = State.COMPLETED
            session.protectDialog = false
            markKeepFlagOff(key.account, key.dialogId)
            return Decision(ActionType.SILENT_FILL_MEDIA, token)
        }
        return Decision(ActionType.SKIP, token)
    }

    fun onRetryTimeout(key: Key, token: Long): Decision {
        val session = sessions[key]
        if (session == null || session.token != token || session.state != State.POSTED_TEXT_RETRYING) return Decision(ActionType.SKIP, token)
        session.state = State.COMPLETED
        val hadProtect = session.protectDialog
        session.protectDialog = false
        if (hadProtect) {
            markKeepFlagOff(key.account, key.dialogId)
            return Decision(ActionType.RELEASE_PROTECT, token)
        }
        return Decision(ActionType.NONE)
    }

    /** A real new message only flushes pending media in its own conversation. */
    fun abortWaitsForDialog(account: Int, dialogId: Long): Boolean {
        var protect = false
        for (session in sessions.values) {
            if (session.key.account != account || session.key.dialogId != dialogId) continue
            if (session.state == State.WAITING) {
                session.state = State.POSTED_TEXT_RETRYING
                session.protectDialog = true
                keepFlagOffDialogs.remove(account to dialogId)
                protect = true
            } else if (session.state == State.POSTED_TEXT_RETRYING && session.protectDialog) protect = true
        }
        return protect
    }

    /** First sound is unflagged. The owner arms the unchanged notification afterwards for a silent fill. */
    fun planFlags(account: Int, dialogId: Long, alertingPost: Boolean, defaultOnlyAlertOnce: Boolean): FlagPlan {
        val pending = hasProtectOnlyAlertOnce(account, dialogId)
        if (alertingPost) return FlagPlan(false, pending, pending)
        if (pending) return FlagPlan(true, false, false)
        if (hasKeepFlagOff(account, dialogId)) return FlagPlan(false, false, false)
        return FlagPlan(defaultOnlyAlertOnce, false, false)
    }
    fun onProtectArmed(account: Int, dialogId: Long) { keepFlagOffDialogs.remove(account to dialogId) }
    fun hasKeepFlagOff(account: Int, dialogId: Long): Boolean = (account to dialogId) in keepFlagOffDialogs
    fun waitingOrRetryingKeys(account: Int): List<Key> = sessions.values.filter { it.key.account == account && isActive(it.key) }.map { it.key }
    /** WAITING, RETRYING, and already-ready COMPLETED sessions that may still have queued work. */
    fun trackedKeys(account: Int): List<Key> = sessions.values.filter {
        it.key.account == account && (isActive(it.key) || it.state == State.COMPLETED)
    }.map { it.key }
    fun hasProtectOnlyAlertOnce(account: Int, dialogId: Long): Boolean = sessions.values.any { it.key.account == account && it.key.dialogId == dialogId && it.protectDialog }
    fun clearProtect(account: Int, dialogId: Long) {
        sessions.values.filter { it.key.account == account && it.key.dialogId == dialogId }.forEach { it.protectDialog = false }
    }
    fun cancelKey(key: Key) {
        val session = sessions[key] ?: return
        session.token = nextToken++
        session.state = State.CANCELLED
        session.protectDialog = false
        maybeClearKeepFlagOff(key.account, key.dialogId)
    }
    fun cancelDialog(account: Int, dialogId: Long) {
        sessions.keys.filter { it.account == account && it.dialogId == dialogId }.forEach { cancelKey(it) }
        keepFlagOffDialogs.remove(account to dialogId)
    }
    fun cancelAccount(account: Int) { sessions.keys.filter { it.account == account }.forEach { cancelKey(it) } }
    fun cancelMessage(account: Int, dialogId: Long, messageId: Int) { cancelKey(Key(account, dialogId, messageId)) }
    fun stateOf(key: Key): State = sessions[key]?.state ?: State.NONE
    fun tokenOf(key: Key): Long = sessions[key]?.token ?: 0
    fun isActive(key: Key): Boolean = stateOf(key) == State.WAITING || stateOf(key) == State.POSTED_TEXT_RETRYING
    fun pruneInactive() { sessions.entries.removeAll { !isActive(it.key) } }
    private fun shouldReleaseProtect(account: Int, dialogId: Long, except: Session): Boolean = sessions.values.any {
        it !== except && it.key.account == account && it.key.dialogId == dialogId && it.protectDialog
    }
    private fun markKeepFlagOff(account: Int, dialogId: Long) {
        if (!hasProtectOnlyAlertOnce(account, dialogId)) keepFlagOffDialogs.add(account to dialogId)
    }
    private fun maybeClearKeepFlagOff(account: Int, dialogId: Long) {
        if (!hasProtectOnlyAlertOnce(account, dialogId) && waitingOrRetryingKeys(account).none { it.dialogId == dialogId }) keepFlagOffDialogs.remove(account to dialogId)
    }
}
