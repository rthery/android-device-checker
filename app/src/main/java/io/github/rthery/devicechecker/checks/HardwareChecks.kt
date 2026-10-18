package io.github.rthery.devicechecker.checks

import io.github.rthery.devicechecker.data.audioTypeName
import io.github.rthery.devicechecker.data.codecKey
import io.github.rthery.devicechecker.data.sensorKey
import io.github.rthery.devicechecker.compat.BiometricStatus
import io.github.rthery.devicechecker.compat.Biometrics
import io.github.rthery.devicechecker.compat.Compat.isHardwareCompat
import io.github.rthery.devicechecker.compat.Compat.isAliasCompat
import io.github.rthery.devicechecker.compat.Compat
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.nfc.NfcAdapter
import android.os.storage.StorageManager
import android.telephony.TelephonyManager
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val SENSOR_EVENT_TIMEOUT_MS = 3_000L

/** PSDS is the Android 11 name for Qualcomm XTRA, so older gps.conf files only have XTRA_SERVER keys. */
private val PSDS_KEYS = listOf("LONGTERM_PSDS_SERVER", "XTRA_SERVER")
private const val HIDDEN_CAMERA_PROBE_MAX_ID = 15

private val CORE_SENSORS = mapOf(
    Sensor.TYPE_ACCELEROMETER to "Accelerometer",
    Sensor.TYPE_GYROSCOPE to "Gyroscope",
    Sensor.TYPE_PROXIMITY to "Proximity",
    Sensor.TYPE_LIGHT to "Light",
)

val hardwareChecks = listOf(
    Check("audio.devices", "Audio devices") {
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtInMics = inputs.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val details = "Outputs: ${outputs.joinToString { audioTypeName(it) }}\nInputs: ${inputs.joinToString { audioTypeName(it.type) }}"
        when {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER !in outputs -> fail("No built-in speaker exposed", details)
            profile.isPhone && AudioDeviceInfo.TYPE_BUILTIN_EARPIECE !in outputs -> fail("No earpiece exposed on a phone", details)
            builtInMics == 0 -> fail("No built-in microphone exposed", details)
            else -> compareWithStock(
                "audio devices",
                stock?.let { it.audioOutputs.map { name -> "out $name" } + it.audioInputs.map { name -> "in $name" } },
                outputs.map { "out ${audioTypeName(it)}" } + inputs.map { "in ${audioTypeName(it.type)}" }.distinct(),
                details,
            )?.takeIf { it.status == CheckStatus.FAIL }
                ?: info("Speaker, ${if (profile.isPhone) "earpiece, " else ""}$builtInMics mic(s) exposed. Playback still needs a listen test.", details)
        }
    },

    Check("telephony", "Telephony stack") {
        if (!profile.stockHasTelephony) return@Check notApplicable("Stock has no RIL")
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return@Check fail("FEATURE_TELEPHONY is missing")
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val subsystems = read("for d in /sys/bus/msm_subsys/devices/*; do echo \"\$(cat \$d/name 2>/dev/null)=\$(cat \$d/state 2>/dev/null)\"; done; true") { it.contains("=") }.output
        val radio = android.os.Build.getRadioVersion().orEmpty()
        val details = "Phone type: ${telephony.phoneType}\nSIM state: ${telephony.simState}\nRIL: ${prop("gsm.version.ril-impl").ifEmpty { "unknown" }}\n" +
            "Baseband: ${radio.ifEmpty { "empty" }}\nSubsystems: ${subsystems?.lines()?.joinToString() ?: "needs Shizuku"}"
        val modemOffline = subsystems?.lines()?.any { it.startsWith("modem=") && !it.endsWith("ONLINE") } == true
        when {
            telephony.phoneType == TelephonyManager.PHONE_TYPE_NONE -> fail("Phone type is NONE", details)
            modemOffline -> fail("Modem subsystem is not ONLINE", details)
            radio.isEmpty() -> fail("No baseband version: the RIL is not talking to the modem", details)
            telephony.simState != TelephonyManager.SIM_STATE_READY -> warn("Telephony present, no ready SIM. Insert one to test calls and data.", details)
            else -> info("Telephony present, SIM ready. Test a call and mobile data by hand.", details)
        }
    },

    Check("wifi.present", "Wi-Fi feature") {
        featureMatchesStock(PackageManager.FEATURE_WIFI, profile.stockHasWifi, "Wi-Fi")
    },

    Check("bluetooth.present", "Bluetooth feature") {
        featureMatchesStock(PackageManager.FEATURE_BLUETOOTH, profile.stockHasBluetooth, "Bluetooth")
    },

    Check("gnss.present", "GNSS") {
        if (!profile.stockHasGps) return@Check notApplicable("Stock has no GPS")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) return@Check fail("FEATURE_LOCATION_GPS is missing")
        val location = context.getSystemService(LocationManager::class.java)
        val config = listOf("/system/etc/gps_debug.conf", "/vendor/etc/gps.conf", "/system/etc/gps.conf")
            .firstNotNullOfOrNull { path -> readFileAsApp(path)?.let { path to it } }
        val psds = config?.second?.lines()?.filter { line -> PSDS_KEYS.any { line.trim().startsWith(it) } }.orEmpty()
        val details = Compat.gnssHardware(location) + "\n" +
            "Config: ${config?.first ?: "not readable"}\n${psds.joinToString("\n").ifEmpty { "No LONGTERM_PSDS_SERVER or XTRA_SERVER keys" }}"
        if (config != null && psds.isEmpty()) warn("GNSS declared, but the config has no PSDS servers (slow or no first fix)", details)
        else info("GNSS declared. A real fix test is still needed.", details)
    },

    Check("nfc.present", "NFC") {
        if (!profile.stockHasNfc) return@Check notApplicable("Stock has no NFC")
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return@Check fail("No NFC adapter")
        info("NFC adapter present (${if (adapter.isEnabled) "enabled" else "disabled"}). Read a tag to confirm.")
    },

    Check("fingerprint.present", "Fingerprint") {
        if (!profile.stockHasFingerprint) return@Check notApplicable("Stock has no fingerprint sensor")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return@Check fail("FEATURE_FINGERPRINT is missing")
        val dump = read("dumpsys fingerprint | grep -iE 'HAL deaths|isUdfps|isPowerbuttonFps' | head -5; logcat -d | grep -cE 'HAL ID: 0|ION_memalloc failed'") { true }.output
        val halZero = dump?.lines()?.lastOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } == true
        if (halZero) return@Check fail("Fingerprint HAL failed to open (HAL ID 0 or ION allocation failure in the log)", dump.orEmpty(), Access.SHIZUKU)
        val (status, code) = Biometrics.status(context)
        when (status) {
            BiometricStatus.READY -> pass("Biometric enrolled and usable", dump.orEmpty())
            BiometricStatus.NONE_ENROLLED -> warn("Sensor present, no fingerprint enrolled. Enroll one and run again.")
            BiometricStatus.HARDWARE_UNAVAILABLE -> fail("Fingerprint hardware unavailable")
            BiometricStatus.NO_HARDWARE -> fail("No fingerprint hardware detected")
            BiometricStatus.OTHER -> fail("Biometric status $code")
        }
    },

    Check("ir.present", "IR blaster") {
        if (!profile.stockHasIrBlaster) return@Check notApplicable("Stock has no IR blaster")
        val ir = context.getSystemService(ConsumerIrManager::class.java)
        if (ir?.hasIrEmitter() == true) {
            info("IR emitter present", "Carrier ranges: ${ir.carrierFrequencies?.joinToString { "${it.minFrequency}-${it.maxFrequency}" }}")
        } else {
            fail("No IR emitter")
        }
    },

    Check("sd.volumes", "SD card") {
        if (!profile.stockHasSdSlot) return@Check notApplicable("Stock has no SD slot")
        val storage = context.getSystemService(StorageManager::class.java)
        val removable = storage.storageVolumes.filter { it.isRemovable }
        if (removable.isEmpty()) warn("No removable volume mounted. Insert a card and run again.")
        else info("Removable volume(s): ${removable.joinToString { "${it.getDescription(context)} (${it.state})" }}. Copy a file to confirm.")
    },

    Check("sensors.core", "Core sensors deliver events") {
        val process = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return@Check warn("Android withholds sensor events from background apps. Run the checks from the app.")
        }
        val sensors = context.getSystemService(SensorManager::class.java)
        val lines = mutableListOf<String>()
        var failed = false
        for ((type, name) in CORE_SENSORS) {
            val sensor = sensors.getDefaultSensor(type)
            if (sensor == null) {
                lines += "$name: not present"
                continue
            }
            val gotEvent = awaitSensorEvent(sensors, sensor)
            if (!gotEvent) failed = true
            lines += "$name: ${sensor.name} (${sensor.vendor}) ${if (gotEvent) "OK" else "no event in ${SENSOR_EVENT_TIMEOUT_MS / 1000}s"}"
        }
        val details = lines.joinToString("\n")
        if (failed) fail("A present sensor sent no events", "Proximity and light only report on change. Wave a hand over them and run again.\n$details")
        else pass("Every present core sensor sends events", details)
    },

    Check("sensors.list", "All sensors") {
        val sensors = context.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
        val details = sensors.joinToString("\n") { "${it.stringType.substringAfterLast('.')}: ${it.name} (${it.vendor})" }
        compareWithStock("sensors", stock?.sensors, sensors.map { sensorKey(it.stringType.substringAfterLast('.'), it.name) }, details)
            ?: info("${sensors.size} sensors. Compare with stock, or import a stock capture.", details)
    },

    Check("camera.list", "Cameras") {
        val cameras = context.getSystemService(CameraManager::class.java)
        val listed = cameras.cameraIdList.toList()
        val hidden = (0..HIDDEN_CAMERA_PROBE_MAX_ID).map(Int::toString).filter { id ->
            id !in listed && runCatching { cameras.getCameraCharacteristics(id) }.isSuccess
        }
        val all = listed + hidden
        val facing = all.associateWith { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
        val physical = all.associateWith { Compat.physicalCameraIds(cameras.getCameraCharacteristics(it)) }
        val rearCount = sensorIds(all, facing, physical, CameraCharacteristics.LENS_FACING_BACK).size
        val frontCount = sensorIds(all, facing, physical, CameraCharacteristics.LENS_FACING_FRONT).size
        val details = all.joinToString("\n") { id ->
            val tag = if (id in hidden) " (hidden)" else ""
            val sub = physical[id].orEmpty().takeIf { it.isNotEmpty() }?.let { " physical=${it.joinToString()}" }.orEmpty()
            "id $id$tag: ${facingName(facing[id])}$sub"
        }
        val problems = buildList {
            if (profile.stockHasRearCamera && rearCount == 0) add("no rear camera")
            if (profile.stockHasFrontCamera && frontCount == 0) add("no front camera")
            profile.stockRearCameraCount?.let { if (rearCount < it) add("$rearCount rear cameras, stock has $it") }
            profile.stockFrontCameraCount?.let { if (frontCount < it) add("$frontCount front cameras, stock has $it") }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString().replaceFirstChar(Char::uppercase), details)
        else info("$rearCount rear, $frontCount front camera sensor(s). Take photos with each to confirm.", details)
    },

    Check("codecs.hardware", "Hardware codecs") {
        withContext(Dispatchers.IO) {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isHardwareCompat() && !it.isAliasCompat() }
            val broken = codecs.filter { info ->
                runCatching { MediaCodec.createByCodecName(info.name).release() }.isFailure
            }
            val details = codecs.joinToString("\n") { codec ->
                "${if (codec.isEncoder) "enc" else "dec"} ${codec.supportedTypes.joinToString()}: ${codec.name}${if (codec in broken) " FAILED" else ""}"
            }
            when {
                codecs.isEmpty() -> fail("No hardware codecs exposed")
                broken.isNotEmpty() -> fail("${broken.size} hardware codec(s) fail to instantiate", details)
                else -> compareWithStock("hardware codec formats", stock?.hardwareCodecs, codecs.flatMap { c -> c.supportedTypes.map { codecKey(c.isEncoder, it) } }, details)
                    ?: info("${codecs.size} hardware codecs instantiate. Compare the list with stock, or import a stock capture.", details)
            }
        }
    },

    Check("display.resolution", "Display resolution") {
        val mode = defaultDisplay().mode
        val actual = sortedPair(mode.physicalWidth, mode.physicalHeight)
        val width = profile.stockWidthPx
        val height = profile.stockHeightPx
        val modes = defaultDisplay().supportedModes.joinToString("\n") { "${it.physicalWidth}x${it.physicalHeight} @ ${"%.1f".format(it.refreshRate)}Hz" }
        if (width == null || height == null) return@Check warn("Set the stock resolution in the device profile", "Current: ${actual.first}x${actual.second}\n$modes")
        if (actual == sortedPair(width, height)) pass("${actual.first}x${actual.second} matches stock", modes)
        else fail("${actual.first}x${actual.second}, stock is ${width}x$height", modes)
    },

    Check("display.density", "Display density") {
        val density = prop("ro.sf.lcd_density").toIntOrNull()
        val stock = profile.stockDensityDpi
        when {
            density == null -> warn("ro.sf.lcd_density is not set")
            stock == null -> warn("Set the stock density in the device profile", "Current: $density dpi")
            density == stock -> pass("$density dpi matches stock")
            else -> warn("$density dpi, stock is $stock dpi")
        }
    },

    Check("display.hdr", "HDR10") {
        if (!profile.stockHasHdr10) return@Check notApplicable("Stock has no HDR10")
        @Suppress("DEPRECATION")
        val types = defaultDisplay().hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in types) pass("HDR10 advertised", "HDR types: $types")
        else fail("HDR10 not advertised", "HDR types: $types")
    },
)

private fun CheckContext.featureMatchesStock(feature: String, stockHas: Boolean, name: String): CheckResult {
    if (!stockHas) return notApplicable("Stock has no $name")
    return if (context.packageManager.hasSystemFeature(feature)) info("$name feature declared. Connect to confirm it works.")
    else fail("$name feature is missing")
}

private fun CheckContext.defaultDisplay(): Display =
    context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

private fun sortedPair(a: Int, b: Int) = minOf(a, b) to maxOf(a, b)

/** Counts sensors, not logical cameras: a logical multi-camera counts as its physical cameras. */
private fun sensorIds(ids: List<String>, facing: Map<String, Int?>, physical: Map<String, Set<String>>, lensFacing: Int): Set<String> =
    ids.filter { facing[it] == lensFacing }.flatMap { id -> physical[id].orEmpty().ifEmpty { setOf(id) } }.toSet()

private fun facingName(facing: Int?) = when (facing) {
    CameraCharacteristics.LENS_FACING_BACK -> "rear"
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "unknown"
}

private suspend fun awaitSensorEvent(sensors: SensorManager, sensor: Sensor): Boolean =
    withTimeoutOrNull(SENSOR_EVENT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sensors.unregisterListener(this)
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
        }
    } ?: false
