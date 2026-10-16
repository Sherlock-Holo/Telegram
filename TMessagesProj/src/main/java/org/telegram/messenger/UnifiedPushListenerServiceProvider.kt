package org.telegram.messenger

/**
 * Fork: push provider backed by UnifiedPush. With UP switched off (the default) hasServices() is false
 * and ApplicationLoader.initPushServices() takes the same "no push service" path as
 * NoPushListenerServiceProvider (status string only, no registerDevice).
 */
object UnifiedPushListenerServiceProvider : PushListenerController.IPushListenerServiceProvider {

    override fun hasServices(): Boolean = UnifiedPushManager.isActive()

    override fun getLogTitle(): String = "UnifiedPush"

    override fun onRequestPushToken() = UnifiedPushManager.ensureRegistered()

    override fun getPushType(): Int = UnifiedPushManager.TOKEN_TYPE
}
