package com.ashuapps.lock

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Transparent host for the system fingerprint prompt; the glass overlay stays visible underneath. */
class BioActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Lock use karo")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LockService.instance?.unlock(); finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { finish() }
        }).authenticate(info)
    }
}
