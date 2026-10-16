package org.telegram.messenger

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Fork: receives UnifiedPush events from the connector library (declared in the manifest with the
 * PUSH_EVENT action; the library's own exported receiver forwards MESSAGE / NEW_ENDPOINT /
 * REGISTRATION_FAILED / UNREGISTERED here). All logic is in [UnifiedPushManager].
 * This is a plain background service started by a broadcast from the distributor; we never promote
 * it to a foreground service and never show a notification of our own.
 */
class UnifiedPushReceiver : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        UnifiedPushManager.onNewEndpoint(endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        UnifiedPushManager.onPush(message.content)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        UnifiedPushManager.onRegistrationFailed(reason)
    }

    override fun onUnregistered(instance: String) {
        UnifiedPushManager.onUnregistered()
    }
}
