package org.telegram.messenger

import android.content.Context
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
 * runs on the shared notificationStickerQueue) is called on NotificationsController's notificationsQueue, which
 * is passed in together with [rebuild], the controller's `showOrUpdateNotification(false)`.
 * NotificationsController keeps only the hooks: [get], [mimeType], [appendFallback], [onFileLoaded] and
 * [onFileLoadFailed] (it also registers itself for NotificationCenter.fileLoadFailed).
 */
internal class NotificationStickerImages(
    private val currentAccount: Int,
    private val notificationsQueue: DispatchQueue,
    /** showOrUpdateNotification(false) of the owning controller; the same instance is used for cancel / post. */
    private val rebuild: Runnable,
) {

    /** What to attach: [file] for the notification, its [mimeType] (null: sniff), and an optional msg_media_raw [fallback]. */
    class Image(
        @JvmField val file: File?,
        @JvmField val mimeType: String?,
        @JvmField val fallback: File?,
    )

    // All of these are only touched on notificationsQueue.
    private val pendingStickerLoads = HashSet<String>()   // file names whose fileLoaded triggers a silent rebuild
    private val requestedStickerLoads = HashSet<String>() // file names already requested once, never re-requested
    private val pendingStickerDecodes = HashSet<String>() // frame cache names being decoded to a still frame
    private val failedStickerDecodes = HashSet<String>()  // frame cache names that failed / timed out, not retried

    private val accountInstance: AccountInstance = AccountInstance.getInstance(currentAccount)
    private val fileLoader: FileLoader get() = accountInstance.fileLoader
    private val downloadController: DownloadController get() = accountInstance.downloadController

    /** NotificationCenter.fileLoaded, already on notificationsQueue (after the voice note check). */
    fun onFileLoaded(path: String?) {
        if (path != null && pendingStickerLoads.remove(path)) {
            scheduleStickerNotificationRebuild()
        }
    }

    /** NotificationCenter.fileLoadFailed, any thread: forget the pending load on notificationsQueue. */
    fun onFileLoadFailed(path: String?) {
        notificationsQueue.postRunnable { if (path != null) pendingStickerLoads.remove(path) }
    }

    /**
     * The image for a sticker message or a single emoji shown as a big sticker, or null (no such message, or
     * nothing usable right now). The caller has already checked passcode and secret media. notificationsQueue only.
     */
    fun get(messageObject: MessageObject): Image? {
        if (!(messageObject.messageOwner.media is TLRPC.TL_messageMediaDocument && (messageObject.isSticker() || messageObject.isAnimatedSticker()) || isSingleEmojiNotificationSticker(messageObject))) {
            return null
        }
        return getStickerNotificationImage(messageObject)
    }

    /**
     * Picks the best still image for a sticker message (static webp, TGS or video sticker) that can be shown
     * right now, best first: decoded TGS / webm frame or full static webp &gt; document thumb. Never returns the .tgs / .webm document
     * itself and never decodes on this thread. When nothing good is on disk yet, it asks FileLoader for the
     * (size limited) document and the thumb once; their fileLoaded triggers one silent rebuild.
     * Returns null when nothing is usable now, so the notification stays text only.
     * Runs on notificationsQueue.
     */
    private fun getStickerNotificationImage(messageObject: MessageObject): Image? {
        try {
            val document: TLRPC.Document? = messageObject.getDocument()
            if (document == null || DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
                return null
            }
            val singleEmoji = messageObject.messageOwner.media !is TLRPC.TL_messageMediaDocument
            if (singleEmoji && !isSingleEmojiNotificationSticker(messageObject)) {
                return null
            }
            // skin tone of a single colored emoji (👍🏽): applied while rendering, thumbs only have the default tone
            var fitzModifier = if (singleEmoji) getEmojiFitzModifier(messageObject.emojiAnimatedStickerColor) else 0
            // a single emoji's document is not part of the message, refresh its file reference through its set
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
            // the full document is only fetched when the user's auto-download rules allow it right now; thumbs are tiny and always ok
            val mayLoadDocument = documentFile == null && smallDocument && (staticWebp || lottie || video) && canAutoDownloadStickerDocument(messageObject, singleEmoji, document)
            if (lottie || video) {
                // best: a still frame we decoded earlier (cached by document id, shared by all accounts)
                val frameFile = getStickerFrameCacheFile(document.id, fitzModifier)
                val frameMime = sniffNotificationImageMime(frameFile)
                if (frameMime != null && frameFile != null) {
                    val now = System.currentTimeMillis()
                    if (now - frameFile.lastModified() > 60 * 60 * 1000L) {
                        frameFile.setLastModified(now) // LRU for trimStickerFrameCache
                    }
                    return Image(frameFile, frameMime, null)
                }
                if (documentFile != null && smallDocument) {
                    requestStickerFrameDecode(frameFile, documentFile, lottie, fitzModifier)
                }
            }
            if (staticWebp) {
                if (documentFile != null) {
                    val mime = sniffNotificationImageMime(documentFile)
                    if (mime != null) {
                        return Image(documentFile, mime, null)
                    }
                } else if (mayLoadDocument) {
                    requestStickerNotificationLoad(FileLoader.getAttachFileName(document)) { fileLoader.loadFile(document, loadParent, FileLoader.PRIORITY_HIGH, 1) }
                }
            } else if ((lottie || video) && mayLoadDocument) {
                // once the document lands, the rebuild finds it on disk and decodes a frame
                requestStickerNotificationLoad(FileLoader.getAttachFileName(document)) { fileLoader.loadFile(document, loadParent, FileLoader.PRIORITY_HIGH, 1) }
            }

            if (fitzModifier != 0) {
                return null // a default-tone thumb would show the wrong skin tone; wait for the decoded frame
            }
            var thumb: TLRPC.PhotoSize? = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320, false, null, true)
            if (thumb == null || thumb.location == null || thumb is TLRPC.TL_photoCachedSize) {
                thumb = null
            }
            val thumbFile = if (thumb != null) findStickerThumbFile(thumb) else null

            if (staticWebp && documentFile == null && fileLoader.isLoadingFile(FileLoader.getAttachFileName(document))) {
                // keep the old behaviour: msg_media_raw URI via NotificationImageProvider while it downloads,
                // stickers are downloaded into the cache dir; fall back to the thumb if it times out
                return Image(fileLoader.getPathToAttach(document, true), "image/webp", thumbFile)
            }
            if (thumbFile != null) {
                return Image(thumbFile, sniffNotificationImageMime(thumbFile), null)
            }
            if (thumb != null) {
                val thumbLocation = ImageLocation.getForDocument(thumb, document)
                if (thumbLocation != null) {
                    requestStickerNotificationLoad(FileLoader.getAttachFileName(thumb, "webp")) { fileLoader.loadFile(thumbLocation, loadParent, "webp", FileLoader.PRIORITY_HIGH, 1) }
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return null
    }

    /**
     * Same rule the chat UI uses for auto-downloading stickers (DownloadController treats them as photos:
     * Wi-Fi / mobile / roaming preset enabled + per-peer mask), plus Android's system Data Saver on metered
     * networks. When this says no, only the thumb is requested.
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

    /** Sticker thumbs are stored as .webp by the chat UI and by our own request, older code used the .jpg name. */
    private fun findStickerThumbFile(thumb: TLRPC.PhotoSize): File? {
        val candidates = arrayOf<File?>(
            fileLoader.getPathToAttach(thumb, "webp", true),
            fileLoader.getPathToAttach(thumb, true),
            fileLoader.getPathToAttach(thumb, "webp", false),
            fileLoader.getPathToAttach(thumb, false),
        )
        for (file in candidates) {
            if (sniffNotificationImageMime(file) != null) {
                return file
            }
        }
        return null
    }

    /** Requests a file at most once per session; its fileLoaded schedules one silent rebuild. notificationsQueue only. */
    private fun requestStickerNotificationLoad(fileName: String?, load: Runnable) {
        if (fileName == null || TextUtils.isEmpty(fileName)) {
            return
        }
        if (requestedStickerLoads.size > 500) {
            requestedStickerLoads.clear()
        }
        if (!requestedStickerLoads.add(fileName)) {
            return
        }
        pendingStickerLoads.add(fileName)
        load.run()
    }

    /**
     * Coalesces sticker preview arrivals into one rebuild. notifyAboutLast = false: no sound / vibration,
     * low priority, silent channel, same as edits and read updates. Dismissed dialogs stay dismissed (dismissDate).
     */
    private fun scheduleStickerNotificationRebuild() {
        notificationsQueue.cancelRunnable(rebuild)
        notificationsQueue.postRunnable(rebuild, NOTIFICATION_STICKER_REBUILD_DELAY)
    }

    /**
     * Decodes one still frame of a TGS / webm sticker on notificationStickerQueue, never on notificationsQueue.
     * Success schedules one silent rebuild; failure or a [NOTIFICATION_STICKER_DECODE_TIMEOUT] timeout
     * marks the document failed so it is not retried and the notification keeps the thumb / text.
     * notificationsQueue only.
     */
    private fun requestStickerFrameDecode(out: File?, source: File, lottie: Boolean, fitzModifier: Int) {
        if (out == null) {
            return
        }
        val key = out.name
        if (failedStickerDecodes.contains(key) || pendingStickerDecodes.contains(key)) {
            return
        }
        if (failedStickerDecodes.size > 500) {
            failedStickerDecodes.clear()
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
                if (!pendingStickerDecodes.remove(key)) {
                    return@Runnable // timed out; a late result is still picked up by the next regular update
                }
                if (ok) {
                    scheduleStickerNotificationRebuild()
                } else {
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
