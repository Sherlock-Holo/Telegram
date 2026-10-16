package org.telegram.messenger

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.tl.TL_account
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fork: UnifiedPush support (everything except the Android service glue, [UnifiedPushReceiver],
 * the provider hook, [UnifiedPushListenerServiceProvider], and the screen, `UnifiedPushSettingsActivity`).
 *
 * Today only Telegram's "Simple Push" (token_type = 4) is used: Telegram sends `PUT <endpoint>` with
 * body `version=N`. The body carries no content, so a push is only a wake-up and the actual messages
 * are fetched over MTProto.
 *
 * The token that is sent to Telegram is produced in exactly one place, [buildToken], and an incoming
 * message goes through exactly one place, [onPush]; a later WebPush (token_type 10) implementation
 * only has to change those two (plus the token type constant).
 *
 * Registration with Telegram deliberately reuses the stock path
 * [PushListenerController.sendRegistrationToServer] -> `MessagesController.registerForPush`, so every
 * logged-in account gets the same token (via other_uids) and accounts that log in later are registered
 * from getDifference() exactly like with FCM.
 */
object UnifiedPushManager {

    /** The Telegram token type this build registers. */
    const val TOKEN_TYPE = PushListenerController.PUSH_TYPE_SIMPLE

    const val STATUS_DISABLED = 0
    const val STATUS_NO_DISTRIBUTOR = 1
    const val STATUS_WAITING = 2
    const val STATUS_REGISTERED = 3
    const val STATUS_FAILED = 4
    const val STATUS_UNREGISTERED_BY_DISTRIBUTOR = 5

    private const val INSTANCE = "default"
    private const val PREFS = "unifiedpush"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISTRIBUTOR = "distributor"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_ERROR = "error"
    private const val KEY_LAST_PUSH = "lastPush"
    private const val KEY_PUSH_COUNT = "pushCount"
    private const val KEY_UNREGISTERED = "unregistered"

    // Several pushes in a burst (Telegram sends one PUT per message) collapse into one wake-up plus at
    // most one trailing wake-up: the first resumes the connection and keeps it open for a while, the
    // trailing one covers a message that lands right after the connection closed again.
    private const val WAKE_THROTTLE_MS = 10_000L
    private const val WAKE_LOCK_MS = 15_000L
    private const val RETRY_MIN_MS = 30_000L
    private const val RETRY_MAX_MS = 15 * 60_000L

    private val wakeMonitor = Any()
    private var lastWakeAt = -WAKE_THROTTLE_MS
    private var trailingWakeScheduled = false
    private var partialWakeLock: PowerManager.WakeLock? = null

    private var retryDelayMs = RETRY_MIN_MS
    private var retryRunnable: Runnable? = null
    private var ignoreUnregisteredUntil = 0L

    private val listeners = ArrayList<Runnable>()

    // ---------------------------------------------------------------- state

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    /** Package name of the distributor the user picked, or null. */
    @JvmStatic
    fun getDistributor(): String? = prefs().getString(KEY_DISTRIBUTOR, null)?.takeIf { it.isNotEmpty() }

    /** Endpoint URL the distributor gave us (secret-ish: it contains the topic), or "". */
    @JvmStatic
    fun getEndpoint(): String = prefs().getString(KEY_ENDPOINT, "") ?: ""

    @JvmStatic
    fun getLastError(): String? = prefs().getString(KEY_ERROR, null)

    /** Wall clock time of the last push that reached the receiver, 0 = never. */
    @JvmStatic
    fun getLastPushTime(): Long = prefs().getLong(KEY_LAST_PUSH, 0)

    @JvmStatic
    fun getPushCount(): Long = prefs().getLong(KEY_PUSH_COUNT, 0)

    /** Provider.hasServices(): UP is on and the chosen distributor is still installed. */
    @JvmStatic
    fun isActive(): Boolean {
        if (!isEnabled()) return false
        val distributor = getDistributor() ?: return false
        return getDistributors().contains(distributor)
    }

    /** Package names of installed UnifiedPush distributors (needs the connector's `<queries>` on Android 11+). */
    @JvmStatic
    fun getDistributors(): List<String> = try {
        UnifiedPush.getDistributors(ApplicationLoader.applicationContext)
    } catch (e: Throwable) {
        FileLog.e(e)
        emptyList()
    }

    @JvmStatic
    fun getDistributorLabel(packageName: String): CharSequence = try {
        val pm = ApplicationLoader.applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
    } catch (ignore: Throwable) {
        packageName
    }

    @JvmStatic
    fun getStatus(): Int {
        val p = prefs()
        return when {
            !p.getBoolean(KEY_ENABLED, false) -> STATUS_DISABLED
            getDistributor() == null -> STATUS_NO_DISTRIBUTOR
            !p.getString(KEY_ENDPOINT, "").isNullOrEmpty() -> STATUS_REGISTERED
            p.getBoolean(KEY_UNREGISTERED, false) -> STATUS_UNREGISTERED_BY_DISTRIBUTOR
            !p.getString(KEY_ERROR, null).isNullOrEmpty() -> STATUS_FAILED
            else -> STATUS_WAITING
        }
    }

    /** `[registered, total]`: logged-in accounts, and how many of them Telegram confirmed for the current token. */
    @JvmStatic
    fun getTelegramRegistration(): IntArray {
        var total = 0
        var registered = 0
        val token = SharedConfig.pushString
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val config = UserConfig.getInstance(a)
            if (config.isClientActivated) {
                total++
                if (config.registeredForPush && SharedConfig.pushType == TOKEN_TYPE && !token.isNullOrEmpty()) {
                    registered++
                }
            }
        }
        return intArrayOf(registered, total)
    }

    /** Host + shortened path: enough to recognise the endpoint without showing the whole topic. */
    @JvmStatic
    fun maskEndpoint(endpoint: String?): String {
        if (endpoint.isNullOrEmpty()) return ""
        val host = try { Uri.parse(endpoint).host } catch (ignore: Throwable) { null }
        if (host == null) {
            return if (endpoint.length > 12) endpoint.take(6) + "…" + endpoint.takeLast(4) else endpoint
        }
        var path = Uri.parse(endpoint).path ?: ""
        if (path.length > 8) path = path.take(4) + "…" + path.takeLast(3)
        return host + path
    }

    // ------------------------------------------------------------ listeners

    @JvmStatic
    fun addListener(listener: Runnable) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { AndroidUtilities.runOnUIThread(it) }
    }

    // ------------------------------------------------- user actions (UI)

    /** Turn UP on or off. With a single installed distributor it is picked automatically. */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            prefs().edit().putBoolean(KEY_ENABLED, true).remove(KEY_UNREGISTERED).apply()
            val distributors = getDistributors()
            if (getDistributor().let { it == null || !distributors.contains(it) } && distributors.size == 1) {
                prefs().edit().putString(KEY_DISTRIBUTOR, distributors[0]).apply()
            }
            if (getDistributor() != null) register()
        } else {
            disable()
        }
        notifyChanged()
    }

    /** Switch to another distributor: leave the old one (and Telegram) first, then subscribe at the new one. */
    @JvmStatic
    fun selectDistributor(packageName: String) {
        if (packageName.isEmpty()) return
        if (packageName == getDistributor() && getEndpoint().isNotEmpty()) return
        dropRegistration()
        prefs().edit().putString(KEY_DISTRIBUTOR, packageName).apply()
        register()
        notifyChanged()
    }

    /** Drop the current subscription everywhere and ask the distributor for a fresh endpoint. */
    @JvmStatic
    fun reregister() {
        dropRegistration()
        register()
        notifyChanged()
    }

    /** Unsubscribe from the distributor and from Telegram and switch UP off. */
    @JvmStatic
    fun disable() {
        prefs().edit().putBoolean(KEY_ENABLED, false).remove(KEY_UNREGISTERED).apply()
        dropRegistration()
        notifyChanged()
    }

    // ---------------------------------------- provider entry (app start)

    /** Called from the push provider on every process start; only talks to the distributor if needed. */
    @JvmStatic
    fun ensureRegistered() {
        if (!isActive()) return
        val endpoint = getEndpoint()
        val ack = getDistributor() == UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext)
        if (endpoint.isNotEmpty() && ack && SharedConfig.pushType == TOKEN_TYPE && SharedConfig.pushString == buildToken(endpoint)) {
            return // nothing changed since the last run; MessagesController.registerForPush() keeps Telegram in sync
        }
        register()
    }

    // ----------------------------------------------- connector callbacks

    internal fun onNewEndpoint(endpoint: String) {
        if (!isEnabled()) {
            // The distributor re-announced an endpoint after the user switched us off: tell it to stop.
            try {
                UnifiedPush.unregister(ApplicationLoader.applicationContext, INSTANCE)
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            return
        }
        cancelRetry()
        val old = getEndpoint()
        prefs().edit().putString(KEY_ENDPOINT, endpoint).remove(KEY_ERROR).remove(KEY_UNREGISTERED).apply()
        val token = buildToken(endpoint)
        val oldToken = if (old.isEmpty()) null else buildToken(old)
        Utilities.globalQueue.postRunnable {
            if (oldToken != null && oldToken != token) {
                sendUnregisterDevice(TOKEN_TYPE, oldToken)
            }
            if (token == SharedConfig.pushString && SharedConfig.pushType == TOKEN_TYPE) {
                val reg = getTelegramRegistration()
                if (reg[0] == reg[1]) {
                    notifyChanged()
                    return@postRunnable // same endpoint re-announced and Telegram already has it
                }
            }
            // Stock path: setRegId() for native + registerDevice for every logged-in account.
            // pushStringGetTimeStart/End stay 0 on purpose, so no "fcm/hcm token" app-log event is sent.
            PushListenerController.sendRegistrationToServer(TOKEN_TYPE, token)
            notifyChanged()
        }
        notifyChanged()
    }

    internal fun onRegistrationFailed(reason: FailedReason?) {
        val name = reason?.name ?: "UNKNOWN"
        prefs().edit().putString(KEY_ERROR, name).apply()
        if (BuildVars.LOGS_ENABLED) FileLog.d("UnifiedPush registration failed: $name")
        // An already registered endpoint is kept: it is most likely still valid, and revoking it
        // would turn a temporary network problem into lost pushes.
        if (isEnabled() && (reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR)) {
            scheduleRetry()
        }
        notifyChanged()
    }

    internal fun onUnregistered() {
        if (SystemClock.elapsedRealtime() < ignoreUnregisteredUntil) return // echo of our own unregister()
        if (!isEnabled()) return
        // The distributor dropped us (app removed inside the distributor, distributor reset ...): the
        // endpoint is dead, so stop Telegram from pushing to it. Re-subscribing happens on the next app
        // start (ensureRegistered) or from the settings screen, not in a loop here.
        prefs().edit().remove(KEY_ENDPOINT).putBoolean(KEY_UNREGISTERED, true).apply()
        revokeTelegramToken()
        notifyChanged()
    }

    /** A push arrived (any content). Type 4 carries nothing useful, so it is just a wake-up. */
    internal fun onPush(content: ByteArray?) {
        val p = prefs()
        p.edit().putLong(KEY_LAST_PUSH, System.currentTimeMillis()).putLong(KEY_PUSH_COUNT, p.getLong(KEY_PUSH_COUNT, 0) + 1).apply()
        if (BuildVars.LOGS_ENABLED) FileLog.d("UnifiedPush push, ${content?.size ?: 0} bytes")
        requestWake()
        notifyChanged()
    }

    // ----------------------------------------------------------- internals

    /**
     * What is sent to Telegram as `token` for [TOKEN_TYPE]. For Simple Push that is the endpoint URL
     * itself (a gateway URL could be prepended here for POST-only distributors). WebPush (type 10)
     * would return the JSON `{"endpoint":..,"keys":{"p256dh":..,"auth":..}}` instead.
     */
    internal fun buildToken(endpoint: String): String = endpoint

    /**
     * Pushes to our own endpoint the way Telegram does (PUT "version=N"; POST if the server refuses PUT),
     * so the whole distributor -> receiver path can be checked without waiting for a message.
     * Blocking: call off the UI thread. Returns the HTTP status code.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun sendTestPush(endpoint: String): Int {
        val body = "version=${System.currentTimeMillis() / 1000}".toByteArray()
        var code = -1
        for (method in arrayOf("PUT", "POST")) {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain")
                connection.outputStream.use { it.write(body) }
                code = connection.responseCode
            } finally {
                connection.disconnect()
            }
            if (code != 405 && code != 501) break
        }
        return code
    }

    private fun register() {
        val distributor = getDistributor() ?: return
        val context = ApplicationLoader.applicationContext
        try {
            // unregister() clears the library's saved distributor, so always write it back first.
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context, INSTANCE, "Telegram", null)
            prefs().edit().remove(KEY_ERROR).apply()
        } catch (e: Throwable) {
            FileLog.e(e)
            prefs().edit().putString(KEY_ERROR, e.javaClass.simpleName).apply()
        }
    }

    /** Unsubscribe at the distributor and at Telegram, forget the endpoint (the chosen distributor stays). */
    private fun dropRegistration() {
        cancelRetry()
        ignoreUnregisteredUntil = SystemClock.elapsedRealtime() + 10_000
        try {
            UnifiedPush.unregister(ApplicationLoader.applicationContext, INSTANCE)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        prefs().edit().remove(KEY_ENDPOINT).remove(KEY_ERROR).remove(KEY_UNREGISTERED).apply()
        revokeTelegramToken()
    }

    /**
     * unregisterDevice for the current token and back to the default "no push token" state (what
     * initPushServices() does without a push provider). MessagesController.unregistedPush() is not
     * usable for this: it only acts when pushString is empty.
     */
    private fun revokeTelegramToken() {
        val token = SharedConfig.pushString
        val type = SharedConfig.pushType
        Utilities.stageQueue.postRunnable {
            if (!token.isNullOrEmpty() && type == TOKEN_TYPE) {
                SharedConfig.pushString = ""
                SharedConfig.pushType = PushListenerController.PUSH_TYPE_FIREBASE
                SharedConfig.pushStringStatus = "__UNIFIEDPUSH_OFF__"
                SharedConfig.saveConfig()
                for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    val config = UserConfig.getInstance(a)
                    config.registeredForPush = false
                    config.saveConfig(false)
                }
                ConnectionsManager.setRegId(null, PushListenerController.PUSH_TYPE_FIREBASE, SharedConfig.pushStringStatus)
                sendUnregisterDevice(type, token)
            }
        }
    }

    private fun sendUnregisterDevice(type: Int, token: String) {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(a).isClientActivated) continue
            val req = TL_account.unregisterDevice()
            req.token_type = type
            req.token = token
            for (b in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                val other = UserConfig.getInstance(b)
                if (b != a && other.isClientActivated) {
                    req.other_uids.add(other.clientUserId)
                }
            }
            ConnectionsManager.getInstance(a).sendRequest(req, null)
        }
    }

    private fun scheduleRetry() {
        cancelRetry()
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        val runnable = Runnable {
            retryRunnable = null
            if (isActive() && getEndpoint().isEmpty()) {
                register()
                notifyChanged()
            }
        }
        retryRunnable = runnable
        AndroidUtilities.runOnUIThread(runnable, delay)
    }

    private fun cancelRetry() {
        retryDelayMs = RETRY_MIN_MS
        retryRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        retryRunnable = null
    }

    private fun requestWake() {
        var delay = 0L
        synchronized(wakeMonitor) {
            val now = SystemClock.elapsedRealtime()
            val since = now - lastWakeAt
            if (since < WAKE_THROTTLE_MS) {
                if (trailingWakeScheduled) return // one trailing wake-up already covers this push
                trailingWakeScheduled = true
                delay = WAKE_THROTTLE_MS - since + 200
            } else {
                lastWakeAt = now
            }
        }
        if (delay == 0L) {
            wakeNow()
        } else {
            AndroidUtilities.runOnUIThread({
                synchronized(wakeMonitor) {
                    trailingWakeScheduled = false
                    lastWakeAt = SystemClock.elapsedRealtime()
                }
                wakeNow()
            }, delay)
        }
    }

    /** Same thing the FCM "no payload / decrypt failed" path does: tell native a push came in and (re)connect. */
    private fun wakeNow() {
        try {
            synchronized(wakeMonitor) {
                val lock = partialWakeLock ?: run {
                    val pm = ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telegram:unifiedpush").also {
                        it.setReferenceCounted(false)
                        partialWakeLock = it
                    }
                }
                lock.acquire(WAKE_LOCK_MS) // auto-released, nothing to pair
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        AndroidUtilities.runOnUIThread {
            ApplicationLoader.postInitApplication()
            Utilities.stageQueue.postRunnable {
                for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    if (UserConfig.getInstance(a).isClientActivated) {
                        ConnectionsManager.onInternalPushReceived(a)
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe()
                    }
                }
            }
        }
    }
}
