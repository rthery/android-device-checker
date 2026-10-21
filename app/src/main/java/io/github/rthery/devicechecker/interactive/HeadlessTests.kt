package io.github.rthery.devicechecker.interactive

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.rthery.devicechecker.checks.Check
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.info
import io.github.rthery.devicechecker.checks.notApplicable
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.checks.warn
import io.github.rthery.devicechecker.compat.Compat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val DEFAULT_STRESS_STEPS = 30
private const val DEFAULT_GNSS_TIMEOUT_S = 180
private const val BLUETOOTH_SCAN_MS = 12_000L
private const val WAV_SAMPLE_RATE = 48_000

/** The adb command that runs headless tests in the foreground, where camera and microphone work. */
fun foregroundCommand(ids: String) = "adb shell am start -n io.github.rthery.devicechecker/.AdbLinkActivity --es run $ids"

/**
 * Guided tests the app can drive alone. They run like checks, from a run started with
 * `am start ... --es run <ids>`. Tests that need a human judgement end as INFO (needs you)
 * with their photos, clips or recordings saved as artifacts for review.
 */
val headlessTests = listOf(
    Check("camera.capture", "Photo on every camera") { photoHeadless() },
    Check("video.record", "Video on every camera") { videoHeadless() },
    Check("hw.camera.hal", "Camera HAL and API coverage") {
        preflight("hw.camera.hal", listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) ?: runCameraHalCoverage()
    },
    Check("camera.stress", "Camera switching stress") { cameraStressHeadless() },
    Check("audio.stress", "Audio churn stress") { audioStressHeadless() },
    Check("audio.input", "Microphones") { micsHeadless() },
    Check("bluetooth.scan", "Bluetooth scan") { bluetoothHeadless() },
    Check("gnss.fix", "GPS fix") { gnssHeadless() },
    Check("integrity.basic", "Play Integrity basic") { integrityHeadless() },
)

object HeadlessRegistry {
    val all: List<Check> = headlessTests
    val byId: Map<String, Check> = all.associateBy { it.id }
}

private suspend fun CheckContext.preflight(testId: String, permissions: List<String>): CheckResult? {
    val missing = ensurePermissions(permissions)
    if (missing.isNotEmpty()) {
        return warn(
            "Missing permission: ${missing.joinToString { it.substringAfterLast('.') }}",
            "Connect Shizuku so the app grants it, or run:\n" + missing.joinToString("\n") { "adb shell pm grant ${context.packageName} $it" },
        )
    }
    if (!isForeground()) {
        return warn("Needs the app in the foreground. Start it with:\n${foregroundCommand(testId)}")
    }
    return null
}

private fun Bitmap.saveJpeg(file: File) {
    FileOutputStream(file).use { compress(Bitmap.CompressFormat.JPEG, 85, it) }
}

private suspend fun CheckContext.photoHeadless(): CheckResult {
    preflight("camera.capture", listOf(Manifest.permission.CAMERA))?.let { return it }
    val dir = artifactsFor("camera.capture")
    val targets = discoverCameras(context.getSystemService(CameraManager::class.java))
    if (targets.isEmpty()) return fail("No camera exposed")
    val artifacts = mutableListOf<String>()
    val lines = targets.map { target ->
        val photo = takePhoto(context, target)
        if (dir != null && photo.jpeg != null) {
            val file = File(dir, "camera${target.id}.jpg").apply { writeBytes(photo.jpeg) }
            writeExifRotation(file, photo.rotation)
            artifacts += relative(file)
        }
        photo.status to "${target.label}: ${photo.note}"
    }
    val details = lines.joinToString("\n") { "${it.first.name}: ${it.second}" }
    return when {
        lines.any { it.first == CheckStatus.FAIL } -> fail("${lines.count { it.first == CheckStatus.FAIL }} camera(s) failed to take a photo", details)
        else -> info("${artifacts.size} photo(s) saved. Look at them, then set the verdict.", details)
    }.copy(artifacts = artifacts)
}

private const val SAME_SCENE_NOTE =
    "\nTo compare with stock, record both at the same spot and light: exposure numbers from different scenes are not comparable."

private suspend fun CheckContext.videoHeadless(): CheckResult {
    preflight("video.record", listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))?.let { return it }
    val dir = artifactsFor("video.record")
    val targets = discoverCameras(context.getSystemService(CameraManager::class.java))
    if (targets.isEmpty()) return fail("No camera exposed")
    val artifacts = mutableListOf<String>()
    val clips = targets.map { target ->
        val clip = recordClip(context, target)
        if (dir != null) {
            clip.file?.takeIf { it.exists() }?.let { source ->
                val copy = File(dir, "camera${target.id}.mp4")
                source.copyTo(copy, overwrite = true)
                source.delete()
                artifacts += relative(copy)
            }
            clip.frame?.let { frame ->
                val still = File(dir, "camera${target.id}-frame.jpg").also(frame::saveJpeg)
                artifacts += relative(still)
            }
        }
        clip
    }
    val details = clips.joinToString("\n") { "${it.status.name}: ${it.label}: ${it.note}" }
    return when {
        clips.any { it.status == CheckStatus.FAIL } -> fail("${clips.count { it.status == CheckStatus.FAIL }} clip(s) failed", details)
        clips.any { it.status == CheckStatus.WARN } -> warn(
            "${clips.count { it.status == CheckStatus.WARN }} clip(s) settle slowly or flicker. Check picture and sound, then set the verdict.",
            details + SAME_SCENE_NOTE,
        )
        else -> info("${clips.size} clip(s) recorded with sound. Check picture and sound, then set the verdict.", details + SAME_SCENE_NOTE)
    }.copy(artifacts = artifacts)
}

private suspend fun CheckContext.cameraStressHeadless(): CheckResult {
    preflight("camera.stress", listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))?.let { return it }
    val steps = options["iterations"] ?: DEFAULT_STRESS_STEPS
    val lines = mutableListOf<Pair<CheckStatus, String>>()
    runCameraStress(context, steps) { status, entry, _ -> lines += status to entry }
    return summarize(lines, "$steps steps and a reopen of every camera without errors")
}

private suspend fun CheckContext.audioStressHeadless(): CheckResult {
    preflight("audio.stress", listOf(Manifest.permission.RECORD_AUDIO))?.let { return it }
    val steps = options["iterations"] ?: DEFAULT_STRESS_STEPS
    val lines = mutableListOf<Pair<CheckStatus, String>>()
    runAudioStress(context.getSystemService(AudioManager::class.java), steps) { status, entry, _ -> lines += status to entry }
    return summarize(lines, "$steps audio steps without errors")
}

private fun summarize(lines: List<Pair<CheckStatus, String>>, passSummary: String): CheckResult {
    val details = lines.joinToString("\n") { "${it.first.name}: ${it.second}" }
    val failed = lines.count { it.first == CheckStatus.FAIL }
    val warned = lines.count { it.first == CheckStatus.WARN }
    return when {
        lines.isEmpty() -> warn("No steps ran")
        failed > 0 -> fail("$failed of ${lines.size} steps failed", details)
        warned > 0 -> warn("$warned clip(s) settle slowly or flicker", details)
        else -> pass(passSummary, details)
    }
}

private suspend fun CheckContext.micsHeadless(): CheckResult {
    preflight("audio.input", listOf(Manifest.permission.RECORD_AUDIO))?.let { return it }
    val audio = context.getSystemService(AudioManager::class.java)
    val devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in TESTABLE_INPUTS }
    if (devices.isEmpty()) return fail("No microphone exposed")
    val dir = artifactsFor("audio.input")
    val artifacts = mutableListOf<String>()
    val lines = devices.mapIndexed { index, device ->
        val recording = record(audio, device) {}
        if (dir != null) {
            val file = File(dir, "mic$index-${device.label().replace(Regex("[^A-Za-z0-9]+"), "_").lowercase()}.wav")
            writeWav(file, recording.samples)
            artifacts += relative(file)
        }
        val status = when {
            recording.slowStart -> CheckStatus.FAIL
            !recording.hasSignal -> CheckStatus.WARN
            else -> CheckStatus.INFO
        }
        status to "${device.label()}: ${recording.describe()}"
    }
    val details = lines.joinToString("\n") { "${it.first.name}: ${it.second}" }
    return when {
        lines.any { it.first == CheckStatus.FAIL } -> fail("A microphone took too long to deliver its first buffer", details)
        lines.any { it.first == CheckStatus.WARN } -> warn("A microphone recorded almost nothing. Speak during the test and run again.", details)
        else -> info("${lines.size} recording(s) saved. Listen to them, then set the verdict.", details)
    }.copy(artifacts = artifacts)
}

/** 16-bit mono PCM WAV. */
private fun writeWav(file: File, samples: ShortArray) {
    val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(WAV_SAMPLE_RATE); putInt(WAV_SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data.size)
    }.array()
    FileOutputStream(file).use {
        it.write(header)
        it.write(data)
    }
}

@SuppressLint("MissingPermission")
private suspend fun CheckContext.bluetoothHeadless(): CheckResult {
    if (!profile.stockHasBluetooth) return notApplicable("Stock has no Bluetooth")
    val missing = ensurePermissions(Compat.bluetoothScanPermissions())
    if (missing.isNotEmpty()) return warn("Missing permission: ${missing.joinToString { it.substringAfterLast('.') }}", missing.joinToString("\n") { "adb shell pm grant ${context.packageName} $it" })
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return fail("No Bluetooth adapter")
    if (!adapter.isEnabled) return warn("Bluetooth is off. Turn it on and run again.")
    val found = linkedMapOf<String, String>()
    scanBluetooth(context, BLUETOOTH_SCAN_MS) { address, info -> found.putIfAbsent(address, info) }
    val details = found.entries.joinToString("\n") { "${it.key} ${it.value}" }
    return if (found.isEmpty()) fail("No devices found in ${BLUETOOTH_SCAN_MS / 1000} s") else pass("${found.size} device(s) found", details)
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private suspend fun CheckContext.gnssHeadless(): CheckResult {
    if (!profile.stockHasGps) return notApplicable("Stock has no GPS")
    preflight("gnss.fix", listOf(Manifest.permission.ACCESS_FINE_LOCATION))?.let { return it }
    val location = context.getSystemService(LocationManager::class.java)
    if (!Compat.isLocationEnabled(context, location)) return warn("Location is off. Turn it on and run again.")
    val timeoutS = options["gnss_timeout"] ?: DEFAULT_GNSS_TIMEOUT_S
    var listed = 0
    var withSignal = 0
    var bestCn0 = 0f
    var ttff = -1
    val started = System.currentTimeMillis()
    val fix = withContext(Dispatchers.Main) {
        val status = object : GnssStatus.Callback() {
            override fun onFirstFix(ttffMillis: Int) {
                ttff = ttffMillis
            }

            override fun onSatelliteStatusChanged(s: GnssStatus) {
                listed = s.satelliteCount
                withSignal = (0 until s.satelliteCount).count { s.getCn0DbHz(it) > 0f }
                bestCn0 = (0 until s.satelliteCount).maxOfOrNull { s.getCn0DbHz(it) } ?: 0f
            }
        }
        var result: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                result = l
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        location.registerGnssStatusCallback(status, Handler(Looper.getMainLooper()))
        location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        try {
            withTimeoutOrNull(timeoutS * 1000L) {
                while (result == null) delay(500)
                result
            }
        } finally {
            location.unregisterGnssStatusCallback(status)
            location.removeUpdates(listener)
        }
    }
    val elapsed = (System.currentTimeMillis() - started) / 1000
    val details = "Elapsed $elapsed s\nSatellites listed $listed, with signal $withSignal, best C/N0 ${"%.1f".format(bestCn0)} dB-Hz\nTTFF ${if (ttff >= 0) "$ttff ms" else "n/a"}"
    return when {
        fix != null -> pass("GPS fix in ${if (ttff >= 0) ttff / 1000 else elapsed} s, accuracy ${fix.accuracy.toInt()} m", details)
        listed > 0 && withSignal == 0 -> warn(
            "No satellite signal in $elapsed s ($listed listed). Expected indoors; run it outdoors to judge.",
            details,
        )
        else -> warn("No fix in $elapsed s. Run it outdoors with a view of the sky.", details)
    }
}
