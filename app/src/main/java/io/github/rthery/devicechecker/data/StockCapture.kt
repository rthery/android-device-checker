package io.github.rthery.devicechecker.data

import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Display
import io.github.rthery.devicechecker.checks.APTX_LIBRARY_COMMAND
import io.github.rthery.devicechecker.checks.BLUETOOTH_DUMP_COMMAND
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.IO_SCHEDULER_COMMAND
import io.github.rthery.devicechecker.checks.WIFI_MAC_COMMAND
import io.github.rthery.devicechecker.checks.extractMac
import io.github.rthery.devicechecker.checks.macFromLinkLocal
import io.github.rthery.devicechecker.checks.parseStackCodecs
import io.github.rthery.devicechecker.checks.withoutMissingAptx
import io.github.rthery.devicechecker.checks.hasSchedulerLines
import io.github.rthery.devicechecker.compat.Compat
import io.github.rthery.devicechecker.compat.Compat.isAliasCompat
import io.github.rthery.devicechecker.compat.Compat.isHardwareCompat
import io.github.rthery.devicechecker.interactive.discoverCameras
import io.github.rthery.devicechecker.profile.DeviceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CPUFREQ_DIRS =
    "dirs=\$(ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null); [ -z \"\$dirs\" ] && dirs=\$(ls -d /sys/devices/system/cpu/cpu[0-9]*/cpufreq)"
private val CAPTURED_PROPS = listOf(
    "ro.product.manufacturer", "ro.product.model", "ro.product.device", "ro.board.platform", "ro.hardware",
    "ro.build.fingerprint", "ro.build.version.release", "ro.build.type", "ro.vendor.build.security_patch",
    "gsm.version.baseband", "ro.sf.lcd_density", "ro.crypto.type", "ro.crypto.state", "ro.frp.pst", "ro.treble.enabled",
)

/** What a stock ROM reports, captured on stock and imported on the custom ROM to compare against. */
@Serializable
data class StockSnapshot(
    val format: Int = 1,
    val capturedAt: Long,
    val device: String,
    val codename: String,
    val android: String,
    val sdk: Int,
    val fingerprint: String,
    val profile: DeviceProfile,
    val cpuFrequencies: List<String> = emptyList(),
    val governors: List<String> = emptyList(),
    val ioSchedulers: List<String> = emptyList(),
    val sensors: List<String> = emptyList(),
    val hardwareCodecs: List<String> = emptyList(),
    val cameras: List<String> = emptyList(),
    val audioOutputs: List<String> = emptyList(),
    val audioInputs: List<String> = emptyList(),
    val charging: Map<String, String> = emptyMap(),
    val features: List<String> = emptyList(),
    val props: Map<String, String> = emptyMap(),
    val kernel: String = "",
    val filesystems: List<String> = emptyList(),
)

val stockJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun sensorKey(type: String, name: String) = "$type: $name"

fun codecKey(encoder: Boolean, mime: String) = "${if (encoder) "enc" else "dec"} $mime"

/** Reads everything it can without a person; Shizuku adds MAC addresses and sysfs nodes. */
suspend fun CheckContext.captureStock(): StockSnapshot {
    val pm = context.packageManager
    val props = CAPTURED_PROPS.associateWith { prop(it) }.filterValues { it.isNotBlank() }

    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val mode = display.mode
    @Suppress("DEPRECATION")
    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()

    val cameraManager = context.getSystemService(CameraManager::class.java)
    val cameraTargets = discoverCameras(cameraManager)
    val cameraLines = cameraTargets.map { target ->
        val physical = Compat.physicalCameraIds(cameraManager.getCameraCharacteristics(target.id))
        "${target.id} ${facingName(target.facing)}${if (target.hidden) " hidden" else ""}${if (physical.isNotEmpty()) " physical=${physical.sorted().joinToString(",")}" else ""}"
    }
    fun sensorCount(facing: Int): Int = cameraTargets.filter { it.facing == facing }.flatMap { target ->
        Compat.physicalCameraIds(cameraManager.getCameraCharacteristics(target.id)).ifEmpty { setOf(target.id) }
    }.toSet().size

    val audio = context.getSystemService(AudioManager::class.java)
    val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.distinct().sorted()

    val sensors = context.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
        .map { sensorKey(it.stringType.substringAfterLast('.'), it.name) }.distinct().sorted()

    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .filter { it.isHardwareCompat() && !it.isAliasCompat() }
        .flatMap { info -> info.supportedTypes.map { codecKey(info.isEncoder, it) } }.distinct().sorted()

    val cpu = read("$CPUFREQ_DIRS; for d in \$dirs; do echo \"\$d min=\$(cat \$d/cpuinfo_min_freq) max=\$(cat \$d/cpuinfo_max_freq)\"; done") { it.contains("max=") }.output
    val governors = read("$CPUFREQ_DIRS; for d in \$dirs; do cat \$d/scaling_available_governors; done") { it.isNotBlank() }.output
    val schedulers = read(IO_SCHEDULER_COMMAND, ::hasSchedulerLines).output
    val charging = read("for f in type real_type current_max input_current_max voltage_max; do v=\$(cat /sys/class/power_supply/usb/\$f 2>/dev/null) && echo \"\$f=\$v\"; done; true") { it.contains("=") }.output
    val filesystems = readFileAsApp("/proc/filesystems") ?: read("cat /proc/filesystems").output.orEmpty()
    val wifiMac = read(WIFI_MAC_COMMAND) { extractMac(it) != null }.output?.let(::extractMac)
        ?: wifiLinkLocalMac(context.getSystemService(ConnectivityManager::class.java)).orEmpty()
    val btMac = (runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        ?: read("settings get secure bluetooth_address") { it.contains(':') }.output)?.trim()?.lowercase()?.takeIf { it != "02:00:00:00:00:00" }.orEmpty()

    val btCodecs = read(BLUETOOTH_DUMP_COMMAND) { it.contains("codecConfigPriorities") }.output?.let(::parseStackCodecs).orEmpty().let { listed ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) withoutMissingAptx(listed, read(APTX_LIBRARY_COMMAND).output.orEmpty()) else listed
    }

    val encryption = context.getSystemService(DevicePolicyManager::class.java).storageEncryptionStatus
    val removable = context.getSystemService(StorageManager::class.java).storageVolumes.any { it.isRemovable }

    val profile = DeviceProfile(
        isPhone = outputs.contains(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        stockHasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        stockHasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
        stockHasBluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
        stockHasGps = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
        stockHasNfc = NfcAdapter.getDefaultAdapter(context) != null,
        stockHasFingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
        stockHasIrBlaster = context.getSystemService(ConsumerIrManager::class.java)?.hasIrEmitter() == true,
        stockHasSdSlot = removable,
        stockHasFrontCamera = cameraTargets.any { it.facing == CameraCharacteristics.LENS_FACING_FRONT },
        stockHasRearCamera = cameraTargets.any { it.facing == CameraCharacteristics.LENS_FACING_BACK },
        stockRearCameraCount = sensorCount(CameraCharacteristics.LENS_FACING_BACK),
        stockFrontCameraCount = sensorCount(CameraCharacteristics.LENS_FACING_FRONT),
        stockHasHdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes,
        stockHasExfat = filesystems.lines().any { it.trim().endsWith("exfat") },
        stockHasForcedEncryption = encryption != DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE && encryption != DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED,
        stockHasHardwareEncryption = props["ro.crypto.type"] != null,
        stockHasFrp = props["ro.frp.pst"] != null,
        stockWidthPx = minOf(mode.physicalWidth, mode.physicalHeight),
        stockHeightPx = maxOf(mode.physicalWidth, mode.physicalHeight),
        stockDensityDpi = props["ro.sf.lcd_density"]?.toIntOrNull() ?: context.resources.displayMetrics.densityDpi,
        stockWifiMac = wifiMac,
        stockBluetoothMac = btMac,
        stockBluetoothCodecs = btCodecs,
    )

    return StockSnapshot(
        capturedAt = System.currentTimeMillis(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        codename = Build.DEVICE,
        android = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        fingerprint = Build.FINGERPRINT,
        profile = profile,
        cpuFrequencies = cpu?.lines()?.map { it.trim().removePrefix("/sys/devices/system/cpu/") }.orEmpty(),
        governors = governors?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.distinct()?.sorted().orEmpty(),
        ioSchedulers = schedulers?.lines()?.filter { hasSchedulerLines(it) }.orEmpty(),
        sensors = sensors,
        hardwareCodecs = codecs,
        cameras = cameraLines,
        audioOutputs = outputs.map(::audioTypeName),
        audioInputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { audioTypeName(it.type) }.distinct().sorted(),
        charging = charging?.lines()?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty(),
        features = pm.systemAvailableFeatures.mapNotNull { it.name }.sorted(),
        props = props,
        kernel = System.getProperty("os.version").orEmpty(),
        filesystems = filesystems.lines().map { it.trim().substringAfterLast('\t').substringAfterLast(' ') }.filter { it.isNotBlank() }.distinct(),
    )
}

private fun facingName(facing: Int?) = when (facing) {
    CameraCharacteristics.LENS_FACING_BACK -> "rear"
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "unknown"
}

fun audioTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> "type $type"
}

/** Apps cannot read the Wi-Fi MAC since Android 6, but an EUI-64 link-local address on wlan0 still encodes it. */
private fun wifiLinkLocalMac(connectivity: ConnectivityManager): String? = connectivity.allNetworks
    .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
    .firstNotNullOfOrNull { macFromLinkLocal(it.address.hostAddress.orEmpty()) }
