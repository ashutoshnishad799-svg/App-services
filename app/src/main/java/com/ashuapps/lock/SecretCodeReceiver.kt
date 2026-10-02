package com.ashuapps.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dial *#*#YOUR PIN#*#* (dialers that send the secret-code broadcast) to open the hidden-apps vault. */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.data?.host ?: return
        val store = LockStore(context)
        if (!store.dialVault || !store.hasPin() || !store.check(code)) return
        LockService.instance?.openVault(true) ?: run {
            store.vaultUntil = System.currentTimeMillis() + 20_000
            context.startActivity(Intent(context, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
