package com.amresalehin.emregallery.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    private fun resolveAllowedAuthenticators(context: Context): Int? {
        val biometricManager = BiometricManager.from(context)
        val strongOrCredential = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (biometricManager.canAuthenticate(strongOrCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
            return strongOrCredential
        }
        val weakOrCredential = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (biometricManager.canAuthenticate(weakOrCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
            return weakOrCredential
        }
        return null
    }

    fun isBiometricAvailable(context: Context): Boolean {
        return resolveAllowedAuthenticators(context) != null
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Locked Folder",
        subtitle: String = "Verify your identity to access locked photos",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val authenticators = resolveAllowedAuthenticators(activity)
        if (authenticators == null) {
            onError("Biometric or device credential authentication is unavailable on this device.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If user explicitly cancelled, do not treat as fatal error
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Authentication failed. Please try again.")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
