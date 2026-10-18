package io.github.rthery.devicechecker.compat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.view.Surface
import java.util.concurrent.Executor

/** Version-guarded wrappers so the app runs from Android 8.0 (API 26). */
object Compat {

    val sdk: Int get() = Build.VERSION.SDK_INT

    fun AudioDeviceInfo.addressCompat(): String = if (Build.VERSION.SDK_INT >= 28) address else ""

    /** Routes voice-communication audio to an earpiece or SCO device; returns the undo action. */
    @Suppress("DEPRECATION")
    fun routeCommunication(audio: AudioManager, device: AudioDeviceInfo): () -> Unit {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            audio.setCommunicationDevice(device)
            return {
                audio.clearCommunicationDevice()
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
        val wasSpeaker = audio.isSpeakerphoneOn
        val sco = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        audio.isSpeakerphoneOn = false
        if (sco) audio.startBluetoothSco()
        return {
            if (sco) audio.stopBluetoothSco()
            audio.isSpeakerphoneOn = wasSpeaker
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    fun microphones(audio: AudioManager): List<String> = if (Build.VERSION.SDK_INT >= 28) {
        runCatching { audio.microphones.map { "${it.description} (${it.address}) location=${it.location}" } }.getOrDefault(emptyList())
    } else emptyList()

    fun activeMicrophones(recorder: AudioRecord): String? = if (Build.VERSION.SDK_INT >= 28) {
        runCatching { recorder.activeMicrophones.joinToString { "${it.description} (${it.address})" } }.getOrNull()?.ifBlank { null }
    } else null

    @Suppress("DEPRECATION")
    fun newMediaRecorder(context: Context): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

    @Suppress("DEPRECATION")
    fun createSession(device: CameraDevice, surfaces: List<Surface>, handler: Handler, executor: Executor, callback: CameraCaptureSession.StateCallback) {
        if (Build.VERSION.SDK_INT >= 28) {
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, surfaces.map(::OutputConfiguration), executor, callback))
        } else {
            device.createCaptureSession(surfaces, callback, handler)
        }
    }

    fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) characteristics.physicalCameraIds else emptySet()

    fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, size: Int): Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
    } else {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
            val scale = size.toFloat() / maxOf(frame.width, frame.height)
            Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
        }
    }

    /** Before API 29 there is no flag, so software codecs are told apart by their well-known name prefixes. */
    fun MediaCodecInfo.isHardwareCompat(): Boolean = if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else {
        val lower = name.lowercase()
        !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.") && !lower.contains(".sw.") && !lower.startsWith("omx.ffmpeg")
    }

    fun MediaCodecInfo.isAliasCompat(): Boolean = Build.VERSION.SDK_INT >= 29 && isAlias

    fun gnssHardware(location: LocationManager): String =
        if (Build.VERSION.SDK_INT >= 28) "Hardware: ${location.gnssHardwareModelName ?: "unknown"}\nYear: ${location.gnssYearOfHardware}" else "Hardware: unknown (needs Android 9)"

    @Suppress("DEPRECATION")
    fun isLocationEnabled(context: Context, location: LocationManager): Boolean = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF

    enum class KeySecurity { STRONGBOX, TEE, SOFTWARE, UNKNOWN }

    @Suppress("DEPRECATION")
    fun keySecurity(info: KeyInfo): KeySecurity = if (Build.VERSION.SDK_INT >= 31) {
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurity.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurity.TEE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurity.SOFTWARE
            else -> KeySecurity.UNKNOWN
        }
    } else if (info.isInsideSecureHardware) KeySecurity.TEE else KeySecurity.SOFTWARE

    /** Runtime permissions for a Bluetooth scan: the nearby-devices pair from 12, location before. */
    fun bluetoothScanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("InlinedApi")
    val bleHeadsetType: Int = AudioDeviceInfo.TYPE_BLE_HEADSET
}
