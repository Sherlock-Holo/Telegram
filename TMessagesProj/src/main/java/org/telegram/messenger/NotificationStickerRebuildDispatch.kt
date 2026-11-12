package org.telegram.messenger

/**
 * Production next-tick dispatcher for targeted notification rebuilds.
 * Tickets keep the original message identities and alert intents, plus a dialog
 * generation, account epoch, and per-dialog alert epoch. Deleting a ready message
 * drops only that message from pending work; a later generation of the same dialog
 * is not published by a stale closure. Timing and combination tests drive this class
 * with a virtual queue.
 */
internal fun interface Poster {
    fun post(runnable: Runnable)
}

internal fun interface Action {
    fun run(ticket: NotificationStickerRebuildDispatch.Ticket)
}

internal class NotificationStickerRebuildDispatch(private val poster: Poster) {
    private val dialogGenerations = HashMap<Long, Long>()
    private val alertEpochs = HashMap<Long, Long>()
    private val pending = LinkedHashMap<Long, Ticket>()
    private var accountEpoch = 0L
    private var nextTicketId = 1L

    class Ticket(
        @JvmField val ticketId: Long,
        @JvmField val dialogId: Long,
        @JvmField val alertsByMessage: LinkedHashMap<Int, Boolean>,
        @JvmField val dialogGeneration: Long,
        @JvmField val accountEpoch: Long,
        @JvmField val alertEpoch: Long,
    ) {
        val messageIds: Set<Int>
            get() = alertsByMessage.keys
    }

    fun currentGeneration(dialogId: Long): Long = dialogGenerations[dialogId] ?: 0L

    fun currentAlertEpoch(dialogId: Long): Long = alertEpochs[dialogId] ?: 0L

    fun isCurrent(ticket: Ticket): Boolean {
        return ticket.accountEpoch == accountEpoch &&
            ticket.dialogGeneration == currentGeneration(ticket.dialogId) &&
            pending[ticket.ticketId] === ticket &&
            ticket.alertsByMessage.isNotEmpty()
    }

    fun pendingCount(): Int = pending.size

    fun invalidate(dialogId: Long) {
        dialogGenerations[dialogId] = currentGeneration(dialogId) + 1
        alertEpochs.remove(dialogId)
        pending.entries.removeAll { it.value.dialogId == dialogId }
    }

    fun invalidateAll() {
        accountEpoch++
        val ids = ArrayList(dialogGenerations.keys)
        for (id in ids) {
            dialogGenerations[id] = currentGeneration(id) + 1
        }
        alertEpochs.clear()
        pending.clear()
    }

    /**
     * Drop one original message from any still-queued tickets. Tickets that lose
     * all of their messages are removed and will not run. Sibling messages in the
     * same dialog keep their own alert intent.
     */
    fun invalidateMessage(dialogId: Long, messageId: Int) {
        val stale = ArrayList<Long>()
        for ((id, ticket) in pending) {
            if (ticket.dialogId != dialogId) continue
            ticket.alertsByMessage.remove(messageId)
            if (ticket.alertsByMessage.isEmpty()) {
                stale.add(id)
            }
        }
        for (id in stale) {
            pending.remove(id)
        }
    }

    fun bumpAlertEpoch(dialogId: Long) {
        alertEpochs[dialogId] = currentAlertEpoch(dialogId) + 1
    }

    fun enqueue(dialogId: Long, alertsByMessage: Map<Int, Boolean>, action: Action) {
        if (alertsByMessage.isEmpty()) return
        val ticketId = nextTicketId++
        val ticket = Ticket(
            ticketId,
            dialogId,
            LinkedHashMap(alertsByMessage),
            currentGeneration(dialogId),
            accountEpoch,
            currentAlertEpoch(dialogId),
        )
        pending[ticketId] = ticket
        poster.post {
            val current = pending.remove(ticketId) ?: return@post
            if (!isCurrentAfterDequeue(current)) return@post
            if (current.alertsByMessage.isEmpty()) return@post
            action.run(current)
        }
    }

    fun enqueue(dialogId: Long, messageId: Int, alert: Boolean, action: Action) {
        enqueue(dialogId, mapOf(messageId to alert), action)
    }

    private fun isCurrentAfterDequeue(ticket: Ticket): Boolean {
        return ticket.accountEpoch == accountEpoch &&
            ticket.dialogGeneration == currentGeneration(ticket.dialogId)
    }
}

/** Shared publish gate used by Images and by dispatch / combination tests. */
internal object NotificationStickerTargetGuard {
    @JvmStatic
    fun shouldPublish(
        dialogId: Long,
        openedDialogId: Long,
        requestedMessageIds: Set<Int>,
        presentEligibleMessageIds: Set<Int>,
    ): Boolean {
        if (openedDialogId != 0L && openedDialogId == dialogId) return false
        if (requestedMessageIds.isEmpty()) return false
        return requestedMessageIds.any { it in presentEligibleMessageIds }
    }

    @JvmStatic
    fun survivingAlert(alertsByMessage: Map<Int, Boolean>, presentEligibleMessageIds: Set<Int>): Boolean {
        for ((messageId, alert) in alertsByMessage) {
            if (alert && messageId in presentEligibleMessageIds) return true
        }
        return false
    }

    @JvmStatic
    fun shouldSound(survivingAlert: Boolean, ticketAlertEpoch: Long, currentAlertEpoch: Long): Boolean {
        return survivingAlert && ticketAlertEpoch == currentAlertEpoch
    }
}
