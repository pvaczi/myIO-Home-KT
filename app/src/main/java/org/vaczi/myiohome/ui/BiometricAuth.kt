package org.vaczi.myiohome.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The system's own authentication (fingerprint / face / device PIN-pattern-password) via
 * `BiometricPrompt` — the app never stores a PIN or any secret of its own. The allowed set is
 * [AUTHENTICATORS] = weak biometrics or the device credential: this combination is supported on every API
 * level we run on (minSdk 24); the "negative button" is NOT set because the device-credential fallback is
 * the way out of the dialog (setting it together with DEVICE_CREDENTIAL throws).
 */
object BiometricAuth {
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    enum class Availability { AVAILABLE, NO_SCREEN_LOCK, UNAVAILABLE }

    fun availability(activity: FragmentActivity): Availability =
        when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NO_SCREEN_LOCK
            else -> Availability.UNAVAILABLE
        }

    /** Hungarian reason a lock switch cannot be enabled (null when it can). */
    fun unavailableReason(a: Availability): String? = when (a) {
        Availability.AVAILABLE -> null
        Availability.NO_SCREEN_LOCK ->
            "Nincs beállított képernyőzár vagy ujjlenyomat a telefonon — állíts be egyet a rendszerbeállításokban."
        Availability.UNAVAILABLE -> "Ezen a telefonon a rendszer-hitelesítés nem érhető el."
    }

    /** Shows the system prompt; [onResult] gets true only on a successful authentication. Main thread. */
    fun prompt(activity: FragmentActivity, title: String, subtitle: String?, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                // onAuthenticationFailed = one bad attempt; the dialog stays open, so no result yet.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
