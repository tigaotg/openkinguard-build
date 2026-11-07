package io.github.tigaotg.openkinguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** One bounded, best-effort boot/package-upgrade attempt; foreground resumes retry later. */
class BackgroundMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (BackgroundConsentStore.get(context) != BackgroundConsent.ENABLED) return
        val trigger = if (intent.action == Intent.ACTION_BOOT_COMPLETED)
            BackgroundRecoveryTrigger.BOOT_COMPLETED
        else BackgroundRecoveryTrigger.PACKAGE_REPLACED
        val result = S1LanService.requestAutoRecovery(context, trigger)
        if (S1LanService.lastStartupFailure() != null)
            Log.w("OpenKinGuard", "Boot listener recovery deferred: $result")
    }
}
