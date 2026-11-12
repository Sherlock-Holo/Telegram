package org.telegram.messenger

/**
 * Decides what a notification-side fileLoaded may do for one still-valid session.
 * Production [NotificationStickerImages] uses this so a TGS/WebM download can start
 * cover decode inside the existing prep/retry window, without extending that window
 * or starting decode for cancelled / already-finished sessions.
 */
internal object NotificationStickerPrepBridge {
    enum class AfterDownload {
        IGNORE,
        COLLECT_ONLY,
        PREPARE_DECODE,
    }

    @JvmStatic
    fun afterDownload(
        state: NotificationStickerMediaPolicy.State,
        mediaComplete: Boolean,
        needsDecode: Boolean,
        eligible: Boolean,
    ): AfterDownload {
        if (!eligible) return AfterDownload.IGNORE
        if (state != NotificationStickerMediaPolicy.State.WAITING
            && state != NotificationStickerMediaPolicy.State.POSTED_TEXT_RETRYING) {
            return AfterDownload.IGNORE
        }
        if (mediaComplete) return AfterDownload.COLLECT_ONLY
        if (needsDecode) return AfterDownload.PREPARE_DECODE
        return AfterDownload.IGNORE
    }
}
