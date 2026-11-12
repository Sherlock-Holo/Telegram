package org.telegram.messenger

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AnimatedFileNative
import org.telegram.ui.Components.RLottieNative
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Fork: still images for sticker / single emoji notifications (static webp, TGS and webm stickers, big single
 * emoji), moved out of the upstream NotificationsController to keep the fork's footprint there small.
 *
 * One instance per NotificationsController (i.e. per account). Everything except [decodeStickerFrame] (which
 * runs on the shared notificationStickerQueue) is called on NotificationsController's notificationsQueue.
 *
 * Complete media only is attached: a full static WebP, or a decoded TGS/WebM still cover. Thumbnails and
 * temporary msg_media_raw URIs are never posted. A hot cache hits immediately. A cold cache waits
 * [NotificationStickerMediaPolicy.PREP_TIMEOUT_MS] for download / frame decode, then posts text and allows
 * exactly one bounded retry of [NotificationStickerMediaPolicy.RETRY_TIMEOUT_MS]. Shared FileLoader
 * downloads used by the chat UI are not cancelled when a notification wait is dropped.
 */
internal class NotificationStickerImages(
    private val currentAccount: Int,
    private val notificationsQueue: DispatchQueue2,
    private val controller: NotificationsController,
) {

    /** What to attach: [file] for the notification, its [mimeType] (null: sniff), and an optional msg_media_raw [fallback]. */
    class Image(
        @JvmField val file: File?,
        @JvmField val mimeType: String?,
        @JvmField val fallback: File?,
    )

    // All of these are only touched on notificationsQueue.
    private val pendingStickerDecodes = HashSet<String>() // frame cache names being decoded to a still frame
    private val failedStickerDecodes = HashSet<String>()  // frame cache names that failed / timed out
    private val retriedStickerDecodes = HashSet<String>() // one extra decode attempt during the bounded retry
    private val policy = NotificationStickerMediaPolicy()
    private val loadTracker = NotificationStickerLoadTracker()
    private val timeouts = NotificationStickerTimeouts(object : NotificationStickerTimeouts.Queue {
        override fun post(runnable: Runnable, delayMs: Long) { notificationsQueue.postRunnable(runnable, delayMs) }
        override fun cancel(runnable: Runnable) { notificationsQueue.cancelRunnable(runnable) }
    })
    private val rebuildDispatch = NotificationStickerRebuildDispatch { notificationsQueue.postRunnable(it) }
    private val skippedDialogs = HashSet<Long>()
    private val lastCompleteImages = HashMap<NotificationStickerMediaPolicy.Key, Image>()

    private val accountInstance: AccountInstance = AccountInstance.getInstance(currentAccount)
    private val fileLoader: FileLoader get() = accountInstance.fileLoader
    private val downloadController: DownloadController get() = accountInstance.downloadController

    /** NotificationCenter.fileLoaded, already on notificationsQueue (after the voice note check). */
    fun onFileLoaded(path: String?) {
        val ours = path != null && loadTracker.isInFlight(path)
        if (path != null) {
            loadTracker.onFinished(path)
        }
        if (ours || policy.waitingOrRetryingKeys(currentAccount).isNotEmpty()) {
            continuePrepAfterDownload()
            notifyMediaMaybeReady(true)
        }
    }

    /** NotificationCenter.fileLoadFailed, any thread: forget the pending load on notificationsQueue. */
    fun onFileLoadFailed(path: String?) {
        notificationsQueue.postRunnable { if (path != null) loadTracker.onFinished(path) }
    }

    /**
     * Called at the start of showOrUpdateNotification after the last message has passed the global dismissDate.
     * Deferral is per dialog: a waiting sticker in one chat does not block or flush another chat.
     */
    fun onBeforeShow(lastMessageObject: MessageObject, notifyAboutLast: Boolean): Boolean {
        skippedDialogs.clear()
        syncWithPushMessages()
        val ready = collectReadyMedia()
        // This build already has a target. Merge only that dialog's surviving alert intent;
        // queue the other targets independently so neither a newer chat nor a story steals the sound.
        val currentReady = ready.removeDialog(lastMessageObject.getDialogId())
        val readyAlert = currentReady?.values?.any { it } == true
        val alertCurrent = notifyAboutLast || readyAlert
        for ((dialogId, alertsByMessage) in ready.messages) {
            enqueueTargetedRebuild(dialogId, alertsByMessage)
        }

        if (!lastMessageObject.isStoryPush) {
            val lastDialogId = lastMessageObject.getDialogId()
            val lastEligible = isStickerLike(lastMessageObject) && isNotificationEligible(lastMessageObject)
            val lastKey = if (lastEligible) keyOf(lastMessageObject) else null
            val lastComplete = lastKey != null && getCompleteImage(lastMessageObject) != null
            val lastCanPrepare = lastKey != null && !lastComplete && canPrepare(lastMessageObject)
            if (lastCanPrepare && lastKey != null) {
                val decision = policy.onBuild(lastKey, false, alertCurrent)
                if (decision.type == NotificationStickerMediaPolicy.ActionType.DEFER) {
                    prepare(lastMessageObject, allowRetry = false)
                    scheduleTimeout(lastKey, decision.token, decision.scheduleTimeoutMs, retry = false)
                    skippedDialogs.add(lastDialogId)
                }
            } else if (lastComplete && lastKey != null) {
                policy.onBuild(lastKey, true, alertCurrent)
            }
            if (alertCurrent && !skippedDialogs.contains(lastDialogId)) {
                if (policy.abortWaitsForDialog(currentAccount, lastDialogId)) {
                    armRetriesForDialog(lastDialogId)
                }
            }
        }
        markSkippedWaitingDialogs()
        armRetriesForPostedDialogs()
        return alertCurrent
    }

    fun shouldSkipDialog(dialogId: Long): Boolean {
        return skippedDialogs.contains(dialogId)
    }

    fun hasPostableDialogBesides(dialogId: Long): Boolean {
        val prefs = controller.notificationSettingsForStickerImages
        val globalDismiss = prefs.getInt("dismissDate", 0)
        for (messageObject in controller.pushMessagesForStickerImages) {
            val other = messageObject.getDialogId()
            if (other == dialogId || skippedDialogs.contains(other) || messageObject.isStoryPush) {
                continue
            }
            if (messageObject.messageOwner.date <= globalDismiss) {
                continue
            }
            if (messageObject.messageOwner.date <= prefs.getInt("dismissDate$other", 0)) {
                continue
            }
            return true
        }
        return false
    }

    fun planFlags(dialogId: Long, alertingPost: Boolean, defaultOnlyAlertOnce: Boolean): NotificationStickerMediaPolicy.FlagPlan {
        return policy.planFlags(currentAccount, dialogId, alertingPost, defaultOnlyAlertOnce)
    }

    fun onProtectArmed(dialogId: Long) {
        policy.onProtectArmed(currentAccount, dialogId)
    }

    fun clearBuildOverrides() {
        skippedDialogs.clear()
    }

    /**
     * Re-check at execution: opened, dismissed, read, deleted, or logged-out targets
     * must not publish, even if the message is still in [pushMessages].
     */
    fun canPublishTargetedRebuild(dialogId: Long, messageIds: IntArray): Boolean {
        val opened = controller.openedDialogIdForStickerImages
        val requested = HashSet<Int>(messageIds.size)
        for (messageId in messageIds) {
            requested.add(messageId)
        }
        val live = liveEligibleIds(dialogId, requested)
        return NotificationStickerTargetGuard.shouldPublish(dialogId, opened, requested, live)
    }

    fun noteDialogAlerted(dialogId: Long) {
        rebuildDispatch.bumpAlertEpoch(dialogId)
    }

    fun syncWithPushMessages() {
        val present = HashSet<NotificationStickerMediaPolicy.Key>()
        for (messageObject in controller.pushMessagesForStickerImages) {
            if (isStickerLike(messageObject) && isNotificationEligible(messageObject)) {
                present.add(keyOf(messageObject))
            }
        }
        // Cover COMPLETED sessions that already queued a rebuild, not only WAITING/RETRYING.
        for (key in ArrayList(policy.trackedKeys(currentAccount))) {
            if (!present.contains(key)) {
                lastCompleteImages.remove(key)
                cancelSession(key)
                rebuildDispatch.invalidateMessage(key.dialogId, key.messageId)
            }
        }
    }

    fun cancelDialog(dialogId: Long) {
        for (key in ArrayList(policy.waitingOrRetryingKeys(currentAccount))) {
            if (key.dialogId == dialogId) {
                cancelSession(key)
            }
        }
        policy.cancelDialog(currentAccount, dialogId)
        lastCompleteImages.keys.removeAll { it.dialogId == dialogId }
        rebuildDispatch.invalidate(dialogId)
    }

    fun cancelAccount() {
        for (key in ArrayList(policy.waitingOrRetryingKeys(currentAccount))) {
            cancelSession(key)
        }
        policy.cancelAccount(currentAccount)
        lastCompleteImages.clear()
        rebuildDispatch.invalidateAll()
    }

    fun cancelMessage(dialogId: Long, messageId: Int) {
        val key = NotificationStickerMediaPolicy.Key(currentAccount, dialogId, messageId)
        lastCompleteImages.remove(key)
        cancelSession(key)
        rebuildDispatch.invalidateMessage(dialogId, messageId)
    }

    /**
     * The image for a sticker message or a single emoji shown as a big sticker, or null (no such message, or
     * nothing complete on disk yet). The caller has already checked passcode and secret media. notificationsQueue only.
     * Never returns a thumbnail or a temporary msg_media_raw path.
     */
    fun get(messageObject: MessageObject): Image? {
        if (!isStickerLike(messageObject)) {
            return null
        }
        val complete = getCompleteImage(messageObject)
        if (complete != null) {
            lastCompleteImages[keyOf(messageObject)] = complete
            return complete
        }
        val previous = lastCompleteImages[keyOf(messageObject)]
        val previousFile = previous?.file
        if (previousFile != null && sniffNotificationImageMime(previousFile) != null) {
            return previous
        }
        return null
    }

    private fun notifyMediaMaybeReady(rebuildNow: Boolean) {
        syncWithPushMessages()
        val ready = collectReadyMedia()
        if (rebuildNow) {
            for ((dialogId, alertsByMessage) in ready.messages) {
                enqueueTargetedRebuild(dialogId, alertsByMessage)
            }
        }
    }

    private fun collectReadyMedia(): NotificationStickerMediaPolicy.RebuildTargets {
        val targets = NotificationStickerMediaPolicy.RebuildTargets()
        for (key in ArrayList(policy.waitingOrRetryingKeys(currentAccount))) {
            val messageObject = findMessage(key) ?: continue
            if (getCompleteImage(messageObject) == null) {
                continue
            }
            val decision = policy.onMediaReady(key, policy.tokenOf(key))
            if (decision.type == NotificationStickerMediaPolicy.ActionType.SKIP) {
                continue
            }
            cancelTimeout(key)
            when (decision.type) {
                NotificationStickerMediaPolicy.ActionType.POST_WITH_MEDIA -> {
                    targets.record(key.dialogId, key.messageId, decision.notifyAboutLast)
                }
                NotificationStickerMediaPolicy.ActionType.SILENT_FILL_MEDIA -> {
                    targets.record(key.dialogId, key.messageId, false)
                }
                else -> {}
            }
        }
        return targets
    }

    private fun continuePrepAfterDownload() {
        syncWithPushMessages()
        for (key in ArrayList(policy.waitingOrRetryingKeys(currentAccount))) {
            val messageObject = findMessage(key) ?: continue
            val eligible = isNotificationEligible(messageObject)
            val complete = getCompleteImage(messageObject) != null
            val needsDecode = !complete && needsNotificationDecode(messageObject)
            val action = NotificationStickerPrepBridge.afterDownload(policy.stateOf(key), complete, needsDecode, eligible)
            if (action == NotificationStickerPrepBridge.AfterDownload.PREPARE_DECODE) {
                val allowRetry = policy.stateOf(key) == NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING
                prepare(messageObject, allowRetry)
            }
        }
    }

    private fun needsNotificationDecode(messageObject: MessageObject): Boolean {
        if (getCompleteImage(messageObject) != null || !canPrepare(messageObject)) {
            return false
        }
        val document = messageObject.getDocument() ?: return false
        val singleEmoji = messageObject.messageOwner.media !is TLRPC.TL_messageMediaDocument
        var fitzModifier = if (singleEmoji) getEmojiFitzModifier(messageObject.emojiAnimatedStickerColor) else 0
        val staticWebp = fitzModifier == 0 && isStaticWebpSticker(messageObject)
        if (staticWebp) {
            return false
        }
        val lottie = messageObject.isAnimatedSticker() || "application/x-tgsticker" == document.mime_type
        val video = !lottie && (messageObject.isVideoSticker() || "video/webm" == document.mime_type)
        if (!lottie && !video) {
            return false
        }
        val documentFile = findStickerDocumentFile(if (singleEmoji) null else messageObject, document)
        return documentFile != null && document.size > 0 && document.size <= NOTIFICATION_STICKER_MAX_DOCUMENT_SIZE
    }

    private fun enqueueTargetedRebuild(dialogId: Long, alertsByMessage: Map<Int, Boolean>) {
        rebuildDispatch.enqueue(dialogId, alertsByMessage) { ticket ->
            publishTargetedRebuild(ticket)
        }
    }

    private fun publishTargetedRebuild(ticket: NotificationStickerRebuildDispatch.Ticket) {
        val live = liveEligibleIds(ticket.dialogId, ticket.messageIds)
        if (!NotificationStickerTargetGuard.shouldPublish(
                ticket.dialogId,
                controller.openedDialogIdForStickerImages,
                ticket.messageIds,
                live,
            )
        ) {
            return
        }
        val alert = NotificationStickerTargetGuard.shouldSound(
            NotificationStickerTargetGuard.survivingAlert(ticket.alertsByMessage, live),
            ticket.alertEpoch,
            rebuildDispatch.currentAlertEpoch(ticket.dialogId),
        )
        controller.rebuildNotificationFromStickerImages(alert, ticket.dialogId, live.toIntArray())
    }

    private fun publishTargetedRebuild(key: NotificationStickerMediaPolicy.Key, alert: Boolean, alertEpoch: Long) {
        val requested = setOf(key.messageId)
        val live = liveEligibleIds(key.dialogId, requested)
        if (!NotificationStickerTargetGuard.shouldPublish(
                key.dialogId,
                controller.openedDialogIdForStickerImages,
                requested,
                live,
            )
        ) {
            return
        }
        val sound = NotificationStickerTargetGuard.shouldSound(alert, alertEpoch, rebuildDispatch.currentAlertEpoch(key.dialogId))
        controller.rebuildNotificationFromStickerImages(sound, key.dialogId, live.toIntArray())
    }

    private fun liveEligibleIds(dialogId: Long, requested: Set<Int>): Set<Int> {
        val live = HashSet<Int>()
        for (messageObject in controller.pushMessagesForStickerImages) {
            if (messageObject.getDialogId() == dialogId &&
                requested.contains(messageObject.getId()) &&
                isNotificationEligible(messageObject)
            ) {
                live.add(messageObject.getId())
            }
        }
        return live
    }

    private fun markSkippedWaitingDialogs() {
        val seen = HashSet<Long>()
        for (messageObject in controller.pushMessagesForStickerImages) {
            val dialogId = messageObject.getDialogId()
            if (!seen.add(dialogId) || skippedDialogs.contains(dialogId) || messageObject.isStoryPush) {
                continue
            }
            if (isStickerLike(messageObject) && isNotificationEligible(messageObject)
                && getCompleteImage(messageObject) == null && canPrepare(messageObject)
                && policy.stateOf(keyOf(messageObject)) == NotificationStickerMediaPolicy.State.WAITING) {
                skippedDialogs.add(dialogId)
            }
        }
    }

    private fun armRetriesForDialog(dialogId: Long) {
        for (key in policy.waitingOrRetryingKeys(currentAccount)) {
            if (key.dialogId == dialogId && policy.stateOf(key) == NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING) {
                val messageObject = findMessage(key)
                if (messageObject != null) {
                    prepare(messageObject, allowRetry = true)
                }
                scheduleTimeout(key, policy.tokenOf(key), NotificationStickerMediaPolicy.RETRY_TIMEOUT_MS, retry = true)
            }
        }
    }

    private fun armRetriesForPostedDialogs() {
        for (messageObject in controller.pushMessagesForStickerImages) {
            if (skippedDialogs.contains(messageObject.getDialogId())) {
                continue
            }
            if (!isStickerLike(messageObject) || !isNotificationEligible(messageObject) || getCompleteImage(messageObject) != null || !canPrepare(messageObject)) {
                continue
            }
            val key = keyOf(messageObject)
            val state = policy.stateOf(key)
            if (state == NotificationStickerMediaPolicy.State.WAITING || state == NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING) {
                if (state == NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING) {
                    prepare(messageObject, allowRetry = true)
                    scheduleTimeout(key, policy.tokenOf(key), NotificationStickerMediaPolicy.RETRY_TIMEOUT_MS, retry = true)
                }
                continue
            }
            if (state == NotificationStickerMediaPolicy.State.CANCELLED || state == NotificationStickerMediaPolicy.State.COMPLETED) {
                continue
            }
            val wait = policy.onBuild(key, false, false)
            if (wait.type == NotificationStickerMediaPolicy.ActionType.DEFER) {
                val timeout = policy.onTimeout(key, wait.token)
                if (timeout.type == NotificationStickerMediaPolicy.ActionType.POST_TEXT_AND_RETRY) {
                    prepare(messageObject, allowRetry = true)
                    scheduleTimeout(key, wait.token, timeout.scheduleTimeoutMs, retry = true)
                }
            }
        }
    }

    private fun scheduleTimeout(key: NotificationStickerMediaPolicy.Key, token: Long, delayMs: Long, retry: Boolean) {
        if (delayMs <= 0) {
            return
        }
        val generation = rebuildDispatch.currentGeneration(key.dialogId)
        val alertEpoch = rebuildDispatch.currentAlertEpoch(key.dialogId)
        timeouts.ensure(key, token, retry, delayMs, Runnable {
            syncWithPushMessages()
            if (rebuildDispatch.currentGeneration(key.dialogId) != generation) {
                return@Runnable
            }
            if (retry) {
                val decision = policy.onRetryTimeout(key, token)
                if (decision.type == NotificationStickerMediaPolicy.ActionType.RELEASE_PROTECT) {
                    publishTargetedRebuild(key, false, alertEpoch)
                }
            } else {
                val decision = policy.onTimeout(key, token)
                if (decision.type == NotificationStickerMediaPolicy.ActionType.POST_TEXT_AND_RETRY) {
                    val scheduled = decision.scheduleTimeoutMs
                    publishTargetedRebuild(key, decision.notifyAboutLast, alertEpoch)
                    if (scheduled > 0 && policy.stateOf(key) == NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING) {
                        scheduleTimeout(key, token, scheduled, retry = true)
                    }
                }
            }
        })
    }

    private fun cancelTimeout(key: NotificationStickerMediaPolicy.Key) {
        timeouts.cancel(key)
    }

    private fun cancelSession(key: NotificationStickerMediaPolicy.Key) {
        cancelTimeout(key)
        policy.cancelKey(key)
        // Do not cancel FileLoader: the same document may be needed by the chat UI or another notification.
    }

    private fun keyOf(messageObject: MessageObject): NotificationStickerMediaPolicy.Key {
        return NotificationStickerMediaPolicy.Key(currentAccount, messageObject.getDialogId(), messageObject.getId())
    }

    private fun findMessage(key: NotificationStickerMediaPolicy.Key): MessageObject? {
        for (messageObject in controller.pushMessagesForStickerImages) {
            if (messageObject.getId() == key.messageId && messageObject.getDialogId() == key.dialogId) {
                return messageObject
            }
        }
        return null
    }

    private fun isStickerLike(messageObject: MessageObject): Boolean {
        return messageObject.messageOwner.media is TLRPC.TL_messageMediaDocument && (messageObject.isSticker() || messageObject.isAnimatedSticker()) || isSingleEmojiNotificationSticker(messageObject)
    }

    private fun isNotificationEligible(messageObject: MessageObject): Boolean {
        if (messageObject.isStoryPush || messageObject.isSecretMedia()) {
            return false
        }
        if (DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
            return false
        }
        val opened = controller.openedDialogIdForStickerImages
        if (opened != 0L && opened == messageObject.getDialogId()) {
            return false
        }
        val prefs = controller.notificationSettingsForStickerImages
        val date = messageObject.messageOwner.date
        if (date <= prefs.getInt("dismissDate", 0)) {
            return false
        }
        if (date <= prefs.getInt("dismissDate" + messageObject.getDialogId(), 0)) {
            return false
        }
        return true
    }

    /**
     * Complete static WebP or a decoded TGS/WebM cover already on disk. No loads, no thumbs, no temp URIs.
     */
    private fun getCompleteImage(messageObject: MessageObject): Image? {
        try {
            val document: TLRPC.Document? = messageObject.getDocument()
            if (document == null || DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
                return null
            }
            val singleEmoji = messageObject.messageOwner.media !is TLRPC.TL_messageMediaDocument
            if (singleEmoji && !isSingleEmojiNotificationSticker(messageObject)) {
                return null
            }
            var fitzModifier = if (singleEmoji) getEmojiFitzModifier(messageObject.emojiAnimatedStickerColor) else 0
            val staticWebp = fitzModifier == 0 && isStaticWebpSticker(messageObject)
            val documentFile = findStickerDocumentFile(if (singleEmoji) null else messageObject, document)
            val lottie = !staticWebp && (messageObject.isAnimatedSticker() || "application/x-tgsticker" == document.mime_type)
            val video = !staticWebp && !lottie && (messageObject.isVideoSticker() || "video/webm" == document.mime_type)
            if (fitzModifier != 0 && !lottie) {
                fitzModifier = 0
            }
            if (lottie || video) {
                val frameFile = getStickerFrameCacheFile(document.id, fitzModifier)
                val frameMime = sniffNotificationImageMime(frameFile)
                if (frameMime != null && frameFile != null) {
                    val now = System.currentTimeMillis()
                    if (now - frameFile.lastModified() > 60 * 60 * 1000L) {
                        frameFile.setLastModified(now)
                    }
                    return Image(frameFile, frameMime, null)
                }
                return null
            }
            if (staticWebp && documentFile != null) {
                val mime = sniffNotificationImageMime(documentFile)
                if (mime != null) {
                    return Image(documentFile, mime, null)
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return null
    }

    private fun canPrepare(messageObject: MessageObject): Boolean {
        try {
            val document: TLRPC.Document? = messageObject.getDocument()
            if (document == null || DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
                return false
            }
            val singleEmoji = messageObject.messageOwner.media !is TLRPC.TL_messageMediaDocument
            var fitzModifier = if (singleEmoji) getEmojiFitzModifier(messageObject.emojiAnimatedStickerColor) else 0
            val staticWebp = fitzModifier == 0 && isStaticWebpSticker(messageObject)
            val smallDocument = document.size > 0 && document.size <= NOTIFICATION_STICKER_MAX_DOCUMENT_SIZE
            if (!smallDocument) {
                return false
            }
            val documentFile = findStickerDocumentFile(if (singleEmoji) null else messageObject, document)
            val lottie = !staticWebp && (messageObject.isAnimatedSticker() || "application/x-tgsticker" == document.mime_type)
            val video = !staticWebp && !lottie && (messageObject.isVideoSticker() || "video/webm" == document.mime_type)
            if (!(staticWebp || lottie || video)) {
                return false
            }
            if (documentFile != null) {
                return true
            }
            return canAutoDownloadStickerDocument(messageObject, singleEmoji, document)
        } catch (e: Exception) {
            FileLog.e(e)
            return false
        }
    }

    /**
     * Starts (or continues) the document download and, when the file is already on disk, a still-frame decode.
     * Does not request thumbs and does not cancel shared FileLoader work.
     */
    private fun prepare(messageObject: MessageObject, allowRetry: Boolean) {
        try {
            val document: TLRPC.Document? = messageObject.getDocument()
            if (document == null || DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
                return
            }
            val singleEmoji = messageObject.messageOwner.media !is TLRPC.TL_messageMediaDocument
            var fitzModifier = if (singleEmoji) getEmojiFitzModifier(messageObject.emojiAnimatedStickerColor) else 0
            var parentObject: Any = messageObject
            if (singleEmoji) {
                val inputStickerSet = MessageObject.getInputStickerSet(document)
                if (inputStickerSet != null) {
                    parentObject = inputStickerSet
                }
            }
            val loadParent = parentObject
            val staticWebp = fitzModifier == 0 && isStaticWebpSticker(messageObject)
            val smallDocument = document.size > 0 && document.size <= NOTIFICATION_STICKER_MAX_DOCUMENT_SIZE
            val documentFile = findStickerDocumentFile(if (singleEmoji) null else messageObject, document)
            val lottie = !staticWebp && (messageObject.isAnimatedSticker() || "application/x-tgsticker" == document.mime_type)
            val video = !staticWebp && !lottie && (messageObject.isVideoSticker() || "video/webm" == document.mime_type)
            if (fitzModifier != 0 && !lottie) {
                fitzModifier = 0
            }
            val mayLoadDocument = documentFile == null && smallDocument && (staticWebp || lottie || video) && canAutoDownloadStickerDocument(messageObject, singleEmoji, document)
            if (lottie || video) {
                val frameFile = getStickerFrameCacheFile(document.id, fitzModifier)
                if (sniffNotificationImageMime(frameFile) != null) {
                    return
                }
                if (documentFile != null && smallDocument) {
                    requestStickerFrameDecode(frameFile, documentFile, lottie, fitzModifier, allowRetry)
                }
            }
            if (staticWebp) {
                if (documentFile == null && mayLoadDocument) {
                    requestStickerNotificationLoad(FileLoader.getAttachFileName(document), allowRetry) { fileLoader.loadFile(document, loadParent, FileLoader.PRIORITY_HIGH, 1) }
                }
            } else if ((lottie || video) && mayLoadDocument) {
                requestStickerNotificationLoad(FileLoader.getAttachFileName(document), allowRetry) { fileLoader.loadFile(document, loadParent, FileLoader.PRIORITY_HIGH, 1) }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    /**
     * Same rule the chat UI uses for auto-downloading stickers (DownloadController treats them as photos:
     * Wi-Fi / mobile / roaming preset enabled + per-peer mask), plus Android's system Data Saver on metered
     * networks. When this says no, the notification stays text-only.
     */
    private fun canAutoDownloadStickerDocument(messageObject: MessageObject, singleEmoji: Boolean, document: TLRPC.Document): Boolean {
        return try {
            if (singleEmoji) {
                // the message has no media, so canDownloadMedia(Message) would say no; use the current network's
                // preset for stickers (photo type) directly
                if (!downloadController.canDownloadMedia(DownloadController.AUTODOWNLOAD_TYPE_PHOTO, document.size)) {
                    return false
                }
            } else if (downloadController.canDownloadMedia(messageObject.messageOwner) == 0) {
                return false
            }
            val connectivityManager = ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            !(connectivityManager != null && connectivityManager.isActiveNetworkMetered
                    && connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    /**
     * The sticker document on disk: chat UI loads stickers into the cache dir, so check that first.
     * messageObject is null for a single emoji, whose document is not the message's media.
     */
    private fun findStickerDocumentFile(messageObject: MessageObject?, document: TLRPC.Document): File? {
        var file = fileLoader.getPathToAttach(document, true)
        if (file.exists() && file.length() > 0) {
            return file
        }
        if (messageObject == null) {
            return null
        }
        file = fileLoader.getPathToMessage(messageObject.messageOwner)
        if (file.exists() && file.length() > 0) {
            return file
        }
        return null
    }

    /** Starts a document load, or restarts it once during the bounded retry if the first attempt failed. Does not cancel shared FileLoader work. */
    private fun requestStickerNotificationLoad(fileName: String?, allowRetry: Boolean, load: Runnable) {
        if (fileName == null || TextUtils.isEmpty(fileName)) {
            return
        }
        if (!loadTracker.shouldStart(fileName, allowRetry)) {
            return
        }
        loadTracker.onStarted(fileName)
        load.run()
    }

    /**
     * Decodes one still frame of a TGS / webm sticker on notificationStickerQueue, never on notificationsQueue.
     * Success tries to complete a wait / silent fill; failure or a [NOTIFICATION_STICKER_DECODE_TIMEOUT] timeout
     * marks the document failed. One extra decode is allowed during the bounded notification retry.
     * notificationsQueue only.
     */
    private fun requestStickerFrameDecode(out: File?, source: File, lottie: Boolean, fitzModifier: Int, allowDecodeRetry: Boolean) {
        if (out == null) {
            return
        }
        val key = out.name
        if (pendingStickerDecodes.contains(key)) {
            return
        }
        if (failedStickerDecodes.contains(key)) {
            if (!allowDecodeRetry || !retriedStickerDecodes.add(key)) {
                return
            }
            failedStickerDecodes.remove(key)
        }
        if (failedStickerDecodes.size > 500) {
            failedStickerDecodes.clear()
        }
        if (retriedStickerDecodes.size > 500) {
            retriedStickerDecodes.clear()
        }
        pendingStickerDecodes.add(key)
        val timeout = Runnable {
            if (pendingStickerDecodes.remove(key)) {
                FileLog.d("sticker notification frame decode timed out $key")
                failedStickerDecodes.add(key)
            }
        }
        notificationsQueue.postRunnable(timeout, NOTIFICATION_STICKER_DECODE_TIMEOUT)
        val account = currentAccount
        getNotificationStickerQueue().postRunnable {
            val ok = decodeStickerFrame(source, out, lottie, fitzModifier, account)
            notificationsQueue.postRunnable(Runnable {
                notificationsQueue.cancelRunnable(timeout)
                val pending = pendingStickerDecodes.remove(key)
                if (ok) {
                    failedStickerDecodes.remove(key)
                    notifyMediaMaybeReady(true)
                } else if (pending) {
                    failedStickerDecodes.add(key)
                }
            })
        }
    }

    companion object {
        private const val NOTIFICATION_STICKER_MAX_DOCUMENT_SIZE = 1024L * 1024
        private const val NOTIFICATION_STICKER_REBUILD_DELAY = 300L
        private const val NOTIFICATION_STICKER_FRAME_SIZE = 512
        private const val NOTIFICATION_STICKER_DECODE_TIMEOUT = 5000L
        private const val NOTIFICATION_STICKER_CACHE_MAX_FILES = 200
        private const val NOTIFICATION_STICKER_CACHE_MAX_BYTES = 20L * 1024 * 1024
        private const val NOTIFICATION_STICKER_CACHE_MAX_AGE = 7L * 24 * 60 * 60 * 1000

        private var notificationStickerQueue: DispatchQueue? = null
        private var notificationStickerCacheDir: File? = null

        /**
         * Mime type for the attached file: jpeg for a regular photo; for a sticker image the picked one, unless
         * the caller replaced the file (spoiler blur), then sniffed, defaulting to jpeg.
         */
        @JvmStatic
        fun mimeType(image: Image?, attach: File): String {
            val stickerAttach = image?.file ?: return "image/jpeg"
            val pickedMime = image.mimeType
            if (attach === stickerAttach && pickedMime != null) {
                return pickedMime
            }
            return sniffNotificationImageMime(attach) ?: "image/jpeg"
        }

        /** Adds the thumb fallback of a still downloading static webp to the msg_media_raw URI. */
        @JvmStatic
        fun appendFallback(uri: Uri.Builder, image: Image?) {
            val fallback = image?.fallback
            if (fallback != null) {
                uri.appendQueryParameter("fallback", fallback.absolutePath)
            }
        }

        @Synchronized
        private fun getNotificationStickerQueue(): DispatchQueue {
            var queue = notificationStickerQueue
            if (queue == null) {
                queue = DispatchQueue("notificationStickerQueue")
                notificationStickerQueue = queue
            }
            return queue
        }

        /**
         * files/cache/notification_stickers: covered by the "cache" files-path of res/xml/provider_paths.xml, so
         * FileProvider can hand it to System UI.
         */
        @Synchronized
        private fun getStickerFrameCacheDir(): File? {
            val dir = notificationStickerCacheDir
            if (dir == null || !dir.isDirectory) {
                notificationStickerCacheDir = ApplicationLoader.getFilesDirFixed("cache/notification_stickers")
            }
            return notificationStickerCacheDir
        }

        private fun getStickerFrameCacheFile(documentId: Long, fitzModifier: Int): File? {
            val dir = getStickerFrameCacheDir()
            return if (dir == null) null else File(dir, "sticker_" + documentId + (if (fitzModifier != 0) "_f$fitzModifier" else "") + ".webp")
        }

        /**
         * Renders only the first frame, straight at &lt;= [NOTIFICATION_STICKER_FRAME_SIZE] px, never the whole
         * animation, writes it as webp via a temp file + rename, and recycles every bitmap. notificationStickerQueue only.
         */
        private fun decodeStickerFrame(source: File, out: File, lottie: Boolean, fitzModifier: Int, account: Int): Boolean {
            if (sniffNotificationImageMime(out) != null) {
                return true // another account already decoded this document
            }
            if (!source.exists() || source.length() <= 0 || source.length() > NOTIFICATION_STICKER_MAX_DOCUMENT_SIZE) {
                return false
            }
            var bitmap: Bitmap? = null
            val tmp = File(out.parentFile, out.name + ".tmp")
            try {
                if (lottie) {
                    val lottieNative = RLottieNative.createFromFile(source.absolutePath, null, null, null, fitzModifier, null)
                        ?: return false
                    try {
                        bitmap = Bitmap.createBitmap(NOTIFICATION_STICKER_FRAME_SIZE, NOTIFICATION_STICKER_FRAME_SIZE, Bitmap.Config.ARGB_8888)
                        var result = -5
                        var attempt = 0
                        while (attempt < 10 && result == -5) {
                            result = lottieNative.getFrame(0, bitmap, true)
                            if (result == -5) {
                                Thread.sleep(20)
                            }
                            attempt++
                        }
                        if (result < 0) {
                            return false
                        }
                    } finally {
                        lottieNative.recycle()
                    }
                } else {
                    val metaData = IntArray(8)
                    var decoder: AnimatedFileNative? = null
                    var attempt = 0
                    while (attempt < 3 && decoder == null) {
                        decoder = AnimatedFileNative.createDecoderFrom(source.absolutePath, metaData, account, 0, null, false)
                        attempt++
                    }
                    if (decoder == null) {
                        return false
                    }
                    var frame: Bitmap? = null
                    try {
                        val w = metaData[0]
                        val h = metaData[1]
                        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) {
                            return false
                        }
                        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        if (decoder.getVideoFrame(frame, false, 0f, 0f, false) == 0) {
                            return false
                        }
                        val scale = Math.min(1f, NOTIFICATION_STICKER_FRAME_SIZE / Math.max(w, h).toFloat())
                        if (scale < 1f) {
                            bitmap = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true)
                        } else {
                            bitmap = frame
                            frame = null
                        }
                    } finally {
                        decoder.recycle()
                        if (frame != null && frame !== bitmap) {
                            frame.recycle()
                        }
                    }
                }
                FileOutputStream(tmp).use { stream ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    if (!bitmap!!.compress(format, 90, stream)) {
                        return false
                    }
                }
                if (!tmp.renameTo(out)) {
                    return false
                }
                trimStickerFrameCache(out.parentFile)
                return true
            } catch (e: Throwable) {
                FileLog.e(e)
                return false
            } finally {
                bitmap?.recycle()
                if (tmp.exists()) {
                    tmp.delete()
                }
            }
        }

        /** Keeps the frame cache small: drops files older than 7 days, then the oldest beyond 200 files / 20 MB. */
        private fun trimStickerFrameCache(dir: File?) {
            try {
                val files = dir?.listFiles() ?: return
                val now = System.currentTimeMillis()
                val alive = ArrayList<File>()
                var total = 0L
                for (file in files) {
                    if (now - file.lastModified() > NOTIFICATION_STICKER_CACHE_MAX_AGE || file.name.endsWith(".tmp") && now - file.lastModified() > 60_000) {
                        file.delete()
                    } else {
                        alive.add(file)
                        total += file.length()
                    }
                }
                if (alive.size <= NOTIFICATION_STICKER_CACHE_MAX_FILES && total <= NOTIFICATION_STICKER_CACHE_MAX_BYTES) {
                    return
                }
                java.util.Collections.sort(alive) { a, b -> java.lang.Long.compare(a.lastModified(), b.lastModified()) }
                var count = alive.size
                var i = 0
                while (i < alive.size && (count > NOTIFICATION_STICKER_CACHE_MAX_FILES || total > NOTIFICATION_STICKER_CACHE_MAX_BYTES)) {
                    val file = alive[i]
                    total -= file.length()
                    count--
                    file.delete()
                    i++
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }

        /**
         * A text message that is a single emoji / single custom emoji which the chat UI shows as a big sticker
         * (MessageObject.checkBigAnimatedEmoji filled emojiAnimatedSticker). Skipped when animated emoji in chats are
         * turned off (LiteMode FLAG_ANIMATED_EMOJI_CHAT), like Telegram X. Only the already resolved document is
         * used: if just emojiAnimatedStickerId is known nothing is fetched and the notification stays text only.
         */
        private fun isSingleEmojiNotificationSticker(messageObject: MessageObject): Boolean {
            if (messageObject.emojiAnimatedSticker == null) {
                return false
            }
            val media = messageObject.messageOwner.media
            if (media != null && media !is TLRPC.TL_messageMediaEmpty) {
                return false
            }
            return try {
                LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT)
            } catch (e: Exception) {
                FileLog.e(e)
                false
            }
        }

        /** Same mapping as ImageLoader uses for the "_c1".."_c5" big emoji filters. */
        private fun getEmojiFitzModifier(color: String?): Int {
            return when (color) {
                null -> 0
                "_c1" -> 12
                "_c2" -> 3
                "_c3" -> 4
                "_c4" -> 5
                "_c5" -> 6
                else -> 0
            }
        }

        private fun isStaticWebpSticker(messageObject: MessageObject): Boolean {
            val document = messageObject.getDocument()
            return document != null && !messageObject.isAnimatedSticker() && !messageObject.isVideoSticker() && "image/webp" == document.mime_type
        }

        /**
         * Returns the image mime type of a file by its magic bytes (webp / jpeg / png), or null when the file
         * is missing or is not one of those (e.g. a gzip'ed .tgs or a .webm).
         */
        @JvmStatic
        fun sniffNotificationImageMime(file: File?): String? {
            if (file == null || !file.exists() || file.length() < 12) {
                return null
            }
            val header = ByteArray(12)
            try {
                FileInputStream(file).use { input ->
                    var read = 0
                    while (read < header.size) {
                        val r = input.read(header, read, header.size - read)
                        if (r < 0) {
                            break
                        }
                        read += r
                    }
                    if (read < header.size) {
                        return null
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
                return null
            }
            if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
                && header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) {
                return "image/webp"
            }
            if ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xFF) == 0xD8 && (header[2].toInt() and 0xFF) == 0xFF) {
                return "image/jpeg"
            }
            if ((header[0].toInt() and 0xFF) == 0x89 && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) {
                return "image/png"
            }
            return null
        }
    }
}
