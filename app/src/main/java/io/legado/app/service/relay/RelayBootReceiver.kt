package io.legado.app.service.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences

class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (context.defaultSharedPreferences.getBoolean(PreferKey.publicWebRelayEnabled, false)) {
            RelayService.start(context)
        }
    }
}
