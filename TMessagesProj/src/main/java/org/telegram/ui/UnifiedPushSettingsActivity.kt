package org.telegram.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.UnifiedPushManager
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Fork: Settings > Notifications and Sounds > UnifiedPush.
 * Toggle, distributor choice, registration status, endpoint (masked, tap to copy), test push,
 * re-register / unregister. All state lives in [UnifiedPushManager].
 */
class UnifiedPushSettingsActivity : UniversalFragment() {

    private val refresh = Runnable { listView?.adapter?.update(true) }

    override fun onFragmentCreate(): Boolean {
        UnifiedPushManager.addListener(refresh)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        UnifiedPushManager.removeListener(refresh)
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        // distributors may have been installed / the battery exemption granted while we were away
        refresh.run()
    }

    override fun getTitle(): CharSequence = getString(R.string.UnifiedPush)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val enabled = UnifiedPushManager.isEnabled()
        items.add(UItem.asCheck(ID_ENABLE, getString(R.string.UnifiedPushEnable)).setChecked(enabled))
        items.add(UItem.asShadow(getString(R.string.UnifiedPushInfo)))
        if (!enabled) return

        items.add(UItem.asHeader(getString(R.string.UnifiedPushDistributor)))
        val distributors = UnifiedPushManager.getDistributors()
        val current = UnifiedPushManager.getDistributor()
        for (pkg in distributors) {
            val row = UItem.asRadio(ID_DISTRIBUTOR, UnifiedPushManager.getDistributorLabel(pkg), pkg).setChecked(pkg == current)
            row.`object` = pkg
            items.add(row)
        }
        items.add(UItem.asShadow(when {
            distributors.isEmpty() -> getString(R.string.UnifiedPushNoDistributors)
            current != null && !distributors.contains(current) ->
                LocaleController.formatString(R.string.UnifiedPushDistributorMissing, current)
            else -> getString(R.string.UnifiedPushDistributorInfo)
        }))

        items.add(UItem.asHeader(getString(R.string.UnifiedPushState)))
        items.add(UItem.asButton(ID_STATUS, getString(R.string.UnifiedPushStatus), statusText(UnifiedPushManager.getStatus())))
        val endpoint = UnifiedPushManager.getEndpoint()
        items.add(UItem.asButton(ID_ENDPOINT, getString(R.string.UnifiedPushEndpoint),
            if (endpoint.isEmpty()) getString(R.string.UnifiedPushNone) else UnifiedPushManager.maskEndpoint(endpoint)))
        val reg = UnifiedPushManager.getTelegramRegistration()
        items.add(UItem.asButton(ID_TELEGRAM, getString(R.string.UnifiedPushTelegramRegistration),
            if (reg[1] == 0) getString(R.string.UnifiedPushNone)
            else LocaleController.formatString(R.string.UnifiedPushAccountsOf, reg[0], reg[1])))
        val lastPush = UnifiedPushManager.getLastPushTime()
        items.add(UItem.asButton(ID_LAST_PUSH, getString(R.string.UnifiedPushLastPush),
            if (lastPush == 0L) getString(R.string.UnifiedPushNever)
            else LocaleController.formatString(R.string.UnifiedPushLastPushValue,
                LocaleController.formatDateTime(lastPush / 1000, true), UnifiedPushManager.getPushCount())))
        items.add(UItem.asShadow(getString(R.string.UnifiedPushStateInfo)))

        items.add(UItem.asButton(ID_TEST, getString(R.string.UnifiedPushTest)))
        items.add(UItem.asButton(ID_REREGISTER, getString(R.string.UnifiedPushReregister)))
        items.add(UItem.asButton(ID_UNREGISTER, getString(R.string.UnifiedPushUnregister)).red())
        items.add(UItem.asShadow(getString(R.string.UnifiedPushTestInfo)))

        val context = ApplicationLoader.applicationContext
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager?
        if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
            items.add(UItem.asButton(ID_BATTERY, getString(R.string.UnifiedPushBattery)))
            items.add(UItem.asShadow(getString(R.string.UnifiedPushBatteryInfo)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ID_ENABLE -> {
                UnifiedPushManager.setEnabled(!UnifiedPushManager.isEnabled())
                refresh.run()
            }
            ID_DISTRIBUTOR -> (item.`object` as? String)?.let {
                UnifiedPushManager.selectDistributor(it)
                refresh.run()
            }
            ID_ENDPOINT -> {
                val endpoint = UnifiedPushManager.getEndpoint()
                if (endpoint.isNotEmpty()) {
                    AndroidUtilities.addToClipboard(endpoint)
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.UnifiedPushEndpointCopied)).show()
                }
            }
            ID_REREGISTER -> {
                UnifiedPushManager.reregister()
                refresh.run()
            }
            ID_UNREGISTER -> {
                UnifiedPushManager.disable()
                refresh.run()
            }
            ID_TEST -> sendTest()
            ID_BATTERY -> try {
                parentActivity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    private fun sendTest() {
        val endpoint = UnifiedPushManager.getEndpoint()
        if (endpoint.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.UnifiedPushTestNoEndpoint)).show()
            return
        }
        Utilities.globalQueue.postRunnable {
            val text = try {
                val code = UnifiedPushManager.sendTestPush(endpoint)
                if (code in 200..299) LocaleController.formatString(R.string.UnifiedPushTestSent, code)
                else LocaleController.formatString(R.string.UnifiedPushTestFailed, "HTTP $code")
            } catch (e: Throwable) {
                LocaleController.formatString(R.string.UnifiedPushTestFailed, e.message.toString())
            }
            AndroidUtilities.runOnUIThread {
                if (!isFinishing && parentActivity != null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show()
                }
            }
        }
    }

    companion object {
        private const val ID_ENABLE = 1
        private const val ID_DISTRIBUTOR = 2
        private const val ID_STATUS = 3
        private const val ID_ENDPOINT = 4
        private const val ID_TELEGRAM = 5
        private const val ID_LAST_PUSH = 6
        private const val ID_REREGISTER = 7
        private const val ID_UNREGISTER = 8
        private const val ID_TEST = 9
        private const val ID_BATTERY = 10

        /** One-line state for the row in Notifications and Sounds and for the status row here. */
        @JvmStatic
        fun statusText(status: Int): String = when (status) {
            UnifiedPushManager.STATUS_NO_DISTRIBUTOR -> getString(R.string.UnifiedPushStatusNoDistributor)
            UnifiedPushManager.STATUS_WAITING -> getString(R.string.UnifiedPushStatusWaiting)
            UnifiedPushManager.STATUS_REGISTERED -> getString(R.string.UnifiedPushStatusRegistered)
            UnifiedPushManager.STATUS_FAILED ->
                LocaleController.formatString(R.string.UnifiedPushStatusFailed, UnifiedPushManager.getLastError())
            UnifiedPushManager.STATUS_UNREGISTERED_BY_DISTRIBUTOR -> getString(R.string.UnifiedPushStatusUnregistered)
            else -> getString(R.string.UnifiedPushStatusDisabled)
        }
    }
}
