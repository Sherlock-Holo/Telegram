package org.telegram.messenger

/**
 * Fork: push provider backed by UnifiedPush. With UP switched off (the default) hasServices() is false
 * and ApplicationLoader.initPushServices() takes the same "no push service" path as
 * NoPushListenerServiceProvider (status string only, no registerDevice).
 */
object UnifiedPushListenerServiceProvider : PushListenerController.IPushListenerServiceProvider {

    override fun hasServices(): Boolean {
        // Only called from initPushServices(), after the configs are loaded. UP is on but its distributor
        // was uninstalled: drop the dead endpoint and revoke the token at Telegram (no-op afterwards).
        if (UnifiedPushManager.isDistributorMissing()) UnifiedPushManager.onDistributorMissing()
        return UnifiedPushManager.isActive()
    }

    override fun getLogTitle(): String = "UnifiedPush"

    override fun onRequestPushToken() = UnifiedPushManager.ensureRegistered()

    override fun getPushType(): Int =
        if (UnifiedPushManager.isActive()) UnifiedPushManager.TOKEN_TYPE else PushListenerController.PUSH_TYPE_FIREBASE
}
