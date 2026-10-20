package org.telegram.messenger

/**
 * Fork: "no push token" provider. hasServices() == false makes ApplicationLoader.initPushServices()
 * take the already-supported "no push service" path (status string only, no registerDevice call),
 * so the app relies on the MTProto push connection (ConnectionTypePush) + NotificationsService.
 * Java callers use NoPushListenerServiceProvider.INSTANCE, as before.
 */
object NoPushListenerServiceProvider : PushListenerController.IPushListenerServiceProvider {

    override fun hasServices(): Boolean = false

    override fun getLogTitle(): String = "Telegram background connection"

    override fun onRequestPushToken() {
    }

    override fun getPushType(): Int = PushListenerController.PUSH_TYPE_FIREBASE
}
