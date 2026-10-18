package io.github.rthery.devicechecker.compat

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

enum class BiometricStatus { READY, NONE_ENROLLED, HARDWARE_UNAVAILABLE, NO_HARDWARE, OTHER }

interface BiometricListener {
    fun onMatch()
    fun onReject()
    fun onError(message: String)
}

/** Strong biometrics through BiometricManager on 10+, the fingerprint service before. */
object Biometrics {

    @Suppress("DEPRECATION")
    fun status(context: Context): Pair<BiometricStatus, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val code = context.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            return map(code) to code
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val code = context.getSystemService(BiometricManager::class.java).canAuthenticate()
            return map(code) to code
        }
        val fingerprint = context.getSystemService(FingerprintManager::class.java) ?: return BiometricStatus.NO_HARDWARE to -1
        return when {
            !fingerprint.isHardwareDetected -> BiometricStatus.NO_HARDWARE to -1
            !fingerprint.hasEnrolledFingerprints() -> BiometricStatus.NONE_ENROLLED to -1
            else -> BiometricStatus.READY to 0
        }
    }

    private fun map(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
        else -> BiometricStatus.OTHER
    }

    /** Before Android 9 there is no system prompt: the caller asks the tester to touch the sensor. */
    val hasSystemPrompt: Boolean get() = Build.VERSION.SDK_INT >= 28

    @Suppress("DEPRECATION")
    fun authenticate(context: Context, title: String, listener: BiometricListener): CancellationSignal {
        val cancel = CancellationSignal()
        val executor = ContextCompat.getMainExecutor(context)
        if (Build.VERSION.SDK_INT >= 28) {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setNegativeButton("Cancel", executor) { _, _ -> listener.onError("Cancelled") }
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            builder.build().authenticate(cancel, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = listener.onMatch()
                override fun onAuthenticationFailed() = listener.onReject()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = listener.onError("Error $errorCode: $errString")
            })
        } else {
            val fingerprint = context.getSystemService(FingerprintManager::class.java)
            if (fingerprint == null) {
                listener.onError("No fingerprint service")
                return cancel
            }
            fingerprint.authenticate(null, cancel, 0, object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) = listener.onMatch()
                override fun onAuthenticationFailed() = listener.onReject()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = listener.onError("Error $errorCode: $errString")
            }, null)
        }
        return cancel
    }
}
