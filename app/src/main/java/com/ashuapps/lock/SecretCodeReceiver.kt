package com.ashuapps.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dial *#*#2748#*#* in the phone app to open the hidden-apps vault. */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LockService.instance?.openVault()
            ?: context.startActivity(Intent(context, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
